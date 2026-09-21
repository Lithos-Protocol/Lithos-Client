package api.models

import play.api.libs.json._

/**
 * How many positions to price, and what each one bids for priority.
 *
 * `finderFeeEachNanoErgs` is paid to whichever miner spends the resulting collateral box, so a
 * higher bid is chosen sooner. The contract charges four times it into the pool as well, which is
 * why raising it moves `principalEachNanoErgs` by five times as much. Omitted or "0" posts at the
 * floor and bids nothing.
 */
case class CollateralJoinCheckRequest(count: Option[Int],
                                      finderFeeEachNanoErgs: Option[String] = None)

object CollateralJoinCheckRequest {
  implicit lazy val jsonFormat: Format[CollateralJoinCheckRequest] =
    Json.format[CollateralJoinCheckRequest]
  val Default: CollateralJoinCheckRequest = CollateralJoinCheckRequest(None)
}

/** Why a manual join cannot go ahead. Absent from the quote when it can. */
object CollateralJoinBlock {
  /** The automated pass owns key selection; a hand-made join can race it onto the same key. */
  final val AutoCollateralize = "AUTO_COLLATERALIZE"
  /** Fewer free keys than positions, and no room left under `maxLenderKeys` to derive more. */
  final val NoLenderKeys = "NO_LENDER_KEYS"
  /** `maxOwnCollateral` already reached, or reached by this many more positions. */
  final val BudgetExhausted = "BUDGET_EXHAUSTED"
  /** The wallet cannot cover principal plus fees for the requested count. */
  final val InsufficientFunds = "INSUFFICIENT_FUNDS"
}

/**
 * What creating `count` positions costs right now, and whether this client will do it.
 *
 * `manualJoinAllowed` is the answer the send control keys off; `blockedReason` says which condition
 * to show. The quote is a snapshot and the config can change under it, so `/collateral/join`
 * re-checks all of this — the quote informs the UI, it does not license the send.
 *
 * `lenderAddresses` lists the keys already free, in the order this join would use them, index for
 * index with `permitsLit`. It is SHORTER than `count` when some keys would be derived at send time,
 * because a derivation is a node-wallet mutation and a quote must not make one.
 *
 * The four fee fields price the priority bid. `principalEachNanoErgs` already contains the bid and
 * its pool premium, so it is the whole per-position cost; the other three break it down and say
 * what the coinbase gives back. `netAtCoinbaseEachNanoErgs` is SIGNED and goes negative above
 * `breakEvenFinderFeeNanoErgs`, where the bid is only recovered from the mined block's own fees.
 */
case class CollateralJoinQuote(count: Int,
                               principalEachNanoErgs: String,
                               finderFeeEachNanoErgs: String,
                               poolPremiumEachNanoErgs: String,
                               breakEvenFinderFeeNanoErgs: String,
                               netAtCoinbaseEachNanoErgs: String,
                               txFeeEachNanoErgs: String,
                               permitsLit: Seq[String],
                               permitTotalLit: String,
                               totalNanoErgs: String,
                               firstPosition: Long,
                               emissionTipBoxId: String,
                               affordNow: Boolean,
                               shortfallNanoErgs: String,
                               ownRoomLeft: Int,
                               manualJoinAllowed: Boolean,
                               blockedReason: Option[String],
                               lenderAddresses: Seq[String],
                               freeLenderKeys: Int,
                               keysRequired: Int)

object CollateralJoinQuote {
  implicit lazy val jsonFormat: Format[CollateralJoinQuote] = Json.format[CollateralJoinQuote]
}

/**
 * Join the queue. Send the quote's `emissionTipBoxId` back so stale state becomes a 409.
 *
 * `acknowledgeNoWithdrawal` must be true. Queued principal and permit come back only when a block
 * spends the box; there is no early exit and no cancel. The acknowledgement is carried in the
 * request rather than inferred from one arriving, which is the same guard `/dex/redeem` puts on its
 * own irreversible spend.
 *
 * `finderFeeEachNanoErgs` must match what the quote was taken at. It is not capped: a bid above the
 * quote's `breakEvenFinderFeeNanoErgs` is accepted, because the transaction fees of the block the
 * box is mined against can still cover it.
 */
case class CollateralJoinExecuteRequest(count: Int,
                                        acknowledgeNoWithdrawal: Option[Boolean],
                                        maxPermitEachLit: Option[String],
                                        expectedEmissionBoxId: Option[String],
                                        finderFeeEachNanoErgs: Option[String] = None)

object CollateralJoinExecuteRequest {
  implicit lazy val jsonFormat: Format[CollateralJoinExecuteRequest] =
    Json.format[CollateralJoinExecuteRequest]
}

/** One join transaction that was sent. `principalNanoErgs` includes the bid and its pool premium. */
case class CollateralJoinEntry(txId: String,
                               position: Long,
                               lenderAddress: String,
                               principalNanoErgs: String,
                               permitLit: String,
                               outcome: String = "accepted",
                               finderFeeNanoErgs: String = "0")

object CollateralJoinEntry {
  implicit lazy val jsonFormat: Format[CollateralJoinEntry] = Json.format[CollateralJoinEntry]
}

/**
 * Result of joining the collateral queue.
 *
 * `stoppedReason` is present when the run ended before every requested position was created. Each
 * join chains off the previous one's emission successor, so one failure invalidates everything
 * after it and the run stops there — and the positions already sent are real, spent principal that
 * has to be reported rather than lost with the exception. A run that sends NOTHING fails with a
 * status instead, since a 200 carrying an empty list cannot be told from doing nothing on purpose.
 */
case class CollateralJoinResult(joins: Seq[CollateralJoinEntry],
                                totalNanoErgs: String,
                                stoppedReason: Option[String])

object CollateralJoinResult {
  implicit lazy val jsonFormat: Format[CollateralJoinResult] = Json.format[CollateralJoinResult]
}
