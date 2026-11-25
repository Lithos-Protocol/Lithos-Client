package transactions.batching.sigmafi.zc

import api.zc.ZcDrafts
import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import sigmafi.zc.{ZcExpression, ZcKind}
import support.FakeNodeContext
import transactions.batching.sigmafi.{SFPlan, SigmaFiBoxes}
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * The boxes this wallet posts and the cancel that takes them back. Each posted box is signed into a transaction and
 * read back through the tracker's reader, which must find the terms the body asked for at the CLI's register layout;
 * each cancel is signed against the pinned ZC contract it spends, so a signature is the contract accepting it.
 */
class ZcUserTransactionsSpec extends AnyFlatSpec with Matchers {

  import ZcFixtures.{Erg => _, _}

  private val Erg = 1000000000L
  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 2))
  private val ownHash = Hex.toHexString(wallet.contract.hashedPropBytes)
  private var fundingIndex = 0

  private def funds(ctx: BlockchainContext, erg: Long, tokens: Token*): InputUTXO = {
    fundingIndex += 1
    UTXO(wallet.contract, erg, tokens).toInput(ctx, ErgoId.create("f0" * 32), fundingIndex.toShort)
  }

  private def sign(ctx: BlockchainContext, plan: SFPlan, funding: InputUTXO*): SignedTransaction =
    transactions.batching.Batcher.withNodeMinimums(ctx, wallet.sign(plan.build(funding)))

  private def out(signed: SignedTransaction, i: Int): NodeBox = {
    val b = signed.getOutputsToSpend.get(i)
    NodeBox(b.getId.toString, CandidateTx.txId(signed), b.getValue, i, b.getCreationHeight,
      Hex.toHexString(b.getErgoTree.bytes), b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
  }

  private def orderBody(h: Int) = Json.obj("market" -> "ERG", "pricing" -> "yield", "principal" -> "1000000000",
    "repayment" -> "1100000000", "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
    "yield" -> Json.obj("startBps" -> 800, "endBps" -> 1200),
    "collateral" -> Json.obj("nanoErg" -> "3000000000"), "rule" -> Json.obj("kind" -> "oracle", "ratioBps" -> 15000))

  "A drafted order" should "refuse more collateral tokens than a Dutch vault can take, a token listed twice, or none of one" in {
    def withTokens(ts: Seq[(String, Long)]) = orderBody(1900000).deepMerge(Json.obj("collateral" -> Json.obj(
      "tokens" -> ts.map { case (t, n) => Json.obj("tokenId" -> t, "amount" -> n) })))
    val many = (1 to ZcSizing.MaxCollateralTokens + 1).map(i => f"$i%064x" -> 10L)
    ZcDrafts.orderOf(withTokens(many.take(ZcSizing.MaxCollateralTokens)), 1900000L).box.assets should
      have size ZcSizing.MaxCollateralTokens.toLong
    the[api.LithosApiErrors.LithosBadRequest] thrownBy ZcDrafts.orderOf(withTokens(many), 1900000L) should
      have message s"at most ${ZcSizing.MaxCollateralTokens} collateral tokens: a liquidated bond must still fit a share vault's box"
    the[api.LithosApiErrors.LithosBadRequest] thrownBy ZcDrafts.orderOf(withTokens(Seq(many.head, many.head)), 1900000L) should
      have message "list each token once"
    the[api.LithosApiErrors.LithosBadRequest] thrownBy ZcDrafts.orderOf(withTokens(Seq(many.head._1 -> 0L)), 1900000L) should
      have message "each token amount must be at least 1"
  }

  "A drafted offer" should "check its share delivery as a share request is checked, and cap an ERG batcher fee" in {
    val base = Json.obj("market" -> "ERG", "capital" -> "1000000000", "minYieldBps" -> 500, "floorBps" -> 13000)
    def refusal(body: play.api.libs.json.JsObject): String =
      (the[api.LithosApiErrors.LithosBadRequest] thrownBy ZcDrafts.offerOf(body, 1900000L)).getMessage
    refusal(base ++ Json.obj("delivery" -> Json.obj("kind" -> "selective", "openingBps" -> 9000, "floorBps" -> 10000))) shouldBe
      "the auction needs 0 <= floorBps <= openingBps <= 1000000"
    refusal(base ++ Json.obj("delivery" -> Json.obj("kind" -> "dutch", "openingBps" -> 10000, "floorBps" -> 9000,
      "length" -> 720, "expiry" -> 700))) shouldBe "the auction expiry must be from its length to 1000000 blocks"
    refusal(base ++ Json.obj("erg" -> Json.obj("batcherFeeNanoErg" -> "999000000"))) should include("batcher fee must leave")
    ZcDrafts.offerOf(base ++ Json.obj("erg" -> Json.obj("batcherFeeNanoErg" -> "998999999")), 1900000L).capital shouldBe 1000000000L
  }

  "A drafted ask" should "refuse a least fill outside its units, a fixed price of 0, and a malformed sale guard" in {
    import ZcMarketFixtures.{matcher, oracleBond, unitOrigin}
    val m = matcher(Seq(oracleBond(id(31), id(30))), Map(id(30) -> unitOrigin(id(30))))
    val base = Json.obj("tokenId" -> id(30), "units" -> "1", "pricing" -> Json.obj("kind" -> "fixed", "price" -> "1000000"))
    def refusal(body: play.api.libs.json.JsObject): String =
      (the[api.LithosApiErrors.LithosBadRequest] thrownBy ZcDrafts.askOf(body, m)).getMessage
    ZcDrafts.askOf(base, m).terms.head shouldBe 1000000L
    refusal(base ++ Json.obj("minFill" -> "2")) shouldBe "minFill must be 1 to the units listed"
    refusal(base ++ Json.obj("pricing" -> Json.obj("kind" -> "fixed", "price" -> "0"))) shouldBe "a fixed price must be above 0"
    refusal(base ++ Json.obj("saleGuard" -> Json.obj("ratioBps" -> 15000, "oracleAge" -> 0))) should include("sale guard")
  }

  "An offer's first fill" should "grow its box by the 32-byte id R6 then names, which its size minimum must cover" in {
    client.execute { ctx =>
      val body = Json.obj("market" -> "ERG", "capital" -> "1000000000", "minYieldBps" -> 500, "floorBps" -> 13000,
        "curve" -> (1 to 20).map(i => Json.obj("bps" -> (800 + i), "blocks" -> (10000 * i))),
        "delivery" -> Json.obj("kind" -> "selective", "openingBps" -> 10000, "floorBps" -> 9000))
      val draft = ZcDrafts.offerOf(body, 1900000L)
      val box = ZcUserTransactions.offerBox(draft, ownHash).setValue(1010000L)
      val after = ZcUserTransactions.offerAfterFirstFill(box, draft.policyHash)
      ZcSizing.bytes(ctx, after) shouldBe ZcSizing.bytes(ctx, box) + 32
      ZcSizing.refusal(ctx, box) shouldBe None
      ZcSizing.refusal(ctx, after).get should include("under the")
    }
  }

  "Script disclosure" should "count only outputs, since a pending transaction shows no input scripts" in {
    client.execute { ctx =>
      val named = wallet.contract
      val in = funds(ctx, Erg)
      val other = UTXO(Contract.SIGMA_TRUE, Erg)
      ZcUserTransactions.disclosedAll(Seq(in), Seq(other), Seq(named)).head.ctxVars.map(_.getId) shouldBe
        Seq(ZcUserTransactions.DisclosureVar)
      ZcUserTransactions.disclosedAll(Seq(in), Seq(UTXO(named, Erg)), Seq(named)).head.ctxVars shouldBe empty
    }
  }

  "A Dutch request's auction" should "be what zcs1 lets a request carry" in {
    val terms = Seq(100000000L, 5000000L, 5000000L, 10000L, 9000L, 720L, 2160L, 100L)
    ZcRules.auctionTermsValid(terms) shouldBe true
    ZcRules.auctionTermsValid(terms.updated(4, 10001L)) shouldBe false
    ZcRules.auctionTermsValid(terms.updated(5, 0L)) shouldBe false
    ZcRules.auctionTermsValid(terms.updated(6, 719L)) shouldBe false
    ZcRules.auctionTermsValid(terms.take(7)) shouldBe false
  }

  "A posted order" should "read back with the drafted terms, priced and guarded, naming this wallet" in {
    client.execute { ctx =>
      val draft = ZcDrafts.orderOf(orderBody(ctx.getHeight), ctx.getHeight.toLong)
      val signed = sign(ctx, ZcUserTransactions.post(ctx, wallet, ZcUserTransactions.orderBox(draft, ownHash, ctx.getHeight),
        wallet.contract), funds(ctx, 4 * Erg))
      val read = ZcBoxes.read(out(signed, 0)).get.asInstanceOf[ZcOrder]
      read.script.kind shouldBe ZcKind.PricedOrder
      read.terms shouldBe draft.terms
      read.borrowerHash shouldBe ownHash
      read.pricingTerms shouldBe draft.pricingTerms
      read.policies shouldBe 2
      read.guardHash shouldBe Some(expr(ZcExpression.FillGuard))
      ZcRules.orderRefusal(read, ctx.getHeight.toLong) shouldBe None
    }
  }

  "A posted offer" should "carry its curve and, delivering Selective shares, their zcs1 metadata without the bond" in {
    client.execute { ctx =>
      val body = Json.obj("market" -> "ERG", "capital" -> "5000000000", "minYieldBps" -> 700, "floorBps" -> 15000,
        "curve" -> Json.arr(Json.obj("blocks" -> 21600, "bps" -> 820)),
        "delivery" -> Json.obj("kind" -> "selective", "openingBps" -> 10000, "floorBps" -> 9000))
      val draft = ZcDrafts.offerOf(body, ctx.getHeight.toLong)
      val signed = sign(ctx, ZcUserTransactions.post(ctx, wallet, ZcUserTransactions.offerBox(draft, ownHash), wallet.contract),
        funds(ctx, 6 * Erg))
      val read = ZcBoxes.read(out(signed, 0)).get.asInstanceOf[ZcOffer]
      read.terms shouldBe draft.terms
      read.curve shouldBe Seq(820L -> 21600L)
      read.fractional shouldBe draft.fractional
      read.metadata.map(new String(_, "UTF-8")) shouldBe Seq("ZCS-Bond-Shares",
        """{"zcs":1,"type":"selective shares","shares":100,"market":"ERG","via":"lend offer","auction":{"from":100,"to":90,"blocks":720,"expiry":2160,"threshold":100},"note":"Verify on chain."}""",
        "6")
    }
  }

  "Posting from another of the wallet's scripts" should "disclose the named script in variable 64 of INPUTS(0)" in {
    client.execute { ctx =>
      val other = work.lithos.mutations.Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
      val funding = UTXO(other, Erg).toInput(ctx, ErgoId.create("f1" * 32), 0)
      val box = UTXO(other, Erg / 2)
      val disclosed = ZcUserTransactions.disclosed(Seq(funding), Seq(box), wallet.contract)
      disclosed.head.ctxVars.map(_.getId) shouldBe Seq(ZcUserTransactions.DisclosureVar)
      ZcBoxes.disclosedTrees(disclosed.head.ctxVars.head.getValue.toHex) shouldBe Seq(wallet.contract.ergoTreeHex)
      ZcUserTransactions.disclosed(Seq(funds(ctx, Erg)), Seq(box), wallet.contract).head.ctxVars.map(_.getId) shouldBe
        Seq(ZcUserTransactions.DisclosureVar)
    }
  }

  "A cancel" should "be accepted by the order's and the offer's own contracts, with a box of the creator's alongside" in {
    client.execute { ctx =>
      val order = ZcDrafts.orderOf(orderBody(ctx.getHeight), ctx.getHeight.toLong)
      val posted = out(sign(ctx, ZcUserTransactions.post(ctx, wallet, ZcUserTransactions.orderBox(order, ownHash,
        ctx.getHeight), wallet.contract), funds(ctx, 4 * Erg)), 0)
      val cancelled = wallet.sign(ZcUserTransactions.cancel(ctx, wallet, SigmaFiBoxes.input(posted, ctx).get,
        funds(ctx, Erg / 10), wallet.contract))
      cancelled.getOutputsToSpend.asScala.map(_.getValue.longValue).head shouldBe posted.value + Erg / 10 - ZcUserTransactions.TxFee
      val offer = ZcDrafts.offerOf(Json.obj("market" -> "ERG", "capital" -> "2000000000", "minYieldBps" -> 700,
        "floorBps" -> 15000), ctx.getHeight.toLong)
      val postedOffer = out(sign(ctx, ZcUserTransactions.post(ctx, wallet, ZcUserTransactions.offerBox(offer, ownHash),
        wallet.contract), funds(ctx, 3 * Erg)), 0)
      noException should be thrownBy wallet.sign(ZcUserTransactions.cancel(ctx, wallet,
        SigmaFiBoxes.input(postedOffer, ctx).get, funds(ctx, Erg / 10), wallet.contract))
    }
  }

  it should "be refused by the contract without a box of the creator's" in {
    client.execute { ctx =>
      val order = ZcDrafts.orderOf(orderBody(ctx.getHeight), ctx.getHeight.toLong)
      val posted = out(sign(ctx, ZcUserTransactions.post(ctx, wallet, ZcUserTransactions.orderBox(order, "ab" * 32,
        ctx.getHeight), wallet.contract), funds(ctx, 4 * Erg)), 0)
      an[Exception] should be thrownBy wallet.sign(ZcUserTransactions.cancel(ctx, wallet,
        SigmaFiBoxes.input(posted, ctx).get, funds(ctx, Erg / 10), wallet.contract))
    }
  }
}
