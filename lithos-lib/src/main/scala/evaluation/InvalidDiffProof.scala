package evaluation

import lfsm.NISPTree
import mutations.BoxLoader
import nisp.NISP
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoProver, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Longs
import sigma.AvlTree
import sigma.data.CBigInt
import work.lithos.mutations.{Contract, InputUTXO, Mutator, TxBuilder, TxContext, UTXO}

import scala.util.{Success, Try}

case class InvalidDiffProof(contract: Contract, miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                            fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("InvalidDiffProof")
  /**
   * Attempt to create fraud proof transaction using FraudProof parameters and transaction building information
   * @param ctx Context to perform transaction under
   * @param prover Prover to sign transaction and receive change
   * @param txBuilder pre-mutated tx builder with `EvaluationMutator` applied
   * @param loader BoxLoader to retrieve new inputs utxos
   * @return `Some(SignedTransaction)` if fraud was proven, None otherwise
   */
  override def attemptFraudProof(ctx: BlockchainContext, prover: ErgoProver,
                                 txBuilder: TxBuilder, loader: BoxLoader): Option[Seq[SignedTransaction]] = {
    val fpAttempt = Try{
      val initialTx = createFraudProof(ctx, prover, loader)
      val fpInput = InputUTXO(initialTx.getOutputsToSpend.get(0))
      val mutateEval = evalInput.withMutator(new EvaluationMutator(evalInput.contract))
      val mutateFP   = fpInput.withMutator(mutator)

      val copy = nispTree.tree.copy()
      copy.prover.generateProof()
      val lookUp = copy.lookUp(miner)
      val delete = copy.delete(miner)
      val fpWithContext = mutateFP.setCtxVars(
        ContextVar.of(0.toByte, ErgoValue.of(miner)),
        ContextVar.of(1.toByte, lookUp.proof.ergoValue),
        ContextVar.of(2.toByte, delete.proof.ergoValue)
      )

      val sTx = prover.sign(txBuilder
        .setInputs(mutateEval,fpWithContext)
        .setDataInputs(fpControl)
        .mutateOutputs
        .buildTx(0, prover.getAddress))
      Seq(initialTx, sTx)
    }
    if(fpAttempt.isFailure){
      logger.info(s"Could not find fraud for miner ${Hex.toHexString(miner)}")
      logger.error("Given reason: ", fpAttempt.failed.get)
      println(s"Could not find fraud for miner ${Hex.toHexString(miner)}")
      println("Given reason: ", fpAttempt.failed.get.printStackTrace())
    }else{
      logger.info(s"Found fraud for miner ${Hex.toHexString(miner)}")
    }
    fpAttempt.toOption
  }
}


