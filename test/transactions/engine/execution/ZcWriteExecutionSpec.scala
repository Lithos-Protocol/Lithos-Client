package transactions.engine.execution

import api.LithosApiErrors.{LithosStateChanged, LithosUnprocessable}
import api.zc.ZcDrafts
import configs.ZcConfig
import node.model.{NodeAsset, NodeBox, NodeRegisters, WalletBalances}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.{JsObject, Json}
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.Batcher
import transactions.batching.sigmafi.zc._
import transactions.batching.tracking.ScriptedChain
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.engine.wallet.EngineFunding
import work.lithos.mutations.{Token, UTXO}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

/**
 * The write layer over a tracker seeded with real boxes: a check quotes what this wallet pays and receives, a refusal
 * comes back as a quote listing it and as 422 on a send, and a send refuses a box a fresh mempool shows spent, or a
 * quote that moved past the body's limits, before anything is reserved or signed.
 */
class ZcWriteExecutionSpec extends AnyFlatSpec with Matchers with Eventually {

  import transactions.batching.sigmafi.zc.ZcFixtures.{Erg => _, _}

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  private val Erg = 1000000000L

  private final case class World(exec: ZcWriteExecution, order: NodeBox, bond: NodeBox, asset: String)

  private def nodeOf(signed: SignedTransaction, i: Int): NodeBox = {
    val b = signed.getOutputsToSpend.get(i)
    NodeBox(b.getId.toString, CandidateTx.txId(signed), b.getValue, i, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
      b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
  }

  /** A posted order of this wallet's and a live maturity-rule bond, both real boxes the tracker holds. */
  private def world(orderSpentInMempool: Boolean = false, bondSpentInMempool: Boolean = false,
                    walletHoldsUnit: Boolean = false, bondMatured: Boolean = false,
                    bondOwnedByMe: Boolean = true,
                    capability: Seq[String] => Set[String] = _ => Set.empty): World = {
    val c = new ScriptedChain
    val (ctx0, _, wallet) = FakeNodeContext(c.api)
    val ownHash = Hex.toHexString(wallet.contract.hashedPropBytes)
    val borrowerHash = if (bondOwnedByMe) ownHash else "bb" * 32
    val (order, bond, authBox, asset) = ctx0.getClient.execute { ctx: BlockchainContext =>
      val h = ctx.getHeight
      val body = Json.obj("market" -> "ERG", "pricing" -> "fixed", "principal" -> "1000000000", "repayment" -> "1100000000",
        "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
        "collateral" -> Json.obj("nanoErg" -> "3000000000"), "rule" -> Json.obj("kind" -> "maturity"))
      val draft = ZcDrafts.orderOf(body, h.toLong)
      val funding = UTXO(wallet.contract, 4 * Erg).toInput(ctx, ErgoId.create("f0" * 32), 0)
      val posted = Batcher.withNodeMinimums(ctx, wallet.sign(ZcUserTransactions.post(ctx, wallet,
        ZcUserTransactions.orderBox(draft, ownHash, h), wallet.contract).build(Seq(funding))))
      val asset = "d1" * 32
      val maturity = if (bondMatured) h - 1L else h + 1000L
      val terms = Seq(2 * Erg, Erg, 2 * Erg, maturity, h.toLong, 5000000L, 0L)
      val live = UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract, 3 * Erg, Seq(Token(ErgoId.create(asset), 2L)),
        Seq(bytes(asset), bytes(borrowerHash), longs(terms: _*), tokens(), bytes(expr(ZcExpression.MaturityLiquidation)),
          longs(50000000L, h - 100L, maturity)), Some(h - 100)).toInput(ctx, ErgoId.create("f1" * 32), 0)
      val b = live.input
      val bondBox = NodeBox(b.getId.toString, "f1" * 32, b.getValue, 0, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
        Seq(NodeAsset(asset, 2L)), NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
      val authIn = UTXO(wallet.contract, ZcBoxes.MinBox, if (walletHoldsUnit) Seq(Token(ErgoId.create(asset), 1L)) else Seq.empty)
        .toInput(ctx, ErgoId.create("f2" * 32), 0).input
      val auth = NodeBox(authIn.getId.toString, "f2" * 32, authIn.getValue, 0, authIn.getCreationHeight,
        Hex.toHexString(authIn.getErgoTree.bytes),
        authIn.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)), NodeRegisters.empty)
      (nodeOf(posted, 0), bondBox, auth, asset)
    }
    val h = ctx0.getClient.execute(_.getHeight)
    c.extend(h - 40, h)
    c.indexed = h
    c.scans(Seq(order, bond, authBox).map(b => LDNodeFixtures.indexed(b, h - 10)): _*)
    val boxes = Seq(order, bond, authBox).map(b => b.boxId -> b).toMap
    when(c.api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer(inv =>
      Success(inv.getArgument[Seq[String]](0).flatMap(boxes.get)))
    when(c.api.walletBalancesWithUnconfirmed()).thenReturn(Success(WalletBalances(h, 5 * Erg,
      if (walletHoldsUnit) Seq(NodeAsset(asset, 1L)) else Seq.empty)))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(h))
    val r = new ZcResolver(() => c.api, t)
    val exec = new ZcWriteExecution(ctx0, EngineFunding(null, 1.second, ExecutionContext.global), t, r,
      capability = capability) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot = {
        val spentInMempool = (if (orderSpentInMempool) Seq(order.boxId) else Seq.empty[String]) ++
          (if (bondSpentInMempool) Seq(bond.boxId) else Seq.empty[String])
        val txs = spentInMempool.map(id => CompleteMempool.MempoolTx("ab" * 32, nodeTx("ab" * 32, Seq(id), Seq.empty), 100))
        CompleteMempool.Snapshot(c.headerId(h), txs.map(_.id).toSet, spentInMempool.toSet, System.currentTimeMillis(),
          transactions = txs.toVector)
      }
    }
    World(exec, order, bond, asset)
  }

  "A borrow order" should "refuse an unwrapped LithosDex provision NFT as collateral, fail closed on lookup error, and allow a wrapped receipt" in {
    val rawLpNft = "11" * 32
    val wrappedReceipt = "22" * 32
    val w = world(capability = ids => ids.filter(_ == rawLpNft).toSet)
    val h = w.order.creationHeight
    def orderBody(tokenId: String) = Json.obj("market" -> "ERG", "pricing" -> "fixed", "principal" -> "1000000000",
      "repayment" -> "1100000000", "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
      "collateral" -> Json.obj("nanoErg" -> "3000000000", "tokens" -> Seq(Json.obj("tokenId" -> tokenId, "amount" -> "1"))),
      "rule" -> Json.obj("kind" -> "maturity"))

    val refusedQuote = w.exec.createOrder(orderBody(rawLpNft), send = false)
    (refusedQuote \ "refusals").as[Seq[String]].head should include("capability token")
    an[LithosUnprocessable] should be thrownBy w.exec.createOrder(orderBody(rawLpNft), send = true)

    val wrappedQuote = w.exec.createOrder(orderBody(wrappedReceipt), send = false)
    (wrappedQuote \ "refusals").as[Seq[String]] shouldBe empty

    val failingCap = world(capability = _ => throw api.LithosApiErrors.LithosUnavailable("pool index incomplete"))
    an[api.LithosApiErrors.LithosUnavailable] should be thrownBy failingCap.exec.createOrder(orderBody(rawLpNft), send = true)
  }

  "A fill check" should "quote the bond it creates and the unit this wallet receives, signing nothing" in {
    val w = world()
    val quote = w.exec.fillOrder(w.order.boxId, Json.obj(), send = false)
    (quote \ "refusals").as[Seq[String]] shouldBe empty
    (quote \ "creates").as[Seq[JsObject]].map(c => (c \ "role").as[String]) should contain("bond")
    (quote \ "receivesTokens").as[Seq[JsObject]].map(t => (t \ "tokenId").as[String]) should contain(w.order.boxId)
    (quote \ "terms" \ "assetId").as[String] shouldBe w.order.boxId
    (quote \ "terms" \ "principal").as[String] shouldBe "1000000000"
    (quote \ "fees").as[Seq[JsObject]].map(f => (f \ "name").as[String]) should contain("liquidationReserve")
  }

  "An offer check" should "quote an offer of 20 points delivering shares, refusing nothing" in {
    val w = world()
    val body = Json.obj("market" -> "ERG", "capital" -> Erg.toString, "minYieldBps" -> 500,
      "floorBps" -> 13000, "curve" -> (1 to 20).map(i => Json.obj("bps" -> (800 + i), "blocks" -> (10000 * i))),
      "delivery" -> Json.obj("kind" -> "selective", "openingBps" -> 10000, "floorBps" -> 9000))
    (w.exec.createOffer(body, send = false) \ "refusals").as[Seq[String]] shouldBe empty
  }

  it should "warn of a 0 bps least auction floor, and of a batcher fee a fifth of the capital" in {
    val w = world()
    val body = Json.obj("market" -> "ERG", "capital" -> "20000000", "minYieldBps" -> 500, "floorBps" -> 13000,
      "shareBounds" -> Json.obj("floorBps" -> 0, "maxExpiry" -> 3000))
    val warnings = (w.exec.createOffer(body, send = false) \ "warnings").as[Seq[String]]
    warnings.exists(_.contains("never auction")) shouldBe true
    warnings.exists(_.contains("a fifth of the capital")) shouldBe true
  }

  "A SigUSD lot check" should "warn that its refund fee goes to its buyer" in {
    val w = world()
    val body = Json.obj("market" -> "SigUSD", "nanoErg" -> "100000000", "opening" -> "2000", "floor" -> "1000",
      "end" -> (w.order.creationHeight + 1000))
    val warnings = (w.exec.createLot(body, send = false) \ "warnings").as[Seq[String]]
    warnings.exists(_.contains("refund fee goes to the lot's buyer")) shouldBe true
  }

  "A refused write" should "answer its refusals on the check and 422 on the send" in {
    val w = world()
    val quote = w.exec.liquidate(w.asset, Json.obj(), send = false)
    (quote \ "refusals").as[Seq[String]].head should include("matures at height")
    an[LithosUnprocessable] should be thrownBy w.exec.liquidate(w.asset, Json.obj(), send = true)
  }

  "An extinguish check" should "quote burning the unit and returning collateral plus fee reserve, including after maturity" in {
    val w = world(walletHoldsUnit = true)
    val quote = w.exec.extinguish(w.asset, Json.obj(), send = false)
    (quote \ "refusals").as[Seq[String]] shouldBe empty
    (quote \ "warnings").as[Seq[String]] shouldBe empty
    (quote \ "paysNanoErgs").as[String] shouldBe "0"
    (quote \ "receivesNanoErgs").as[String] shouldBe (w.bond.value - ZcUserTransactions.TxFee).toString
    (quote \ "paysTokens").as[Seq[JsObject]].map(t => (t \ "tokenId").as[String] -> (t \ "amount").as[String]) shouldBe
      Seq(w.asset -> "1")
    (quote \ "creates").as[Seq[JsObject]] shouldBe empty
    (quote \ "terms" \ "assetId").as[String] shouldBe w.asset
    (quote \ "terms" \ "returns" \ "nanoErg").as[String] shouldBe (w.bond.value - ZcUserTransactions.TxFee).toString

    val due = world(walletHoldsUnit = true, bondMatured = true)
    val dueQuote = due.exec.extinguish(due.asset, Json.obj(), send = false)
    (dueQuote \ "refusals").as[Seq[String]] shouldBe empty
    (dueQuote \ "warnings").as[Seq[String]].head should include("races any liquidation")
  }

  it should "refuse when this wallet is not the borrower or does not hold the circulating unit" in {
    val noUnit = world(walletHoldsUnit = false)
    (noUnit.exec.extinguish(noUnit.asset, Json.obj(), send = false) \ "refusals").as[Seq[String]].head should
      include("does not hold bond unit")
    an[LithosUnprocessable] should be thrownBy noUnit.exec.extinguish(noUnit.asset, Json.obj(), send = true)

    val otherBorrower = world(walletHoldsUnit = true, bondOwnedByMe = false)
    (otherBorrower.exec.extinguish(otherBorrower.asset, Json.obj(), send = false) \ "refusals").as[Seq[String]].head should
      include("only the borrower may extinguish")
    an[LithosUnprocessable] should be thrownBy otherBorrower.exec.extinguish(otherBorrower.asset, Json.obj(), send = true)
  }

  "A send" should "refuse a box a fresh mempool observation shows spent, with 409" in {
    val spent = world(orderSpentInMempool = true)
    val e = the[LithosStateChanged] thrownBy spent.exec.fillOrder(spent.order.boxId, Json.obj(), send = true)
    e.reason should include("already spent by unconfirmed transaction")

    val spentBond = world(bondSpentInMempool = true, walletHoldsUnit = true)
    val e2 = the[LithosStateChanged] thrownBy spentBond.exec.extinguish(spentBond.asset, Json.obj(), send = true)
    e2.reason should include("already spent by unconfirmed transaction")
  }

  it should "refuse a quote that moved past the body's limits, with 409, before reserving anything" in {
    val w = world()
    val e = the[LithosStateChanged] thrownBy w.exec.fillOrder(w.order.boxId, Json.obj("limits" -> Json.obj("maxPay" -> "1")),
      send = true)
    e.reason should include("over the 1 the request allows")
  }

  it should "enforce ERG limits on SigUSD bond liquidation and ERG recollateralization, and SigUSD limits on SigUSD claim" in {
    val c = new ScriptedChain
    val (ctx0, _, wallet) = FakeNodeContext(c.api)
    val ownHash = Hex.toHexString(wallet.contract.hashedPropBytes)
    val sigAsset = "d9" * 32
    val settledAsset = "da" * 32
    val (sigBond, settledBox, originOrder, authBox, h) = ctx0.getClient.execute { ctx: BlockchainContext =>
      val h = ctx.getHeight
      val maturity = h - 1L
      val sigId = ErgoId.create(ZcContracts.SigUsdId)
      val terms = Seq(1000L, 0L, ZcBoxes.MinBox, maturity, h.toLong, 5000000L, 0L)
      val bondIn = UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.SigUsd)).contract, 3 * Erg,
        Seq(Token(ErgoId.create(sigAsset), 2L)),
        Seq(bytes(sigAsset), bytes(ownHash), longs(terms: _*), tokens(ZcContracts.SigUsdId -> 900L),
          bytes(expr(ZcExpression.MaturityLiquidation)), longs(500000L, h - 100L, maturity)), Some(h - 100))
        .toInput(ctx, ErgoId.create("f5" * 32), 0).input
      val bondNode = NodeBox(bondIn.getId.toString, "f5" * 32, bondIn.getValue, 0, bondIn.getCreationHeight,
        Hex.toHexString(bondIn.getErgoTree.bytes),
        bondIn.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
        NodeRegisters(bondIn.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
      val ordDraft = ZcDrafts.orderOf(Json.obj("market" -> "SigUSD", "pricing" -> "fixed", "principal" -> "900",
        "repayment" -> "1000", "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
        "collateral" -> Json.obj("nanoErg" -> "3000000000"), "rule" -> Json.obj("kind" -> "maturity")), h.toLong)
      val ordIn = ZcUserTransactions.orderBox(ordDraft, ownHash, h - 100).toInput(ctx, ErgoId.create("f6" * 32), 0).input
      val ordNode = NodeBox(settledAsset, "f6" * 32, ordIn.getValue, 0, ordIn.getCreationHeight,
        Hex.toHexString(ordIn.getErgoTree.bytes),
        ordIn.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
        NodeRegisters(ordIn.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
      val settleIn = UTXO(ZcContracts.script(ZcKind.BondVault, None).contract, ZcBoxes.MinBox,
        Seq(Token(ErgoId.create(settledAsset), 2L), Token(sigId, 1000L)),
        Seq(bytes("f7" * 32), org.ergoplatform.appkit.ErgoValue.of(0.toByte)), Some(h - 10))
        .toInput(ctx, ErgoId.create("f8" * 32), 0).input
      val settleNode = NodeBox(settleIn.getId.toString, "f8" * 32, settleIn.getValue, 0, settleIn.getCreationHeight,
        Hex.toHexString(settleIn.getErgoTree.bytes),
        settleIn.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
        NodeRegisters(settleIn.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
      val authIn = UTXO(wallet.contract, ZcBoxes.MinBox).toInput(ctx, ErgoId.create("f9" * 32), 0).input
      val authNode = NodeBox(authIn.getId.toString, "f9" * 32, authIn.getValue, 0, authIn.getCreationHeight,
        Hex.toHexString(authIn.getErgoTree.bytes), Seq.empty, NodeRegisters.empty)
      (bondNode, settleNode, ordNode, authNode, h)
    }
    c.extend(h - 40, h)
    c.indexed = h
    c.scans(Seq(sigBond, settledBox, authBox).map(b => LDNodeFixtures.indexed(b, h - 10)): _*)
    val boxes = Seq(sigBond, settledBox, authBox).map(b => b.boxId -> b).toMap
    when(c.api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer(inv =>
      Success(inv.getArgument[Seq[String]](0).flatMap(boxes.get)))
    when(c.api.indexedBoxById(settledAsset)).thenReturn(
      Success(Some(LDNodeFixtures.indexed(originOrder, h - 100).copy(spentTransactionId = Some("fa" * 32)))))
    when(c.api.walletBalancesWithUnconfirmed()).thenReturn(Success(WalletBalances(h, 5 * Erg, Seq(NodeAsset(settledAsset, 1L)))))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(h))
    val r = new ZcResolver(() => c.api, t)
    val exec = new ZcWriteExecution(ctx0, EngineFunding(null, 1.second, ExecutionContext.global), t, r) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot =
        CompleteMempool.Snapshot(c.headerId(h), Set.empty, Set.empty, System.nanoTime())
    }
    // Liquidate on a SigUSD bond with feeReserve (0.0005 ERG) < TxFee (0.001 ERG) pays 500,000 nanoERG: maxPay=100,000 must refuse!
    val liqErr = the[LithosStateChanged] thrownBy exec.liquidate(sigAsset, Json.obj("limits" -> Json.obj("maxPay" -> "100000")), send = true)
    liqErr.reason should include("over the 100000")

    // Claim on a repaid SigUSD settlement receives 1000 SigUSD: minReceive=1001 must refuse in SigUSD units (not 0 ERG)!
    val claimErr = the[LithosStateChanged] thrownBy exec.claim(settledAsset, Json.obj("limits" -> Json.obj("minReceive" -> "1001")), send = true)
    claimErr.reason should include("receives 1000, under the 1001")
  }

  /**
   * An oracle-rule bond far under its ratio, a confirmed oracle box too old for it, and, when `refresh` is set, one
   * pending refresh whose new box is fresh. The engine is `engine`, so a held write's reschedule reaches it.
   */
  private def oracleWorld(refresh: Option[(Option[Int], Option[Long])], engine: akka.actor.ActorRef)
  : (ZcWriteExecution, ZcTracker, ZcResolver, String, String) = {
    val c = new ScriptedChain
    val (ctx0, _, wallet) = FakeNodeContext(c.api)
    val asset = "d3" * 32
    def nodeOf(in: work.lithos.mutations.InputUTXO, txId: String): NodeBox = {
      val b = in.input
      NodeBox(b.getId.toString, txId, b.getValue, 0, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
        b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
        NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
    }
    val (bond, stale, fresh, h) = ctx0.getClient.execute { ctx: BlockchainContext =>
      val h = ctx.getHeight
      val terms = Seq(2 * Erg, Erg, 2 * Erg, h + 1000L, h - 100L, 5000000L, 0L, 1000000L, 60L)
      val bond = UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract, 3 * Erg, Seq(Token(ErgoId.create(asset), 2L)),
        Seq(bytes(asset), bytes("bb" * 32), longs(terms: _*), tokens(), bytes(expr(ZcExpression.OracleLiquidation)),
          longs(50000000L, h - 100L, h + 1000L)), Some(h - 100)).toInput(ctx, ErgoId.create("e1" * 32), 0)
      def oracle(made: Int, txId: String) = UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, ZcBoxes.MinBox,
        Seq(Token(ErgoId.create(ZcContracts.OracleNft), 1L)),
        Seq(org.ergoplatform.appkit.ErgoValue.of(465116279L), org.ergoplatform.appkit.ErgoValue.of(1)), Some(made))
        .toInput(ctx, ErgoId.create(txId), 0)
      (nodeOf(bond, "e1" * 32), nodeOf(oracle(h - 200, "e2" * 32), "e2" * 32), nodeOf(oracle(h, "e3" * 32), "e3" * 32), h)
    }
    c.extend(h - 250, h)
    c.indexed = h
    c.scans(LDNodeFixtures.indexed(bond, h - 100), LDNodeFixtures.indexed(stale, h - 200))
    val boxes = Seq(bond, stale, fresh).map(b => b.boxId -> b).toMap
    when(c.api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer(inv =>
      Success(inv.getArgument[Seq[String]](0).flatMap(boxes.get)))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(h))
    val txs = refresh.toVector.map { case (size, cost) =>
      val body = node.model.NodeTransaction("e4" * 32, Seq(node.model.NodeInput(stale.boxId, node.model.NodeSpendingProof.empty)),
        Seq.empty, Seq(fresh), size, cost)
      CompleteMempool.MempoolTx(body.id, body, 800)
    }
    val snapshot = CompleteMempool.Snapshot(c.headerId(h), txs.map(_.id).toSet, txs.flatMap(_.body.inputs.map(_.boxId)).toSet,
      System.nanoTime(), transactions = txs)
    t.observe(CompleteMempool.Observation(1L, Some(snapshot), None))
    if (refresh.isDefined) eventually(t.view.flatMap(_.pendingOracle).map(_.box.boxId) shouldBe Some(fresh.boxId))
    val r = new ZcResolver(() => c.api, t)
    val exec = new ZcWriteExecution(ctx0, EngineFunding(engine, 1.second, ExecutionContext.global), t, r) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot = snapshot
    }
    (exec, t, r, asset, fresh.boxId)
  }

  "A write reading the oracle" should "price at a single pending refresh's box, and hold its send while miners could reach it first" in {
    implicit val system: akka.actor.ActorSystem = akka.actor.ActorSystem("zc-held-write")
    try {
      val engine = akka.testkit.TestProbe()
      val (confirmedOnly, _, _, asset, _) = oracleWorld(None, engine.ref)
      (confirmedOnly.liquidate(asset, Json.obj(), send = false) \ "refusals").as[Seq[String]].head should include("older than")

      // The node reports no cost for the refresh, so nothing can show miners reach it first
      val (exec, tracker, resolver, asset2, freshId) = oracleWorld(Some(Some(788) -> None), engine.ref)
      val quote = exec.liquidate(asset2, Json.obj(), send = false)
      (quote \ "refusals").as[Seq[String]] shouldBe empty
      (quote \ "warnings").as[Seq[String]].exists(_.contains("refreshing")) shouldBe true
      val held = the[ZcHeld] thrownBy exec.liquidate(asset2, Json.obj(), send = true)
      held.reason should (include(freshId) and include("no cost"))

      val before = System.currentTimeMillis()
      val answer = transactions.engine.ZcIntent.Write("liquidate", Some(asset2), Json.obj(), tracker, resolver).execute(exec)
      (answer \ "outcome").as[String] shouldBe "held"
      val again = engine.expectMsgType[transactions.engine.TransactionEngine.Schedule](5.seconds)
      again.notBeforeMillis should be >= (before + transactions.engine.ZcIntent.RetryMs)
      again.expiresAtMillis should be <= (System.currentTimeMillis() + transactions.engine.ZcIntent.HoldMs)
      again.intent match {
        case transactions.engine.EngineIntent.Zc(w: transactions.engine.ZcIntent.Write, _) => w.heldUntil shouldBe defined
        case other => fail(s"rescheduled $other")
      }
      // Past its hold, a held write is refused rather than rescheduled
      an[ZcRefusal] should be thrownBy transactions.engine.ZcIntent.Write("liquidate", Some(asset2), Json.obj(), tracker,
        resolver, heldUntil = Some(System.currentTimeMillis())).execute(exec)
    } finally akka.testkit.TestKit.shutdownActorSystem(system)
  }
}
