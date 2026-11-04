package transactions.batching.tracking

import node.model.NodeBox
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.batching.arb.ArbFixtures

/**
 * The tracked state alone, with no node: which outputs a block adds, which inputs it drops, how a block is
 * undone, and when an NFT stops being served. Pools are at the real ErgoDEX and LithosDex contracts, since what
 * is tracked is decided by the scripts and the shapes those contracts take.
 */
class TrackedPoolsSpec extends AnyFlatSpec with Matchers {
  import ArbFixtures._
  import TrackerFixtures._

  private val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)

  /** Pool `n`'s box holding `erg`, each reserve a distinct box id. */
  private def pool(n: Int, erg: Long): NodeBox =
    nodeContext.getClient.execute(ctx => nativePool(ctx, id(n), id(100 + n), erg * Erg, 1000000000L))

  private def seeded(at: Int, boxes: NodeBox*): TrackedPools =
    TrackedPools.seeded(at, s"h$at", boxes.map(b => shapes.read(b, at - 10).get), cut = false)

  "Applying a block" should "drop the pool boxes it spends and add the pool boxes it creates, at its height" in {
    val (a0, a1, b0) = (pool(1, 100), pool(1, 101), pool(2, 100))
    val stray = plain("ff" * 32)
    // A box at the pool script that does not parse as a pool: a single token where a pool holds three
    val broken = a0.copy(boxId = "ee" * 32, assets = a0.assets.take(1))
    val next = seeded(100, a0).applied(101, "h101", "h100",
      Seq(tx("t1", Seq(a0.boxId), Seq(a1, stray)), tx("t2", Seq("cc" * 32), Seq(b0, broken))), shapes, 100)

    next.boxes.keySet shouldBe Set(a1.boxId, b0.boxId)
    next.pool(id(1)).map(_.inclusionHeight) shouldBe Some(101)
    next.pool(id(2)).map(_.deployment) shouldBe Some(PoolDeployment.ErgoDexNative)
    next.history.map(b => (b.height, b.removed.map(_.boxId), b.added)) shouldBe
      Vector((101, Vector(a0.boxId), Vector(a1.boxId, b0.boxId)))
    withClue("a kept ErgoDEX box shares the script's one copy of its tree: ") {
      next.pool(id(1)).get.box.ergoTree should be theSameInstanceAs NativeTree
    }
  }

  it should "end a pool spent twice in one block at its last box, and undo to its first" in {
    val (a0, a1, a2) = (pool(1, 100), pool(1, 101), pool(1, 102))
    val start = seeded(100, a0)
    val next = start.applied(101, "h101", "h100",
      Seq(tx("t1", Seq(a0.boxId), Seq(a1)), tx("t2", Seq(a1.boxId), Seq(a2))), shapes, 100)
    next.pool(id(1)).map(_.boxId) shouldBe Some(a2.boxId)
    next.boxes.size shouldBe 1
    val undone = next.rolledBackTo(100).get
    undone.boxes shouldBe start.boxes
    undone.byNft shouldBe start.byNft
    (undone.height, undone.headerId) shouldBe (100 -> "h100")
  }

  it should "refuse a block that does not follow the tracked one" in {
    val start = seeded(100, pool(1, 100))
    an[IllegalArgumentException] should be thrownBy start.applied(101, "h101", "other", Seq.empty, shapes, 100)
    an[IllegalArgumentException] should be thrownBy start.applied(102, "h102", "h100", Seq.empty, shapes, 100)
  }

  "Rolling back" should "restore the state before each undone block, and nothing below the retained history" in {
    val boxes = (0 to TrackedPools.MaxHistory + 2).map(i => pool(1, 100 + i))
    val states = boxes.indices.tail.scanLeft(seeded(100, boxes.head)) { (state, i) =>
      state.applied(100 + i, s"h${100 + i}", s"h${99 + i}", Seq(tx(s"t$i", Seq(boxes(i - 1).boxId), Seq(boxes(i)))),
        shapes, 100)
    }
    val last = states.last
    last.history.size shouldBe TrackedPools.MaxHistory
    last.base shouldBe last.height - TrackedPools.MaxHistory
    last.rolledBackTo(last.base).map(_.boxes) shouldBe Some(states(states.size - 1 - TrackedPools.MaxHistory).boxes)
    last.rolledBackTo(last.height - 3).map(_.headerId) shouldBe Some(s"h${last.height - 3}")
    last.rolledBackTo(last.base - 1) shouldBe None
    withClue("the ids it can match a canonical header against: the tip, every retained block and the base: ") {
      last.idAt(last.base) shouldBe Some(s"h${last.base}")
      last.idAt(last.base - 1) shouldBe None
    }
  }

  "An NFT two boxes carry" should "not be served until one of them leaves" in {
    val (a0, twin) = (pool(1, 100), pool(1, 200))
    val both = seeded(100, a0).applied(101, "h101", "h100", Seq(tx("t1", Seq.empty, Seq(twin))), shapes, 100)
    both.pool(id(1)) shouldBe None
    both.pools.toSeq shouldBe empty
    both.byNft(id(1)) shouldBe Set(a0.boxId, twin.boxId)
    val one = both.applied(102, "h102", "h101", Seq(tx("t2", Seq(twin.boxId), Seq.empty)), shapes, 100)
    one.pool(id(1)).map(_.boxId) shouldBe Some(a0.boxId)
  }

  "The cap" should "leave a new pool out and say so, but never refuse a pool's successor" in {
    val (a0, a1, b0) = (pool(1, 100), pool(1, 101), pool(2, 100))
    val full = seeded(100, a0).applied(101, "h101", "h100",
      Seq(tx("t1", Seq(a0.boxId), Seq(a1)), tx("t2", Seq.empty, Seq(b0))), shapes, cap = 1)
    full.pool(id(1)).map(_.boxId) shouldBe Some(a1.boxId)
    full.pool(id(2)) shouldBe None
    full.cut shouldBe true
  }

  "Pool shapes" should "take LithosDex pools of both kinds with the deployment their ids build" in {
    nodeContext.getClient.execute { ctx =>
      val canonical = canonicalPool(ctx, 1000 * Erg, 100000000L)
      shapes.read(canonical, 5).map(_.deployment).collect { case PoolDeployment.LithosDexNative(c) =>
        c.liquidityPool.ergoTreeHex } shouldBe Some(canonical.ergoTree)
      shapes.read(litRsnPool, 5).map(_.deployment).collect { case PoolDeployment.LithosDexToken(c) =>
        c.poolNFT.toString } shouldBe Some(litRsnPool.assets.head.tokenId)
      withClue("a box at the ERG pool template whose own ids do not build its tree: ") {
        val swapped = canonical.copy(assets = Seq(canonical.assets(1), canonical.assets.head) ++ canonical.assets.drop(2))
        shapes.read(swapped, 5) shouldBe None
      }
    }
  }
}
