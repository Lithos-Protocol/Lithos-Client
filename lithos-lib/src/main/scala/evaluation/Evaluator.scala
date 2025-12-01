package evaluation

import lfsm.NISPTree
import lfsm.fraudproofs.FraudProofContracts
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{InputUTXO, TxBuilder}

case class Evaluator(ctx: BlockchainContext, prover: ErgoProver, evalInput: InputUTXO, nispTree: NISPTree,
                miners: Seq[Array[Byte]], fpControl: InputUTXO, loader: BoxLoader) {
  val logger: Logger = LoggerFactory.getLogger("Evaluator")

  def evaluate: Seq[SignedTransaction] = {
    miners.foreach{
      m =>
        val fpContracts = FraudProofContracts.getFraudProofContracts(ctx)
        logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)}")
        println(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)}")
        var txs = Option.empty[Seq[SignedTransaction]]
        var idx = 0
        while(txs.isEmpty){
          val fraudProof = FraudProof.genFraudProof(ctx, fpContracts(idx), m, nispTree, evalInput, fpControl)
          txs = fraudProof.attemptFraudProof(ctx, prover, TxBuilder(ctx), loader)
          idx = idx + 1
        }
        txs match {
          case Some(txs) =>
            logger.info(s"Got FP transactions for miner ${Hex.toHexString(m)}")
            println(s"Got FP transactions for miner ${Hex.toHexString(m)}")
            return txs
          case None =>
            logger.info(s"Could not find fraud for miner ${Hex.toHexString(m)}")
            println(s"Could not find fraud for miner ${Hex.toHexString(m)}")
        }
    }
    Seq.empty[SignedTransaction]
  }


}
