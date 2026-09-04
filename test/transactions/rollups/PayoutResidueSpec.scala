package transactions.rollups

import contracts.specs.rollup.RollupSpecBase
import lfsm.states.{Rollup, RollupInfoState}
import lfsm.{LFSMHelpers, RollupProtocol}
import mutations.NodeWallet
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, SecretString}
import org.scalatest.propspec.AnyPropSpec
import transactions.rollups.TransactionMessages.LatestRollup
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/**
 * The LIT a final payout leaves behind.
 *
 * Every share is floored against the original R7 snapshot, so the box that pays the last claimant
 * holds their exact share plus everything the flooring dropped. That remainder belongs to nobody: it
 * cannot stay on a box being consumed, and this path burns only the rollup NFT. Without an output for
 * it the transaction cannot balance, and a lone final payout is the ordinary shape once every other
 * claimant has gone — so the rollup's ERG and LIT would be locked with a satisfiable contract.
 */
class PayoutResidueSpec extends AnyPropSpec with RollupSpecBase {

  // Scores 1 and 6 over this reward floor to 68,571,428,571 and 411,428,571,428, one unit short of
  // the 480,000,000,000 the box holds. That last unit is the whole finding.
  private val litReward = 480000000000L
  private val ergReward = 700000000L
  private val scoreA = 1L
  private val scoreB = 6L
  private val totalScore = scoreA + scoreB
  private val bond = LFSMHelpers.MIN_ENTRY_BOND
  private val litId = LFSMHelpers.LIT_ID
  private val nft = Token(ErgoId.create("cc" * 32), 1L)

  private def shareOf(score: Long, total: Long): Long =
    LFSMHelpers.paymentFromScore(score, BigInt(totalScore), total)

