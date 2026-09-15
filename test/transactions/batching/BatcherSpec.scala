package transactions.batching

import com.typesafe.config.ConfigFactory
import node.model.{NodeBox, NodeInput, NodeSpendingProof, NodeTransaction}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.synchronization.CompleteMempool

/**
 * Whether a batcher answers the stratum. Module starts every batcher whatever this returns, so the
 * answer decides only two things: whether the stratum registers it, and whether its scans have a use
 * when broadcasting is off. A wrong `true` scans the node for nothing; a wrong `false` loses every
 * candidate execution without an error.
 */
class BatcherSpec extends AnyFlatSpec with Matchers {

  private def config(stratum: Option[Boolean], batching: Option[Boolean], source: Option[Boolean],
                     blockTransactions: Option[Boolean] = Some(true)): Configuration =
    Configuration(ConfigFactory.parseString(Seq(
      stratum.map(v => s"lithos-tasks.stratum-server.enabled = $v"),
      blockTransactions.map(v => s"stratum.candidate.blockTransactions = $v"),
      batching.map(v => s"batching.ergodex.enabled = $v"),
      source.map(v => s"stratum.candidate.sources.ergodex.enabled = $v")
    ).flatten.mkString("\n")))

  "Batcher.servesCandidates" should "be true only when the stratum, the batcher and its source are all on" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true)), "ergodex") shouldBe true
  }

  it should "be false when the stratum task is off or absent" in {
    Batcher.servesCandidates(config(Some(false), Some(true), Some(true)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(None, Some(true), Some(true)), "ergodex") shouldBe false
  }

  it should "be false when the stratum inserts no block transactions, which is its default" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true), blockTransactions = Some(false)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true), blockTransactions = None), "ergodex") shouldBe false
  }

  it should "be false when the batcher is disabled" in {
    Batcher.servesCandidates(config(Some(true), Some(false), Some(true)), "ergodex") shouldBe false
  }

  it should "be false when the candidate source is off, which is its default" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(false)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(Some(true), Some(true), None), "ergodex") shouldBe false
  }

  it should "be false for a name with no candidate source" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true)), "no-such-batcher") shouldBe false
  }

  // ─── mempool order selection ──────────────────────────────────────────────

  private def orderish(id: String): NodeBox = NodeBox(id, "ab" * 32, 1000000L, 0, 100, "order-shaped")

  private def tx(txId: String, outputs: Seq[NodeBox]): CompleteMempool.MempoolTx =
    CompleteMempool.MempoolTx(txId, NodeTransaction(txId,
      Seq(NodeInput("99" * 32, NodeSpendingProof.empty)), Seq.empty, outputs), 100)

  private def snapshot(txs: CompleteMempool.MempoolTx*): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot("cc" * 32, txs.map(_.id).toSet, Set.empty, System.nanoTime(),
      transactions = txs.toVector)

  private def shaped(box: NodeBox): Boolean = box.ergoTree == "order-shaped"

  /** One transaction carrying hundreds of order boxes is what this bounds. */
  "Batcher.mempoolOrderBoxes" should "take at most perTx from one transaction, leaving room for the others" in {
    val flood = tx("ee" * 32, (1 to 50).map(i => orderish(f"$i%064x")))
    val mine = tx("dd" * 32, Seq(orderish("aa" * 32)))
    val taken = Batcher.mempoolOrderBoxes(snapshot(flood, mine), shaped, limit = 10, perTx = 2).map(_.boxId)
    taken should have size 3
    taken should contain("aa" * 32)
    withClue("the flood contributes its first perTx outputs and no more: ") {
      taken.count(id => id != "aa" * 32) shouldBe 2
    }
  }

  it should "stop at the limit, in rounds, and ignore boxes that are not order-shaped" in {
    val txs = (1 to 6).map(i => tx(f"$i%064x", (1 to 3).map(j => orderish(f"${i * 10 + j}%064x"))))
    val taken = Batcher.mempoolOrderBoxes(snapshot(txs: _*), shaped, limit = 7, perTx = 3)
    taken should have size 7
    withClue("one from each transaction before any second: ") {
      taken.take(6).map(_.boxId) shouldBe (1 to 6).map(i => f"${i * 10 + 1}%064x")
    }
    val plain = tx("bb" * 32, Seq(orderish("cc" * 32).copy(ergoTree = "0008cd")))
    Batcher.mempoolOrderBoxes(snapshot(plain), shaped, limit = 10, perTx = 4) shouldBe empty
    Batcher.mempoolOrderBoxes(snapshot(txs: _*), shaped, limit = 0, perTx = 4) shouldBe empty
  }
}