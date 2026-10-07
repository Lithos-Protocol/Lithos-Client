package controllers

import akka.actor.ActorSystem
import api.models.{CommitmentRequest, DifficultyCommitment, NISPRepresentation, StratumInfo}
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, LithosApiErrors, MiningApi}
import configs.Contexts
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256
import transactions.engine.wallet.EngineWalletMessages.InsufficientWalletFundsException
import transactions.engine.wallet.FundingExpiredException

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class MiningApiController @Inject()(cc: ControllerComponents, api: MiningApi, config: Configuration,
                                    system: ActorSystem) extends AbstractController(cc) {
  import DifficultyCommitment._

  // The commitment endpoints make blocking node calls, so they run off Play's request threads.
  // TODO: Make separate context for this?
  private val ioContext = new Contexts(system).dexContext

  /**
    * GET /mining/bestNISP?height=[value]&score=[value]
    */
  def getBestNISPAtHeight(): Action[AnyContent] = withApiKey {
    Action { request =>
      def executeApi(): Option[NISPRepresentation] = {
        val height = request.getQueryString("height")
          .map(value => value.toInt)
          .getOrElse {
            throw new OpenApiExceptions.MissingRequiredParameterException("height", "query string")
          }

        val score = request.getQueryString("score")
          .map(value => value.toLong)
          .getOrElse {
            throw new OpenApiExceptions.MissingRequiredParameterException("score", "query string")
          }

        api.getBestNISPAtHeight(height, score)
      }

      val optResult = executeApi()
      optResult match {
        case Some(result) =>
          val json = Json.toJson(result)
          Ok(json)
        case None =>
          NotFound(ApiHelper.makeError(404, "Could not find NISP",
            "Could not produce best NISP with 10 super shares for given height and score"))
      }

    }
  }

  /**
    * GET /mining
    */
  def getStratumInfo(): Action[AnyContent] = Action { request =>
    def executeApi(): StratumInfo = {
      api.getStratumInfo(config)
    }

    val result = executeApi()
    val json = Json.toJson(result)
    Ok(json)
  }

  /**
    * GET /mining/candidate
    */
  def getCandidateSettings(): Action[AnyContent] = Action {
    Ok(Json.toJson(api.getCandidateSettings(config)))
  }

  /**
    * GET /mining/commitment
    *
    * Open, like the statistics reads: the commitment is public on chain, and a page shows it on load.
    */
  def getCommitment(): Action[AnyContent] = Action.async {
    Future(respond(api.getCommitment))(ioContext)
  }

  /**
    * POST /mining/commitment
    */
  def commit(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      Future(respond(api.commit(body[CommitmentRequest](request))))(ioContext)
    }
  }

  private def body[A](request: Request[AnyContent])(implicit reads: Reads[A]): A =
    request.body.asJson match {
      case None => throw new OpenApiExceptions.MissingRequiredParameterException("body", "request")
      case Some(json) => json.validate[A] match {
        case JsSuccess(value, _) => value
        case JsError(_) => throw LithosApiErrors.LithosBadRequest("request body must be {\"diff\": \"<diff>\"}")
      }
    }

  /**
    * 400 the request is wrong; 409 something it depends on is held or moved, read the status and
    * retry; 422 it cannot be done yet or the wallet cannot pay; 503 the chain could not be read.
    */
  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: LithosApiErrors.LithosBadRequest) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: OpenApiExceptions.MissingRequiredParameterException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosApiErrors.LithosStateChanged) => Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnprocessable) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: NotEnoughInputsException) => UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: InsufficientWalletFundsException) =>
        UnprocessableEntity(ApiHelper.makeError(422, "Cannot be executed", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnavailable) => ServiceUnavailable(ApiHelper.makeError(503, "Unavailable", e.getMessage))
      case Failure(e: FundingExpiredException) => ServiceUnavailable(ApiHelper.makeError(503, "Wallet busy", e.getMessage))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }

  private def splitCollectionParam(paramValues: String, collectionFormat: String): List[String] = {
    val splitBy =
      collectionFormat match {
        case "csv" => ",+"
        case "tsv" => "\t+"
        case "ssv" => " +"
        case "pipes" => "|+"
      }

    paramValues.split(splitBy).toList
  }

  private val apiKeyHash: String = config.get[String]("lithos.apiKeyHash")

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    request.headers
      .get("api_key")
      .collect {
        case key if MessageDigest.isEqual(Hex.toHexString(Blake2b256.hash(key)).getBytes(StandardCharsets.UTF_8),
          apiKeyHash.getBytes(StandardCharsets.UTF_8)) => action(request)
      }
      .getOrElse {
        Future.successful(Forbidden(ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key")))
      }
  }
}
