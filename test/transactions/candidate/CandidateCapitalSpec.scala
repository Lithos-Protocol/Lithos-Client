package transactions.candidate

import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import scala.util.Try

/**
 * The per-height ledger of outputs this client's own candidate transactions created.
 *
 * It exists so a final holding top-up knows exactly what it may aggregate. Everything it refuses is
 * something the node would refuse the whole candidate over, or value that was never realized.
 */
class CandidateCapitalSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, testWallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(f)

  /**
   * A revenue output as a candidate transaction would leave it. Entries carry the box itself
   * because it is not on the chain yet, so nothing can read it back by id.
   */
  private def output(ctx: BlockchainContext, index: Int, value: Long): InputUTXO =
    UTXO(testWallet.contract, value).toInput(ctx, ErgoId.create("ab" * 32), index.toShort)

  private def entry(box: InputUTXO) =
    CapitalEntry(CapitalOrigin.ExecutorReward, box, parentTxId = "parent-tx")

  private val fresh = CandidateCapital(500)

  "An empty ledger" should "offer nothing to aggregate" in {
    fresh.availableErg shouldEqual 0L
    fresh.entries shouldBe empty
  }

  "A credited output" should "count toward what the top-up may aggregate" in {
    withCtx { ctx =>
      val ledger = fresh.credit(entry(output(ctx, 0, 1000L))).credit(entry(output(ctx, 1, 250L)))
      ledger.availableErg shouldEqual 1250L
      ledger.entries.map(_.value) shouldBe Vector(1000L, 250L)
    }
  }

  it should "not be credited twice under the same id" in {
    withCtx { ctx =>
      val box = output(ctx, 0, 1000L)
      Try(fresh.credit(entry(box)).credit(entry(box))).isFailure shouldBe true
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
