package contracts.specs.rollup

import evaluation.Evaluator
import lfsm.LFSMHelpers
import mutations.{BoxLoader, NodeWallet}
import node.NodeApi
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, SecretString}
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * What the evaluator concludes when a proof cannot run.
 *
 * A proof has two verdicts and one non-answer, and only the two are about the miner. Reading the
 * third as "no fraud" persists `evaluated = true` and retires the rollup, so a NISP that breaks one
 * proof buys immunity from all of them — and the accused writes the NISP, so they choose what each
 * proof is handed.
 */
class EvaluatorOutcomeSpec extends AnyPropSpec with FraudProofSpecBase with MockitoSugar {

  private val score = 100000L

  /** A proof the dispatcher has no builder for, which is how a proof fails before it can run. */
  private def unbuildable(ctx: BlockchainContext): Contract = payoutContract(ctx)

  /**
   * The production evaluation contract and fraud-proof token, not the spec base's.
   *
   * `EvaluationMutator` is production code and pins `LFSMHelpers.getFPToken`, so a stand-in token in
   * the FP_CONTROL data input fails its pre-requisite before any proof runs — and the evaluation
   * contract has to be the one compiled against that same token or it rejects the data input itself.
   */
  private def liveEval(ctx: BlockchainContext): Contract = utils.Helpers.evalContract(ctx)

  private def finder(ctx: BlockchainContext): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(
        "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"),
        SecretString.empty(), false)
      .withEip3Secret(0).build())

  private def accused(ctx: BlockchainContext): Array[Byte] =
    contractOf(proverWith(ctx, otherSecret)).hashedPropBytes

  /** An evaluation box holding the accused and one bystander, as every real one does. */
  private def evaluatorOver(ctx: BlockchainContext,
                            nisp: Array[Byte],
                            proofs: Seq[Contract]): Evaluator = {
    val wallet = finder(ctx)
    val bystander = contractOf(proverWith(ctx, funderSecret)).hashedPropBytes
    val tree = treeWith(Seq(
      accused(ctx) -> nisp,
      bystander -> nispBytes(bystanderScore, realisticNispSize)))
    val bondHeld = bondFor(score) + bondFor(bystanderScore)
    val periodStart = ctx.getHeight.toLong - 100L
    val block = rollupBlock(ctx)

    val evalIn = UTXO(liveEval(ctx), boxValue, Seq(Token(LFSMHelpers.getFPToken(ctx), 1L)), Seq(
      tree.ergoValue, ErgoValue.of(2), ErgoValue.of(BigInt(score + bystanderScore).bigInteger),
      stateReg(periodStart, block, bondHeld)))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

    val rollup = lfsm.states.Rollup(tree, numMiners = 2, totalScore = BigInt(score + bystanderScore),
      state = lfsm.states.RollupInfoState.evaluation(periodStart, block, bondHeld),
      value = boxValue, startHeight = block.toInt, hasMiner = true, evaluated = false,
      blockId = "bb" * 32, utxoId = dummyTxId)

    Evaluator(ctx, wallet, evalIn, rollup, Seq(accused(ctx)),
      fpDataInput(ctx, proofs, LFSMHelpers.getFPToken(ctx)), new BoxLoader(ctx, mock[NodeApi]), proofs)
  }

  private def verdictOf(e: Evaluator): Try[Option[(SignedTransaction, String)]] =
    e.evaluateSync.head._2

  /** Well formed and transaction-proved, so the proof below has nothing to find. */
  private def cleanNisp(ctx: BlockchainContext): Array[Byte] =
    NispFixtures.nisp(score,
      NispFixtures.includedShares(rollupBlock(ctx).toInt, NispFixtures.pkOf(miner(ctx))))

  /** Framing the format gate rejects. */
  private def brokenNisp(ctx: BlockchainContext): Array[Byte] =
    NispFixtures.rawNisp(score,
      NispFixtures.cleanShares(rollupBlock(ctx).toInt, NispFixtures.pkOf(miner(ctx)))
        .map { s =>
          val bytes = NispFixtures.shareWithHeader(s, s.headerBytes)
          val sizeAt = 4 + s.headerBytes.length
          bytes.slice(0, sizeAt) ++ Array(0.toByte, 2.toByte) ++ bytes.drop(sizeAt + 2)
        })

  // ─── a non-answer is not a verdict ────────────────────────────────────────

  property("a proof that cannot run leaves the miner without a verdict") {
    withCtx { ctx =>
      // The whole finding in one line: this used to come back as Success(None), which the caller
      // records as NoFraudulence and the rollup is then marked evaluated and never retried.
      val e = evaluatorOver(ctx, cleanNisp(ctx), Seq(unbuildable(ctx)))
      verdictOf(e).isFailure shouldBe true
    }
  }

  property("a clean rollup stays unevaluated when any proof could not answer") {
    withCtx { ctx =>
      // Every other proof declines, so the only thing standing between this rollup and a permanent
      // `evaluated = true` is that one proof never ran.
      val e = evaluatorOver(ctx, cleanNisp(ctx),
        Seq(unbuildable(ctx), fpTransactionNotIncluded(ctx)))
      val verdict = verdictOf(e)

      verdict.isFailure shouldBe true
      withClue("the reason has to name the proofs, or nobody can tell why it is being retried: ") {
        verdict.failed.get.getMessage should include("could not run")
      }
    }
  }

  // ─── and it does not stop the others ──────────────────────────────────────

  property("a real fraud is still found after an earlier proof failed") {
    withCtx { ctx =>
      // The property the old behaviour got right and which the fix has to keep: one proof failing
      // must not cost the rollup every later proof's verdict.
      val e = evaluatorOver(ctx, brokenNisp(ctx),
        Seq(unbuildable(ctx), fpInvalidFormat(ctx)))
      verdictOf(e) match {
        case Success(Some((_, hash))) =>
          hash shouldEqual fpInvalidFormat(ctx).hashedPropBytesHex
        case other => fail(s"expected fraud to be found despite the failed proof, got $other")
      }
    }
  }

  property("every proof answering cleanly is the only way to no fraud") {
    withCtx { ctx =>
      val e = evaluatorOver(ctx, cleanNisp(ctx), Seq(fpTransactionNotIncluded(ctx)))
      verdictOf(e) shouldEqual Success(None)
    }
  }
}
