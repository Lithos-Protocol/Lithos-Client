package transactions.batching.sigmafi.zc

import node.model.NodeRegisters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}

/**
 * The ZC readers on node JSON: each kind read with its terms, every box sharing its contract's one tree string,
 * and boxes a stranger can make under the same tree with the wrong shape turned away.
 */
class ZcBoxesSpec extends AnyFlatSpec with Matchers {

  import ZcFixtures._

  private val asset = id(0xa1)
  private val share = id(0xb1)
  private val token = id(0xc1)

  "The readers" should "read an order's terms, borrower and rule" in {
    val o = ZcBoxes.read(order(id(1))).collect { case o: ZcOrder => o }.get
    (o.market, o.repayment, o.principal, o.expiry, o.mode) shouldBe ((ZcMarket.Erg, 2 * Erg, Erg, 1750000L, 0L))
    o.borrowerHash shouldBe hashOf(party(1))
    o.liqHash shouldBe expr(ZcExpression.MaturityLiquidation)
    (o.guardHash, o.pricingHash, o.priced) shouldBe ((None, None, false))
  }

  it should "read a priced order's pricing hash, guard and payload" in {
    val o = ZcBoxes.read(pricedOrder(id(2))).collect { case o: ZcOrder => o }.get
    o.priced shouldBe true
    o.pricingHash shouldBe Some(expr(ZcExpression.YieldOrderPricing))
    o.guardHash shouldBe Some(expr(ZcExpression.FillGuard))
    o.pricingTerms shouldBe Seq(1700000L, 800L, 1750000L, 900L)
  }

  it should "read a bond, a settlement, an offer, a sale order, a lot, a share request and vault, and wraps" in {
    val b = ZcBoxes.read(bond(id(3), asset, collateral = Seq(token -> 5L))).collect { case b: ZcBond => b }.get
    (b.asset, b.feeReserve, b.start, b.maturity, b.collateral.map(_.tokenId)) shouldBe ((asset, 50000000L, 1700000L, 1800000L, Seq(token)))
    val s = ZcBoxes.read(settlement(id(4), asset, id(3), liquidated = true)).collect { case s: ZcSettlement => s }.get
    (s.asset, s.bondBoxId, s.liquidated) shouldBe ((asset, id(3), true))
    val f = ZcBoxes.read(offer(id(5), predecessor = Some(id(50)))).collect { case f: ZcOffer => f }.get
    (f.policyHash, f.predecessor, f.curve, f.capital) shouldBe
      ((expr(ZcExpression.CollateralPolicy), Some(id(50)), Seq(820L -> 21600L, 940L -> 131400L), 10 * Erg))
    val a = ZcBoxes.read(saleOrder(id(6), asset, 1L, predecessor = Some(id(60)))).collect { case a: ZcAsk => a }.get
    (a.token.tokenId, a.priced, a.predecessor, a.sellerHash) shouldBe ((asset, false, Some(id(60)), hashOf(party(3))))
    ZcBoxes.read(lot(id(7), Seq(token -> 9L))).collect { case l: ZcLot => l.expiry } shouldBe Some(1702880L)
    val r = ZcBoxes.read(shareRequest(id(8), asset)).collect { case r: ZcShareRequest => r }.get
    (r.bondAsset, r.shareId, r.dutch, r.terms.size) shouldBe ((asset, id(8), Some(ZcMarket.Erg), 8))
    val v = ZcBoxes.read(shareVault(id(9), share, asset)).collect { case v: ZcShareVault => v }.get
    (v.shareId, v.bondAsset, v.shares, v.holding, v.state) shouldBe ((share, asset, 100000000L, true, Some(0.toByte)))
    ZcBoxes.read(wrapRequest(id(10), token)).collect { case w: ZcWrapRequest => w.receiptId } shouldBe Some(id(10))
    ZcBoxes.read(wrapVault(id(11), id(10), token)).collect { case w: ZcWrapVault => w.wrapped.tokenId } shouldBe Some(token)
  }

  it should "read the oracle pool box, and nothing from a box without its NFT" in {
    ZcBoxes.oracle(oracleBox(id(12), 400000000L)).map(o => o.price -> o.epoch) shouldBe Some(400000000L -> 3000)
    ZcBoxes.oracle(wallet(id(13))) shouldBe None
  }

  it should "keep the contract's own tree string, not the box's copy" in {
    val copy = new String(tree(ZcKind.Order).toCharArray)
    val r = ZcBoxes.read(order(id(14)).copy(ergoTree = copy)).get
    (r.box.ergoTree eq ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).ergoTreeHex) shouldBe true
  }

  "A box under a ZC tree" should "be turned away when a register has the wrong type or size" in {
    val o = order(id(20))
    // Borrower hash 31 bytes
    ZcBoxes.read(o.copy(additionalRegisters = NodeRegisters(o.additionalRegisters.values.updated("R6", bytes("ab" * 31).toHex)))) shouldBe None
    // Terms as one Long instead of a Coll[Long]
    ZcBoxes.read(o.copy(additionalRegisters = NodeRegisters(o.additionalRegisters.values.updated("R4", long(5L).toHex)))) shouldBe None
    // Six terms, one short of the order's seven
    ZcBoxes.read(order(id(21), terms = Seq(1L, 2L, 3L, 4L, 5L, 6L))) shouldBe None
    // No registers at all
    ZcBoxes.read(o.copy(additionalRegisters = NodeRegisters.empty)) shouldBe None
    // A register that is not valid hex
    ZcBoxes.read(o.copy(additionalRegisters = NodeRegisters(o.additionalRegisters.values.updated("R4", "zz")))) shouldBe None
  }

  it should "be turned away when its tokens break the contract's shape" in {
    // A bond holding one unit, not two
    ZcBoxes.read(bond(id(22), asset).copy(assets = Seq(node.model.NodeAsset(asset, 1L)))) shouldBe None
    // A bond whose R4 names another asset than the one it holds
    val b = bond(id(23), asset)
    ZcBoxes.read(b.copy(additionalRegisters = NodeRegisters(b.additionalRegisters.values.updated("R4", bytes(token).toHex)))) shouldBe None
    // A sale order holding two tokens
    ZcBoxes.read(saleOrder(id(24), asset, 1L).copy(assets = Seq(node.model.NodeAsset(asset, 1L), node.model.NodeAsset(token, 1L)))) shouldBe None
    // A wrap vault with registers
    ZcBoxes.read(wrapVault(id(25), id(10), token).copy(additionalRegisters = NodeRegisters(Map("R4" -> long(1L).toHex)))) shouldBe None
    // A share vault whose reserve is not N+1
    ZcBoxes.read(shareVault(id(26), share, asset).copy(assets = Seq(node.model.NodeAsset(share, 100000000L)))) shouldBe None
  }

  it should "not be read at all under a tree that is not one of the pins" in {
    ZcBoxes.read(order(id(27)).copy(ergoTree = party(1))) shouldBe None
  }
}
