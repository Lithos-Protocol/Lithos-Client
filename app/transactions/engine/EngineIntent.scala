package transactions.engine

import transactions.rollups.TransactionMessages.RollupTxStub

/** Rebuildable work only; future API requests are deliberately not persisted. */
sealed trait EngineIntent { def key: String; def lane: String = "optional" }
object EngineIntent {
  final case class Holding(value: TransactionEngine.HoldingTransform) extends EngineIntent { def key: String = value.key }
  final case class Dex(value: DexIntent, requestId: String = java.util.UUID.randomUUID().toString) extends EngineIntent {
    def key: String = "dex:" + requestId
  }
  final case class Rollups(stubs: Vector[RollupTxStub]) extends EngineIntent {
    override val lane = "critical"
    def key: String = "rollups:" + org.bouncycastle.util.encoders.Hex.toHexString(
      scorex.crypto.hash.Blake2b256(stubs.map(s => s"${s.rollupBlockId}:${s.txType}:${s.currentPeriod}:${s.fpInfo.map(_._2)}")
        .sorted.mkString("|").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  }
  final case class Join(request: api.models.CollateralJoinExecuteRequest,
                        requestId: String = java.util.UUID.randomUUID().toString) extends EngineIntent {
    def key: String = "join:" + requestId
  }
  case object Queue extends EngineIntent { val key = "emission-queue" }
  case object Collateralize extends EngineIntent { val key = "self-collateralize" }
  case object Register extends EngineIntent { val key = "register-miner" }
  case object Consolidate extends EngineIntent { val key = "consolidation" }
}
