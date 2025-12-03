package evaluation

import lfsm.NISPTree
import lfsm.fraudproofs.FraudProofContracts
import mutations.BoxLoader
import nisp.NISP
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.Logger
import scorex.utils.Longs
import sigma.AvlTree
import sigma.data.CBigInt
import work.lithos.mutations.{Contract, InputUTXO, Mutator, TxBuilder, TxContext, UTXO}

abstract class FraudProof(contract: Contract, miner: Array[Byte],
                          nispTree: NISPTree, evalInput: InputUTXO, fpControl: InputUTXO) {
  val mutator: Mutator = FraudProof.standardMutator(miner, nispTree, contract)
  val logger: Logger
  /**
   * Attempt to create fraud proof transaction using FraudProof parameters and transaction building information
   * @param ctx Context to perform transaction under
   * @param prover Prover to sign transaction and receive change
   * @param txBuilder pre-mutated tx builder with `EvaluationMutator` applied
   * @param loader BoxLoader to retrieve new inputs utxos
   * @return Some(Sequence of chained transactions to prove fraud) or None
   */
  def attemptFraudProof(ctx: BlockchainContext, prover: ErgoProver, txBuilder: TxBuilder, loader: BoxLoader): Option[Seq[SignedTransaction]]

  protected def createFraudProof(ctx: BlockchainContext, prover: ErgoProver, loader: BoxLoader): SignedTransaction = {
    val fpUtxo = UTXO(contract, Parameters.MinFee)
    val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val inputs = loader.getInputs(Parameters.MinFee * 2)
    val txB = TxBuilder(ctx)
    prover.sign(txB
      .setInputs(inputs:_*)
      .setOutputs(fpUtxo, feeOutput)
      .buildTx(0, prover.getAddress))
  }
}

object FraudProof {

  private def standardPreReqs(nispTree: NISPTree, contract: Contract): Seq[TxContext => Boolean] = {
    Seq(
      {(t: TxContext) => t.inputs(1).contract.hashedPropBytes sameElements contract.hashedPropBytes},
      {
        (t: TxContext) =>
          t.inputs.head.registers.head.getValue.asInstanceOf[AvlTree].digest.toArray sameElements nispTree.tree.digest
      },
      {
        (t: TxContext) =>
          t.inputs(1).value == Parameters.MinFee
      },
      { (t: TxContext) => t.inputs(1).ctxVars.nonEmpty },
      { (t: TxContext) => t.inputs.head.value == t.outputs.head.value },
      { (t: TxContext) => t.inputs.head.registers == t.outputs.head.registers }
    )
  }

  private def standardMutator(miner: Array[Byte], nispTree: NISPTree, contract: Contract): Mutator = new Mutator {
    override val preReqs: Seq[TxContext => Boolean] = FraudProof.standardPreReqs(nispTree, contract)

    // We should assume no fraud proofs have been executed prior to this mutation
    override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
      val evalOutput = tCtx.outputs.head
      val feeProp = Contract(ErgoTreePredef.feeProposition(720))
      val copy = nispTree.tree.copy()
      copy.prover.generateProof() // Reset prover before applying operations to tree
      val lookUp = copy.lookUp(miner)
      val removal = copy.delete(miner)
      val feeOutput = UTXO(feeProp, Parameters.MinFee)
      val score = Longs.fromByteArray(lookUp.response.head.get.slice(0, 8))
      val lastMiners = evalOutput.registers(1).getValue.asInstanceOf[Int]
      val lastScore = evalOutput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
      val lastPeriod = evalOutput.registers(3)
      val lastBlock = evalOutput.registers(4)
      Seq(evalOutput.copy(registers = Seq(
        copy.ergoValue,
        ErgoValue.of(lastMiners - 1),
        ErgoValue.of((BigInt(lastScore) - score).bigInteger),
        lastPeriod,
        lastBlock
      )), feeOutput)
    }
  }

  /**
   * Defines `FraudProof` mapping to `FraudProofContract`, this method generates the `FraudProof`
   * associated with a given contract
   * @param ctx Context to generate contracts
   * @param contract `Contract` to find FraudProof` for
   * @param miner Miner to check for fraud
   * @param nispTree `NISPTree` associated with the given evalInput
   * @param evalInput Evaluation Input to evaluate for fraud
   * @param fpControl FP_Control box to use as data input, used to verify a valid FP contract is used
   */
  def genFraudProof(ctx: BlockchainContext, contract: Contract,
                     miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                     fpControl: InputUTXO): FraudProof = {
    def propByteEquality(otrContract: Contract, fpContract: Contract): Boolean = {
      fpContract.hashedPropBytes sameElements otrContract.hashedPropBytes
    }

    contract match {
      case invalidDiff if propByteEquality(invalidDiff, FraudProofContracts.mkInvalidDiffContract(ctx)) =>
        InvalidDiffProof(contract, miner, nispTree, evalInput, fpControl)
      case invalidSize if propByteEquality(invalidSize, FraudProofContracts.mkInvalidSizeContract(ctx)) =>
        InvalidSizeProof(contract, miner, nispTree, evalInput, fpControl)
      case nonUniqueHeaders if propByteEquality(nonUniqueHeaders, FraudProofContracts.mkNonUniqueHeadersContract(ctx)) =>
        NonUniqueHeadersProof(contract, miner, nispTree, evalInput, fpControl)
      case notInWindow if propByteEquality(notInWindow, FraudProofContracts.mkNotInWindowContract(ctx)) =>
        NotInWindowProof(contract, miner, nispTree, evalInput, fpControl)
      case _ =>
        throw new IllegalArgumentException(s"Cannot find FraudProof for contract ${contract.address(ctx.getNetworkType)}")
    }

  }
}
