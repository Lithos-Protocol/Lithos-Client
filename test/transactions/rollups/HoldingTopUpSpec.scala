package transactions.rollups

import contracts.specs.rollup.RollupSpecBase
import mutations.NodeWallet
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.{ErgoId, SecretString}
import org.scalatest.propspec.AnyPropSpec
import sigma.data.AvlTreeFlags
import utils.{Helpers => Production}
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * The same-height holding top-up: Holding op 1 taken inside the rollup's own block.
 *
 * The contract allows the box's ERG to rise only while HEIGHT equals the rollup's own block height,
 * and requires everything else — tokens, all four registers, the script — to carry through
 * untouched. A signed transaction here is the production guard and logic accepting the spend, so
 * these properties assert against the real branch rather than a restatement of it.
 *
 * Boxes are built at the contracts `utils.Helpers` compiles, not the spec base's: the base compiles
 * Evaluation with a stand-in fraud-proof token, so its holding guard is a different script and
 * refuses the logic this builder hands to context variable 64.
 */
class HoldingTopUpSpec extends AnyPropSpec with RollupSpecBase {

  private val nft = Token(ErgoId.create("cc" * 32), 1L)
  private val revenueToken = Token(ErgoId.create("dd" * 32), 500L)

  private def wallet(ctx: BlockchainContext): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(
        "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"),
        SecretString.empty(), false)
      .withEip3Secret(0).build())

  /**
   * A holding box as genesis leaves it: no submissions, no bond, and its period start and block
   * height both at the block being mined, which is the only height a top-up is legal at.
   */
  private def genesisHolding(ctx: BlockchainContext, value: Long = boxValue): InputUTXO = {
    val height = ctx.getHeight.toLong
    rollupBox(Production.holdingContract(ctx), emptyTreeWith(AvlTreeFlags.InsertOnly),
      miners = 0, totalScore = 0L, periodOrReward = height, value = value,
      tokens = Seq(nft), blockOrLit = height, bond = 0L)
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
  }

  /** Revenue as a candidate transaction would leave it: an output the miner's wallet can spend. */
  private def revenue(ctx: BlockchainContext, w: NodeWallet,
                      value: Long, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    UTXO(w.contract, value, tokens).toInput(ctx, ErgoId.create("ab" * 32), 0.toShort)

  private def outputs(tx: SignedTransaction): Seq[InputBox] =
    tx.getOutputsToSpend.asScala.toVector

  // ─── the successor ────────────────────────────────────────────────────────

  property("the box grows and nothing else about it moves") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val holding = genesisHolding(ctx)
      val plan = RollupTransactions.planTopUp(
        Seq(revenue(ctx, w, Parameters.OneErg)), Seq(UTXO.feeBox()))
      plan.added shouldEqual Parameters.OneErg - UTXO.MIN_FEE

      val tx = RollupTransactions.genHoldingTopUp(ctx, w, holding,
        Seq(revenue(ctx, w, Parameters.OneErg)), Seq(UTXO.feeBox()), ctx.getHeight)
      val successor = outputs(tx).head

      successor.getValue.toLong shouldEqual (holding.value + plan.added)
      successor.getErgoTree.bytesHex shouldEqual Production.holdingContract(ctx).ergoTreeHex
      withClue("the registers are the rollup's state, and a top-up is not a state change: ") {
        successor.getRegisters.asScala.toVector shouldEqual holding.registers
      }
      successor.getTokens.asScala.map(t => t.getId.toString -> t.getValue.toLong) shouldEqual
        Seq(nft.id.toString -> nft.amount)
    }
  }

  /** The contract identifies the holding box by position on both sides of the transaction. */
  property("holding is input 0 and output 0") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val holding = genesisHolding(ctx)
      val tx = RollupTransactions.genHoldingTopUp(ctx, w, holding,
        Seq(revenue(ctx, w, Parameters.OneErg)), Seq(UTXO.feeBox()), ctx.getHeight)

      tx.getInputBoxesIds.asScala.head.replace("\"", "") shouldEqual holding.id.toString
      outputs(tx).head.getErgoTree.bytesHex shouldEqual Production.holdingContract(ctx).ergoTreeHex
    }
  }

  // ─── tokens the revenue carried ───────────────────────────────────────────

  /**
   * Holding conserves its own token vector exactly, so a token arriving on a revenue input cannot
   * ride into the box. It leaves to the miner's own wallet, and the ERG that output needs is
   * charged against what the top-up can add.
   */
  property("revenue tokens leave in a wallet output rather than entering holding") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val holding = genesisHolding(ctx)
      val inputs = Seq(revenue(ctx, w, Parameters.OneErg, Seq(revenueToken)))
      val fees = Seq(UTXO.feeBox())

      val plan = RollupTransactions.planTopUp(inputs, fees)
      plan.residue shouldEqual Seq(revenueToken)
      plan.added shouldEqual Parameters.OneErg - UTXO.MIN_FEE - UTXO.MIN_CHANGE

      val tx = RollupTransactions.genHoldingTopUp(ctx, w, holding, inputs, fees, ctx.getHeight)
      val residue = outputs(tx)(1)
      residue.getErgoTree.bytesHex shouldEqual w.contract.ergoTreeHex
      residue.getTokens.asScala.map(_.getValue.toLong) shouldEqual Seq(revenueToken.amount)
      outputs(tx).head.getTokens.asScala.map(_.getId.toString) shouldEqual Seq(nft.id.toString)
    }
  }

  property("one token id spread over several inputs is summed into a single residue") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val inputs = Seq(
        revenue(ctx, w, Parameters.OneErg, Seq(Token(revenueToken.id, 200L))),
        revenue(ctx, w, Parameters.OneErg, Seq(Token(revenueToken.id, 300L))))

      RollupTransactions.planTopUp(inputs, Seq(UTXO.feeBox())).residue shouldEqual
        Seq(Token(revenueToken.id, 500L))
    }
  }

  // ─── what is refused ──────────────────────────────────────────────────────

  /** The contract needs the value strictly higher, so a top-up that adds nothing is not built. */
  property("a top-up that adds nothing is refused") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val holding = genesisHolding(ctx)
      val inputs = Seq(revenue(ctx, w, UTXO.MIN_FEE))

      RollupTransactions.planTopUp(inputs, Seq(UTXO.feeBox())).isViable shouldBe false
      val attempt = Try(RollupTransactions.genHoldingTopUp(ctx, w, holding, inputs, Seq(UTXO.feeBox()), ctx.getHeight))
      attempt.isFailure shouldBe true
      attempt.failed.get.getMessage should include("value to increase")
    }
  }

  /**
   * Past the rollup's own block the same op byte means transform into evaluation, so a top-up built
   * there would be signed against a branch that conserves value and refuses it.
   */
  property("a top-up outside the rollup's own block is refused") {
    withCtx { ctx =>
      val w = wallet(ctx)
      val height = ctx.getHeight.toLong
      val stale = rollupBox(Production.holdingContract(ctx), emptyTreeWith(AvlTreeFlags.InsertOnly),
        miners = 0, totalScore = 0L, periodOrReward = height, tokens = Seq(nft),
        blockOrLit = height - 1L)
        .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

      val attempt = Try(RollupTransactions.genHoldingTopUp(ctx, w, stale,
        Seq(revenue(ctx, w, Parameters.OneErg)), Seq(UTXO.feeBox()), ctx.getHeight))
      attempt.isFailure shouldBe true
      attempt.failed.get.getMessage should include("own block")
    }
  }
}
