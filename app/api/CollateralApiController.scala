package api


import configs.NodeConfig

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import model.ApiError
import model.CollateralInfo
import model.CollateralUTXO
import model.SuccessfulTransaction
import mutations.NotEnoughInputsException
import openapitools.OpenApiExceptions
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class CollateralApiController @Inject()(cc: ControllerComponents, api: CollateralApi, config: Configuration) extends AbstractController(cc) {
  /**
    * POST /collateral/create
    */
  def createCollateralUTXO(): Action[AnyContent] = withApiKey {
    Action { request =>
      def executeApi(): Try[SuccessfulTransaction] = {
        val collateralInfo = request.body.asJson.map(_.as[CollateralInfo]).getOrElse {
          throw new OpenApiExceptions.MissingRequiredParameterException("body", "collateralInfo")
        }
        Try(api.createCollateralUTXO(collateralInfo, config))
      }

      val tryResult = executeApi()
      tryResult match {
        case Failure(exception) =>
          exception match {
            case _: NotEnoughInputsException =>
              NotFound(
                ApiHelper.makeError(
                  404,
                  "Not enough inputs",
                  "Could not collect enough inputs to cover all collateral UTXOs"
                )
              )
            case _ =>
              InternalServerError(
                ApiHelper.makeError(
                  500,
                  "Internal error occurred",
                  exception.getMessage
                )
              )
          }
        case Success(result) =>
          val json = Json.toJson(result)
          Ok(json)
      }

    }
  }

  /**
    * GET /collateral/all?limit=[value]&offset=[value]
    */
  def getAllCollateralInfo(): Action[AnyContent] = Action { request =>
    def executeApi(): List[CollateralUTXO] = {
      val limit = request.getQueryString("limit")
        .map(value => value.toInt)
        
      val offset = request.getQueryString("offset")
        .map(value => value.toInt)
        
      api.getAllCollateralInfo(limit, offset, config)
    }

    val result = executeApi()
    val json = Json.toJson(result)
    Ok(json)
  }

  /**
    * GET /collateral/local?limit=[value]&offset=[value]
    */
  def getLocalCollateralInfo(): Action[AnyContent] = withApiKey {
    Action { request =>
      def executeApi(): List[CollateralUTXO] = {
        val limit = request.getQueryString("limit")
          .map(value => value.toInt)

        val offset = request.getQueryString("offset")
          .map(value => value.toInt)

        api.getLocalCollateralInfo(limit, offset, config)
      }

      val result = executeApi()
      val json = Json.toJson(result)
      Ok(json)
    }
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
