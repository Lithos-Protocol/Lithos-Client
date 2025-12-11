package api

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import model.ApiError
import model.BlockMiners
import model.PoolBlock
import openapitools.OpenApiExceptions
import play.api.cache.SyncCacheApi

@Singleton
class BlocksApiController @Inject()(cc: ControllerComponents, api: BlocksApi, cache: SyncCacheApi) extends AbstractController(cc) {
  /**
    * GET /blocks/:utxoId
    * @param utxoId Id of the UTXO representing a Lithos block
    */
  def getBlockById(utxoId: String): Action[AnyContent] = Action { request =>
    def executeApi(): Option[PoolBlock] = {
      api.getBlockById(utxoId, cache)
    }

    val optResult = executeApi()
    optResult match {
      case Some(result) =>
        val json = Json.toJson(result)
        Ok(json)
      case None =>
        NotFound(ApiHelper.makeError(404,
          "Could not find PoolBlock with given utxoId",
          s"PoolBlock with utxoId ${utxoId} could not be found. It may be untracked" +
            s" or could not exist (could've been spent)"))
    }

  }

  /**
    * GET /blocks/:utxoId/miners
    * @param utxoId UTXO id of the pool block whose miners are being queried
    */
  def getBlockMinersById(utxoId: String): Action[AnyContent] = Action { request =>
    def executeApi(): Option[List[BlockMiners]] = {
      api.getBlockMinersById(utxoId, cache)
    }

    val optResult = executeApi()
    optResult match {
      case Some(result) =>
        val json = Json.toJson(result)
        Ok(json)
      case None =>
        NotFound(ApiHelper.makeError(404,
          "Could not find PoolBlock with given utxoId",
          s"PoolBlock with utxoId ${utxoId} could not be found. It may be untracked" +
            s" or could not exist (could've been spent)"))
    }


  }

  /**
    * GET /blocks/byHeight?fromHeight=[value]&toHeight=[value]
    */
  def getBlocksByHeight(): Action[AnyContent] = Action { request =>
    def executeApi(): List[String] = {
      val fromHeight = request.getQueryString("fromHeight")
        .map(value => value.toInt)
        
      val toHeight = request.getQueryString("toHeight")
        .map(value => value.toInt)
        
      api.getBlocksByHeight(fromHeight, toHeight, cache)
    }

    val result = executeApi()
    val json = Json.toJson(result)
    Ok(json)
  }

  /**
    * GET /blocks?limit=[value]&offset=[value]
    */
  def getContractIds(): Action[AnyContent] = Action { request =>
    def executeApi(): List[String] = {
      val limit = request.getQueryString("limit")
        .map(value => value.toInt)
        
      val offset = request.getQueryString("offset")
        .map(value => value.toInt)
        
      api.getContractIds(limit, offset, cache)
    }

    val result = executeApi()
    val json = Json.toJson(result)
    Ok(json)
  }

  /**
    * POST /blocks/utxoIds
    */
  def getPoolBlocksByIds(): Action[AnyContent] = Action { request =>
    def executeApi(): List[Option[PoolBlock]] = {
      val requestBody = request.body.asJson.map(_.as[List[String]]).getOrElse {
        throw new OpenApiExceptions.MissingRequiredParameterException("body", "requestBody")
      }
      api.getPoolBlocksByIds(requestBody, cache)
    }

    val result = executeApi()
    if(result.forall(_.isDefined)) {
      val json = Json.toJson(result.map(_.get))
      Ok(json)
    }else{
      NotFound(ApiHelper.makeError(404, "Could not find PoolBlocks for all given utxoIds",
        "Not all utxoIds corresponded to an existing PoolBlock"))
    }
  }

  /**
   * GET /blocks/all?limit=[value]&offset=[value]
   */
  def getAllPoolBlocks(): Action[AnyContent] = Action { request =>
    def executeApi(): List[PoolBlock] = {
      val limit = request.getQueryString("limit")
        .map(value => value.toInt)

      val offset = request.getQueryString("offset")
        .map(value => value.toInt)

      api.getAllPoolBlocks(limit, offset, cache)
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
