package controllers

import akka.actor.ActorSystem
import api.LithosApiErrors._
import api.models._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, LDPoolsApi}
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
                                     system: ActorSystem) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  /** GET /dex/pools */
  def listPools(): Action[AnyContent] = Action.async { _ => offPool(respond(api.listPools)) }

  /** GET /dex/pools/:poolNft */
  def getPool(poolNft: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.getPool(poolNft))) }

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
