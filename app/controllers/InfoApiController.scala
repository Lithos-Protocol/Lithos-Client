package controllers

import api.InfoApi
import api.models.LithosInfo
import play.api.cache.SyncCacheApi
import play.api.libs.json._
import play.api.mvc._

import javax.inject.{Inject, Singleton}

@Singleton
class InfoApiController @Inject()(cc: ControllerComponents, api: InfoApi, cache: SyncCacheApi) extends AbstractController(cc) {
  /**
    * GET /info
    */
  def getLithosInfo(): Action[AnyContent] = Action { request =>
    def executeApi(): LithosInfo = {
      api.getLithosInfo(cache)
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
}
