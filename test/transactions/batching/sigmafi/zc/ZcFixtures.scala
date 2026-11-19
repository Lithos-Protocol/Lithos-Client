package transactions.batching.sigmafi.zc

import node.model._
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.synchronization.CompleteMempool

/**
 * ZC boxes as the node reports them, at the pinned trees with every register at the type its contract writes.
 * Box ids are whatever a spec names: the book keys boxes by the id the node gives, never recomputing it.
 */
object ZcFixtures {

  val Erg: Long = 1000000000L

  def id(n: Int): String = f"$n%064x"

  /** A P2PK tree for party `n`, and its hash as boxes name it. */
  def party(n: Int): String = "0008cd02" + f"$n%064x"
  def hashOf(tree: String): String = Hex.toHexString(Blake2b256.hash(Hex.decode(tree)))

  def tree(kind: ZcKind, market: ZcMarket = ZcMarket.Erg): String =
    ZcContracts.script(kind, if (kind.perMarket) Some(market) else None).ergoTreeHex

  def expr(e: ZcExpression, market: ZcMarket = ZcMarket.Erg): String =
    ZcContracts.expression(e, if (e.perMarket) Some(market) else None).hash

  // ─── register values ─────────────────────────────────────────────────────

  def longs(xs: Long*): ErgoValue[_] = ErgoValue.of(Colls.fromArray(xs.toArray), scalaLongType)
  def bytes(hex: String): ErgoValue[_] = ErgoValue.of(Colls.fromArray(Hex.decode(hex)), scalaByteType)
  def long(x: Long): ErgoValue[_] = ErgoValue.of(x)
  def int(x: Int): ErgoValue[_] = ErgoValue.of(x)
  def byte(x: Byte): ErgoValue[_] = ErgoValue.of(x)

  def bytesList(hexes: String*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(hexes.map(h => Colls.fromArray(Hex.decode(h))).toArray)(sigma.collRType(sigma.ByteType)),
      org.ergoplatform.appkit.ErgoType.collType(scalaByteType))

