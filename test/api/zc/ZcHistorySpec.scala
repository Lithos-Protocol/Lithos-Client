package api.zc

import configs.ZcConfig
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import sigmafi.zc.ZcMarket
import support.LDNodeFixtures
import transactions.batching.sigmafi.zc.{ZcPricing, ZcTracker}
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext
import scala.util.Success

/**
 * Wallet history: each ZC transaction shape read from its input and output boxes into what it did for this wallet, the
 * cost basis and realized P&L built from those events, and the reader reading only marked transactions, once.
 */
class ZcHistorySpec extends AnyFlatSpec with Matchers with Eventually {

  import transactions.batching.sigmafi.zc.ZcFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  private val Me = 9
  private val wallet = Set(hashOf(party(Me)))
  private val order1 = id(1)
  private val bond1 = id(2)

  private def indexedTx(txId: String, ins: Seq[NodeBox], outs: Seq[NodeBox], height: Int = 1700100): IndexedTransaction =
    IndexedTransaction(txId, ins.map(LDNodeFixtures.indexed(_, height - 1)), Seq.empty,
      outs.zipWithIndex.map { case (b, i) => LDNodeFixtures.indexed(b.copy(transactionId = txId, index = i), height) },
      height, 10, "ff" * 32, 0L, 0, 1L, 100)

  private def mine(boxId: String, value: Long, assets: Seq[(String, Long)] = Seq.empty, regs: NodeRegisters = NodeRegisters.empty) =
    box(boxId, party(Me), value, assets, regs)

  /** A fill of `order1` by this wallet: the unit to it, the bond, the principal to party 1, tagged. */
  private def fill: IndexedTransaction = indexedTx(id(100),
    Seq(order(order1), mine(id(50), 5 * Erg)),
    Seq(mine(id(51), 1000000L, Seq(order1 -> 1L)), bond(bond1, order1), box(id(52), party(1), Erg + 5000000L, Seq.empty,
      regs(bytes(order1)))))

  "A wallet fill" should "be lent by this wallet at the bond's principal, and borrowed by the order's borrower" in {
    val events = ZcHistory.classify(fill, wallet)
    events.map(_.kind) shouldBe Seq("lent")
    events.head.token shouldBe Some(order1)
    events.head.amount shouldBe BigInt(Erg)
    events.head.market shouldBe Some(ZcMarket.Erg)
    ZcHistory.classify(fill, Set(hashOf(party(1)))).map(_.kind) shouldBe Seq("borrowed")
  }

  "A SigUSD lot of nanoERG alone" should "be sold and bought when its seller is paid SigUSD, and returned otherwise" in {
    val sig = sigmafi.zc.ZcContracts.SigUsdId
    val lot = box(id(70), tree(sigmafi.zc.ZcKind.LotAuction, ZcMarket.SigUsd), 50000000L, Seq.empty,
      regs(bytes(hashOf(party(3))), longs(2000L, 1000L, 1700000L, 1700720L, 1702880L, 2000000L)))
    val sold = indexedTx(id(103), Seq(lot, mine(id(71), Erg, Seq(sig -> 2000L))),
      Seq(box(id(72), party(3), 1000000L, Seq(sig -> 1500L), regs(bytes(id(70)))),
        mine(id(73), Erg + 48000000L, Seq(sig -> 500L))))
    ZcHistory.classify(sold, Set(hashOf(party(3)))).map(_.kind) shouldBe Seq("lotSold")
    ZcHistory.classify(sold, wallet).map(_.kind) shouldBe Seq("lotBought")
    ZcHistory.classify(sold, wallet).head.amount shouldBe BigInt(1500L)
    val returned = indexedTx(id(104), Seq(lot), Seq(box(id(74), party(3), 48000000L, Seq.empty, regs(bytes(id(70))))))
    ZcHistory.classify(returned, Set(hashOf(party(3)))).map(_.kind) shouldBe Seq("returned")
  }

