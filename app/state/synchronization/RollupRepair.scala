package state.synchronization

import lfsm.states.NISPTree
import node.NodeApi
import org.slf4j.{Logger, LoggerFactory}
import state.messages.{BlockTx, NodeSync}

import scala.annotation.tailrec

/** A quarantined rollup rebuilt to a committed height, or the reason it stays quarantined. */
sealed trait RollupRepairResult
object RollupRepairResult {
  final case class Rebuilt(tree: NISPTree) extends RollupRepairResult
  /** The rollup ended on chain at or below the committed height, so nothing needs tracking. */
  case object Terminated extends RollupRepairResult
  final case class Failed(reason: String, retryable: Boolean) extends RollupRepairResult
}

/**
 * Rebuilds one quarantined rollup by replaying its own spend chain from its genesis block.
 * Two indexed calls per transform, similar to bootstrap protocols.
 */
final class RollupRepair(nodeApi: NodeApi, protocol: SyncProtocolContext, maxTransforms: Int) {

  private val logger: Logger = LoggerFactory.getLogger("RollupRepair")
  private val source = new CanonicalBlockSource(nodeApi)

  def run(fault: QuarantineFault, committedHeight: Int): RollupRepairResult = {
    val started = System.currentTimeMillis()
    val replay = new BlockReducer.RollupReplay(protocol)
    seed(replay, fault) match {
      case Left(failure) => failure
      case Right(utxoId) => walk(replay, fault.rollupId, utxoId, committedHeight, applied = 0) match {
        case Left(failure) => failure
        case Right(applied) => replay.rollup(fault.rollupId) match {
          case None => RollupRepairResult.Terminated
          case Some(tree) =>
            logger.info(s"Rebuilt rollup ${fault.rollupId} from height ${fault.genesisHeight} in " +
              s"${System.currentTimeMillis() - started}ms: $applied transform(s), ${tree.numMiners} " +
              s"miner(s), phase ${tree.phase}")
            RollupRepairResult.Rebuilt(tree)
        }
      }
    }
  }

  /** Replays the genesis block so the rollup exists before its own spends are followed. */
  private def seed(replay: BlockReducer.RollupReplay,
                   fault: QuarantineFault): Either[RollupRepairResult, String] =
    source.blockAt(fault.genesisHeight) match {
      case scala.util.Failure(ex) =>
        Left(RollupRepairResult.Failed(s"could not read genesis block at ${fault.genesisHeight}: " +
          message(ex), retryable = true))
      case scala.util.Success(block) if block.id != fault.rollupId =>
        Left(RollupRepairResult.Failed(s"height ${fault.genesisHeight} now holds block ${block.id}, " +
          s"not ${fault.rollupId}", retryable = false))
      case scala.util.Success(block) =>
        val applied = block.txs.foldLeft[Either[RollupRepairResult, Unit]](Right(())) {
          case (Right(_), tx) => replay.apply(block, tx) match {
            case Right(_) => Right(())
            case Left(error) =>
              Left(RollupRepairResult.Failed(error.message, BlockReducer.isRetryable(error)))
          }
          case (left, _) => left
        }
        applied.flatMap(_ => replay.rollup(fault.rollupId) match {
          case Some(tree) => Right(tree.utxoId)
          case None => Left(RollupRepairResult.Failed(
            s"block ${fault.rollupId} no longer produces a rollup this build recognizes",
            retryable = false))
        })
    }

  @tailrec
  private def walk(replay: BlockReducer.RollupReplay,
                   rollupId: String,
                   boxId: String,
                   committedHeight: Int,
                   applied: Int): Either[RollupRepairResult, Int] =
    nodeApi.indexedBoxById(boxId) match {
      case scala.util.Failure(ex) =>
        Left(RollupRepairResult.Failed(s"could not read rollup box $boxId: ${message(ex)}",
          retryable = true))
      case scala.util.Success(None) =>
        Left(RollupRepairResult.Failed(s"indexed node has no rollup box $boxId", retryable = true))
      case scala.util.Success(Some(box)) => box.spentTransactionId match {
        // Unspent, or spent above the cursor: the block path owns everything from here.
        case None => Right(applied)
        case Some(_) if box.spendingHeight.exists(_ > committedHeight) => Right(applied)
        case Some(_) if applied >= maxTransforms =>
          Left(RollupRepairResult.Failed(
            s"rollup history exceeds sync.minerDictionary.maxTransforms ($maxTransforms)",
            retryable = false))
        case Some(txId) => readSpend(txId, box.spendingHeight.getOrElse(committedHeight)) match {
          case Left(failure) => Left(failure)
          case Right((tx, height)) =>
            val block = state.messages.BlockInfo(id = "", height = height, txs = Seq(tx), parentId = "")
            replay.apply(block, tx) match {
              case Left(error) =>
                Left(RollupRepairResult.Failed(s"at height $height: ${error.message}",
                  BlockReducer.isRetryable(error)))
              case Right(false) =>
                Left(RollupRepairResult.Failed(
                  s"transaction $txId at height $height changed no rollup state", retryable = false))
              case Right(true) => replay.rollup(rollupId) match {
                // Removed by its own payout or phase transition, which is a clean ending.
                case None => Right(applied + 1)
                case Some(tree) =>
                  walk(replay, rollupId, tree.utxoId, committedHeight, applied + 1)
              }
            }
        }
      }
    }

  private def readSpend(txId: String, height: Int): Either[RollupRepairResult, (BlockTx, Int)] =
    nodeApi.indexedTransactionById(txId) match {
      case scala.util.Failure(ex) =>
        Left(RollupRepairResult.Failed(s"could not read rollup transaction $txId: ${message(ex)}",
          retryable = true))
      case scala.util.Success(None) =>
        Left(RollupRepairResult.Failed(s"indexed node has no transaction $txId", retryable = true))
      case scala.util.Success(Some(indexed)) => Right((NodeSync.blockTx(indexed), height))
    }

  private def message(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
}
