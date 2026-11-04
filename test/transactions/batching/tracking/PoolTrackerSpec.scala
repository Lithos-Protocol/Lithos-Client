package transactions.batching.tracking

import node.model.NodeBox
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.arb.ArbFixtures

import scala.concurrent.duration._

/**
 * The pool tracker against a scripted node. These pin what each advance costs (a scan only to start, after a
 * fork deeper than the kept history, or past the catch-up limit; otherwise one block read per block), that a
 * fork is undone to the block the chains share, that a reader gets nothing for a block it was not told of or
 * that the node cannot serve, and that the periodic rescan replaces the kept state.
 */
class PoolTrackerSpec extends AnyFlatSpec with Matchers with Eventually {
  import ArbFixtures._
  import TrackerFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(50, Millis))

  private val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)
  private val tip = 1908200

  private def pool(n: Int, erg: Long): NodeBox =
    nodeContext.getClient.execute(ctx => nativePool(ctx, id(n), id(100 + n), erg * Erg, 1000000000L))

  /** A chain to `tip` whose index sits at `indexed`, scanning pools 1 and 2 at their first boxes. */
  private def chain(indexed: Int = tip): ScriptedChain = {
    val c = new ScriptedChain
    c.extend(tip - 40, tip)
    c.indexed = indexed
    c.scans(LDNodeFixtures.indexed(pool(1, 100), indexed - 3), LDNodeFixtures.indexed(pool(2, 100), indexed - 3))
    c
  }

  "The tracker" should "start from a scan at the index's height and read the blocks after it" in {
    val c = chain(indexed = tip - 2)
    c.block(tip - 1, tx("t1", Seq(pool(1, 100).boxId), Seq(pool(1, 101))))
    c.block(tip, tx("t2", Seq(pool(2, 100).boxId), Seq(pool(2, 102))))
    val state = warmed(c, tracker(c))
    (state.height, state.headerId) shouldBe (tip -> c.header(tip).id)
    state.pool(id(1)).map(b => b.boxId -> b.inclusionHeight) shouldBe Some(pool(1, 101).boxId -> (tip - 1))
    state.pool(id(2)).map(_.boxId) shouldBe Some(pool(2, 102).boxId)
    state.scannedAt shouldBe tip - 2
    c.blockReads.get shouldBe 2
  }

  it should "read one block and no scan for a block extending the one it holds" in {
    val c = chain()
    val t = tracker(c)
    warmed(c, t)
    val (scans, slices) = (c.scanPages.get, c.sliceReads.get)
    c.extend(tip + 1, tip + 1)
    c.block(tip + 1, tx("t", Seq(pool(1, 100).boxId), Seq(pool(1, 103))))
    t.follow(tip + 1, c.header(tip + 1).id)
    t.at(tip + 1, 10.seconds).flatMap(_.pool(id(1))).map(_.boxId) shouldBe Some(pool(1, 103).boxId)
    c.scanPages.get shouldBe scans
    c.blockReads.get shouldBe 1
    c.sliceReads.get shouldBe slices + 1
  }

  it should "undo a fork to the block the chains share and apply the new one, with no scan" in {
    val c = chain()
    val t = tracker(c)
    warmed(c, t)
    c.extend(tip + 1, tip + 1)
    c.block(tip + 1, tx("t", Seq(pool(1, 100).boxId), Seq(pool(1, 104))))
    t.follow(tip + 1, c.header(tip + 1).id)
    t.at(tip + 1, 10.seconds).flatMap(_.pool(id(1))).map(_.boxId) shouldBe Some(pool(1, 104).boxId)
    val scans = c.scanPages.get

    // Another block at the same height that moves pool 2 instead: pool 1 goes back to its box before the fork
    c.extend(tip + 1, tip + 1, branch = 1)
    c.block(tip + 1, tx("u", Seq(pool(2, 100).boxId), Seq(pool(2, 105))))
    t.follow(tip + 1, c.header(tip + 1).id)
    val forked = t.at(tip + 1, 10.seconds).get
    forked.headerId shouldBe c.header(tip + 1).id
    forked.pool(id(1)).map(_.boxId) shouldBe Some(pool(1, 100).boxId)
    forked.pool(id(2)).map(_.boxId) shouldBe Some(pool(2, 105).boxId)
    c.scanPages.get shouldBe scans
  }

  it should "rescan after a fork deeper than its history, and when too far behind to catch up" in {
    val c = chain()
    val t = tracker(c)
    warmed(c, t)
    val scans = c.scanPages.get
    // Every block from below the tracked one is replaced
    c.extend(tip - TrackedPools.MaxHistory - 2, tip + 1, branch = 2)
    c.indexed = tip + 1
    t.follow(tip + 1, c.header(tip + 1).id)
    t.at(tip + 1, 10.seconds).map(_.headerId) shouldBe Some(c.header(tip + 1).id)
    c.scanPages.get should be > scans

    val again = c.scanPages.get
    c.extend(tip + 2, tip + PoolTracker.MaxCatchUp + 3, branch = 2)
    c.indexed = tip + PoolTracker.MaxCatchUp + 3
    t.follow(c.tip, c.header(c.tip).id)
    t.at(c.tip, 10.seconds).map(_.height) shouldBe Some(c.tip)
    c.scanPages.get should be > again
  }

  "A reader" should "get nothing for a block it was not told of, and wait for one it was" in {
    val c = chain()
    val t = tracker(c)
    t.at(tip, 1.second) shouldBe None
    t.follow(tip, c.header(tip).id)
    t.at(tip - 1, 1.second) shouldBe None
    t.at(tip, 10.seconds).map(_.height) shouldBe Some(tip)
    t.latest.map(_.height) shouldBe Some(tip)
  }

  it should "get nothing while the node cannot serve the block, and the block once it can" in {
    val c = chain()
    c.failing = Some("node down")
    val t = tracker(c, retryMs = 100L)
    t.follow(tip, c.header(tip).id)
    t.at(tip, 2.seconds) shouldBe None
    c.failing = None
    eventually(t.at(tip, 1.second).map(_.height) shouldBe Some(tip))
  }

  it should "get nothing when the node's chain has another block at that height" in {
    val c = chain()
    val t = tracker(c)
    warmed(c, t)
    c.extend(tip + 1, tip + 1)
    t.follow(tip + 1, "77" * 32)
    t.at(tip + 1, 2.seconds) shouldBe None
    t.latest.map(_.height) shouldBe Some(tip)
  }

  "The periodic rescan" should "replace the kept state with what it finds" in {
    val c = chain()
    val t = tracker(c, reseedBlocks = 2)
    warmed(c, t)
    val scans = c.scanPages.get
    // A pool the blocks never showed: only a rescan finds it
    c.scans(LDNodeFixtures.indexed(pool(1, 100), tip - 3), LDNodeFixtures.indexed(pool(2, 100), tip - 3),
      LDNodeFixtures.indexed(pool(3, 100), tip + 1))
    c.extend(tip + 1, tip + 2)
    c.indexed = tip + 2
    t.follow(tip + 2, c.header(tip + 2).id)
    t.at(tip + 2, 10.seconds).map(_.height) shouldBe Some(tip + 2)
    eventually(t.latest.flatMap(_.pool(id(3))).map(_.boxId) shouldBe Some(pool(3, 100).boxId))
    c.scanPages.get should be > scans
    t.latest.map(_.scannedAt) shouldBe Some(tip + 2)
  }

  it should "keep the kept state when it fails" in {
    val c = chain()
    val t = tracker(c, reseedBlocks = 2)
    warmed(c, t)
    val scans = c.scanPages.get
    c.scansFailing = true
    c.scans(LDNodeFixtures.indexed(pool(1, 100), tip - 3), LDNodeFixtures.indexed(pool(2, 100), tip - 3),
      LDNodeFixtures.indexed(pool(3, 100), tip + 1))
    c.extend(tip + 1, tip + 2)
    c.indexed = tip + 2
    t.follow(tip + 2, c.header(tip + 2).id)
    t.at(tip + 2, 10.seconds).map(_.height) shouldBe Some(tip + 2)
    eventually(c.scanPages.get should be > scans)
    Thread.sleep(300)
    t.latest.map(s => (s.height, s.scannedAt, s.pool(id(3)))) shouldBe Some((tip + 2, tip, None))
  }
}
