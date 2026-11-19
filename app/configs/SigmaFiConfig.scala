package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.sigmafi` block: the settings every batcher shares, plus when broadcasts begin and the least fee
 * a bond must pay to be liquidated.
 *
 * @param broadcastAfterBlocks  blocks after a bond matures that only this miner's own blocks carry its
 *                              liquidation, fee-less, its fee going to the pool. From then on, with `broadcast`,
 *                              it is broadcast, paying the miner fee out of the batching fee
 * @param minBatchingFeeNanoErg bonds paying less are left to their lender
 */
case class SigmaFiBatchingConfig(batching: BatchingConfig, broadcastAfterBlocks: Int, minBatchingFeeNanoErg: Long)

object SigmaFiBatchingConfig {

  /** The batching block and candidate source key the SigmaFi batcher reads. */
  final val Name: String = CandidateSourceConfig.SigmaFi

  /**
   * The shipped fee floor. Anyone can build a bond naming an asset they hold, and its liquidation pays
   * everything back to them, so a bond paying nothing would be free filler in every Lithos block.
   */
  final val ShippedMinBatchingFee: Long = 50000000L

  /** Mirrors `batching.sigmafi` in application.conf; keep the two in step. */
  val Default: SigmaFiBatchingConfig = SigmaFiBatchingConfig(
    BatchingConfig.Default.copy(scanIntervalMs = 30000L, maxOrdersPerBlock = 20),
    broadcastAfterBlocks = 1,
    minBatchingFeeNanoErg = ShippedMinBatchingFee)

  def apply(config: Configuration): SigmaFiBatchingConfig =
    SigmaFiBatchingConfig(
      BatchingConfig(config, Name, Default.batching),
      config.getOptional(s"batching.$Name.broadcastAfterBlocks")(ConfigLoader.intLoader)
        .getOrElse(Default.broadcastAfterBlocks),
      config.getOptional(s"batching.$Name.minBatchingFeeNanoErg")(ConfigLoader.longLoader)
        .getOrElse(Default.minBatchingFeeNanoErg))
}

/**
 * The `sigmafi.zc` block: live tracking of the SigmaFi ZC Standard's boxes.
 *
 * @param enabled  whether this client follows ZC boxes at all; off leaves every ZC read unavailable
 * @param maxBoxes most ZC boxes held in memory, at roughly 2 KB each. Past it a box joins only in place of one
 *                 holding less ERG, so crowding real boxes out costs a spammer ERG locked in every slot
 * @param fillsFromHeight the height the fill history replays from: the first block holding a ZC box. 0 keeps the
 *                        fill history off, since replaying from genesis would read millions of blocks
 */
case class ZcConfig(enabled: Boolean, maxBoxes: Int, fillsFromHeight: Int = 0)

object ZcConfig {

  final val MinBoxes = 100
  final val MaxBoxes = 200000

  /** Mirrors `sigmafi.zc` in application.conf; keep the two in step. */
  val Default: ZcConfig = ZcConfig(enabled = true, maxBoxes = 10000, fillsFromHeight = 0)

  def apply(config: Configuration): ZcConfig = {
    val c = ZcConfig(
      config.getOptional("sigmafi.zc.enabled")(ConfigLoader.booleanLoader).getOrElse(Default.enabled),
      config.getOptional("sigmafi.zc.maxBoxes")(ConfigLoader.intLoader).getOrElse(Default.maxBoxes),
      config.getOptional("sigmafi.zc.fillsFromHeight")(ConfigLoader.intLoader).getOrElse(Default.fillsFromHeight))
    if (c.maxBoxes < MinBoxes || c.maxBoxes > MaxBoxes)
      Configs.fail("sigmafi.zc.maxBoxes", s"must be in $MinBoxes..$MaxBoxes")
    if (c.fillsFromHeight < 0) Configs.fail("sigmafi.zc.fillsFromHeight", "must be 0 (off) or a block height")
    c
  }
}

/**
 * The `sigmafi.bonds` block: lender reserve selected on fill when the request leaves it out.
 *
 * @param batchingFeeNanoErg lender-funded reserve added above the order's collateral, refunded to the
 *                           lender on repayment or available to a liquidator. Fill requests may override it.
 */
case class SigmaFiBondsConfig(batchingFeeNanoErg: Long)

object SigmaFiBondsConfig {

  /** Mirrors `sigmafi.bonds` in application.conf; keep the two in step. */
  val Default: SigmaFiBondsConfig = SigmaFiBondsConfig(SigmaFiBatchingConfig.ShippedMinBatchingFee)

  def apply(config: Configuration): SigmaFiBondsConfig =
    SigmaFiBondsConfig(config.getOptional("sigmafi.bonds.batchingFeeNanoErg")(ConfigLoader.longLoader)
      .getOrElse(Default.batchingFeeNanoErg))
}
