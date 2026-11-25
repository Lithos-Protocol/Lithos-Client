package transactions.batching.sigmafi.zc

import configs.ZcCollateralArbConfig
import mutations.NodeWallet
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket}
import support.FakeNodeContext
import transactions.batching.arb._
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.sigmafi.SigmaFiBoxes
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * ZC collateral arb broadcast as one transaction, `[pool, ZC box, wallet] -> [successor, ZC outputs, wallet, fee]`,
 * signed offline against the pinned ZC contracts and the ErgoDEX pool contract; the plans that may not be built
 * that way; the return and profit floors checked on the signed outputs; and the oracle refresh guard.
 */
class ZcMempoolArbSpec extends AnyFlatSpec with Matchers {

  import ArbFixtures.{Erg => _, id => arbId, _}
  import ZcFixtures.{Erg => _, _}
  import ZcPricing.ShareCount

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Fee = ZcMempoolArb.TxFee
  private val SigUsd = ZcContracts.SigUsdId
  private val TokenA = "aa" * 32
  private val TokenB = "bb" * 32
  private val UnpricedNft = "cc" * 32
  private val Settings = ZcCollateralArbConfig.Default

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 2))
  private val seller: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val sellerHash: String = Hex.toHexString(seller.hashedPropBytes)

  private var seq = 300
  private def nextId(): ErgoId = { seq += 1; ErgoId.create(f"$seq%064x") }
  private def seeded(ctx: BlockchainContext, u: UTXO): InputUTXO = u.toInput(ctx, nextId(), 0)
  private def tok(id: String, n: Long): Token = Token(ErgoId.create(id), n)
  private def nodeOf(in: InputUTXO): NodeBox =
    ZcBatchTransactions.nodeBoxOf(in.input, in.input.getTransactionId, in.input.getTransactionIndex)
  private def input(ctx: BlockchainContext, b: NodeBox): InputUTXO = SigmaFiBoxes.input(b, ctx).get
  private def poolEntry(box: NodeBox): ArbPoolEntry = ArbPoolEntry(ErgoDexArbPool(ErgoDexPool.parse(box).get), box)
  private def bookOf(h: Int, boxes: NodeBox*): ZcBook =
    ZcBook.seeded(h, "00" * 32, boxes.flatMap(b => ZcBoxes.read(b).map(ZcEntry(_, h - 5))), Seq.empty, cut = false)
  private def lot(ctx: BlockchainContext, h: Int, price: Long, tokens: Token*): InputUTXO =
    seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L, tokens,
      Seq(bytes(sellerHash), longs(price, price, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)), Some(h - 10)))
  private def outputs(tx: SignedTransaction) = tx.getOutputsToSpend.asScala.toVector
  private def tree(o: org.ergoplatform.appkit.InputBox): String = InputUTXO(o).contract.ergoTreeHex

  /** Plans with the one pool given, then signs with a 5 ERG wallet box funding it. */
  private def signed(ctx: BlockchainContext, h: Int, pool: ArbPoolEntry, zc: InputUTXO,
                     settings: ZcCollateralArbConfig = Settings): (ZcMempoolPlan, SignedTransaction, InputUTXO) = {
    val planned = ZcMempoolArb.best(bookOf(h - 1, nodeOf(zc)), Map(pool.nft -> pool.pool), Map(pool.nft -> pool.box),
      _ => Some(seller), h, settings, 100000L, Set.empty).getOrElse(fail("no one-transaction plan"))
    val funding = seeded(ctx, UTXO(wallet.contract, 5 * Erg))
    (planned, ZcMempoolArb.sign(ctx, wallet, planned, input(ctx, pool.box), input(ctx, nodeOf(zc)), Seq(funding),
      settings), funding)
  }

  "ZcMempoolArb" should "buy an ERG lot and sell its token through one pool in one transaction, the NFT to the wallet" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val lotIn = lot(ctx, h, 2 * Erg, tok(TokenA, 5000L), tok(UnpricedNft, 1L))
      val (planned, tx, funding) = signed(ctx, h, pA, lotIn)
      val outs = outputs(tx)
      outs.size shouldBe 4
      tree(outs(0)) shouldBe pA.box.ergoTree
      tree(outs(1)) shouldBe seller.ergoTreeHex
      outs(1).getValue shouldBe lotIn.value + 2 * Erg
      tree(outs(2)) shouldBe wallet.contract.ergoTreeHex
      outs(2).getTokens.asScala.map(_.getId.toString) shouldBe Seq(UnpricedNft)
      tree(outs(3)) shouldBe Contract.FEE.ergoTreeHex
      outs(3).getValue shouldBe Fee
      // The plan counts the NFT's box minimum as spent; the wallet output keeps it
      outs(2).getValue - funding.value shouldBe planned.net + MinBox
      planned.paid shouldBe 2 * Erg + MinBox + Fee
    }
  }

  it should "buy a Dutch vault's ERG floor auction in one transaction" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 3000000L,
        Seq(tok("55" * 32, ShareCount + 1L), tok(TokenA, 5000L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(1),
          longs(2 * Erg, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 100L), bytes("")), Some(h - 100)))
      val (planned, tx, _) = signed(ctx, h, pA, vaultIn)
      val sold = ZcBoxes.read(ZcBatchTransactions.nodeBoxOf(outputs(tx)(1), "00" * 32, 1)).get.asInstanceOf[ZcShareVault]
      sold.state shouldBe Some(2.toByte)
      sold.box.value shouldBe 1800000000L
      planned.plan.target shouldBe a[ZcVaultTarget]
    }
  }

  it should "buy a Dutch vault's excess auction in one transaction, leaving its thick token" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 15000L))
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 2 * Erg,
        Seq(tok("55" * 32, ShareCount + 1L), tok(TokenA, 50L), tok(TokenB, 500L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(4),
          longs(2 * Erg, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 50L), bytes("")), Some(h - 60)))
      val (_, tx, _) = signed(ctx, h, pA, vaultIn)
      val kept = outputs(tx)(1).getTokens.asScala
      kept.map(_.getId.toString) shouldBe Seq("55" * 32, TokenB)
    }
  }

  it should "buy part of a sale order in one transaction, its continuation at output 2" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val askIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 20000L)), Seq(bytes(sellerHash), longs(500000000L, 1000L, 1000L, h.toLong + 500L)), Some(h - 5)))
      val (planned, tx, _) = signed(ctx, h, pA, askIn, Settings.copy(maxErgIn = 4 * Erg + Fee))
      planned.plan.target.asInstanceOf[ZcSaleOrderTarget].full shouldBe false
      planned.paid should be <= (4 * Erg + Fee)
      val rest = ZcBoxes.read(ZcBatchTransactions.nodeBoxOf(outputs(tx)(2), "00" * 32, 2)).get.asInstanceOf[ZcAsk]
      rest.predecessor shouldBe Some(nodeOf(askIn).boxId)
      tree(outputs(tx)(3)) shouldBe wallet.contract.ergoTreeHex
    }
  }

  it should "send at the return floor and not below it" in {
    ZcMempoolArb.returns(10L, 100L, 10) shouldBe true
    ZcMempoolArb.returns(9L, 100L, 10) shouldBe false
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      // 5000 TokenA fetch about 4.96 ERG: priced at 4.6 ERG the lot returns about 7.6%
      val lotIn = lot(ctx, h, 4600000000L, tok(TokenA, 5000L))
      def at(percent: Int) = ZcMempoolArb.best(bookOf(h - 1, nodeOf(lotIn)), Map(pA.nft -> pA.pool),
        Map(pA.nft -> pA.box), _ => Some(seller), h, Settings.copy(minReturnPercent = percent), 100000L, Set.empty)
      at(10) shouldBe None
      val sent = at(7).get
      ZcMempoolArb.returns(sent.net, sent.paid, 7) shouldBe true
      ZcMempoolArb.returns(sent.net, sent.paid, 8) shouldBe false
    }
  }

  it should "never plan a sale through two pools, a SigUSD market, or a pool the mempool spends" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pUsd = poolEntry(nativePool(ctx, arbId(1), SigUsd, 1000 * Erg, 200000L))
      val pAUsd = poolEntry(tokenPool(ctx, arbId(2), TokenA, SigUsd, 1000000L, 100000L))
      val parsed = Map(pUsd.nft -> pUsd, pAUsd.nft -> pAUsd)
      val pools = parsed.map { case (k, e) => k -> e.pool }
      val boxes = parsed.map { case (k, e) => k -> e.box }
      // TokenA only reaches ERG through SigUSD: the block path sells it in two swaps, the broadcast never
      val twoHop = lot(ctx, h, Erg, tok(TokenA, 10000L))
      val inBlock = ZcCollateralArb.evaluateLot(ZcBoxes.read(nodeOf(twoHop)).get.asInstanceOf[ZcLot], pools, _ => Some(seller),
        h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      inBlock.allSwaps.size shouldBe 2
      ZcMempoolArb.fits(inBlock) shouldBe false
      // Two tokens, each sold for ERG through its own pool: two swaps, so never one transaction
      val pA = poolEntry(nativePool(ctx, arbId(3), TokenA, 1000 * Erg, 1000000L))
      val pB = poolEntry(nativePool(ctx, arbId(4), TokenB, 1000 * Erg, 1000000L))
      val basket = ZcBoxes.read(nodeOf(lot(ctx, h, Erg, tok(TokenA, 5000L), tok(TokenB, 5000L)))).get.asInstanceOf[ZcLot]
      val both = ZcCollateralArb.evaluateLot(basket, Map(pA.nft -> pA.pool, pB.nft -> pB.pool), _ => Some(seller), h.toLong,
        8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      both.allSwaps.map(_.assetOut.isErg) shouldBe Seq(true, true)
      ZcMempoolArb.fits(both) shouldBe false
      ZcMempoolArb.best(bookOf(h - 1, nodeOf(twoHop)), pools, boxes, _ => Some(seller), h, Settings, 100000L,
        Set.empty) shouldBe None
      // A SigUSD lot needs SigUSD bought first
      val usdLot = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.SigUsd)).contract, 5 * Erg,
        Seq.empty, Seq(bytes(sellerHash), longs(10L, 10L, h - 10L, h + 100L, h + 500L, 2000000L)), Some(h - 10)))
      ZcMempoolArb.best(bookOf(h - 1, nodeOf(usdLot)), pools, boxes, _ => Some(seller), h, Settings, 100000L,
        Set.empty) shouldBe None
      ZcMempoolArb.freePools(parsed, Set(pUsd.box.boxId)).keySet shouldBe Set(pAUsd.nft)
    }
  }

  it should "refuse a signed transaction short of the floors, and a pool that moved" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val lotIn = lot(ctx, h, 2 * Erg, tok(TokenA, 5000L))
      val (planned, tx, funding) = signed(ctx, h, pA, lotIn)
      val net = outputs(tx)(2).getValue - funding.value
      ZcMempoolArb.checked(tx, planned, lotIn.value, Seq(funding), wallet.contract, Settings) shouldBe
        Right(net -> (2 * Erg + Fee))
      ZcMempoolArb.checked(tx, planned, lotIn.value, Seq(funding), wallet.contract,
        Settings.copy(minProfitNanoErg = net + 1)).isLeft shouldBe true
      ZcMempoolArb.checked(tx, planned, lotIn.value, Seq(funding), wallet.contract,
        Settings.copy(minReturnPercent = 1000)).isLeft shouldBe true
      ZcMempoolArb.checked(tx, planned, lotIn.value, Seq(funding), seller, Settings).isLeft shouldBe true
      val moved = nativePool(ctx, arbId(1), TokenA, 1001 * Erg, 999000L)
      an[IllegalStateException] should be thrownBy ZcMempoolArb.sign(ctx, wallet, planned, input(ctx, moved),
        input(ctx, nodeOf(lotIn)), Seq(seeded(ctx, UTXO(wallet.contract, 5 * Erg))), Settings)
    }
  }

  "ZcOwnArbs" should "hold a sent arb's boxes until an observation after it no longer holds its transaction" in {
    var now = 0L
    val own = new ZcOwnArbs(() => now)
    own.note(Seq("a", "b"), "tx1")
    own.boxes shouldBe Set("a", "b")
    own.prune(Set("tx1"), now + 60000000000L)
    own.boxes shouldBe Set("a", "b")
    // An observation that finished within a walk of the send may have started before it
    own.prune(Set.empty, now + 1000000000L)
    own.boxes shouldBe Set("a", "b")
    own.prune(Set.empty, now + 60000000000L)
    own.boxes shouldBe empty
    own.note(Seq("c"), "tx2")
    now += ZcOwnArbs.TtlNanos
    own.boxes shouldBe empty
  }

  "ZcRefreshGuard" should "let a write chain off a refresh only when both its fee rates are below the refresh's" in {
    val now = 100L * 1000000000L
    val refresh = ZcRefresh(node.model.NodeTransaction("ab" * 32, Seq.empty, Seq.empty, Seq.empty), None, 1100000L,
      Some(788), Some(30000L), now - 20L * 1000000000L)
    ZcRefreshGuard.refusal(Fee, 800L, 40000L, refresh, now) shouldBe None
    ZcRefreshGuard.refusal(Fee, 600L, 40000L, refresh, now).get should include("per byte")
    ZcRefreshGuard.refusal(Fee, 800L, 20000L, refresh, now).get should include("per cost")
    ZcRefreshGuard.refusal(Fee, 800L, 40000L, refresh.copy(firstSeen = now - 5L * 1000000000L), now).get should
      include("10 s")
    ZcRefreshGuard.refusal(Fee, 800L, 40000L, refresh.copy(cost = None), now).get should include("no cost")
    ZcRefreshGuard.refusal(Fee, 800L, 40000L, refresh.copy(size = None), now).get should include("no size")
    ZcRefreshGuard.refusal(Fee - 1L, 800L, 40000L, refresh, now).get should include("minimum")
    // Equal weights tie, and a tie may put ours first: refused
    ZcRefreshGuard.refusal(1100000L, 788L, 40000L, refresh, now).get should include("per byte")
  }
}
