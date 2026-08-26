package controllers

import akka.actor.{ActorRef, ActorSystem}
import api.models._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, CollateralMarketApi, LithosApiErrors}
import configs.Contexts
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import stratum.CollateralNotFoundException
import transactions.wallet.WalletMessages.InsufficientWalletFundsException
import mutations.NotEnoughInputsException

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The collateral-market endpoints. Everything here does blocking node IO, so like the LithosDex
 * controller each action runs on the dedicated dex pool rather than Play's request threads.
 */
@Singleton
class CollateralMarketApiController @Inject()(cc: ControllerComponents,
                                              api: CollateralMarketApi,
                                              config: Configuration,
                                              system: ActorSystem
                                             ) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  // ── reads ────────────────────────────────────────────────────────────────

  /** GET /collateral/market */
  def getMarketInfo(): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getMarketInfo)) }

  /** GET /collateral/queue */
  def getQueue(limit: Option[Int], offset: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(Json.toJson(api.getQueue(limit, offset)))) }

  /** GET /collateral/active */
  def getActiveSet(limit: Option[Int], offset: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(Json.toJson(api.getActiveSet(limit, offset)))) }

  /** GET /collateral/permits/history */
  def getPermitHistory(limit: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getPermitHistory(limit))) }

  /**
   * GET /collateral/rewards
   *
   * Key-gated. The spec has always said so and the route did not enforce it, which left this
   * wallet's locked coinbase totals readable by anyone who could reach the port.
   */
  def getRewardSummary(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.getRewardSummary)) }
  }

  // ── writes ───────────────────────────────────────────────────────────────

  /** POST /collateral/rewards/claim */
  def claimRewards(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.claimUnlockedRewards)) }
  }

  /**
   * POST /collateral/join/check
   *
   * Key-gated, unlike its earlier form. The quote names this wallet's lender addresses and reports
   * its shortfall, from which the spendable balance follows directly — both are wallet data, and
   * the caller needs the same key to act on the quote anyway.
   */
  def checkJoin(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.checkJoin(body[CollateralJoinCheckRequest](request))))
    }
  }

  /** POST /collateral/join */
  def join(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.join(body[CollateralJoinExecuteRequest](request))))
    }
  }

  // ── plumbing ─────────────────────────────────────────────────────────────

  private def describe(errors: Seq[(JsPath, Seq[JsonValidationError])]): String =
    errors.map { case (path, messages) =>
      s"${path.toJsonString} ${messages.flatMap(_.messages).mkString(", ")}"
    }.mkString("; ")

  private def body[A](request: Request[AnyContent])(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => throw new OpenApiExceptions.MissingRequiredParameterException("body", "request")
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(errors) => throw LithosApiErrors.LithosBadRequest(
          s"request body is not valid: ${describe(errors)}")
      }
    }

  /**
   * Status codes decided in one place, matching the LithosDex mapping: 400 the request or its
   * preconditions are wrong; 409 quoted state moved, re-quote and resend; 422 the wallet cannot do
   * it right now; 404 the queried thing does not exist yet; 503 something upstream did not answer;
   * only a defect in this client reaches 500.
   */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: CollateralMarketApi.NothingToClaim) =>
        BadRequest(ApiHelper.makeError(400, "Nothing to claim", e.getMessage))
      case Failure(e: LithosApiErrors.LithosBadRequest) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      // `LDStateChangedResponse` carries the DEX's three box ids, which are always absent here, and
      // `openapi.yaml` documents this path's 409 as an ApiError. Answering with the DEX shape was
      // undocumented drift in a body a caller parses.
      case Failure(e: LithosApiErrors.LithosStateChanged) =>
        Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: CollateralNotFoundException) =>
        NotFound(ApiHelper.makeError(404, "Not found",
          "no emission state on this network - has emission launched and is the node indexed?"))
      case Failure(e: NotEnoughInputsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: InsufficientWalletFundsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: java.util.concurrent.TimeoutException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      case Failure(e: IllegalArgumentException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) =>
        InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers
      .get("api_key")
      .collect {
        case key if MessageDigest.isEqual(
          org.bouncycastle.util.encoders.Hex.toHexString(
            scorex.crypto.hash.Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8),
          apiKeyHash.getBytes(StandardCharsets.UTF_8)) => action(request)
      }
      .getOrElse {
        Future.successful(Forbidden(
          ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key")))
      }
  }
}
