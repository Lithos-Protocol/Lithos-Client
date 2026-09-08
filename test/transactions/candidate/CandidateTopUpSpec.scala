package transactions.candidate

import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import stratum.CollateralData
import work.lithos.mutations.{InputUTXO, Token, UTXO}

/**
 * The transaction that closes a candidate package: every unspent candidate output folded into the
 * holding box the block's own genesis created.
 *
 * One transaction rather than one per source, because Holding permits a single top-up per block and
 * each additional one would have to spend the successor of the last. What it returns is signed
 * against the production contract, so building at all is the contract accepting the spend.
 */
class CandidateTopUpSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(f)

  /** One past the offline context's tip, which is the block a real candidate is built for. */
  private def nextHeight: Int = withCtx(_.getHeight) + 1

  private def revenue(ctx: BlockchainContext, index: Int, value: Long,
                      tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    UTXO(wallet.contract, value, tokens).toInput(ctx, ErgoId.create("cd" * 32), index.toShort)

  private def genesis(holding: Option[InputUTXO]): CollateralData =
    CollateralData("aa" * 32, "{}", "pk", Array.emptyByteArray, Array.emptyByteArray,
      "collateral", "9address", holdingOutput = holding)

  private def ledgerOf(height: Int, boxes: Seq[InputUTXO]): CandidateCapital =
    boxes.foldLeft(CandidateCapital(height))((ledger, box) =>
      ledger.credit(CapitalEntry(CapitalOrigin.ExecutorReward, box, parentTxId = "earner")))

  private def build(ctx: BlockchainContext, height: Int, holding: InputUTXO,
                    ledger: CandidateCapital, w: NodeWallet = wallet) =
    CandidateTopUp.build(ctx, w, genesis(Some(holding)), ledger, height)

  // ─── aggregation ──────────────────────────────────────────────────────────

  "Several revenue outputs" should "be folded into one transaction" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val holding = support.GenesisHolding(ctx, height)
      val boxes = (0 until 5).map(i => revenue(ctx, i, Parameters.OneErg))

      val tx = build(ctx, height, holding, ledgerOf(height, boxes))
      tx.map(_.kind) shouldBe Some(CandidateTopUp.Kind)
      withClue("every credited output is spent by the one top-up: ") {
        tx.get.inputIds should contain allElementsOf boxes.map(_.id.toString)
      }
      tx.get.inputIds should contain(holding.id.toString)
      tx.get.inputIds.size shouldEqual boxes.size + 1
    }
  }

  it should "raise the holding box by exactly what they held" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val holding = support.GenesisHolding(ctx, height)
      val boxes = Seq(revenue(ctx, 0, Parameters.OneErg), revenue(ctx, 1, 3L * Parameters.OneErg))

      val plan = transactions.rollups.RollupTransactions.planTopUp(boxes, Seq.empty[UTXO])
      plan.added shouldEqual 4L * Parameters.OneErg
      build(ctx, height, holding, ledgerOf(height, boxes)) shouldBe defined
    }
  }

  /** Tokens cannot enter Holding, so however many outputs carried them they leave in one output. */
  it should "hand back the tokens of every output in a single residue" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val holding = support.GenesisHolding(ctx, height)
      val litLike = ErgoId.create("dd" * 32)
      val boxes = Seq(
        revenue(ctx, 0, Parameters.OneErg, Seq(Token(litLike, 40L))),
        revenue(ctx, 1, Parameters.OneErg, Seq(Token(litLike, 60L))))

      val plan = transactions.rollups.RollupTransactions.planTopUp(boxes, Seq.empty[UTXO])
      plan.residue shouldEqual Seq(Token(litLike, 100L))
      plan.added shouldEqual (2L * Parameters.OneErg - UTXO.MIN_CHANGE)
      build(ctx, height, holding, ledgerOf(height, boxes)) shouldBe defined
    }
  }

  // ─── the input ceiling ────────────────────────────────────────────────────

  /**
   * Every input costs about the same bytes and execution, so when they cannot all fit the most
   * valuable ones carry the most ERG into the block. What is left over stays spendable later.
   */
  "More outputs than one transaction may spend" should "be reduced to the most valuable" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val holding = support.GenesisHolding(ctx, height)
      val over = CandidateTopUp.MaxRevenueInputs + 10
      // Ascending value, so the ones that must be dropped are the earliest.
      val boxes = (0 until over).map(i => revenue(ctx, i, Parameters.OneErg + i * 1000L))

      val tx = build(ctx, height, holding, ledgerOf(height, boxes)).get
      tx.inputIds.size shouldEqual CandidateTopUp.MaxRevenueInputs + 1
      val kept = boxes.drop(10).map(_.id.toString)
      tx.inputIds should contain allElementsOf kept
      withClue("the least valuable outputs are the ones left behind: ") {
        boxes.take(10).map(_.id.toString).foreach(id => tx.inputIds should not contain id)
      }
    }
  }

  // ─── what produces nothing ────────────────────────────────────────────────

  "An empty ledger" should "produce no top-up at all" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      build(ctx, height, support.GenesisHolding(ctx, height), CandidateCapital(height)) shouldBe None
    }
  }

  /** Without the box genesis created there is nothing to top up, and the revenue keeps for later. */
  "A genesis carrying no holding output" should "produce no top-up" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val ledger = ledgerOf(height, Seq(revenue(ctx, 0, Parameters.OneErg)))
      CandidateTopUp.build(ctx, wallet, genesis(None), ledger, height) shouldBe None
    }
  }

  /** The contract needs the value to rise, and the residue output can take more than there is. */
  "Revenue worth less than the residue output it needs" should "produce no top-up" in {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val dust = revenue(ctx, 0, UTXO.MIN_CHANGE / 2, Seq(Token(ErgoId.create("dd" * 32), 1L)))
      build(ctx, height, support.GenesisHolding(ctx, height), ledgerOf(height, Seq(dust))) shouldBe None
    }
  }

  /** A top-up built for another block is refused by the height the contract pins in R7. */
  "A ledger from a different height" should "produce no top-up" in {
    withCtx { ctx =>
      val height = nextHeight
      val holding = support.GenesisHolding(ctx, height)
      val ledger = ledgerOf(height, Seq(revenue(ctx, 0, Parameters.OneErg)))
      build(ctx, height + 1, holding, ledger) shouldBe None
    }
  }
}
