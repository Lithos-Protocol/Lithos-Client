package transactions.batching.sigmafi.zc

import configs.ZcConfig
import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket}
import support.FakeNodeContext
import transactions.batching.arb._
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.batching.tracking.ScriptedChain
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.concurrent.duration._

/**
 * Collateral valuation and atomic arbitrage across SigmaFi ZC contracts (`LotAuction`, `DutchAuctionVault` floor
 * and excess, and `SaleOrder`), signed offline against the real pinned ZC and AMM pool contracts. Verifies
 * pluggable pricing (AMM tokens now, 0 value for unpriced tokens, clean extension via `ZcAssetPricer.chain`),
 * exact stake preservation, unpriced-token wallet sweeps, profit confirmation and refusal of unprofitable deals.
 */
class ZcCollateralArbSpec extends AnyFlatSpec with Matchers {

  import ArbFixtures.{Erg => _, id => arbId, _}
  import ZcFixtures.{Erg => _, _}
  import ZcPricing.ShareCount

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val SigUsd = ZcContracts.SigUsdId
  private val TokenA = "aa" * 32
  private val TokenB = "bb" * 32
  private val UnpricedNft = "cc" * 32

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 2))
  private val seller: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val sellerHash: String = Hex.toHexString(seller.hashedPropBytes)
  private val profitTo: Contract = wallet.contract

  private var seq = 100
  private def nextId(): ErgoId = { seq += 1; ErgoId.create(f"$seq%064x") }
  private def seeded(ctx: BlockchainContext, u: UTXO): InputUTXO = u.toInput(ctx, nextId(), 0)
  private def tok(id: String, n: Long): Token = Token(ErgoId.create(id), n)
  private def nodeOf(in: InputUTXO): NodeBox =
    ZcBatchTransactions.nodeBoxOf(in.input, in.input.getTransactionId, in.input.getTransactionIndex)
  private def read[A](b: NodeBox): A =
    ZcBoxes.read(b).getOrElse(fail(s"box ${b.boxId} does not read as ZC")).asInstanceOf[A]

  private def poolEntry(box: NodeBox): ArbPoolEntry =
    ArbPoolEntry(ErgoDexArbPool(ErgoDexPool.parse(box).get), box)

  private def bookOf(h: Int, boxes: NodeBox*): ZcBook =
    ZcBook.seeded(h, "00" * 32, boxes.flatMap(b => ZcBoxes.read(b).map(ZcEntry(_, h - 5))), Seq.empty, cut = false)

  // ─── pricing ───────────────────────────────────────────────────────────────

  "ZcCollateralPricing" should "price 1-hop and 2-hop AMM tokens, account for shared-pool slippage, and treat unpriced tokens as 0" in {
    client.execute { ctx =>
      val pSigUsd = poolEntry(nativePool(ctx, arbId(1), SigUsd, 1000 * Erg, 200000L)) // 100 SigUSD ($1) ~= 0.5 ERG
      val pAToSigUsd = poolEntry(tokenPool(ctx, arbId(2), TokenA, SigUsd, 1000000L, 100000L)) // 1000 TokenA ~= 100 SigUSD
      val pools = Map(pSigUsd.nft -> pSigUsd.pool, pAToSigUsd.nft -> pAToSigUsd.pool)

      // Basket holds TokenA (2-hop via SigUSD), SigUSD (1-hop, sharing pSigUsd!), and an unpriced NFT (0 value)
      val valn = ZcCollateralPricing.evaluateBasket(0L,
        Seq(NodeAsset(TokenA, 10000L), NodeAsset(SigUsd, 2000L), NodeAsset(UnpricedNft, 1L)),
        pools, ZcAssetPricer.Default, maxTotalLegs = 6, minLegProceeds = 100000L)

      valn.liquidations.size shouldBe 2
      valn.unpriced shouldBe Vector(NodeAsset(UnpricedNft, 1L))
      valn.sweepErgCost shouldBe MinBox
      // Verify cumulative slippage: totalErgOut must strictly equal sequential swaps, strictly LESS than independent quotes!
      val independentSigUsd = pSigUsd.pool.quote(2000L, xIn = false)
      val hop1A = pAToSigUsd.pool.quote(10000L, xIn = true)
      val independentA = pSigUsd.pool.quote(hop1A, xIn = false)
      valn.totalErgOut should be < (independentSigUsd + independentA)
      valn.totalErgOut should be > 0L
    }
  }

  it should "allow future asset pricers (e.g. bonds/shares/wrapped LD-NFTs) to plug in via ZcAssetPricer.chain" in {
    client.execute { ctx =>
      val customPricer = new ZcAssetPricer {
        override def price(tokenId: String, amount: Long, pools: Map[String, ArbPool], maxLegs: Int,
                           minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])] =
          if (tokenId == UnpricedNft) Some(new ZcAssetLiquidation {
            def tokenId = UnpricedNft; def amount = 1L; def ergOut = 5 * Erg; def legs = 1
            def swaps = Vector.empty[transactions.batching.arb.ArbSwap]
          } -> pools)
          else None
      }
      val composed = ZcAssetPricer.chain(Seq(customPricer, ZcAssetPricer.Default))
      val valn = ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(UnpricedNft, 1L)), Map.empty, composed)
      valn.totalErgOut shouldBe 5 * Erg
      valn.unpriced shouldBe empty
      valn.sweepErgCost shouldBe 0L
    }
  }

  // ─── signed atomic arbitrages ──────────────────────────────────────────────

  "ZcCollateralArb" should "atomically arbitrage an ERG LotAuction with an unpriced spam token, returning stake whole and sweeping the NFT" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L)) // 1000 TokenA ~= 1 ERG
      val pools = Map(pA.nft -> pA.pool)
      val poolBoxes = Map(pA.nft -> SigmaFiBoxes.input(pA.box, ctx).get)
      val stake = SigmaFiBoxes.input(arbStakeBox(ctx, wallet, 10 * Erg), ctx).get

      // Lot sells 5000 TokenA (~5 ERG) + 1 UnpricedNft for 2 ERG
      val lotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L), tok(UnpricedNft, 1L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10)))
      val lot = read[ZcLot](nodeOf(lotIn))

      val plan = ZcCollateralArb.evaluateLot(lot, pools, _ => Some(seller), h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      plan.valuation.unpriced shouldBe Vector(NodeAsset(UnpricedNft, 1L))
      plan.valuation.sweepErgCost shouldBe MinBox
      plan.netProfit should be > (2 * Erg)

      val (legs, moved, nextCarry, profitOut) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, profitTo, MinBox).get
      legs.size shouldBe 2 // purchase + 1 AMM swap
      moved.size shouldBe 1
      nextCarry.value shouldBe stake.value
      nextCarry.tokens shouldBe empty
      nextCarry.contract.ergoTreeHex shouldBe stake.contract.ergoTreeHex
      profitOut.value shouldBe plan.netProfit
      // Verify the unpriced NFT was swept to wallet.contract in leg 0
      val sweep = legs.head.getOutputsToSpend.get(1)
      sweep.getValue shouldBe MinBox
      sweep.getTokens.get(0).getId.toString shouldBe UnpricedNft
    }
  }

  it should "atomically arbitrage a SigUSD DutchAuctionVault (state 1 floor) holding ERG collateral in 2 transactions" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pUsd = poolEntry(nativePool(ctx, arbId(1), SigUsd, 1000 * Erg, 200000L)) // 200 SigUSD ($2) ~= 1 ERG
      val pools = Map(pUsd.nft -> pUsd.pool)
      val poolBoxes = Map(pUsd.nft -> SigmaFiBoxes.input(pUsd.box, ctx).get)
      val stake = SigmaFiBoxes.input(arbStakeBox(ctx, wallet, 10 * Erg), ctx).get

      // SigUSD Dutch vault in state 1 (floor auction): repayment 200 SigUSD, target at floor = 180 SigUSD (~0.9 ERG),
      // holds 3 ERG collateral (so buyer pays ~0.9 ERG -> 180 SigUSD, receives 3 ERG - 0.001 ERG = ~2.999 ERG -> ~2 ERG profit!)
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 3 * Erg,
        Seq(tok("55" * 32, ShareCount + 1L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(1),
          longs(200L, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 100L), bytes(SigUsd)), Some(h - 100)))
      val vault = read[ZcShareVault](nodeOf(vaultIn))

      val plan = ZcCollateralArb.evaluateVault(vault, pools, h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      plan.acquireSwap shouldBe defined
      plan.valuation.liquidations shouldBe empty
      plan.legs shouldBe 2 // ERG->SigUSD swap + vault buy (emits profit directly!)
      plan.netProfit should be > (1500000000L)

      val (legs, moved, nextCarry, profitOut) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, profitTo, MinBox).get
      legs.size shouldBe 2
      moved.size shouldBe 1
      nextCarry.value shouldBe stake.value
      nextCarry.tokens shouldBe empty
      profitOut.value shouldBe plan.netProfit
      // Vault transitions to state 2 holding 180 SigUSD
      val nextVault = read[ZcShareVault](ZcBatchTransactions.nodeBoxOf(legs(1).getOutputsToSpend.get(0), "00" * 32, 0))
      nextVault.state shouldBe Some(2.toByte)
      ZcRules.currencyHeld(nextVault) shouldBe 180L
    }
  }

  it should "atomically arbitrage a DutchAuctionVault excess auction (state 4), taking only thin items and leaving thick ones" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      // Thin token: 50 units (< threshold 100), worth ~3 ERG in AMM! Thick token: 500 units (>= 100) stays in vault!
      val pThin = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 15000L)) // 50 TokenA ~= 3.3 ERG
      val pools = Map(pThin.nft -> pThin.pool)
      val poolBoxes = Map(pThin.nft -> SigmaFiBoxes.input(pThin.box, ctx).get)
      val stake = SigmaFiBoxes.input(arbStakeBox(ctx, wallet, 10 * Erg), ctx).get

      // Excess auction: repayment 2 ERG, opening 100%, length 100, elapsed 50 -> excessPrice = 1 ERG
      val vaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 2 * Erg,
        Seq(tok("55" * 32, ShareCount + 1L), tok(TokenA, 50L), tok(TokenB, 500L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(4),
          longs(2 * Erg, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 50L), bytes("")), Some(h - 60)))
      val vault = read[ZcShareVault](nodeOf(vaultIn))

      val plan = ZcCollateralArb.evaluateVault(vault, pools, h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      plan.netProfit should be > (2 * Erg)

      val (legs, _, nextCarry, profitOut) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, profitTo, MinBox).get
      legs.size shouldBe 2
      nextCarry.value shouldBe stake.value
      nextCarry.tokens shouldBe empty
      profitOut.value shouldBe plan.netProfit
      // Thick token TokenB stayed in vault
      val soldVault = legs.head.getOutputsToSpend.get(0)
      soldVault.getTokens.get(1).getId.toString shouldBe TokenB
      soldVault.getTokens.get(1).getValue shouldBe 500L
    }
  }

  it should "atomically arbitrage a SaleOrder selling an AMM-priced token (optimal partial or full fill)" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L)) // 1000 TokenA ~= 1 ERG
      val pools = Map(pA.nft -> pA.pool)
      val poolBoxes = Map(pA.nft -> SigmaFiBoxes.input(pA.box, ctx).get)
      val stake = SigmaFiBoxes.input(arbStakeBox(ctx, wallet, 10 * Erg), ctx).get

      // SaleOrder offers 20,000 TokenA at 0.5 ERG per 1000 TokenA (half of AMM spot!), minFill 1000
      val askIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 20000L)), Seq(bytes(sellerHash), longs(500000000L, 1000L, 1000L, h.toLong + 500L)), Some(h - 5)))
      val ask = read[ZcAsk](nodeOf(askIn))

      // Cap at 4 ERG -> forces a partial fill (8000 TokenA for 4 ERG, selling for ~7.9 ERG -> ~3.9 ERG profit!)
      val plan = ZcCollateralArb.evaluateSaleOrder(ask, pools, _ => Some(seller), h.toLong, 4 * Erg, 6, 100000L, ZcAssetPricer.Default).get
      plan.target.asInstanceOf[ZcSaleOrderTarget].full shouldBe false
      plan.netProfit should be > (3 * Erg)

      val (legs, _, nextCarry, profitOut) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, profitTo, MinBox).get
      legs.size shouldBe 2
      nextCarry.value shouldBe stake.value
      nextCarry.tokens shouldBe empty
      profitOut.value shouldBe plan.netProfit
      // Verify ask continuation exists at output 1 of leg 0
      read[ZcAsk](ZcBatchTransactions.nodeBoxOf(legs.head.getOutputsToSpend.get(1), "00" * 32, 1)).predecessor shouldBe Some(ask.boxId)
    }
  }

  // ─── strict profit-safety & refusal tests ──────────────────────────────────

  "Profit safety" should "refuse unprofitable auctions, unpriced-only baskets, self-currency lots and sweep-cost losses" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pUsd = poolEntry(nativePool(ctx, arbId(2), SigUsd, 1000 * Erg, 200000L))
      val pools = Map(pA.nft -> pA.pool, pUsd.nft -> pUsd.pool)

      // 1. Overpriced ERG lot (5000 TokenA worth ~4.95 ERG, priced at 5 ERG)
      val pricey = read[ZcLot](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(tok(TokenA, 5000L)), Seq(bytes(sellerHash), longs(5 * Erg, 5 * Erg, h - 10, h + 100, h + 500, 2000000L))))))
      ZcCollateralArb.evaluateLot(pricey, pools, _ => Some(seller), h, 8 * Erg, 6, 100000L, ZcAssetPricer.Default) shouldBe None

      // 2. Unpriced-only basket (0 ERG value -> never bought)
      val unpricedLot = read[ZcLot](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(tok(UnpricedNft, 1L)), Seq(bytes(sellerHash), longs(MinBox, MinBox, h - 10, h + 100, h + 500, 2000000L))))))
      ZcCollateralArb.evaluateLot(unpricedLot, pools, _ => Some(seller), h, 8 * Erg, 6, 100000L, ZcAssetPricer.Default) shouldBe None

      // 3. SigUSD lot holding SigUSD itself (contract refuses `holdsCurrency`)
      val selfCurrency = read[ZcLot](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.SigUsd)).contract,
        5 * Erg, Seq(tok(SigUsd, 100L)), Seq(bytes(sellerHash), longs(10L, 10L, h - 10, h + 100, h + 500, 2000000L))))))
      ZcCollateralArb.evaluateLot(selfCurrency, pools, _ => Some(seller), h, 8 * Erg, 6, 100000L, ZcAssetPricer.Default) shouldBe None

      // 4. Barely profitable BEFORE unpriced NFT sweep cost, unprofitable AFTER deducting MinBox sweep cost
      val exactOut = pA.pool.quote(2000L, xIn = false)
      val price = exactOut - 500000L // 0.0005 ERG gross gain, minus 0.001 ERG sweep => -0.0005 ERG loss!
      val sweepLoss = read[ZcLot](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(tok(TokenA, 2000L), tok(UnpricedNft, 1L)),
        Seq(bytes(sellerHash), longs(price, price, h - 10, h + 100, h + 500, 2000000L))))))
      ZcCollateralArb.evaluateLot(sweepLoss, pools, _ => Some(seller), h, 8 * Erg, 6, 100000L, ZcAssetPricer.Default) shouldBe None

      // 5. SigUSD excess vault with saleKeepsErg = true and unpriced sweep token: must enforce acqCost + MinBox <= maxErgIn
      val sigVaultIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 2 * Erg,
        Seq(tok("55" * 32, ShareCount + 1L), tok(SigUsd, 100L), tok(TokenA, 50L), tok(UnpricedNft, 1L)),
        Seq(bytes("66" * 32), longs(ShareCount, ShareCount, 5000000L), byte(4),
          longs(200L, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 50L), bytes(SigUsd)), Some(h - 60)))
      val sigVault = read[ZcShareVault](nodeOf(sigVaultIn))
      ZcRules.saleKeepsErg(sigVault) shouldBe true
      // Need 100 SigUSD (~0.5 ERG); give maxErgIn = acqCost exactly so acqCost <= maxErgIn but acqCost + MinBox > maxErgIn
      val pThin = poolEntry(nativePool(ctx, arbId(3), TokenA, 1000 * Erg, 15000L))
      val sigPools = Map(pUsd.nft -> pUsd.pool, pThin.nft -> pThin.pool)
      val exactAcq = ZcCollateralPricing.acquireToken(SigUsd, 100L, sigPools).get._1.amountIn
      ZcCollateralArb.evaluateVault(sigVault, sigPools, h.toLong, maxErgIn = exactAcq, 6, 100000L, ZcAssetPricer.Default) shouldBe None
      ZcCollateralArb.evaluateVault(sigVault, sigPools, h.toLong, maxErgIn = exactAcq + MinBox, 6, 100000L, ZcAssetPricer.Default) shouldBe defined
    }
  }

  it should "find real profitable collateral arb even under a flood of 300 unpriced and dust spam orders" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)

      // 200 unpriced-token spam asks + 120 1-unit TokenA dust spam asks (past MaxEvaluatedTargets = 200)
      val unpricedSpam = (1 to 200).map { i =>
        val fakeTok = f"dd${i}%062x"
        nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox,
          Seq(tok(fakeTok, 1000000L)), Seq(bytes(sellerHash), longs(MinBox, 1L, 1L, h.toLong + 500L)), Some(h - 5))))
      }
      val dustSpam = (1 to 120).map { _ =>
        nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox,
          Seq(tok(TokenA, 1L)), Seq(bytes(sellerHash), longs(MinBox, 1L, 1L, h.toLong + 500L)), Some(h - 5))))
      }
      val realLot = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10))))

      val book = bookOf(h - 1, (unpricedSpam ++ dustSpam :+ realLot): _*)
      val found = ZcCollateralArb.bestPlan(book, Seq.empty, pools, _ => Some(seller), h, 8 * Erg, MinBox,
        slots = 6, minLegProceeds = 100000L, spentBoxes = Set.empty)
      found shouldBe defined
      found.get._1.target.boxId shouldBe realLot.boxId
    }
  }

  "ArbCycles with ZcArbContext" should "chain ZC collateral arb into DEX cycles on the same stake without double-spends" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA1 = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pA2 = poolEntry(nativePool(ctx, arbId(2), TokenA, 1100 * Erg, 1000000L)) // 10% DEX gap too!
      val index = ArbDiscovery.index(h - 1, Seq(pA1, pA2)).copy(parsed = Map(pA1.nft -> pA1, pA2.nft -> pA2))
      val stakeBox = arbStakeBox(ctx, wallet, 12 * Erg)

      val lotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L)), Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h - 10, h + 100, h + 500, 2000000L)), Some(h - 10)))
      val book = bookOf(h - 1, nodeOf(lotIn))
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, scala.concurrent.ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      // Pre-seed seller script into resolver by putting it in a mock tx lookup
      val sellerTree = seller.ergoTreeHex
      val tx = node.model.IndexedTransaction(lotIn.input.getTransactionId, Seq.empty, Seq.empty,
        Seq(node.model.IndexedBox(nodeOf(lotIn).copy(ergoTree = sellerTree), "", h - 10, 1L)),
        h - 10, 1, "", 0L, 0, 0L, 100)
      org.mockito.Mockito.when(chain.api.indexedTransactionById(lotIn.input.getTransactionId))
        .thenReturn(scala.util.Success(Some(tx)))

      var reserved = Vector.empty[String]
      val zcCtx = ArbCycles.ZcArbContext(book, resolver, maxBundles = 5, maxCost = 10 * Erg, minProfit = MinBox,
        minLegProceeds = 100000L)
      val bundles = ArbCycles.build(ctx, wallet, index, ArbBasis.Empty, Some(stakeBox), Seq.empty, MinBox, h, slots = 10,
        maxIn = 10 * Erg, minProfit = MinBox, profitTo = profitTo,
        selectUntil = Deadline.now + 10.seconds, buildUntil = Deadline.now + 10.seconds,
        reserve = id => { reserved :+= id; true }, zc = Some(zcCtx))

      bundles.size should be >= 2
      bundles.head.members.head.kind shouldBe ArbKind.ZcArb
      bundles.tail.exists(_.members.head.kind == ArbKind.ErgoDexCycle) shouldBe true
      // Second bundle must chain from the ZC arb bundle's last tx!
      bundles(1).packageParents should contain(bundles.head.members.last.id)
      reserved.size shouldBe bundles.size
    }
  }

  it should "rank by estimated net profit so high-value non-arb listings cannot crowd out real smaller arbs" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L)) // spot ~0.001 ERG / TokenA
      val pools = Map(pA.nft -> pA.pool)

      // 250 high-value non-arb asks (asking 2x spot = 0.002 ERG/TokenA for 50,000 TokenA each -> huge gross value, negative net!)
      val overpriced = (1 to 250).map { _ =>
        nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox,
          Seq(tok(TokenA, 50000L)), Seq(bytes(sellerHash), longs(2000000L, 1L, 1L, h.toLong + 500L)), Some(h - 5))))
      }
      // One real profitable smaller lot (5,000 TokenA worth ~5 ERG for 2 ERG)
      val realLot = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10))))

      val book = bookOf(h - 1, (overpriced :+ realLot): _*)
      val found = ZcCollateralArb.bestPlan(book, Seq.empty, pools, _ => Some(seller), h, 8 * Erg, MinBox,
        slots = 6, minLegProceeds = 100000L, spentBoxes = Set.empty)
      found shouldBe defined
      found.get._1.target.boxId shouldBe realLot.boxId
    }
  }

  it should "find the optimal partial SaleOrder fill even when minFill is unprofitable and full amount exceeds maxErgIn" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      // Pool: 1000 ERG / 1,000,000 TokenA (~1,000,000 nanoERG per TokenA)
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)
      // Ask selling 500,000 TokenA at 0.5x spot (500,000 nanoERG/TokenA), minFill = 1 (proceeds ~1M - MinBox 1M = 0 -> unprofitable!)
      // Full fill costs 250 ERG >> maxErgIn (2 ERG), so both ends of [1, 499999] fail tryUnits!
      val askBox = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox,
        Seq(tok(TokenA, 500000L)), Seq(bytes(sellerHash), longs(500000L, 1L, 1L, h.toLong + 500L)), Some(h - 5))))
      val ask = ZcBoxes.read(askBox).get.asInstanceOf[ZcAsk]
      val plan = ZcCollateralArb.evaluateSaleOrder(ask, pools, _ => Some(seller), h.toLong,
        maxErgIn = 2 * Erg, maxLegs = 4, minLegProceeds = 100000L, pricer = ZcAssetPricer.Default)
      plan shouldBe defined
      plan.get.target.asInstanceOf[ZcSaleOrderTarget].full shouldBe false
      plan.get.netProfit should be > Erg // ~4000 TokenA bought for 2 ERG, sold for ~3.97 ERG -> ~1.97 ERG profit!
    }
  }

  it should "cap unbuildable targets per build in ZcCollateralArb.buildAll so spam cannot starve DEX cycles" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val states = Map(pA.nft -> pA.pool)
      val places: Map[String, (Either[node.model.NodeBox, InputUTXO], Option[String])] =
        Map(pA.nft -> (Left(pA.box) -> None))
      val stakeBox = arbStakeBox(ctx, wallet, 10 * Erg)
      val stake = SigmaFiBoxes.input(stakeBox, ctx).get

      // 20 lots whose book boxIds mismatch their serialized box so sign fails (testing MaxUnbuildablePerBuild cap)
      val badLots = (1 to 20).map { _ =>
        nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
          Seq(tok(TokenA, 5000L)),
          Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
          Some(h - 10))))
      }
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, scala.concurrent.ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      var refusedCount = 0
      val corruptedLots = badLots.zipWithIndex.map { case (b, i) =>
        val fakeId = f"aa${i + 1}%062x"
        val tx = node.model.IndexedTransaction(b.transactionId, Seq.empty, Seq.empty,
          Seq(node.model.IndexedBox(b.copy(ergoTree = seller.ergoTreeHex), "", h - 10, 1L)),
          h - 10, 1, "", 0L, 0, 0L, 100)
        org.mockito.Mockito.when(chain.api.indexedTransactionById(b.transactionId)).thenReturn(scala.util.Success(Some(tx)))
        b.copy(boxId = fakeId)
      }
      val spamBook = bookOf(h - 1, corruptedLots: _*)
      val (bundles, _, _, _, _) = ZcCollateralArb.buildAll(ctx, wallet, spamBook, resolver, ArbBasis.Empty,
        states, places, stake, None, stake, h, slots = 10, maxBundles = 5, maxErgIn = 8 * Erg, minProfit = MinBox,
        minLegProceeds = 100000L, profitTo = profitTo,
        selectUntil = Deadline.now + 10.seconds, buildUntil = Deadline.now + 10.seconds,
        reserve = _ => true, refused = Set.empty, onRefuse = _ => refusedCount += 1)
      bundles shouldBe empty
      refusedCount shouldBe ZcCollateralArb.MaxUnbuildablePerBuild
    }
  }

  "A SigUSD lot" should "liquidate its collateral through a SigmaUSD bank redeem, signed against the real bank" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val doc = {
        val source = scala.io.Source.fromResource("arb/sigmausd-bank.json")
        try new com.google.gson.JsonParser().parse(source.mkString).getAsJsonObject finally source.close()
      }
      val bankBox = node.rest.NodeCodecs.box(doc.getAsJsonObject("bank"))
      val oracleBox = node.rest.NodeCodecs.box(doc.getAsJsonObject("oracle"))
      val sides = SigmaUsdArbPool.read(bankBox, oracleBox)
      val scPrice = sides.head.bank.scPrice
      // The only pool sells SigUSD 10% under the bank, so the bank pays more for the 1 SigUSD in the lot
      val pUsd = poolEntry(nativePool(ctx, arbId(1), SigUsd, scPrice * 10000000L * 9 / 10, 10000000L))
      val pools = Map(pUsd.nft -> pUsd.pool) ++ sides.map(s => s.nft -> s)
      val bankIn = SigmaFiBoxes.input(bankBox, ctx).get
      val poolBoxes = Map(pUsd.nft -> SigmaFiBoxes.input(pUsd.box, ctx).get) ++ sides.map(s => s.nft -> bankIn)
      val stake = SigmaFiBoxes.input(arbStakeBox(ctx, wallet, 10 * Erg), ctx).get
      val cost = scPrice * 100L * 9 / 10
      val lotIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(SigUsd, 100L)),
        Seq(bytes(sellerHash), longs(cost, cost, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10)))
      val lot = read[ZcLot](nodeOf(lotIn))
      val plan = ZcCollateralArb.evaluateLot(lot, pools, _ => Some(seller), h.toLong, 8 * Erg, 6, 100000L,
        ZcAssetPricer.Default).get
      plan.valuation.liquidations.map(_.getClass) shouldBe Vector(classOf[BankAssetLiquidation])
      val (legs, _, nextCarry, profitOut) = ZcCollateralArb.sign(ctx, wallet, plan, poolBoxes, stake, h, profitTo, MinBox).get
      legs.size shouldBe 2
      legs(1).getSignedInputs.get(0).getId.toString shouldBe bankBox.boxId
      nextCarry.value shouldBe stake.value
      profitOut.value shouldBe plan.netProfit
    }
  }

  it should "cap spot-score ranking by pool ERG liquidity so thin-pool spam cannot eclipse real arbs" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val spamToken = "ee" * 32
      // Tiny 1 ERG pool holding 1000 units of spamToken (spot = 0.001 ERG/token, max ERG liquidity = 1 ERG)
      val pSpam = poolEntry(nativePool(ctx, arbId(10), spamToken, 1 * Erg, 1000L))
      // Deep 1000 ERG pool for TokenA
      val pA = poolEntry(nativePool(ctx, arbId(11), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pSpam.nft -> pSpam.pool, pA.nft -> pA.pool)

      // 210 spam SaleOrders (past MaxEvaluatedTargets = 200) each holding 10,000,000 spamToken priced at 0.5 ERG
      // Uncapped spot valuation would be 10,000 ERG each, eclipsing realLot; capped at 1 ERG depth -> net <= 0.5 ERG!
      val spamAsks = (1 to 210).map { _ =>
        nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, MinBox,
          Seq(tok(spamToken, 10000000L)), Seq(bytes(sellerHash), longs(500000000L, 10000000L, 10000000L, h.toLong + 500L)),
          Some(h - 5))))
      }
      // Real LotAuction netting ~3 ERG profit (5000 TokenA worth ~5 ERG for 2 ERG)
      val realLot = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(TokenA, 5000L)),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10))))

      val book = bookOf(h - 1, (spamAsks :+ realLot): _*)
      val found = ZcCollateralArb.bestPlan(book, Seq.empty, pools, _ => Some(seller), h, 8 * Erg, MinBox,
        slots = 6, minLegProceeds = 100000L, spentBoxes = Set.empty)
      found shouldBe defined
      found.get._1.target.boxId shouldBe realLot.boxId
    }
  }

  it should "filter untraded junk tokens before MaxBasketTokens so 16 dust tokens cannot hide a valuable token" in {
    client.execute { ctx =>
      val h = ctx.getHeight + 1
      val pA = poolEntry(nativePool(ctx, arbId(12), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)
      val junk = (1 to 17).map(i => tok(f"aa${i}%062x", 1L))
      val lot = read[ZcLot](nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract,
        3000000L, junk :+ tok(TokenA, 5000L),
        Seq(bytes(sellerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L)),
        Some(h - 10)))))
      val plan = ZcCollateralArb.evaluateLot(lot, pools, _ => Some(seller), h.toLong, 8 * Erg, 6, 100000L, ZcAssetPricer.Default)
      plan shouldBe defined
      plan.get.valuation.liquidations.map(_.tokenId) shouldBe Vector(TokenA)
      plan.get.valuation.unpriced.size shouldBe 17
    }
  }
}
