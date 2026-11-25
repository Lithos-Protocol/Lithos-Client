package transactions.batching.sigmafi.zc

import api.zc.ZcDrafts
import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, InputBox, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import support.FakeNodeContext
import transactions.batching.Batcher
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.batching.sigmafi.zc.ZcUserTransactions.ZcSpend
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Every ZC write after posting, signed against the pinned contract it spends: a signature is the contract accepting
 * the transaction at the CLI's positions and context variables. Each lifecycle chains real outputs into the next
 * spend, and the key paths also show the contract refusing a wrong shape.
 */
class ZcLifecycleSpec extends AnyFlatSpec with Matchers {

  import ZcFixtures.{Erg => _, _}
  import ZcPricing.ShareCount

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Reserve = 50000000L
  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 2))
  private val me: Contract = wallet.contract
  private val ownHash = Hex.toHexString(me.hashedPropBytes)
  /** A seller this wallet can sign for but never funds from, so no funding box doubles as the seller's cancel. */
  private val seller: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val sellerHash = Hex.toHexString(seller.hashedPropBytes)
  private var seq = 0

  private def nextId(): ErgoId = { seq += 1; ErgoId.create(f"$seq%064x") }

  private def funds(ctx: BlockchainContext, erg: Long, tokens: Token*): InputUTXO =
    UTXO(me, erg, tokens).toInput(ctx, nextId(), 0)

  private def seeded(ctx: BlockchainContext, u: UTXO): InputUTXO = u.toInput(ctx, nextId(), 0)

  private def token(id: String, n: Long): Token = Token(ErgoId.create(id), n)

  private def nodeOf(b: InputBox, txId: String, index: Int): NodeBox =
    NodeBox(b.getId.toString, txId, b.getValue, index, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
      b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))

  private def out(signed: SignedTransaction, i: Int): NodeBox = {
    val b = signed.getOutputsToSpend.get(i)
    nodeOf(b, CandidateTx.txId(signed), i)
  }

  private def nodeOf(in: InputUTXO): NodeBox = nodeOf(in.input, in.input.getTransactionId, in.input.getTransactionIndex)

  private def input(ctx: BlockchainContext, b: NodeBox): InputUTXO = SigmaFiBoxes.input(b, ctx).get

  private def read[A](b: NodeBox): A = ZcBoxes.read(b).getOrElse(fail(s"box ${b.boxId} does not read as a ZC box"))
    .asInstanceOf[A]

  /** `spend` built and signed with `funding` added when the plan asks for any. */
  private def sign(ctx: BlockchainContext, spend: ZcSpend, funding: InputUTXO*): SignedTransaction = {
    val plan = ZcUserTransactions.plan(ctx, wallet, spend)
    Batcher.withNodeMinimums(ctx, wallet.sign(plan.build(if (ZcUserTransactions.selfFunded(plan)) Seq.empty else funding)))
  }

  private def posted(ctx: BlockchainContext, box: UTXO, funding: InputUTXO*): NodeBox =
    out(Batcher.withNodeMinimums(ctx, wallet.sign(ZcUserTransactions.post(ctx, wallet, box, me).build(funding))), 0)

  private def bytes(hex: String): ErgoValue[_] = ZcFixtures.bytes(hex)

  /** A bond as a fill long ago would have made it, moved at `moved`, named for this wallet. */
  private def bond(ctx: BlockchainContext, asset: String, collateralErg: Long, collateral: Seq[Token], repayment: Long,
                   rule: ZcExpression, start: Long, maturity: Long, moved: Int, payload: Seq[Long] = Seq.empty,
                   market: ZcMarket = ZcMarket.Erg): InputUTXO = {
    val terms = Seq(repayment, Erg, repayment, maturity, start + 10L, 5000000L, 0L) ++ payload
    seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(market)).contract, collateralErg + Reserve,
      token(asset, 2L) +: collateral, Seq(bytes(asset), bytes(ownHash), longs(terms: _*), tokens(), bytes(expr(rule, market)),
        longs(Reserve, start, maturity)), Some(moved)))
  }

  private def oracle(ctx: BlockchainContext, price: Long, created: Int): InputUTXO =
    seeded(ctx, UTXO(Contract.SIGMA_TRUE, MinBox, Seq(token(ZcContracts.OracleNft, 1L)), Seq(ErgoValue.of(price), ErgoValue.of(1)),
      Some(created)))

  private def orderBody(h: Int, maturityRule: Boolean) = {
    val base = Json.obj("market" -> "ERG", "principal" -> "1000000000", "repayment" -> "1100000000",
      "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
      "collateral" -> Json.obj("nanoErg" -> "3000000000"))
    if (maturityRule) base ++ Json.obj("pricing" -> "fixed", "rule" -> Json.obj("kind" -> "maturity"))
    else base ++ Json.obj("pricing" -> "yield", "yield" -> Json.obj("startBps" -> 800, "endBps" -> 1200),
      "rule" -> Json.obj("kind" -> "oracle", "ratioBps" -> 15000))
  }

  /** A posted order of this wallet's, filled by this wallet: the fill, and the order it filled. */
  private def filled(ctx: BlockchainContext, maturityRule: Boolean = true, oracleCreated: Option[Int] = None): (SignedTransaction, ZcOrder) = {
    val h = ctx.getHeight
    val draft = ZcDrafts.orderOf(orderBody(h, maturityRule), h.toLong)
    val order = read[ZcOrder](posted(ctx, ZcUserTransactions.orderBox(draft, ownHash, h), funds(ctx, 4 * Erg)))
    val maturity = ZcRules.orderMaturity(order, h).toOption.get
    val principal = ZcRules.resolvePrincipal(order, h, maturity).toOption.get
    val spend = ZcUserTransactions.fillOrder(ZcUserTransactions.resolved(order, principal), input(ctx, order.box), me, me, h,
      maturity, Reserve, oracleCreated.map(c => oracle(ctx, 465116279L, c)))
    (sign(ctx, spend, funds(ctx, 2 * Erg)), order)
  }

  // ─── lending ───────────────────────────────────────────────────────────────

  "A wallet fill" should "mint the unit with zcs1 metadata, lock the bond, and pay the borrower tagged" in {
    client.execute { ctx =>
      val (fill, order) = filled(ctx)
      val unit = out(fill, 0)
      unit.assets shouldBe Seq(NodeAsset(order.boxId, 1L))
      new String(Hex.decode(unit.additionalRegisters.get(4).get).drop(2), "UTF-8") should startWith("ZCS-Bond-Unit-")
      val b = read[ZcBond](out(fill, 1))
      b.asset shouldBe order.boxId
      b.terms shouldBe order.terms
      b.feeReserve shouldBe Reserve
      b.start shouldBe ctx.getHeight.toLong
      b.maturity shouldBe order.terms(3)
      b.collateralErg shouldBe order.box.value - order.expiryFee
      val principal = out(fill, 2)
      principal.value shouldBe order.expiryFee + order.principalErg
      principal.additionalRegisters.get(4) shouldBe Some(bytes(order.boxId).toHex)
    }
  }

  "A guarded yield order" should "fill with the oracle as a data input, and be refused with a stale one" in {
    client.execute { ctx =>
      val h = ctx.getHeight
      val (fill, order) = filled(ctx, maturityRule = false, oracleCreated = Some(h - 5))
      order.guardHash shouldBe defined
      order.priced shouldBe true
      read[ZcBond](out(fill, 1)).terms(1) should be >= order.principalErg
      an[Exception] should be thrownBy filled(ctx, maturityRule = false, oracleCreated = Some(h - 1000))
    }
  }

  "Buying from a sale order" should "pay the seller tagged and continue the rest, and be refused underpaid" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val sold = "ab" * 32
      val ask = read[ZcAsk](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(token(sold, 10L)), Seq(bytes(sellerHash), longs(10 * Erg, 10L, 1L, h + 100L))))))
      val price = ZcRules.askPrice(ask, 4L, None, h).toOption.get
      price shouldBe 4 * Erg
      val signed = sign(ctx, ZcUserTransactions.buyAsk(ask, input(ctx, ask.box), seller, 4L, price, None, None), funds(ctx, 5 * Erg))
      ZcBoxes.payoutTag(out(signed, 0)) shouldBe Some(ask.boxId -> 4L)
      val rest = read[ZcAsk](out(signed, 1))
      rest.token.amount shouldBe 6L
      rest.predecessor shouldBe Some(ask.boxId)
      an[Exception] should be thrownBy sign(ctx, ZcUserTransactions.buyAsk(ask, input(ctx, ask.box), seller, 4L, price - 1L, None,
        None), funds(ctx, 5 * Erg))
    }
  }

  "Buying from a yield ask" should "run its expression against the bond as data input 0" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val asset = "c1" * 32
      val live = bond(ctx, asset, 3 * Erg, Seq.empty, 2 * Erg, ZcExpression.MaturityLiquidation, h - 10, h + 100000, h.toInt - 10)
      val pricing = expr(ZcExpression.YieldAskPricing)
      val ask = read[ZcAsk](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.PricedAsk, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(token(asset, 1L)), Seq(bytes(ownHash), longs(1L, 1000000000000L, 1L, h + 500L, 1L, h, 900L, h + 500L, 900L),
          bytes(pricing), bytes(asset))))))
      val b = read[ZcBond](nodeOf(live))
      val price = ZcRules.askPrice(ask, 1L, Some(ZcBondTerms.of(b)), h).toOption.get
      val signed = sign(ctx, ZcUserTransactions.buyAsk(ask, input(ctx, ask.box), me, 1L, price, Some(live), None), funds(ctx, 3 * Erg))
      ZcBoxes.payoutTag(out(signed, 0)) shouldBe Some(ask.boxId -> 1L)
    }
  }

  "Buying a lot" should "pay its current price to the seller, and be refused a nanoERG short" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val box = ZcUserTransactions.lotBox(ZcMarket.Erg, Seq(token("ee" * 32, 5L)), 0L,
        Seq(2 * Erg, Erg, h - 10L, h + 100L, h + 200L, 2000000L), sellerHash)
      val lot = read[ZcLot](posted(ctx, box, funds(ctx, Erg, token("ee" * 32, 5L))))
      ZcRules.lotValid(lot) shouldBe true
      val price = ZcRules.lotPrice(lot, h)
      price should (be > Erg and be < 2 * Erg)
      val signed = sign(ctx, ZcUserTransactions.buyLot(lot, input(ctx, lot.box), seller, price), funds(ctx, 3 * Erg))
      out(signed, 0).value shouldBe lot.box.value + price
      an[Exception] should be thrownBy sign(ctx, ZcUserTransactions.buyLot(lot, input(ctx, lot.box), seller, price - 1L),
        funds(ctx, 3 * Erg))
    }
  }

  // ─── borrowing ─────────────────────────────────────────────────────────────

  "Repaying" should "settle into the vault, and the unit then claims it, burning all three" in {
    client.execute { ctx =>
      val (fill, _) = filled(ctx)
      val b = read[ZcBond](out(fill, 1))
      val repaid = sign(ctx, ZcUserTransactions.repay(b, input(ctx, b.box), me, 0L, None), funds(ctx, 2 * Erg))
      val settlement = read[ZcSettlement](out(repaid, 0))
      settlement.liquidated shouldBe false
      settlement.bondBoxId shouldBe b.boxId
      settlement.box.value shouldBe Reserve + b.repayment
      out(repaid, 1).value shouldBe b.collateralErg
      val unit = input(ctx, out(fill, 0))
      val claimed = sign(ctx, ZcUserTransactions.claimUnit(b.asset, Seq(input(ctx, settlement.box)), me), unit)
      claimed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId.toString) should not contain b.asset
      claimed.getOutputsToSpend.get(0).getValue.longValue shouldBe settlement.box.value + unit.value - ZcUserTransactions.TxFee
    }
  }

  it should "take part of the repayment from the collateral with the borrower's box, and be refused short" in {
    client.execute { ctx =>
      val (fill, _) = filled(ctx)
      val b = read[ZcBond](out(fill, 1))
      val kept = ZcUserTransactions.repayableFromCollateral(b)
      kept shouldBe b.repayment
      val repaid = sign(ctx, ZcUserTransactions.repay(b, input(ctx, b.box), me, kept, Some(funds(ctx, Erg / 10))), funds(ctx, Erg))
      out(repaid, 1).value shouldBe b.collateralErg - kept
      val short = ZcUserTransactions.repay(b, input(ctx, b.box), me, 0L, None)
      an[Exception] should be thrownBy sign(ctx, short.copy(outputs = short.outputs.updated(0, short.outputs.head.subValue(1L))),
        funds(ctx, 2 * Erg))
    }
  }

  "Extinguishing" should "burn all three units directly and return collateral plus fee reserve, at any height" in {
    client.execute { ctx =>
      // 1. Before maturity, circulating unit held at borrower's own address (unit itself authorizes)
      val (fill, _) = filled(ctx)
      val b = read[ZcBond](out(fill, 1))
      val unitAtBorrower = input(ctx, out(fill, 0))
      val ext = sign(ctx, ZcUserTransactions.extinguish(b, input(ctx, b.box), unitAtBorrower, me))
      ext.getSignedInputs.size shouldBe 2
      ext.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId.toString) should not contain b.asset
      ext.getOutputsToSpend.get(0).getValue.longValue shouldBe b.box.value + unitAtBorrower.value - ZcUserTransactions.TxFee

      // 2. Unit held at a separate wallet address alongside an explicit borrower auth box
      val (fill2, _) = filled(ctx)
      val b2 = read[ZcBond](out(fill2, 1))
      val unitElsewhere = seeded(ctx, UTXO(seller, MinBox, Seq(token(b2.asset, 1L))))
      val authBox = funds(ctx, MinBox)
      val ext2 = sign(ctx, ZcUserTransactions.extinguish(b2, input(ctx, b2.box), authBox, me), unitElsewhere)
      ext2.getSignedInputs.size shouldBe 3
      ext2.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId.toString) should not contain b2.asset
      ext2.getOutputsToSpend.get(0).getValue.longValue shouldBe b2.box.value + authBox.value + unitElsewhere.value - ZcUserTransactions.TxFee

      // 3. Past maturity on a SigUSD bond with collateral tokens: still extinguishable
      val h = ctx.getHeight.toLong
      val sigAsset = "d9" * 32
      val collToken = "da" * 32
      val dueSig = bond(ctx, sigAsset, 2 * Erg, Seq(token(collToken, 50L)), 500L, ZcExpression.MaturityLiquidation,
        h - 1000, h - 1, h.toInt - 1000, market = ZcMarket.SigUsd)
      val dueBond = read[ZcBond](nodeOf(dueSig))
      val dueUnit = funds(ctx, MinBox, token(sigAsset, 1L))
      val extDue = sign(ctx, ZcUserTransactions.extinguish(dueBond, dueSig, dueUnit, me))
      val swept = extDue.getOutputsToSpend.get(0)
      swept.getValue.longValue shouldBe dueBond.box.value + dueUnit.value - ZcUserTransactions.TxFee
      swept.getTokens.asScala.map(t => t.getId.toString -> t.getValue) shouldBe Seq(collToken -> 50L)
    }
  }

  it should "be refused by the contract without borrower auth, with unburned units, or with a path context var" in {
    client.execute { ctx =>
      // Missing borrower auth (non-borrower holds the circulating unit)
      val (fill, _) = filled(ctx)
      val b = read[ZcBond](out(fill, 1))
      val nonBorrowerUnit = seeded(ctx, UTXO(seller, MinBox, Seq(token(b.asset, 1L))))
      an[Exception] should be thrownBy sign(ctx, ZcUserTransactions.extinguish(b, input(ctx, b.box), nonBorrowerUnit, seller))

      // Leaving one unit unburned (outCount == 1)
      val unitAtBorrower = input(ctx, out(fill, 0))
      val badBurn = ZcSpend(Seq(input(ctx, b.box), unitAtBorrower), Seq.empty, burn = Seq(token(b.asset, 2L)), sweep = Some(me))
      an[Exception] should be thrownBy sign(ctx, badBurn)

      // Path context var set on bond input (var 3 enters recollateralize instead of extinguish)
      val withVar = ZcUserTransactions.extinguish(b, input(ctx, b.box).withCtxVar(3.toByte, ErgoValue.of(0)), unitAtBorrower, me)
      an[Exception] should be thrownBy sign(ctx, withVar)
    }
  }

  "Recollateralizing" should "grow the bond 60 blocks after it moved, and be refused before" in {
    client.execute { ctx =>
      val h = ctx.getHeight
      val old = bond(ctx, "c2" * 32, 3 * Erg, Seq.empty, 2 * Erg, ZcExpression.MaturityLiquidation, h - 100, h + 1000, h - 61)
      val b = read[ZcBond](nodeOf(old))
      val auth = funds(ctx, Erg / 10)
      val spend = ZcUserTransactions.recollateralize(b, old, auth, Erg, Seq.empty, h)
      spend.named shouldBe Seq(auth.contract)
      val signed = sign(ctx, spend, funds(ctx, 2 * Erg))
      val grown = read[ZcBond](out(signed, 0))
      grown.collateralErg shouldBe b.collateralErg + Erg
      grown.box.creationHeight shouldBe h
      val fresh = bond(ctx, "c3" * 32, 3 * Erg, Seq.empty, 2 * Erg, ZcExpression.MaturityLiquidation, h - 100, h + 1000, h - 10)
      an[Exception] should be thrownBy sign(ctx, ZcUserTransactions.recollateralize(read[ZcBond](nodeOf(fresh)), fresh,
        funds(ctx, Erg / 10), Erg, Seq.empty, h), funds(ctx, 2 * Erg))
    }
  }

  "Liquidating" should "settle a matured bond for its fee reserve, and be refused before maturity" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val due = bond(ctx, "c4" * 32, 3 * Erg, Seq.empty, 2 * Erg, ZcExpression.MaturityLiquidation, h - 1000, h - 1, h.toInt - 1000)
      val b = read[ZcBond](nodeOf(due))
      val expression = ZcContracts.expression(ZcExpression.MaturityLiquidation, None).bytes
      val signed = sign(ctx, ZcUserTransactions.liquidate(b, due, expression, None), funds(ctx, Erg))
      val settled = read[ZcSettlement](out(signed, 0))
      settled.liquidated shouldBe true
      settled.box.value shouldBe b.box.value - Reserve
      val early = bond(ctx, "c5" * 32, 3 * Erg, Seq.empty, 2 * Erg, ZcExpression.MaturityLiquidation, h - 1000, h + 100, h.toInt - 1000)
      an[Exception] should be thrownBy sign(ctx, ZcUserTransactions.liquidate(read[ZcBond](nodeOf(early)), early, expression,
        None), funds(ctx, Erg))
    }
  }

  it should "settle an oracle bond under its ratio before maturity, reading a fresh oracle" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val under = bond(ctx, "c6" * 32, Erg, Seq.empty, 2 * Erg, ZcExpression.OracleLiquidation, h - 100, h + 10000,
        h.toInt - 100, Seq(15000L, 60L))
      val b = read[ZcBond](nodeOf(under))
      val o = oracle(ctx, 465116279L, h.toInt - 5)
      ZcRules.belowOwnRatio(ZcBondTerms.of(b), 465116279L) shouldBe true
      val expression = ZcContracts.expression(ZcExpression.OracleLiquidation, Some(ZcMarket.Erg)).bytes
      read[ZcSettlement](out(sign(ctx, ZcUserTransactions.liquidate(b, under, expression, Some(o)), funds(ctx, Erg)), 0))
        .liquidated shouldBe true
    }
  }

  // ─── shares ────────────────────────────────────────────────────────────────

  "A plain share vault" should "mint, take its settlement on a pull, and pay partial then final claims" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val (fill, order) = filled(ctx)
      val asset = order.boxId
      val draft = ZcDrafts.shareRequestOf(Json.obj("vault" -> "plain"))
      val reqBox = ZcUserTransactions.shareRequestBox(asset, ZcMarket.Erg, draft.terms, auction = false,
        ZcMetadata.shares(ZcMarket.Erg, None, None), ownHash)
      val req = read[ZcShareRequest](posted(ctx, reqBox, input(ctx, out(fill, 0)), funds(ctx, Erg)))
      val minted = sign(ctx, ZcUserTransactions.mintShares(req, input(ctx, req.box), me, None))
      val shares = out(minted, 0)
      shares.assets shouldBe Seq(NodeAsset(req.shareId, ShareCount))
      val vault = read[ZcShareVault](out(minted, 1))
      vault.holding shouldBe true
      vault.outstanding shouldBe ShareCount

      val b = read[ZcBond](out(fill, 1))
      val settlement = read[ZcSettlement](out(sign(ctx, ZcUserTransactions.repay(b, input(ctx, b.box), me, 0L, None),
        funds(ctx, 2 * Erg)), 0))
      val pulled = read[ZcShareVault](out(sign(ctx, ZcUserTransactions.pull(vault, input(ctx, vault.box), settlement,
        input(ctx, settlement.box), h)), 0))
      pulled.holding shouldBe false
      pulled.box.value shouldBe vault.box.value + settlement.box.value - vault.pullFee

      // CONST_VAULT_HASH: FractionalVault must reject pulling from a live Bond (even burning 3 units) instead of BondVault
      val fakePull = ZcSpend(Seq(input(ctx, vault.box).withCtxVar(0.toByte, ErgoValue.of(0)), input(ctx, b.box)),
        Seq(UTXO(ZcContracts.script(ZcKind.FractionalVault, None).contract, vault.box.value + b.box.value - vault.pullFee,
          Seq(token(vault.shareId, ShareCount + 1L)), input(ctx, vault.box).registers)),
        burn = Seq(token(asset, 3L)))
      an[Exception] should be thrownBy sign(ctx, fakePull)

      val partial = sign(ctx, ZcUserTransactions.claimShares(ctx, pulled, input(ctx, pulled.box), ShareCount / 4, me),
        input(ctx, shares), funds(ctx, Erg))
      out(partial, 0).value shouldBe pulled.box.value / 4
      val left = read[ZcShareVault](out(partial, 1))
      left.outstanding shouldBe ShareCount - ShareCount / 4
      val rest = partial.getOutputsToSpend.asScala.zipWithIndex.collectFirst {
        case (o, i) if o.getTokens.asScala.exists(t => t.getId.toString == req.shareId && t.getValue == ShareCount - ShareCount / 4) =>
          out(partial, i)
      }.get
      val last = sign(ctx, ZcUserTransactions.claimShares(ctx, left, input(ctx, left.box), left.outstanding, me), input(ctx, rest))
      last.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId.toString) should not contain req.shareId
    }
  }

  "A Dutch share vault" should "mint against its live bond, open a floor auction on a thin liquidation, and sell it" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val asset = "c7" * 32
      val thin = "c8" * 32
      val live = bond(ctx, asset, Erg, Seq(token(thin, 50L)), 100 * Erg, ZcExpression.MaturityLiquidation, h - 1000, h - 1,
        h.toInt - 1000)
      val draft = ZcDrafts.shareRequestOf(Json.obj("vault" -> "selective", "openingBps" -> 10000, "floorBps" -> 9000))
      val reqBox = ZcUserTransactions.shareRequestBox(asset, ZcMarket.Erg, draft.terms, auction = true,
        ZcMetadata.shares(ZcMarket.Erg, draft.auction, None), ownHash)
      val req = read[ZcShareRequest](posted(ctx, reqBox, funds(ctx, Erg, token(asset, 1L))))
      req.dutch shouldBe Some(ZcMarket.Erg)
      val b = read[ZcBond](nodeOf(live))
      val minted = sign(ctx, ZcUserTransactions.mintShares(req, input(ctx, req.box), me, Some((live, b.repayment, None))))
      val vault = read[ZcShareVault](out(minted, 1))
      vault.dutch shouldBe true
      vault.auction.head shouldBe b.repayment

      val expression = ZcContracts.expression(ZcExpression.MaturityLiquidation, None).bytes
      val settlement = read[ZcSettlement](out(sign(ctx, ZcUserTransactions.liquidate(b, live, expression, None), funds(ctx, Erg)), 0))
      val pulled = read[ZcShareVault](out(sign(ctx, ZcUserTransactions.pull(vault, input(ctx, vault.box), settlement,
        input(ctx, settlement.box), h)), 0))
      pulled.state shouldBe Some(1.toByte)
      ZcRules.auctionOpen(pulled) shouldBe true
      val target = ZcRules.lotTarget(pulled, h).get
      val sold = sign(ctx, ZcUserTransactions.buyFromVault(ctx, pulled, input(ctx, pulled.box), h), funds(ctx, target + Erg))
      val after = read[ZcShareVault](out(sold, 0))
      after.state shouldBe Some(2.toByte)
      after.box.value shouldBe target
      sold.getOutputsToSpend.asScala.exists(_.getTokens.asScala.exists(_.getId.toString == thin)) shouldBe true
    }
  }

  "A partial claim" should "top a vault left under 0.001 ERG up to its minimum, and leave out a register anyone added" in {
    client.execute { ctx =>
      val share = "c9" * 32
      val proceeds = "ca" * 32
      val spam = bytes("ab" * 200)
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.FractionalVault, None).contract, 2 * MinBox,
        Seq(token(share, ShareCount + 1L), token(proceeds, 1000L)),
        Seq(bytes("cb" * 32), longs(ShareCount, ShareCount, 5000000L), ErgoValue.of(0.toByte), spam), Some(ctx.getHeight - 10)))
      val vault = read[ZcShareVault](nodeOf(vaultIn))
      val burned = ShareCount * 6 / 10
      val claimed = sign(ctx, ZcUserTransactions.claimShares(ctx, vault, vaultIn, burned, me),
        funds(ctx, Erg, token(share, burned)))
      out(claimed, 0).value shouldBe 2 * MinBox * 6 / 10
      val left = out(claimed, 1)
      left.value shouldBe MinBox
      left.additionalRegisters.values.size shouldBe 3
      read[ZcShareVault](left).outstanding shouldBe ShareCount - burned
    }
  }

  "A floor auction's sale" should "leave out a register anyone added to the Dutch vault" in {
    client.execute { ctx =>
      val h = ctx.getHeight.toLong
      val share = "cc" * 32
      val thin = "cd" * 32
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, Erg,
        Seq(token(share, ShareCount + 1L), token(thin, 50L)),
        Seq(bytes("ce" * 32), longs(ShareCount, ShareCount, 5000000L), ErgoValue.of(1.toByte),
          longs(2 * Erg, 10000L, 9000L, 720L, 2160L, 100L, h - 10L), bytes(""), bytes("ab" * 200)), Some(h.toInt - 10)))
      val vault = read[ZcShareVault](nodeOf(vaultIn))
      ZcRules.auctionOpen(vault) shouldBe true
      val target = ZcRules.lotTarget(vault, h).get
      val sold = sign(ctx, ZcUserTransactions.buyFromVault(ctx, vault, vaultIn, h), funds(ctx, target + Erg))
      out(sold, 0).additionalRegisters.values.size shouldBe 5
      read[ZcShareVault](out(sold, 0)).state shouldBe Some(2.toByte)
    }
  }

  // ─── wraps ─────────────────────────────────────────────────────────────────

  "A wrap" should "mint its receipt with the request as the only input, and unwrap by burning all three" in {
    client.execute { ctx =>
      val wrapped = "c9" * 32
      val box = ZcUserTransactions.wrapRequestBox(wrapped, 1L, ZcDrafts.DefaultWrapFee, ZcDrafts.DefaultRefreshReserve, ownHash)
      val req = read[ZcWrapRequest](posted(ctx, box, funds(ctx, Erg, token(wrapped, 1L))))
      val minted = sign(ctx, ZcUserTransactions.mintWrap(req, input(ctx, req.box), me))
      minted.getSignedInputs.size shouldBe 1
      val receipt = out(minted, 0)
      receipt.assets shouldBe Seq(NodeAsset(req.receiptId, 1L))
      val vault = read[ZcWrapVault](out(minted, 1))
      vault.wrapped shouldBe NodeAsset(wrapped, 1L)
      val unwrapped = sign(ctx, ZcUserTransactions.unwrap(vault, input(ctx, vault.box), me), input(ctx, receipt))
      val tokensOut = unwrapped.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId.toString)
      tokensOut should contain(wrapped)
      tokensOut should not contain req.receiptId
    }
  }

  it should "refuse a wrap mint whose fee cannot pay the miner without another input" in {
    client.execute { ctx =>
      val box = ZcUserTransactions.wrapRequestBox("ca" * 32, 1L, ZcPricing.MinBatchingFee, 0L, ownHash)
      val req = read[ZcWrapRequest](posted(ctx, box, funds(ctx, Erg, token("ca" * 32, 1L))))
      val spend = ZcUserTransactions.mintWrap(req, input(ctx, req.box), me)
      noException should be thrownBy ZcUserTransactions.plan(ctx, wallet, spend)
      val tooCheap = spend.copy(outputs = spend.outputs.updated(1, spend.outputs(1).addValue(ZcPricing.MinBatchingFee)))
      an[IllegalArgumentException] should be thrownBy ZcUserTransactions.plan(ctx, wallet, tooCheap)
    }
  }
}
