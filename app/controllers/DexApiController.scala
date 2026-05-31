package controllers

import api.models.{DepositInfo, RedemptionInfo, SwapInfo}
import api.openapitools.OpenApiExceptions
import api.{ApiHelper, DexApi}
import cache.PDCache
import models._
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
class DexApiController @Inject()(cc: ControllerComponents, api: DexApi, config: Configuration, cache: SyncCacheApi) extends AbstractController(cc) {

  /**
    * POST /dex/syncFee/:feeNFTId
    */
  def syncFee(feeNFTId: String): Action[AnyContent] = withApiKey {
    Action { _ =>
      val tryResult = Try(api.syncFee(feeNFTId, new PDCache(cache)))
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * POST /dex/claimFee/:feeNFTId
    */
  def claimFee(feeNFTId: String): Action[AnyContent] = withApiKey {
    Action { _ =>
      val tryResult = Try(api.claimFee(feeNFTId, new PDCache(cache)))
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * POST /dex/:lpId/withdraw/check
    */
  def checkWithdrawDex(lpId: String): Action[AnyContent] = Action { _ =>
    val tryResult = Try(api.checkWithdrawDex(lpId, new PDCache(cache)))
    tryResult match {
      case Success(result) => Ok(Json.toJson(result))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }
  }

  /**
    * POST /dex/:lpId/withdraw
    */
  def withdrawDex(lpId: String): Action[AnyContent] = withApiKey {
    Action { _ =>
      val tryResult = Try(api.withdrawDex(lpId, new PDCache(cache)))
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * GET /dex
    */
  def getDexInfo(): Action[AnyContent] = Action {
    val result = api.getDexInfo(new PDCache(cache))
    Ok(Json.toJson(result))
  }

  /**
    * GET /dex/:lpId/sync
    */
  def getDexSyncStatus(lpId: String): Action[AnyContent] = Action {
    val result = api.getDexSyncStatus(lpId, new PDCache(cache))
    Ok(Json.toJson(result))
  }

  /**
    * POST /dex/:lpId/swap
    */
  def swapDex(lpId: String): Action[AnyContent] = withApiKey {
    Action { request =>
      val tryResult = Try {
        val swapInfo = request.body.asJson.map(_.as[SwapInfo]).getOrElse {
          throw new OpenApiExceptions.MissingRequiredParameterException("body", "swapInfo")
        }
        api.swapDex(lpId, swapInfo, new PDCache(cache))
      }
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * POST /dex/:lpId/swap/check
    */
  def checkSwapDex(lpId: String): Action[AnyContent] = Action { request =>
    val tryResult = Try {
      val swapInfo = request.body.asJson.map(_.as[SwapInfo]).getOrElse {
        throw new OpenApiExceptions.MissingRequiredParameterException("body", "swapInfo")
      }
      api.checkSwapDex(lpId, swapInfo, new PDCache(cache))
    }
    tryResult match {
      case Success(result) => Ok(Json.toJson(result))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }
  }

  /**
    * POST /dex/:lpId/deposit
    */
  def depositDex(lpId: String): Action[AnyContent] = withApiKey {
    Action { request =>
      val tryResult = Try {
        val depositInfo = request.body.asJson.map(_.as[DepositInfo]).getOrElse {
          throw new OpenApiExceptions.MissingRequiredParameterException("body", "depositInfo")
        }
        api.depositDex(lpId, depositInfo, new PDCache(cache))
      }
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * POST /dex/:lpId/deposit/check
    */
  def checkDepositDex(lpId: String): Action[AnyContent] = Action { request =>
    val tryResult = Try {
      val depositInfo = request.body.asJson.map(_.as[DepositInfo]).getOrElse {
        throw new OpenApiExceptions.MissingRequiredParameterException("body", "depositInfo")
      }
      api.checkDepositDex(lpId, depositInfo, new PDCache(cache))
    }
    tryResult match {
      case Success(result) => Ok(Json.toJson(result))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }
  }

  /**
    * POST /dex/:lpId/redemption
    */
  def redemptionDex(lpId: String): Action[AnyContent] = withApiKey {
    Action { request =>
      val tryResult = Try {
        val redemptionInfo = request.body.asJson.map(_.as[RedemptionInfo]).getOrElse {
          throw new OpenApiExceptions.MissingRequiredParameterException("body", "redemptionInfo")
        }
        api.redemptionDex(lpId, redemptionInfo, new PDCache(cache))
      }
      tryResult match {
        case Success(result) => Ok(Json.toJson(result))
        case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
      }
    }
  }

  /**
    * POST /dex/:lpId/redemption/check
    */
  def checkRedemptionDex(lpId: String): Action[AnyContent] = Action { request =>
    val tryResult = Try {
      val redemptionInfo = request.body.asJson.map(_.as[RedemptionInfo]).getOrElse {
        throw new OpenApiExceptions.MissingRequiredParameterException("body", "redemptionInfo")
      }
      api.checkRedemptionDex(lpId, redemptionInfo, new PDCache(cache))
    }
    tryResult match {
      case Success(result) => Ok(Json.toJson(result))
      case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }
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
