package state.synchronization

import lfsm.states.Rollup
import node.NodeApi
import node.model.IndexedBox
import org.slf4j.{Logger, LoggerFactory}
import state.messages.{BlockInfo, BlockTx, NodeSync}

import scala.annotation.tailrec
import scala.util.{Failure, Success}

/** A quarantined rollup rebuilt to a committed height, or the reason it stays quarantined. */
sealed trait RollupRepairResult
object RollupRepairResult {
  final case class Rebuilt(tree: Rollup) extends RollupRepairResult
  /** The rollup ended on chain at or below the committed height, so nothing needs tracking. */
  case object Terminated extends RollupRepairResult
  final case class Failed(reason: String, retryable: Boolean) extends RollupRepairResult
}

/**
 * Rebuilds one quarantined rollup from its collateral box and then follows only that rollup's boxes.
 * The configured node is authoritative: each box names the next transaction and that transaction's
 * indexed inclusion names the block boundary. No redundant same-node header comparison is performed.
 */
final class RollupRepair(nodeApi: NodeApi, protocol: SyncProtocolContext, maxTransforms: Int,
                          onIndexedRead: () => Unit = () => ()) {

  private val logger: Logger = LoggerFactory.getLogger("RollupRepair")

  private sealed trait SpendRead
  private case object BeyondCursor extends SpendRead
  private final case class Included(block: BlockInfo, tx: BlockTx) extends SpendRead

  def run(fault: QuarantineFault, committedHeight: Int): RollupRepairResult = {
    val started = System.currentTimeMillis()
    val replay = new BlockReducer.RollupReplay(protocol)
    seed(replay, fault, committedHeight) match {
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

  /** Authenticate and apply the one genesis transaction that spent the retained collateral box. */
  private[synchronization] def seed(replay: BlockReducer.RollupReplay,
                   fault: QuarantineFault,
                   committedHeight: Int): Either[RollupRepairResult, String] =
    readBox(fault.collateralBoxId, "collateral").flatMap { collateral =>
      readLinkedSpend(collateral, committedHeight).flatMap {
        case BeyondCursor => Left(RollupRepairResult.Failed(
          s"collateral box ${fault.collateralBoxId} is spent above committed height $committedHeight",
          retryable = true))
        case Included(block, tx) if block.id != fault.rollupId =>
          Left(RollupRepairResult.Failed(
            s"collateral transaction ${tx.id} is in block ${block.id}, not rollup ${fault.rollupId}",
            retryable = true))
        case Included(block, tx) if block.height != fault.genesisHeight =>
          Left(RollupRepairResult.Failed(
            s"collateral transaction ${tx.id} is at height ${block.height}, not genesis height " +
              s"${fault.genesisHeight}", retryable = true))
        case Included(block, tx) => replay.apply(block, tx) match {
          case Left(error) =>
            Left(RollupRepairResult.Failed(error.message, BlockReducer.isRetryable(error)))
          case Right(false) =>
            Left(RollupRepairResult.Failed(
              s"collateral transaction ${tx.id} created no recognized rollup state", retryable = false))
          case Right(true) => replay.rollup(fault.rollupId) match {
            case Some(tree) => Right(tree.utxoId)
            case None => Left(RollupRepairResult.Failed(
              s"collateral transaction ${tx.id} did not create rollup ${fault.rollupId}",
              retryable = false))
          }
        }
      }
    }

  @tailrec
  private def walk(replay: BlockReducer.RollupReplay,
                   rollupId: String,
                   boxId: String,
                   committedHeight: Int,
                   applied: Int): Either[RollupRepairResult, Int] =
    readBox(boxId, "rollup") match {
      case Left(failure) => Left(failure)
      case Right(box) => box.spentTransactionId match {
        case None => Right(applied)
        case Some(_) => readLinkedSpend(box, committedHeight) match {
          case Left(failure) => Left(failure)
          // The current box is the committed state at the cutoff; its later spend belongs to block sync.
          case Right(BeyondCursor) => Right(applied)
          case Right(Included(_, _)) if applied >= maxTransforms =>
            Left(RollupRepairResult.Failed(
              s"rollup history exceeds sync.quarantine.maxTransforms ($maxTransforms)",
              retryable = false))
          case Right(Included(block, tx)) => replay.apply(block, tx) match {
            case Left(error) =>
              Left(RollupRepairResult.Failed(s"at height ${block.height}: ${error.message}",
                BlockReducer.isRetryable(error)))
            case Right(false) =>
              Left(RollupRepairResult.Failed(
                s"transaction ${tx.id} at height ${block.height} changed no rollup state",
                retryable = false))
            case Right(true) => replay.rollup(rollupId) match {
              // Removed by its own payout or phase transition, which is a clean ending.
              case None => Right(applied + 1)
              case Some(tree) => walk(replay, rollupId, tree.utxoId, committedHeight, applied + 1)
            }
          }
        }
      }
    }

  private def readBox(boxId: String,
                      kind: String): Either[RollupRepairResult, IndexedBox] = {
    onIndexedRead()
    nodeApi.indexedBoxById(boxId) match {
      case Failure(ex) => Left(RollupRepairResult.Failed(
        s"could not read $kind box $boxId: ${message(ex)}", retryable = true))
      case Success(None) => Left(RollupRepairResult.Failed(
        s"indexed node has no $kind box $boxId", retryable = true))
      case Success(Some(box)) => Right(box)
    }
  }

  /** A missing transaction id is the only unspent-chain terminator; inclusion comes from the tx. */
  private def readLinkedSpend(box: IndexedBox,
                              committedHeight: Int): Either[RollupRepairResult, SpendRead] =
    box.spentTransactionId match {
      case None => Left(RollupRepairResult.Failed(
        s"box ${box.boxId} has no spending transaction", retryable = true))
      case Some(txId) => readSpend(txId, box, committedHeight)
    }

  private def readSpend(txId: String,
                        spentBox: IndexedBox,
                        committedHeight: Int): Either[RollupRepairResult, SpendRead] = {
    onIndexedRead()
    nodeApi.indexedTransactionById(txId) match {
      case Failure(ex) => Left(RollupRepairResult.Failed(
        s"could not read rollup transaction $txId: ${message(ex)}", retryable = true))
      case Success(None) => Left(RollupRepairResult.Failed(
        s"indexed node has no transaction $txId", retryable = true))
      case Success(Some(indexed)) =>
        if (indexed.inclusionHeight > committedHeight) Right(BeyondCursor)
        else {
          val tx = NodeSync.blockTx(indexed)
          // Resolve the followed input from the separately fetched box record, not another copy of
          // its contents embedded in the transaction endpoint. The box id is the link; the retained
          // record is the collateral/script evidence genesis classification authenticates.
          val inputs = indexed.inputs.map(in => in.boxId -> NodeSync.txOutput(in)).toMap +
            (spentBox.boxId -> NodeSync.txOutput(spentBox))
          Right(Included(BlockInfo(indexed.blockId, indexed.inclusionHeight, Seq(tx), "", inputs), tx))
        }
    }
  }

  private def message(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
}
