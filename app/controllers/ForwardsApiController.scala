package controllers

import akka.actor.ActorSystem
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.models.Forwards._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, ForwardsApi}
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
 * The `forwards` endpoints: the whole of `/forwards`. Reads and checks are open; this wallet's positions and
 * everything that signs need the api key. Each action does blocking node IO, so it runs on the dex pool
 * rather than Play's request threads.
 */
@Singleton
class ForwardsApiController @Inject()(cc: ControllerComponents,
                                      api: ForwardsApi,
                                      config: Configuration,
                                      system: ActorSystem) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  // ── reads ────────────────────────────────────────────────────────────────

  /** GET /forwards/accumulator */
  def getAccumulator(): Action[AnyContent] = Action.async { _ => offPool(respond(api.getAccumulator)) }

  /** GET /forwards/offers */
  def listOffers(offset: Option[Int], limit: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.listOffers(offset, limit))) }

  /** GET /forwards/market */
  def getMarket(): Action[AnyContent] = Action.async { _ => offPool(respond(api.getMarket)) }

  /** GET /forwards/positions */
  def listPositions(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.listPositions)) }
  }

  /** GET /forwards/history */
  def getHistory(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.getHistory)) }
  }

  // ── offers ───────────────────────────────────────────────────────────────

  /** POST /forwards/offers/check */
  def checkOffer(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkOffer(body[DFOfferRequest](request, "offerRequest"))))
  }

  /** POST /forwards/offers */
  def createOffer(): Action[AnyContent] = withApiKey {
    Action.async { request => offPool(respond(api.createOffer(body[DFOfferRequest](request, "offerRequest")))) }
  }

  /** POST /forwards/offers/:boxId/fill/check */
  def checkFill(boxId: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.checkFill(boxId))) }

  /** POST /forwards/offers/:boxId/fill */
  def fill(boxId: String): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.fill(boxId))) }
  }

  /** POST /forwards/offers/:boxId/cancel/check */
  def checkCancel(boxId: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.checkCancel(boxId))) }

  /** POST /forwards/offers/:boxId/cancel */
  def cancel(boxId: String): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.cancel(boxId))) }
  }

  // ── settlement ───────────────────────────────────────────────────────────

  /** POST /forwards/positions/:boxId/settle/check */
  def checkSettle(boxId: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.checkSettle(boxId))) }

  /** POST /forwards/positions/:boxId/settle */
  def settle(boxId: String): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.settle(boxId))) }
  }

  // ── plumbing ─────────────────────────────────────────────────────────────

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
