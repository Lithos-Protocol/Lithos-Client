package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.sigmafi` block: ZC Standard batching settings, broadcast wait and fee floors.
 *
 * @param broadcastAfterBlocks  blocks after a box becomes eligible that only this miner's own blocks carry its
 *                              execution, fee-less, its fee going to the pool. From then on, with `broadcast`,
 *                              it is broadcast, paying the miner fee out of the batching fee
 * @param minBatchingFeeNanoErg least fee reserve a ZC bond liquidation must pay
 * @param minActionFeeNanoErg   least reward a ZC match, share/wrap mint, pull, refund or refresh must pay
 * @param collateralArb         the `collateralArb` block: ZC collateral arb broadcast in one transaction each
 */
case class SigmaFiBatchingConfig(batching: BatchingConfig, broadcastAfterBlocks: Int, minBatchingFeeNanoErg: Long,
                                 minActionFeeNanoErg: Long = SigmaFiBatchingConfig.ShippedMinActionFee,
                                 collateralArb: ZcCollateralArbConfig = ZcCollateralArbConfig.Default)

/**
 * The `batching.sigmafi.collateralArb` block: buying a ZC lot, Dutch vault auction or sale order and selling its
 * collateral through one ERG pool, in one broadcast transaction funded from the node wallet.
 *
 * @param broadcast        whether the background tick sends these at all
 * @param minReturnPercent least net ERG profit, as a percentage of the ERG one transaction pays out
 * @param minProfitNanoErg least net ERG profit one transaction must leave, after its miner fee
 * @param maxErgIn         most nanoERG one transaction may take from the wallet
 */
case class ZcCollateralArbConfig(broadcast: Boolean, minReturnPercent: Int, minProfitNanoErg: Long, maxErgIn: Long)

object ZcCollateralArbConfig {

  /** Mirrors `batching.sigmafi.collateralArb` in application.conf; keep the two in step. */
  val Default: ZcCollateralArbConfig = ZcCollateralArbConfig(broadcast = true, minReturnPercent = 10,
    minProfitNanoErg = 10000000L, maxErgIn = 10000000000L)

  def apply(config: Configuration, path: String): ZcCollateralArbConfig = {
    def key(name: String) = s"$path.collateralArb.$name"
    ZcCollateralArbConfig(
      config.getOptional(key("broadcast"))(ConfigLoader.booleanLoader).getOrElse(Default.broadcast),
      config.getOptional(key("minReturnPercent"))(ConfigLoader.intLoader).getOrElse(Default.minReturnPercent),
      config.getOptional(key("minProfitNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.minProfitNanoErg),
      config.getOptional(key("maxErgIn"))(ConfigLoader.longLoader).getOrElse(Default.maxErgIn))
  }
}

object SigmaFiBatchingConfig {

  /** The batching block and candidate source key the SigmaFi batcher reads. */
  final val Name: String = CandidateSourceConfig.SigmaFi

  /**
   * The shipped liquidation fee floor (0.05 ERG). Anyone can build a bond naming an asset they hold, and its
   * liquidation pays everything back to them, so a bond paying nothing would be free filler in every Lithos block.
   */
  final val ShippedMinBatchingFee: Long = 50000000L

  /** The shipped floor for non-liquidation ZC actions (0.002 ERG), matching the zcs1 contract floor. */
  final val ShippedMinActionFee: Long = 2000000L

  /** Mirrors `batching.sigmafi` in application.conf; keep the two in step. */
  val Default: SigmaFiBatchingConfig = SigmaFiBatchingConfig(
    BatchingConfig.Default.copy(scanIntervalMs = 30000L, maxOrdersPerBlock = 20),
    broadcastAfterBlocks = 1,
    minBatchingFeeNanoErg = ShippedMinBatchingFee,
    minActionFeeNanoErg = ShippedMinActionFee)

  def apply(config: Configuration): SigmaFiBatchingConfig =
    SigmaFiBatchingConfig(
      BatchingConfig(config, Name, Default.batching),
      config.getOptional(s"batching.$Name.broadcastAfterBlocks")(ConfigLoader.intLoader)
        .getOrElse(Default.broadcastAfterBlocks),
      config.getOptional(s"batching.$Name.minBatchingFeeNanoErg")(ConfigLoader.longLoader)
        .getOrElse(Default.minBatchingFeeNanoErg),
      config.getOptional(s"batching.$Name.minActionFeeNanoErg")(ConfigLoader.longLoader)
        .getOrElse(Default.minActionFeeNanoErg),
      ZcCollateralArbConfig(config, s"batching.$Name"))
}

/**
 * The `sigmafi.zc` block: live tracking of the SigmaFi ZC Standard's boxes.
 *
 * @param enabled            whether this client follows ZC boxes at all; off leaves every ZC read unavailable
 * @param maxBoxes           most ZC boxes held in memory, at roughly 2 KB each. Past it a box joins only in place of one
 *                           holding less ERG, so crowding real boxes out costs a spammer ERG locked in every slot
 * @param fillsFromHeight    the height the fill history replays from: the first block holding a ZC box. 0 keeps the
 *                           fill history off, since replaying from genesis would read millions of blocks
 * @param fillsRetentionDays days of fill history retained (at 720 blocks per day; default 1460, ~4 years). 0 keeps
 *                           fills forever
 */
case class ZcConfig(enabled: Boolean, maxBoxes: Int, fillsFromHeight: Int = 0,
                    fillsRetentionDays: Int = ZcConfig.DefaultFillsRetentionDays)

object ZcConfig {

  final val MinBoxes = 100
  final val MaxBoxes = 200000
  final val DefaultFillsRetentionDays = 1460
  final val MaxFillsRetentionDays = 36500

  /** The first mainnet block holding a box under the pinned ZC contracts. */
  final val MainnetFirstBlock = 1918857

  /** Mirrors `sigmafi.zc` in application.conf; keep the two in step. */
  val Default: ZcConfig = ZcConfig(enabled = true, maxBoxes = 10000, fillsFromHeight = MainnetFirstBlock,
    fillsRetentionDays = DefaultFillsRetentionDays)

  def apply(config: Configuration): ZcConfig = {
    val c = ZcConfig(
      config.getOptional("sigmafi.zc.enabled")(ConfigLoader.booleanLoader).getOrElse(Default.enabled),
      config.getOptional("sigmafi.zc.maxBoxes")(ConfigLoader.intLoader).getOrElse(Default.maxBoxes),
      config.getOptional("sigmafi.zc.fillsFromHeight")(ConfigLoader.intLoader).getOrElse(Default.fillsFromHeight),
      config.getOptional("sigmafi.zc.fillsRetentionDays")(ConfigLoader.intLoader).getOrElse(Default.fillsRetentionDays))
    if (c.maxBoxes < MinBoxes || c.maxBoxes > MaxBoxes)
      Configs.fail("sigmafi.zc.maxBoxes", s"must be in $MinBoxes..$MaxBoxes")
    if (c.fillsFromHeight < 0) Configs.fail("sigmafi.zc.fillsFromHeight", "must be 0 (off) or a block height")
    if (c.fillsRetentionDays < 0 || c.fillsRetentionDays > MaxFillsRetentionDays)
      Configs.fail("sigmafi.zc.fillsRetentionDays", s"must be 0 (keep forever) or in 1..$MaxFillsRetentionDays")
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
