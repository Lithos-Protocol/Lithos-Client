package transactions.batching.sigmafi.zc

import node.model.{NodeAsset, NodeBox}
import sigmafi.zc.{ZcExpression, ZcKind, ZcMarket}

/**
 * A market at height [[H]] with a fresh USD oracle: oracle-rule orders, ERG lend offers, live bonds, share vaults and
 * asks, at the register layouts the CLI writes. ERG collateral against an ERG repayment has a cover of exactly
 * collateral / repayment, whatever the oracle price, so cover figures here are plain ratios.
 */
object ZcMarketFixtures {

  import ZcFixtures._

  val H = 1710000
  val Grid = 21600L

  /** The confirmed oracle box, made five blocks before [[H]]. */
  def oracle(boxId: String = id(500), made: Int = H - 5): NodeBox = oracleBox(boxId).copy(creationHeight = made)

  /**
   * An ERG `Order` lending `principal` for `repayment` at `maturity`, under the oracle rule at `ratio` (oracle age
   * 60), with an optional fill guard. `value` holds the collateral and the 0.005 ERG expiry fee.
   */
  def oracleOrder(boxId: String, principal: Long = Erg, repayment: Long = 1050000000L, maturity: Long = 1749600L,
                  expiry: Long = H + 5000L, value: Long = 3 * Erg, ratio: Long = 15000L, guard: Option[Long] = None,
                  borrower: Int = 1): NodeBox =
    box(boxId, tree(ZcKind.Order), value, Seq.empty,
      regs(Seq(longs(Seq(repayment, principal, repayment, maturity, expiry, 5000000L, 0L, ratio, 60L) ++ guard.toSeq: _*),
        tokens(), bytes(hashOf(party(borrower))), bytes(expr(ZcExpression.OracleLiquidation))) ++
        guard.map(_ => bytes(expr(ZcExpression.FillGuard))).toSeq: _*))

  /** A window `PricedOrder` lending a floor of `principal` for `repayment`, `lo` to `hi` blocks after the fill. */
  def windowOrder(boxId: String, lo: Long, hi: Long, capBps: Long, curve: Seq[(Long, Long)] = Seq.empty,
                  principal: Long = 900000000L, repayment: Long = Erg, value: Long = 3 * Erg): NodeBox =
    box(boxId, tree(ZcKind.PricedOrder), value, Seq.empty,
      regs(longs(repayment, principal, repayment, lo, H + 5000L, 5000000L, 2L, 15000L, 60L), tokens(),
        bytes(hashOf(party(1))), bytes(expr(ZcExpression.OracleLiquidation)),
        bytesList(expr(ZcExpression.CurveOrderPricing), ""),
        longs(Seq(hi, capBps) ++ curve.flatMap { case (y, d) => Seq(y, d) }: _*)))

  /** A two-point yield `PricedOrder` with a fixed maturity. */
  def yieldOrder(boxId: String, y0: Long, y1: Long, h0: Long = H - 100L, h1: Long = H + 100L, principal: Long = 500000000L,
                 repayment: Long = Erg, maturity: Long = 1749600L): NodeBox =
    box(boxId, tree(ZcKind.PricedOrder), 3 * Erg, Seq.empty,
      regs(longs(repayment, principal, repayment, maturity, H + 5000L, 5000000L, 0L, 15000L, 60L), tokens(),
        bytes(hashOf(party(1))), bytes(expr(ZcExpression.OracleLiquidation)),
        bytesList(expr(ZcExpression.YieldOrderPricing), ""), longs(h0, y0, h1, y1)))

