package configs

import lfsm.LFSMHelpers
import lithosdex.LDHelpers
import org.ergoplatform.appkit.NetworkType
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

/** A token this release marks verified. */
final case class VerifiedToken(tokenId: String, name: String, ticker: String, decimals: Int)

/**
 * A LithosDex pool this release marks verified. `tokens` is Y alone for an ERG pool, X then Y for a token pool.
 * `addedHeight` is the height it joined the list: its graphs are collected from there, and anything earlier is
 * reported as not collected. None for ERG:LIT, whose graphs cover its whole life.
 */
final case class VerifiedPool(poolNft: String, kind: VerifiedPool.Kind, tokens: Seq[String],
                              addedHeight: Option[Int] = None)

object VerifiedPool {
  sealed abstract class Kind(val name: String, val tokenCount: Int)

  object Kind {
    case object N2T extends Kind("n2t", 1)
    case object T2T extends Kind("t2t", 2)
    val all: Seq[Kind] = Seq(N2T, T2T)
  }
}

/**
 * The tokens and LithosDex pools this release marks verified on one network. Verified means checked by
 * hand and added to the shipped list by PR; everything else is shown with a warning. LIT and ERG:LIT are
 * built in from the protocol constants, so the list cannot misstate them. Lookups take ids in any case.
 */
final class VerifiedRegistry private[configs](val tokens: Seq[VerifiedToken], val pools: Seq[VerifiedPool]) {
  private val tokenById = tokens.map(t => t.tokenId -> t).toMap
  private val poolByNft = pools.map(p => p.poolNft -> p).toMap

  def token(tokenId: String): Option[VerifiedToken] = tokenById.get(tokenId.toLowerCase)
  def pool(poolNft: String): Option[VerifiedPool] = poolByNft.get(poolNft.toLowerCase)
}

object VerifiedRegistry {

  private val logger: Logger = LoggerFactory.getLogger("VerifiedRegistry")

  final val TokensResource = "verified/tokens.json"
  final val PoolsResource = "verified/pools.json"

  private val IdPattern = "[0-9a-f]{64}"
  private val TokenFields = Set("tokenId", "name", "ticker", "decimals")
  private val PoolFields = Set("poolNft", "kind", "tokens", "addedHeight")

  /** LIT and the ERG:LIT pool, from the same constants the contracts are built with. */
  def builtIn(network: NetworkType): (Seq[VerifiedToken], Seq[VerifiedPool]) = (
    Seq(VerifiedToken(LFSMHelpers.getLitId(network).toString, "LIT", "LIT", 9)),
    Seq(VerifiedPool(LDHelpers.getPoolNFT(network).toString, VerifiedPool.Kind.N2T,
      Seq(LDHelpers.getTokenY(network).toString))))

  def networkKey(network: NetworkType): String = network match {
    case NetworkType.MAINNET => "mainnet"
    case NetworkType.TESTNET => "testnet"
  }

