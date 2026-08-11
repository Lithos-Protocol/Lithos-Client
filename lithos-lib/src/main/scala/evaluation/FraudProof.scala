package evaluation

import lfsm.contracts.FraudProofContracts
import lfsm.states.NISPTree
import mutations.{BoxLoader, NodeWallet}
import nisp.NISP
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoProver, ErgoType, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.Logger
import scorex.utils.Longs
import sigma.{AvlTree, Coll, Colls}
import sigma.data.CBigInt
import sigma.exceptions.InterpreterException
import work.lithos.mutations.{Contract, InputUTXO, Mutator, TxBuilder, TxContext, UTXO}

import scala.util.Try

abstract class FraudProof(contract: Contract, miner: Array[Byte],
                          nispTree: NISPTree, evalInput: InputUTXO, fpControl: InputUTXO) {
  val mutator: Mutator = FraudProof.standardMutator(miner, nispTree, contract)
  val logger: Logger

  /**
   * Context variables this proof needs beyond the three every proof carries, appended after them.
   *
   * A hook rather than an overridden `attemptFraudProof`, because the one proof that needed an extra
   * variable used to copy the whole method to add a line — leaving the fee handling written twice,
   * where a change to one copy makes that proof pay its fee twice or not at all.
   *
   * Read this before adding one. The context extension is part of `messageToSign` and its serializer
   * walks the map in ITERATION order, which the signing path (a Scala Map, insertion-ordered at four
   * entries or fewer) and the sending path (a `java.util.HashMap` keyed by the decimal id) only
   * agree on by luck. They agree for 0–3 because those decimal strings land in HashMap buckets in
   * that same order, and they agree for five or more entries because neither side can honour
   * insertion order any more. A single two-digit id in between — as the emission spends have at 64
   * and 65 — signs one byte string and asks the node to verify another, and anything reducing to a
   * constant true still passes, so it looks fine until a real signature is on the transaction.
   */
  protected def extraCtxVars: Seq[ContextVar] = Seq.empty
  /**
   * Attempt to create fraud proof transaction using FraudProof parameters and transaction building information
   * @param ctx Context to perform transaction under
   * @param prover Prover to sign transaction and receive change
   * @param txBuilder pre-mutated tx builder with `EvaluationMutator` applied
   * @param initInputs Wallet inputs for the tx; a virtual UTXO is used if none are given. The HEAD input
   *                   carries the proof's context variables, so there is always at least one.
   * @param additionalOutputs Appended after the mutated outputs, where a caller supplying its own fee
   *                          output puts it.
   * @param includeFee Emit the standard 0.001 ERG fee output from the mutator. Off when the caller
   *                   supplies one in `additionalOutputs`, so the tx does not pay twice.
   * @return Some(signed fraud proof), or None when the proof reduced to false — the ordinary
   *         "this miner is honest" answer, not an error
   */
  def attemptFraudProof(ctx: BlockchainContext, prover: NodeWallet, txBuilder: TxBuilder, initInputs: Option[Seq[InputUTXO]],
                        additionalOutputs: Option[Seq[UTXO]] = None, includeFee: Boolean = true): Option[SignedTransaction] = {

    val fpAttempt = Try{
      val inputsToUse = initInputs.getOrElse(Seq(UTXO(prover.contract, UTXO.MIN_FEE).toDummyInput(ctx)))
      val fpInput = inputsToUse.head
      val mutateEval = evalInput
        .withCtxVar(ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(contract.valueBytes), scalaByteType)))
        .withMutator(new EvaluationMutator(evalInput.contract))
      // `mutator` is the fee-emitting form, so a fee-less caller rebuilds it. Fold the flag into
      // `mutator` if this stops being the only variation.
      val mutateFP   = {
        if (includeFee) fpInput.withMutator(mutator)
        else fpInput.withMutator(FraudProof.standardMutator(miner, nispTree, contract, includeFee))
      }
      val copy = nispTree.dictionary.copy()
      val lookUp = copy.lookUp(miner)
      val delete = copy.delete(miner)
      val fpWithContext = mutateFP.setCtxVars(
        (Seq(
          ContextVar.of(0.toByte, ErgoValue.of(miner)),
          ContextVar.of(1.toByte, lookUp.proof.ergoValue),
          ContextVar.of(2.toByte, delete.proof.ergoValue)
        ) ++ extraCtxVars): _*
      )
      val totalInputs = Seq(mutateEval, fpWithContext) ++ inputsToUse.drop(1)
      val uTx = txBuilder
        .setInputs(totalInputs:_*)
        .setDataInputs(fpControl)
        .mutateOutputs

      if(additionalOutputs.isDefined)
        uTx.addOutputs(additionalOutputs.get)

      val sTx = prover.sign(uTx.buildTx(0, prover.p2pk))
      sTx
    }
    if(fpAttempt.isFailure){


      fpAttempt.failed.get match {
        case ie: InterpreterException =>
          if(!ie.getMessage.contains("Script reduced to false")) {
            logger.error(s"Got critical interpreter exception while evaluating miner ${Hex.toHexString(miner)}", ie)
            throw ie
          }else{
            //logger.info("Proof reduced to false")
          }
        case e =>
          throw e

      }

    }else{
      logger.info(s"Found fraud for miner ${Hex.toHexString(miner)}")
    }
    fpAttempt.toOption
  }

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
      {
        (t: TxContext) =>
          t.inputs.head.ctxVars.head.getValue.getValue.asInstanceOf[Coll[_]].toArray sameElements contract.valueBytes
      },
      {
        (t: TxContext) =>
          t.inputs.head.registers.head.getValue.asInstanceOf[AvlTree].digest.toArray sameElements nispTree.dictionary.digest
      },
      { (t: TxContext) => t.inputs(1).ctxVars.nonEmpty },
      { (t: TxContext) => t.inputs.head.value == t.outputs.head.value },
      { (t: TxContext) => t.inputs.head.registers == t.outputs.head.registers }
    )
  }

  /**
   * Removes `miner` from the NISP dictionary and subtracts their score from the evaluation box.
   * `includeFee` decides whether the fee output is emitted here or supplied by the caller; the
   * mutation is otherwise identical, and every FraudProof subclass uses this one.
   */
  def standardMutator(miner: Array[Byte], nispTree: NISPTree, contract: Contract, includeFee: Boolean = true): Mutator = new Mutator {
    override val preReqs: Seq[TxContext => Boolean] = FraudProof.standardPreReqs(nispTree, contract)

    // We should assume no fraud proofs have been executed prior to this mutation
    override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
      val evalOutput = tCtx.outputs.head

      val copy = nispTree.dictionary.copy()
      val lookUp = copy.lookUp(miner)
      val removal = copy.delete(miner)

      val score = Longs.fromByteArray(lookUp.response.head.get.slice(0, 8))
      val lastMiners = evalOutput.registers(1).getValue.asInstanceOf[Int]
      val lastScore = evalOutput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
      val lastPeriod = evalOutput.registers(3)
      val lastBlock = evalOutput.registers(4)
      val nextEval = evalOutput.copy(registers = Seq(
        copy.ergoValue,
        ErgoValue.of(lastMiners - 1),
        ErgoValue.of((BigInt(lastScore) - score).bigInteger),
        lastPeriod,
        lastBlock
      ))
      if(includeFee){
        val feeProp = Contract(ErgoTreePredef.feeProposition(720))
        val feeOutput = UTXO(feeProp, Parameters.MinFee)
        Seq(nextEval, feeOutput)
      }else{
        Seq(nextEval)
      }

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
      case invalidFormat if propByteEquality(invalidFormat, FraudProofContracts.mkInvalidFormatContract(ctx)) =>
        InvalidFormatProof(contract, miner, nispTree, evalInput, fpControl)
      case nonUniqueHeaders if propByteEquality(nonUniqueHeaders, FraudProofContracts.mkNonUniqueHeadersContract(ctx)) =>
        NonUniqueHeadersProof(contract, miner, nispTree, evalInput, fpControl)
      case notInWindow if propByteEquality(notInWindow, FraudProofContracts.mkNotInWindowContract(ctx)) =>
        NotInWindowProof(contract, miner, nispTree, evalInput, fpControl)
      case incorrectN if propByteEquality(incorrectN, FraudProofContracts.mkIncorrectNContract(ctx)) =>
        IncorrectNProof(contract, miner, nispTree, evalInput, fpControl)
      case malformedGE if propByteEquality(malformedGE, FraudProofContracts.mkMalformedGEContract(ctx)) =>
        MalformedGEProof(contract, miner, nispTree, evalInput, fpControl)
      case transactionNotIncluded if propByteEquality(transactionNotIncluded, FraudProofContracts.mkTransactionNotIncludedContract(ctx)) =>
        TransactionNotIncludedProof(contract, miner, nispTree, evalInput, fpControl)
      case _ =>
        throw new IllegalArgumentException(s"Cannot find FraudProof for contract ${contract.address(ctx.getNetworkType)}")
    }

  }
}
