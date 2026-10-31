package api.models

import play.api.libs.json._

/**
 * A swap order on any pool. `tokenIn` is "ERG" or the id of one of the pool's tokens; the order sells it for
 * the pool's other side. Fees the request leaves out come from `lithosdex.orders`.
 */
case class LDPoolSwapRequest(tokenIn: String, amountIn: String, executorFee: Option[String] = None,
                             maxMinerFee: Option[String] = None)

object LDPoolSwapRequest {
  implicit lazy val jsonFormat: Format[LDPoolSwapRequest] = Json.format[LDPoolSwapRequest]
}

/**
 * @param minOutput             the least of the other side the order accepts, net of the fee when that is ERG
 * @param acknowledgeUnverified must be true to trade with a pool the verified list does not name
 */
case class LDPoolSwapExecuteRequest(tokenIn: String, amountIn: String, minOutput: String,
                                    executorFee: Option[String] = None, maxMinerFee: Option[String] = None,
                                    acknowledgeUnverified: Option[Boolean] = None)

object LDPoolSwapExecuteRequest {
  implicit lazy val jsonFormat: Format[LDPoolSwapExecuteRequest] = Json.format[LDPoolSwapExecuteRequest]
}

/**
 * What a swap order would fill at against the pool now.
 *
 * @param minAmountIn      the least input that still buys `amountOut`; anything above it buys nothing more, which
 *                         matters for a token whose single unit is worth a lot. Absent when nothing comes out
 * @param netOutput        what the owner receives of `tokenOut`: the pool's output, less the executor fee
 *                         when that output is ERG
 * @param totalErgRequired nanoERG the order box holds, which leaves the wallet at placement
 * @param tokensRequired   tokens the order box holds
 * @param ergReturned      nanoERG that comes back beside the fill
 * @param priceImpact      how far the fill falls short of the pool's price before it, fees included, as a fraction
 * @param protocolFee      of `tokenIn`, set aside for the pool's providers
 * @param ammFee           of `tokenIn`, the curve's own fee, which stays in the reserves
 */
case class LDPoolSwapQuote(poolNFT: String, tokenIn: String, tokenOut: String, amountIn: String, amountOut: String,
                           minAmountIn: Option[String], netOutput: String, executable: Boolean, executorFee: String, maxMinerFee: String,
                           totalErgRequired: String, tokensRequired: Seq[LDTokenAmount], ergReturned: String,
                           networkFee: String, priceImpact: Double = 0.0, protocolFee: String = "0", ammFee: String = "0")

object LDPoolSwapQuote {
  implicit lazy val jsonFormat: Format[LDPoolSwapQuote] = Json.format[LDPoolSwapQuote]
}

/** A deposit order on any pool: X is ERG for an ERG/token pool and token X for a token/token one. */
case class LDPoolDepositRequest(amountX: String, amountY: String, executorFee: Option[String] = None,
                                maxMinerFee: Option[String] = None)

object LDPoolDepositRequest {
  implicit lazy val jsonFormat: Format[LDPoolDepositRequest] = Json.format[LDPoolDepositRequest]
}

case class LDPoolDepositExecuteRequest(amountX: String, amountY: String, minShares: String,
                                       executorFee: Option[String] = None, maxMinerFee: Option[String] = None,
                                       acknowledgeUnverified: Option[Boolean] = None)

object LDPoolDepositExecuteRequest {
  implicit lazy val jsonFormat: Format[LDPoolDepositExecuteRequest] = Json.format[LDPoolDepositExecuteRequest]
}

/** The fill a deposit order would get now: shares from the scarcer side, and the other side's excess returned. */
case class LDPoolDepositQuote(poolNFT: String, shares: String, takenX: String, takenY: String, excessX: String,
                              excessY: String, shareOfSupply: Double, provisionBoxValue: String, executorFee: String,
                              maxMinerFee: String, totalErgRequired: String, tokensRequired: Seq[LDTokenAmount],
                              ergReturned: String, networkFee: String)

object LDPoolDepositQuote {
  implicit lazy val jsonFormat: Format[LDPoolDepositQuote] = Json.format[LDPoolDepositQuote]
}

case class LDPoolRedeemRequest(provisionBoxId: String, executorFee: Option[String] = None,
                               maxMinerFee: Option[String] = None)

object LDPoolRedeemRequest {
  implicit lazy val jsonFormat: Format[LDPoolRedeemRequest] = Json.format[LDPoolRedeemRequest]
}

/**
 * What closing a provision through an order returns now.
 *
 * @param amountX    the provision's share of reserve X; nanoERG for an ERG/token pool
 * @param claimableX fees the placement claims from the vault before the order is placed; a fill forfeits
 *                   anything left unclaimed
 * @param ergReturned nanoERG the owner receives beside the reserves: the provision box's ERG and the order's
 *                   return, less the executor fee where it comes out of them
 */
case class LDPoolRedeemQuote(poolNFT: String, provisionBoxId: String, shares: String, amountX: String,
                             amountY: String, claimableX: String, claimableY: String, provisionValue: String,
                             ergReturned: String, withinMinSupply: Boolean, executorFee: String,
                             maxMinerFee: String, totalErgRequired: String, networkFee: String)

object LDPoolRedeemQuote {
  implicit lazy val jsonFormat: Format[LDPoolRedeemQuote] = Json.format[LDPoolRedeemQuote]
}

