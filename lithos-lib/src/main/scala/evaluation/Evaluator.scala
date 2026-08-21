package evaluation

import lfsm.contracts.FraudProofContracts
import lfsm.states.NISPTree
import mutations.{BoxLoader, NodeWallet}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class Evaluator(ctx: BlockchainContext, prover: NodeWallet, evalInput: InputUTXO, nispTree: NISPTree,
                     miners: Seq[Array[Byte]], fpControl: InputUTXO, loader: BoxLoader, fpContracts: Seq[Contract]) {
  private val logger: Logger = LoggerFactory.getLogger("Evaluator")

  def evaluate: Seq[SignedTransaction] = {
    miners.foreach {
      m =>

        logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)} in rollup ${nispTree.blockId}")

        var txs = Option.empty[SignedTransaction]
        var idx = 0
        while (txs.isEmpty && idx < fpContracts.size) {
          val fraudProof = FraudProof.genFraudProof(ctx, fpContracts(idx), m, nispTree, evalInput, fpControl)
          txs = fraudProof.attemptFraudProof(ctx, prover, TxBuilder(ctx), Some(loader.getInputs(UTXO.MIN_FEE)))
          idx = idx + 1
        }
        txs match {
          case Some(txs) =>
            logger.info(s"Got FP transaction for miner ${Hex.toHexString(m)}")

            return Seq(txs)
          case None =>
            logger.info(s"Could not find fraud for miner ${Hex.toHexString(m)}")

        }
    }
    logger.info("Got no fraudulent miners")
    Seq.empty[SignedTransaction]
  }

  def evaluateAsync(dispatcher: ExecutionContext): Future[Seq[(Array[Byte], Try[Option[(SignedTransaction, String)]])]] = {
    Future {
      miners.map {
        m =>
          m -> Try {
            logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)}")
            fpContracts.foldLeft(Option.empty[(SignedTransaction, String)]) {
              (z, fpContract) =>
                z match {
                  case Some(_) =>
                    z
                  case None =>
                    val fraudProof = FraudProof.genFraudProof(ctx, fpContract, m, nispTree, evalInput, fpControl)
                    fraudProof
                      .attemptFraudProof(ctx, prover, TxBuilder(ctx), None)
                      .map(_ -> fpContract.hashedPropBytesHex)
                }

            }

          }
      }
    }(dispatcher)
  }

  def evaluateSync: Seq[(Array[Byte], Try[Option[(SignedTransaction, String)]])] = {
    miners.map {
      m =>
        m -> Try {
          logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)}")
          fpContracts.foldLeft(Option.empty[(SignedTransaction, String)]) {
            (z, fpContract) =>
              z match {
                case Some(_) =>
                  z
                case None =>
                  val fraudProof = FraudProof.genFraudProof(ctx, fpContract, m, nispTree, evalInput, fpControl)
                  fraudProof
                    .attemptFraudProof(ctx, prover, TxBuilder(ctx), None)
                    .map(_ -> fpContract.hashedPropBytesHex)
              }

          }

        }
    }
  }

  def evaluateFor(miner: Array[Byte], fpContractHashHex: String, initInputs: Seq[InputUTXO],
                  additionalOutputs: Seq[UTXO]): Option[SignedTransaction] = {
    val fpContract = fpContracts.find(_.hashedPropBytesHex == fpContractHashHex).get
    val fraudProof = FraudProof.genFraudProof(ctx, fpContract, miner, nispTree, evalInput, fpControl)
    fraudProof
      .attemptFraudProof(ctx, prover, TxBuilder(ctx), Some(initInputs), Some(additionalOutputs), includeFee = false)
  }

}
