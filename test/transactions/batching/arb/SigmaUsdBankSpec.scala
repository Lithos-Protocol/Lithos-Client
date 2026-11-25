package transactions.batching.arb

import node.MutationConversions._
import node.model.{NodeAsset, NodeBox}
import node.rest.NodeCodecs
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import pricing.SigmaUsdBank
import scorex.crypto.hash.Blake2b256
import sigmausd.SigmaUsdContracts
import support.LDNodeFixtures
import transactions.batching.sigmafi.zc.{AmmAssetPricer, BankAssetLiquidation, BankAssetPricer, ZcAmmLiquidation,
  ZcCollateralPricing}
import transactions.batching.tracking.{PoolDeployment, PoolShapes, TrackedBox, TrackedPools}
import work.lithos.mutations.{Token, UTXO}

import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

/**
 * The SigmaUSD bank as an arb leg and a ZC pricer, on the bank and oracle boxes read from mainnet at 1,924,254 and
 * the 291 bank transactions from 1,908,570 to there (164 SigUSD redeems, 127 SigRSV mints, reserve ratio 175–295%).
 * These pin the bank maths to every recorded transaction, the pins, the ratio bounds against the real bank script,
 * a cycle each way signed against it, the oracle the bank is priced at, and SigUSD collateral liquidated through
 * the bank or the pool, whichever pays more.
 */
class SigmaUsdBankSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private lazy val doc = {
    val source = Source.fromResource("arb/sigmausd-bank.json")
    try new com.google.gson.JsonParser().parse(source.mkString).getAsJsonObject finally source.close()
  }
  private lazy val bankBox: NodeBox = NodeCodecs.box(doc.getAsJsonObject("bank"))
  private lazy val oracleBox: NodeBox = NodeCodecs.box(doc.getAsJsonObject("oracle"))
  private lazy val sides: Seq[SigmaUsdArbPool] = SigmaUsdArbPool.read(bankBox, oracleBox)
  private def side(sc: Boolean): SigmaUsdArbPool = sides.find(_.sc == sc).get
  private def bank: SigmaUsdBank = side(true).bank

  private def long(hex: String): Long = ErgoValue.fromHex(hex).getValue.asInstanceOf[java.lang.Long].longValue

  "The pins" should "hold the bank's script, NFT, coins and oracle as read from chain" in {
    Hex.toHexString(Blake2b256.hash(Hex.decode(SigmaUsdContracts.BankErgoTree))) shouldBe BankTreeHash
    bankBox.ergoTree shouldBe SigmaUsdContracts.BankErgoTree
    SigmaUsdContracts.BankNft shouldBe "7d672d1def471720ca5782fd6473e47e796d9ac0c138d9911346f118b2f6d9d9"
    SigmaUsdContracts.OracleNft shouldBe "011d3364de07e5a26f0c4eef0852cddb387039a921b7154ef3cab22c6eda887f"
    SigmaUsdContracts.SigUsd shouldBe "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"
    SigmaUsdContracts.SigRsv shouldBe "003bd19d0187117f130b62e1bcab0939929ff5c7709f843c5c4dd158949285d0"
    withClue("the script names the oracle pool NFT the pin says: ") {
      SigmaUsdContracts.BankErgoTree should include(SigmaUsdContracts.OracleNft)
    }
    bankBox.assets.map(_.tokenId) shouldBe Seq(SigmaUsdContracts.SigUsd, SigmaUsdContracts.SigRsv, SigmaUsdContracts.BankNft)
    sides.map(_.sc).toSet shouldBe Set(true, false)
  }

  "The bank maths" should "reproduce every recorded bank transaction to the nanoERG" in {
    val history = doc.getAsJsonArray("history")
    history.size shouldBe 291
    (0 until history.size).foreach { i =>
      val h = history.get(i).getAsJsonObject
      def state(o: com.google.gson.JsonObject, rate: Long) = SigmaUsdBank(o.get("v").getAsString.toLong,
        long(o.get("R4").getAsString), long(o.get("R5").getAsString), o.get("sc").getAsString.toLong,
        o.get("rc").getAsString.toLong, rate)
      val rate = SigmaUsdBank.rateOf(long(h.get("oracleR4").getAsString))
      val in = state(h.getAsJsonObject("in"), rate)
      val out = state(h.getAsJsonObject("out"), rate)
      val sc = in.scTokens != out.scTokens
      val delta = if (sc) in.scTokens - out.scTokens else in.rcTokens - out.rcTokens
      withClue(s"transaction ${h.get("tx").getAsString}: ") {
        val ex = in.exchange(sc, delta).getOrElse(fail("the maths refuse what the bank accepted"))
        ex.after shouldBe out
        ex.reserveDelta shouldBe out.reserve - in.reserve
        val receipt = h.getAsJsonObject("receipt")
        ex.circDelta shouldBe long(receipt.get("R4").getAsString)
        ex.reserveDelta shouldBe long(receipt.get("R5").getAsString)
      }
    }
  }

  it should "open and close each operation by the ratio it leaves" in {
    bank.ratioPercent should (be > 100L and be < 400L)
    withClue("below 400%, a SigUSD redeem and a SigRSV mint are open: ") {
      bank.redeem(sc = true, 10000L) shouldBe defined
      bank.mintOf(sc = false, 1000L) shouldBe defined
    }
    withClue("a SigUSD mint and a SigRSV redeem are closed: ") {
      bank.mintOf(sc = true, 1L) shouldBe None
      bank.redeem(sc = false, 1L) shouldBe None
    }
    // Enough reserve that minting SigUSD lands on 400%: the most allowed is open, one coin more is not
    val rich = bank.copy(reserve = bank.scCirc * bank.rate * 5L)
    val most = rich.mintFor(sc = true, Long.MaxValue / 4).get
    most.after.ratioPercent should be >= 400L
    rich.mintOf(sc = true, most.circDelta + 1L) shouldBe None
    withClue("past 800% a SigRSV mint closes: ") {
      val over = bank.copy(reserve = bank.scCirc * bank.rate * 81L / 10L)
      over.mintOf(sc = false, 1L) shouldBe None
      over.redeem(sc = false, 1L) shouldBe defined
    }
  }

  it should "mint the most coins an amount pays for, at exactly their cost" in {
    val erg = 37 * Erg + 123456L
    val ex = bank.mintFor(sc = false, erg).get
    ex.reserveDelta should be <= erg
    bank.mintOf(sc = false, ex.circDelta + 1L).get.reserveDelta should be > erg
    bank.mintOf(sc = false, ex.circDelta).get shouldBe ex
  }

  "A bank leg" should "sign against the real bank script, each open operation, with the receipt on the carry" in {
    nodeContext.getClient.execute { ctx =>
      val redeem = side(true).swap(25000L, xIn = false).get
      val carryIn = carry(ctx, 1 * Erg, Seq(Token(ErgoId.create(SigmaUsdContracts.SigUsd), 25000L)))
      val tx = ArbLeg.build(ctx, wallet, redeem, bankInput(ctx), Seq(carryIn), stakeContract(wallet), 1924255)
      val out = tx.getOutputsToSpend
      out.get(0).getValue.longValue shouldBe bankBox.value - redeem.amountOut
      out.get(1).getValue.longValue shouldBe 1 * Erg + redeem.amountOut
      out.get(1).getRegisters.get(0).getValue shouldBe -25000L
      out.get(1).getRegisters.get(1).getValue shouldBe -redeem.amountOut
      tx.getSignedInputs.size shouldBe 2

      val mint = side(false).swap(5 * Erg, xIn = true).get
      val minted = ArbLeg.build(ctx, wallet, mint, bankInput(ctx), Seq(carry(ctx, 6 * Erg, Seq.empty)),
        stakeContract(wallet), 1924255)
      minted.getOutputsToSpend.get(1).getTokens.get(0).getValue shouldBe mint.amountOut
    }
  }

  it should "be refused by the bank script one coin past the 400% bound the maths stop at" in {
    nodeContext.getClient.execute { ctx =>
      val rich = bankBox.copy(value = bank.scCirc * bank.rate * 5L)
      val box = LDNodeFixtures.nodeBox(ctx, rich.toUTXO, 0, "b4" * 32)
      val pool = SigmaUsdArbPool.read(box, oracleBox).find(_.sc).get
      val most = pool.bank.mintFor(sc = true, Long.MaxValue / 4).get
      val allowed = pool.swapFor(most.circDelta, xIn = true).get
      val fund = most.reserveDelta + Erg
      Try(ArbLeg.build(ctx, wallet, allowed, box.toInputUTXO(ctx), Seq(carry(ctx, fund, Seq.empty)),
        stakeContract(wallet), 1924255)) shouldBe a[scala.util.Success[_]]
      // The same rule with the ratio check left out, for one coin more
      val n = most.circDelta + 1L
      val expected = pool.bank.scPrice * n
      val paid = expected + math.abs(expected * SigmaUsdBank.FeePercent / 100L)
      val after = pool.bank.copy(reserve = pool.bank.reserve + paid, scCirc = pool.bank.scCirc + n,
        scTokens = pool.bank.scTokens - n)
      after.ratioPercent should be < 400L
      val forced = ArbSwap(pool, paid, xIn = true, n, pool.copy(bank = after))
      Try(ArbLeg.build(ctx, wallet, forced, box.toInputUTXO(ctx), Seq(carry(ctx, paid + Erg, Seq.empty)),
        stakeContract(wallet), 1924255)) shouldBe a[scala.util.Failure[_]]
    }
  }

  "A cycle" should "buy SigUSD where a pool sells it cheap and redeem it at the bank, signed" in {
    nodeContext.getClient.execute { ctx =>
      // The bank redeems a cent for ~0.98 of the oracle rate; this pool sells SigUSD 10% under it
      val cents = 10000000L
      val usd = nativePool(ctx, id(41), SigmaUsdContracts.SigUsd, bank.scPrice * cents * 9 / 10, cents)
      val bundles = cycle(ctx, usd)
      bundles should not be empty
      val legs = bundles.head.members
      legs.size shouldBe 2
      legs.head.inputIds should contain(usd.boxId)
      legs(1).inputIds should contain(bankBox.boxId)
    }
  }

  it should "mint SigRSV at the bank and sell it where a pool pays more, signed" in {
    nodeContext.getClient.execute { ctx =>
      val coins = 100000000L
      val rsv = nativePool(ctx, id(42), SigmaUsdContracts.SigRsv, bank.rcPrice * coins * 12 / 10, coins)
      val bundles = cycle(ctx, rsv)
      bundles should not be empty
      val legs = bundles.head.members
      legs.size shouldBe 2
      legs.head.inputIds should contain(bankBox.boxId)
      legs(1).inputIds should contain(rsv.boxId)
      withClue("one bank leg per cycle, and the two sides move together: ") {
        bundles.flatMap(_.members).count(_.inputIds.contains(bankBox.boxId)) shouldBe 1
      }
    }
  }

  "Routes" should "take at most one side of the bank, since both sides are one box" in {
    nodeContext.getClient.execute { ctx =>
      val rsvUsd = entry(ctx, tokenPool(ctx, id(46), SigmaUsdContracts.SigRsv, SigmaUsdContracts.SigUsd, 1000000000L,
        10000000L)).pool
      val usd = entry(ctx, nativePool(ctx, id(47), SigmaUsdContracts.SigUsd, 1000 * Erg, 10000000L)).pool
      val (routes, _) = ArbRoutes.enumerate(Seq(side(true), side(false), rsvUsd, usd), 100)
      routes should not be empty
      routes.foreach(r => r.hops.count(_.nft.startsWith(SigmaUsdContracts.BankNft)) should be <= 1)
      routes.exists(_.hops.exists(_.nft == SigmaUsdArbPool.key(false))) shouldBe true
    }
  }

  "Discovery" should "price the bank at the oracle box tracked with it, and again when the oracle moves" in {
    nodeContext.getClient.execute { ctx =>
      val shapes = new PoolShapes(org.ergoplatform.appkit.NetworkType.MAINNET)
      shapes.read(bankBox, 1924250).map(_.deployment) shouldBe Some(PoolDeployment.SigmaUsdBank)
      shapes.read(oracleBox, 1924250).map(_.deployment) shouldBe Some(PoolDeployment.SigmaUsdOracle)
      shapes.read(bankBox.copy(ergoTree = wallet.contract.ergoTreeHex), 1924250) shouldBe None
      val tracked = TrackedPools.seeded(1924254, "aa" * 32,
        Seq(shapes.read(bankBox, 1924250).get, shapes.read(oracleBox, 1924250).get), cut = false)
      tracked.pool(SigmaUsdContracts.BankNft).map(_.boxId) shouldBe Some(bankBox.boxId)
      val first = ArbDiscovery.index(ctx, tracked, None, configs.BatchingConfig.Default, Set.empty, _ => true)
      first.parsed.keySet shouldBe Set(SigmaUsdArbPool.key(true), SigmaUsdArbPool.key(false))
      val rate = first.parsed(SigmaUsdArbPool.key(true)).pool.asInstanceOf[SigmaUsdArbPool].bank.rate
      rate shouldBe SigmaUsdBank.rateOf(long(oracleBox.additionalRegisters.ordered.head))

      val datapoint = long(oracleBox.additionalRegisters.ordered.head)
      val doubled = oracleBox.copy(boxId = "0d" * 32, additionalRegisters = oracleBox.additionalRegisters.copy(
        values = oracleBox.additionalRegisters.values.updated("R4", ErgoValue.of(datapoint * 2).toHex)))
      val moved = TrackedPools.seeded(1924255, "bb" * 32,
        Seq(shapes.read(bankBox, 1924250).get, shapes.read(doubled, 1924255).get), cut = false)
      val next = ArbDiscovery.index(ctx, moved, Some(first), configs.BatchingConfig.Default, Set.empty,
        _ => true)
      next.parsed(SigmaUsdArbPool.key(true)).pool.asInstanceOf[SigmaUsdArbPool].bank.rate shouldBe
        SigmaUsdBank.rateOf(datapoint * 2)
      next.parsed(SigmaUsdArbPool.key(false)).pool.asInstanceOf[SigmaUsdArbPool].oracle.boxId shouldBe doubled.boxId
      withClue("without the oracle box the bank is not offered: ") {
        val alone = TrackedPools.seeded(1924255, "cc" * 32, Seq(shapes.read(bankBox, 1924250).get), cut = false)
        ArbDiscovery.index(ctx, alone, None, configs.BatchingConfig.Default, Set.empty, _ => true)
          .parsed shouldBe empty
      }
    }
  }

  "ZC pricing" should "liquidate SigUSD through a bank redeem where it pays more than the pool" in {
    nodeContext.getClient.execute { ctx =>
      val cents = 10000000L
      val usd = entry(ctx, nativePool(ctx, id(43), SigmaUsdContracts.SigUsd, bank.scPrice * cents * 9 / 10, cents))
      val pools = Map(usd.nft -> usd.pool) ++ sides.map(s => s.nft -> s)
      val (plan, after) = BankAssetPricer.price(SigmaUsdContracts.SigUsd, 50000L, pools, 2, 100000L).get
      plan shouldBe a[BankAssetLiquidation]
      plan.ergOut shouldBe side(true).bank.redeem(sc = true, 50000L).get.reserveDelta.abs
      plan.ergOut should be > AmmAssetPricer.price(SigmaUsdContracts.SigUsd, 50000L, pools, 2, 100000L).get._1.ergOut
      withClue("both sides of the bank move: ") {
        after(SigmaUsdArbPool.key(false)).asInstanceOf[SigmaUsdArbPool].bank shouldBe
          after(SigmaUsdArbPool.key(true)).asInstanceOf[SigmaUsdArbPool].bank
      }
      val basket = ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(SigmaUsdContracts.SigUsd, 50000L)), pools)
      basket.liquidations.map(_.getClass) shouldBe Vector(classOf[BankAssetLiquidation])
    }
  }

  it should "leave SigUSD to the pool past what the bank can redeem, or where the pool pays more" in {
    nodeContext.getClient.execute { ctx =>
      val cents = 10000000L
      val dear = entry(ctx, nativePool(ctx, id(44), SigmaUsdContracts.SigUsd, bank.scPrice * cents * 11 / 10, cents))
      val pools = Map(dear.nft -> dear.pool) ++ sides.map(s => s.nft -> s)
      BankAssetPricer.price(SigmaUsdContracts.SigUsd, 50000L, pools, 2, 100000L) shouldBe None
      ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(SigmaUsdContracts.SigUsd, 50000L)), pools)
        .liquidations.map(_.getClass) shouldBe Vector(classOf[ZcAmmLiquidation])
      val past = bank.scCirc + 1L
      val cheap = entry(ctx, nativePool(ctx, id(45), SigmaUsdContracts.SigUsd, 100000 * Erg, past * 4))
      val all = Map(cheap.nft -> cheap.pool) ++ sides.map(s => s.nft -> s)
      BankAssetPricer.price(SigmaUsdContracts.SigUsd, past, all, 2, 100000L) shouldBe None
      ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(SigmaUsdContracts.SigUsd, past)), all)
        .liquidations.map(_.getClass) shouldBe Vector(classOf[ZcAmmLiquidation])
    }
  }

  private def bankInput(ctx: BlockchainContext) = {
    val in = bankBox.toInputUTXO(ctx)
    in.id.toString shouldBe bankBox.boxId
    in
  }

  private var seeds = 500
  private def carry(ctx: BlockchainContext, value: Long, tokens: Seq[Token]) = {
    seeds += 1
    LDNodeFixtures.nodeBox(ctx, UTXO(stakeContract(wallet), value, tokens), 0, id(seeds)).toInputUTXO(ctx)
  }

  private def cycle(ctx: BlockchainContext, pool: NodeBox) = {
    val entries = entry(ctx, pool) +: sides.map(ArbPoolEntry(_, bankBox))
    val stake = arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
    ArbCycles.build(ctx, wallet, ArbDiscovery.index(1924254, entries), ArbBasis.Empty, Some(stake), Seq.empty,
      1000000L, 1924255, 150, 10 * Erg, 1000000L, wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute,
      _ => true)
  }

  /** Blake2b256 of the bank's tree as read from chain. */
  private val BankTreeHash = "4a90a4403ccc810c957dd0f0241eba31ffbaa65640c452cbcdcfa224458ab9cf"
}