  /**
   * An ERG `LendOffer` of `value` nanoERG: least yield `minYield` and `curve`, batcher fee `fee`, collateral floor
   * `floor`, oracle age 30, maturities `min` to `max` blocks, buying shares when `shareBounds` is set.
   */
  def ergOffer(boxId: String, value: Long = 10 * Erg, fee: Long = 5000000L, minYield: Long = 700L,
               curve: Seq[(Long, Long)] = Seq(820L -> 21600L, 940L -> 131400L), floor: Long = 15000L,
               min: Long = 720L, max: Long = 262800L, shareBounds: Option[(Long, Long)] = None, owner: Int = 2): NodeBox = {
    val (aFloor, aExpiry) = shareBounds.getOrElse((0L, 0L))
    box(boxId, tree(ZcKind.LendOffer), value, Seq.empty,
      regs(bytes(hashOf(party(owner))),
        longs(Seq(minYield, min, max, fee, 50000000L, floor, H + 5000L, 1L, 30L, aFloor, aExpiry) ++
          curve.flatMap { case (y, d) => Seq(y, d) }: _*),
        bytes(expr(ZcExpression.CollateralPolicy)), longs(), bytesList(), bytes("")))
  }

  /** A live ERG bond for `asset` under the oracle rule, started at `start`. */
  def oracleBond(boxId: String, asset: String, repayment: Long = 1050000000L, maturity: Long = 1749600L,
                 value: Long = 3 * Erg, start: Long = H - 1000L, borrower: Int = 1): NodeBox =
    box(boxId, tree(ZcKind.Bond), value, Seq(asset -> 2L),
      regs(bytes(asset), bytes(hashOf(party(borrower))),
        longs(repayment, Erg, repayment, maturity, H + 5000L, 5000000L, 0L, 15000L, 60L), tokens(),
        bytes(expr(ZcExpression.OracleLiquidation)), longs(50000000L, start, maturity)))
      .copy(creationHeight = start.toInt)

  /** A `SaleOrder` selling `units` of `token` at `num / den` nanoERG a raw unit. */
  def ask(boxId: String, token: String, units: Long, num: Long, den: Long, seller: Int = 3): NodeBox =
    box(boxId, tree(ZcKind.SaleOrder), 3000000L, Seq(token -> units),
      regs(bytes(hashOf(party(seller))), longs(num, den, 1L, H + 5000L)))

  /** The origin a bond unit minted by a standard order proves. */
  def unitOrigin(asset: String): ZcOrigin = ZcOrigin(asset, ZcKind.Order, Some(ZcMarket.Erg), "aa" * 32, Some(H - 1000), IndexedSeq.empty, None)

  /** The origin of Selective shares of `asset`, minted with an auction floor of `floorBps` and an expiry of `expiry`. */
  def shareOrigin(share: String, asset: String, floorBps: Long = 9000L, expiry: Long = 2160L): ZcOrigin =
    ZcOrigin(share, ZcKind.DutchAuctionMint, Some(ZcMarket.Erg), "ab" * 32, Some(H - 900),
      IndexedSeq(100000000L, 5000000L, 5000000L, 10000L, floorBps, 720L, expiry, 100L), Some(NodeAsset(asset, 1L)))

  /** A view of `boxes` at [[H]], with the oracle unless left out, and a pending refresh when asked. */
  def view(boxes: Seq[NodeBox], withOracle: Boolean = true, refreshing: Boolean = false): ZcView = {
    val entries = boxes.map(b => ZcEntry(ZcBoxes.read(b).getOrElse(throw new IllegalArgumentException(s"not a ZC box: ${b.boxId}")), H - 10))
    val book = ZcBook.seeded(H, blockId(H), entries, if (withOracle) Seq(oracle() -> (H - 5)) else Seq.empty, cut = false)
    val mempool = if (!refreshing) None
      else Some(ZcMempool(book.headerId, 0L, Map.empty, Map.empty, Map.empty, Some("cc" * 32), Map.empty))
    ZcView(book, mempool, 1L)
  }

  def matcher(boxes: Seq[NodeBox], origins: Map[String, ZcOrigin] = Map.empty, withOracle: Boolean = true,
              refreshing: Boolean = false, maxSources: Int = ZcMatcher.MaxSources): ZcMatcher =
    new ZcMatcher(ZcMarketBook.of(view(boxes, withOracle, refreshing)), origins.get, maxSources = maxSources)
}