/**
 * A placed order.
 *
 * @param kind           SWAP, DEPOSIT or REDEEM
 * @param provisionBoxId a redeem placement that claimed first: the provision's successor
 */
case class LDPoolOrderPlaced(outcome: String, txId: String, poolNFT: String, kind: String, orderBoxId: String,
                             claimedX: Option[String], claimedY: Option[String], provisionBoxId: Option[String])

object LDPoolOrderPlaced {
  implicit lazy val jsonFormat: Format[LDPoolOrderPlaced] = Json.format[LDPoolOrderPlaced]
}

/**
 * A direct swap against any pool, spending it now rather than placing an order. `minOutput` is checked against the
 * pool box this request spends, and `expectedPoolBoxId`, when given, refuses a pool that has moved since the quote.
 */
case class LDPoolDirectSwapRequest(tokenIn: String, amountIn: String, minOutput: String,
                                   expectedPoolBoxId: Option[String] = None,
                                   acknowledgeUnverified: Option[Boolean] = None)

object LDPoolDirectSwapRequest {
  implicit lazy val jsonFormat: Format[LDPoolDirectSwapRequest] = Json.format[LDPoolDirectSwapRequest]
}

/**
 * What a direct swap would return against the pool now. No executor is paid: the wallet pays the network fee.
 *
 * @param minAmountIn the least input that still buys `amountOut`. Absent when nothing comes out
 * @param priceImpact how far the swap falls short of the pool's price before it, fees included, as a fraction
 * @param protocolFee of `tokenIn`, set aside for the pool's providers
 * @param ammFee      of `tokenIn`, the curve's own fee, which stays in the reserves
 * @param poolBoxId   the pool box quoted against, to send back as `expectedPoolBoxId`
 */
case class LDPoolDirectSwapQuote(poolNFT: String, tokenIn: String, tokenOut: String, amountIn: String, amountOut: String,
                                 minAmountIn: Option[String], executable: Boolean, priceImpact: Double,
                                 protocolFee: String, ammFee: String, networkFee: String, poolBoxId: String)

object LDPoolDirectSwapQuote {
  implicit lazy val jsonFormat: Format[LDPoolDirectSwapQuote] = Json.format[LDPoolDirectSwapQuote]
}

/**
 * A direct deposit offering `amountX` and `amountY`. The pool takes the balanced part for the most shares it can
 * issue; the rest stays in the wallet. Refused below `minShares`.
 */
case class LDPoolDirectDepositRequest(amountX: String, amountY: String, minShares: String,
                                      expectedPoolBoxId: Option[String] = None,
                                      acknowledgeUnverified: Option[Boolean] = None)

object LDPoolDirectDepositRequest {
  implicit lazy val jsonFormat: Format[LDPoolDirectDepositRequest] = Json.format[LDPoolDirectDepositRequest]
}

/**
 * What a direct deposit would take and issue against the pool now.
 *
 * @param totalErgRequired nanoERG leaving the wallet: X itself on an ERG/token pool, the provision box, the owner
 *                         NFT's box and the network fee
 */
case class LDPoolDirectDepositQuote(poolNFT: String, shares: String, takenX: String, takenY: String, excessX: String,
                                    excessY: String, shareOfSupply: Double, provisionBoxValue: String,
                                    totalErgRequired: String, networkFee: String, poolBoxId: String)

object LDPoolDirectDepositQuote {
  implicit lazy val jsonFormat: Format[LDPoolDirectDepositQuote] = Json.format[LDPoolDirectDepositQuote]
}

/**
 * A direct redemption of one of this wallet's provisions. Fees the vault owes it are lost with the box, so a
 * provision with any needs `acknowledgeUnclaimedFees`.
 */
case class LDPoolDirectRedeemRequest(provisionBoxId: String, acknowledgeUnclaimedFees: Option[Boolean] = None,
                                     expectedPoolBoxId: Option[String] = None)

object LDPoolDirectRedeemRequest {
  implicit lazy val jsonFormat: Format[LDPoolDirectRedeemRequest] = Json.format[LDPoolDirectRedeemRequest]
}

/**
 * What a direct redemption returns now.
 *
 * @param ergReturned the provision box's ERG, which comes back beside the reserves
 * @param unclaimedX  fees the vault owes the provision now, lost when its box is spent
 */
case class LDPoolDirectRedeemQuote(poolNFT: String, provisionBoxId: String, shares: String, amountX: String,
                                   amountY: String, ergReturned: String, unclaimedX: String, unclaimedY: String,
                                   withinMinSupply: Boolean, networkFee: String, poolBoxId: String)

object LDPoolDirectRedeemQuote {
  implicit lazy val jsonFormat: Format[LDPoolDirectRedeemQuote] = Json.format[LDPoolDirectRedeemQuote]
}

/**
 * A direct spend sent. `amountX`/`amountY` are what moved on each side: for a swap, `amountX` is what was sold and
 * `amountY` what came back; for a deposit and a redemption, X and Y in pool order.
 */
case class LDPoolDirectResult(outcome: String, txId: String, poolNFT: String, kind: String, amountX: String,
                              amountY: String, shares: Option[String] = None, ownerNFT: Option[String] = None,
                              provisionBoxId: Option[String] = None)

object LDPoolDirectResult {
  implicit lazy val jsonFormat: Format[LDPoolDirectResult] = Json.format[LDPoolDirectResult]
}
