package controllers

import akka.actor.ActorSystem
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable}
import api.models.Oracles._
import api.{ApiHelper, OraclesApi}
import configs.Contexts
import play.api.libs.json.{Json, Writes}
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

@Singleton
class OraclesApiController @Inject()(cc: ControllerComponents, api: OraclesApi, system: ActorSystem)
  extends AbstractController(cc) {
  private val reads = new Contexts(system).dexContext
  def listFeeds(): Action[AnyContent] = Action { _ => respond(api.listFeeds()) }
  def getFeed(feedId: String, offset: Option[Int], limit: Option[Int], atBlockId: Option[String]): Action[AnyContent] =
    Action.async { _ => Future(respond(api.getFeed(feedId, offset, limit, atBlockId)))(reads) }

  private def respond[A: Writes](result: => A): Result = Try(result) match {
    case Success(value) => Ok(Json.toJson(value))
    case Failure(e: LithosBadRequest) => BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
    case Failure(e: LithosNotFound) => NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
    case Failure(e: LithosStateChanged) => Conflict(ApiHelper.makeError(409, "State changed", e.getMessage))
    case Failure(e: LithosUnavailable) => ServiceUnavailable(ApiHelper.makeError(503, "Oracle unavailable", e.getMessage))
    case Failure(e) => InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
  }
}
