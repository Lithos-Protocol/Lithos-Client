package api.models

import play.api.libs.json._

/**
 * A swap order to quote.
 *
 * @param amountIn    total handed to the pool, pool fees included; the executor fee is separate
 * @param executorFee nanoERG paid to whoever fills the order, or the client's default
 * @param maxMinerFee most of `executorFee` a fill may pay as a miner fee, or the client's default
 */
case class LDSwapOrderRequest(amountIn: String,
                              ergIn: Boolean,
                              executorFee: Option[String] = None,
                              maxMinerFee: Option[String] = None)

object LDSwapOrderRequest {
  implicit lazy val ldSwapOrderRequestJsonFormat: Format[LDSwapOrderRequest] = Json.format[LDSwapOrderRequest]
}

/** @param minOutput least the owner receives: tokens when selling ERG, nanoERG after the executor fee otherwise */
case class LDSwapOrderExecuteRequest(amountIn: String,
                                     ergIn: Boolean,
                                     minOutput: String,
                                     executorFee: Option[String] = None,
                                     maxMinerFee: Option[String] = None)

object LDSwapOrderExecuteRequest {
  implicit lazy val ldSwapOrderExecuteRequestJsonFormat: Format[LDSwapOrderExecuteRequest] =
    Json.format[LDSwapOrderExecuteRequest]
}

/**
 * @param netOutput        what the owner receives if the order fills against the current pool
 * @param totalErgRequired nanoERG locked in the order box
 * @param ergReturned      the part of `totalErgRequired` returned beside the fill
 * @param networkFee       nanoERG paid to place the order
 */
case class LDSwapOrderQuote(swap: LDSwapQuote,
                            executorFee: String,
                            maxMinerFee: String,
                            netOutput: String,
                            totalErgRequired: String,
                            ergReturned: String,
                            networkFee: String)

object LDSwapOrderQuote {
  implicit lazy val ldSwapOrderQuoteJsonFormat: Format[LDSwapOrderQuote] = Json.format[LDSwapOrderQuote]
}

/**
 * A deposit order to quote.
 *
 * @param amountX most nanoERG offered to the reserves, not counting the provision box
 * @param amountY raw token units offered
 */
case class LDDepositOrderRequest(amountX: String,
                                 amountY: String,
                                 executorFee: Option[String] = None,
                                 maxMinerFee: Option[String] = None)

object LDDepositOrderRequest {
  implicit lazy val ldDepositOrderRequestJsonFormat: Format[LDDepositOrderRequest] = Json.format[LDDepositOrderRequest]
}

/** @param minShares fewest shares the order accepts, checked against the pool when it fills */
case class LDDepositOrderExecuteRequest(amountX: String,
                                        amountY: String,
                                        minShares: String,
                                        executorFee: Option[String] = None,
                                        maxMinerFee: Option[String] = None)

object LDDepositOrderExecuteRequest {
  implicit lazy val ldDepositOrderExecuteRequestJsonFormat: Format[LDDepositOrderExecuteRequest] =
    Json.format[LDDepositOrderExecuteRequest]
}

/**
 * @param amountX     nanoERG the pool would take
 * @param excessX     nanoERG offered beyond what the pool would take, returned with the fill
 * @param ergReturned nanoERG returned beside the ownership NFT, not counting `excessX`
 */
case class LDDepositOrderQuote(shares: String,
                               amountX: String,
                               amountY: String,
                               excessX: String,
                               excessY: String,
                               shareOfSupply: Double,
                               provisionBoxValue: String,
                               executorFee: String,
                               maxMinerFee: String,
                               totalErgRequired: String,
                               ergReturned: String,
                               networkFee: String)

object LDDepositOrderQuote {
  implicit lazy val ldDepositOrderQuoteJsonFormat: Format[LDDepositOrderQuote] = Json.format[LDDepositOrderQuote]
}

