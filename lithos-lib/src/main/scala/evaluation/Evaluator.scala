package evaluation

import lfsm.contracts.FraudProofContracts
import lfsm.states.Rollup
import mutations.{BoxLoader, NodeWallet}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * @param commitment Miner Dictionary state for `FP_NonMatchingCommitment`. Optional because the rest
 *                   of the set needs nothing outside the rollup, and a client whose dictionary is
 *                   faulted should still be able to run the other seven proofs.
 */
case class Evaluator(ctx: BlockchainContext, prover: NodeWallet, evalInput: InputUTXO, nispTree: Rollup,
                     miners: Seq[Array[Byte]], fpControl: InputUTXO, loader: BoxLoader, fpContracts: Seq[Contract],
                     commitment: Option[CommitmentSource] = None) {
  private val logger: Logger = LoggerFactory.getLogger("Evaluator")

  /**
   * One proof against one miner, with a throw costing only that attempt.
   */
  private def attempt(miner: Array[Byte],
                      fpContract: Contract,
                      initInputs: Option[Seq[InputUTXO]]): Option[SignedTransaction] =
    Try {
      FraudProof.genFraudProof(ctx, fpContract, miner, nispTree, evalInput, fpControl, commitment)
        .attemptFraudProof(ctx, prover, TxBuilder(ctx), initInputs)
    } match {
      case Success(result) => result
      case Failure(ex) =>
        logger.warn(s"Fraud proof ${fpContract.hashedPropBytesHex.take(16)} failed to evaluate for " +
          s"miner ${Hex.toHexString(miner)}, skipping it and continuing with the rest: ${ex.getMessage}")
        None
    }

  def evaluate: Seq[SignedTransaction] = {
    miners.foreach {
      m =>

        logger.info(s"Starting ${fpContracts.size} evaluations for miner ${Hex.toHexString(m)} in rollup ${nispTree.blockId}")

        var txs = Option.empty[SignedTransaction]
        var idx = 0
        while (txs.isEmpty && idx < fpContracts.size) {
          txs = attempt(m, fpContracts(idx), Some(loader.getInputs(UTXO.MIN_FEE)))
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
                  case Some(_) => z
                  case None    => attempt(m, fpContract, None).map(_ -> fpContract.hashedPropBytesHex)
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
                case Some(_) => z
                case None    => attempt(m, fpContract, None).map(_ -> fpContract.hashedPropBytesHex)
              }
          }

        }
    }
  }

  def evaluateFor(miner: Array[Byte], fpContractHashHex: String, initInputs: Seq[InputUTXO],
                  additionalOutputs: Seq[UTXO]): Option[SignedTransaction] = {
    val fpContract = fpContracts.find(_.hashedPropBytesHex == fpContractHashHex).get
    val fraudProof = FraudProof.genFraudProof(ctx, fpContract, miner, nispTree, evalInput, fpControl, commitment)
    fraudProof
      .attemptFraudProof(ctx, prover, TxBuilder(ctx), Some(initInputs), Some(additionalOutputs), includeFee = false)
  }

}
