package state.synchronization

import lfsm.states.MinerDictionary
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.slf4j.{Logger, LoggerFactory}
import state.messages.SyncMessages.SyncCursor
import org.ergoplatform.sdk.ErgoId
import state.messages.{BlockTx, NodeSync}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/** Miner Dictionary state and local data-box token at the synchronization seed height. */
final case class MinerDictionarySeed(minerTree: MinerDictionary, dataBoxToken: Option[ErgoId])

object MinerDictionaryBootstrap {
  /**
   * Walk segments allowed before a moving tip is reported as a failure. Each resume costs one box
   * read plus the transforms that landed, so this bounds a dictionary registering faster than the
   * client can read it without capping ordinary chain activity.
   */
  private[synchronization] final val MaxResumes = 16
}

/** Why a reconstructed tip did not match the live dictionary box. */
private sealed trait VerifyFailure
private object VerifyFailure {
  /** The chain moved on while the walk ran. The walk can continue from where it stopped. */
  final case class Advanced(liveBoxId: String) extends VerifyFailure
  final case class Fatal(reason: String) extends VerifyFailure
}

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
    val replay = new BlockReducer.MinerDictionaryReplay(seedState(startState), protocol)
    converge(replay, protocol.minerDictionaryGenesisId, seedHeight, applied = 0, seed = None,
      resumes = 0, started)
  }

  /**
   * Walks to the tip, and on finding the chain moved on continues from the box already reached.
   *
   * Restarting from genesis instead makes the expected cost grow with the chain, so past the point
   * where a walk takes longer than the gap between registrations it never finishes at all.
   */
  @tailrec
  private def converge(replay: BlockReducer.MinerDictionaryReplay,
                       boxId: String,
                       seedHeight: Int,
                       applied: Int,
                       seed: Option[CommittedSyncState],
                       resumes: Int,
                       started: Long): Either[String, MinerDictionarySeed] =
    walk(replay, boxId, seedHeight, applied, seed) match {
      case Left(reason) => Left(reason)
      case Right(result) => verify(result) match {
        case Right(_) =>
          val elapsed = System.currentTimeMillis() - started
          // Every transform landed at or below the seed height, so the walk's end is also the seed.
          val frozen = result.seed.getOrElse(replay.frozen)
          val value = MinerDictionarySeed(frozen.minerTree, frozen.dataBoxToken)
          val segments = if (resumes > 0) s" across ${resumes + 1} walk segment(s)" else ""
          logger.info(s"Miner Dictionary bootstrap replayed ${result.applied} transform(s) in " +
            s"${elapsed}ms$segments; ${value.minerTree.numMiners} miner(s) registered at height " +
            s"$seedHeight" +
            value.dataBoxToken.map(token => s"; this miner's data box is $token").getOrElse(""))
          Right(value)

        // The unresolved seed carries forward, so a segment that never reached seedHeight still
        // freezes on the transform that crosses it rather than at the segment boundary.
        case Left(VerifyFailure.Advanced(live)) if resumes < MinerDictionaryBootstrap.MaxResumes =>
          logger.info(s"Miner Dictionary advanced to $live during bootstrap; continuing from " +
            s"${result.tipBoxId} after ${result.applied} transform(s)")
          converge(replay, result.tipBoxId, seedHeight, result.applied, result.seed,
            resumes + 1, started)

        case Left(VerifyFailure.Advanced(live)) =>
          Left(s"dictionary advanced ${MinerDictionaryBootstrap.MaxResumes} times during bootstrap; " +
            s"walked to ${result.tipBoxId}, chain holds $live")

        case Left(VerifyFailure.Fatal(reason)) => Left(reason)
      }
    }

  /** Anchors the empty dictionary to the configured genesis box. */
  private def startState: MinerDictionary =
    MinerDictionary.initialState.copy(utxoId = protocol.minerDictionaryGenesisId)

  /**
   * `seed` stays unresolved between segments. Resolving it per segment would both take a full
   * dictionary copy each time and freeze the seed at the end of a segment that never reached
   * `seedHeight`, which is a shorter dictionary than the height asked for.
   */
  private final case class WalkResult(seed: Option[CommittedSyncState],
                                      tip: CommittedSyncState,
                                      tipBoxId: String,
                                      applied: Int)

  /**
   * Follows each dictionary spend, freezing the seed state the first time a transform lands above it.
   *
   * The replay mutates one dictionary, so the seed has to be taken before the transform that leaves
   * its height rather than after every transform below it.
   */
  @tailrec
  private def walk(replay: BlockReducer.MinerDictionaryReplay,
                   boxId: String,
                   seedHeight: Int,
                   applied: Int,
                   seed: Option[CommittedSyncState]): Either[String, WalkResult] = {
    nodeApi.indexedBoxById(boxId) match {
      case Failure(ex) => Left(s"could not read dictionary box $boxId: ${message(ex)}")
      case Success(None) => Left(s"indexed node has no dictionary box $boxId")
      case Success(Some(box)) => box.spentTransactionId match {
        case None => Right(WalkResult(seed, replay.current, boxId, applied))
        // Checked here rather than before the read, so a chain of exactly the bound still terminates.
        case Some(_) if applied >= maxTransforms =>
          Left(s"Miner Dictionary history exceeds sync.minerDictionary.maxTransforms ($maxTransforms)")
        case Some(txId) => readSpend(box, txId) match {
          case Left(reason) => Left(reason)
          case Right((tx, height, nextBoxId)) =>
            val carried = if (height > seedHeight && seed.isEmpty) Some(replay.frozen) else seed
            replay.apply(height, tx) match {
              case Left(error) => Left(s"at height $height: ${error.message}")
              case Right(_) => walk(replay, nextBoxId, seedHeight, applied + 1, carried)
            }
        }
      }
    }
  }

  private def readSpend(box: IndexedBox,
                        txId: String): Either[String, (BlockTx, Int, String)] =
    nodeApi.indexedTransactionById(txId) match {
      case Failure(ex) => Left(s"could not read dictionary transaction $txId: ${message(ex)}")
      case Success(None) => Left(s"indexed node has no transaction $txId spending ${box.boxId}")
      case Success(Some(indexed)) =>
        val tx: BlockTx = NodeSync.blockTx(indexed)
        // The configured node is authoritative. The transaction inclusion is the one boundary the
        // reducer needs; comparing it with another field from the same node adds no trust.
        val height = indexed.inclusionHeight
        tx.outputs.headOption match {
          case Some(output) => Right((tx, height, output.id))
          case None => Left(s"dictionary transaction $txId has no output to follow")
        }
    }

  /** Wraps dictionary state in the reducer's complete-state input. */
  private def seedState(tree: MinerDictionary): CommittedSyncState =
    CommittedSyncState(SyncCursor(0, "", ""), 0L, Map.empty, Map.empty, Map.empty, tree, None)

  /**
   * Verifies the reconstructed tip against the current unspent dictionary box and digest.
   *
   * A tip that moved is separated from every other failure because it is the one the walk can
   * continue from; the rest mean the reconstruction itself is wrong.
   */
  private def verify(result: WalkResult): Either[VerifyFailure, Unit] =
    liveDictionaryBox match {
      case Left(reason) => Left(VerifyFailure.Fatal(reason))
      case Right(live) if live.boxId != result.tipBoxId =>
        Left(VerifyFailure.Advanced(live.boxId))
      case Right(live) => liveDigest(live) match {
        case Left(reason) => Left(VerifyFailure.Fatal(reason))
        case Right(digest) =>
          if (java.util.Arrays.equals(digest, result.tip.minerTree.dictionary.digest)) Right(())
          else Left(VerifyFailure.Fatal(
            "reconstructed dictionary digest does not match the live dictionary box"))
      }
    }

  /**
   * The unspent box holding the dictionary singleton.
   */
  private def liveDictionaryBox: Either[String, IndexedBox] =
    nodeApi.unspentBoxesByTokenId(protocol.minerDictionaryToken.toString, Paging(0, 8),
      SortDirection.Desc, MempoolOptions.ConfirmedOnly) match {
      case Failure(ex) => Left(s"could not read the live dictionary box: ${message(ex)}")
      case Success(boxes) => boxes.filterNot(_.isSpent) match {
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
