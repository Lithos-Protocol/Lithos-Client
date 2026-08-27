package state.synchronization

import lfsm.states.MinerTree
import node.NodeApi
import node.model.{IndexedBox, Paging}
import org.slf4j.{Logger, LoggerFactory}
import state.messages.SyncMessages.SyncCursor
import org.ergoplatform.sdk.ErgoId
import state.messages.{BlockTx, NodeSync}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/** Miner Dictionary state and local data-box token at the synchronization seed height. */
final case class MinerDictionarySeed(minerTree: MinerTree, dataBoxToken: Option[ErgoId])

/**
 * Rebuilds the Miner Dictionary by following its singleton box spend chain.
 *
 * The reconstructed tip must match the live dictionary box before a seed is returned.
 */
final class MinerDictionaryBootstrap(nodeApi: NodeApi,
                                     protocol: SyncProtocolContext,
                                     maxTransforms: Int) {

  private val logger: Logger = LoggerFactory.getLogger("MinerDictionaryBootstrap")

  /** Returns verified dictionary state at `seedHeight`; later transforms remain for block sync. */
  def run(seedHeight: Int): Either[String, MinerDictionarySeed] = {
    val started = System.currentTimeMillis()
    // Start empty so a pre-registration seed cannot inherit later dictionary state.
    val empty = seedState(startState)
    walk(empty, protocol.minerDictionaryGenesisId, seedHeight, applied = 0, seed = Some(empty)) match {
      case Left(reason) => Left(reason)
      case Right(result) => verify(result).map { _ =>
        val elapsed = System.currentTimeMillis() - started
        val seed = MinerDictionarySeed(result.seed.minerTree, result.seed.dataBoxToken)
        logger.info(s"Miner Dictionary bootstrap replayed ${result.applied} transform(s) in ${elapsed}ms; " +
          s"${seed.minerTree.numMiners} miner(s) registered at height $seedHeight" +
          seed.dataBoxToken.map(token => s"; this miner's data box is $token").getOrElse(""))
        seed
      }
    }
  }

  /** Anchors the empty dictionary to the configured genesis box. */
  private def startState: MinerTree =
    MinerTree.initialState.copy(utxoId = protocol.minerDictionaryGenesisId)

  private final case class WalkResult(seed: CommittedSyncState,
                                      tip: CommittedSyncState,
                                      tipBoxId: String,
                                      applied: Int)

  /** Follows each dictionary spend while retaining the latest state at or below `seedHeight`. */
  @tailrec
  private def walk(current: CommittedSyncState,
                   boxId: String,
                   seedHeight: Int,
                   applied: Int,
                   seed: Option[CommittedSyncState]): Either[String, WalkResult] = {
    if (applied > maxTransforms)
      Left(s"Miner Dictionary history exceeds sync.minerDictionary.maxTransforms ($maxTransforms)")
    else nodeApi.indexedBoxById(boxId) match {
      case Failure(ex) => Left(s"could not read dictionary box $boxId: ${message(ex)}")
      case Success(None) => Left(s"indexed node has no dictionary box $boxId")
      case Success(Some(box)) => box.spentTransactionId match {
        case None =>
          Right(WalkResult(seed.getOrElse(current), current, boxId, applied))
        case Some(txId) => applySpend(current, box, txId) match {
          case Left(reason) => Left(reason)
          case Right((next, nextBoxId, height)) =>
            val carried = if (height <= seedHeight) Some(next) else seed
            walk(next, nextBoxId, seedHeight, applied + 1, carried)
        }
      }
    }
  }

  private def applySpend(current: CommittedSyncState,
                         box: IndexedBox,
                         txId: String): Either[String, (CommittedSyncState, String, Int)] =
    nodeApi.indexedTransactionById(txId) match {
      case Failure(ex) => Left(s"could not read dictionary transaction $txId: ${message(ex)}")
      case Success(None) => Left(s"indexed node has no transaction $txId spending ${box.boxId}")
      case Success(Some(indexed)) =>
        val tx: BlockTx = NodeSync.blockTx(indexed)
        val height = box.spendingHeight.getOrElse(indexed.inclusionHeight)
        BlockReducer.applyMinerDictionaryTransform(current, height, tx, protocol) match {
          case Left(error) => Left(s"at height $height: ${error.message}")
          case Right(next) => tx.outputs.headOption match {
            case Some(output) => Right((next, output.id, height))
            case None => Left(s"dictionary transaction $txId has no output to follow")
          }
        }
    }

  /** Wraps dictionary state in the reducer's complete-state input. */
  private def seedState(tree: MinerTree): CommittedSyncState =
    CommittedSyncState(SyncCursor(0, "", ""), 0L, Map.empty, Map.empty, tree, None)

  /** Verifies the reconstructed tip against the current unspent dictionary box and digest. */
  private def verify(result: WalkResult): Either[String, Unit] =
    liveDictionaryBox.flatMap { live =>
      if (live.boxId != result.tipBoxId)
        Left(s"dictionary advanced during bootstrap: walked to ${result.tipBoxId}, chain holds ${live.boxId}")
      else liveDigest(live).flatMap { digest =>
        if (java.util.Arrays.equals(digest, result.tip.minerTree.dictionary.digest)) Right(())
        else Left("reconstructed dictionary digest does not match the live dictionary box")
      }
    }

  private def liveDictionaryBox: Either[String, IndexedBox] =
    nodeApi.boxesByTokenId(protocol.minerDictionaryToken.toString, Paging(0, 10)) match {
      case Failure(ex) => Left(s"could not read the live dictionary box: ${message(ex)}")
      case Success(paged) => paged.items.filterNot(_.isSpent) match {
        case Seq(box) => Right(box)
        case Seq() => Left("no unspent box holds the Miner Dictionary singleton")
        case many => Left(s"${many.size} unspent boxes hold the Miner Dictionary singleton")
      }
    }

  private def liveDigest(box: IndexedBox): Either[String, Array[Byte]] =
    box.box.additionalRegisters.ordered.headOption match {
      case None => Left("live dictionary box has no R4")
      case Some(hex) => Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue
        .asInstanceOf[sigma.AvlTree].digest.toArray) match {
        case Success(digest) => Right(digest)
        case Failure(ex) => Left(s"live dictionary box R4 is not an authenticated dictionary: ${message(ex)}")
      }
    }

  private def message(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
}
