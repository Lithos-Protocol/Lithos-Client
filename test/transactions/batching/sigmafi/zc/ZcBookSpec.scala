package transactions.batching.sigmafi.zc

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.ZcKind

/**
 * The book as blocks change it. These pin the spam rules (a state box joins only from its token's previous state
 * box or its mint; past the cap a box joins only by evicting one holding less ERG), that every change undoes
 * exactly, that origins and scripts come from the transactions applied, and that the mempool overlay is built
 * only on the book's own block and reuses what it parsed before.
 */
class ZcBookSpec extends AnyFlatSpec with Matchers {

  import ZcFixtures._

  private val h0 = 1700000
  private val noneKnown: String => Boolean = _ => false

  private def apply(book: ZcBook, txs: ZcTx*): ZcBook = applyCapped(book, 1000, txs: _*)

  private def applyCapped(book: ZcBook, cap: Int, txs: ZcTx*): ZcBook =
    book.applied(book.height + 1, blockId(book.height + 1), book.headerId, txs, cap, noneKnown)._1

  /** An order posted at h0+1 and filled at h0+2 into a bond holding its two units. */
  private def filled(): ZcBook = {
    val posted = apply(emptyBook(h0), zcTx("aa" * 32, Seq(id(900)), Seq(order(id(1)))))
    apply(posted, zcTx("ab" * 32, Seq(id(1), id(901)), Seq(wallet(id(2), assets = Seq(id(1) -> 1L)), bond(id(3), id(1)))))
  }

  "Applying blocks" should "track an order, then the bond its fill mints, with the asset's origin" in {
    val b = filled()
    b.entry(id(1)) shouldBe None
    b.stateOf(id(1)).map(_.boxId) shouldBe Some(id(3))
    b.origins.get(id(1)).map(o => (o.kind, o.txId, o.height)) shouldBe Some((ZcKind.Order, "ab" * 32, Some(h0 + 2)))
    b.namedBy(hashOf(party(1))).map(_.boxId) shouldBe Seq(id(3))
  }

  it should "refuse a hand-made bond, whose units neither moved from a state box nor were minted" in {
    val forged = apply(emptyBook(h0), zcTx("ac" * 32, Seq(id(902)), Seq(bond(id(4), id(77)))))
    forged.entry(id(4)) shouldBe None
    // Minted by a wallet box rather than a tracked order: still hand-made
    val minted = apply(emptyBook(h0), zcTx("ad" * 32, Seq(id(77)), Seq(bond(id(5), id(77)))))
    minted.entry(id(5)) shouldBe None
  }

  it should "follow a bond into its vault, then drop the origin once the vault is claimed" in {
    val repaid = apply(filled(), zcTx("ae" * 32, Seq(id(3)), Seq(settlement(id(6), id(1), id(3)))))
    repaid.stateOf(id(1)).map(_.boxId) shouldBe Some(id(6))
    repaid.origins.contains(id(1)) shouldBe true
    val claimed = apply(repaid, zcTx("af" * 32, Seq(id(6), id(2)), Seq(wallet(id(7)))))
    claimed.boxes shouldBe empty
    claimed.origins shouldBe empty
    // Undoing the claim brings both back
    val undone = claimed.rolledBackTo(repaid.height).get
    undone.stateOf(id(1)).map(_.boxId) shouldBe Some(id(6))
    undone.origins.keySet shouldBe Set(id(1))
  }

  it should "drop a bond and its origin when extinguished directly, and restore both on rollback" in {
    val live = filled()
    val extinguished = apply(live, zcTx("b9" * 32, Seq(id(3), id(2)), Seq(wallet(id(8)))))
    extinguished.boxes shouldBe empty
    extinguished.origins shouldBe empty
    val undone = extinguished.rolledBackTo(live.height).get
    undone.stateOf(id(1)).map(_.boxId) shouldBe Some(id(3))
    undone.origins.keySet shouldBe Set(id(1))
  }

