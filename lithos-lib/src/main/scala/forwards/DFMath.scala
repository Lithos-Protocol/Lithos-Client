package forwards

import forwards.DFHelpers._

/**
 * The difficulty forward contracts' arithmetic, mirrored exactly, truncation included.
 *
 * Off-chain code builds recordings and settlements from these, and the contract specs check them against
 * the scripts, so a drift on either side fails a spec rather than a transaction.
 */
object DFMath {

  /** Difficulty epoch of the block at `height`. */
  def epochOf(height: Int): Int = (height - 1) / EPOCH_LENGTH

  /** Each side's margin. */
  def margin(notional: Long): Long = notional / MARGIN_DIVISOR

  /** LIT each side puts in: the maker into the offer, the taker at the fill. */
  def sideLit(notional: Long): Long = margin(notional) + SIDE_REWARD

  /** LIT a position holds: both margins and both sides' rewards. */
  def positionLit(notional: Long): Long = 2L * sideLit(notional)

  /**
   * The ring after recording `difficulty` at `currentEpoch`. Each slot holds the newest epoch up to the
   * current one that maps to it; slots newer than `lastEpoch` are rewritten, the current epoch with its
   * difficulty and skipped ones with the last recorded value.
   */
  def recordedRing(ring: IndexedSeq[Long], lastEpoch: Int, currentEpoch: Int, difficulty: Long): IndexedSeq[Long] = {
    require(currentEpoch > lastEpoch, s"epoch $currentEpoch is not after the last recorded, $lastEpoch")
    require(ring.size == RING_SIZE, s"a ring holds $RING_SIZE epochs, not ${ring.size}")
    val carried = ring(lastEpoch % RING_SIZE)
    ring.indices.map { i =>
      val slotEpoch = currentEpoch - (currentEpoch - i) % RING_SIZE
      if (slotEpoch == currentEpoch) difficulty else if (slotEpoch > lastEpoch) carried else ring(i)
    }
  }

  /** Whether every epoch of the window has been recorded. */
  def windowComplete(lastEpoch: Int, windowStart: Int, windowLength: Int): Boolean =
    lastEpoch >= windowStart + windowLength - 1

  /** Whether the window's first epoch has left the ring, which turns a settlement into a refund. */
  def overwritten(lastEpoch: Int, windowStart: Int): Boolean = lastEpoch - windowStart >= RING_SIZE

  /** Sum of the window's difficulties as the ring holds them. */
  def windowTotal(ring: IndexedSeq[Long], windowStart: Int, windowLength: Int): BigInt =
    (0 until windowLength).map(j => BigInt(ring((windowStart + j) % RING_SIZE))).sum

  /**
   * LIT the long side receives above its margin, negative when the short side is paid:
   * `notional * (total / (length * strike) - 1)`, one truncating division, clamped to the margin.
   */
  def payout(ring: IndexedSeq[Long], windowStart: Int, windowLength: Int, strike: Long, notional: Long): Long = {
    val base = BigInt(windowLength) * BigInt(strike)
    val raw = BigInt(notional) * (windowTotal(ring, windowStart, windowLength) - base) / base
    val cap = BigInt(margin(notional))
    raw.max(-cap).min(cap).toLong
  }

  /**
   * LIT owed to (long, short) by a position settled against an accumulator whose newest epoch is
   * `lastEpoch`: margins back if the window has been overwritten, otherwise margins plus or minus the payout.
   */
  def owed(ring: IndexedSeq[Long], lastEpoch: Int, windowStart: Int, windowLength: Int,
           strike: Long, notional: Long): (Long, Long) = {
    val m = margin(notional)
    if (overwritten(lastEpoch, windowStart)) (m, m)
    else {
      val p = payout(ring, windowStart, windowLength, strike, notional)
      (m + p, m - p)
    }
  }
}
