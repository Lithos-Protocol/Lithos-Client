package controllers

import akka.actor.ActorSystem
import api.models.PaymentTransaction
import api.{ApiHelper, LithosApiErrors, PaymentsApi}
import configs.Contexts
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json._
import play.api.mvc._
import scorex.crypto.hash.Blake2b256

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class PaymentsApiController @Inject()(cc: ControllerComponents,
                                      api: PaymentsApi,
                                      config: Configuration,
                                      cache: SyncCacheApi,
                                      system: ActorSystem) extends AbstractController(cc) {

  // Run blocking index scans outside request and synchronization threads.
  private val nodeIoContext = new Contexts(system).dexContext

  /**
    * GET /payments?limit=[value]&offset=[value]
    */
  def getPayments(): Action[AnyContent] = withApiKey {
    Action.async { request =>
      Future {
        respond {
          val limit = request.getQueryString("limit")
            .map(value => value.toInt)

          val offset = request.getQueryString("offset")
            .map(value => value.toInt)

          api.getPayments(limit, offset, config, cache)
        }
      }(nodeIoContext)
    }
  }

  private def respond(result: => List[PaymentTransaction]): Result =
    Try(result) match {
      case Success(payments) if payments.nonEmpty => Ok(Json.toJson(payments))
      case Success(_) =>
        NotFound(ApiHelper.makeError(404, "Could not find payment transactions",
          "Unable to find any Lithos payment transactions"))
      case Failure(e: LithosApiErrors.LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e: IllegalArgumentException) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e) =>
        InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
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
