package api

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import model.ApiError
import model.PaymentTransaction
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import play.api.Configuration
import play.api.cache.SyncCacheApi
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future

@Singleton
class PaymentsApiController @Inject()(cc: ControllerComponents, api: PaymentsApi, config: Configuration, cache: SyncCacheApi) extends AbstractController(cc) {
  /**
    * GET /payments?limit=[value]&offset=[value]
    */
  def getPayments(): Action[AnyContent] = withApiKey {
    Action { request =>
      def executeApi(): List[PaymentTransaction] = {
        val limit = request.getQueryString("limit")
          .map(value => value.toInt)

        val offset = request.getQueryString("offset")
          .map(value => value.toInt)

        api.getPayments(limit, offset, config, cache)
      }

      val result = executeApi()
      if(result.nonEmpty) {
        val json = Json.toJson(result)
        Ok(json)
      }else{
        NotFound(ApiHelper.makeError(404, "Could not find payment transactions", "Unable to find any Lithos payment transactions"))
      }
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