  private def walletFrom(ctx: BlockchainContext, phrase: String): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(phrase), SecretString.empty(), false)
      .withEip3Secret(0).build())

  private def minerA(ctx: BlockchainContext): NodeWallet =
    walletFrom(ctx, "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic")

  private def minerB(ctx: BlockchainContext): NodeWallet =
    walletFrom(ctx, "legal winner thank year wave sausage worth useful legal winner thank yellow")

  /**
   * A payout box holding exactly the claimants still in the tree.
   *
   * R6 stays at the original total score however many have been paid — that snapshot is what every
   * share is divided against, and it is why the flooring residue accumulates rather than being
   * re-spread over whoever is left.
   */
  private def boxWith(ctx: BlockchainContext,
                      claimants: Seq[(NodeWallet, Long)],
                      value: Long,
                      lit: Long,
                      bondHeld: Long): (InputUTXO, Rollup) = {
    val state = RollupInfoState.payout(ergReward, litReward, bondHeld)
    val tree = treeWith(claimants.map { case (w, score) =>
      w.contract.hashedPropBytes -> nispBytes(score, realisticNispSize)
    })
    val tokens = Seq(nft) ++ (if (lit > 0) Seq(Token(litId, lit)) else Seq.empty[Token])
    val box = UTXO(payoutContract(ctx), value, tokens, Seq(
      tree.ergoValue, ErgoValue.of(claimants.size), ErgoValue.of(BigInt(totalScore).bigInteger),
      state.ergoValue)).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
    (box, Rollup(tree, numMiners = claimants.size, totalScore = BigInt(totalScore), state = state,
      value = value, startHeight = ctx.getHeight - 800, hasMiner = true,
      blockId = "bb" * 32, utxoId = dummyTxId))
  }

  /** A funded shape: one fee box, and a wallet input two fees wide, as a batch supplies. */
  private def funded(ctx: BlockchainContext, wallet: NodeWallet): (Seq[InputUTXO], Seq[UTXO]) =
    (Seq(UTXO(wallet.contract, 2L * UTXO.MIN_FEE).toInput(ctx, ErgoId.create("ab" * 32), 0.toShort)),
      Seq(UTXO.feeBox(UTXO.MIN_FEE)))

  private def outputs(tx: SignedTransaction): Seq[InputBox] = {
    val list = tx.getOutputsToSpend
    (0 until list.size()).map(list.get)
  }

  private def litOn(box: InputBox): Long = {
    val tokens = box.getTokens
    (0 until tokens.size()).map(tokens.get)
      .find(_.getId.toString == litId.toString).map(_.getValue.toLong).getOrElse(0L)
  }

  // ─── the residue accumulates ──────────────────────────────────────────────

  /**
   * The partial payout first, run through the real builder, because what the final one inherits is
   * whatever this leaves — asserting it here is what makes the fixture below the real successor
   * rather than a guess about one.
   */
  property("partial: the successor keeps every unit the paid share did not take") {
    withCtx { ctx =>
      val a = minerA(ctx)
      val b = minerB(ctx)
      val value = ergReward + 2L * bond
      val (in, rollup) = boxWith(ctx, Seq(a -> scoreA, b -> scoreB), value, litReward, 2L * bond)
      val (inputs, fees) = funded(ctx, a)

      val tx = RollupTransactions.genPayout(ctx, a, in, inputs, LatestRollup(in, rollup), fees)
      val successor = outputs(tx).head
      val paid = outputs(tx)(1)

      shareOf(scoreA, litReward) shouldEqual 68571428571L
      litOn(paid) shouldEqual shareOf(scoreA, litReward)
      withClue("the flooring residue stays with the box, which is where it accumulates: ") {
        litOn(successor) shouldEqual litReward - shareOf(scoreA, litReward)
      }
      successor.getValue.toLong shouldEqual value - (shareOf(scoreA, ergReward) + bond)
    }
  }

  // ─── the final payout ─────────────────────────────────────────────────────

  /** What the partial payout above leaves: B alone in the tree, holding one bond. */
  private def finalBox(ctx: BlockchainContext, b: NodeWallet): (InputUTXO, Rollup) =
    boxWith(ctx, Seq(b -> scoreB),
      value = ergReward + 2L * bond - (shareOf(scoreA, ergReward) + bond),
      lit = litReward - shareOf(scoreA, litReward),
      bondHeld = bond)

  property("final: the residue leaves in a P2PK output and the miner gets the exact share") {
    withCtx { ctx =>
      val b = minerB(ctx)
      val (in, rollup) = finalBox(ctx, b)
      val (inputs, fees) = funded(ctx, b)

      val plan = RollupTransactions.planPayout(b, in, LatestRollup(in, rollup))
      plan.isFinal shouldBe true
      plan.litResidue shouldEqual 1L

      // Signed by the builder against the production Payout contract, so a returned transaction is
      // one the contract accepted.
      val tx = RollupTransactions.genPayout(ctx, b, in, inputs, LatestRollup(in, rollup), fees)
      val paid = outputs(tx).head
      val change = outputs(tx)(1)

      litOn(paid) shouldEqual shareOf(scoreB, litReward)
      withClue("every residual unit has to reach an output, since only the NFT is burned: ") {
        litOn(change) shouldEqual 1L
      }
      change.getErgoTree.bytesHex shouldEqual b.contract.ergoTreeHex
      change.getValue.toLong should be >= UTXO.MIN_CHANGE
      withClue("nothing may be left unaccounted for, since only the NFT is burned: ") {
        litOn(paid) + litOn(change) shouldEqual litReward - shareOf(scoreA, litReward)
      }
    }
  }

  property("final: no residue leaves no extra output") {
    withCtx { ctx =>
      // The same shape with a reward that divides exactly, so nothing is left over.
      val b = minerB(ctx)
      val (in, rollup) = boxWith(ctx, Seq(b -> scoreB),
        value = ergReward + 2L * bond - (shareOf(scoreA, ergReward) + bond),
        lit = shareOf(scoreB, litReward), bondHeld = bond)
      val (inputs, fees) = funded(ctx, b)

      RollupTransactions.planPayout(b, in, LatestRollup(in, rollup)).litResidue shouldEqual 0L
      val tx = RollupTransactions.genPayout(ctx, b, in, inputs, LatestRollup(in, rollup), fees)
      litOn(outputs(tx).head) shouldEqual shareOf(scoreB, litReward)
    }
  }

  // ─── what a fee-less build can and cannot do ──────────────────────────────

  /**
   * A fee-less payout balances on the rollup box alone, and reaching the final branch means its
   * remainder is already below what a box can hold — so there is no ERG for a change output and no
   * fee box to fold a shortfall into. It is refused rather than built wrong; the funded copy still
   * reaches the mempool, so the payout happens, it just does not ride in this miner's own block.
   */
  property("fee-less: a final payout carrying residue is refused rather than built") {
    withCtx { ctx =>
      val b = minerB(ctx)
      val (in, rollup) = finalBox(ctx, b)

      RollupTransactions.planPayout(b, in, LatestRollup(in, rollup)).needsChangeOutput shouldBe true
      val attempt = Try(RollupTransactions.genPayout(
        ctx, b, in, Seq.empty[InputUTXO], LatestRollup(in, rollup), Seq.empty[UTXO]))
      attempt.isFailure shouldBe true
      attempt.failed.get.getMessage should include("LIT")
    }
  }

  property("fee-less: a final payout with nothing left over still builds") {
    withCtx { ctx =>
      val b = minerB(ctx)
      val (in, rollup) = boxWith(ctx, Seq(b -> scoreB),
        value = ergReward + 2L * bond - (shareOf(scoreA, ergReward) + bond),
        lit = shareOf(scoreB, litReward), bondHeld = bond)

      RollupTransactions.planPayout(b, in, LatestRollup(in, rollup)).needsChangeOutput shouldBe false
      val tx = RollupTransactions.genPayout(
        ctx, b, in, Seq.empty[InputUTXO], LatestRollup(in, rollup), Seq.empty[UTXO])
      outputs(tx) should have size 1
    }
  }

  // ─── the bound ────────────────────────────────────────────────────────────

  property("the residue is bounded by the number of payouts before it") {
    // One floored division per claimant, each losing under one unit, so the last box can never hold
    // more than the number of shares taken before it. Nothing here can overflow a Long.
    val miners = 720
    val scores = (1 to miners).map(_.toLong * 1000L)
    val total = BigInt(scores.sum)
    val paid = scores.map(LFSMHelpers.paymentFromScore(_, total, litReward)).sum
    val residue = litReward - paid

    residue should be >= 0L
    residue should be < miners.toLong
  }
}
