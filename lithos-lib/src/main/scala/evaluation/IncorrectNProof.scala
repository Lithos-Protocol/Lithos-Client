package evaluation

import lfsm.NISPTree
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoProver, ErgoType, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import sigma.Colls
import sigma.exceptions.InterpreterException
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder}

import scala.util.Try

case class IncorrectNProof(contract: Contract, miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                           fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("IncorrectNProof")

  override def attemptFraudProof(ctx: BlockchainContext, prover: ErgoProver, txBuilder: TxBuilder, loader: BoxLoader): Option[Seq[SignedTransaction]] = {
    println(s"Using contract ${contract.address(ctx).toString}")
    val fpAttempt = Try{

      val fpInput = loader.getInputs(Parameters.MinFee).head
      val mutateEval = evalInput
        .withCtxVar(ContextVar.of(0.toByte, ErgoValue.ofColl(Colls.fromArray(contract.valueBytes), ErgoType.byteType())))
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

      val sTx = prover.sign(txBuilder
        .setInputs(mutateEval,fpWithContext)
        .setDataInputs(fpControl)
        .mutateOutputs
        .buildTx(0, prover.getAddress))
      Seq(sTx)
    }
    if(fpAttempt.isFailure){


      fpAttempt.failed.get match {
        case ie: InterpreterException =>
          if(!ie.getMessage.contains("Script reduced to false")) {
            logger.error(s"Got critical interpreter exception while evaluating miner ${Hex.toHexString(miner)}", ie)
            throw ie
          }else{
            logger.info("Proof reduced to false")
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


