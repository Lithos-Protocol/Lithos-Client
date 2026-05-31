package api.models

import play.api.libs.json._

case class SwapResult(swapOutput: Long, txId: String)

object SwapResult {
  implicit lazy val swapResultJsonFormat: Format[SwapResult] = Json.format[SwapResult]
}
