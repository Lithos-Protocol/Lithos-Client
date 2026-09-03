package contracts.specs.rollup

import lfsm.LFSMHelpers
import lfsm.contracts.{FraudProofContracts, RollupContracts}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * Compiled once and shared, in the order `Evaluator` runs them. The sigma compiler mutates
 * `_sourceContext` on shared AST nodes, so compiling a script twice throws.
 */
object FraudProofSpecBase {
  private var cache: Option[Seq[Contract]] = None

  def compiled(ctx: BlockchainContext): Seq[Contract] = synchronized {
    cache.getOrElse {
      val all = FraudProofContracts.getFraudProofContracts(ctx)
      cache = Some(all)
      all
    }
  }
}

/**
 * The shared shape of a fraud-proof spend: `INPUTS(0)` the evaluation box, `INPUTS(1)` the prover's
 * box carrying the proof's context variables, `OUTPUTS(0)` the slashed successor and `OUTPUTS(1)` the
 * prover's reward. `dataInputs(0)` is FP_CONTROL, which whitelists the script Evaluation runs from
 * its own variable 0.
 *
 * The three outcomes below are the whole point of this file. A fraud proof has exactly two correct
 * answers — it proves fraud, or it reduces to false — and a third behaviour, throwing while it
 * evaluates, which is neither. A throw on data the accused miner controls is a miner who cannot be
 * slashed, so [[findsNoFraud]] insists on the reduction rather than accepting any failure.
 */
trait FraudProofSpecBase extends RollupSpecBase {

  protected def fraudProofs(ctx: BlockchainContext): Seq[Contract] = FraudProofSpecBase.compiled(ctx)

  /** Named off the compiled set, so a change to the try order does not silently retarget a spec. */
  private def set(ctx: BlockchainContext) = FraudProofContracts.fraudProofSet(ctx)

  protected def fpNonMatchingCommitment(ctx: BlockchainContext): Contract = set(ctx).nonMatchingCommitment
  protected def fpInvalidFormat(ctx: BlockchainContext): Contract = set(ctx).invalidFormat
  protected def fpMalformedGE(ctx: BlockchainContext): Contract = set(ctx).malformedGE
  protected def fpNotInWindow(ctx: BlockchainContext): Contract = set(ctx).notInWindow
  protected def fpNonUniqueHeaders(ctx: BlockchainContext): Contract = set(ctx).nonUniqueHeaders
  protected def fpIncorrectN(ctx: BlockchainContext): Contract = set(ctx).incorrectN
  protected def fpInvalidDiff(ctx: BlockchainContext): Contract = set(ctx).invalidDiff
  protected def fpTransactionNotIncluded(ctx: BlockchainContext): Contract = set(ctx).transactionNotIncluded
  protected def fpMalformedGenesis(ctx: BlockchainContext): Contract = set(ctx).malformedGenesis

  // ─── outcomes ─────────────────────────────────────────────────────────────

  /** The sigma interpreter's wording for a contract that evaluated to false rather than blew up. */
  private val ReducedToFalse = "Script reduced to false"

  /** The proof fires: this miner is slashed. */
  protected def provesFraud(prover: ErgoProver, tx: UnsignedTransaction): SignedTransaction =
    Try(prover.sign(tx)) match {
      case Success(signed) => signed
      case Failure(ex) =>
        fail(s"expected the proof to establish fraud, but it did not: ${ex.getMessage}", ex)
    }

  /**
   * The proof declines, and declines *cleanly*.
   *
   * Anything other than a reduction to false is a failure of the proof itself rather than a verdict
   * on the miner, and `Evaluator` cannot tell the two apart except by this same message. A proof that
   * throws on a NISP its accused wrote is a miner nobody can slash.
   */
  protected def findsNoFraud(prover: ErgoProver, tx: => UnsignedTransaction): Unit = {
    val built = Try(tx) match {
      case Success(t) => t
      case Failure(ex) =>
        fail(s"expected a well-formed transaction for the proof to decline, but building it failed: " +
          s"${ex.getMessage}", ex)
    }
    Try(prover.sign(built)) match {
      case Success(_) => fail("expected the proof to decline, but it established fraud")
      case Failure(ex) if Option(ex.getMessage).exists(_.contains(ReducedToFalse)) => ()
      case Failure(ex) =>
        fail(s"expected the proof to reduce to false, but it failed while evaluating: ${ex.getMessage}", ex)
    }
  }

  /**
   * The proof reaches a verdict, either one. Fails only on a throw.
   *
   * This is the property the ordering exists to deliver: whichever way a proof answers is fine, but
   * blowing up on data the accused wrote is not an answer at all.
   */
  protected def evaluatesCleanly(prover: ErgoProver, tx: => UnsignedTransaction): String = {
    val built = Try(tx) match {
      case Success(t) => t
      case Failure(ex) =>
        fail(s"expected a well-formed transaction, but building it failed: ${ex.getMessage}", ex)
    }
    Try(prover.sign(built)) match {
      case Success(_) => "fires"
      case Failure(ex) if Option(ex.getMessage).exists(_.contains(ReducedToFalse)) => "declines"
      case Failure(ex) =>
        fail(s"expected a verdict, but the proof failed while evaluating: ${ex.getMessage}", ex)
    }
  }

