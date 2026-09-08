package transactions

import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/**
 * The per-height ledger of outputs this client's own candidate transactions created.
 *
 * It exists so a final holding top-up knows exactly what it may aggregate. Everything it refuses is
 * something the node would refuse the whole candidate over, or value that was never realized.
 */
class CandidateCapitalSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val tokenA = Token(ErgoId.create("aa" * 32), 100L)
  private val tokenB = Token(ErgoId.create("bb" * 32), 7L)

  private val (nodeContext, _, testWallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(f)

  /**
   * A revenue output as a candidate transaction would leave it. Entries carry the box itself
   * because it is not on the chain yet, so nothing can read it back by id.
   */
  private def output(ctx: BlockchainContext, index: Int, value: Long,
                     tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    UTXO(testWallet.contract, value, tokens)
      .toInput(ctx, ErgoId.create("ab" * 32), index.toShort)

  private def entry(box: InputUTXO) =
    CapitalEntry(CapitalOrigin.ExecutorReward, box, parentTxId = "parent-tx")

  private val fresh = CandidateCapital(500)

  "An empty ledger" should "offer nothing to aggregate" in {
    fresh.availableErg shouldEqual 0L
    fresh.unspent shouldBe empty
    fresh.residualTokens shouldBe empty
  }

  "A credited output" should "be available until a later member spends it" in {
    withCtx { ctx =>
      val first = output(ctx, 0, 1000L)
      val ledger = fresh.credit(entry(first)).credit(entry(output(ctx, 1, 250L)))
      ledger.availableErg shouldEqual 1250L

      val after = ledger.spend(first.id.toString, "tx-9")
      after.availableErg shouldEqual 250L
      after.unspent.map(_.value) shouldBe Vector(250L)
      after.entries.head.spentBy shouldBe Some("tx-9")
    }
  }

  /** Two members spending one output is a double spend, and it costs the whole candidate. */
  it should "not be spendable twice" in {
    withCtx { ctx =>
      val box = output(ctx, 0, 1000L)
      val ledger = fresh.credit(entry(box)).spend(box.id.toString, "tx-9")
      val attempt = Try(ledger.spend(box.id.toString, "tx-10"))
      attempt.isFailure shouldBe true
      attempt.failed.get.getMessage should include("already spent")
    }
  }

  it should "not be credited twice under the same id" in {
    withCtx { ctx =>
      val box = output(ctx, 0, 1000L)
      Try(fresh.credit(entry(box)).credit(entry(box))).isFailure shouldBe true
    }
  }

  "An output the ledger never saw" should "not be spendable" in {
    Try(fresh.spend("unknown", "tx-9")).isFailure shouldBe true
  }

  // ─── what the top-up has to hand back ─────────────────────────────────────

  /**
   * Holding conserves its own token vector exactly, so tokens on candidate outputs cannot enter it.
   * They are summed per id here because the top-up gives them one output between them.
   */
  "Residual tokens" should "be summed per id across unspent outputs" in {
    withCtx { ctx =>
      val ledger = fresh
        .credit(entry(output(ctx, 0, 1000L, Seq(tokenA, tokenB))))
        .credit(entry(output(ctx, 1, 1000L, Seq(Token(tokenA.id, 50L)))))

      ledger.residualTokens should contain theSameElementsAs
        Seq(Token(tokenA.id, 150L), tokenB)
    }
  }

  it should "exclude an output a later member already consumed" in {
    withCtx { ctx =>
      val spent = output(ctx, 0, 1000L, Seq(tokenA))
      val ledger = fresh
        .credit(entry(spent))
        .credit(entry(output(ctx, 1, 1000L, Seq(tokenB))))
        .spend(spent.id.toString, "tx-9")

      ledger.residualTokens shouldBe Seq(tokenB)
    }
  }

  // ─── the script an intermediate output carries ────────────────────────────

  /** A candidate intermediate is recoverable by default; `TrueProp` trades that for fewer bytes. */
  "An intermediate output" should "be guarded by the miner's own script unless TrueProp is chosen" in {
    CandidateCapital.collectionContract(testWallet, useTrueProp = false).ergoTreeHex shouldEqual
      testWallet.contract.ergoTreeHex
    CandidateCapital.collectionContract(testWallet, useTrueProp = true).ergoTreeHex shouldEqual
      Contract.SIGMA_TRUE.ergoTreeHex
  }
}
