package stats

import api.DexHistory
import api.models.{LDPriceHistory, LDPricePoint, LDRecentActivity}
import com.google.gson.JsonElement
import configs.{DexStatsConfig, NodeContext}
import lithosdex.LDHelpers
import node.NodeApi
import node.model.{MempoolOptions, NodeInfo, Paging, SortDirection}
import node.rest.{NodeHttp, NodeHttpConfig, RestNodeApi}
import okhttp3.OkHttpClient
import transactions.batching.lithosdex.LDBoxes

import java.util.concurrent.{TimeUnit, TimeoutException}
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Try}

trait DexStatsSource {
  def read(): DexStatsData
}

/** A verified pool besides ERG:LIT whose graphs are kept, read from `since`, the height it joined the list. */
final case class DexStatsTarget(lineage: LDBoxes.PoolLineage, tokenX: Option[String], tokenY: String, since: Int)

/** Reads one verified pool other than ERG:LIT, from the height it joined the list. */
trait PoolStatsSource {
  def read(target: DexStatsTarget): DexStatsData
}

/** Reads public node data only; no wallet, signing, or BlockchainContext is required. */
@Singleton
class NodeDexStatsSource @Inject()(node: NodeContext, cache: StatsCache) extends DexStatsSource with PoolStatsSource {
  private val settings: DexStatsConfig = cache.settings.dex
  private lazy val httpConfig = NodeHttpConfig(node.getNodeUrl, Option(node.getNodeKey).filter(_.nonEmpty),
    connectTimeoutMs = settings.readTimeoutMs, readTimeoutMs = settings.readTimeoutMs,
    callTimeoutMs = settings.readTimeoutMs, maxResponseBytes = 8 * 1024 * 1024)
  private lazy val httpClient = new OkHttpClient.Builder()
    .connectTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS).build()

  protected def createApi(deadline: Long): NodeApi = {
    def bounded[A](read: => Try[A]): Try[A] =
      if (System.nanoTime() - deadline >= 0) Failure(new TimeoutException("DEX refresh budget exceeded")) else read
    new RestNodeApi(new NodeHttp(httpConfig, httpClient) {
      override def getJson(path: String, params: Seq[(String, String)]): Try[JsonElement] =
        bounded(super.getJson(path, params))
      override def getJsonOpt(path: String, params: Seq[(String, String)]): Try[Option[JsonElement]] =
        bounded(super.getJsonOpt(path, params))
      override def postJson(path: String, body: String, params: Seq[(String, String)]): Try[JsonElement] =
        bounded(super.postJson(path, body, params))
    })
  }

  private def tip(info: NodeInfo): (Int, String) =
    (info.fullHeight.getOrElse(throw new IllegalStateException("node has no full height")),
      info.bestFullHeaderId.filter(_.nonEmpty).getOrElse(throw new IllegalStateException("node has no full block id")))

  override def read(): DexStatsData = {
    val network = node.getNetwork
    readPool(LDBoxes.PoolLineage.canonical(network), None, LDHelpers.getTokenY(network).toString, None)
  }

  override def read(target: DexStatsTarget): DexStatsData =
    readPool(target.lineage, target.tokenX, target.tokenY, Some(target.since))

  /**
   * One pool's graphs. With `since`, nothing before that height is read: the window starts there, and the data carries
   * it as `collectedFrom` so every graph can report the range before it as not collected.
   */
  private def readPool(lineage: LDBoxes.PoolLineage, tokenX: Option[String], tokenY: String,
                       since: Option[Int]): DexStatsData = {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.refreshBudgetMs)
    val api = createApi(deadline)
    val (height, blockId) = tip(api.info().get)
    val index = api.indexedHeight().get
    require(index.indexedHeight == height && index.fullHeight == height, "node index has not caught up to the full chain")
    val nft = lineage.nft
    val tree = lineage.tree
    val lo = math.max(math.max(0, height - LDPriceHistory.Ranges.values.max), since.getOrElse(0))
    val (history, complete) = NodeDexStatsSource.history(api, nft, tree, lo, settings.historyPages, lineage.tokenToken)
    require(history.nonEmpty, "no indexed LithosDex pool history is available")
    val live = api.unspentBoxesByTokenId(nft, Paging(0, 20), SortDirection.Desc, MempoolOptions.WithMempool).get
      .filter(b => b.ergoTree == tree && b.assets.headOption.exists(a => a.tokenId == nft && a.amount == 1L))
    require(live.size == 1, "could not resolve a unique live LithosDex pool")
    val pool = LDBoxes.readSnapshot(live.head, lineage.tokenToken)
      .getOrElse(throw new IllegalStateException("malformed live pool"))
    def decimalsOf(token: String): Int = {
      val d = api.tokenById(token).get.map(_.decimals).getOrElse(0)
      require(d >= 0 && d <= 255, "invalid token decimals")
      d
    }
    val decimals = decimalsOf(tokenY)
    val decimalsX = tokenX.map(decimalsOf).getOrElse(9)
    val current = LDPricePoint(height, None, DexHistory.spotPrice(pool.reservesX, pool.reservesY, decimals, decimalsX),
      pool.reservesX.toString, pool.reservesY.toString)
    val transitions = LDBoxes.recentTransitions(lineage, api, history, LDRecentActivity.MaxLimit)
    // Before `since` nothing was read, so a range reaching back past it is partial however much the walk saw
    val prices = LDPriceHistory.Ranges.map { case (name, blocks) =>
      name -> DexHistory.price(history, since.forall(_ <= height - blocks) &&
        (complete || history.head.height < height - blocks), current, decimals, Some(name), None, _ => Map.empty, decimalsX)
    }
    val fees = DexHistory.fees(history, complete, lo, height, 720, _ => Map.empty)
    val wanted = (prices.toVector.sortBy(_._1).flatMap(_._2.history.map(_.height)) ++
      fees.history.map(_.height) ++ transitions.flatMap(_.height)).distinct.take(settings.timestampLookups)
    val times = LDBoxes.timestampsAt(api, wanted)
    require(tip(api.info().get) == (height -> blockId), "chain changed during DEX refresh")
    if (System.nanoTime() - deadline >= 0) throw new TimeoutException("DEX refresh budget exceeded")
    DexStatsData(height, blockId, lo, history, complete, current, decimals, times,
      prices.map { case (name, graph) => name -> graph.copy(history = graph.history.map(p =>
        p.copy(timestamp = if (p == current) None else times.get(p.height))).toVector) },
      fees.copy(history = fees.history.map(p => p.copy(timestamp = times.get(p.height))).toVector),
      DexHistory.activity(transitions, times), decimalsX, since)
  }
}

