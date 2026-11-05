package controllers

import akka.actor.ActorSystem
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.models.SigmaFi._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, SigmaFiApi}
import configs.Contexts
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import transactions.engine.wallet.FundingExpiredException

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The `sigmafi` endpoints: the whole of `/sigmafi`. Lists, the asset lookup and every check are open; the
 * portfolio and everything that signs need the api key. Each action does blocking node IO, so it runs on the dex
 * pool rather than Play's request threads.
 */
@Singleton
class SigmaFiApiController @Inject()(cc: ControllerComponents,
                                     api: SigmaFiApi,
                                     config: Configuration,
                                     system: ActorSystem) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  // ── reads ────────────────────────────────────────────────────────────────

  /** GET /sigmafi/orders */
  def listOrders(offset: Option[Int], limit: Option[Int], collateral: Option[String], loan: Option[String]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.listOrders(offset, limit, collateral, loan))) }

  /** GET /sigmafi/offers */
  def listOffers(offset: Option[Int], limit: Option[Int], collateral: Option[String], loan: Option[String]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.listOffers(offset, limit, collateral, loan))) }

  /** GET /sigmafi/bonds */
  def listBonds(offset: Option[Int], limit: Option[Int], all: Option[Boolean]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.listBonds(offset, limit, all))) }

  /** POST /sigmafi/assets */
  def lookupAssets(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.lookupAssets(body[SFAssetLookupRequest](request, "assetLookupRequest"))))
  }

  /** GET /sigmafi/portfolio */
  def getPortfolio(): Action[AnyContent] = withApiKey { Action.async { _ => offPool(respond(api.getPortfolio)) } }

  // ── actions: each `check` is open and signs nothing, each send needs the key ─────────────

  def checkCreateOrder(): Action[AnyContent] = Action.async { r =>
    offPool(respondEither(api.createOrder(body[SFOrderRequest](r, "orderRequest"), send = false)))
  }
  def createOrder(): Action[AnyContent] = withApiKey {
    Action.async { r => offPool(respondEither(api.createOrder(body[SFOrderRequest](r, "orderRequest"), send = true))) }
  }

  def checkCreateOffer(): Action[AnyContent] = Action.async { r =>
    offPool(respondEither(api.createOffer(body[SFOfferRequest](r, "offerRequest"), send = false)))
  }
  def createOffer(): Action[AnyContent] = withApiKey {
    Action.async { r => offPool(respondEither(api.createOffer(body[SFOfferRequest](r, "offerRequest"), send = true))) }
  }

  def checkCancelOrder(boxId: String): Action[AnyContent] = check(api.cancelOrder(boxId, send = false))
  def cancelOrder(boxId: String): Action[AnyContent] = sendKeyed(api.cancelOrder(boxId, send = true))

  def checkCancelOffer(boxId: String): Action[AnyContent] = check(api.cancelOffer(boxId, send = false))
  def cancelOffer(boxId: String): Action[AnyContent] = sendKeyed(api.cancelOffer(boxId, send = true))

  def checkFillOrder(boxId: String): Action[AnyContent] = check(api.fillOrder(boxId, send = false))
  def fillOrder(boxId: String): Action[AnyContent] = sendKeyed(api.fillOrder(boxId, send = true))

  def checkCloseOffer(boxId: String): Action[AnyContent] = check(api.closeOffer(boxId, send = false))
  def closeOffer(boxId: String): Action[AnyContent] = sendKeyed(api.closeOffer(boxId, send = true))

  def checkRepay(boxId: String): Action[AnyContent] = check(api.repay(boxId, send = false))
  def repay(boxId: String): Action[AnyContent] = sendKeyed(api.repay(boxId, send = true))

  def checkLiquidate(boxId: String): Action[AnyContent] = check(api.liquidate(boxId, send = false))
  def liquidate(boxId: String): Action[AnyContent] = sendKeyed(api.liquidate(boxId, send = true))

  def checkClaim(tokenId: String): Action[AnyContent] = check(api.claim(tokenId, send = false))
  def claim(tokenId: String): Action[AnyContent] = sendKeyed(api.claim(tokenId, send = true))

  // ── plumbing ─────────────────────────────────────────────────────────────

  private def check(answer: => Either[SFQuote, SFResult]): Action[AnyContent] =
    Action.async { _ => offPool(respondEither(answer)) }

  private def sendKeyed(answer: => Either[SFQuote, SFResult]): Action[AnyContent] =
    withApiKey { Action.async { _ => offPool(respondEither(answer)) } }

  private def respondEither(answer: => Either[SFQuote, SFResult]): Result =
    Try(answer) match {
      case Success(Left(quote)) => respond(quote)
      case Success(Right(result)) => respond(result)
      case Failure(e) => respond[SFQuote](throw e)
    }

  /** A required body; one that is present but malformed is a 400 like a missing one. */
  private def body[A](request: Request[AnyContent], name: String)(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => throw new OpenApiExceptions.MissingRequiredParameterException("body", name)
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(errors) => throw LithosBadRequest(s"'$name' is not valid: " + errors.map { case (path, messages) =>
          s"${path.toJsonString} ${messages.flatMap(_.messages).mkString(", ")}"
        }.mkString("; "))
      }
    }

  /**
   * 400 the request is wrong; 409 a box it names is already being spent, so re-read and resend; 422 the request
   * is valid and the chain or the wallet cannot do it now; 404 the box is not there or not this wallet's; 503
   * the node, the engine or the wallet could not answer. Only a defect in this client reaches 500.
   */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: LithosBadRequest) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: OpenApiExceptions.MissingRequiredParameterException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosStateChanged) => Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
      case Failure(e: FundingExpiredException) => Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
      case Failure(e: LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: NotEnoughInputsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosNotFound) => NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: java.util.concurrent.TimeoutException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      // A builder's `require` guards a condition the contracts would reject, so it is a statement about the request
      case Failure(e: IllegalArgumentException) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  /** Read once at construction, so a missing hash fails the client at startup rather than every request. */
  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers
      .get("api_key")
      .collect {
        case key if MessageDigest.isEqual(
          Hex.toHexString(Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8),
          apiKeyHash.getBytes(StandardCharsets.UTF_8)) => action(request)
      }
      .getOrElse {
        Future.successful(Forbidden(ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key")))
      }
  }
}
