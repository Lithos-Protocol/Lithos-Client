package state.synchronization

import lfsm.states.NISPTree
import node.NodeApi
import node.model.IndexedBox
import org.slf4j.{Logger, LoggerFactory}
import state.messages.{BlockInfo, BlockTx, NodeSync}

import scala.annotation.tailrec
import scala.util.{Failure, Success}

/** A quarantined rollup rebuilt to a committed height, or the reason it stays quarantined. */
sealed trait RollupRepairResult
object RollupRepairResult {
  final case class Rebuilt(tree: NISPTree) extends RollupRepairResult
  /** The rollup ended on chain at or below the committed height, so nothing needs tracking. */
  case object Terminated extends RollupRepairResult
  final case class Failed(reason: String, retryable: Boolean) extends RollupRepairResult
}

/**
 * Rebuilds one quarantined rollup from its collateral box and then follows only that rollup's boxes.
 * Each applied link is authenticated against its indexed transaction and the canonical header chain;
 * no full block is fetched or replayed.
 */
final class RollupRepair(nodeApi: NodeApi, protocol: SyncProtocolContext, maxTransforms: Int) {

  private val logger: Logger = LoggerFactory.getLogger("RollupRepair")
  private val source = new CanonicalBlockSource(nodeApi)

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
  private def seed(replay: BlockReducer.RollupReplay,
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
                      kind: String): Either[RollupRepairResult, IndexedBox] =
    nodeApi.indexedBoxById(boxId) match {
      case Failure(ex) => Left(RollupRepairResult.Failed(
        s"could not read $kind box $boxId: ${message(ex)}", retryable = true))
      case Success(None) => Left(RollupRepairResult.Failed(
        s"indexed node has no $kind box $boxId", retryable = true))
      case Success(Some(box)) if box.boxId != boxId => Left(RollupRepairResult.Failed(
        s"indexed node returned box ${box.boxId} for requested $kind box $boxId", retryable = true))
      case Success(Some(box)) => Right(box)
    }

  /** Every spent link needs both index evidence and the authoritative transaction inclusion record. */
  private def readLinkedSpend(box: IndexedBox,
                              committedHeight: Int): Either[RollupRepairResult, SpendRead] =
    box.spentTransactionId match {
      case None => Left(RollupRepairResult.Failed(
        s"box ${box.boxId} has no spending transaction", retryable = true))
      case Some(txId) => box.spendingHeight match {
        case None => Left(RollupRepairResult.Failed(
          s"box ${box.boxId} names spending transaction $txId without a spending height",
          retryable = true))
        case Some(indexedHeight) => readSpend(txId, box, indexedHeight, committedHeight)
      }
    }

  private def readSpend(txId: String,
                        spentBox: IndexedBox,
                        indexedSpendingHeight: Int,
                        committedHeight: Int): Either[RollupRepairResult, SpendRead] =
    nodeApi.indexedTransactionById(txId) match {
      case Failure(ex) => Left(RollupRepairResult.Failed(
        s"could not read rollup transaction $txId: ${message(ex)}", retryable = true))
      case Success(None) => Left(RollupRepairResult.Failed(
        s"indexed node has no transaction $txId", retryable = true))
      case Success(Some(indexed)) if indexed.id != txId => Left(RollupRepairResult.Failed(
        s"indexed node returned transaction ${indexed.id} for requested transaction $txId",
        retryable = true))
      case Success(Some(indexed)) if !indexed.inputs.exists(_.boxId == spentBox.boxId) =>
        Left(RollupRepairResult.Failed(
          s"transaction $txId does not spend followed box ${spentBox.boxId}", retryable = true))
      case Success(Some(indexed)) if indexed.inclusionHeight < 0 =>
        Left(RollupRepairResult.Failed(
          s"transaction $txId has no valid inclusion height", retryable = true))
      case Success(Some(indexed)) if indexed.inclusionHeight != indexedSpendingHeight =>
        Left(RollupRepairResult.Failed(
          s"transaction $txId is included at ${indexed.inclusionHeight}, but box ${spentBox.boxId} " +
            s"reports spending height $indexedSpendingHeight", retryable = true))
      case Success(Some(indexed)) => source.headerAt(indexed.inclusionHeight) match {
        case Failure(ex) => Left(RollupRepairResult.Failed(
          s"could not authenticate transaction $txId at height ${indexed.inclusionHeight}: ${message(ex)}",
          retryable = true))
        case Success(header) if header.id != indexed.blockId => Left(RollupRepairResult.Failed(
          s"transaction $txId names block ${indexed.blockId}, but canonical height " +
            s"${indexed.inclusionHeight} is ${header.id}", retryable = true))
        case Success(_) if indexed.inclusionHeight > committedHeight => Right(BeyondCursor)
        case Success(header) =>
          val tx = NodeSync.blockTx(indexed)
          // Resolve the followed input from the separately fetched box record, not another copy of
          // its contents embedded in the transaction endpoint. The box id is the link; the retained
          // record is the collateral/script evidence genesis classification authenticates.
          val inputs = indexed.inputs.map(in => in.boxId -> NodeSync.txOutput(in)).toMap +
            (spentBox.boxId -> NodeSync.txOutput(spentBox))
          Right(Included(BlockInfo(header.id, header.height, Seq(tx), header.parentId, inputs), tx))
      }
    }

  private def message(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
}
