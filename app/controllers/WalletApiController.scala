package controllers

import akka.actor.ActorSystem
import api.models._
import api.{ApiHelper, LithosApiErrors, WalletApi}
import configs.Contexts
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The wallet endpoints. Balances and rewards do blocking node IO, so each runs on the dex pool rather
 * than Play's request threads, matching the LithosDex and collateral-market controllers. The verified
 * list is in memory and answers inline.
 */
@Singleton
class WalletApiController @Inject()(cc: ControllerComponents,
                                    api: WalletApi,
                                    config: Configuration,
                                    system: ActorSystem
                                   ) extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  /** GET /wallet/balances */
  def getWalletBalances(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.getWalletBalances)) }
  }

  /** GET /wallet/rewards */
  def getRewardSummary(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.getRewardSummary)) }
  }

  /** POST /wallet/rewards/claim */
  def claimRewards(): Action[AnyContent] = withApiKey {
    Action.async { _ => offPool(respond(api.claimUnlockedRewards)) }
  }

  /** GET /wallet/tokens/verified */
  def getVerifiedTokens(): Action[AnyContent] = Action { _ => respond(api.getVerifiedTokens) }

  /** Same mapping as the other controllers, minus the cases these endpoints cannot raise. */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: WalletApi.NothingToClaim) =>
        BadRequest(ApiHelper.makeError(400, "Nothing to claim", e.getMessage))
      case Failure(e: LithosApiErrors.LithosBadRequest) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: java.util.concurrent.TimeoutException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      case Failure(e: IllegalArgumentException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) =>
        InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  // Read once at construction, not per request: `config.get` throws when the key is absent, and
  // inside the action that is a raw 500 with a stack trace on every authenticated request.
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
