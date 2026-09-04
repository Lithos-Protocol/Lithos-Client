package evaluation

import lfsm.contracts.FraudProofContracts.FraudProofSet
import lfsm.states.Rollup
import mutations.{BoxLoader, NodeWallet}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import evaluation.Evaluator.{Clean, Failed, Fraud, ProofOutcome}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * @param fpSet      The compiled proof set. Maps a held script back to the builder that knows its
 *                   context variables, and supplies the run order unless one is given.
 * @param fpContracts Which proofs to try, in order. Empty runs the whole set; a subset is a test
 *                   seam, used by `EvaluatorOutcomeSpec` to drive one proof at a time.
 * @param commitment Miner Dictionary state for `FP_NonMatchingCommitment`. Optional because the rest
 *                   of the set needs nothing outside the rollup, and a client whose dictionary is
 *                   faulted should still be able to run the other eight proofs.
 */
case class Evaluator(ctx: BlockchainContext, prover: NodeWallet, evalInput: InputUTXO, nispTree: Rollup,
                     miners: Seq[Array[Byte]], fpControl: InputUTXO, loader: BoxLoader,
                     fpSet: FraudProofSet, fpContracts: Seq[Contract] = Seq.empty,
                     commitment: Option[CommitmentSource] = None) {
  private val logger: Logger = LoggerFactory.getLogger("Evaluator")

  /** The proofs this evaluator runs, in order. */
  private val runOrder: Seq[Contract] = if (fpContracts.isEmpty) fpSet.ordered else fpContracts

  /**
   * One proof against one miner, with a throw costing only that attempt.
   */
  private def attempt(miner: Array[Byte],
                      fpContract: Contract,
                      initInputs: Option[Seq[InputUTXO]]): ProofOutcome =
    Try {
      FraudProof.genFraudProof(fpSet, ctx, fpContract, miner, nispTree, evalInput, fpControl, commitment)
        .attemptFraudProof(ctx, prover, TxBuilder(ctx), initInputs)
    } match {
      case Success(Some(tx)) => Fraud(tx)
      case Success(None) => Clean
      case Failure(ex) =>
        logger.warn(s"Fraud proof ${fpContract.hashedPropBytesHex.take(16)} failed to evaluate for " +
          s"miner ${Hex.toHexString(miner)}, continuing with the rest: ${ex.getMessage}")
        Failed(ex)
    }

  /**
   * Every proof against one miner, stopping at the first that establishes fraud.
   *
   * A proof that could not run does not stop the others, since a later one may still find the fraud.
   */
  private def verdict(miner: Array[Byte])
                     (initInputs: => Option[Seq[InputUTXO]]): Try[Option[(SignedTransaction, String)]] = {
    logger.info(s"Starting ${runOrder.size} evaluations for miner ${Hex.toHexString(miner)}")
    var failures = Vector.empty[Throwable]
    var found = Option.empty[(SignedTransaction, String)]
    var idx = 0
    while (found.isEmpty && idx < runOrder.size) {
      val fpContract = runOrder(idx)
      attempt(miner, fpContract, initInputs) match {
        case Fraud(tx) => found = Some(tx -> fpContract.hashedPropBytesHex)
        case Failed(ex) => failures :+= ex
        case Clean => ()
      }
      idx += 1
    }
    if (found.isDefined) Success(found)
    else if (failures.isEmpty) Success(None)
    else Failure(Evaluator.incompleteEvaluation(miner, runOrder.size, failures))
  }

  def evaluate: Seq[SignedTransaction] = {
    miners.foreach { m =>
      verdict(m)(Some(loader.getInputs(UTXO.MIN_FEE))) match {
        case Success(Some((tx, _))) =>
          logger.info(s"Got FP transaction for miner ${Hex.toHexString(m)}")
          return Seq(tx)
        case Success(None) =>
          logger.info(s"Could not find fraud for miner ${Hex.toHexString(m)}")
        case Failure(ex) =>
          logger.error(s"Could not finish evaluating miner ${Hex.toHexString(m)}: ${ex.getMessage}")
      }
    }
    logger.info("Got no fraudulent miners")
    Seq.empty[SignedTransaction]
  }

  def evaluateAsync(dispatcher: ExecutionContext): Future[Seq[(Array[Byte], Try[Option[(SignedTransaction, String)]])]] =
    Future(evaluateSync)(dispatcher)

  def evaluateSync: Seq[(Array[Byte], Try[Option[(SignedTransaction, String)]])] =
    miners.map(m => m -> verdict(m)(None))

  def evaluateFor(miner: Array[Byte], fpContractHashHex: String, initInputs: Seq[InputUTXO],
                  additionalOutputs: Seq[UTXO]): Option[SignedTransaction] = {
    val fpContract = fpSet.ordered.find(_.hashedPropBytesHex == fpContractHashHex).get
    val fraudProof = FraudProof.genFraudProof(fpSet, ctx, fpContract, miner, nispTree, evalInput, fpControl, commitment)
    fraudProof
      .attemptFraudProof(ctx, prover, TxBuilder(ctx), Some(initInputs), Some(additionalOutputs), includeFee = false)
  }

}

object Evaluator {

  /**
   * How a proof answered. Only `Clean` and `Fraud` are verdicts; `Failed` is the absence of one.
   */
  sealed trait ProofOutcome
  case object Clean extends ProofOutcome
  final case class Fraud(tx: SignedTransaction) extends ProofOutcome
  final case class Failed(cause: Throwable) extends ProofOutcome

  /** Raised when no proof found fraud but not all of them managed to answer. */
  class ProofsIncompleteException(msg: String) extends RuntimeException(msg)

  /**
   * The failure to report when a miner's evaluation did not finish. Names how many proofs could not
   * answer and carries the rest as suppressed causes, so one log line holds every reason.
   *
   * One type for every cause. The caller counts these against a retry bound and does not read the
   * exception, so nothing is gained by preferring one of them.
   */
  private[evaluation] def incompleteEvaluation(miner: Array[Byte],
                                               proofs: Int,
                                               failures: Seq[Throwable]): Throwable = {
    val chosen = new ProofsIncompleteException(
      s"${failures.size} of $proofs fraud proofs could not run for miner " +
        s"${Hex.toHexString(miner)}, so no fraud verdict was reached: " +
        failures.map(f => Option(f.getMessage).getOrElse(f.getClass.getSimpleName)).mkString("; "))
    failures.foreach(chosen.addSuppressed)
    chosen
  }
}