  "An ask purchase" should "be bought by the buyer and sold by the seller, at the payout" in {
    val ask = saleOrder(id(60), order1, 1L, seller = 3)
    val payout = box(id(62), party(3), 2 * Erg, Seq.empty, regs(bytes(id(60)), long(1L)))
    val tx = indexedTx(id(101), Seq(ask, mine(id(61), 3 * Erg)), Seq(payout, mine(id(63), Erg, Seq(order1 -> 1L))))
    val bought = ZcHistory.classify(tx, wallet)
    bought.map(_.kind) shouldBe Seq("bought")
    bought.head.amount shouldBe BigInt(2 * Erg)
    ZcHistory.classify(tx, Set(hashOf(party(3)))).map(_.kind) shouldBe Seq("sold")
  }

  "A repayment and a claim" should "be repaid by the borrower, then claimed by the unit's holder for the settlement" in {
    val repay = indexedTx(id(102), Seq(bond(bond1, order1, borrower = Me), mine(id(70), 3 * Erg)),
      Seq(settlement(id(71), order1, bond1, value = 2050000000L), mine(id(72), 3 * Erg, regs = regs(bytes(bond1)))))
    ZcHistory.classify(repay, wallet).map(_.kind) shouldBe Seq("repaid")
    val claim = indexedTx(id(103), Seq(settlement(id(71), order1, bond1, value = 2050000000L),
      mine(id(51), 1000000L, Seq(order1 -> 1L))), Seq(mine(id(73), 2050000000L)))
    val claimed = ZcHistory.classify(claim, wallet)
    claimed.map(_.kind) shouldBe Seq("claimed")
    claimed.head.amount shouldBe BigInt(2050000000L - 1000000L)
  }

