package transactions.rent

import org.ergoplatform.appkit.{BlockchainParameters => NodeParameters}
import org.ergoplatform.sdk.BlockchainParameters
import org.ergoplatform.validation.ValidationRules
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import sigma.crypto.CryptoConstants
import sigma.data.CGroupElement
import sigma.{Colls, Header, PreHeader}
import sigmastate.eval.CPreHeader
import sigmastate.interpreter.Interpreter

import scala.util.{Failure, Success, Try}

/** What the node's own rule made of one rent input. */
sealed trait RentVerdict

object RentVerdict {
  case object Accepted extends RentVerdict

  case object Refused extends RentVerdict

  /** The check itself could not be run, which says nothing about the box. */
  final case class Unverifiable(reason: String) extends RentVerdict
}

/**
 * Each rent input put through `ErgoInterpreter.verify`, the call the node verifies inputs with.
 *
 * A sweep carries empty proofs, so no signing step checks it; this runs the node's own rule in place
 * of the client's reasoning about it. Input-level only: dust and distinct output indices are
 * transaction rules, asserted where the sweep is assembled.
 */
object RentVerifier {

  /** One verdict per input of `tx`, whose inputs spend `boxes` in order, at the height it is built for. */
  def verdicts(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], height: Int,
               params: NodeParameters): IndexedSeq[RentVerdict] = {
    // A verdict checked against the wrong box would evict a good one, so misalignment fails the build.
    require(boxes.size == tx.inputs.size &&
      boxes.indices.forall(i => java.util.Arrays.equals(boxes(i).id, tx.inputs(i).boxId)),
      "rent verdicts need the spent boxes in input order")
    val interpreter = ErgoInterpreter(mirrored(params))
    // Serialized once for the whole sweep rather than once per input.
    val message = tx.messageToSign
    boxes.indices.map { i =>
      Try(contextFor(tx, boxes, i, height, params)) match {
        case Failure(ex) => RentVerdict.Unverifiable(String.valueOf(ex.getMessage))
        case Success(context) =>
          interpreter.verify(Interpreter.emptyEnv, boxes(i).ergoTree, context,
            tx.inputs(i).spendingProof.proof, message) match {
            case Success((true, _)) => RentVerdict.Accepted
            case _ => RentVerdict.Refused
          }
      }
    }
  }

  /**
   * The context the rent branch reads: the block height, the box, the transaction's outputs and the
   * input's own extension. Headers and the state root are placeholders, which only matter to a box
   * whose rent branch already failed and whose own script then runs against an empty proof.
   */
  private def contextFor(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], index: Int, height: Int,
                         params: NodeParameters): ErgoLikeContext =
    new ErgoLikeContext(
      ErgoInterpreter.avlTreeFromDigest(Colls.fromArray(Array.fill[Byte](33)(0))),
      Colls.emptyColl[Header],
      preHeaderAt(height),
      IndexedSeq.empty[ErgoBox],
      boxes,
      tx,
      index,
      tx.inputs(index).spendingProof.extension,
      ValidationRules.currentSettings,
      params.getMaxBlockCost.toLong,
      0L,
      activatedScriptVersion = (params.getBlockVersion - 1).toByte)

  private def preHeaderAt(height: Int): PreHeader = CPreHeader(
    version = 0,
    parentId = Colls.emptyColl[Byte],
    timestamp = 0L,
    nBits = 0L,
    height = height,
    minerPk = CGroupElement(CryptoConstants.dlogGroup.generator),
    votes = Colls.emptyColl[Byte])

  /** The node's parameters in the shape the interpreter takes. */
  private def mirrored(p: NodeParameters): BlockchainParameters = new BlockchainParameters {
    override def storageFeeFactor: Int = p.getStorageFeeFactor
    override def minValuePerByte: Int = p.getMinValuePerByte
    override def maxBlockSize: Int = p.getMaxBlockSize
    override def tokenAccessCost: Int = p.getTokenAccessCost
    override def inputCost: Int = p.getInputCost
    override def dataInputCost: Int = p.getDataInputCost
    override def outputCost: Int = p.getOutputCost
    override def maxBlockCost: Int = p.getMaxBlockCost
    override def softForkStartingHeight: Option[Int] = None
    override def softForkVotesCollected: Option[Int] = None
    override def blockVersion: Byte = p.getBlockVersion
  }
}
