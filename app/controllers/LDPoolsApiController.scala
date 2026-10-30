package controllers

import akka.actor.ActorSystem
import api.{ApiHelper, LDPoolsApi, LithosApiErrors}
import configs.Contexts
import play.api.libs.json._
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * The `/dex/pools` endpoints. The list reads a snapshot and answers inline in effect, but a pool's detail
 * reads its vault from the node, so both run on the dex pool rather than Play's request threads.
 */
@Singleton
class LDPoolsApiController @Inject()(cc: ControllerComponents, api: LDPoolsApi, system: ActorSystem)
  extends AbstractController(cc) {

  private val dexContext = new Contexts(system).dexContext

  private def offPool(result: => Result): Future[Result] = Future(result)(dexContext)

  /** GET /dex/pools */
  def listPools(): Action[AnyContent] = Action.async { _ => offPool(respond(api.listPools)) }

  /** GET /dex/pools/:poolNft */
  def getPool(poolNft: String): Action[AnyContent] = Action.async { _ => offPool(respond(api.getPool(poolNft))) }

  private def respond[A](result: => A)(implicit writes: Writes[A]): Result =
    Try(result) match {
      case Success(value) => Ok(Json.toJson(value))
      case Failure(e: LithosApiErrors.LithosBadRequest) =>
        BadRequest(ApiHelper.makeError(400, "Bad request", e.getMessage))
      case Failure(e: LithosApiErrors.LithosNotFound) =>
        NotFound(ApiHelper.makeError(404, "Not found", e.getMessage))
      case Failure(e: LithosApiErrors.LithosUnavailable) =>
        ServiceUnavailable(ApiHelper.makeError(503, "Node unavailable", e.getMessage))
      case Failure(e) =>
        InternalServerError(ApiHelper.makeError(500, "Internal error occurred", e.getMessage))
    }
}
