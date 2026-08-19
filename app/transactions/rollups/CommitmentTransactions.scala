package transactions.rollups

import configs.NodeContext
import lfsm.LFSMHelpers
import mutations.NodeWallet
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi.{scalaIntType, scalaLongType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import sigma.{Coll, Colls}
import state.DataBoxRetrievalException
import transactions.wallet.{WalletReservation, WalletSelector}
import utils.{Globals, Helpers}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * This miner's difficulty commitment: reading the one in force, and moving it.
 *
 * The data box carries a list of `(height, score)` pairs. Entry 0 is the newest and takes effect
 * `NISP_WINDOW` blocks after the height it names; until then entry 1 is what everyone else will
 * judge this miner's NISPs against. Reading the wrong one produces submissions that look fraudulent,
 * which is why every reader here distinguishes "no commitment yet" from "could not read one" rather
 * than defaulting silently.
 *
 *
 *  1. `NodeContext` and the data-box token arrive by constructor, so this can be built offline.
 *  2. [[commitScore]] owns the whole wallet-reservation lifecycle. It used to be a callback the
 *     caller passed in, which left the caller holding an acknowledged submission permit it could
 *     drop on an exception — and a Submitting lease has no TTL, so those boxes were unspendable
 *     until the process restarted.
 *  3. The reads return `Try` with a named exception rather than a logged default where the caller
 *     cannot tell the difference.
 */
class CommitmentTransactions(nodeContext: NodeContext, dataBoxes: DataBoxSource) {

  private val logger: Logger = LoggerFactory.getLogger("CommitmentTransactions")

  private val client: ErgoClient = nodeContext.getClient

  /**
   * Height offset a new commitment is declared at, so it lands far enough ahead that everyone
   * currently mining against the old one has finished before it takes effect.
   */
  private final val DataBoxBuffer: Int = (LFSMHelpers.NISP_WINDOW + 50).toInt

  /** The data box this miner's commitments live in. Overridable so a test can supply one. */
  protected def dataBox(ctx: BlockchainContext): Try[InputUTXO] =
    dataBoxes.getDataBoxToken match {
      case None => Failure(new DataBoxRetrievalException("Could not find a stored data box"))
      case Some(nft) => LFSMHelpers.getLocalDataBox(ctx, nft, Helpers.dataBoxContract(ctx))
    }

  private def commitments(box: InputUTXO): Array[(Int, Long)] =
    box.registers.head.getValue.asInstanceOf[Coll[(Int, Long)]].toArray

  /**
   * The commitment in force at `height`.
   *
   * Entry 0 once it has aged past the window, entry 1 until then. A single entry that has not aged
   * yet is not "use it anyway" — nothing has ever been in force, so a NISP submitted now would be
   * judged against a score nobody agreed to.
   */
  private def inForceAt(commits: Array[(Int, Long)], height: Int): Try[Long] =
    if (commits.isEmpty)
      Failure(new DataBoxRetrievalException("The data box carries no commitments"))
    else if (height - commits.head._1 >= LFSMHelpers.NISP_WINDOW) Success(commits.head._2)
    else if (commits.length == 1)
      Failure(new IllegalStateException(
        s"Cannot submit NISPs until commit ${commits.head} is in effect"))
    else Success(commits(1)._2)

  // ══════════════════════════════════════════════════════════════════════════
  //  READS
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * The score a NISP starting at `height` will be judged against.
   *
   * Fails rather than falling back. A NISP built against the wrong score is not merely rejected —
   * it is the shape a fraud proof is written for.
   */
  def commitmentForNISP(height: Int): Try[Long] =
    Try(client.execute(ctx => dataBox(ctx).flatMap(box => inForceAt(commitments(box), height)))).flatten

  /**
   * The tau this miner should hand its rigs, given the commitment in force.
   *
   * Falls back to the configured tau with a warning, because the alternative is refusing to mine.
   */
  def committedTau(tau: BigInt): Try[BigInt] = Try {
    client.execute { ctx =>
      dataBox(ctx).flatMap(box => inForceAt(commitments(box), ctx.getHeight)) match {
        case Success(score) =>
          val next = LFSMHelpers.convertTauOrScore(score)
          logger.info(s"Using committed score $score, tau $next")
          next
        case Failure(ex) =>
          logger.warn(s"No usable difficulty commitment, mining on the configured tau $tau: " +
            s"${ex.getMessage}")
          logger.warn("NISPs submitted or mined in this manner may be considered fraudulent")
          tau
      }
    }
  }

  /**
   * The score in force, for a caller that already holds a context.
   */
  def committedScore(ctx: BlockchainContext, diff: String, reason: String): Try[Long] = Try {
    val configured = LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum(diff).get)).toLong
    dataBox(ctx).flatMap(box => inForceAt(commitments(box), ctx.getHeight)) match {
      case Success(score) =>
        logger.info(s"Using committed score $score for $reason")
        score
      case Failure(ex) =>
        logger.warn(s"No usable difficulty commitment, forcing configured diff $diff " +
          s"and score $configured for $reason: ${ex.getMessage}")
        logger.warn("NISPs submitted or mined in this manner may be considered fraudulent")
        configured
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  THE WRITE
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Move this miner's commitment to the configured difficulty, if it has to move.
   *
   * Nothing happens unless the current commitment has taken effect AND the configured score differs
   * from it. Both conditions matter: replacing an entry that is not yet in force would leave the
   * window with no agreed score in it at all.
   *
   * @return the transaction id, or None when no change was needed
   */
  def commitScore(diff: String, walletSelector: WalletSelector): Try[Option[String]] = Try {
    val score = LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum(diff).get)).toLong
    client.execute { ctx =>
      dataBox(ctx) match {
        case Failure(ex) =>
          logger.info(s"Not auto-committing: ${ex.getMessage}")
          None
        case Success(input) =>
          val commits = commitments(input)
          val current = commits.head
          if (ctx.getHeight - current._1 < LFSMHelpers.NISP_WINDOW) {
            logger.info(s"Not changing commits until $current takes effect (height ${ctx.getHeight})")
            None
          } else if (current._2 == score) {
            logger.info("No commitment change needed")
            None
          } else Some(sendCommitment(ctx, input, current, score, walletSelector))
      }
    }
  }

  /**
   * Build, fund, sign and send one commitment change.
   *
   * The reservation lifecycle is closed here rather than by the caller. `beginSubmission` is an
   * obligation with no expiry — the manager will not age a Submitting lease out, because releasing
   * one while a node call may still be in flight is a double spend — so every path out of this
   * method has to end in `commit` or `uncertain`. An escape marks the lease uncertain rather than
   * releasing it, since a throw after the request left this process may still have been accepted.
   */
  private def sendCommitment(ctx: BlockchainContext,
                             input: InputUTXO,
                             current: (Int, Long),
                             score: Long,
                             walletSelector: WalletSelector): String = {
    val prover: NodeWallet = nodeContext.getNodeWallet
    val newCommit = ctx.getHeight + DataBoxBuffer -> score
    logger.info(s"Committing $newCommit, replacing $current")

    val reservation = walletSelector.reserve(Parameters.MinFee * 2)
    val signed =
      try signCommitment(ctx, input, prover, newCommit, reservation.inputs)
      catch {
        // Nothing has left this process, so the exact selection is ours to hand straight back.
        case NonFatal(ex) => reservation.release(); throw ex
      }

    // Converted BEFORE the permit is taken, which is what the method below claims and what the
    // equivalent site in LithosDexApiImpl.send does. Reading it after the broadcast would put a
    // conversion between acceptance and commit — harmless while it swallows its own failures, and a
    // real defect the moment anyone removes that.
    val change = signableOutputs(signed, prover)

    reservation.beginSubmission()
    try {
      val txId = ctx.sendTransaction(signed).replace("\"", "")
      reservation.commit(change)
      logger.info(s"Sent transaction $txId to commit $newCommit")
      txId
    } catch {
      case NonFatal(ex) =>
        reservation.uncertain()
        throw ex
    }
  }

  private def signCommitment(ctx: BlockchainContext,
                             input: InputUTXO,
                             prover: NodeWallet,
                             newCommit: (Int, Long),
                             funding: Seq[InputUTXO]): SignedTransaction = {
    val commits = input.registers.head.getValue.asInstanceOf[Coll[(Int, Long)]]
    // The new entry replaces slot 0 and the one it displaces moves to slot 1, so the score that is
    // still in force stays readable for the whole window rather than disappearing the moment a
    // change is declared.
    val nextCommits = commits.slice(0, 1).updated(0, newCommit)
      .append(Colls.fromArray(Array(commits(0))))

    val nextDataBox = input.toUTXO.copy(
      registers = input.registers.updated(0,
        ErgoValue.of(nextCommits, ErgoType.pairType(scalaIntType, scalaLongType))))

    val inputWithCtx = input
      .withCtxVar(0.toByte, ErgoValue.of(0.toByte))
      .withCtxVar(ContextVar.of(1.toByte, prover.contract.sigmaBoolean.get))

    val fee = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val uTx = TxBuilder(ctx)
      .setInputs((inputWithCtx +: funding): _*)
      .setOutputs(nextDataBox, fee)
      .buildTx(0, prover.p2pk)

    prover.sign(uTx)
  }

  /**
   * The transaction's own outputs this wallet can spend, converted BEFORE the node call so a local
   * conversion failure cannot land after acceptance. Empty rather than throwing: returning change is
   * an optimisation over waiting for the next refresh, not a reason to fail a landed transaction.
   */
  private def signableOutputs(signed: SignedTransaction, prover: NodeWallet): Seq[InputUTXO] =
    Try {
      import scala.collection.JavaConverters._
      signed.getOutputsToSpend.asScala.toSeq.map(InputUTXO(_))
        .filter(box => prover.signableTrees.contains(box.contract.ergoTreeHex))
    }.getOrElse(Seq.empty[InputUTXO])
}

/**
 * Where the data box's own token id comes from.
 *
 * A one-method seam over `MDDatabase`, which is a disk-backed singleton reached through `Globals`.
 * Without it nothing in this file can be constructed in a test.
 */
trait DataBoxSource {
  def getDataBoxToken: Option[ErgoId]
}

object DataBoxSource {
  /** The production source: the miner-dictionary database this client keeps on disk. */
  val Stored: DataBoxSource = new DataBoxSource {
    override def getDataBoxToken: Option[ErgoId] = Globals.mdDB.getDataBoxToken
  }
}