  /**
   * The proof neither fires nor declines — it throws. Only ever asserted deliberately, to pin a
   * defect in place while it is being fixed, or to record a failure the proof's own builder caused.
   */
  protected def failsToEvaluate(prover: ErgoProver, tx: => UnsignedTransaction): Throwable = {
    val built = Try(tx) match {
      case Success(t) => t
      case Failure(ex) => fail(s"expected a well-formed transaction, but building it failed: ${ex.getMessage}", ex)
    }
    Try(prover.sign(built)) match {
      case Success(_) => fail("expected the proof to fail while evaluating, but it established fraud")
      case Failure(ex) if Option(ex.getMessage).exists(_.contains(ReducedToFalse)) =>
        fail("expected the proof to fail while evaluating, but it reduced to false cleanly")
      case Failure(ex) => ex
    }
  }

  // ─── the transaction ──────────────────────────────────────────────────────

  private def fpControl(ctx: BlockchainContext): Contract =
    RollupContracts.mkFPControlTestnetContract(ctx, miner(ctx).getAddress.getPublicKey)

  /** FP_CONTROL, whitelisting the value-hashes of the scripts Evaluation may run. */
  protected def fpDataInput(ctx: BlockchainContext,
                            whitelisted: Seq[Contract],
                            tokenId: ErgoId = fpTokenId): InputUTXO =
    UTXO(fpControl(ctx), Parameters.MinFee,
      tokens = Seq(Token(tokenId, 1L)),
      registers = Seq(ErgoValue.of(
        Colls.fromArray(whitelisted.map(c => Colls.fromArray(c.hashedValueBytes)).toArray),
        ErgoType.collType(scalaByteType)))
    ).toInput(ctx, ErgoId.create(dummyTxId), 3.toShort)

  /** A second miner, so the tree is never emptied by the removal under test. */
  protected def bystanderScore: Long = 4000L

  protected case class Fraud(ctx: BlockchainContext,
                             evalIn: InputUTXO,
                             fpIn: InputUTXO,
                             out: UTXO,
                             reward: UTXO,
                             data: Seq[InputUTXO],
                             prover: ErgoProver,
                             minerHash: Array[Byte],
                             script: Contract,
                             score: Long,
                             bond: Long,
                             periodStart: Long,
                             blockHeight: Long)

