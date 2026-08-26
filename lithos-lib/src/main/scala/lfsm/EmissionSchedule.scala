package lfsm

/**
 * The LIT emission schedule, mirrored from `LIT_Emissions.ergo`. Anything building an Activate must
 * work out `emittedTotal`, `emittedPub` and the founders' map exactly as the contract does or the
 * transaction is rejected, so this is kept as one block that diffs cleanly against that file.
 *
 * Two behaviours are reproduced deliberately: the total is capped by the LIT the emission box still
 * holds rather than the nominal rate, and the founders are paid in full or not at all.
 */
object EmissionSchedule {

  final val LIT_DECIMALS: Long = 1000000000L // 1e9

  final val EPOCH_LENGTH: Int = 125000
  final val INIT_PUB_RATE: Long = 500L * LIT_DECIMALS
  final val INIT_PRIV_RATE: Long = 140L * LIT_DECIMALS
  final val PRIV_LENGTH: Int = 8
  final val PUB_DECAY: Long = 50L * LIT_DECIMALS
  final val P1_DECAY_LENGTH: Int = 2
  final val P2_RATE_1: Long = 150L * LIT_DECIMALS
  final val P2_RATE_2: Long = 100L * LIT_DECIMALS
  final val P3_RATE: Long = 50L * LIT_DECIMALS
  final val P2_START: Int = 14
  final val P3_START: Int = 16
  final val MAX_EPOCHS: Int = 22
  final val FINAL_EPOCH_LEN: Int = 75000

  /** Integer division over epochs cannot express the truncated final epoch, so the end is a block. */
  final val FINAL_BLOCK: Int = MAX_EPOCHS * EPOCH_LENGTH + FINAL_EPOCH_LEN // 2825000

  final val F1_RATE: Long = 100L * LIT_DECIMALS
  final val F2_RATE: Long = 25L * LIT_DECIMALS
  final val F3_RATE: Long = 15L * LIT_DECIMALS

  /** Live collateral boxes, and so distinct lender keys, allowed at once (`CONST_MAX_ACTIVE`). */
  final val MAX_ACTIVE: Int = 100

  /** `CONST_FINDER_DIVISOR` in `Collateral_Mainnet.ergo` — 25 gives the finder a flat 4%. */
  final val FINDER_DIVISOR: Long = 25L

  /** The founders' fixed split, keyed by hashed prop bytes exactly as the contract writes it. */
  def founders: Seq[(Array[Byte], Long)] = Seq(
    LFSMHelpers.FOUNDER_1.hashedPropBytes -> F1_RATE,
    LFSMHelpers.FOUNDER_2.hashedPropBytes -> F2_RATE,
    LFSMHelpers.FOUNDER_3.hashedPropBytes -> F3_RATE
  )

  def privRateAt(currentBlock: Int): Long =
    if (currentBlock / EPOCH_LENGTH < PRIV_LENGTH) INIT_PRIV_RATE else 0L

  def pubRateAt(currentBlock: Int): Long = {
    val epoch = currentBlock / EPOCH_LENGTH
    if (currentBlock >= FINAL_BLOCK) 0L
    else if (epoch < P2_START) INIT_PUB_RATE - ((epoch / P1_DECAY_LENGTH) * PUB_DECAY)
    else if (epoch < P3_START) { if (epoch == P2_START) P2_RATE_1 else P2_RATE_2 }
    else P3_RATE
  }

  /**
   * What an Activate at `currentBlock` must place on the collateral box, given the LIT the emission
   * box still holds: `(emittedTotal, emittedPub, foundersMap)`.
   */
  def emissionAt(currentBlock: Int, litHeld: Long): (Long, Long, Seq[(Array[Byte], Long)]) = {
    val priv = privRateAt(currentBlock)
    val total = math.min(litHeld, pubRateAt(currentBlock) + priv)
    val emittedPriv = if (total >= priv) priv else 0L
    (total, total - emittedPriv, if (emittedPriv > 0) founders else Seq.empty[(Array[Byte], Long)])
  }

  /** Public-rate phase label for display: DECAYING, STEPPED, FLAT_50 or ENDED. */
  def phaseAt(currentBlock: Int): String =
    if (currentBlock >= FINAL_BLOCK) "ENDED"
    else if (currentBlock / EPOCH_LENGTH >= P3_START) "FLAT_50"
    else if (currentBlock / EPOCH_LENGTH >= P2_START) "STEPPED"
    else "DECAYING"

  /**
   * The next height at which any rate component changes - a public decay step, the founder period
   * ending, or one of the fixed phase starts. `None` once the schedule has ended.
   */
  def nextChangeAt(currentBlock: Int): Option[Int] = {
    if (currentBlock >= FINAL_BLOCK) return None
    val epoch = currentBlock / EPOCH_LENGTH
    val boundary = (epoch + 1 to P3_START).find { e =>
      e == PRIV_LENGTH || e == P2_START || e == P3_START ||
        (e < P2_START && e % P1_DECAY_LENGTH == 0)
    }
    Some(boundary.map(_ * EPOCH_LENGTH).getOrElse(FINAL_BLOCK))
  }
}

/**
 * What the collateral enforcer demands of a queue box, mirrored from `Collateral_Enforcer.ergo`.
 * They are inline constants there rather than injected ones, so changing any of them changes the
 * enforcer hash in the emission config's R5. A client building a Join must agree with whichever
 * enforcer the config currently names.
 */
object CollateralParams {

  /** EIP-27 flat block reward. */
  final val BLOCK_REWARD: Long = 3000000000L

  /** The most a lender may keep back out of the block reward — exactly 3%. */
  final val FEE_CAP: Long = 90000000L

  /** Funds the genesis transaction's founder, permit, finder and proof-of-spend boxes. */
  final val DUST_BUDGET: Long = 5000000L

  /** The principal a queue box must post: 2.915 ERG. */
  final val PRINCIPAL_FLOOR: Long = BLOCK_REWARD - FEE_CAP + DUST_BUDGET

  /** Pinned at Join. Widens to the live product-flag set as collateral extensions ship (§26.3.1). */
  final val EXTENSION_FLAG: Byte = 0x00

  /**
   * The permit demanded at a given backlog, evaluated as the enforcer does it: wide, then clamped,
   * so no parameter choice can overflow and make every Join unsatisfiable. `params` is the emission
   * config's R4 — floor, ceiling, slope.
   */
  def permitAt(backlog: Long, params: Seq[Long]): Long = {
    require(params.size >= 3, "emission config R4 must hold at least floor, ceiling and slope")
    val scaled = BigInt(params.head) + (BigInt(params(2)) * BigInt(backlog))
    if (scaled >= BigInt(params(1))) params(1) else scaled.toLong
  }
}
