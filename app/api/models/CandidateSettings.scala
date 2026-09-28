package api.models

import configs.{BatchingConfig, CandidateConfig, CandidateSourceConfig}
import play.api.Configuration
import play.api.libs.json._

/**
 * One `stratum.candidate.sources` entry. `active` is whether candidates actually ask this source:
 * block transactions are on, the source is enabled with at least one slot and, for a batcher, its
 * `batching` block is enabled too.
 */
case class CandidateSourceSettings(name: String, enabled: Boolean, active: Boolean, maxTxs: Int,
                                   maxBytes: Long, maxCost: Long)

/**
 * The `stratum.candidate` settings that decide what this client's blocks carry besides the genesis
 * transaction, which collateral box funds them, and when a served job is replaced mid-block.
 */
case class CandidateSettings(blockTransactions: Boolean,
                             sources: Seq[CandidateSourceSettings],
                             blockShare: Double,
                             waitForBlockPackage: Boolean,
                             genesisWaitMs: Int,
                             blockTxTimeout: Int,
                             mempoolRefreshMs: Int,
                             minCandidateChangeRevenue: Long,
                             refreshForProtocolTxs: Boolean,
                             minNewProtocolTxs: Int,
                             collateralStrategy: String,
                             clearanceAge: Int,
                             pinnedInputs: Int)

object CandidateSettings {
  implicit lazy val sourceJsonFormat: Format[CandidateSourceSettings] = Json.format[CandidateSourceSettings]
  implicit lazy val candidateSettingsJsonFormat: Format[CandidateSettings] = Json.format[CandidateSettings]

  /** In the order the stratum asks them, which is the order their work takes block slots. */
  private val SourceOrder: Seq[String] = Seq(CandidateSourceConfig.Rollups, CandidateSourceConfig.Emissions,
    CandidateSourceConfig.Rent, CandidateSourceConfig.LithosDex, CandidateSourceConfig.ErgoDex)

  /** Sources served by a batcher, which also needs its own `batching` block enabled. */
  private val Batchers: Set[String] = Set(CandidateSourceConfig.LithosDex, CandidateSourceConfig.ErgoDex)

  def from(config: Configuration): CandidateSettings = {
    val candidate = CandidateConfig(config)
    val sources = SourceOrder.flatMap(name => candidate.sources.get(name).map { source =>
      val served = !Batchers.contains(name) || BatchingConfig(config, name).enabled
      CandidateSourceSettings(name, source.enabled,
        candidate.blockTransactions && source.enabled && source.maxTxs > 0 && served,
        source.maxTxs, source.maxBytes, source.maxCost)
    })
    CandidateSettings(candidate.blockTransactions, sources, candidate.blockShare, candidate.waitForBlockPackage,
      candidate.genesisWaitMs, candidate.blockTxTimeout, candidate.mempoolRefreshMs,
      candidate.minCandidateChangeRevenue, candidate.refreshForProtocolTxs, candidate.minNewProtocolTxs,
      candidate.collateralStrategy, candidate.clearanceAge, candidate.pinnedInputs)
  }
}
