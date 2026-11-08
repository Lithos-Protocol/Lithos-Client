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
    broadcastAfterBlocks = 2,
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
