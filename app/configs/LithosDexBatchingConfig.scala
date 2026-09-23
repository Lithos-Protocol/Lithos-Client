package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.lithosdex` block: the settings every batcher shares, plus what only LithosDex has.
 *
 * @param autoFlush close each candidate run with a flush moving the pool's pending fees into the vault,
 *                  so providers can claim them. Never broadcast, since a broadcast flush pays a fee.
 * @param discoverPools also broadcast orders for pools other than the canonical one, each served only when the
 *                  contracts built from its own ids reproduce its pool and vault exactly. Block candidates keep
 *                  executing the canonical pool alone.
 */
case class LithosDexBatchingConfig(batching: BatchingConfig, autoFlush: Boolean, discoverPools: Boolean = true)

object LithosDexBatchingConfig {

  /** The batching block and candidate source key the LithosDex batcher reads. */
  final val Name: String = CandidateSourceConfig.LithosDex

  /** Mirrors `batching.lithosdex` in application.conf; keep the two in step. */
  val Default: LithosDexBatchingConfig = LithosDexBatchingConfig(BatchingConfig.Default, autoFlush = true)

  def apply(config: Configuration): LithosDexBatchingConfig =
    LithosDexBatchingConfig(
      BatchingConfig(config, Name),
      config.getOptional(s"batching.$Name.autoFlush")(ConfigLoader.booleanLoader).getOrElse(Default.autoFlush),
      config.getOptional(s"batching.$Name.discoverPools")(ConfigLoader.booleanLoader)
        .getOrElse(Default.discoverPools))
}
