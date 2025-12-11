package api


import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import model.ApiError
import model.NISPRepresentation
import model.StratumInfo
import openapitools.OpenApiExceptions
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future

@Singleton
class MiningApiController @Inject()(cc: ControllerComponents, api: MiningApi, config: Configuration) extends AbstractController(cc) {
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

  private def withApiKey[A](action: Action[A]) = Action.async(action.parser) { request =>
    val secretKey = config.get[String]("lithos.apiKeyHash")
    request.headers
      .get("api_key")
      .collect {
        case key if Hex.toHexString(Blake2b256.hash(key)) == secretKey => action(request)
      }
      .getOrElse {
        Future.successful(Forbidden(ApiHelper.makeError(403, "Forbidden request", "Could not authenticate request with given api key")))
      }
  }
}
