package model

import play.api.libs.json._

/**
  * Error response from API
  * @param error Error code
  * @param reason Error message explaining the reason of the error
  * @param detail Detailed error description
  */
case class ApiError(
  error: Int,
  reason: String,
  detail: String
)

object ApiError {
  implicit lazy val apiErrorJsonFormat: Format[ApiError] = Json.format[ApiError]
}

