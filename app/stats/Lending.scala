package stats

/**
 * What this wallet's lending has earned in the retained window, over every position a Lithos block spent.
 *
 * @param netNanoErg what came back less what was posted, before the network fee each join paid
 */
final case class LendingTotals(positions: Int, postedNanoErg: String, priorityFeesNanoErg: String,
                               coinbaseNanoErg: String, feesNanoErg: String, netNanoErg: String) {

  /** These totals with `record` added, or taken away when `sign` is -1. */
  def add(record: LendingRecord, sign: Int): LendingTotals = {
    def plus(total: String, amount: BigInt): String = (BigInt(total) + sign * amount).toString
    val next = LendingTotals(positions + sign, plus(postedNanoErg, BigInt(record.postedNanoErg)),
      plus(priorityFeesNanoErg, BigInt(record.priorityFeeNanoErg)), plus(coinbaseNanoErg, BigInt(record.coinbaseNanoErg)),
      plus(feesNanoErg, BigInt(record.feesNanoErg)), plus(netNanoErg, record.netNanoErg))
    require(next.positions >= 0 && BigInt(next.postedNanoErg) >= 0, "lending totals became negative")
    next
  }
}

object LendingTotals {
  val Zero: LendingTotals = LendingTotals(0, "0", "0", "0", "0", "0")

  def of(records: Seq[LendingRecord]): LendingTotals = records.foldLeft(Zero)(_.add(_, 1))
}

/**
 * A page of this wallet's spent positions, newest first. The statistics keep `stats.mining.historyDays` of
 * blocks, so positions spent before `retainedFromHeight` have been pruned along with them.
 *
 * @param rewardLockBlocks how long a block reward stays locked to the lender's key after the block
 */
final case class LendingPage(status: String, source: MiningCursor, retainedFromHeight: Int,
                             retainedFromTimestamp: Option[Long], positions: Vector[LendingRecord], offset: Int,
                             total: Int, totals: LendingTotals,
                             rewardLockBlocks: Int = mutations.NodeWallet.MINER_REWARD_DELAY)

object Lending {
  val DefaultLimit: Int = 50
  val MaxLimit: Int = 500

  /** One per block, since a block spends at most one collateral box. */
  def key(height: Int): String = f"ledger/lending/$height%010d"
}