  /**
   * The built-in entries plus `network`'s lists from both files. Any problem rejects the files whole and
   * names every problem found, so one bad entry cannot slip through behind another.
   */
  def parse(network: NetworkType, tokensJson: String, poolsJson: String): Either[Seq[String], VerifiedRegistry] = {
    val (baseTokens, basePools) = builtIn(network)
    val key = networkKey(network)
    val problems = Vector.newBuilder[String]

    def listIn(file: String, json: String): Seq[JsValue] =
      Try(Json.parse(json)) match {
        case Failure(e) => problems += s"$file is not JSON: ${e.getMessage}"; Seq.empty
        case Success(obj: JsObject) => obj.value.get(key) match {
          case Some(JsArray(items)) => items.toSeq
          case _ => problems += s"$file has no $key array"; Seq.empty
        }
        case Success(_) => problems += s"$file is not a JSON object"; Seq.empty
      }

    def fields(file: String, i: Int, item: JsValue, allowed: Set[String]): Option[JsObject] = item match {
      case obj: JsObject =>
        val unknown = obj.keys.diff(allowed)
        if (unknown.nonEmpty) problems += s"$file $key[$i] has unknown fields ${unknown.mkString(", ")}"
        Some(obj)
      case _ => problems += s"$file $key[$i] is not an object"; None
    }

    def id(where: String, v: JsLookupResult): Option[String] = v.asOpt[String] match {
      case Some(s) if s.matches(IdPattern) => Some(s)
      case _ => problems += s"$where is not 64 lowercase hex characters"; None
    }

    val fileTokens = listIn(TokensResource, tokensJson).zipWithIndex.flatMap { case (item, i) =>
      val where = s"$TokensResource $key[$i]"
      fields(TokensResource, i, item, TokenFields).flatMap { obj =>
        val tokenId = id(s"$where tokenId", obj \ "tokenId")
        val name = (obj \ "name").asOpt[String].filter(n => n.trim.nonEmpty && n.length <= 64)
        if (name.isEmpty) problems += s"$where name must be 1 to 64 characters"
        val ticker = (obj \ "ticker").asOpt[String].filter(t => t.nonEmpty && t.length <= 12 && !t.exists(_.isWhitespace))
        if (ticker.isEmpty) problems += s"$where ticker must be 1 to 12 characters with no spaces"
        val decimals = (obj \ "decimals").asOpt[Int].filter(d => d >= 0 && d <= 18)
        if (decimals.isEmpty) problems += s"$where decimals must be a whole number from 0 to 18"
        for (t <- tokenId; n <- name; k <- ticker; d <- decimals) yield VerifiedToken(t, n, k, d)
      }
    }
    val tokens = baseTokens ++ fileTokens
    duplicates(tokens.map(_.tokenId)).foreach(t => problems += s"token $t is listed more than once")
    val tokenIds = tokens.map(_.tokenId).toSet

    val filePools = listIn(PoolsResource, poolsJson).zipWithIndex.flatMap { case (item, i) =>
      val where = s"$PoolsResource $key[$i]"
      fields(PoolsResource, i, item, PoolFields).flatMap { obj =>
        val poolNft = id(s"$where poolNft", obj \ "poolNft")
        val kind = (obj \ "kind").asOpt[String].flatMap(k => VerifiedPool.Kind.all.find(_.name == k))
        if (kind.isEmpty) problems += s"$where kind must be one of ${VerifiedPool.Kind.all.map(_.name).mkString(", ")}"
        val ids = (obj \ "tokens").asOpt[Seq[JsValue]] match {
          case None => problems += s"$where tokens must be an array"; None
          case Some(vs) =>
            val parsed = vs.indices.map(j => id(s"$where tokens[$j]", JsDefined(vs(j))))
            if (parsed.forall(_.isDefined)) Some(parsed.flatten) else None
        }
        val added = (obj \ "addedHeight").asOpt[Int].filter(_ > 0)
        if (added.isEmpty) problems += s"$where addedHeight must be the positive block height the pool joined the list at"
        for (n <- poolNft; k <- kind; ts <- ids; h <- added) yield {
          if (ts.size != k.tokenCount) problems += s"$where is ${k.name} and must name ${k.tokenCount} token(s), not ${ts.size}"
          if (ts.distinct.size != ts.size) problems += s"$where names the same token twice"
          ts.filterNot(tokenIds).foreach(t => problems += s"$where names $t, which is not a verified token")
          VerifiedPool(n, k, ts, Some(h))
        }
      }
    }
    val pools = basePools ++ filePools
    duplicates(pools.map(_.poolNft)).foreach(p => problems += s"pool $p is listed more than once")

    val found = problems.result()
    if (found.nonEmpty) Left(found) else Right(new VerifiedRegistry(tokens, pools))
  }

  /**
   * The shipped list for `network`, read once per process. If it does not parse, only LIT and ERG:LIT stay
   * verified and every problem is logged at error: verification fails closed, and never stops the client.
   */
  def forNetwork(network: NetworkType): VerifiedRegistry = network match {
    case NetworkType.MAINNET => Mainnet
    case NetworkType.TESTNET => Testnet
  }

  private lazy val Mainnet = loadShipped(NetworkType.MAINNET)
  private lazy val Testnet = loadShipped(NetworkType.TESTNET)

  private def loadShipped(network: NetworkType): VerifiedRegistry = {
    val loaded = for {
      tokens <- resourceText(TokensResource)
      pools <- resourceText(PoolsResource)
    } yield parse(network, tokens, pools)
    loaded match {
      case Success(Right(registry)) => registry
      case outcome =>
        val reasons = outcome.fold(e => Seq(e.getMessage), _.left.getOrElse(Seq.empty))
        logger.error(s"The verified token and pool list did not load, so only LIT and ERG:LIT are marked verified: " +
          reasons.mkString("; "))
        val (tokens, pools) = builtIn(network)
        new VerifiedRegistry(tokens, pools)
    }
  }

  private def resourceText(path: String): Try[String] =
    Try(Option(getClass.getClassLoader.getResourceAsStream(path))).flatMap {
      case None => Failure(new IllegalStateException(s"$path is not on the classpath"))
      case Some(stream) =>
        try Success(new String(stream.readAllBytes(), "UTF-8"))
        catch { case e: java.io.IOException => Failure(e) }
        finally stream.close()
    }

  private def duplicates(ids: Seq[String]): Seq[String] =
    ids.groupBy(identity).collect { case (id, copies) if copies.size > 1 => id }.toSeq.sorted
}