  it should "undo every block back to the one asked for, and refuse below its history" in {
    val b = filled()
    val back = b.rolledBackTo(h0).get
    (back.height, back.headerId, back.boxes, back.origins) shouldBe ((h0, blockId(h0), Map.empty, Map.empty))
    b.rolledBackTo(h0 + 1).get.entry(id(1)).map(_.boxId) shouldBe Some(id(1))
    b.rolledBackTo(h0 - 1) shouldBe None
    b.idAt(h0) shouldBe Some(blockId(h0))
    b.idAt(h0 + 2) shouldBe Some(b.headerId)
  }

  it should "refuse a block that does not follow the book" in {
    an[IllegalArgumentException] should be thrownBy emptyBook(h0).applied(h0 + 2, blockId(h0 + 2), blockId(h0 + 1),
      Seq.empty, 1000, noneKnown)
    an[IllegalArgumentException] should be thrownBy emptyBook(h0).applied(h0 + 1, blockId(h0 + 1), blockId(h0, 1),
      Seq.empty, 1000, noneKnown)
  }

  "A full book" should "admit a box only in place of one holding less ERG, and undo the eviction" in {
    val full = applyCapped(emptyBook(h0), 2, zcTx("b0" * 32, Seq(id(900)),
      Seq(order(id(10), value = 2 * Erg), order(id(11), value = 5 * Erg))))
    full.cut shouldBe false
    val poorer = applyCapped(full, 2, zcTx("b1" * 32, Seq(id(901)), Seq(order(id(12), value = Erg))))
    poorer.boxes.keySet shouldBe Set(id(10), id(11))
    poorer.cut shouldBe true
    val richer = applyCapped(full, 2, zcTx("b2" * 32, Seq(id(902)), Seq(order(id(13), value = 3 * Erg))))
    richer.boxes.keySet shouldBe Set(id(11), id(13))
    richer.byValue.map(_._2) shouldBe Set(id(11), id(13))
    richer.rolledBackTo(full.height).get.boxes.keySet shouldBe Set(id(10), id(11))
  }

  it should "keep fewer blocks of history when they hold more boxes than the cap" in {
    var b = applyCapped(emptyBook(h0), 4, zcTx("c0" * 32, Seq(id(900)), (20 until 24).map(i => order(id(i)))))
    b = applyCapped(b, 4, zcTx("c1" * 32, (20 until 23).map(id), Seq.empty))
    b = applyCapped(b, 4, zcTx("c2" * 32, Seq(id(23)), Seq(order(id(24)))))
    b = applyCapped(b, 4, zcTx("c3" * 32, Seq(id(24)), Seq.empty))
    // Five boxes held for undoing against a cap of four: the oldest blocks go, the newest stays
    b.history.map(_.removed.size).sum should be <= 4
    b.history.last.height shouldBe b.height
  }

  "Script disclosure" should "find a named party in variable 64, or among the transaction's trees" in {
    val borrower = party(1)
    val (_, viaVar) = emptyBook(h0).applied(h0 + 1, blockId(h0 + 1), blockId(h0),
      Seq(zcTx("d0" * 32, Seq(id(900)), Seq(order(id(30))), disclose = Seq(borrower))), 1000, noneKnown)
    viaVar shouldBe Map(hashOf(borrower) -> borrower)
    val (_, viaOutput) = emptyBook(h0).applied(h0 + 1, blockId(h0 + 1), blockId(h0),
      Seq(zcTx("d1" * 32, Seq(id(900)), Seq(order(id(31)), wallet(id(32), n = 1)))), 1000, noneKnown)
    viaOutput shouldBe Map(hashOf(borrower) -> borrower)
    val (_, hidden) = emptyBook(h0).applied(h0 + 1, blockId(h0 + 1), blockId(h0),
      Seq(zcTx("d2" * 32, Seq(id(900)), Seq(order(id(33))))), 1000, noneKnown)
    hidden shouldBe empty
    val (_, alreadyKnown) = emptyBook(h0).applied(h0 + 1, blockId(h0 + 1), blockId(h0),
      Seq(zcTx("d3" * 32, Seq(id(900)), Seq(order(id(34))), disclose = Seq(borrower))), 1000, _ => true)
    alreadyKnown shouldBe empty
  }

