package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.forwards` block: the settings every batcher shares, plus when broadcasts begin.
 *
 * @param broadcastAfterBlocks blocks into each difficulty epoch before this client broadcasts accumulator work.
 *                             Until then the epoch is left to Lithos blocks, which record it and settle positions
 *                             fee-less; from then on, with `broadcast`, this miner's own block offers none of it,
 *                             so it never displaces the broadcast it sent
 */
case class ForwardsBatchingConfig(batching: BatchingConfig, broadcastAfterBlocks: Int)

object ForwardsBatchingConfig {

  /** The batching block and candidate source key the forwards batcher reads. */
  final val Name: String = CandidateSourceConfig.Forwards

  /** Mirrors `batching.forwards` in application.conf; keep the two in step. */
  val Default: ForwardsBatchingConfig = ForwardsBatchingConfig(
    BatchingConfig.Default.copy(scanIntervalMs = 30000L, maxOrdersPerBlock = 10),
    broadcastAfterBlocks = 96)

  def apply(config: Configuration): ForwardsBatchingConfig =
    ForwardsBatchingConfig(
      BatchingConfig(config, Name, Default.batching),
      config.getOptional(s"batching.$Name.broadcastAfterBlocks")(ConfigLoader.intLoader)
        .getOrElse(Default.broadcastAfterBlocks))
}