  "An extinguished bond" should "be classified for the borrower, clear cost without lending P&L, and be found via its bond box" in {
    val ext = indexedTx(id(120), Seq(bond(bond1, order1, borrower = Me), mine(id(51), 1000000L, Seq(order1 -> 1L))),
      Seq(mine(id(74), 3050000000L)))
    val events = ZcHistory.classify(ext, wallet)
    events.map(_.kind) shouldBe Seq("extinguished")
    events.head.token shouldBe Some(order1)
    events.head.units shouldBe 1L
    events.head.refs.get("bondBoxId") shouldBe Some(bond1)
    ZcHistory.classify(ext, Set(hashOf(party(3)))) shouldBe empty

    // Cost basis from buying back the bond clears without realizing lending P&L
    def e(kind: String, token: String, units: Long, amount: Long, refs: (String, String)*) =
      ZcEvent("tx-" + kind, 1, 0, kind, Some(ZcMarket.Erg), Some(token), units, amount, refs.toMap)
    val shares = id(91)
    val l = ZcHistory.ledger(Seq(
      e("bought", shares, ZcPricing.ShareCount, 1500000000L),
      e("claimed", shares, ZcPricing.ShareCount, 0L, "bondId" -> order1, "unfractionalized" -> "true"),
      e("extinguished", order1, 1L, 2 * Erg, "assetId" -> order1, "bondBoxId" -> bond1)), "ready")
    l.costs shouldBe empty
    l.realized shouldBe empty

    // Reader detects an unmarked extinguish tx (no ZC outputs, no R4 tags) via the borrowed bond box remembered on fill
    val c = new ScriptedChain
    c.extend(1700060, 1700120)
    c.indexed = 1700120
    c.scans(LDNodeFixtures.indexed(oracleBox(id(99)), 1700050))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(1700120))
    val borrowFill = indexedTx(id(121),
      Seq(order(order1, borrower = Me), box(id(122), party(2), 5 * Erg, Seq.empty, NodeRegisters.empty)),
      Seq(box(id(123), party(2), 1000000L, Seq(order1 -> 1L), NodeRegisters.empty), bond(bond1, order1, borrower = Me),
        mine(id(124), Erg + 5000000L, regs = regs(bytes(order1)))), height = 1700095)
    val wtFill = WalletTransaction(borrowFill.id, Seq(NodeInput(order1, NodeSpendingProof("", Map.empty))), Seq.empty,
      borrowFill.outputs.map(_.box), 1700095, 25, Seq.empty)
    val wtExt = WalletTransaction(ext.id,
      Seq(NodeInput(bond1, NodeSpendingProof("", Map.empty)), NodeInput(id(51), NodeSpendingProof("", Map.empty))),
      Seq.empty, ext.outputs.map(_.box), 1700105, 15, Seq.empty)
    when(c.api.walletTransactions(any[Option[Int]], any[Option[Int]], any[Option[Int]], any[Option[Int]]))
      .thenReturn(Success(Seq(wtFill, wtExt)))
    when(c.api.indexedTransactionById(borrowFill.id)).thenReturn(Success(Some(borrowFill)))
    when(c.api.indexedTransactionById(ext.id)).thenReturn(Success(Some(ext)))
    val hist = new ZcHistory(() => c.api, () => wallet, t, 1700080)
    hist.all().map(_.kind) shouldBe Seq("extinguished", "borrowed")
  }

  "A posted order" should "be posted, and its cancel returned" in {
    val post = indexedTx(id(104), Seq(mine(id(80), 5 * Erg)), Seq(order(id(81), borrower = Me), mine(id(82), Erg)))
    ZcHistory.classify(post, wallet).map(_.kind) shouldBe Seq("posted")
    val cancel = indexedTx(id(105), Seq(order(id(81), borrower = Me), mine(id(82), Erg)), Seq(mine(id(83), 4 * Erg)))
    ZcHistory.classify(cancel, wallet).map(_.kind) shouldBe Seq("returned")
  }

  "The ledger" should "carry a unit's cost to its shares and realize a sale against the average cost" in {
    def e(kind: String, token: String, units: Long, amount: Long, refs: (String, String)*) =
      ZcEvent("tx-" + kind, 1, 0, kind, Some(ZcMarket.Erg), Some(token), units, amount, refs.toMap)
    val shares = id(90)
    val l = ZcHistory.ledger(Seq(e("lent", order1, 1L, Erg), e("sharesMinted", shares, ZcPricing.ShareCount, 0L, "bondId" -> order1),
      e("sold", shares, ZcPricing.ShareCount / 2, 600000000L)), "ready")
    l.costs.get(order1) shouldBe None
    l.costs(shares).paid shouldBe BigInt(Erg / 2)
    l.costs(shares).units shouldBe ZcPricing.ShareCount / 2
    l.realized("ERG") shouldBe BigInt(100000000L)
  }

  "The reader" should "look closer only at marked transactions, once each, and list them newest first" in {
    val c = new ScriptedChain
    c.extend(1700060, 1700120)
    c.indexed = 1700120
    c.scans(LDNodeFixtures.indexed(oracleBox(id(99)), 1700050))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(1700120))
    val plain = WalletTransaction(id(110), Seq(NodeInput(id(111), NodeSpendingProof("", Map.empty))), Seq.empty,
      Seq(mine(id(112), Erg)), 1700090, 30, Seq.empty)
    val marked = WalletTransaction(fill.id, Seq(NodeInput(id(50), NodeSpendingProof("", Map.empty))), Seq.empty,
      fill.outputs.map(_.box), 1700100, 20, Seq.empty)
    when(c.api.walletTransactions(any[Option[Int]], any[Option[Int]], any[Option[Int]], any[Option[Int]]))
      .thenReturn(Success(Seq(plain, marked)))
    when(c.api.indexedTransactionById(anyString())).thenReturn(Success(Some(fill)))
    val h = new ZcHistory(() => c.api, () => wallet, t, 1700080)
    h.all().map(_.kind) shouldBe Seq("lent")
    h.all().map(_.kind) shouldBe Seq("lent")
    verify(c.api, times(1)).indexedTransactionById(anyString())
    h.ledger().costs(order1).paid shouldBe BigInt(Erg)
    new ZcHistory(() => c.api, () => wallet, t, 0).status shouldBe "not-configured"

    // Shallow reorg: the same tx re-mines at a different height within the Reorg window -> height is reconciled without re-fetching
    c.extend(1700121, 1700121)
    c.indexed = 1700121
    t.follow(1700121, c.header(1700121).id)
    eventually(t.view.map(_.book.height) shouldBe Some(1700121))
    val remined = marked.copy(inclusionHeight = 1700118)
    when(c.api.walletTransactions(any[Option[Int]], any[Option[Int]], any[Option[Int]], any[Option[Int]]))
      .thenReturn(Success(Seq(plain, remined)))
    h.all().map(e => e.kind -> e.height) shouldBe Seq("lent" -> 1700118)
    verify(c.api, times(1)).indexedTransactionById(anyString())
  }
}
