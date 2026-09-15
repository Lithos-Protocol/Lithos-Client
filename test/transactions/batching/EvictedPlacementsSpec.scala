package transactions.batching

import node.NodeApi
import node.model.{NodeBox, NodeInput, NodeSpendingProof, NodeTransaction}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool

import scala.util.{Failure, Success}

/**
 * Placements a batcher carried, added back to an observation after the node evicts them.
 *
 * The node here is only a UTXO set: a box id it returns is unspent. What matters is that nothing is
 * added back that a block could not carry, and that the store frees its slots as placements settle.
 */
class EvictedPlacementsSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def id(seed: String): String = (seed * 64).take(64)

  private def box(boxId: String): NodeBox = NodeBox(boxId, id("8"), 1000000000L, 0, 100, "0008cd02" + "11" * 32)

  private def tx(txId: String, spends: Seq[String], creates: Seq[String] = Seq.empty): CompleteMempool.MempoolTx =
    CompleteMempool.MempoolTx(txId,
      NodeTransaction(txId, spends.map(NodeInput(_, NodeSpendingProof.empty)), Seq.empty, creates.map(box)), 100)

  private def snapshot(txs: CompleteMempool.MempoolTx*): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot("cc" * 32, txs.map(_.id).toSet, txs.flatMap(_.body.inputs.map(_.boxId)).toSet,
      System.nanoTime(), transactions = txs.toVector)

  /** A node whose UTXO set holds exactly `unspent`. */
  private def node(unspent: String*): NodeApi = {
    val api = mock[NodeApi]
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      Success(inv.getArgument[Seq[String]](0).filter(unspent.contains).map(box))
    }
    api
  }

  private val placement = tx(id("a"), Seq(id("1")), Seq(id("2")))

  "Evicted placements" should "add back a carried placement the mempool no longer holds" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement), 100)
    val restored = held.restore(snapshot(), 101, node(id("1")))
    restored.transactions.map(_.id) shouldBe Vector(placement.id)
    restored.ids should contain(placement.id)
    restored.spent should contain(id("1"))
  }

  it should "not add a second copy of a placement the mempool still holds, and read nothing for it" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement), 100)
    val api = mock[NodeApi]
    val observed = snapshot(placement)
    held.restore(observed, 101, api) shouldBe observed
    org.mockito.Mockito.verifyNoInteractions(api)
  }

  it should "hold a placement once when one build carries it twice, so its input shows one claim" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement, placement), 100)
    val restored = held.restore(snapshot(), 101, node(id("1")))
    restored.transactions.map(_.id) shouldBe Vector(placement.id)
    BatchingMempool.placedByWallet(placement, Map(id("1") -> box(id("1"))), BatchingMempool.spenders(restored),
      _ => false) shouldBe true
  }

  it should "forget a placement whose input the chain has spent, even once the read would pass again" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement), 100)
    held.restore(snapshot(), 101, node()).transactions shouldBe empty
    held.heldIds shouldBe empty
    held.restore(snapshot(), 102, node(id("1"))).transactions shouldBe empty
  }

  it should "forget a placement whose input another unconfirmed transaction claims" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement), 100)
    val cancel = tx(id("c"), Seq(id("1")))
    held.restore(snapshot(cancel), 101, node(id("1"))).transactions.map(_.id) shouldBe Vector(cancel.id)
    held.heldIds shouldBe empty
  }

  it should "add back a child after its parent, and neither when the parent's input is spent" in {
    val parent = tx(id("a"), Seq(id("1")), Seq(id("2")))
    val child = tx(id("b"), Seq(id("2")), Seq(id("3")))
    val kept = new EvictedPlacements
    kept.remember(Seq(parent, child), 100)
    kept.restore(snapshot(), 101, node(id("1"))).transactions.map(_.id) shouldBe Vector(parent.id, child.id)

    val spent = new EvictedPlacements
    spent.remember(Seq(parent, child), 100)
    spent.restore(snapshot(), 101, node()).transactions shouldBe empty
    spent.heldIds shouldBe empty
  }

  it should "keep a placement across heights while builds carry it, and forget it once they stop" in {
    val held = new EvictedPlacements
    val idle = EvictedPlacements.MaxIdleBlocks
    held.remember(Seq(placement), 100)
    held.restore(snapshot(), 100 + idle, node(id("1"))).transactions should have size 1
    held.remember(Seq(placement), 100 + idle)
    held.restore(snapshot(), 100 + 2 * idle, node(id("1"))).transactions should have size 1
    held.restore(snapshot(), 100 + 2 * idle + 1, node(id("1"))).transactions shouldBe empty
    held.heldIds shouldBe empty

    withClue("an idle placement the mempool still holds frees its slot too: ") {
      val present = new EvictedPlacements
      present.remember(Seq(placement), 100)
      present.restore(snapshot(placement), 100 + idle + 1, node(id("1")))
      present.heldIds shouldBe empty
    }
  }

  it should "take no new placement at capacity, and free a slot once one settles" in {
    val held = new EvictedPlacements
    val full = (1 to EvictedPlacements.Capacity).map(i => tx(f"$i%064x", Seq(f"${i + 100}%064x")))
    held.remember(full, 100)
    val extra = tx(id("e"), Seq(id("5")))
    held.remember(Seq(extra), 100)
    held.heldIds shouldBe full.map(_.id).toSet

    // Every held input except the first is still unspent, so exactly one slot frees
    held.restore(snapshot(), 101, node(full.tail.flatMap(_.body.inputs.map(_.boxId)): _*))
    held.remember(Seq(extra), 101)
    held.heldIds should contain(extra.id)
    held.heldIds.size shouldBe EvictedPlacements.Capacity
  }

  it should "restore nothing and forget nothing when the input read fails" in {
    val held = new EvictedPlacements
    held.remember(Seq(placement), 100)
    val api = mock[NodeApi]
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenReturn(Failure(new RuntimeException("node down")))
    val observed = snapshot()
    held.restore(observed, 101, api) shouldBe observed
    held.heldIds shouldBe Set(placement.id)
  }
}
