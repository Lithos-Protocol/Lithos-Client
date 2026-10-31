package controllers

import akka.actor.ActorSystem
import api.LithosApiErrors._
import api.models._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, LDPoolIndex, LDPoolsApi}
import configs.NodeContext
import lithosdex.LDHelpers
import stats.{DexStatsData, DexStatsSnapshot, DexStatsView, StatsCache}
import configs.Contexts
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import transactions.batching.lithosdex.LDBoxes
import transactions.engine.wallet.FundingExpiredException

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The `/dex/pools` endpoints. Every action reads the node or the wallet, so each runs on the dex pool
 * rather than Play's request threads. Placements need the api key; reads and quotes do not.
 */
@Singleton
class LDPoolsApiController @Inject()(cc: ControllerComponents, api: LDPoolsApi, config: Configuration,
                                     system: ActorSystem, statsCache: StatsCache, index: LDPoolIndex,
                                     nodeContext: NodeContext) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  private lazy val canonicalNft = LDHelpers.getPoolNFT(nodeContext.getNetwork).toString

  // ── graphs, verified pools only ──────────────────────────────────────────

  /** GET /dex/pools/:poolNft/price/history */
  def getPoolPriceHistory(poolNft: String, range: Option[String], bucket: Option[Int]): Action[AnyContent] =
    Action { _ => graph(poolNft)(_.price(range, bucket)) }

  /** GET /dex/pools/:poolNft/fees/history */
  def getPoolFeeHistory(poolNft: String, from: Option[Int], to: Option[Int], bucket: Option[Int]): Action[AnyContent] =
    Action { _ => graph(poolNft)(_.fees(from, to, bucket)) }

  /** GET /dex/pools/:poolNft/activity/recent */
  def getPoolRecentActivity(poolNft: String, limit: Option[Int]): Action[AnyContent] =
    Action { _ => graph(poolNft)(_.activity(limit)) }

  /** GET /dex/pools/:poolNft/provisions/:boxId/fees/history: the provision's own box is read from the node. */
  def getPoolProvisionFeeHistory(poolNft: String, boxId: String, from: Option[Int], to: Option[Int],
                                 bucket: Option[Int]): Action[AnyContent] = Action.async { _ =>
    offPool(graph(poolNft) { data =>
      val (shares, entryX, entryY) = api.provisionTerms(poolNft, boxId)
      data.provisionFees(boxId, shares, entryX, entryY, from, to, bucket)
    })
  }

  /**
   * Graphs come from the stats collector, which reads ERG:LIT and the other verified pools in the background, each
   * other verified pool from the height it joined the list. An unverified pool has none: 404.
   */
  private def graph[A: Writes](poolNft: String)(select: DexStatsData => A): Result = {
    val nft = poolNft.toLowerCase
    lazy val pools = index.snapshot()
    val snapshot =
      if (nft == canonicalNft) Right(statsCache.dexSnapshot())
      else if (pools.status != "ready") Left(ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable",
        "the pool index is still building; retry shortly")).withHeaders("Retry-After" -> "5"))
      else if (!pools.pools.exists(p => p.nft == nft && p.verified)) Left(NotFound(ApiHelper.makeError(404, "Not kept",
        s"graphs and history are collected for verified pools only, and $poolNft is not one")))
      else Right(statsCache.verifiedDexSnapshot(nft).getOrElse(DexStatsSnapshot(DexStatsView("loading"), None)))
    val result = snapshot.fold(identity, s => s.data match {
      case Some(data) => respond {
        val fields = Json.toJson(select(data)).as[JsObject] ++ Json.obj("stats" -> s.view)
        if (s.view.status == "stale") fields + ("partial" -> JsBoolean(true)) else fields
      }
      case None => ServiceUnavailable(ApiHelper.makeError(503, "DEX statistics unavailable",
        s"Background collection is ${s.view.status}; no graph snapshot is available")
        .as[JsObject] ++ Json.obj("stats" -> s.view)).withHeaders("Retry-After" -> "5")
    })
    result.withHeaders("Cache-Control" -> "no-store")
  }

  /** GET /dex/pools */
  def listPools(): Action[AnyContent] = Action.async { _ => offPool(respond(api.listPools)) }

  /** GET /dex/pools/:poolNft */
  def getPool(poolNft: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.getPool(poolNft))) }

  /** POST /dex/pools/create/check */
  def checkCreatePool(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkCreatePool(body[LDPoolCreateRequest](request))))
  }

  /** POST /dex/pools/create */
  def createPool(): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.createPool(body[LDPoolCreateExecuteRequest](request)))))
  }

  /** GET /dex/pools/:poolNft/orders */
  def listPoolOrders(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(_ => offPool(respond(api.listPoolOrders(poolNft))))
  }

  /** POST /dex/provisions/nft: open, since every figure it returns is public chain state */
  def lookupNfts(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.lookupNfts(body[LDNftLookupRequest](request))))
  }

  /** GET /dex/pools/:poolNft/provisions */
  def listPoolProvisions(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(_ => offPool(respond(api.listPoolProvisions(poolNft))))
  }

  /** POST /dex/pools/:poolNft/provisions/:boxId/claim */
  def claimPoolProvision(poolNft: String, boxId: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.claimPoolProvision(poolNft, boxId,
      optionalBody[LDClaimRequest](request, LDClaimRequest())))))
  }

  /** POST /dex/pools/:poolNft/flush/check */
  def checkPoolFlush(poolNft: String): Action[AnyContent] = Action.async { _ =>
    offPool(respond(api.checkPoolFlush(poolNft)))
  }

  /** POST /dex/pools/:poolNft/flush */
  def flushPool(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.flushPool(poolNft,
      optionalBody[LDFlushRequest](request, LDFlushRequest.Empty)))))
  }

  /** POST /dex/pools/:poolNft/orders/swap/check */
  def checkSwapOrder(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkSwapOrder(poolNft, body[LDPoolSwapRequest](request))))
  }

  /** POST /dex/pools/:poolNft/orders/swap */
  def placeSwapOrder(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.placeSwapOrder(poolNft, body[LDPoolSwapExecuteRequest](request)))))
  }

  /** POST /dex/pools/:poolNft/orders/deposit/check */
  def checkDepositOrder(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkDepositOrder(poolNft, body[LDPoolDepositRequest](request))))
  }

  /** POST /dex/pools/:poolNft/orders/deposit */
  def placeDepositOrder(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.placeDepositOrder(poolNft, body[LDPoolDepositExecuteRequest](request)))))
  }

  /** POST /dex/pools/:poolNft/orders/redeem/check */
  def checkRedeemOrder(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkRedeemOrder(poolNft, body[LDPoolRedeemRequest](request))))
  }

  /** POST /dex/pools/:poolNft/orders/redeem */
  def placeRedeemOrder(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.placeRedeemOrder(poolNft, body[LDPoolRedeemRequest](request)))))
  }

  /** POST /dex/pools/:poolNft/swap/check */
  def checkDirectSwap(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkDirectSwap(poolNft, body[LDPoolSwapRequest](request))))
  }

  /** POST /dex/pools/:poolNft/swap */
  def directSwap(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.directSwap(poolNft, body[LDPoolDirectSwapRequest](request)))))
  }

  /** POST /dex/pools/:poolNft/deposit/check */
  def checkDirectDeposit(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkDirectDeposit(poolNft, body[LDPoolDepositRequest](request))))
  }

  /** POST /dex/pools/:poolNft/deposit */
  def directDeposit(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.directDeposit(poolNft, body[LDPoolDirectDepositRequest](request)))))
  }

  /** POST /dex/pools/:poolNft/redeem/check */
  def checkDirectRedeem(poolNft: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkDirectRedeem(poolNft, body[LDPoolRedeemRequest](request))))
  }

  /** POST /dex/pools/:poolNft/redeem */
  def directRedeem(poolNft: String): Action[AnyContent] = withApiKey {
    Action.async(request => offPool(respond(api.directRedeem(poolNft, body[LDPoolDirectRedeemRequest](request)))))
  }

  /** The JSON body as `A`, or `empty` when the request has no body: every field of these requests is optional. */
  private def optionalBody[A](request: Request[AnyContent], empty: A)(implicit reads: Reads[A]): A =
    if (request.body.asJson.isEmpty) empty else body[A](request)

  /** The JSON body as `A`, or a 400 naming what is wrong with it. Decoded inside `respond`. */
  private def body[A](request: Request[AnyContent])(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => throw new OpenApiExceptions.MissingRequiredParameterException("body", "request")
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(errors) => throw LithosBadRequest(s"request body is not valid: " + errors.map {
          case (path, messages) => s"${path.toJsonString} ${messages.flatMap(_.messages).mkString(", ")}"
        }.mkString("; "))
      }
    }

  /**
   * The `/dex` mapping: 400 the request is wrong; 409 a box moved, so re-quote and resend; 422 the request is
   * fine and the pool or wallet cannot do it; 404 the pool or box is not there; 503 the node, index or wallet
   * did not answer. Only a defect in this client reaches 500.
   */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: LithosBadRequest) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: OpenApiExceptions.MissingRequiredParameterException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosStateChanged) => Conflict(Json.toJson(LDStateChangedResponse(e)))
      case Failure(e: LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosNotFound) => NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LDBoxes.NoSuchBoxException) => NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LDBoxes.BoxBeingSpentException) =>
        Conflict(Json.toJson(LDStateChangedResponse(LithosStateChanged(e.getMessage))))
      case Failure(e: FundingExpiredException) =>
        Conflict(Json.toJson(LDStateChangedResponse(LithosStateChanged(e.getMessage))))
      case Failure(e: NotEnoughInputsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosUnavailable) => ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: LDBoxes.IndexUnavailableException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: LDBoxes.IndexTruncatedException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Answer would be partial", e.getMessage))
      case Failure(e: java.util.concurrent.TimeoutException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      case Failure(e: IllegalArgumentException) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  // Read once at construction: `config.get` throws when the key is absent
  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers
      .get("api_key")
      .collect {
        case key if MessageDigest.isEqual(
          Hex.toHexString(Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8),
          apiKeyHash.getBytes(StandardCharsets.UTF_8)) => action(request)
      }
      .getOrElse(Future.successful(Forbidden(
        ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key"))))
  }
}
