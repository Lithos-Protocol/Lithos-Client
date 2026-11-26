package controllers

import akka.actor.ActorSystem
import api.ApiHelper
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.zc.{ZcFilters, ZcHistory, ZcReads, ZcWrites}
import configs.{Contexts, NodeContext}
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import stats.ZcFills
import transactions.batching.sigmafi.zc.{ZcResolver, ZcTracker}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.{ConcurrentHashMap, Semaphore}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The `/sigmafi/zc` reads. Every read is open except the portfolio, which names this wallet's funds. A read sent with
 * `ifRevision` equal to the tracker's revision answers 304 at once, so the UI's polls cost nothing between changes.
 * Reads run on the dex pool: they may make a few bounded node reads. Write checks and `simulate` can run the matcher
 * over the whole book, so at most [[ZcApiController.MaxPreviews]] of them run or wait at once, counted before they are
 * queued; `simulate` is also rate-limited per caller.
 */
@Singleton
class ZcApiController @Inject()(cc: ControllerComponents, tracker: ZcTracker, resolver: ZcResolver,
                                nodeContext: NodeContext, config: Configuration, system: ActorSystem, fillIndex: ZcFills,
                                writes: ZcWrites, history: ZcHistory)
  extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  protected lazy val reads: ZcReads = new ZcReads(tracker, resolver, () => nodeContext.getNodeApi,
    () => nodeContext.getNodeWallet.signableTrees.map(t => Hex.toHexString(Blake2b256.hash(Hex.decode(t)))),
    () => nodeContext.getNodeApi.walletBalances().map(b => (b.balance, b.assets)).get,
    nodeContext.getNetwork, history = Some(history))

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  /** `result` on the dex pool under a preview slot, taken before it is queued; 429 when none is free. */
  private def preview(result: => Result): Future[Result] =
    if (!previews.tryAcquire()) Future.successful(TooManyRequests(ApiHelper.makeError(429, "Too many requests",
      s"at most ${ZcApiController.MaxPreviews} quotes and simulations run or wait at once; retry shortly")))
    else Try(Future(try result finally previews.release())(dexContext)) match {
      case Success(running) => running
      case Failure(ex) => previews.release(); Future.failed(ex)
    }

  private def read(ifRevision: Option[Long])(answer: => JsValue): Action[AnyContent] = Action.async { _ =>
    if (ifRevision.exists(r => reads.revision.contains(r))) Future.successful(NotModified)
    else offPool(respond(answer))
  }

  private def filters(coverage: Option[Long], kind: Option[String], matchable: Option[Boolean], grid: Option[Boolean]): ZcFilters =
    ZcFilters(coverage.getOrElse(15000L), kind.getOrElse("all"), matchable.getOrElse(true), grid.getOrElse(false))

  /** GET /sigmafi/zc/info */
  def info(ifRevision: Option[Long]): Action[AnyContent] = read(ifRevision)(reads.info())

  /** GET /sigmafi/zc/markets/:market/asks */
  def asks(market: String, coverage: Option[Long], kind: Option[String], matchable: Option[Boolean], grid: Option[Boolean],
           from: Option[Long], to: Option[Long], sort: Option[String], cursor: Option[String], limit: Option[Int],
           ifRevision: Option[Long]): Action[AnyContent] =
    read(ifRevision)(reads.asks(market, filters(coverage, kind, matchable, grid), from, to, sort, cursor, limit))

  /** GET /sigmafi/zc/markets/:market/offers */
  def offers(market: String, coverage: Option[Long], kind: Option[String], matchable: Option[Boolean], grid: Option[Boolean],
             from: Option[Long], to: Option[Long], sort: Option[String], cursor: Option[String], limit: Option[Int],
             ifRevision: Option[Long]): Action[AnyContent] =
    read(ifRevision)(reads.offers(market, filters(coverage, kind, matchable, grid), from, to, sort, cursor, limit))

  /** GET /sigmafi/zc/markets/:market/curves */
  def curves(market: String, coverage: Option[Long], kind: Option[String], matchable: Option[Boolean], grid: Option[Boolean],
             ifRevision: Option[Long]): Action[AnyContent] =
    read(ifRevision)(reads.curves(market, filters(coverage, kind, matchable, grid)))

  /** GET /sigmafi/zc/markets/:market/orderbook */
  def orderbook(market: String, coverage: Option[Long], kind: Option[String], matchable: Option[Boolean], grid: Option[Boolean],
                to: Option[Long], around: Option[Long], band: Option[Long], ifRevision: Option[Long]): Action[AnyContent] =
    read(ifRevision)(reads.orderbook(market, filters(coverage, kind, matchable, grid), to, around, band))

  /** GET /sigmafi/zc/matches/:id */
  def matches(id: String, ifRevision: Option[Long]): Action[AnyContent] = read(ifRevision)(reads.matches(id.trim.toLowerCase))

  /**
   * GET /sigmafi/zc/markets/:market/fills. From the fill history, not the tracker, so it carries the history's own
   * `status`; `ifRevision` does not apply.
   */
  def fills(market: String, fromHeight: Option[Int], kind: Option[String], via: Option[String], cursor: Option[String],
            limit: Option[Int]): Action[AnyContent] = Action.async { _ =>
    offPool(respond {
      val m = sigmafi.zc.ZcMarket.named(market).getOrElse(throw LithosBadRequest(s"unknown market $market: ERG or SigUSD"))
      val size = limit.getOrElse(ZcReads.DefaultPage)
      if (size < 1 || size > ZcReads.MaxPage) throw LithosBadRequest(s"limit must be 1 to ${ZcReads.MaxPage}")
      kind.foreach(k => if (!Set("new", "resale", "shares").contains(k)) throw LithosBadRequest("kind must be new, resale or shares"))
      via.foreach(v => if (!Set("offer", "wallet").contains(v)) throw LithosBadRequest("via must be offer or wallet"))
      val page = fillIndex.fills(m, fromHeight.getOrElse(0), kind, via, cursor, size)
      Json.obj("status" -> page.status, "observedAt" -> page.observedAt, "indexedTo" -> page.indexedTo,
        "items" -> page.fills, "next" -> page.next)
    })
  }

  /** GET /sigmafi/zc/auctions */
  def auctions(market: Option[String], ifRevision: Option[Long]): Action[AnyContent] = read(ifRevision)(reads.auctions(market))

  /** GET /sigmafi/zc/lookup/:id */
  def lookup(id: String, ifRevision: Option[Long]): Action[AnyContent] = read(ifRevision)(reads.lookup(id))

  /** GET /sigmafi/zc/search */
  def search(q: String, ifRevision: Option[Long]): Action[AnyContent] = read(ifRevision)(reads.search(q))

  /** POST /sigmafi/zc/assets */
  def assets(): Action[AnyContent] = Action.async { request =>
    offPool(respond {
      val ids = request.body.asJson.flatMap(j => (j \ "tokenIds").asOpt[Seq[String]])
        .getOrElse(throw LithosBadRequest("the body needs tokenIds, a list of up to 50 token ids"))
      reads.assets(ids)
    })
  }

  /** POST /sigmafi/zc/simulate */
  def simulate(ifRevision: Option[Long]): Action[AnyContent] = Action.async { request =>
    if (ifRevision.exists(r => reads.revision.contains(r))) Future.successful(NotModified)
    else if (!limiter.take(request.remoteAddress))
      Future.successful(TooManyRequests(ApiHelper.makeError(429, "Too many requests",
        s"at most ${ZcApiController.SimulateBurst} simulations at once, then ${ZcApiController.SimulatePerSecond} a second")))
    else preview(respond(reads.simulate(request.body.asJson.getOrElse(Json.obj()))))
  }

  // ── writes: each check signs nothing and is open but the wrap's, each send needs the key ──────────────

  def checkCreateOrder(): Action[AnyContent] = Action.async { r => preview(respond(writes.createOrder(r.body.asJson, send = false))) }
  def createOrder(): Action[AnyContent] = withApiKey(Action.async { r => offPool(respond(writes.createOrder(r.body.asJson, send = true))) })
  def checkCreateOffer(): Action[AnyContent] = Action.async { r => preview(respond(writes.createOffer(r.body.asJson, send = false))) }
  def createOffer(): Action[AnyContent] = withApiKey(Action.async { r => offPool(respond(writes.createOffer(r.body.asJson, send = true))) })
  def checkCreateAsk(): Action[AnyContent] = Action.async { r => preview(respond(writes.createAsk(r.body.asJson, send = false))) }
  def createAsk(): Action[AnyContent] = withApiKey(Action.async { r => offPool(respond(writes.createAsk(r.body.asJson, send = true))) })
  def checkCancel(boxId: String): Action[AnyContent] = Action.async { _ => preview(respond(writes.cancel(boxId, send = false))) }
  def cancel(boxId: String): Action[AnyContent] =
    withApiKey(Action.async { _ => offPool(respond(writes.cancel(boxId, send = true))) })

  /** One of the other writes: its check open under a preview slot, its send keyed. */
  private def zc(action: String, id: Option[String], send: Boolean): Action[AnyContent] =
    if (send) withApiKey(Action.async { r => offPool(respond(writes.write(action, id, r.body.asJson, send = true))) })
    else Action.async { r => preview(respond(writes.write(action, id, r.body.asJson, send = false))) }

  def checkCreateLot(): Action[AnyContent] = zc("createLot", None, send = false)
  def createLot(): Action[AnyContent] = zc("createLot", None, send = true)
  def checkBuyLot(boxId: String): Action[AnyContent] = zc("buyLot", Some(boxId), send = false)
  def buyLot(boxId: String): Action[AnyContent] = zc("buyLot", Some(boxId), send = true)
  def checkFillOrder(boxId: String): Action[AnyContent] = zc("fillOrder", Some(boxId), send = false)
  def fillOrder(boxId: String): Action[AnyContent] = zc("fillOrder", Some(boxId), send = true)
  def checkBuyAsk(boxId: String): Action[AnyContent] = zc("buyAsk", Some(boxId), send = false)
  def buyAsk(boxId: String): Action[AnyContent] = zc("buyAsk", Some(boxId), send = true)
  def checkBuyVault(boxId: String): Action[AnyContent] = zc("buyVault", Some(boxId), send = false)
  def buyVault(boxId: String): Action[AnyContent] = zc("buyVault", Some(boxId), send = true)
  def checkPull(boxId: String): Action[AnyContent] = zc("pull", Some(boxId), send = false)
  def pull(boxId: String): Action[AnyContent] = zc("pull", Some(boxId), send = true)
  def checkFractionalize(assetId: String): Action[AnyContent] = zc("fractionalize", Some(assetId), send = false)
  def fractionalize(assetId: String): Action[AnyContent] = zc("fractionalize", Some(assetId), send = true)
  def checkRepay(assetId: String): Action[AnyContent] = zc("repay", Some(assetId), send = false)
  def repay(assetId: String): Action[AnyContent] = zc("repay", Some(assetId), send = true)
  def checkRecollateralize(assetId: String): Action[AnyContent] = zc("recollateralize", Some(assetId), send = false)
  def recollateralize(assetId: String): Action[AnyContent] = zc("recollateralize", Some(assetId), send = true)
  def checkLiquidate(assetId: String): Action[AnyContent] = zc("liquidate", Some(assetId), send = false)
  def liquidate(assetId: String): Action[AnyContent] = zc("liquidate", Some(assetId), send = true)
  def checkExtinguish(assetId: String): Action[AnyContent] = zc("extinguish", Some(assetId), send = false)
  def extinguish(assetId: String): Action[AnyContent] = zc("extinguish", Some(assetId), send = true)
  def checkClaim(tokenId: String): Action[AnyContent] = zc("claim", Some(tokenId), send = false)
  def claim(tokenId: String): Action[AnyContent] = zc("claim", Some(tokenId), send = true)
  // Keyed: the check reads how much of the token this wallet holds
  def checkCreateWrap(): Action[AnyContent] = withApiKey(zc("createWrap", None, send = false))
  def createWrap(): Action[AnyContent] = zc("createWrap", None, send = true)
  def checkUnwrap(receiptId: String): Action[AnyContent] = zc("unwrap", Some(receiptId), send = false)
  def unwrap(receiptId: String): Action[AnyContent] = zc("unwrap", Some(receiptId), send = true)
  def checkMint(boxId: String): Action[AnyContent] = zc("mint", Some(boxId), send = false)
  def mint(boxId: String): Action[AnyContent] = zc("mint", Some(boxId), send = true)

  /** GET /sigmafi/zc/portfolio */
  def portfolio(ifRevision: Option[Long]): Action[AnyContent] = withApiKey(read(ifRevision)(reads.portfolio()))

  /** GET /sigmafi/zc/activity. Not keyed to `ifRevision`: confirmed history moves with the wallet, not the market. */
  def activity(cursor: Option[String], limit: Option[Int]): Action[AnyContent] =
    withApiKey(read(None)(reads.activity(cursor, limit)))

  // ── plumbing ─────────────────────────────────────────────────────────────

  private val previews = new Semaphore(ZcApiController.MaxPreviews)
  private val limiter = new ZcApiController.Buckets(ZcApiController.SimulateBurst, ZcApiController.SimulatePerSecond)

  /** 400 the request is wrong; 404 nothing by that id; 503 tracking is off or not seeded, or the node failed a read. */
  private def respond(result: => JsValue): Result =
    Try(result) match {
      case Success(value) => Ok(value)
      case Failure(e: LithosBadRequest) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: IllegalArgumentException) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosNotFound) => NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LithosStateChanged) => Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
      case Failure(e: LithosUnprocessable) => UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: mutations.NotEnoughInputsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosUnavailable) => ServiceUnavailable(ApiHelper.makeError(503, "Unavailable", e.getMessage))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  /** Read once at construction, so a missing hash fails the client at startup rather than every request. */
  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers.get("api_key").collect {
      case key if MessageDigest.isEqual(Hex.toHexString(Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8),
        apiKeyHash.getBytes(StandardCharsets.UTF_8)) => action(request)
    }.getOrElse(Future.successful(Forbidden(ApiHelper.makeError(403, "Forbidden request",
      "Could not authenticate request with given api key"))))
  }
}

object ZcApiController {

  /** Simulations one caller may start at once, then per second: one costs about 1.5 s at the matcher's caps. */
  final val SimulateBurst = 5
  final val SimulatePerSecond = 1

  /** Write checks and simulations running or queued at once, across every caller. */
  final val MaxPreviews = 4

  /** Most callers tracked; past it the table is cleared, which only forgives a burst. */
  final val MaxCallers = 10000

  /** A token bucket per caller. */
  final class Buckets(burst: Int, perSecond: Int) {
    private val buckets = new ConcurrentHashMap[String, (Double, Long)]()
    def take(caller: String): Boolean = {
      if (buckets.size > MaxCallers) buckets.clear()
      val now = System.nanoTime()
      var granted = false
      buckets.compute(caller, (_, old) => {
        val (tokens, at) = Option(old).getOrElse((burst.toDouble, now))
        val refilled = math.min(burst.toDouble, tokens + (now - at) / 1e9 * perSecond)
        granted = refilled >= 1.0
        (if (granted) refilled - 1.0 else refilled, now)
      })
      granted
    }
  }
}