object NodeDexStatsSource {
  /** The node's token index is newest first. Stop after the window's predecessor or the page budget. */
  private[stats] def history(api: NodeApi, nft: String, tree: String, from: Int,
                             maxPages: Int, tokenToken: Boolean = false): (Vector[LDBoxes.PoolSnapshot], Boolean) = {
    val pageSize = 200
    var loaded = Vector.empty[LDBoxes.PoolSnapshot]
    var pages = 0
    var exhausted = false
    var previousIndex = Long.MaxValue
    while (pages < maxPages && !exhausted && !loaded.exists(_.height < from)) {
      val page = api.boxesByTokenId(nft, Paging(pages * pageSize, pageSize)).get
      require(page.items.size <= pageSize, "pool history page exceeded its requested size")
      page.items.foreach { box =>
        require(box.globalIndex < previousIndex, "pool history order changed during paging")
        previousIndex = box.globalIndex
        if (box.ergoTree == tree) {
          require(box.assets.headOption.exists(a => a.tokenId == nft && a.amount == 1L), "invalid pool NFT")
          loaded :+= LDBoxes.readSnapshot(box, tokenToken).getOrElse(throw new IllegalStateException("malformed pool history"))
        }
      }
      exhausted = page.items.size < pageSize
      pages += 1
    }
    val ordered = loaded.sortBy(s => (s.height, s.globalIndex))
    val before = ordered.filter(_.height < from).lastOption
    (before.toVector ++ ordered.filter(_.height >= from), exhausted || before.isDefined)
  }
}