  def tokens(ts: (String, Long)*): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(ts.map { case (t, n) => Colls.fromArray(Hex.decode(t)) -> n }.toArray)(
      sigma.data.RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType)),
      org.ergoplatform.appkit.ErgoType.pairType(org.ergoplatform.appkit.ErgoType.collType(scalaByteType), scalaLongType))

  def regs(values: ErgoValue[_]*): NodeRegisters =
    NodeRegisters(values.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap)

  def box(boxId: String, ergoTree: String, value: Long, assets: Seq[(String, Long)], registers: NodeRegisters,
          txId: String = "ee" * 32, index: Int = 0): NodeBox =
    NodeBox(boxId, txId, value, index, 1, ergoTree, assets.map { case (t, n) => NodeAsset(t, n) }, registers)

  // ─── ZC boxes ────────────────────────────────────────────────────────────

  val metadata: Seq[String] = Seq("5a43532d426f6e642d556e6974", "7b7d", "30")

  /** An ERG `Order`: repayment 2 ERG for 1 ERG, fixed maturity, oracle-free maturity rule. */
  def order(boxId: String, value: Long = 3 * Erg, borrower: Int = 1, market: ZcMarket = ZcMarket.Erg,
            terms: Seq[Long] = Seq(2 * Erg, Erg, 0L, 1800000L, 1750000L, 5000000L, 0L)): NodeBox =
    box(boxId, tree(ZcKind.Order, market), value, Seq.empty,
      regs(longs(terms: _*), tokens(), bytes(hashOf(party(borrower))), bytes(expr(ZcExpression.MaturityLiquidation))))

  /** A `PricedOrder` with a two-point yield and a fill guard. */
  def pricedOrder(boxId: String, value: Long = 3 * Erg, borrower: Int = 1): NodeBox =
    box(boxId, tree(ZcKind.PricedOrder), value, Seq.empty,
      regs(longs(2 * Erg, Erg, 0L, 1800000L, 1750000L, 5000000L, 0L), tokens(), bytes(hashOf(party(borrower))),
        bytes(expr(ZcExpression.MaturityLiquidation)),
        bytesList(expr(ZcExpression.YieldOrderPricing), expr(ZcExpression.FillGuard)),
        longs(1700000L, 800L, 1750000L, 900L)))

  /** A live bond holding two units of `asset`, a 0.05 ERG reserve and its collateral. */
  def bond(boxId: String, asset: String, value: Long = 3 * Erg, borrower: Int = 1, collateral: Seq[(String, Long)] = Seq.empty): NodeBox =
    box(boxId, tree(ZcKind.Bond), value, (asset -> 2L) +: collateral,
      regs(bytes(asset), bytes(hashOf(party(borrower))), longs(2 * Erg, Erg, 0L, 1800000L, 1750000L, 5000000L, 0L),
        tokens(), bytes(expr(ZcExpression.MaturityLiquidation)), longs(50000000L, 1700000L, 1800000L)))

  /** A settlement at the bond vault for `asset`, naming the bond box that settled. */
  def settlement(boxId: String, asset: String, bondBox: String, value: Long = 2 * Erg, liquidated: Boolean = false): NodeBox =
    box(boxId, tree(ZcKind.BondVault), value, Seq(asset -> 2L), regs(bytes(bondBox), byte(if (liquidated) 1 else 0)))

  /** An ERG `LendOffer` with a two-point curve, continued from `predecessor` when given. */
  def offer(boxId: String, value: Long = 10 * Erg, owner: Int = 2, predecessor: Option[String] = None): NodeBox =
    box(boxId, tree(ZcKind.LendOffer), value, Seq.empty,
      regs(bytes(hashOf(party(owner))),
        longs(700L, 720L, 262800L, 5000000L, 50000000L, 15000L, 1760000L, 1L, 30L, 0L, 0L, 820L, 21600L, 940L, 131400L),
        bytes(expr(ZcExpression.CollateralPolicy) + predecessor.getOrElse("")), longs(), bytesList(), bytes("")))

  /** A `SaleOrder` selling `units` of `token`, continued from `predecessor` when given. */
  def saleOrder(boxId: String, token: String, units: Long, value: Long = 3000000L, seller: Int = 3,
                predecessor: Option[String] = None): NodeBox =
    box(boxId, tree(ZcKind.SaleOrder), value, Seq(token -> units),
      regs(Seq(bytes(hashOf(party(seller))), longs(105L, 100L, 1L, 1760000L)) ++ predecessor.map(bytes).toSeq: _*))

  /** A `LotAuction` selling `items`. */
  def lot(boxId: String, items: Seq[(String, Long)], value: Long = 2000000L, seller: Int = 3): NodeBox =
    box(boxId, tree(ZcKind.LotAuction), value, items,
      regs(bytes(hashOf(party(seller))), longs(2 * Erg, Erg, 1700000L, 1700720L, 1702880L, 2000000L)))

  /** A Selective `DutchAuctionMint` request holding one unit of `asset`. */
  def shareRequest(boxId: String, asset: String, owner: Int = 4, value: Long = 20000000L): NodeBox =
    box(boxId, tree(ZcKind.DutchAuctionMint), value, Seq(asset -> 1L),
      regs(bytes(""), bytes(""), byte(0), bytes(hashOf(party(owner))),
        longs(100000000L, 5000000L, 5000000L, 10000L, 9000L, 720L, 2160L, 100L), bytesList(metadata: _*)))

  /** A `DutchAuctionVault` holding N+1 of `share` and the bond unit `asset`. */
  def shareVault(boxId: String, share: String, asset: String, n: Long = 100000000L, value: Long = 10000000L): NodeBox =
    box(boxId, tree(ZcKind.DutchAuctionVault), value, Seq(share -> (n + 1L), asset -> 1L),
      regs(bytes(asset), longs(n, n, 5000000L), byte(0), longs(2 * Erg, 10000L, 9000L, 720L, 2160L, 100L, 0L), bytes("")))

  /** A `WrapMint` request holding `token`. */
  def wrapRequest(boxId: String, token: String, owner: Int = 5, value: Long = 40000000L): NodeBox =
    box(boxId, tree(ZcKind.WrapMint), value, Seq(token -> 1L), regs(bytes(hashOf(party(owner))), long(5000000L),
      bytesList(metadata: _*)))

  /** A `WrapVault` holding two `receipt` units and `token`. */
  def wrapVault(boxId: String, receipt: String, token: String, value: Long = 30000000L): NodeBox =
    box(boxId, tree(ZcKind.WrapVault), value, Seq(receipt -> 2L, token -> 1L), NodeRegisters.empty)

  /** The USD oracle pool box at `price` nanoERG per USD. */
  def oracleBox(boxId: String, price: Long = 465116279L): NodeBox =
    box(boxId, party(99), 1000000000L, Seq(ZcContracts.OracleNft -> 1L, "dd" * 32 -> 100L), regs(long(price), int(3000)))

  /** A wallet box for party `n`, holding `assets`. */
  def wallet(boxId: String, n: Int = 9, assets: Seq[(String, Long)] = Seq.empty): NodeBox =
    box(boxId, party(n), Erg, assets, NodeRegisters.empty)

  // ─── transactions ────────────────────────────────────────────────────────

  def nodeTx(txId: String, spends: Seq[String], creates: Seq[NodeBox], disclose: Seq[String] = Seq.empty): NodeTransaction = {
    val ext = if (disclose.isEmpty) Map.empty[String, String] else Map(ZcTx.DisclosureVar -> bytesList(disclose: _*).toHex)
    NodeTransaction(txId, spends.zipWithIndex.map { case (s, i) =>
      NodeInput(s, NodeSpendingProof("", if (i == 0) ext else Map.empty)) }, Seq.empty,
      creates.zipWithIndex.map { case (b, i) => b.copy(transactionId = txId, index = i) })
  }

  def zcTx(txId: String, spends: Seq[String], creates: Seq[NodeBox], disclose: Seq[String] = Seq.empty): ZcTx =
    ZcTx.of(nodeTx(txId, spends, creates, disclose))

  def snapshot(anchor: String, txs: NodeTransaction*): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot(anchor, txs.map(_.id).toSet, txs.flatMap(_.inputs.map(_.boxId)).toSet, System.nanoTime(),
      transactions = txs.map(tx => CompleteMempool.MempoolTx(tx.id, tx, 0)).toVector)

  def blockId(height: Int, branch: Int = 0): String = f"$branch%02x" + f"$height%062x"

  def emptyBook(height: Int = 1700000, branch: Int = 0): ZcBook = ZcBook.seeded(height, blockId(height, branch), Seq.empty, Seq.empty, cut = false)
}
