package state.synchronization

import lfsm.LFSMPhase
import lfsm.states.RollupMetadata
import nisp.{NispCommitment, ResolvedNisp, ResolvedNisps}
import node.NodeApi
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoValue
import sigma.Coll
import state.messages.{BlockInfo, NodeSync}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

final case class NispResolution(resolved: ResolvedNisps,
                                unavailable: Map[String, String],
                                historyError: Option[String],
                                transforms: Int,
                                indexRequests: Int,
                                elapsedMillis: Long) {
  def complete: Boolean = historyError.isEmpty && unavailable.isEmpty
}

/** One bounded indexed walk per evaluation; it owns no persistent or cross-rollup cache. */
trait NispResolver {
  def resolve(target: RollupMetadata, collateralBoxId: String, confirmedHeight: Int,
              requested: Map[String, NispCommitment]): NispResolution
}

final class RollupNispResolver(node: NodeApi, protocol: SyncProtocolContext, maxTransforms: Int)
  extends NispResolver {
  require(maxTransforms > 0, "NISP recovery transform limit must be positive")

  override def resolve(target: RollupMetadata, collateralBoxId: String, confirmedHeight: Int,
                        requested: Map[String, NispCommitment]): NispResolution = {
    val started = System.nanoTime()
    var applied = 0
    var reads = 0
    val recovered = mutable.Map.empty[String, ResolvedNisp]
    val result = Try {
      require(target.phase == LFSMPhase.EVAL, s"box ${target.utxoId} is not a confirmed Evaluation boundary")
      val replay = new BlockReducer.RollupReplay(protocol, retainUnownedRollups = true)
      val fault = QuarantineFault(target.blockId, target.startHeight, collateralBoxId,
        "evaluation payload recovery", retryable = true)
      val genesis = new RollupRepair(node, protocol, maxTransforms, () => reads += 1)
        .seed(replay, fault, confirmedHeight)
        .fold(f => throw new IllegalStateException(f.asInstanceOf[RollupRepairResult.Failed].reason), identity)
      require(replay.origin(target.blockId).contains(collateralBoxId),
        s"genesis box $genesis does not originate from retained collateral $collateralBoxId")
      var boxId = genesis
      var lastHeight = target.startHeight
      val visited = mutable.HashSet.empty[String]
      while (boxId != target.utxoId) {
        require(visited.add(boxId), s"cycle at rollup box $boxId")
        require(applied < maxTransforms,
          s"NISP history exceeds evaluation.nispRecovery.maxTransforms ($maxTransforms) at box $boxId")
        reads += 1
        val box = node.indexedBoxById(boxId).recover { case ex =>
          throw new IllegalStateException(s"cannot read rollup box $boxId", ex)
        }.get.getOrElse(
          throw new IllegalStateException(s"indexed node has no rollup box $boxId"))
        require(box.boxId == boxId, s"requested box $boxId, received ${box.boxId}")
        val txId = box.spentTransactionId.getOrElse(
          throw new IllegalStateException(s"history stops at box $boxId before target ${target.utxoId}"))
        reads += 1
        val indexed = node.indexedTransactionById(txId).recover { case ex =>
          throw new IllegalStateException(s"cannot read transaction $txId spending box $boxId", ex)
        }.get.getOrElse(
          throw new IllegalStateException(s"indexed node has no transaction $txId spending box $boxId"))
        require(indexed.id == txId, s"box $boxId links transaction $txId, received ${indexed.id}")
        require(indexed.inputs.headOption.exists(_.boxId == boxId),
          s"transaction $txId does not spend followed box $boxId at input 0")
        require(indexed.inclusionHeight >= lastHeight && indexed.inclusionHeight <= confirmedHeight,
          s"transaction $txId at height ${indexed.inclusionHeight} is outside confirmed history ending $confirmedHeight")
        val tx = NodeSync.blockTx(indexed)
        val before = replay.rollup(target.blockId).get
        val inputs = indexed.inputs.iterator.map(in => in.boxId -> NodeSync.txOutput(in)).toMap +
          (boxId -> NodeSync.txOutput(box))
        val block = BlockInfo(indexed.blockId, indexed.inclusionHeight, Seq(tx), "", inputs)
        val changed = replay.apply(block, tx).fold(e => throw new IllegalStateException(e.message), identity)
        require(changed, s"transaction $txId changes no followed rollup at box $boxId")
        val next = replay.rollup(target.blockId).getOrElse(
          throw new IllegalStateException(s"transaction $txId terminates rollup before target ${target.utxoId}"))
        require(next.utxoId != boxId, s"transaction $txId does not advance followed box $boxId")
        require(!visited(next.utxoId), s"cycle from transaction $txId to rollup box ${next.utxoId}")
        if (before.phase == LFSMPhase.HOLDING && next.numMiners == before.numMiners + 1) {
          val encoded = tx.inputs.head.spendingProof.get.ext("1")
          val pair = ErgoValue.fromHex(encoded).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
          val miner = Hex.toHexString(pair._1.toArray)
          requested.get(miner).foreach { commitment =>
            val raw = pair._2.toArray
            if (commitment.matchesPayload(raw)) recovered.update(miner, ResolvedNisp(raw))
          }
        }
        boxId = next.utxoId
        lastHeight = indexed.inclusionHeight
        applied += 1
      }
      val reached = replay.rollup(target.blockId).get
      require(reached.metadata.dictionaryDigest == target.dictionaryDigest && reached.state == target.state &&
        reached.totalScore == target.totalScore && reached.numMiners == target.numMiners && reached.value == target.value,
        s"recovered state at box ${target.utxoId} differs from the confirmed evaluation snapshot")
    }
    val failure = result match {
      case Failure(ex) => Some(s"rollup ${target.blockId}, target ${target.utxoId}: " +
        Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
      case Success(_) => None
    }
    // A partial or different chain never publishes a successful recovery result.
    val payloads = if (failure.isDefined) Map.empty[String, ResolvedNisp] else recovered.toMap
    val missing = requested.keySet.diff(payloads.keySet).iterator.map { key => key -> failure.getOrElse(
      s"no submission for miner $key matching ${requested(key).hash} before box ${target.utxoId}") }.toMap
    NispResolution(ResolvedNisps(target.blockId, payloads), missing, failure, applied, reads,
      (System.nanoTime() - started) / 1000000L)
  }
}
