package api.models

import play.api.libs.json._

/**
 * @param amountIn total handed to the pool, fees included — this is what leaves the wallet
 * @param ergIn    true to sell ERG for the token, false to sell the token for ERG
 */
case class LDSwapRequest(amountIn: String, ergIn: Boolean)

object LDSwapRequest {
  implicit lazy val ldSwapRequestJsonFormat: Format[LDSwapRequest] = Json.format[LDSwapRequest]
}

/**
 * @param minOutput         floor below which the swap must be rejected. Required — without it a swap
 *                          can execute at any price if the pool moves between the quote and the
 *                          submission. Must not be negative: a negative floor is no floor.
 * @param expectedPoolBoxId the pool box the caller was quoted against. Optional, and the only thing
 *                          that makes a re-quote visible: without it a request that raced another
 *                          local mutation is silently re-quoted against whatever the pool became.
 */
case class LDSwapExecuteRequest(amountIn: String,
                                ergIn: Boolean,
                                minOutput: String,
                                expectedPoolBoxId: Option[String] = None)

object LDSwapExecuteRequest {
  implicit lazy val ldSwapExecuteRequestJsonFormat: Format[LDSwapExecuteRequest] =
    Json.format[LDSwapExecuteRequest]
}

/** Desired output and direction, for a reverse quote. */
case class LDSwapOutputRequest(amountOut: String, ergIn: Boolean)

object LDSwapOutputRequest {
  implicit lazy val ldSwapOutputRequestJsonFormat: Format[LDSwapOutputRequest] =
    Json.format[LDSwapOutputRequest]
}

/**
 * @param protocolFee taken out of the input and set aside for liquidity providers
 * @param ammFee      the curve's own fee on the remainder; stays in the pool
 * @param tradedIn    what actually reaches the curve: `amountIn` less `protocolFee`
 * @param isExecutable false when the pool would reject the swap
 */
case class LDSwapQuote(ergIn: Boolean,
                       amountIn: String,
                       protocolFee: String,
                       ammFee: String,
                       tradedIn: String,
                       amountOut: String,
                       totalFee: String,
                       spotBefore: Double,
                       spotAfter: Double,
                       executionPrice: Double,
                       priceImpact: Double,
                       isExecutable: Boolean)

object LDSwapQuote {
  implicit lazy val ldSwapQuoteJsonFormat: Format[LDSwapQuote] = Json.format[LDSwapQuote]

  /** Serialise a quote the library already computed. */
  def apply(q: lithosdex.LDSwapQuote): LDSwapQuote =
    LDSwapQuote(
      ergIn = q.ergIn,
      amountIn = LDAmounts(q.amountIn),
      protocolFee = LDAmounts(q.protocolFee),
      ammFee = LDAmounts(q.ammFee),
      tradedIn = LDAmounts(q.tradedIn),
      amountOut = LDAmounts(q.amountOut),
      totalFee = LDAmounts(q.totalFee),
      spotBefore = q.spotBefore.toDouble,
      spotAfter = q.spotAfter.toDouble,
      executionPrice = q.executionPrice.toDouble,
      priceImpact = q.priceImpact,
      isExecutable = q.isExecutable)
}

case class LDSwapResult(amountOut: String, txId: String)

object LDSwapResult {
  implicit lazy val ldSwapResultJsonFormat: Format[LDSwapResult] = Json.format[LDSwapResult]
}
