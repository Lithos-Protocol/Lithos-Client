package transactions.rollups

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import evaluation.CommitmentSource
import lfsm.states.MinerDictionary
import node.NodeApi
import node.MutationConversions._
import node.model.{MempoolOptions, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import state.messages.SyncMessages.{CurrentMinerDictionary, GetMinerDictionary}
import utils.Helpers
import work.lithos.mutations.InputUTXO

import scala.concurrent.Await
import scala.util.Try

/**
 * Assembles what `FP_NonMatchingCommitment` reads outside the rollup.
 *
 * Everything here is best effort and returns `None` rather than throwing: the other seven proofs need
 * none of it, and a client whose Miner Dictionary is still catching up or has faulted should go on
 * slashing the fraud it can still see. What must not happen is a `CommitmentSource` that is present
 * but wrong — a missing MinerData box makes the proof index past its data inputs and throw, which
 * `Evaluator` cannot tell from a proof that never ran.
 */
object CommitmentSources {

  private val logger: Logger = LoggerFactory.getLogger("CommitmentSources")

  /** A dictionary entry is `[credentialId: 32][validUntil: 8]`. */
  private val EntrySize = 32 + 8

  def load(ctx: BlockchainContext,
           nodeApi: NodeApi,
           syncHandler: ActorRef,
           miners: Seq[Array[Byte]])(implicit timeout: Timeout): Option[CommitmentSource] =
    dictionary(syncHandler).flatMap(md => build(ctx, nodeApi, md, miners))

  /** The materialized dictionary, or None when synchronization cannot vouch for it. */
  private def dictionary(syncHandler: ActorRef)(implicit timeout: Timeout): Option[MinerDictionary] =
    Try(Await.result(syncHandler ? GetMinerDictionary, timeout.duration)) match {
      case scala.util.Success(CurrentMinerDictionary(md)) => Some(md)
      case scala.util.Success(other) =>
        logger.info(s"Miner Dictionary unavailable for commitment proofs: $other")
        None
      case scala.util.Failure(ex) =>
        logger.warn(s"Could not read the Miner Dictionary for commitment proofs: ${ex.getMessage}")
        None
    }

  private def build(ctx: BlockchainContext,
                    nodeApi: NodeApi,
                    md: MinerDictionary,
                    miners: Seq[Array[Byte]]): Option[CommitmentSource] =
    Try(InputUTXO(ctx.getBoxesById(md.utxoId).head)) match {
      case scala.util.Failure(ex) =>
        logger.warn(s"Could not load Miner Dictionary box ${md.utxoId}: ${ex.getMessage}")
        None
      case scala.util.Success(dictBox) =>
        val boxes = miners.flatMap { miner =>
          dataBoxFor(ctx, nodeApi, md, miner).map(Hex.toHexString(miner) -> _)
        }.toMap
        Some(CommitmentSource(md.dictionary, dictBox, boxes))
    }

  /**
   * The accused's MinerData box, found by the credential their dictionary entry names.
   *
   * Only registered miners have one, and only a registration the proof will still honour needs one —
   * an expired or malformed entry sends the contract down the branch that reads the dictionary alone.
   * The credential is what makes this box theirs: anyone can send a box to the MinerData address.
   */
  private def dataBoxFor(ctx: BlockchainContext,
                         nodeApi: NodeApi,
                         md: MinerDictionary,
                         miner: Array[Byte]): Option[InputUTXO] = {
    val entry = Try(md.dictionary.copy().lookUp(miner).response.headOption.flatMap(_.tryOp.toOption).flatten)
      .toOption.flatten
    entry.filter(_.length == EntrySize).flatMap { e =>
      val credential = Hex.toHexString(e.slice(0, 32))
      val dataTree = Helpers.dataBoxContract(ctx).ergoTreeHex
      nodeApi.unspentBoxesByTokenId(credential, Paging(0, 8), SortDirection.Desc,
        MempoolOptions.ConfirmedOnly)
        .getOrElse(Seq.empty)
        .find(b => b.ergoTree == dataTree && b.assets.headOption.exists(_.tokenId == credential))
        .map(_.toInputUTXO(ctx))
    }
  }
}