  "The oracle" should "be read when one box carries the NFT, followed through refreshes and undone" in {
    val b1 = apply(emptyBook(h0), zcTx("e0" * 32, Seq(id(900)), Seq(oracleBox(id(40), 400000000L))))
    b1.oracle.map(_.price) shouldBe Some(400000000L)
    val b2 = apply(b1, zcTx("e1" * 32, Seq(id(40)), Seq(oracleBox(id(41), 410000000L))))
    b2.oracle.map(o => o.box.boxId -> o.price) shouldBe Some(id(41) -> 410000000L)
    b2.rolledBackTo(b1.height).get.oracle.map(_.box.boxId) shouldBe Some(id(40))
    // Two boxes carrying the NFT: no oracle at all
    apply(b1, zcTx("e2" * 32, Seq(id(900)), Seq(oracleBox(id(42))))).oracle shouldBe None
  }

  "The mempool overlay" should "drop spent boxes and add unspent new ones, only on the book's own block" in {
    val b = apply(emptyBook(h0), zcTx("f0" * 32, Seq(id(900)), Seq(order(id(50)), offer(id(51)))))
    val spend = nodeTx("f1" * 32, Seq(id(50)), Seq(wallet(id(52))))
    val post = nodeTx("f2" * 32, Seq(id(901)), Seq(saleOrder(id(53), id(77), 1L)))
    val chained = nodeTx("f3" * 32, Seq(id(902)), Seq(offer(id(54))))
    val spendsChained = nodeTx("f4" * 32, Seq(id(54)), Seq(offer(id(55), predecessor = Some(id(54)))))
    ZcMempool.over(b, snapshot(blockId(h0), spend), None) shouldBe None
    val m = ZcMempool.over(b, snapshot(b.headerId, spend, post, chained, spendsChained), None).get
    m.spentBy shouldBe Map(id(50) -> spend.id)
    m.pending.keySet shouldBe Set(id(53), id(55))
    val view = ZcView(b, Some(m), 1L)
    view.open.map { case (r, pending) => r.boxId -> pending }.toSet shouldBe Set(id(51) -> false, id(53) -> true, id(55) -> true)
    view.record(id(50)) shouldBe None
    view.spentBy(id(50)) shouldBe Some(spend.id)
  }

  it should "see a mint of a confirmed order, flag an oracle refresh, and reuse what it parsed" in {
    val b = apply(emptyBook(h0), zcTx("10" * 32, Seq(id(900)), Seq(order(id(60)), oracleBox(id(61)))))
    val fill = nodeTx("11" * 32, Seq(id(60), id(901)), Seq(wallet(id(62), assets = Seq(id(60) -> 1L)), bond(id(63), id(60))))
    val refresh = nodeTx("12" * 32, Seq(id(61)), Seq(oracleBox(id(64))))
    val forged = nodeTx("13" * 32, Seq(id(902)), Seq(bond(id(65), id(78))))
    val first = ZcMempool.over(b, snapshot(b.headerId, fill, refresh, forged), None).get
    first.pending.keySet shouldBe Set(id(63))
    first.origins.get(id(60)).map(o => (o.kind, o.height)) shouldBe Some(ZcKind.Order -> None)
    first.oracleSpentBy shouldBe Some(refresh.id)
    val second = ZcMempool.over(b, snapshot(b.headerId, fill, refresh), Some(first)).get
    (second.pending(id(63)).record eq first.pending(id(63)).record) shouldBe true
    ZcView(b, Some(second), 0L).refreshPending shouldBe true
  }

  "The view's fingerprint" should "change with the block or the mempool's effect, and only then" in {
    val b = apply(emptyBook(h0), zcTx("20" * 32, Seq(id(900)), Seq(order(id(70)))))
    val unrelated = nodeTx("21" * 32, Seq(id(903)), Seq(wallet(id(71))))
    val spend = nodeTx("22" * 32, Seq(id(70)), Seq(wallet(id(72))))
    val quiet = ZcView(b, ZcMempool.over(b, snapshot(b.headerId), None), 0L).fingerprint
    ZcView(b, ZcMempool.over(b, snapshot(b.headerId, unrelated), None), 0L).fingerprint shouldBe quiet
    ZcView(b, ZcMempool.over(b, snapshot(b.headerId, spend), None), 0L).fingerprint should not be quiet
    ZcView(apply(b), None, 0L).fingerprint should not be quiet
  }
}
