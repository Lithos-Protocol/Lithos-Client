package transactions.engine.execution

import akka.pattern.ask
import akka.util.Timeout
import api.LithosApiErrors._
import api.zc.{ZcDrafts, ZcJson, ZcKnown}
import configs.NodeContext
import mutations.NodeWallet
import node.NodeApi
import node.model.{MempoolOptions, NodeAsset, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import sigma.ast.ErgoTree
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.synchronization.CompleteMempool
import transactions.batching.sigmafi.zc.ZcPricing._
import transactions.batching.sigmafi.zc.ZcUserTransactions.ZcSpend
import transactions.batching.sigmafi.zc._
import transactions.batching.sigmafi.{SFPlan, SigmaFiBoxes}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.concurrent.Await
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** A write that cannot run now, with what must happen first (`requires`, e.g. `pull`). */
final class ZcRefusal(reason: String, val requires: Seq[String] = Seq.empty) extends LithosUnprocessable(reason)

/**
 * The ZC writes this wallet makes. Each has a check that signs nothing and a send that runs on the transaction engine's
 * worker, which owns the wallet. Checks quote from the tracker's view; a send re-reads every box it spends or reads
 * from the node and refuses one a fresh mempool observation shows spent (409).
 *
 * A check that cannot run answers 200 with its `refusals`; the send answers 422 with them. A send body may carry
 * `limits: { maxPay, minReceive }` in the action's market unit (nanoERG for ERG, fee included; raw units for SigUSD;
 * nanoERG for actions with no market): a rebuilt transaction outside them is refused with 409, so a quote that moved
 * between review and send is never signed silently.
 *
 * @param alive      whether the engine still wants this attempt; once false, nothing is signed or sent
 * @param capability which of the given token ids are capability tokens (LithosDex provision NFTs), refused in lots
 */
class ZcWriteExecution(nodeContext: NodeContext, walletSelector: EngineFunding, tracker: ZcTracker, resolver: ZcResolver,
                       alive: () => Boolean = () => true, capability: Seq[String] => Set[String] = _ => Set.empty) {

  import ZcWriteExecution._

  protected def executionNode: NodeApi = nodeContext.getNodeApi

  private def wallet: NodeWallet = nodeContext.getNodeWallet

  /** Blake2b256 of each tree this wallet signs for, to its contract: what ZC boxes name their parties by. */
  private lazy val walletHashes: Map[String, Contract] = wallet.signableTrees.toSeq.map { hex =>
    Hex.toHexString(Blake2b256.hash(Hex.decode(hex))) -> Contract(ErgoTree.fromHex(hex))
  }.toMap

  private lazy val walletTrees: Set[String] = walletHashes.values.map(_.ergoTreeHex).toSet

  private def own: Contract = wallet.contract
  private def ownHash: String = Hex.toHexString(own.hashedPropBytes)

  private def view: ZcView = tracker.view.getOrElse(throw LithosUnavailable("ZC tracking has not finished its first scan"))

  private def matcher(v: ZcView): ZcMatcher = new ZcMatcher(ZcMarketBook.of(v), resolver.knownOrigin)

  private def withCtx[A](f: BlockchainContext => A): A = nodeContext.getClient.execute(ctx => f(ctx))

  private def refuse(reason: String, requires: Seq[String] = Seq.empty): Nothing = throw new ZcRefusal(reason, requires)

  /** A check's refusal answered as a quote listing it; a send's left to answer 422. */
  private def checked(action: String, send: Boolean)(body: => JsObject): JsObject =
    try body catch {
      case r: LithosUnprocessable if !send =>
        val requires = r match { case z: ZcRefusal => z.requires; case _ => Seq.empty }
        Json.obj("action" -> action, "at" -> Try(ZcJson.at(ZcMarketBook.of(view)).value("at")).toOption,
          "refusals" -> Seq(r.reason), "requires" -> requires, "warnings" -> JsArray())
    }

  // ─── posting ───────────────────────────────────────────────────────────────

  /** POST /sigmafi/zc/orders[/check]: an `Order` or a `PricedOrder` from the create-order body. */
  def createOrder(body: JsObject, send: Boolean): JsObject = checked("createOrder", send)(withCtx { ctx =>
    val h = ctx.getHeight
    val draft = ZcDrafts.orderOf(body, h.toLong)
    val v = view
    val m = matcher(v)
    ZcRules.orderRefusal(draft, h.toLong).filterNot(_.contains("expired")).foreach(r => throw LithosBadRequest(r))
    val rule = ZcRules.rule(draft.liqHash, draft.market)
    val ratio = if (rule == ZcRule.Oracle) draft.payload.headOption else None
    val guardRatio = ZcRules.fillRatio(draft)
    val prospect = ZcRules.orderMaturity(draft, h.toLong).flatMap(mat =>
      ZcRules.resolvePrincipal(draft, h.toLong, mat).map(p => ZcBondTerms.prospect(draft, h.toLong, mat, p))).toOption
    val coverNow = for (o <- v.book.oracle; b <- prospect; c <- ZcRules.cover(b, o.price)) yield c
    val under = ratio.exists(r => coverNow.exists(c => !atLeast(c, r)))
    val allow = (body \ "allowLiquidatable").asOpt[Boolean].contains(true)
    if (under && guardRatio.isEmpty && !allow)
      refuse("at the confirmed oracle price the collateral is already under the order's own ratio: a fill " +
        "would start a bond anyone could liquidate at once, taking all the collateral. Add collateral, keep the fill guard, " +
        "or set allowLiquidatable")
    val box = ZcUserTransactions.orderBox(draft, ownHash, h)
    val warnings = (if (guardRatio.exists(g => coverNow.exists(c => !atLeast(c, g))))
      Seq("at the oracle price now the fill guard refuses every fill; the order waits until cover recovers") else Seq.empty) ++
      (draft.mode match {
        case 0L =>
          val lastFill = math.min(draft.expiry - 1L, draft.terms(3) - 2L)
          if (draft.terms(3) - lastFill >= (draft.terms(3) - h) / 2L) Seq.empty
          else Seq(s"a fill at height $lastFill leaves only ${draft.terms(3) - lastFill} blocks to repay")
        case _ => Seq.empty
      })
    val terms = Json.obj("contract" -> draft.script.kind.name, "treeHash" -> draft.script.treeHash,
      "liquidationHash" -> draft.liqHash, "pricingHash" -> draft.pricingHash, "guardHash" -> draft.guardHash,
      "requestedMaturity" -> (body \ "timing" \ "maturity").asOpt[Long],
      "maturity" -> (if (draft.mode == 0L) Some(draft.terms(3)) else None),
      "expiry" -> draft.expiry, "principal" -> draft.principal.toString, "repayment" -> draft.repayment.toString,
      "coverBps" -> coverNow.map(ratioBps),
      "liquidatesAtUsdPerErg" -> ratio.flatMap(r => prospect.flatMap(b =>
        crossingPrice(b.market, b.collateralErg, b.collateral, b.repayment, r))).map(p => usdPerErg(p._1)),
      "guardStopsAtUsdPerErg" -> guardRatio.flatMap(g => prospect.flatMap(b =>
        crossingPrice(b.market, b.collateralErg, b.collateral, b.repayment, g))).map(p => usdPerErg(p._1)))
    post(ctx, "createOrder", "order", box, terms, warnings, body ++ Json.obj("type" -> "order"), m, send)
  })

  /** POST /sigmafi/zc/offers[/check]: a `LendOffer` from the create-offer body. */
  def createOffer(body: JsObject, send: Boolean): JsObject = checked("createOffer", send)(withCtx { ctx =>
    val h = ctx.getHeight
    val draft = ZcDrafts.offerOf(body, h.toLong)
    val box = ZcUserTransactions.offerBox(draft, ownHash)
    val warnings = (if (ZcRules.batcherFee(draft) < MinBatchingFee) Seq("the batcher fee is under 0.002 ERG: no batcher fills it")
      else Seq.empty) ++ (if (ZcRules.token(draft) && ZcRules.minFillBps(draft) > 100L)
      Seq("the least fill refuses small new loans") else Seq.empty)
    val terms = Json.obj("contract" -> "LendOffer", "policyHash" -> draft.policyHash, "terms" -> draft.terms.map(_.toString),
      "funding" -> draft.box.value.toString)
    post(ctx, "createOffer", "offer", box, terms, warnings, body ++ Json.obj("type" -> "offer"), matcher(view), send)
  })

  /** POST /sigmafi/zc/asks[/check]: a `SaleOrder` or a `PricedAsk` selling a proven bond unit or shares. */
  def createAsk(body: JsObject, send: Boolean): JsObject = checked("createAsk", send)(withCtx { ctx =>
    val v = view
    val m = matcher(v)
    val draft = ZcDrafts.askOf(body, m)
    val claim = m.claimOf(draft.token.tokenId).toOption
    val bond = claim.flatMap(c => m.book.bonds.get(c._1)).map(b => ZcBondTerms.of(b.record))
    if (bond.exists(b => v.book.oracle.exists(o => ZcRules.belowOwnRatio(b, o.price))))
      refuse("the bond is under its own ratio: liquidate it, which pays its holder the collateral, rather than sell it")
    val box = ZcUserTransactions.askBox(draft, ownHash)
    val terms = Json.obj("contract" -> draft.script.kind.name, "tokenId" -> draft.token.tokenId,
      "units" -> draft.token.amount.toString, "bondId" -> claim.map(_._1), "unitsPerBond" -> claim.map(_._2.toString),
      "pricingHash" -> draft.pricingHash, "terms" -> draft.terms.map(_.toString))
    post(ctx, "createAsk", "ask", box, terms, Seq.empty, body ++ Json.obj("type" -> "ask"), m, send)
  })

  /**
   * POST /sigmafi/zc/lots[/check]: a lot auction selling a basket for the market's asset, falling from `opening` at
   * `start` to `floor` at `end`, held there until `expiry`. A capability token is refused: wrap it first.
   */
  def createLot(body: JsObject, send: Boolean): JsObject = checked("createLot", send)(withCtx { ctx =>
    val h = ctx.getHeight.toLong
    val lot = ZcDrafts.lotOf(body, h)
    val items = lot.items
    val m = lot.market
    capability(items.filter(_.amount == 1L).map(_.tokenId)).headOption.foreach(id =>
      refuse(s"$id is a capability token (a LithosDex provision NFT): whoever spends a box holding it acts for its " +
        "position, and a lot's buyer spends it beside anything. Wrap it (POST /sigmafi/zc/wraps) and sell the receipt"))
    m.tokenId.foreach(id => if (items.exists(_.tokenId == id)) throw LithosBadRequest(s"a lot paid in $m cannot hold $m itself"))
    val box = ZcUserTransactions.lotBox(m, items.map(t => Token(ErgoId.create(t.tokenId), t.amount)), lot.erg, lot.terms,
      ownHash)
    val terms = Json.obj("contract" -> "LotAuction", "market" -> m.name, "opening" -> lot.terms(0).toString,
      "floor" -> lot.terms(1).toString, "start" -> lot.terms(2), "end" -> lot.terms(3), "expiry" -> lot.terms(4),
      "refundFeeNanoErg" -> lot.terms(5).toString)
    post(ctx, "createLot", "lot", box, terms, Seq.empty, body, matcher(view), send, simulate = false)
  })

  /**
   * POST /sigmafi/zc/bonds/:assetId/fractionalize[/check]: a share mint request holding this wallet's bond unit, whose
   * box id becomes the share token's id once a batcher mints it.
   */
  def fractionalize(assetId: String, body: JsObject, send: Boolean): JsObject = checked("fractionalize", send)(withCtx { ctx =>
    val asset = hexId(assetId)
    val v = view
    val m = matcher(v)
    val budget = newBudget()
    val origin = resolver.origin(asset, budget).getOrElse(refuse(s"$asset was not minted by a standard order"))
    if (origin.kind != ZcKind.Order && origin.kind != ZcKind.PricedOrder) refuse(s"$asset is not a bond unit")
    val market = origin.market.getOrElse(refuse("the bond's market is unknown"))
    val req = ZcDrafts.shareRequestOf(body)
    val live = m.book.bonds.get(asset).map(_.record)
    val facts = live.map(b => ZcMetadata.BondFacts(market, asset, if (market.tokenId.isEmpty) b.terms(1)
      else b.principalTokens.headOption.fold(0L)(_.amount), b.repayment, b.start, b.maturity,
      ZcMetadata.ruleFields(ZcRules.rule(b.liqHash, market), b.terms.drop(7), b.liqHash)))
    val metadata = ZcMetadata.shares(market, req.auction, facts)
    val box = ZcUserTransactions.shareRequestBox(asset, market, req.terms, req.auction.isDefined, metadata, ownHash)
    val buyers = req.auction.map { a =>
      m.book.offers.count(o => o.record.market == market && ZcRules.buysShares(o.record) && h1(ctx) < o.record.expiry &&
        a.floorBps >= o.record.terms(9) && a.expiry <= o.record.terms(10))
    }
    val warnings = (if (req.auction.isEmpty) Seq("a plain fractional vault pays holders in kind after a liquidation, a token too " +
      "thin to split going to the last claimant; lend offers do not buy these shares") else Seq.empty) ++
      (if (req.terms(0) != ShareCount) Seq(s"a nonstandard share count: zcs1 uses $ShareCount") else Seq.empty)
    val terms = Json.obj("contract" -> (if (req.auction.isDefined) "DutchAuctionMint" else "FractionalMint"), "bondId" -> asset, "market" -> market.name, "shares" -> req.terms(0).toString,
      "pullFeeNanoErg" -> req.terms(1).toString, "mintFeeNanoErg" -> req.terms(2).toString,
      "auction" -> req.auction.map(a => Json.obj("openingBps" -> a.openingBps, "floorBps" -> a.floorBps, "length" -> a.length,
        "expiry" -> a.expiry, "threshold" -> (if (a.threshold == Long.MaxValue) JsNull else JsNumber(a.threshold)))),
      "name" -> metadata.head, "offersBuying" -> buyers)
    post(ctx, "fractionalize", "mintRequest", box, terms, warnings, body, m, send, simulate = false,
      fees = Seq(fee("mint", req.terms(2), "locked"), fee("pull", req.terms(1), "locked")))
  })

  /** POST /sigmafi/zc/wraps[/check]: a wrap request for a token this wallet holds; a batcher mints its receipt. */
  def createWrap(body: JsObject, send: Boolean): JsObject = checked("createWrap", send)(withCtx { ctx =>
    val w = ZcDrafts.wrapOf(body)
    val held = walletHeld(w.tokenId)
    val amount = w.amount.getOrElse(held)
    if (held == 0L) refuse(s"this wallet holds no ${w.tokenId}")
    if (amount < 1L || amount > held) throw LithosBadRequest(s"wrap 1 to $held of ${w.tokenId}")
    val box = ZcUserTransactions.wrapRequestBox(w.tokenId, amount, w.mintFee, w.refreshReserve, ownHash)
    val terms = Json.obj("contract" -> "WrapMint", "tokenId" -> w.tokenId, "amount" -> amount.toString,
      "receiptName" -> ZcMetadata.receipt(w.tokenId, amount).head, "mintFeeNanoErg" -> w.mintFee.toString,
      "refreshReserveNanoErg" -> w.refreshReserve.toString, "refreshes" -> w.refreshReserve / RefreshBounty)
    post(ctx, "createWrap", "wrapRequest", box, terms, Seq.empty, body, matcher(view), send, simulate = false,
      fees = Seq(fee("mint", w.mintFee, "locked"), fee("wrapRefreshReserve", w.refreshReserve, "locked")))
  })

  /** The quote, or the send, of posting `box` from this wallet. */
  private def post(ctx: BlockchainContext, action: String, role: String, box: UTXO, terms: JsObject, warnings: Seq[String],
                   draft: JsObject, m: ZcMatcher, send: Boolean, simulate: Boolean = true,
                   fees: Seq[JsObject] = Seq.empty): JsObject = {
    val pays = Math.addExact(box.value, ZcUserTransactions.TxFee)
    val quote = Json.obj("action" -> action, "at" -> ZcJson.at(m.book).value("at"),
      "paysNanoErgs" -> pays.toString, "paysTokens" -> box.tokens.map(t => tokenJson(t.id.toString, t.amount)),
      "receivesNanoErgs" -> "0", "receivesTokens" -> JsArray(),
      "fees" -> (fee("miner", ZcUserTransactions.TxFee, "spent") +: fees),
      "creates" -> Seq(Json.obj("role" -> role, "contract" -> terms.value.get("contract"), "nanoErg" -> box.value.toString,
        "tokens" -> box.tokens.map(t => tokenJson(t.id.toString, t.amount)))),
      "terms" -> terms, "validFrom" -> ctx.getHeight, "matchesNow" -> (if (!simulate) None else Try(ZcDrafts.simulate(
        Json.obj("draft" -> draft), m, new ZcJson(m, ZcKnown(resolver.knownOrigin, resolver.knownDisclosed,
          walletHashes.contains, _ => None)))).toOption),
      "warnings" -> warnings, "refusals" -> JsArray())
    if (!send) quote
    else {
      limit(draft, pays, 0L)
      val (signed, sent) = fundAndSend(ctx, ZcUserTransactions.post(ctx, wallet, box, own))
      result(signed, sent, Some(outputId(signed, 0)), warnings)
    }
  }

  // ─── lending ───────────────────────────────────────────────────────────────

  /**
   * POST /sigmafi/zc/orders/:boxId/fill[/check]: this wallet lends, minting the bond asset to itself. A window order's
   * maturity is the body's `maturity`, else zcs1's default; `reserveNanoErg` funds the liquidation fee reserve.
   */
  def fillOrder(boxId: String, body: JsObject, send: Boolean): JsObject = checked("fillOrder", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val h = ctx.getHeight.toLong
    val v = view
    val m = matcher(v)
    val box = read(id)
    val order = ZcBoxes.read(box).collect { case o: ZcOrder => o }.getOrElse(throw LithosNotFound(s"$id is not a ZC order"))
    ZcRules.orderRefusal(order, h).foreach(r => refuse(r))
    val chosen = long(body, "maturity")
    val reserve = long(body, "reserveNanoErg").getOrElse(DefaultReserve)
    if (reserve < DefaultReserve) throw LithosBadRequest(s"reserveNanoErg must be at least $DefaultReserve")
    if (chosen.isDefined && !ZcRules.window(order)) throw LithosBadRequest("only a window order lets the lender choose its maturity")
    val maturity = ZcRules.orderMaturity(order, h, chosen).fold(r => refuse(r), identity)
    val principal = ZcRules.resolvePrincipal(order, h, maturity).fold(r => refuse(r), identity)
    val filled = ZcUserTransactions.resolved(order, principal)
    val prospect = ZcBondTerms.prospect(order, h, maturity, principal)
    val oracle = if (order.guardHash.isEmpty) None else Some(oracleOf(v))
    ZcRules.guardRefusal(order, prospect, oracle, h).foreach(r => refuse(s"the order's fill guard refuses this fill: $r"))
    coverCheck(v, prospect, body, "the bond would start", "Wait for the price, or for the borrower to cancel")
    val snapshot = if (send) Some(fresh(Seq(id) ++ oracle.map(_.box.boxId))) else None
    val borrower = scriptOf(order, pending(v, id), "borrower")
    val input = protocolInput(ctx, box)
    val spend = ZcUserTransactions.fillOrder(filled, input, borrower, own, h, maturity, reserve,
      oracle.map(o => dataInput(ctx, o.box.boxId)))
    val competing = m.offerStates(order.market).filter(s => m.evaluate(s, ZcOrderSource(ZcOpen(order, false))).isRight)
      .take(ZcMatcher.MaxOffers).map(_.boxId)
    val terms = Json.obj("assetId" -> id, "market" -> order.market.name, "principal" -> principal.toString,
      "repayment" -> order.repayment.toString, "start" -> h, "maturity" -> maturity,
      "yieldBps" -> yieldBps(BigInt(order.repayment), BigInt(principal), maturity - (h + 1L)),
      "coverBps" -> v.book.oracle.flatMap(o => ZcRules.cover(prospect, o.price)).map(ratioBps),
      "window" -> ZcRules.orderWindow(order).map { case (lo, hi) => Json.obj("from" -> (h + lo), "to" -> (h + hi)) },
      "feeReserveNanoErg" -> reserve.toString)
    val validThrough = if (order.priced) h + QuoteBlocks else math.min(order.expiry - 2L, maturity - 3L)
    execute(ctx, "fillOrder", Some(order.market), spend, Seq("unit", "bond", "principal"), terms,
      Seq(fee("liquidationReserve", reserve, "locked")), Seq.empty, competing, validThrough, body, send, snapshot,
      Seq.empty, Some(id))
  })

  /**
   * POST /sigmafi/zc/asks/:boxId/buy[/check]: this wallet buys `units` (default all) of an ask. A token claims a bond
   * only once its origin, and for shares their mint, is proven; unproven shares are refused.
   */
  def buyAsk(boxId: String, body: JsObject, send: Boolean): JsObject = checked("buyAsk", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val h = ctx.getHeight.toLong
    val v = view
    val budget = newBudget()
    val m = new ZcMatcher(ZcMarketBook.of(v), t => resolver.origin(t, budget))
    val box = read(id)
    val ask = ZcBoxes.read(box).collect { case a: ZcAsk => a }.getOrElse(throw LithosNotFound(s"$id is not a ZC ask"))
    val units = long(body, "units").getOrElse(ask.token.amount)
    ZcRules.fillRefusal(ask, units, h).foreach(r => refuse(r))
    val token = ask.token.tokenId
    val claim = m.claimOf(token)
    if (claim.isLeft && m.book.shareVaults.contains(token))
      refuse(s"$token sits in a share vault naming a bond, but no standard share mint minted it: anyone can make such " +
        "a vault, so it claims nothing")
    val liveBond = claim.toOption.flatMap(c => m.book.bonds.get(c._1)).map(_.record)
    if (ask.priced && !ZcRules.yieldAsk(ask)) refuse("the ask names a pricing expression this client does not know")
    val pricedBond = if (!ask.priced) None
      else Some(ask.pricedAsset.flatMap(a => m.book.bonds.get(a)).map(_.record)
        .getOrElse(refuse("the bond a yield ask prices is not live: it sells only while its bond is")))
    val price = ZcRules.askPrice(ask, units, pricedBond.map(ZcBondTerms.of), h).fold(r => refuse(r), identity)
    liveBond.foreach(b => coverCheck(v, ZcBondTerms.of(b), body, s"bond ${b.boxId} is",
      s"Liquidate it instead: its holder, the seller, then claims all the collateral"))
    val oracle = if (ZcRules.saleGuard(ask).isEmpty) None else Some(oracleOf(v))
    pricedBond.flatMap(b => ZcRules.saleGuardRefusal(ask, ZcBondTerms.of(b), oracle, h))
      .foreach(r => refuse(s"the ask's sale guard refuses this sale: $r"))
    val snapshot = if (send) Some(fresh(Seq(id) ++ pricedBond.map(_.boxId) ++ oracle.map(_.box.boxId))) else None
    val seller = scriptOf(ask, pending(v, id), "seller")
    val spend = ZcUserTransactions.buyAsk(ask, protocolInput(ctx, box), seller, units, price,
      pricedBond.map(b => dataInput(ctx, b.boxId)), oracle.map(o => dataInput(ctx, o.box.boxId)))
    val warnings = claim.swap.toOption.map(r => s"the token is not a proven bond unit or shares ($r): it claims nothing").toSeq
    val terms = Json.obj("tokenId" -> token, "units" -> units.toString, "price" -> price.toString, "market" -> ask.market.name,
      "bondId" -> claim.toOption.map(_._1), "unitsPerBond" -> claim.toOption.map(_._2.toString),
      "yieldBps" -> liveBond.flatMap(b => claim.toOption.flatMap(c => yieldBps(BigInt(b.repayment) * units / c._2,
        BigInt(price), b.maturity - (h + 1L)))))
    val competing = claim.toOption.flatMap(_ => m.askSource(ZcOpen(ask, false)).toOption).toSeq.flatMap(src =>
      m.offerStates(ask.market).filter(s => m.evaluate(s, src).isRight).take(ZcMatcher.MaxOffers).map(_.boxId))
    execute(ctx, "buyAsk", Some(ask.market), spend, Seq("payout", "continuation"), terms, Seq.empty, warnings, competing,
      if (ask.priced) h + QuoteBlocks else ask.expiry - 2L, body, send, snapshot, Seq.empty, None)
  })

  /** POST /sigmafi/zc/lots/:boxId/buy[/check]: this wallet buys a lot whole at the current price. */
  def buyLot(boxId: String, body: JsObject, send: Boolean): JsObject = checked("buyLot", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val h = ctx.getHeight.toLong
    val v = view
    val box = read(id)
    val lot = ZcBoxes.read(box).collect { case l: ZcLot => l }.getOrElse(throw LithosNotFound(s"$id is not a ZC lot"))
    if (!ZcRules.lotValid(lot)) refuse("the lot's terms are malformed, so only its seller can cancel it")
    if (h + 1L >= lot.expiry) refuse(s"the lot expired at height ${lot.expiry}")
    val price = ZcRules.lotPrice(lot, h)
    val snapshot = if (send) Some(fresh(Seq(id))) else None
    val seller = scriptOf(lot, pending(v, id), "seller")
    val spend = ZcUserTransactions.buyLot(lot, protocolInput(ctx, box), seller, price)
    val terms = Json.obj("price" -> price.toString, "market" -> lot.market.name,
      "items" -> lot.box.assets.map(a => tokenJson(a.tokenId, a.amount)),
      "nanoErg" -> (if (lot.market.tokenId.isEmpty) "0" else lot.box.value.toString))
    execute(ctx, "buyLot", Some(lot.market), spend, Seq("payout"), terms, Seq.empty, Seq.empty, Seq.empty, lot.expiry - 2L,
      body, send, snapshot, Seq.empty, None)
  })

  // ─── borrowing ─────────────────────────────────────────────────────────────

  /**
   * POST /sigmafi/zc/bonds/:assetId/repay[/check]: repay before maturity. `fromCollateral` ("max" or a raw amount)
   * keeps that much of the repayment asset back from the collateral, which needs this wallet to be the borrower.
   */
  def repay(assetId: String, body: JsObject, send: Boolean): JsObject = checked("repay", send)(withCtx { ctx =>
    val h = ctx.getHeight.toLong
    val v = view
    val (bond, box) = liveBond(v, assetId)
    if (h + 1L >= bond.maturity) refuse(s"the bond matures at height ${bond.maturity}: repayment is closed, liquidation is open")
    val most = ZcUserTransactions.repayableFromCollateral(bond)
    val kept = (body \ "fromCollateral").toOption.filterNot(_ == JsNull) match {
      case None => 0L
      case Some(JsString("max")) => most
      case Some(_) => long(body, "fromCollateral").getOrElse(0L)
    }
    if (kept < 0L || kept > most) throw LithosBadRequest(s"fromCollateral must be 0 to $most")
    val borrowerOwn = walletHashes.get(bond.borrowerHash)
    if (kept > 0L && borrowerOwn.isEmpty) refuse("only the borrower may repay from the collateral: it needs their box")
    val snapshot = if (send) Some(fresh(Seq(bond.boxId))) else None
    val auth = if (kept == 0L) None else Option(authorize(ctx, borrowerOwn.get, snapshot))
    try {
      val borrower = borrowerOwn.getOrElse(scriptOf(bond, pending(v, bond.boxId), "borrower"))
      val authInput = if (kept == 0L) None
        else Some(auth.map(_.inputs.head).getOrElse(UTXO(borrowerOwn.get, ZcBoxes.MinBox).toDummyInput(ctx)))
      val spend = ZcUserTransactions.repay(bond, protocolInput(ctx, box), borrower, kept, authInput)
      val warnings = if (bond.maturity - h <= 2L) Seq("two blocks or fewer remain: if the repayment lands at maturity it " +
        "fails, and the bond can be liquidated") else Seq.empty
      val terms = Json.obj("assetId" -> bond.asset, "bondBoxId" -> bond.boxId, "repayment" -> bond.repayment.toString,
        "fromCollateral" -> kept.toString, "maturity" -> bond.maturity, "market" -> bond.market.name,
        "collateralBack" -> Json.obj("nanoErg" -> spend.outputs(1).value.toString,
          "tokens" -> spend.outputs(1).tokens.map(t => tokenJson(t.id.toString, t.amount))))
      execute(ctx, "repay", Some(bond.market), spend, Seq("vault", "collateral"), terms,
        Seq(fee("liquidationReserve", bond.feeReserve, "refundable")), warnings, Seq.empty, bond.maturity - 2L, body, send,
        snapshot, auth.toSeq, None, ownInputs = authInput.toSeq)
    } catch { case NonFatal(ex) => auth.foreach(_.release()); throw ex }
  })

  /**
   * POST /sigmafi/zc/bonds/:assetId/recollateralize[/check]: add ERG, more of a token the bond holds, or a token bond's
   * repayment token, at most once per 60 blocks. Needs this wallet to be the borrower.
   */
  def recollateralize(assetId: String, body: JsObject, send: Boolean): JsObject = checked("recollateralize", send)(withCtx { ctx =>
    val h = ctx.getHeight.toLong
    val v = view
    val (bond, box) = liveBond(v, assetId)
    val erg = long(body, "nanoErg").getOrElse(0L)
    val added = ZcDrafts.tokensOf(body \ "tokens")
    if (erg < 0L) throw LithosBadRequest("nanoErg cannot be negative")
    if (erg == 0L && added.isEmpty) throw LithosBadRequest("add something: nanoErg or tokens")
    if (added.map(_.tokenId).distinct.size != added.size) throw LithosBadRequest("list each token once")
    added.filterNot(t => bond.collateral.exists(_.tokenId == t.tokenId)).foreach { t =>
      if (!bond.market.tokenId.contains(t.tokenId))
        throw LithosBadRequest(s"the bond does not hold ${t.tokenId}: it takes only more of its own tokens, or its repayment token")
    }
    if (h + 1L >= bond.maturity) refuse(s"the bond matures at height ${bond.maturity}: it can no longer take collateral")
    val from = bond.box.creationHeight.toLong + RecollateralizeWait
    if (h < from) refuse(s"the bond last moved at height ${bond.box.creationHeight}; it takes collateral again from height $from")
    val borrower = walletHashes.getOrElse(bond.borrowerHash, refuse("only the borrower may add collateral: it needs their box"))
    val snapshot = if (send) Some(fresh(Seq(bond.boxId))) else None
    val auth = if (send) authorize(ctx, borrower, snapshot) else { requireAuth(borrower); null }
    try {
      val authInput = Option(auth).map(_.inputs.head).getOrElse(UTXO(borrower, ZcBoxes.MinBox).toDummyInput(ctx))
      val spend = ZcUserTransactions.recollateralize(bond, protocolInput(ctx, box), authInput, erg,
        added.map(t => Token(ErgoId.create(t.tokenId), t.amount)), h.toInt)
      val after = spend.outputs.head
      val cover = v.book.oracle.flatMap(o => ZcRules.cover(ZcBondTerms.of(bond).copy(collateralErg = after.value - bond.feeReserve,
        collateral = after.tokens.drop(1).map(t => NodeAsset(t.id.toString, t.amount))), o.price)).map(ratioBps)
      val terms = Json.obj("assetId" -> bond.asset, "bondBoxId" -> bond.boxId, "addsNanoErg" -> erg.toString,
        "addsTokens" -> added.map(t => tokenJson(t.tokenId, t.amount)), "coverBpsAfter" -> cover,
        "nextFrom" -> (h + RecollateralizeWait))
      execute(ctx, "recollateralize", Some(bond.market), spend, Seq("bond"), terms, Seq.empty, Seq.empty, Seq.empty,
        bond.maturity - 2L, body, send, snapshot, Option(auth).toSeq, None, ownInputs = Seq(authInput))
    } catch { case NonFatal(ex) => Option(auth).foreach(_.release()); throw ex }
  })

  /**
   * POST /sigmafi/zc/bonds/:assetId/liquidate[/check]: anyone, on any bond, from maturity on, or before it while the
   * bond's cover is under its own oracle ratio at a fresh enough oracle. This wallet keeps the fee reserve.
   */
  def liquidate(assetId: String, body: JsObject, send: Boolean): JsObject = checked("liquidate", send)(withCtx { ctx =>
    val h = ctx.getHeight.toLong
    val v = view
    val (bond, box) = liveBond(v, assetId)
    val matured = h >= bond.maturity
    val rule = ZcRules.rule(bond.liqHash, bond.market)
    val oracle = rule match {
      case ZcRule.Other => refuse("the bond names a liquidation expression this client does not know")
      case _ if matured => None
      case ZcRule.Maturity => refuse(s"the bond matures at height ${bond.maturity}; it can be liquidated from then on")
      case ZcRule.Oracle =>
        val p = bond.terms.drop(7)
        if (p.size < 2) refuse(s"the bond has no oracle terms; it can be liquidated from maturity, height ${bond.maturity}")
        val o = oracleOf(v)
        if (!ZcRules.fresh(o, p(1), h)) refuse(s"the oracle price is from height ${o.box.creationHeight}, older than the bond's ${p(1)} blocks")
        if (!ZcRules.belowOwnRatio(ZcBondTerms.of(bond), o.price))
          refuse(s"the bond is at ${ZcRules.cover(ZcBondTerms.of(bond), o.price).map(ratioBps).getOrElse(0L)} bps, not below its " +
            s"${p(0)}; it can be liquidated at maturity, height ${bond.maturity}")
        Some(o)
    }
    val expression = ZcContracts.expression(if (rule == ZcRule.Oracle) ZcExpression.OracleLiquidation
      else ZcExpression.MaturityLiquidation, Some(bond.market)).bytes
    val snapshot = if (send) Some(fresh(Seq(bond.boxId) ++ oracle.map(_.box.boxId))) else None
    val spend = ZcUserTransactions.liquidate(bond, protocolInput(ctx, box), expression,
      oracle.map(o => dataInput(ctx, o.box.boxId)))
    val terms = Json.obj("assetId" -> bond.asset, "bondBoxId" -> bond.boxId, "rule" -> rule.name, "matured" -> matured,
      "feeReserveNanoErg" -> bond.feeReserve.toString,
      "coverBps" -> v.book.oracle.flatMap(o => ZcRules.cover(ZcBondTerms.of(bond), o.price)).map(ratioBps))
    execute(ctx, "liquidate", Some(bond.market), spend, Seq("vault"), terms, Seq.empty,
      if (bond.feeReserve <= ZcUserTransactions.TxFee) Seq("the fee reserve does not cover the miner fee: this wallet pays the rest")
      else Seq.empty, Seq.empty, if (oracle.isDefined) h + 1L else Long.MaxValue, body, send, snapshot, Seq.empty, None)
  })

  /**
   * POST /sigmafi/zc/bonds/:assetId/extinguish[/check]: closes a live bond directly by burning all three bond units
   * (the two locked in the bond plus the circulating unit this wallet holds). Needs this wallet to be the borrower,
   * works at any height, and sweeps collateral and the fee reserve straight to the borrower without BondVault.
   */
  def extinguish(assetId: String, body: JsObject, send: Boolean): JsObject = checked("extinguish", send)(withCtx { ctx =>
    val h = ctx.getHeight.toLong
    val v = view
    val m = matcher(v)
    val (bond, box) = liveBond(v, assetId)
    val borrower = walletHashes.getOrElse(bond.borrowerHash, refuse("only the borrower may extinguish a bond: it needs their box"))
    if (walletHeld(bond.asset) < 1L) {
      val sharesHint = m.book.shareVaults.values.find(_.record.bondAsset == bond.asset)
        .filter(sv => walletHeld(sv.record.shareId) > 0L)
        .map(sv => s"; this wallet holds shares of ${sv.record.shareId} — final-claim all ${sv.record.outstanding} shares first to recover the bond unit")
        .getOrElse("")
      refuse(s"this wallet does not hold bond unit ${bond.asset}$sharesHint")
    }
    val snapshot = if (send) Some(fresh(Seq(bond.boxId))) else None
    val auth = if (send) authorize(ctx, borrower, snapshot, preferToken = Some(bond.asset)) else { requireAuth(borrower); null }
    try {
      val authInput = Option(auth).map(_.inputs.head)
        .getOrElse(UTXO(borrower, ZcBoxes.MinBox, Seq(Token(ErgoId.create(bond.asset), 1L))).toDummyInput(ctx))
      val spend = ZcUserTransactions.extinguish(bond, protocolInput(ctx, box), authInput, borrower)
      val warnings =
        if (h >= bond.maturity)
          Seq("the bond has reached maturity and can be liquidated by anyone: extinguishing races any liquidation")
        else if (v.book.oracle.exists(o => ZcRules.belowOwnRatio(ZcBondTerms.of(bond), o.price)))
          Seq("the bond is under its liquidation ratio and can be liquidated by anyone: extinguishing races any liquidation")
        else Seq.empty
      val returnedErg = math.max(0L, bond.box.value - ZcUserTransactions.TxFee)
      val terms = Json.obj("assetId" -> bond.asset, "bondBoxId" -> bond.boxId, "market" -> bond.market.name,
        "repayment" -> bond.repayment.toString, "feeReserveNanoErg" -> bond.feeReserve.toString,
        "returns" -> Json.obj("nanoErg" -> returnedErg.toString,
          "tokens" -> bond.collateral.map(a => tokenJson(a.tokenId, a.amount))))
      execute(ctx, "extinguish", None, spend, Seq.empty, terms, Seq.empty, warnings, Seq.empty, Long.MaxValue,
        body, send, snapshot, Option(auth).toSeq, None, ownInputs = Seq(authInput))
    } catch { case NonFatal(ex) => Option(auth).foreach(_.release()); throw ex }
  })

  // ─── claims ────────────────────────────────────────────────────────────────

  /**
   * POST /sigmafi/zc/claims/:tokenId[/check]: claim a settled bond with its unit, burning all three, or with shares:
   * `units` of them (default all held). Holding every outstanding share is a final claim.
   */
  def claim(tokenId: String, body: JsObject, send: Boolean): JsObject = checked("claim", send)(withCtx { ctx =>
    val token = hexId(tokenId)
    val h = ctx.getHeight.toLong
    val v = view
    val m = matcher(v)
    val origin = resolver.origin(token, newBudget()).getOrElse(refuse(s"$token is not a proven bond unit or shares"))
    origin.kind match {
      case ZcKind.Order | ZcKind.PricedOrder =>
        if (m.book.bonds.contains(token)) refuse("the bond is live: it has not been repaid or liquidated yet")
        val settled = v.book.holding(token).flatMap(e => v.record(e.boxId)).collect { case (s: ZcSettlement, false) => s }
        if (settled.isEmpty) refuse("no confirmed settlement holds this bond's reserve yet")
        if (walletHeld(token) < 1L) refuse(s"this wallet does not hold bond unit $token")
        val snapshot = if (send) Some(fresh(settled.map(_.boxId))) else None
        val boxes = readAll(settled.map(_.boxId))
        val spend = ZcUserTransactions.claimUnit(token, boxes.map(b => protocolInput(ctx, b)), own)
        val terms = Json.obj("tokenId" -> token, "kind" -> "bondUnit", "final" -> true,
          "liquidated" -> settled.exists(_.liquidated), "settlements" -> settled.map(_.boxId),
          "payout" -> Json.obj("nanoErg" -> (settled.map(_.box.value).sum - ZcUserTransactions.TxFee).toString,
            "tokens" -> settled.flatMap(_.proceeds).map(a => tokenJson(a.tokenId, a.amount))))
        execute(ctx, "claim", None, spend, Seq.empty, terms, Seq.empty, Seq.empty, Seq.empty, Long.MaxValue, body, send,
          snapshot, Seq.empty, None)
      case ZcKind.FractionalMint | ZcKind.DutchAuctionMint =>
        val vault = m.book.shareVaults.get(token).map(_.record).getOrElse(refuse(s"no share vault holds the reserve of $token"))
        m.claimOf(token).left.foreach(r => refuse(s"the shares are not proven: $r"))
        val held = walletHeld(token)
        if (held == 0L) refuse(s"this wallet holds none of $token")
        val asked = long(body, "units")
        val burned = asked.getOrElse(held)
        if (burned < 1L || burned > held) throw LithosBadRequest(s"claim 1 to $held shares")
        val finalClaim = burned >= vault.outstanding
        if (finalClaim && held < vault.outstanding) refuse(s"a final claim burns all ${vault.outstanding} outstanding shares")
        if (!finalClaim && vault.holding)
          refuse("the bond has not settled into the vault yet: it needs a pull first, or a final claim with every share",
            if (m.book.settlements.contains(vault.bondAsset)) Seq("pull") else Seq.empty)
        if (!finalClaim && ZcRules.auctionOpen(vault) && h < ZcRules.claimableFrom(vault))
          refuse(s"the vault's auction runs until height ${ZcRules.claimableFrom(vault)}; claims wait for it", Seq("auction"))
        val snapshot = if (send) Some(fresh(Seq(vault.boxId))) else None
        val spend = ZcUserTransactions.claimShares(vault, protocolInput(ctx, read(vault.boxId)), burned, own)
        val rounded = if (finalClaim) Seq.empty else vault.box.assets.drop(1)
          .filter(t => BigInt(t.amount) * burned / vault.outstanding == 0)
          .map(t => s"this claim takes none of ${t.tokenId} (${t.amount} held); claim at least " +
            s"${(BigInt(vault.outstanding) + t.amount - 1) / t.amount} shares to receive some")
        val terms = Json.obj("tokenId" -> token, "kind" -> "shares", "final" -> finalClaim, "units" -> burned.toString,
          "outstanding" -> vault.outstanding.toString, "vaultBoxId" -> vault.boxId,
          "payout" -> Json.obj("nanoErg" -> spend.outputs.head.value.toString,
            "tokens" -> spend.outputs.head.tokens.map(t => tokenJson(t.id.toString, t.amount))))
        execute(ctx, "claim", None, spend, Seq("payout", "vault"), terms, Seq.empty, rounded, Seq.empty, Long.MaxValue, body,
          send, snapshot, Seq.empty, None)
      case _ => refuse(s"$token is not a bond unit or shares")
    }
  })

  // ─── shares and auctions ───────────────────────────────────────────────────

  /**
   * POST /sigmafi/zc/mints/:boxId[/check]: anyone runs a pending share mint or wrap mint and keeps its fee less the
   * miner fee. A wrap mint is its transaction's only input.
   */
  def mint(boxId: String, body: JsObject, send: Boolean): JsObject = checked("mint", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val v = view
    val box = read(id)
    ZcBoxes.read(box) match {
      case Some(req: ZcShareRequest) =>
        val Seq(n, pullFee, mintFee) = req.terms.take(3)
        if (n < 1L || n > 1000000000L || pullFee < MinBatchingFee || mintFee < MinBatchingFee || pullFee > box.value ||
          mintFee > box.value - pullFee) refuse("the request's terms are outside its contract's bounds, so only its owner can cancel it")
        val stateId = req.dutch.map(_ => v.book.stateOf(req.bondAsset).map(_.record)
          .getOrElse(refuse(s"no live bond or settlement holds the reserve of ${req.bondAsset}")))
        val snapshot = if (send) Some(fresh(Seq(id) ++ stateId.map(_.boxId))) else None
        val state = stateId.map {
          case b: ZcBond => (dataInput(ctx, b.boxId), b.repayment, None)
          case s: ZcSettlement =>
            val spent = Try(executionNode.indexedBoxById(s.bondBoxId)).toOption.flatMap(_.toOption).flatten
              .getOrElse(throw LithosUnavailable("the node's index has no record of the settled bond box"))
            val repayment = ZcBoxes.read(spent.box).collect { case b: ZcBond => b.repayment }
              .getOrElse(refuse("the settled bond box does not read as a bond"))
            val bytes = SigmaFiBoxes.input(spent.box, ctx).map(_.bytes)
              .getOrElse(throw LithosUnavailable("the settled bond box could not be rebuilt"))
            (dataInput(ctx, s.boxId), repayment, Some(bytes))
          case _ => refuse("the bond's reserve is in neither a bond nor a settlement")
        }
        val owner = scriptOf(req, pending(v, id), "share owner")
        val spend = ZcUserTransactions.mintShares(req, protocolInput(ctx, box), owner, state)
        val terms = Json.obj("kind" -> "shares", "requestBoxId" -> id, "shareTokenId" -> req.shareId, "shares" -> n.toString,
          "bondId" -> req.bondAsset, "mintFeeNanoErg" -> mintFee.toString)
        execute(ctx, "mint", None, spend, Seq("shares", "vault"), terms, Seq(fee("mint", mintFee, "earned")), Seq.empty,
          Seq.empty, Long.MaxValue, body, send, snapshot, Seq.empty, None)
      case Some(req: ZcWrapRequest) =>
        if (req.mintFee < MinBatchingFee || req.mintFee > box.value - 2L * ZcBoxes.MinBox)
          refuse("the request's mint fee leaves too little for the receipt and vault: only its owner can cancel it")
        if (req.mintFee - ZcUserTransactions.TxFee < 0L) refuse("the mint fee does not cover the miner fee")
        val snapshot = if (send) Some(fresh(Seq(id))) else None
        val owner = scriptOf(req, pending(v, id), "wrap owner")
        val spend = ZcUserTransactions.mintWrap(req, protocolInput(ctx, box), owner)
        val terms = Json.obj("kind" -> "wrap", "requestBoxId" -> id, "receiptId" -> req.receiptId,
          "tokenId" -> req.wrapped.tokenId, "amount" -> req.wrapped.amount.toString, "mintFeeNanoErg" -> req.mintFee.toString)
        execute(ctx, "mint", None, spend, Seq("receipt", "vault"), terms, Seq(fee("mint", req.mintFee, "earned")), Seq.empty,
          Seq.empty, Long.MaxValue, body, send, snapshot, Seq.empty, None)
      case _ => throw LithosNotFound(s"$id is not a share or wrap mint request")
    }
  })

  /** POST /sigmafi/zc/vaults/:boxId/pull[/check]: anyone pulls a settled bond into its share vault for the pull fee. */
  def pull(boxId: String, body: JsObject, send: Boolean): JsObject = checked("pull", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val h = ctx.getHeight.toLong
    val v = view
    val box = read(id)
    val vault = ZcBoxes.read(box).collect { case s: ZcShareVault => s }.getOrElse(throw LithosNotFound(s"$id is not a share vault"))
    if (!vault.holding) refuse("the vault has already pulled its bond's settlement")
    if (vault.pullFee < 0L || vault.pullFee > box.value) refuse("the vault's pull fee is out of bounds")
    val source = v.book.stateOf(vault.bondAsset).map(_.record).collect { case s: ZcSettlement => s }
      .getOrElse(refuse(s"bond ${vault.bondAsset} has not been repaid or liquidated yet"))
    val snapshot = if (send) Some(fresh(Seq(id, source.boxId))) else None
    val spend = ZcUserTransactions.pull(vault, protocolInput(ctx, box), source, protocolInput(ctx, read(source.boxId)), h)
    val state = spend.outputs.head.registers.lift(2).map(_.getValue).collect { case b: java.lang.Byte => b.intValue }
    val terms = Json.obj("vaultBoxId" -> id, "bondId" -> vault.bondAsset, "settlementBoxId" -> source.boxId,
      "liquidated" -> source.liquidated, "state" -> (if (vault.dutch) state else None), "pullFeeNanoErg" -> vault.pullFee.toString)
    execute(ctx, "pull", None, spend, Seq("vault"), terms, Seq(fee("pull", vault.pullFee, "earned")), Seq.empty, Seq.empty,
      Long.MaxValue, body, send, snapshot, Seq.empty, None)
  })

  /**
   * POST /sigmafi/zc/vaults/:boxId/buy[/check]: buy an open vault auction. State 1 tops the vault up to its target;
   * state 4 pays the falling price for the thin items only.
   */
  def buyVault(boxId: String, body: JsObject, send: Boolean): JsObject = checked("buyVault", send)(withCtx { ctx =>
    val id = hexId(boxId)
    val h = ctx.getHeight.toLong
    val box = read(id)
    val vault = ZcBoxes.read(box).collect { case s: ZcShareVault => s }.getOrElse(throw LithosNotFound(s"$id is not a share vault"))
    if (!ZcRules.auctionOpen(vault)) refuse("the vault has no open auction")
    if (vault.auction(3) < 1L) refuse("the auction has no length")
    val excess = ZcRules.excessAuction(vault)
    val end = vault.auction(6) + vault.auction(3)
    if (excess && (ZcRules.excessPrice(vault, h).isEmpty || h + 1L >= end))
      refuse(s"the excess auction's price reaches zero at height $end: holders claim the lot in kind")
    val market = ZcMarket.all.find(_.tokenId == ZcRules.vaultCurrency(vault))
      .getOrElse(refuse("the vault's currency is not a market this client knows"))
    val snapshot = if (send) Some(fresh(Seq(id))) else None
    val spend = ZcUserTransactions.buyFromVault(vault, protocolInput(ctx, box), h)
    val before = ZcRules.currencyHeld(vault)
    val after = market.tokenId.fold(spend.outputs.head.value)(c => spend.outputs.head.tokens.filter(_.id.toString == c).map(_.amount).sum)
    val terms = Json.obj("vaultBoxId" -> id, "kind" -> (if (excess) "excess" else "floor"), "market" -> market.name,
      "pays" -> (after - before).toString, "vaultHoldsAfter" -> after.toString)
    execute(ctx, "buyVault", Some(market), spend, Seq("vault"), terms, Seq.empty, Seq.empty, Seq.empty,
      if (excess) end - 2L else vault.auction(6) + vault.auction(4), body, send, snapshot, Seq.empty, None)
  })

  /** POST /sigmafi/zc/wraps/:receiptId/unwrap[/check]: burn all three receipts and take the wrapped token back. */
  def unwrap(receiptId: String, body: JsObject, send: Boolean): JsObject = checked("unwrap", send)(withCtx { ctx =>
    val receipt = hexId(receiptId)
    val v = view
    val m = matcher(v)
    val vault = m.book.wrapVaults.get(receipt).map(_.record).getOrElse(refuse(s"no wrap vault holds the reserve of $receipt"))
    if (!resolver.origin(receipt, newBudget()).exists(_.kind == ZcKind.WrapMint)) refuse(s"$receipt is not a proven wrap receipt")
    if (walletHeld(receipt) < 1L) refuse(s"this wallet does not hold receipt $receipt")
    val snapshot = if (send) Some(fresh(Seq(vault.boxId))) else None
    val spend = ZcUserTransactions.unwrap(vault, protocolInput(ctx, read(vault.boxId)), own)
    val terms = Json.obj("receiptId" -> receipt, "vaultBoxId" -> vault.boxId, "tokenId" -> vault.wrapped.tokenId,
      "amount" -> vault.wrapped.amount.toString, "nanoErg" -> vault.box.value.toString)
    execute(ctx, "unwrap", None, spend, Seq.empty, terms, Seq.empty, Seq.empty, Seq.empty, Long.MaxValue, body, send,
      snapshot, Seq.empty, None)
  })

  // ─── cancelling ────────────────────────────────────────────────────────────

  /**
   * POST /sigmafi/zc/boxes/:boxId/cancel[/check]: any order, offer, ask, lot, share or wrap request this wallet made,
   * swept back with one of its own boxes at the creator's script, which the contracts take as the creator's consent.
   */
  def cancel(boxId: String, send: Boolean): JsObject = checked("cancel", send)(withCtx { ctx =>
    if (!boxId.matches("[0-9a-fA-F]{64}")) throw LithosBadRequest(s"'$boxId' is not a box id")
    val box = read(boxId)
    val record = ZcBoxes.read(box).getOrElse(throw LithosNotFound(s"box $boxId is not a ZC box"))
    val creatorHash = record match {
      case _: ZcOrder | _: ZcOffer | _: ZcAsk | _: ZcLot | _: ZcShareRequest | _: ZcWrapRequest => record.namedHash.get
      case _ => refuse(s"a ${record.script.kind} box has no cancel")
    }
    val creator = walletHashes.getOrElse(creatorHash, throw LithosNotFound(s"box $boxId was not made by this wallet"))
    val expired = record match {
      case o: ZcOrder => ctx.getHeight >= o.expiry
      case o: ZcOffer => ctx.getHeight >= o.expiry
      case a: ZcAsk => ctx.getHeight >= a.expiry
      case l: ZcLot => ctx.getHeight >= l.expiry
      case _ => false
    }
    val quote = Json.obj("action" -> "cancel", "boxId" -> boxId, "contract" -> record.script.kind.name,
      "paysNanoErgs" -> ZcUserTransactions.TxFee.toString, "receivesNanoErgs" -> (box.value - ZcUserTransactions.TxFee).toString,
      "receivesTokens" -> box.assets.map(a => tokenJson(a.tokenId, a.amount)),
      "fees" -> Seq(fee("miner", ZcUserTransactions.TxFee, "spent")),
      "warnings" -> (if (expired) Seq("the box is past its expiry: anyone may refund it first, keeping its fee") else Seq.empty[String]),
      "refusals" -> JsArray())
    if (!send) {
      requireAuth(creator)
      quote
    } else {
      val snapshot = mempoolSnapshot()
      BatchingMempool.spenders(snapshot).get(boxId).flatMap(_.headOption).foreach(tx =>
        throw LithosStateChanged(s"box $boxId is already spent by unconfirmed transaction ${tx.id}"))
      val auth = reserveAuth(ctx, creator, snapshot)
      try {
        val input = SigmaFiBoxes.input(box, ctx).getOrElse(throw LithosUnavailable(s"box $boxId could not be rebuilt"))
        val signed = sign(ctx, ZcUserTransactions.cancel(ctx, wallet, input, auth.inputs.head, creator))
        val sent = broadcast(signed, Seq(auth))
        result(signed, sent, Some(outputId(signed, 0)), Seq.empty)
      } catch { case NonFatal(ex) => auth.release(); throw ex }
    }
  })

  // ─── one spend: quote, or sign and send ────────────────────────────────────

  /**
   * The quote of `spend`, or its send. `roles` names the outputs in order; those at this wallet's scripts are what it
   * receives, the rest what it creates. `ownInputs` are protocol inputs that are this wallet's own boxes.
   */
  private def execute(ctx: BlockchainContext, action: String, market: Option[ZcMarket], spend: ZcSpend, roles: Seq[String],
                      terms: JsObject, fees: Seq[JsObject], warnings: Seq[String], competing: Seq[String], validThrough: Long,
                      body: JsObject, send: Boolean, snapshot: Option[CompleteMempool.Snapshot], held: Seq[FundingAllocation],
                      assetId: Option[String], ownInputs: Seq[InputUTXO] = Seq.empty): JsObject = {
    val ownIds = (ownInputs ++ held.flatMap(_.inputs)).map(_.id.toString).toSet
    val outside = spend.inputs.filterNot(i => ownIds(i.id.toString))
    val (outs, toWallet) = if (spend.sweep.isDefined) (Seq.empty[UTXO], Seq.empty[UTXO])
      else spend.outputs.partition(o => !walletTrees.contains(o.contract.ergoTreeHex))
    val erg = outside.map(_.value).sum - outs.map(_.value).sum - ZcUserTransactions.TxFee
    val tally = (ts: Seq[Token]) => ts.groupBy(_.id.toString).map { case (k, v) => k -> v.map(_.amount).sum }
    val in = tally(outside.flatMap(_.tokens))
    val used = tally(outs.flatMap(_.tokens) ++ spend.burn)
    // A minted token is this wallet's only as far as its own outputs hold it
    val mintedHere = spend.minted.map(id => id -> tally(toWallet.flatMap(_.tokens)).getOrElse(id, 0L))
    val net = (((in.keySet ++ used.keySet) -- spend.minted).map(k => k -> (in.getOrElse(k, 0L) - used.getOrElse(k, 0L)))
      .toMap ++ mintedHere).filter(_._2 != 0L)
    def marketNet: Long = market.flatMap(_.tokenId).fold(erg)(id => net.getOrElse(id, 0L))
    val quote = Json.obj("action" -> action, "at" -> ZcJson.at(ZcMarketBook.of(view)).value("at"),
      "paysNanoErgs" -> math.max(0L, -erg).toString, "receivesNanoErgs" -> math.max(0L, erg).toString,
      "paysTokens" -> net.collect { case (id, n) if n < 0L => tokenJson(id, -n) }.toSeq,
      "receivesTokens" -> net.collect { case (id, n) if n > 0L => tokenJson(id, n) }.toSeq,
      "fees" -> (fee("miner", ZcUserTransactions.TxFee, "spent") +: fees),
      "creates" -> spend.outputs.zipAll(roles, null, "output").filter(_._1 != null)
        .filterNot(o => walletTrees.contains(o._1.contract.ergoTreeHex)).map { case (o, role) =>
          Json.obj("role" -> role, "contract" -> ZcContracts.classify(o.contract.ergoTreeHex).map(_.kind.name),
            "nanoErg" -> o.value.toString, "tokens" -> o.tokens.map(t => tokenJson(t.id.toString, t.amount)))
        },
      "terms" -> terms, "validFrom" -> ctx.getHeight, "validThrough" -> (if (validThrough == Long.MaxValue) None else Some(validThrough)),
      "matchesNow" -> competing, "warnings" -> warnings, "refusals" -> JsArray())
    if (!send) quote
    else {
      limit(body, math.max(0L, -marketNet), math.max(0L, marketNet))
      val plan = ZcUserTransactions.plan(ctx, wallet, spend)
      val (signed, sent) = sendPlan(ctx, plan, held, snapshot)
      result(signed, sent, Some(outputId(signed, 0)), warnings, assetId)
    }
  }

  private def sendPlan(ctx: BlockchainContext, plan: SFPlan, held: Seq[FundingAllocation],
                       snapshot: Option[CompleteMempool.Snapshot]): (SignedTransaction, EngineBroadcast.Result) = {
    stillRunning()
    if (ZcUserTransactions.selfFunded(plan)) {
      val signed = sign(ctx, plan.build(Seq.empty))
      (signed, broadcast(signed, held))
    } else {
      val reservation = walletSelector.reserveAvoiding(plan.value, plan.tokens, plan.avoidTokens)
      val signed = try sign(ctx, plan.build(reservation.inputs)) catch { case NonFatal(ex) => reservation.release(); throw ex }
      (signed, broadcast(signed, held :+ reservation))
    }
  }

  // ─── reads ─────────────────────────────────────────────────────────────────

  private def newBudget(): ZcBudget = new ZcBudget(ReadsPerWrite)

  private def h1(ctx: BlockchainContext): Long = ctx.getHeight.toLong + 1L

  private def hexId(id: String): String = {
    val x = id.trim.toLowerCase
    if (!x.matches("[0-9a-f]{64}")) throw LithosBadRequest(s"'$id' is not a 64-character hex id")
    x
  }

  private def long(body: JsValue, field: String): Option[Long] = (body \ field).toOption.filterNot(_ == JsNull).map {
    case JsString(s) => Try(s.trim.toLong).getOrElse(throw LithosBadRequest(s"$field must be an integer"))
    case JsNumber(n) if n.isValidLong => n.toLongExact
    case _ => throw LithosBadRequest(s"$field must be an integer")
  }

  private def pending(v: ZcView, boxId: String): Boolean = v.record(boxId).exists(_._2)

  /** The live bond of `assetId` the tracker holds, re-read from the node. */
  private def liveBond(v: ZcView, assetId: String): (ZcBond, NodeBox) = {
    val asset = hexId(assetId)
    val state = v.book.stateOf(asset).map(_.record).orElse(v.mempool.flatMap(_.pending.values.map(_.record)
      .find(r => r.box.assets.headOption.exists(a => a.tokenId == asset && a.amount == 2L))))
    state match {
      case Some(b: ZcBond) =>
        val box = read(b.boxId)
        val bond = ZcBoxes.read(box).collect { case x: ZcBond => x }.getOrElse(throw LithosNotFound(s"${b.boxId} is not a bond"))
        (bond, box)
      case Some(_: ZcSettlement) => refuse(s"bond $asset is settled")
      case _ => throw LithosNotFound(s"no live bond holds the reserve of $asset")
    }
  }

  /** The confirmed oracle a trade may read; refused while a mempool refresh spends it. */
  private def oracleOf(v: ZcView): ZcOracle =
    if (v.refreshPending) refuse("the oracle pool is being refreshed: a mempool transaction spends its box, so a transaction " +
      "reading it would fail once that confirms. Try after the next block")
    else v.book.oracle.getOrElse(refuse("the node's index has no single USD oracle pool box"))

  /**
   * zcs1: nothing this wallet buys or fills may start below the bond's own oracle ratio, which anyone could liquidate at
   * once. Refused unless the body sets `allowLiquidatable`.
   */
  private def coverCheck(v: ZcView, bond: ZcBondTerms, body: JsObject, why: String, instead: String): Unit =
    v.book.oracle.foreach { o =>
      if (ZcRules.belowOwnRatio(bond, o.price) && !(body \ "allowLiquidatable").asOpt[Boolean].contains(true))
        refuse(s"$why at ${ZcRules.cover(bond, o.price).map(ratioBps).getOrElse(0L)} bps of its repayment, under its own ratio " +
          s"at the oracle price: anyone could liquidate it at once, taking all the collateral. $instead, or set allowLiquidatable")
    }

  /** The script `record` names, from this wallet or as the box discloses it. */
  private def scriptOf(record: ZcRecord, pending: Boolean, who: String): Contract =
    record.namedHash.flatMap(h => walletHashes.get(h).orElse(resolver.script(record, pending, newBudget())
      .flatMap(SigmaFiBoxes.parse).map(Contract(_))))
      .getOrElse(refuse(s"box ${record.boxId} does not disclose its $who's script (zcs1 script disclosure), so only the " +
        s"$who can act on it"))

  /** How much of `tokenId` this wallet holds, confirmed or pending. */
  private def walletHeld(tokenId: String): Long =
    executionNode.walletBalancesWithUnconfirmed() match {
      case Success(b) => b.assets.filter(_.tokenId == tokenId).map(_.amount).sum
      case Failure(ex) => throw LithosUnavailable(s"could not read this wallet's balance: ${ex.getMessage}", ex)
    }

  private def read(boxId: String): NodeBox = executionNode.boxesWithPoolByIds(Seq(boxId)) match {
    case Success(found) => found.find(_.boxId.equalsIgnoreCase(boxId)).getOrElse(throw LithosNotFound(s"no unspent box has id $boxId"))
    case Failure(ex) => throw LithosUnavailable(s"could not read box $boxId from the node: ${ex.getMessage}", ex)
  }

  private def readAll(ids: Seq[String]): Seq[NodeBox] = executionNode.boxesWithPoolByIds(ids) match {
    case Success(found) =>
      ids.map(id => found.find(_.boxId.equalsIgnoreCase(id)).getOrElse(throw LithosStateChanged(s"box $id is no longer unspent")))
    case Failure(ex) => throw LithosUnavailable(s"could not read boxes from the node: ${ex.getMessage}", ex)
  }

  private def protocolInput(ctx: BlockchainContext, box: NodeBox): InputUTXO =
    SigmaFiBoxes.input(box, ctx).getOrElse(throw LithosUnavailable(s"box ${box.boxId} could not be rebuilt"))

  private def dataInput(ctx: BlockchainContext, boxId: String): InputUTXO = protocolInput(ctx, read(boxId))

  // ─── authorizing boxes ─────────────────────────────────────────────────────

  private def authCandidates(creator: Contract, spent: Set[String], preferToken: Option[String] = None): Seq[NodeBox] =
    executionNode.unspentBoxesByErgoTree(creator.ergoTreeHex, Paging(0, Batcher.PageSize), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly) match {
      case Success(boxes) => boxes.map(_.box).filterNot(b => spent(b.boxId))
        .sortBy(b => (!preferToken.exists(t => b.assets.exists(_.tokenId == t)), b.value))
      case Failure(ex) => throw LithosUnavailable(s"could not read this wallet's boxes: ${ex.getMessage}", ex)
    }

  private def requireAuth(creator: Contract): Unit =
    if (authCandidates(creator, Set.empty).isEmpty)
      refuse("this wallet holds no box at the creator's address, which this transaction must spend")

  private def reserveAuth(ctx: BlockchainContext, creator: Contract, snapshot: CompleteMempool.Snapshot,
                          preferToken: Option[String] = None): FundingAllocation = {
    stillRunning()
    authCandidates(creator, snapshot.spent, preferToken).take(MaxAuthTries).iterator
      .flatMap(box => SigmaFiBoxes.input(box, ctx))
      .map(input => Try(walletSelector.reserveKnown(Seq(input))))
      .collectFirst { case Success(allocation) => allocation }
      .getOrElse(refuse("no box of this wallet at the creator's address is free to authorize the transaction"))
  }

  /** A box at `creator` to authorize a send; a check only confirms one exists. */
  private def authorize(ctx: BlockchainContext, creator: Contract, snapshot: Option[CompleteMempool.Snapshot],
                        preferToken: Option[String] = None): FundingAllocation =
    snapshot match {
      case Some(s) => reserveAuth(ctx, creator, s, preferToken)
      case None => requireAuth(creator); null
    }

  // ─── plumbing ──────────────────────────────────────────────────────────────

  /**
   * A fresh mempool observation, refusing with 409 when an unconfirmed transaction already spends any of `ids`: a send
   * never builds on a box the mempool is about to take.
   */
  private def fresh(ids: Seq[String]): CompleteMempool.Snapshot = {
    val snapshot = mempoolSnapshot()
    val spenders = BatchingMempool.spenders(snapshot)
    ids.foreach(id => spenders.get(id).flatMap(_.headOption).foreach(tx =>
      throw LithosStateChanged(s"box $id is already spent by unconfirmed transaction ${tx.id}")))
    snapshot
  }

  private def limit(body: JsObject, pays: Long, receives: Long): Unit = {
    def bound(field: String): Option[Long] = (body \ "limits" \ field).toOption.filterNot(_ == JsNull).map {
      case JsString(s) => Try(s.trim.toLong).getOrElse(throw LithosBadRequest(s"limits.$field must be an integer"))
      case JsNumber(n) if n.isValidLong => n.toLongExact
      case _ => throw LithosBadRequest(s"limits.$field must be an integer")
    }
    bound("maxPay").filter(pays > _).foreach(max =>
      throw LithosStateChanged(s"the transaction now pays $pays, over the $max the request allows"))
    bound("minReceive").filter(receives < _).foreach(min =>
      throw LithosStateChanged(s"the transaction now receives $receives, under the $min the request asks"))
  }

  private def result(signed: SignedTransaction, sent: EngineBroadcast.Result, boxId: Option[String],
                     warnings: Seq[String], assetId: Option[String] = None): JsObject =
    Json.obj("txId" -> sent.txId, "outcome" -> sent.outcome, "boxId" -> boxId, "assetId" -> assetId, "warnings" -> warnings)

  /** A complete, fresh mempool observation: anything that spends never builds on the tracker's overlay. */
  protected def mempoolSnapshot(): CompleteMempool.Snapshot = {
    val wait = Batcher.ObservationTimeout
    val observed = Try(Await.result((walletSelector.walletRef ? CompleteMempool.Refresh)(Timeout(wait))
      .mapTo[CompleteMempool.Observation], wait)).getOrElse(throw LithosUnavailable("no mempool observation arrived"))
    if (!observed.fresh) throw LithosUnavailable("no fresh mempool observation")
    observed.snapshot.get
  }

  private def fundAndSend(ctx: BlockchainContext, plan: SFPlan): (SignedTransaction, EngineBroadcast.Result) = {
    stillRunning()
    val reservation = walletSelector.reserveAvoiding(plan.value, plan.tokens, plan.avoidTokens)
    val signed = try sign(ctx, plan.build(reservation.inputs)) catch { case NonFatal(ex) => reservation.release(); throw ex }
    (signed, broadcast(signed, Seq(reservation)))
  }

  private def sign(ctx: BlockchainContext, unsigned: org.ergoplatform.appkit.UnsignedTransaction): SignedTransaction = {
    stillRunning()
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

  private def broadcast(signed: SignedTransaction, funding: Seq[FundingAllocation]): EngineBroadcast.Result =
    try new EngineBroadcast(walletSelector.walletRef, executionNode)(walletSelector.executionContext)
      .send(signed, funding, operation(signed), alive)
    catch { case _: IllegalArgumentException if !alive() => throw stopped }

  private def stillRunning(): Unit = if (!alive()) throw stopped
}

object ZcWriteExecution {

  private final val MaxAuthTries = 5

  /** Node reads one write may make for origins and scripts. */
  final val ReadsPerWrite = 16

  /** zcs1's least and default liquidation fee reserve a wallet fill funds: 0.05 ERG. */
  final val DefaultReserve = 50000000L

  def operation(signed: SignedTransaction): String =
    "sigmafi-zc:" + _root_.transactions.candidate.BlockTxMessages.CandidateTx.txId(signed)

  def stopped: LithosUnavailable = LithosUnavailable("the transaction engine stopped this request before sending it, " +
    "so nothing was sent")

  private def outputId(signed: SignedTransaction, index: Int): String = signed.getOutputsToSpend.get(index).getId.toString

  private def tokenJson(id: String, n: Long): JsObject = Json.obj("tokenId" -> id, "amount" -> n.toString)

  private def fee(name: String, nanoErg: Long, kind: String): JsObject =
    Json.obj("name" -> name, "nanoErg" -> nanoErg.toString, "kind" -> kind)
}
