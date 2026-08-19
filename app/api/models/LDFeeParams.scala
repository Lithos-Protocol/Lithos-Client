package api.models

import play.api.libs.json._

/**
 * Fee numerators over `feeDenom`.
 *
 * These are ratios rather than amounts, so unlike everything else in the tag they stay JSON numbers.
 *
 * @param dexFeeNum   share the curve keeps for itself; accrues to everyone holding LP shares
 * @param ergFeeNum   taken out of an incoming ERG swap and set aside for providers
 * @param tokenFeeNum the same on the token side
 */
case class LDFeeParams(dexFeeNum: Long, ergFeeNum: Long, tokenFeeNum: Long, feeDenom: Long)

object LDFeeParams {
  implicit lazy val ldFeeParamsJsonFormat: Format[LDFeeParams] = Json.format[LDFeeParams]

  /** R5 of the pool box, in the order the contract declares it. */
  def apply(feeParams: Array[Long], feeDenom: Long): LDFeeParams =
    LDFeeParams(feeParams(0), feeParams(1), feeParams(2), feeDenom)
}
