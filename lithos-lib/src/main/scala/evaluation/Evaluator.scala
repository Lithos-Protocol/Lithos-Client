package evaluation

import lfsm.contracts.FraudProofContracts
import lfsm.states.NISPTree
import mutations.{BoxLoader, NodeWallet}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder}

case class Evaluator(ctx: BlockchainContext, prover: NodeWallet, evalInput: InputUTXO, nispTree: NISPTree,
                     miners: Seq[Array[Byte]], fpControl: InputUTXO, loader: BoxLoader, fpContracts: Seq[Contract]) {
  private val logger: Logger = LoggerFactory.getLogger("Evaluator")

  def evaluate: Seq[SignedTransaction] = {
    miners.foreach{
      m =>

        logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)}")

        var txs = Option.empty[Seq[SignedTransaction]]
        var idx = 0
        while(txs.isEmpty && idx < fpContracts.size){
          val fraudProof = FraudProof.genFraudProof(ctx, fpContracts(idx), m, nispTree, evalInput, fpControl)
          txs = fraudProof.attemptFraudProof(ctx, prover, TxBuilder(ctx), loader)
          idx = idx + 1
        }
        txs match {
          case Some(txs) =>
            logger.info(s"Got FP transaction for miner ${Hex.toHexString(m)}")

            return txs
          case None =>
            logger.info(s"Could not find fraud for miner ${Hex.toHexString(m)}")

        }
    }
    logger.info("Got no fraudulent miners")
    Seq.empty[SignedTransaction]
  }


}
