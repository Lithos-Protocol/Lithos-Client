package transactions.batching.sigmafi.zc

import configs.ZcConfig
import node.model.NodeTransaction
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import state.messages.{BlockInfo, NodeSync}
import state.synchronization.CompleteMempool
import support.LDNodeFixtures
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext

/**
 * The ZC tracker against a scripted node. These pin what each block costs (nothing for a block extending the book,
 * one read per block of a gap, a scan only to seed or past a deep fork), that a fork is undone, that a scan keeps
 * the boxes holding most ERG when it holds more than the cap, and that the revision moves only with what a reader
 * would see.
 */
class ZcTrackerSpec extends AnyFlatSpec with Matchers with Eventually {

  import ZcFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  private val tip = 1908200

  private def chain(scanned: node.model.NodeBox*): ScriptedChain = {
    val c = new ScriptedChain
    c.extend(tip - 40, tip)
    c.indexed = tip
    c.scans(scanned.map(b => LDNodeFixtures.indexed(b, tip - 3)): _*)
    c
  }

  private def tracker(c: ScriptedChain, cap: Int = 1000, enabled: Boolean = true, reseed: Int = ZcTracker.ReseedBlocks,
                      recovery: Int = ZcTracker.RecoveryBlocks,
                      scriptChars: Long = ZcTracker.MaxScriptChars): ZcTracker =
    new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled, cap), reseed, retryMs = 20L,
      recoveryBlocks = recovery, maxScriptChars = scriptChars)

  private def seeded(c: ScriptedChain, t: ZcTracker): ZcView = {
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(tip))
    t.view.get
  }

  /** The block the chain holds at `height`, carrying `txs`, as the sync would offer it. */
  private def offered(c: ScriptedChain, height: Int, txs: NodeTransaction*): BlockInfo = {
    c.block(height, txs: _*)
    val h = c.header(height)
    BlockInfo(h.id, height, txs.map(NodeSync.blockTx), h.parentId)
  }

  "The tracker" should "seed from one scan at the index's height" in {
    val c = chain(order(id(1)), offer(id(2)), oracleBox(id(3)))
    val view = seeded(c, tracker(c))
    view.book.boxes.keySet shouldBe Set(id(1), id(2))
    view.book.oracle.map(_.box.boxId) shouldBe Some(id(3))
    view.book.headerId shouldBe c.header(tip).id
    c.blockReads.get shouldBe 0
  }

  it should "apply a block extending its book with no node read" in {
    val c = chain(order(id(1)))
    val t = tracker(c)
    seeded(c, t)
    val (scans, reads, slices) = (c.scanPages.get, c.blockReads.get, c.sliceReads.get)
    c.extend(tip + 1, tip + 1)
    t.offer(offered(c, tip + 1, nodeTx("a0" * 32, Seq(id(1), id(900)),
      Seq(wallet(id(4), assets = Seq(id(1) -> 1L)), bond(id(5), id(1))))))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 1))
    t.view.get.book.stateOf(id(1)).map(_.boxId) shouldBe Some(id(5))
    t.view.get.origin(id(1)).map(_.txId) shouldBe Some("a0" * 32)
    (c.scanPages.get, c.blockReads.get, c.sliceReads.get) shouldBe ((scans, reads, slices))
  }

  it should "read the blocks of a gap one by one, then apply the offered block" in {
    val c = chain(order(id(1)))
    val t = tracker(c)
    seeded(c, t)
    val scans = c.scanPages.get
    c.extend(tip + 1, tip + 3)
    c.block(tip + 1, nodeTx("b0" * 32, Seq(id(900)), Seq(offer(id(6)))))
    c.block(tip + 2)
    t.offer(offered(c, tip + 3, nodeTx("b1" * 32, Seq(id(6)), Seq(wallet(id(7))))))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 3))
    t.view.get.book.boxes.keySet shouldBe Set(id(1))
    c.blockReads.get shouldBe 2
    c.scanPages.get shouldBe scans
  }

  it should "undo a fork to the block the chains share, with no scan" in {
    val c = chain(order(id(1)), order(id(8)))
    val t = tracker(c)
    seeded(c, t)
    c.extend(tip + 1, tip + 1)
    t.offer(offered(c, tip + 1, nodeTx("c0" * 32, Seq(id(1)), Seq(wallet(id(9))))))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 1))
    t.view.get.book.boxes.keySet shouldBe Set(id(8))
    val scans = c.scanPages.get
    // Another block at the same height that spends the other order instead
    c.extend(tip + 1, tip + 1, branch = 1)
    t.offer(offered(c, tip + 1, nodeTx("c1" * 32, Seq(id(8)), Seq(wallet(id(10))))))
    eventually(t.view.map(_.book.headerId) shouldBe Some(c.header(tip + 1).id))
    t.view.get.book.boxes.keySet shouldBe Set(id(1))
    c.scanPages.get shouldBe scans
  }

  it should "ignore a block older than anything it can check" in {
    val c = chain(order(id(1)))
    val t = tracker(c)
    val before = seeded(c, t)
    t.offer(BlockInfo(c.header(tip - 5).id, tip - 5, Seq.empty, c.header(tip - 6).id))
    t.poke()
    Thread.sleep(100)
    t.view.map(_.book) shouldBe Some(before.book)
  }

  it should "keep the boxes holding most ERG when a scan finds more than its cap" in {
    val c = chain(order(id(11), value = 2 * Erg), order(id(12), value = 9 * Erg), offer(id(13), value = 4 * Erg))
    val view = seeded(c, tracker(c, cap = 2))
    view.book.boxes.keySet shouldBe Set(id(12), id(13))
    view.book.cut shouldBe true
  }

  it should "move its revision with the mempool's effect and with blocks, and only then" in {
    val c = chain(order(id(1)))
    val t = tracker(c)
    val first = seeded(c, t).revision
    def observe(rev: Long, txs: NodeTransaction*): Unit =
      t.observe(CompleteMempool.Observation(rev, Some(snapshot(c.header(tip).id, txs: _*)), None))
    observe(1L, nodeTx("d0" * 32, Seq(id(900)), Seq(wallet(id(20)))))
    eventually(t.view.flatMap(_.mempool).isDefined shouldBe true)
    t.view.get.revision shouldBe first
    observe(2L, nodeTx("d1" * 32, Seq(id(1)), Seq(wallet(id(21)))))
    eventually(t.view.get.spentBy(id(1)) shouldBe Some("d1" * 32))
    t.view.get.revision shouldBe first + 1
    // The same observation again changes nothing
    observe(2L, nodeTx("d1" * 32, Seq(id(1)), Seq(wallet(id(21)))))
    t.poke()
    Thread.sleep(100)
    t.view.get.revision shouldBe first + 1
    c.extend(tip + 1, tip + 1)
    t.offer(offered(c, tip + 1))
    eventually(t.view.get.revision shouldBe first + 2)
    // Taken on the previous block, the observation no longer lays over the book
    t.view.get.mempool shouldBe None
  }

  it should "back off, not rescan in a loop, while the index holds another block than the sync committed" in {
    val c = chain(order(id(1)))
    val t = tracker(c)
    seeded(c, t)
    val perScan = c.scanPages.get
    // The sync commits a block at the tip that the node's index does not have
    t.offer(BlockInfo(c.headerId(tip, branch = 1), tip, Seq.empty, c.header(tip - 1).id))
    Thread.sleep(400)
    // Retries wait 20, 40, 80, 160 ms...: a handful of scans, where a loop would run hundreds
    c.scanPages.get / perScan should be <= 7
    t.view.map(_.book.headerId) shouldBe Some(c.header(tip).id)
  }

  "A check rescan" should "wait, without scanning, while the index is past the book, and run once they meet" in {
    val c = chain(order(id(1)))
    val t = tracker(c, reseed = 1)
    seeded(c, t)
    val scans = c.scanPages.get
    c.extend(tip + 1, tip + 2)
    c.indexed = tip + 2
    t.offer(offered(c, tip + 1))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 1))
    t.poke()
    Thread.sleep(100)
    c.scanPages.get shouldBe scans
    t.view.get.book.scannedAt shouldBe tip
    t.offer(offered(c, tip + 2))
    eventually(t.view.map(_.book.scannedAt) shouldBe Some(tip + 2))
    c.scanPages.get should be > scans
  }

  "A cut book" should "rescan once the spam is spent, so the boxes it left out return" in {
    val c = chain(order(id(41), value = 2 * Erg), order(id(42), value = 9 * Erg), offer(id(43), value = 4 * Erg))
    val t = tracker(c, cap = 2, recovery = 1)
    seeded(c, t).book.boxes.keySet shouldBe Set(id(42), id(43))
    c.extend(tip + 1, tip + 1)
    c.indexed = tip + 1
    c.scans(LDNodeFixtures.indexed(order(id(41), value = 2 * Erg), tip - 3))
    t.offer(offered(c, tip + 1, nodeTx("e0" * 32, Seq(id(42), id(43)), Seq(wallet(id(44))))))
    eventually(t.view.map(_.book.boxes.keySet) shouldBe Some(Set(id(41))))
    t.view.get.book.cut shouldBe false
  }

  "The script map" should "make room at its cap by dropping scripts no box names any more" in {
    val c = chain()
    val t = tracker(c, scriptChars = 150)
    seeded(c, t)
    c.extend(tip + 1, tip + 3)
    t.offer(offered(c, tip + 1, nodeTx("f0" * 32, Seq(id(900)), Seq(order(id(30), borrower = 1), order(id(31), borrower = 2)),
      disclose = Seq(party(1), party(2)))))
    eventually(t.script(hashOf(party(2))) shouldBe Some(party(2)))
    t.offer(offered(c, tip + 2, nodeTx("f1" * 32, Seq(id(30), id(31)), Seq(wallet(id(32))))))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 2))
    t.script(hashOf(party(1))) shouldBe Some(party(1))
    // Two 72-character scripts fill 150; a third is kept only by dropping the two nothing names now
    t.offer(offered(c, tip + 3, nodeTx("f2" * 32, Seq(id(901)), Seq(order(id(33), borrower = 3)), disclose = Seq(party(3)))))
    eventually(t.script(hashOf(party(3))) shouldBe Some(party(3)))
    t.script(hashOf(party(1))) shouldBe None
  }

  "A disabled tracker" should "do nothing at all" in {
    val c = chain(order(id(1)))
    val t = tracker(c, enabled = false)
    t.poke()
    t.offer(offered(c, tip))
    Thread.sleep(100)
    t.view shouldBe None
    c.scanPages.get shouldBe 0
  }
}
