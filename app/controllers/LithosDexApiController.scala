package controllers

import akka.actor.{ActorRef, ActorSystem}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, LithosDexApi}
import cache.LDCache
import configs.Contexts
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import mutations.NotEnoughInputsException
import transactions.dex.LDBoxes
import transactions.wallet.ReservationExpiredException
import transactions.wallet.WalletMessages.InsufficientWalletFundsException
import transactions.wallet.WalletSelector

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * The 18 `lithosdex` endpoints, and the whole of `/dex` since PlasmaDex was removed.
 */
@Singleton
class LithosDexApiController @Inject()(cc: ControllerComponents,
                                       api: LithosDexApi,
                                       config: Configuration,
                                       cache: SyncCacheApi,
                                       @Named("wallet-manager")  walletManager: ActorRef,
                                       system: ActorSystem
                                      ) extends AbstractController(cc) {


  private val walletSelector = WalletSelector(walletManager, 4 seconds, cc.executionContext)

  /**
   * Every endpoint here does blocking node IO — box scans, signing, broadcasting — and the execute
   * paths also Await on the wallet manager. On Play's own request pool a slow node holds request
   * threads for the length of its read timeout, and a history scan can hold one for minutes on an
   * endpoint that needs no API key. Moved to a dedicated fixed pool so that can only exhaust itself.
   *
   * Not a mining concern: the stratum, polling, sync and tx dispatchers are all separate, so nothing
   * here can take the miner off Lithos. It can make the local site stop answering.
   */
  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)
  // ── read-only state ──────────────────────────────────────────────────────

  /** GET /dex/pool */
  def getPool(): Action[AnyContent] = Action.async { _ => offPool(respond(api.getPool(ldCache))) }

  /** GET /dex/vault */
  def getVault(): Action[AnyContent] = Action.async { _ => offPool(respond(api.getVault(ldCache))) }

  /** GET /dex/provisions */
  def listProvisions(): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.listProvisions(ldCache))) }

  // ── swap ─────────────────────────────────────────────────────────────────

  /** POST /dex/swap/check */
  def checkSwap(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkSwap(body[LDSwapRequest](request, "swapRequest"), ldCache)))
  }

  /** POST /dex/swap/checkOutput */
  def checkSwapOutput(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkSwapOutput(body[LDSwapOutputRequest](request, "swapOutputRequest"), ldCache)))
  }

  /** POST /dex/swap */
  def swap(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.swap(body[LDSwapExecuteRequest](request, "swapExecuteRequest"), ldCache, walletSelector)))
    }
  }

  // ── deposit ──────────────────────────────────────────────────────────────

  /** POST /dex/deposit/check */
  def checkDeposit(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkDeposit(body[LDDepositRequest](request, "depositRequest"), ldCache)))
  }

  /** POST /dex/deposit */
  def deposit(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.deposit(body[LDDepositExecuteRequest](request, "depositExecuteRequest"), ldCache, walletSelector)))
    }
  }

  // ── redeem ───────────────────────────────────────────────────────────────

  /** POST /dex/redeem/check */
  def checkRedeem(): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkRedeem(body[LDRedeemRequest](request, "redeemRequest"), ldCache)))
  }

  /** POST /dex/redeem */
  def redeem(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.redeem(body[LDRedeemRequest](request, "redeemRequest"), ldCache, walletSelector)))
    }
  }

  // ── provisions ───────────────────────────────────────────────────────────

  /** POST /dex/provisions/:boxId/claim */
  def claimProvision(boxId: String): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.claimProvision(
        boxId, optionalBody[LDClaimRequest](request, LDClaimRequest.Empty), ldCache, walletSelector)))
    }
  }

  /** POST /dex/provisions/:boxId/resize/check */
  def checkResize(boxId: String): Action[AnyContent] = Action.async { request =>
    offPool(respond(api.checkResize(boxId, body[LDResizeRequest](request, "resizeRequest"), ldCache)))
  }

  /** POST /dex/provisions/:boxId/resize */
  def resize(boxId: String): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.resize(boxId, body[LDResizeRequest](request, "resizeRequest"), ldCache, walletSelector)))
    }
  }

  // ── flush ────────────────────────────────────────────────────────────────

  /** POST /dex/flush/check */
  def checkFlush(): Action[AnyContent] = Action.async { _ => offPool(respond(api.checkFlush(ldCache))) }

  /** POST /dex/flush */
  def flush(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      offPool(respond(api.flush(
        optionalBody[LDFlushRequest](request, LDFlushRequest.Empty), ldCache, walletSelector)))
    }
  }

  // ── fee history ──────────────────────────────────────────────────────────

  /** GET /dex/fees/history */
  def getFeeHistory(from: Option[Int], to: Option[Int], bucket: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getFeeHistory(from, to, bucket, ldCache))) }

  /** GET /dex/price/history */
  def getPriceHistory(range: Option[String], bucket: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getPriceHistory(range, bucket, ldCache))) }

  /** GET /dex/swaps/recent */
  def getRecentSwaps(limit: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getRecentSwaps(limit))) }

  /** GET /dex/provisions/:boxId/fees/history */
  def getProvisionFeeHistory(boxId: String,
                             from: Option[Int],
                             to: Option[Int],
                             bucket: Option[Int]): Action[AnyContent] =
    Action.async { _ => offPool(respond(api.getProvisionFeeHistory(boxId, from, to, bucket, ldCache))) }

  // ── plumbing ─────────────────────────────────────────────────────────────

  private def ldCache: LDCache = new LDCache(cache)

  /**
   * A required body. A body that is present but malformed is a 400 like a missing one, rather than
   * the 500 a `JsResultException` escaping to the catch-all used to produce.
   */
  private def body[A](request: Request[AnyContent], name: String)(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => throw new OpenApiExceptions.MissingRequiredParameterException("body", name)
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(errors) => throw LithosBadRequest(s"'$name' is not valid: ${describe(errors)}")
      }
    }

  /** A body that may be omitted entirely, but must parse if it is sent. */
  private def optionalBody[A](request: Request[AnyContent], empty: A)(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => empty
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(errors) => throw LithosBadRequest(s"request body is not valid: ${describe(errors)}")
      }
    }

  private def describe(errors: Seq[(JsPath, Seq[JsonValidationError])]): String =
    errors.map { case (path, messages) =>
      s"${path.toJsonString} ${messages.flatMap(_.messages).mkString(", ")}"
    }.mkString("; ")

  /**
   * One place the status codes are decided, and the reason each one is what a caller can act on.
   *
   * 400 the request is wrong; 409 a singleton moved, so re-quote and resend; 422 the request is fine
   * and the pool cannot do it; 404 the box is not there; 503 the node, index or wallet could not
   * answer, so nothing about this is decided yet. Only a defect in this client reaches 500 — the
   * previous mapping sent insufficient funds, a malformed body and an unreachable node all there,
   * which tells a caller to retry, re-read or give up with no way to tell which.
   */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: LithosBadRequest) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosStateChanged) =>
        Conflict(Json.toJson(LDStateChangedResponse(e)))
      case Failure(e: LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      // Named types, not `NoSuchElementException`. Matching the JDK type meant every accidental
      // `.get` on a `None` and every `.head` on an empty collection anywhere under the API was
      // reported as "not found" — the one status that tells a caller to stop trying.
      case Failure(e: LithosNotFound) =>
        NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LDBoxes.NoSuchBoxException) =>
        NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      // The box is there and something else is spending it, so there is a successor to read. That is
      // a conflict to retry against, not a 404 telling a UI the position is gone.
      case Failure(e: LDBoxes.BoxBeingSpentException) =>
        Conflict(Json.toJson(LDStateChangedResponse(LithosStateChanged(e.getMessage))))
      // A lease that expired between building and broadcasting. A timing condition the caller
      // resolves by re-reading and resending, so it must not look like a defect in this client.
      case Failure(e: ReservationExpiredException) =>
        Conflict(Json.toJson(LDStateChangedResponse(LithosStateChanged(e.getMessage))))
      // Insufficient selectable funds is a fact about this wallet right now, not a defect: a later
      // request after a confirmation or a refresh can succeed unchanged.
      case Failure(e: NotEnoughInputsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: InsufficientWalletFundsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      // The node, its index, its mempool or its wallet did not answer. Nothing is wrong with the
      // request and nothing has been decided, so this must not look like either 400 or 500.
      case Failure(e: LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: LDBoxes.IndexUnavailableException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: LDBoxes.IndexTruncatedException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Answer would be partial", e.getMessage))
      // A wallet ask that timed out is the selector under load, not a bad request.
      case Failure(e: java.util.concurrent.TimeoutException) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      // `require` in the transaction builders guards conditions the contracts would reject. That is a
      // statement about the request, not a fault in the client, so it is a 400 rather than a 500.
      case Failure(e: IllegalArgumentException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) =>
        InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  /**
   * Read once at construction, not per request.
   *
   * `config.get` throws when the key is absent, and it was being called inside the action — outside
   * `respond`, so an unset `lithos.apiKeyHash` produced a raw 500 with a stack trace on every
   * authenticated request instead of failing the client at startup where it can be fixed.
   */
  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers
      .get("api_key")
      // Constant-time: this compares hashes rather than secrets, so a timing signal leaks nothing
      // usable — but the comparison costs the same either way and the habit is worth keeping.
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
