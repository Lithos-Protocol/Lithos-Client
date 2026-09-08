package support

import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.BlockchainParameters
import org.ergoplatform.validation.ValidationRules
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import sigma.crypto.CryptoConstants
import sigma.data.CGroupElement
import sigma.{Colls, Header, PreHeader}
import sigmastate.eval.CPreHeader
import sigmastate.interpreter.Interpreter

/**
 * Ergo's storage-rent rule, run directly.
 *
 * `ErgoInterpreter.verify` is what accepts an expired box spent with an empty proof, so a spec that
 * calls it is asserting against the rule itself rather than a restatement of it. Nothing here
 * compiles or proves a script — that is the whole point of the branch under test.
 */
object RentRule {

  /**
   * The node's own parameters, mirrored into the shape the interpreter takes.
   *
   * Read from the context rather than fixed, so the fee a builder charged and the fee the rule
   * checks can never come from two different numbers.
   */
  def paramsFrom(ctx: BlockchainContext): BlockchainParameters = {
    val p = ctx.getDataSource.getParameters
    new BlockchainParameters {
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

  private def preHeaderAt(height: Int): PreHeader = CPreHeader(
    version = 0,
    parentId = Colls.emptyColl[Byte],
    timestamp = 0L,
    nBits = 0L,
    height = height,
    minerPk = CGroupElement(CryptoConstants.dlogGroup.generator),
    votes = Colls.emptyColl[Byte])

  /** Whether the rule accepts the input at `index`, and what verifying it cost. */
  def verifyInput(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], index: Int,
                  height: Int, params: BlockchainParameters): (Boolean, Long) = {
    val box = boxes(index)
    val context = new ErgoLikeContext(
      ErgoInterpreter.avlTreeFromDigest(Colls.fromArray(Array.fill[Byte](33)(0))),
      Colls.emptyColl[Header],
      preHeaderAt(height),
      IndexedSeq.empty[ErgoBox],
      boxes,
      tx,
      index,
      tx.inputs(index).spendingProof.extension,
      ValidationRules.currentSettings,
      params.maxBlockCost.toLong,
      0L,
      activatedScriptVersion = (params.blockVersion - 1).toByte)
    ErgoInterpreter(params).verify(Interpreter.emptyEnv, box.ergoTree, context,
      tx.inputs(index).spendingProof.proof, tx.messageToSign).getOrElse(false -> 0L)
  }

  def acceptsInput(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], index: Int,
                   height: Int, params: BlockchainParameters): Boolean =
    verifyInput(tx, boxes, index, height, params)._1

  /** Whether every input of a sweep is accepted, which is what a block would require. */
  def accepts(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], height: Int,
              params: BlockchainParameters): Boolean =
    boxes.indices.forall(i => acceptsInput(tx, boxes, i, height, params))

  /**
   * What a block is charged for this transaction, on the node's own accounting.
   *
   * The interpreter charges only what verifying each input cost; everything else is the flat per
   * transaction, input, output and asset cost `ErgoTransaction` adds before validation starts.
   */
  def blockCost(tx: ErgoLikeTransaction, boxes: IndexedSeq[ErgoBox], height: Int,
                params: BlockchainParameters): Long = {
    val verification = boxes.indices.map(i => verifyInput(tx, boxes, i, height, params)._2).sum
    val inAssets = boxes.map(_.additionalTokens.length).sum
    val outAssets = tx.outputCandidates.map(_.additionalTokens.length).sum
    val assets = org.ergoplatform.wallet.boxes.ErgoBoxAssetExtractor.totalAssetsAccessCost(
      inAssets, boxes.flatMap(_.additionalTokens.toArray.map(_._1)).distinct.size,
      outAssets, tx.outputCandidates.flatMap(_.additionalTokens.toArray.map(_._1)).distinct.size,
      params.tokenAccessCost)
    ErgoInterpreter.interpreterInitCost.toLong +
      boxes.size.toLong * params.inputCost +
      tx.outputCandidates.size.toLong * params.outputCost +
      assets + verification
  }
}
