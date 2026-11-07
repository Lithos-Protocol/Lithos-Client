package api

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable}
import api.models._
import com.google.gson.JsonElement
import configs.{NodeContext, OracleConfig, OracleFeedConfig}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeInfo, Paging, SortDirection}
import node.rest.{NodeHttp, NodeHttpConfig, RestNodeApi}
import okhttp3.OkHttpClient
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import play.api.Configuration
import scorex.crypto.hash.Blake2b256

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/** Public, confirmed data from this client's node. No explorer, wallet, signing or background polling. */
@Singleton
class OraclesApiImpl @Inject()(node: NodeContext, config: Configuration) extends OraclesApi {
  private val settings = OracleConfig(config)
  private def feeds = settings.feeds.filter(_.network == node.getNetwork.name())
  private lazy val httpConfig = NodeHttpConfig(node.getNodeUrl, Option(node.getNodeKey).filter(_.nonEmpty),
    connectTimeoutMs = settings.readTimeoutMs, readTimeoutMs = settings.readTimeoutMs,
    callTimeoutMs = settings.readTimeoutMs, maxResponseBytes = 8 * 1024 * 1024)
  private lazy val httpClient = new OkHttpClient.Builder()
    .connectTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS).readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS).build()

  protected def createApi(deadline: Long): NodeApi = new RestNodeApi(new NodeHttp(httpConfig, httpClient) {
    private def bounded[A](f: => Try[A]): Try[A] =
      if (System.nanoTime() >= deadline) Failure(new java.util.concurrent.TimeoutException("oracle read budget exceeded")) else f
    override def getJson(path: String, params: Seq[(String, String)]): Try[JsonElement] = bounded(super.getJson(path, params))
    override def getJsonOpt(path: String, params: Seq[(String, String)]): Try[Option[JsonElement]] = bounded(super.getJsonOpt(path, params))
  })

  override def listFeeds(): OracleFeeds = OracleFeeds(node.getNetwork.name(), feeds.map(view))

  override def getFeed(feedId: String, offset: Option[Int], limit: Option[Int], atBlockId: Option[String]): OracleState = {
    val feed = feeds.find(_.id == feedId).getOrElse(throw LithosNotFound(s"no configured oracle feed '$feedId' on ${node.getNetwork.name()}"))
    val from = offset.getOrElse(0)
    val size = limit.getOrElse(settings.defaultHistoryLimit)
    if (from < 0 || from > 1000000) throw LithosBadRequest("'offset' must be in 0..1000000")
    if (size < 0 || size > OracleConfig.MaxHistoryLimit) throw LithosBadRequest(s"'limit' must be in 0..${OracleConfig.MaxHistoryLimit}; 0 omits history")
    if (atBlockId.exists(!_.matches("[0-9a-fA-F]{64}"))) throw LithosBadRequest("'atBlockId' must be a block id")
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.readBudgetMs)
    val api = createApi(deadline)
    val before = tip(read(api.info(), "node tip"))
    if (atBlockId.exists(!_.equalsIgnoreCase(before._2)))
      throw LithosStateChanged("the requested oracle snapshot is no longer the node tip; restart history pagination")
    val index = read(api.indexedHeight(), "node index height")
    if (index.indexedHeight != before._1 || index.fullHeight != before._1)
      throw LithosUnavailable("the node's index has not caught up to the full chain")
    val live = read(api.unspentBoxesByTokenId(feed.poolNft, Paging(0, 2), SortDirection.Desc, MempoolOptions.ConfirmedOnly), "oracle pool")
    if (live.size != 1 || live.head.isSpent) throw LithosUnavailable("the oracle NFT does not resolve to one confirmed unspent pool box")
    val currentBox = live.head
    val current = point(feed, currentBox, before._1)
      .getOrElse(throw LithosUnavailable("the oracle pool has not published a positive rate yet"))
    val authoritative = read(api.boxById(current.boxId), "oracle UTXO")
    if (!authoritative.contains(currentBox.box)) throw LithosUnavailable("the indexed oracle pool is not the authoritative unspent box")
    val page = if (size == 0) None else Some(read(api.boxesByTokenId(feed.poolNft, Paging(from, size)), "oracle history"))
    page.foreach { p =>
      if (p.total < 0 || (from < p.total && p.items.isEmpty) ||
        p.items.size > size || p.items.map(_.boxId).distinct.size != p.items.size)
        throw LithosUnavailable("the oracle history page is inconsistent")
      if (from == 0 && p.items.headOption.forall(_.boxId != current.boxId))
        throw LithosUnavailable("the oracle history and current pool do not agree")
      p.items.sliding(2).filter(_.size == 2).foreach { pair =>
        if (!pair(1).spentTransactionId.contains(pair.head.box.transactionId))
          throw LithosUnavailable("the oracle history is not a contiguous NFT lineage")
      }
    }
    val history = page.toSeq.flatMap(_.items).flatMap(b => point(feed, b, before._1))
    val times = timestamps(api, (history :+ current).map(_.inclusionHeight), deadline)
    def dated(p: OraclePoint) = p.copy(timestamp = times.get(p.inclusionHeight))
    if (tip(read(api.info(), "node tip")) != before) throw LithosStateChanged("the chain changed while reading the oracle; retry the snapshot")
    if (System.nanoTime() >= deadline) throw LithosUnavailable("the oracle read budget was exceeded")
    val next = page.flatMap(p => if (p.items.nonEmpty && from.toLong + p.items.size < p.total) Some(from + p.items.size) else None)
    val age = before._1 - current.creationHeight
    OracleState(view(feed), before._1, before._2, index.indexedHeight, dated(current), age, age > feed.maxAgeBlocks,
      history.map(dated), from, size, page.map(_.total), next,
      (history :+ current).forall(p => times.contains(p.inclusionHeight)))
  }

  private def point(feed: OracleFeedConfig, indexed: IndexedBox, height: Int): Option[OraclePoint] = {
    val box = indexed.box
    val singleton = box.assets.headOption.exists(a => a.tokenId == feed.poolNft && a.amount == 1L) &&
      box.assets.count(_.tokenId == feed.poolNft) == 1
    val hash = Try(Hex.toHexString(Blake2b256.hash(Hex.decode(box.ergoTree)))).toOption
    if (!singleton || !hash.contains(feed.ergoTreeHash) || box.creationHeight < 0 ||
      indexed.inclusionHeight <= 0 || indexed.inclusionHeight > height || box.creationHeight > indexed.inclusionHeight)
      throw LithosUnavailable("the oracle box does not match its configured identity, script or confirmed height")
    def register(r: Int, tpe: ErgoType[_]): Option[Any] = box.additionalRegisters.get(r)
      .flatMap[ErgoValue[_]](h => Try(ErgoValue.fromHex(h)).toOption).filter(_.getType == tpe).map(_.getValue)
    val raw = register(4, ErgoType.longType()).collect { case n: java.lang.Long => n.longValue }
      .getOrElse(throw LithosUnavailable("oracle R4 must be a Long"))
    val epoch = register(5, ErgoType.integerType()).collect { case n: java.lang.Integer => n.intValue }
      .getOrElse(throw LithosUnavailable("oracle R5 must be an Int"))
    if (raw == 0 && epoch == 0) return None // The bootstrap box predates the first published price.
    if (raw <= 0 || epoch < 0) throw LithosUnavailable("the oracle published an invalid rate or epoch")
    val numerator = BigInt(10).pow(feed.baseDecimals)
    val price = new java.math.BigDecimal(numerator.bigInteger).divide(java.math.BigDecimal.valueOf(raw),
      18, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros.toPlainString
    Some(OraclePoint(box.boxId, box.transactionId, box.creationHeight, indexed.inclusionHeight, epoch,
      raw.toString, price, numerator.toString, raw.toString, hash.get, None))
  }

  private def timestamps(api: NodeApi, heights: Seq[Int], deadline: Long): Map[Int, Long] =
    heights.distinct.groupBy(_ / 500).valuesIterator.flatMap { group =>
      if (System.nanoTime() >= deadline) Iterator.empty
      else api.chainSlice(Some(group.min - 1), Some(group.max)).toOption.toSeq.flatten.iterator
        .filter(h => group.contains(h.height)).map(h => h.height -> h.timestamp)
    }.toMap

  private def tip(info: NodeInfo): (Int, String) = (
    info.fullHeight.getOrElse(throw LithosUnavailable("the node has no full chain height")),
    info.bestFullHeaderId.getOrElse(throw LithosUnavailable("the node has no full block id")))
  private def read[A](result: Try[A], what: String): A = result match {
    case Success(value) => value
    case Failure(e) => throw LithosUnavailable(s"could not read $what: ${e.getMessage}", e)
  }
  private def view(f: OracleFeedConfig): OracleFeed = OracleFeed(f.id, f.name, f.network, "oracle-pool-v2", f.poolNft,
    f.ergoTreeHash, f.baseAsset, f.quoteAsset, s"${f.baseUnit}/${f.quoteAsset}", s"${f.quoteAsset}/${f.baseAsset}",
    f.baseDecimals, f.maxAgeBlocks)
}
