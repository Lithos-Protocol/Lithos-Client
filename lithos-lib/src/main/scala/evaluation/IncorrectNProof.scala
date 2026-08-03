package evaluation

import lfsm.states.NISPTree
import mutations.{BoxLoader, NodeWallet}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoProver, ErgoType, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import sigma.Colls
import sigma.exceptions.InterpreterException
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.util.Try

case class IncorrectNProof(contract: Contract, miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                           fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("IncorrectNProof")

  override def attemptFraudProof(ctx: BlockchainContext, prover: NodeWallet, txBuilder: TxBuilder, initInputs: Option[Seq[InputUTXO]]): Option[SignedTransaction] = {
    val fpAttempt = Try{
      val inputsToUse = initInputs.getOrElse(Seq(UTXO(prover.contract, UTXO.MIN_FEE).toDummyInput(ctx)))
      val fpInput = inputsToUse.head
      val mutateEval = evalInput
        .withCtxVar(ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(contract.valueBytes), scalaByteType)))
        .withMutator(new EvaluationMutator(evalInput.contract))
      val mutateFP   = fpInput.withMutator(mutator)

      val copy = nispTree.dictionary.copy()
      copy.prover.generateProof()
      val lookUp = copy.lookUp(miner)
      val delete = copy.delete(miner)
      val fpWithContext = mutateFP.setCtxVars(
        ContextVar.of(0.toByte, ErgoValue.of(miner)),
        ContextVar.of(1.toByte, lookUp.proof.ergoValue),
        ContextVar.of(2.toByte, delete.proof.ergoValue),
        ContextVar.of(3.toByte, NTable.ergoValue)
      )
      val totalInputs = Seq(mutateEval, fpWithContext) ++ inputsToUse.drop(1)
      val sTx = prover.sign(txBuilder
        .setInputs(totalInputs:_*)
        .setDataInputs(fpControl)
        .mutateOutputs
        .buildTx(0, prover.p2pk))
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
}


