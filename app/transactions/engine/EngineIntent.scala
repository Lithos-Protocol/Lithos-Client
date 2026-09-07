package transactions.engine

import transactions.engine.ExecutionSchedule.Lane
import transactions.rollups.TransactionMessages.RollupTxStub

/**
 * Work the engine can rebuild against newer state. Nothing here is persisted: an intent's `key` only
 * coalesces concurrent requests, and whether the work is still needed after a restart is decided by
 * rereading contract state, not by a stored record.
 */
sealed trait EngineIntent {
  /** Stable identity for this unit of work. Two intents sharing a key are the same request. */
  def key: String
  def lane: String = Lane.Optional
}

object EngineIntent {
  final case class Holding(transform: TransactionEngine.HoldingTransform) extends EngineIntent {
    def key: String = transform.key
  }

  /** A one-off API request, so its key is per-request and never rediscovered after a restart. */
  final case class Dex(request: DexIntent,
                       requestId: String = java.util.UUID.randomUUID().toString) extends EngineIntent {
    def key: String = "dex:" + requestId
  }

  final case class Rollups(stubs: Vector[RollupTxStub]) extends EngineIntent {
    override val lane = Lane.Critical

    /**
     * Hashed over the sorted stub identities so a batch requested twice coalesces regardless of the
     * order the stubs arrived in.
     */
    def key: String = "rollups:" + org.bouncycastle.util.encoders.Hex.toHexString(
      scorex.crypto.hash.Blake2b256(stubs
        .map(stub => s"${stub.rollupBlockId}:${stub.txType}:${stub.currentPeriod}:${stub.fpInfo.map(_._2)}")
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
