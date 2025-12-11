package api

import model.ApiError
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
object ApiHelper {
  def makeError(code: Int, reason: String, detail: String): JsValue = {
    val error = ApiError(code, reason, detail)
    val json = Json.toJson(error)
    json
  }

  def handlePagination(offset: Option[Int], limit: Option[Int]): (Int, Int) = {
    val start = offset.flatMap(o => if(o < 0) None else Some(o))
    val lim = limit.flatMap(l => if(l < 10 || l > 100) None else Some(l))
    start.getOrElse(0) -> lim.getOrElse(50)
  }

}
