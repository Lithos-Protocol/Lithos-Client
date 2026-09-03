package evaluation

import lfsm.states.{AuthenticatedDictionaryView, Rollup}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ContextVar
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Longs
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * What `FP_NonMatchingCommitment` needs beyond the rollup: the Miner Dictionary and the accused's own
 * MinerData box.
 *
 * `dataBoxes` is keyed by the hex of a miner's hashed proposition bytes, because one `Evaluator` walks
 * several miners against one dictionary.
 */
final case class CommitmentSource(dictionary: AuthenticatedDictionaryView,
                                  dictionaryBox: InputUTXO,
                                  dataBoxes: Map[String, InputUTXO]) {
  def dataBoxFor(miner: Array[Byte]): Option[InputUTXO] = dataBoxes.get(Hex.toHexString(miner))
}

object NonMatchingCommitmentProof {
  /** A dictionary entry is `[credentialId: 32][validUntil: 8]`. */
  final val EntrySize = 40
}

/**
 * The only proof that reads state outside the rollup, so the only one with data inputs of its own and
 * a fourth context variable.
 *
 * Which data inputs it needs depends on the answer to the dictionary lookup, and the decision has to
 * mirror the contract's `registrationLive` exactly. A live registration sends the contract down the
 * branch that reads `dataInputs(2)`, so the box has to be there; a missing, malformed or expired one
 * sends it down the branch that reads only the dictionary, and a third data input would go unread.
 *
 * Getting that backwards is not a rejected transaction, it is a thrown one — and a throw is
 * indistinguishable from a proof that never ran, so the miner goes unslashed. That is why the
 * mismatch below raises rather than building something optimistic.
 */
case class NonMatchingCommitmentProof(contract: Contract, miner: Array[Byte], nispTree: Rollup,
                                      evalInput: InputUTXO, fpControl: InputUTXO,
                                      source: CommitmentSource)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("NonMatchingCommitmentProof")

  /** The rollup's own block, which the contract compares the registration's expiry against. */
  private val currentBlock: Long = nispTree.state.nispBlockHeight

  private val lookUp = source.dictionary.copy().lookUp(miner)

  private val entry: Option[Array[Byte]] =
    lookUp.response.headOption.flatMap(_.tryOp.toOption).flatten

  /** `entryOpt.isDefined && entryWellFormed && currentBlock < validUntil`, as the contract has it. */
  private val registrationLive: Boolean = entry.exists { e =>
    e.length == NonMatchingCommitmentProof.EntrySize &&
      currentBlock < Longs.fromByteArray(e.slice(32, 40))
  }

  override protected def extraCtxVars: Seq[ContextVar] =
    Seq(ContextVar.of(3.toByte, lookUp.proof.ergoValue))

  override protected def extraDataInputs: Seq[InputUTXO] = {
    if (!registrationLive) Seq(source.dictionaryBox)
    else source.dataBoxFor(miner) match {
      case Some(box) => Seq(source.dictionaryBox, box)
      case None =>
        throw new IllegalStateException(
          s"miner ${Hex.toHexString(miner)} holds a registration live at block $currentBlock, so " +
            "FP_NonMatchingCommitment reads their MinerData box as dataInputs(2), but no box was " +
            "supplied for them")
    }
  }
}