  /**
   * One accused miner holding `nisp`, one bystander, and the successor that removes the first.
   *
   * `blockHeight` is the rollup's own block — what the proofs read as `currentBlock` — so a share's
   * height has to sit inside `[blockHeight - NISP_WINDOW, blockHeight]` to be in window.
   */
  protected def fraud(ctx: BlockchainContext,
                      script: Contract,
                      nisp: Array[Byte],
                      score: Long,
                      blockHeight: Long = -1L,
                      extraVars: Seq[ContextVar] = Seq.empty,
                      dataInputs: Seq[InputUTXO] = null,
                      minerHash: Array[Byte] = null,
                      tokens: Seq[Token] = Seq.empty[Token]): Fraud = {
    val prover = miner(ctx)
    val accused = if (minerHash == null) contractOf(prover).hashedPropBytes else minerHash
    val bystander = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
    val periodStart = ctx.getHeight.toLong - 100L
    val block = if (blockHeight < 0L) periodStart - LFSMHelpers.HOLDING_PERIOD else blockHeight

    val totalScore = score + bystanderScore
    val bondHeld = bondFor(score) + bondFor(bystanderScore)
    val bond = bondFor(score)

    val tree = treeWith(Seq(
      accused -> nisp,
      bystander -> nispBytes(bystanderScore, realisticNispSize)))
    val inTree = tree.ergoValue
    // lookUp does not move the digest, so both proofs are against the same input tree.
    val lookup = tree.lookUp(accused)
    val removal = tree.delete(accused)
    val outTree = tree.ergoValue

    val evalIn = UTXO(evalContract(ctx), boxValue, tokens, Seq(
      inTree, ErgoValue.of(2), ErgoValue.of(BigInt(totalScore).bigInteger),
      stateReg(periodStart, block, bondHeld)))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(ContextVar.of(0.toByte,
        ErgoValue.of(Colls.fromArray(script.valueBytes), scalaByteType)))

    val fpIn = fundingInput(ctx, prover).setCtxVars(
      (Seq(
        ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(accused), scalaByteType)),
        ContextVar.of(1.toByte, lookup.proof.ergoValue),
        ContextVar.of(2.toByte, removal.proof.ergoValue)) ++ extraVars): _*)

    val out = UTXO(evalContract(ctx), boxValue - bond, tokens, Seq(
      outTree, ErgoValue.of(1), ErgoValue.of(BigInt(bystanderScore).bigInteger),
      stateReg(periodStart, block, bondHeld - bond)))

    Fraud(ctx, evalIn, fpIn, out, UTXO(contractOf(prover), bond),
      if (dataInputs == null) Seq(fpDataInput(ctx, Seq(script))) else dataInputs,
      prover, accused, script, score, bond, periodStart, block)
  }

  /** Curried, so a negative names the one field it perturbs. */
  protected def fraudTx(f: Fraud)(out: UTXO = f.out,
                                  reward: UTXO = f.reward,
                                  inputs: Seq[InputUTXO] = null,
                                  data: Seq[InputUTXO] = null): UnsignedTransaction =
    build(f.ctx,
      if (inputs == null) Seq(f.evalIn, f.fpIn) else inputs,
      Seq(out, reward),
      f.prover.getAddress,
      if (data == null) f.data else data)

  /** The rollup's own block, which `fraud` writes into R7 slot 1 and the window proofs read. */
  protected def rollupBlock(ctx: BlockchainContext): Long =
    ctx.getHeight.toLong - 100L - LFSMHelpers.HOLDING_PERIOD

  /** A height inside `[currentBlock - NISP_WINDOW, currentBlock]`, where an honest share sits. */
  protected def inWindow(ctx: BlockchainContext): Long = rollupBlock(ctx)

  /**
   * Successor and reward for a slash declaring `delta` instead of the removed entry's real score.
   *
   * Evaluation prices the slashed bond off `totalScore - nextScore` and pins the box value, the bond
   * ledger and the prover's reward to it. Moving the score alone is therefore rejected by Evaluation
   * and proves nothing about the proof, so this moves all four together — leaving the proof's own
   * `nextScore == totalScore - score` as the only thing that can fail.
   */
  protected def declaring(f: Fraud, delta: Long): (UTXO, UTXO) = {
    val slashed = bondFor(delta)
    val held = bondFor(f.score) + bondFor(bystanderScore)
    val out = UTXO(f.out.contract, boxValue - slashed, f.out.tokens, f.out.registers)
      .withReg(2, ErgoValue.of(BigInt(f.score + bystanderScore - delta).bigInteger))
      .withReg(3, stateReg(f.periodStart, f.blockHeight, held - slashed))
    (out, UTXO(f.reward.contract, slashed))
  }
}

/**
 * The transition every fraud proof owns, run against each one.
 *
 * All eight carry their own copy of `minerRemoved`, `nextMiners == currentMiners - 1` and
 * `nextScore == totalScore - score`, so the eight copies can drift apart. Asserting them from one
 * place is what makes a proof that quietly dropped one of them fail here.
 *
 * Evaluation owns the box value, the bond ledger and the prover's reward on the same transaction, so
 * each perturbation below is chosen to leave those satisfied.
 */
trait FraudProofTransitionChecks { this: AnyPropSpec with FraudProofSpecBase =>

  protected def transitionChecks(label: String)(build: BlockchainContext => Fraud): Unit = {

    property(s"$label transition: rejects a successor that keeps the miner count (nextMiners)") {
      withCtx { ctx =>
        val f = build(ctx)
        rejectsAtSigning(f.prover, fraudTx(f)(out = f.out.withReg(1, ErgoValue.of(2))))
      }
    }

    property(s"$label transition: rejects a successor that drops two miners at once (nextMiners)") {
      withCtx { ctx =>
        val f = build(ctx)
        rejectsAtSigning(f.prover, fraudTx(f)(out = f.out.withReg(1, ErgoValue.of(0))))
      }
    }

    /** The proof is what ties the score delta to the entry it removed; Evaluation only prices it. */
    property(s"$label transition: rejects a score delta larger than the removed entry's (nextScore)") {
      withCtx { ctx =>
        val f = build(ctx)
        val (out, reward) = declaring(f, f.score + 1000L)
        rejectsAtSigning(f.prover, fraudTx(f)(out = out, reward = reward))
      }
    }

    property(s"$label transition: rejects a score delta smaller than the removed entry's (nextScore)") {
      withCtx { ctx =>
        val f = build(ctx)
        val (out, reward) = declaring(f, f.score - 1000L)
        rejectsAtSigning(f.prover, fraudTx(f)(out = out, reward = reward))
      }
    }

    property(s"$label transition: rejects a successor whose tree still holds the miner (minerRemoved)") {
      withCtx { ctx =>
        val f = build(ctx)
        val unchanged = f.evalIn.registers.head
        rejectsAtSigning(f.prover, fraudTx(f)(out = f.out.withReg(0, unchanged)))
      }
    }
  }
}