/** The provision a redeem order closes. Used both to quote and to place. */
case class LDRedeemOrderRequest(provisionBoxId: String,
                                executorFee: Option[String] = None,
                                maxMinerFee: Option[String] = None)

object LDRedeemOrderRequest {
  implicit lazy val ldRedeemOrderRequestJsonFormat: Format[LDRedeemOrderRequest] = Json.format[LDRedeemOrderRequest]
}

/**
 * @param receivedX  nanoERG paid to the owner if the order fills against the current pool
 * @param claimableX settled ERG fees the placement claims
 * @param unflushedX ERG fees earned but not flushed, lost when the order fills
 */
case class LDRedeemOrderQuote(shares: String,
                              amountX: String,
                              amountY: String,
                              provisionValue: String,
                              receivedX: String,
                              claimableX: String,
                              claimableY: String,
                              unflushedX: String,
                              unflushedY: String,
                              withinMinSupply: Boolean,
                              executorFee: String,
                              maxMinerFee: String,
                              totalErgRequired: String,
                              networkFee: String)

object LDRedeemOrderQuote {
  implicit lazy val ldRedeemOrderQuoteJsonFormat: Format[LDRedeemOrderQuote] = Json.format[LDRedeemOrderQuote]
}

/**
 * An outstanding order owned by this wallet. The term fields present depend on `type`.
 *
 * @param status       PENDING, OPEN, FILLING or CANCELLING
 * @param placedHeight height the placement confirmed at; absent while it is unconfirmed
 * @param spendingTxId the unconfirmed transaction filling or cancelling the order
 * @param fillableNow  whether the order would fill against the current pool; always true while FILLING and
 *                     false while CANCELLING
 */
case class LDOrder(boxId: String,
                   `type`: String,
                   status: String,
                   placementTxId: String,
                   placedHeight: Option[Int],
                   spendingTxId: Option[String],
                   owner: String,
                   value: String,
                   executorFee: String,
                   maxMinerFee: String,
                   ergIn: Option[Boolean] = None,
                   amountIn: Option[String] = None,
                   minOutput: Option[String] = None,
                   amountX: Option[String] = None,
                   amountY: Option[String] = None,
                   minShares: Option[String] = None,
                   ownerNFT: Option[String] = None,
                   provisionBoxId: Option[String] = None,
                   fillableNow: Boolean)

object LDOrder {
  implicit lazy val ldOrderJsonFormat: Format[LDOrder] = Json.format[LDOrder]
}

case class LDOrderList(orders: Seq[LDOrder])

object LDOrderList {
  implicit lazy val ldOrderListJsonFormat: Format[LDOrderList] = Json.format[LDOrderList]
}

/** @param claimedX settled ERG fees a redeem placement claimed; absent for other types */
case class LDOrderPlacementResult(outcome: String,
                                  txId: String,
                                  order: LDOrder,
                                  claimedX: Option[String] = None,
                                  claimedY: Option[String] = None)

object LDOrderPlacementResult {
  implicit lazy val ldOrderPlacementResultJsonFormat: Format[LDOrderPlacementResult] =
    Json.format[LDOrderPlacementResult]
}

case class LDTokenAmount(tokenId: String, amount: String)

object LDTokenAmount {
  implicit lazy val ldTokenAmountJsonFormat: Format[LDTokenAmount] = Json.format[LDTokenAmount]
}

/**
 * @param returnedNanoErgs nanoERG the order box held, returned to this wallet
 * @param networkFee       nanoERG the cancel paid, from the order box when it held enough
 */
case class LDOrderCancelResult(outcome: String,
                               txId: String,
                               returnedNanoErgs: String,
                               returnedTokens: Seq[LDTokenAmount],
                               networkFee: String)

object LDOrderCancelResult {
  implicit lazy val ldOrderCancelResultJsonFormat: Format[LDOrderCancelResult] = Json.format[LDOrderCancelResult]
}
