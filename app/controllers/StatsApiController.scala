package controllers

import api.ApiHelper
import api.models.{LocalMiningResponse, StatsResponses}
import org.bouncycastle.util.encoders.Hex
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import stats.{DifficultyEpochs, LocalMiningSummary, MiningHistory, MiningStatsRefresh, PaymentLedger, StatsCache, StatsView}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Chain-derived and pool-wide statistics are read-only and served without a key so a dashboard can
 * load them unattended. Only the local producer detail stays behind the client's API-key boundary:
 * it carries session counters and fraud targets that have not necessarily been submitted yet.
 */
@Singleton
class StatsApiController @Inject()(cc: ControllerComponents, cache: StatsCache, config: Configuration,
                                  mining: MiningStatsRefresh)
  extends AbstractController(cc) {
  import StatsResponses._

  private val logger = LoggerFactory.getLogger("StatsApiController")
  private implicit val executionContext: scala.concurrent.ExecutionContext = cc.executionContext

  def getStats(): Action[AnyContent] = noStore(Action {
    Ok(Json.toJson(cache.snapshot())(StatsView.writes))
  })

  def getMiningTotals(): Action[AnyContent] = noStore(Action {
    Ok(Json.toJson(StatsResponses.totals(mining.view)))
  })

  def getMiningBuckets(): Action[AnyContent] = history { (from, until, width) =>
    mining.buckets(from, until, width).map(value => Ok(Json.toJson(value)))
  }

  def getMiningHashrate(): Action[AnyContent] = history { (from, until, width) =>
    mining.hashrate(from, until, width).map(value => Ok(Json.toJson(value)))
  }

  /**
   * Epoch indices, not timestamps: the table's unit is a difficulty epoch, and a caller graphing
   * the curve wants "the last N epochs" without first having to learn which heights those are.
   */
  def getDifficultyEpochs(from: Option[Int], to: Option[Int], limit: Option[Int]): Action[AnyContent] =
    noStore(Action.async {
      Try(DifficultyEpochs.resolve(from, to, limit.getOrElse(256))) match {
        case Failure(error: IllegalArgumentException) =>
          Future.successful(BadRequest(ApiHelper.makeError(400, "Invalid difficulty epoch range", error.getMessage)))
        case Failure(error) => Future.failed(error)
        case Success((first, last, count)) =>
          mining.difficulty(first, last, count).map(value => Ok(Json.toJson(value))).recover(unavailable)
      }
    })

  def getMiningPayments(offset: Option[Int], limit: Option[Int], sort: Option[String],
                        order: Option[String]): Action[AnyContent] = noStore(Action.async {
    val size = limit.getOrElse(PaymentLedger.DefaultLimit)
    val skip = offset.getOrElse(0)
    val by = sort.getOrElse("paid")
    val direction = order.getOrElse("desc")
    def invalid(reason: String) = Future.successful(BadRequest(ApiHelper.makeError(400, "Invalid payments page", reason)))
    if (size < 1 || size > PaymentLedger.MaxLimit) invalid(s"'limit' must be between 1 and ${PaymentLedger.MaxLimit}")
    else if (skip < 0) invalid("'offset' must be nonnegative")
    else if (!PaymentLedger.Sorts.contains(by)) invalid("'sort' must be 'paid' or 'mined'")
    else if (direction != "asc" && direction != "desc") invalid("'order' must be 'asc' or 'desc'")
    else mining.payments(skip, size, by, direction == "asc").map(value => Ok(Json.toJson(value))).recover(unavailable)
  })

  def getLocalMiningSummary(): Action[AnyContent] = noStore(Action {
    val height = cache.snapshot().local.stratum.activeJob.map(_.height)
    Ok(Json.toJson(LocalMiningSummary.of(cache.settings.enabled, cache.localMiningViews, cache.recentWork, height)))
  })

  def getLocalMiningHistory(): Action[AnyContent] = history { (from, until, width) =>
    mining.localHistory("shares", from, until)
      .map(rows => Ok(Json.toJson(LocalMiningSummary.history(rows, from, until, width, mining.view.status))))
  }

  def getCollateralStats(): Action[AnyContent] = noStore(Action {
    Ok(Json.toJson(mining.collateral))
  })

  def getLocalMiningStats(): Action[AnyContent] = withApiKey(Action {
    Ok(Json.toJson(LocalMiningResponse(cache.settings.enabled, cache.localMiningViews)))
  })

  private def history(read: (Long, Long, Long) => Future[Result]): Action[AnyContent] = noStore(Action.async { request =>
    Try(historyRange(request)) match {
      case Failure(error: IllegalArgumentException) =>
        Future.successful(BadRequest(ApiHelper.makeError(400, "Invalid statistics range", error.getMessage)))
      case Failure(error) => Future.failed(error)
      case Success((from, until, width)) => read(from, until, width).recover(unavailable)
    }
  })

  /** A busy or loading worker is the same answer whichever series was asked for. */
  private def unavailable: PartialFunction[Throwable, Result] = {
    case NonFatal(error) =>
      logger.debug("Statistics history query failed", error)
      ServiceUnavailable(ApiHelper.makeError(503, "Statistics history unavailable",
        "History may be loading, disabled, or busy. Check /stats for collection status and retry later."))
        .withHeaders("Retry-After" -> "1")
  }

  private def historyRange(request: RequestHeader): (Long, Long, Long) = {
    def timestamp(name: String): Long = {
      val value = request.getQueryString(name).getOrElse(
        throw new IllegalArgumentException(s"'$name' is required and must be a Unix timestamp in milliseconds"))
      Try(value.toLong).getOrElse(
        throw new IllegalArgumentException(s"'$name' must be a Unix timestamp in milliseconds within the signed 64-bit range"))
    }
    val from = timestamp("from")
    val until = timestamp("until")
    val width = request.getQueryString("interval").getOrElse("hour") match {
      case "hour" => MiningHistory.HourMs
      case "day" => MiningHistory.DayMs
      case _ => throw new IllegalArgumentException("'interval' must be 'hour' or 'day'")
    }
    MiningHistory.validateRange(from, until, width)
    (from, until, width)
  }

  /** Every statistic is a live observation, so none of them may be cached by an intermediary. */
  private def noStore[A](action: Action[A]): Action[A] = Action.async(action.parser) { request =>
    action(request).map(_.withHeaders("Cache-Control" -> "no-store"))
  }

  private def withApiKey[A](action: Action[A]): Action[A] = noStore(Action.async(action.parser) { request =>
    val expected = config.get[String]("lithos.apiKeyHash").getBytes(StandardCharsets.UTF_8)
    val authorized = request.headers.get("api_key").exists { key =>
      val actual = Hex.toHexString(Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8)
      MessageDigest.isEqual(expected, actual)
    }
    if (authorized) action(request)
    else Future.successful(Forbidden(ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key")))
  })
}
