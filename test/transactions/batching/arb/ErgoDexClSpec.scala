package transactions.batching.arb

import ergodexcl.ErgoDexClContracts
import node.MutationConversions._
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import pricing.{ClPosition, ClProtocol}
import scorex.crypto.hash.Blake2b256
import support.LDNodeFixtures
import transactions.batching.tracking.{PoolDeployment, PoolShapes, TrackedPools}
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.concurrent.duration._
import scala.util.Try

/**
 * ErgoDEX CL positions as arb legs, on the launch deployment's recorded transactions and its config box. These pin the
 * position maths to every recorded swap, the successor the leg builds to what chain holds, legs of each shape signed
 * against the real position and swap scripts, the fee and range rounding the scripts enforce, the config's delisting
 * and fee parameters, discovery, and a cycle through an ErgoDEX pool and a position.
 */
class ErgoDexClSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._
  import ClFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val height = 1924255
  private val SigRsv = "003bd19d0187117f130b62e1bcab0939929ff5c7709f843c5c4dd158949285d0"

  private def int(hex: String): Int = ErgoValue.fromHex(hex).getValue.asInstanceOf[java.lang.Integer].intValue

  private def input(ctx: BlockchainContext, box: NodeBox): InputUTXO = {
    val in = box.toInputUTXO(ctx)
    in.id.toString shouldBe box.boxId
    in
  }

  private var seeds = 900
  private def carry(ctx: BlockchainContext, value: Long, tokens: Seq[Token] = Seq.empty): InputUTXO = {
    seeds += 1
    LDNodeFixtures.nodeBox(ctx, UTXO(stakeContract(wallet), value, tokens), 0, id(seeds)).toInputUTXO(ctx)
  }

  private def leg(ctx: BlockchainContext, swap: ArbSwap, box: NodeBox, carryIn: InputUTXO,
                  profit: Option[Long] = None) =
    ArbLeg.build(ctx, wallet, swap, input(ctx, box), Seq(carryIn), stakeContract(wallet), height,
      profit = profit.map(wallet.contract -> _))

  private def refused(attempt: => Any): Unit = {
    val result = Try(attempt)
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("Script reduced to false")
  }

  "The pins" should "hold the launch position script, swap script and config token as read from chain" in {
    Hex.toHexString(Blake2b256.hash(Hex.decode(ErgoDexClContracts.SwapScript))) shouldBe ErgoDexClContracts.SwapScriptHash
    ErgoDexClContracts.SwapScriptHash shouldBe "70286f997a21187839b4902d464335eafb14c4c397f846ab0c12193b142fe67d"
    ErgoDexClContracts.ConfigToken shouldBe "289c9c0e8a394769cf5b9622546e58b263d20dd2504eabdcaa01515bf149da8b"
    ErgoDexClContracts.PositionErgoTree shouldBe positionTreeHex
    withClue("the position script names the swap script's hash and the config token: ") {
      ErgoDexClContracts.PositionErgoTree should startWith(
        "10020e20" + ErgoDexClContracts.SwapScriptHash + "0e20" + ErgoDexClContracts.ConfigToken)
    }
    configBox.assets.head.tokenId shouldBe ErgoDexClContracts.ConfigToken
    val swapVar = "0e" + Hex.toHexString(vlq(ErgoDexClContracts.SwapScript.length / 2)) + ErgoDexClContracts.SwapScript
    val leaders = spends.flatMap { case (_, tx) => inputs(tx).map(extension) }.filter(_.contains("3"))
    leaders should not be empty
    withClue("every recorded swap passed the pinned swap script: ") {
      leaders.foreach(_("1") shouldBe swapVar)
    }
  }

  "The CL maths" should "reproduce every recorded swap: curve, LP fee, protocol fee and successor" in {
    nodeContext.getClient.execute { ctx =>
      val cfg = config(ctx)
      cfg.protocol shouldBe ClProtocol(4000L, 1L, 10L)
      var swapTxs = 0
      var legs = 0
      var exactFees = 0
      spends.foreach { case (_, tx) =>
        val ins = inputs(tx)
        val outs = outputs(tx)
        val leaderAt = ins.indexWhere(i => extension(i).contains("3"))
        if (leaderAt >= 0) withClue(s"transaction ${tx.get("id").getAsString}: ") {
          swapTxs += 1
          val offset = int(extension(ins(leaderAt))("2"))
          var expected = Map.empty[Option[String], Long]
          ins.zipWithIndex.filter(_._1.get("tree").getAsString == positionTreeHex).foreach { case (in, k) =>
            legs += 1
            val before = ErgoDexClArbPool.read(box(in, "00" * 32, k), cfg).getOrElse(fail(s"input $k does not read"))
            val after = ErgoDexClArbPool.read(outs(k + offset), cfg).getOrElse(fail(s"output ${k + offset} does not read"))
            val (p, q) = (before.position, after.position)
            (q.a, q.b, q.feePpm) shouldBe ((p.a, p.b, p.feePpm))
            val xIn = q.x > p.x
            val net = if (xIn) q.x - p.x else q.y - p.y
            val out = if (xIn) p.y - q.y else p.x - q.x
            val lp = if (xIn) q.feesX - p.feesX else q.feesY - p.feesY
            net should be > 0L
            out should be >= 0L
            (if (xIn) q.feesY - p.feesY else q.feesX - p.feesX) shouldBe 0L
            withClue("the curve: ") { p.curveOut(net, xIn) should be >= out }
            withClue("the LP fee: ") { lp should be >= p.lpFee(net) }
            val fee = ClPosition.protocolFee(net, cfg.protocol.rate(p.feePpm))
            val asset = (if (xIn) before.x else before.y).tokenId
            expected += asset -> (expected.getOrElse(asset, 0L) + fee)
            withClue("the successor the leg builds is the box chain holds: ") {
              val built = before.successor(box(in, "00" * 32, k).toInputUTXO(ctx), after)
              val held = outs(k + offset)
              built.value shouldBe held.value
              built.tokens.map(t => t.id.toString -> t.amount) shouldBe held.assets.map(a => a.tokenId -> a.amount)
              built.registers.map(_.toHex) shouldBe held.additionalRegisters.ordered
            }
          }
          val paid = outs.filter(_.ergoTree.equalsIgnoreCase(cfg.feeTo.ergoTreeHex)).groupBy(_.assets.headOption.map(_.tokenId))
            .map { case (asset, boxes) => asset -> boxes.map(b => if (asset.isEmpty) b.value else b.assets.head.amount).sum }
          expected.filter(_._2 > 0L).foreach { case (asset, fee) =>
            withClue(s"the protocol fee on ${asset.getOrElse("ERG")}: ") {
              paid.getOrElse(asset, 0L) should be >= fee
              if (paid(asset) == fee) exactFees += 1
            }
          }
        }
      }
      info(s"$swapTxs swap transactions, $legs position legs, $exactFees protocol fees paid to the unit")
      swapTxs shouldBe 66
      legs shouldBe 67
    }
  }

  "A CL leg" should "sign against the real scripts with ERG in, its protocol fee paid in ERG" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexClArbPool.read(ergRsv, config(ctx)).get
      val buy = pool.swap(2 * Erg, xIn = true).get
      buy.protocolFee should be > pool.config.ergFeeBox
      buy.feeBoxErg shouldBe 0L
      val tx = leg(ctx, buy, ergRsv, carry(ctx, 3 * Erg))
      val out = tx.getOutputsToSpend
      out.get(0).getValue.longValue shouldBe ergRsv.value + buy.amountIn - buy.protocolFee
      out.get(1).getValue.longValue shouldBe Erg
      out.get(1).getTokens.get(0).getValue shouldBe buy.amountOut
      out.get(2).getValue.longValue shouldBe buy.protocolFee
      Hex.toHexString(out.get(2).getErgoTree.bytes) shouldBe pool.config.feeTo.ergoTreeHex
      out.get(2).getTokens shouldBe empty
      withClue("the ERG fee is the protocol's rate on the net input, rounded down: ") {
        val net = buy.amountIn - buy.protocolFee - (buy.after.asInstanceOf[ErgoDexClArbPool].position.feesX -
          pool.position.feesX)
        buy.protocolFee shouldBe ClPosition.protocolFee(net, 10000000L)
      }
    }
  }

  it should "sign with a token in, its fee in a box whose ERG the carry pays, after the profit output" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexClArbPool.read(ergRsv, config(ctx)).get
      val sell = pool.swap(5000L, xIn = false).get
      sell.protocolFee shouldBe 4L
      sell.feeBoxErg shouldBe pool.config.tokenFeeBox
      val carryIn = carry(ctx, Erg, Seq(Token(ErgoId.create(SigRsv), 5000L)))
      val tx = leg(ctx, sell, ergRsv, carryIn, profit = Some(sell.amountOut))
      val out = tx.getOutputsToSpend
      out.get(0).getValue.longValue shouldBe ergRsv.value - sell.amountOut
      out.get(1).getValue.longValue shouldBe Erg - sell.feeBoxErg
      out.get(1).getTokens shouldBe empty
      out.get(2).getValue.longValue shouldBe sell.amountOut
      out.get(3).getValue.longValue shouldBe pool.config.tokenFeeBox
      out.get(3).getTokens.get(0).getId.toString shouldBe SigRsv
      out.get(3).getTokens.get(0).getValue shouldBe 4L
      withClue("context variable 3 names the fee output after the profit: ") {
        pool.legInput(input(ctx, ergRsv), sell, Seq(3)).ctxVars.find(_.getId == 3.toByte).get.getValue.toHex shouldBe
          "100106"
      }
    }
  }

  it should "sign a token/token leg, whose ERG never moves" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexClArbPool.read(tokenPair, config(ctx)).get
      pool.x.isErg shouldBe false
      val xId = pool.x.tokenId.get
      val swap = pool.swap(300L, xIn = true).get
      swap.amountOut should be > 0L
      val tx = leg(ctx, swap, tokenPair, carry(ctx, Erg, Seq(Token(ErgoId.create(xId), 300L))))
      tx.getOutputsToSpend.get(0).getValue.longValue shouldBe tokenPair.value
    }
  }

  it should "be refused one LP fee unit or one protocol fee unit short" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexClArbPool.read(ergRsv, config(ctx)).get
      // A net input whose LP fee rounds up, and whose protocol fee rounds down, at 1%
      val p = pool.position
      val net = 2 * Erg + 7L
      (BigInt(net) * p.feePpm % (ClPosition.FeeDenom - p.feePpm)) should not be 0
      val lp = p.lpFee(net)
      val fee = ClPosition.protocolFee(net, 10000000L)
      val out = p.curveOut(net, xIn = true)
      def forced(lpPaid: Long, feePaid: Long): ArbSwap = ArbSwap(pool, net + lpPaid + feePaid, xIn = true, out,
        pool.copy(position = p.copy(x = p.x + net, y = p.y - out, feesX = p.feesX + lpPaid)), feePaid)
      leg(ctx, forced(lp, fee), ergRsv, carry(ctx, 3 * Erg))
      refused(leg(ctx, forced(lp - 1L, fee), ergRsv, carry(ctx, 3 * Erg)))
      refused(leg(ctx, forced(lp, fee - 1L), ergRsv, carry(ctx, 3 * Erg)))
      withClue("an exact output costs the least net input that buys it, and the least of each fee on that: ") {
        val priced = pool.swapFor(out, xIn = true).get
        val least = priced.amountIn - priced.protocolFee -
          (priced.after.asInstanceOf[ErgoDexClArbPool].position.feesX - p.feesX)
        p.curveOut(least, xIn = true) should be >= out
        p.curveOut(least - 1L, xIn = true) should be < out
        priced.amountIn shouldBe least + p.lpFee(least) + ClPosition.protocolFee(least, 10000000L)
        leg(ctx, priced, ergRsv, carry(ctx, 3 * Erg))
      }
    }
  }

  it should "pay out no more than the side holds: the range bound" in {
    nodeContext.getClient.execute { ctx =>
      val box = position(ctx, id(301), id(302) -> 1000L, a = 10 * Erg, b = 500L, feePpm = 3000L, erg = 10 * Erg,
        fees = (0L, 5L))
      val pool = ErgoDexClArbPool.read(box, config(ctx)).get
      val all = pool.swap(1000 * Erg, xIn = true).get
      all.amountOut shouldBe 1000L
      leg(ctx, all, box, carry(ctx, 1001 * Erg))
      val over = ArbSwap(pool, all.amountIn, xIn = true, 1001L,
        pool.copy(position = all.after.asInstanceOf[ErgoDexClArbPool].position.copy(y = -1L)), all.protocolFee)
      refused(leg(ctx, over, box, carry(ctx, 1001 * Erg)))
    }
  }

  "A drained position" should "sell only toward the side it still holds" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexClArbPool.read(drained, config(ctx)).get
      pool.position.y shouldBe 0L
      pool.swap(Erg, xIn = true) shouldBe None
      pool.marginal(xIn = true) shouldBe 0.0
      val crux = pool.y.tokenId.get
      val sell = pool.swap(100000L, xIn = false).get
      sell.amountOut should be > 0L
      leg(ctx, sell, drained, carry(ctx, Erg, Seq(Token(ErgoId.create(crux), 100000L))))
    }
  }

  "The config" should "stop every CL leg when R8 delists the swap script" in {
    nodeContext.getClient.execute { ctx =>
      val r8 = configBox.additionalRegisters.values("R8")
      val delisted = configWith(ctx, "d1" * 32, r8 = Some(r8.replace(ErgoDexClContracts.SwapScriptHash, "00" * 32)))
      ClConfig.read(ctx, delisted, 360L) shouldBe None
      val shapes = new PoolShapes(NetworkType.MAINNET)
      def tracked(cfg: NodeBox) = TrackedPools.seeded(height - 1, "aa" * 32,
        Seq(shapes.read(ergRsv, 1923141).get, shapes.read(cfg, 1913306).get), cut = false)
      ArbDiscovery.index(ctx, tracked(configBox), None, configs.BatchingConfig.Default, Set.empty, _ => true)
        .parsed.keySet shouldBe Set(ergRsv.assets.head.tokenId)
      ArbDiscovery.index(ctx, tracked(delisted), None, configs.BatchingConfig.Default, Set.empty, _ => true)
        .parsed shouldBe empty
    }
  }

  it should "price a raised protocol fee from R5, which the script then demands" in {
    nodeContext.getClient.execute { ctx =>
      val raised = configWith(ctx, "d2" * 32, r5 = Some(Seq(10000L, 50L, 50L, 4000L, 1L, 10L)))
      val cfg = config(ctx, raised)
      cfg.protocol.rate(10000L) shouldBe 50000000L
      val now = ErgoDexClArbPool.read(ergRsv, config(ctx)).get
      val pool = ErgoDexClArbPool.read(ergRsv, cfg).get
      val buy = pool.swap(2 * Erg, xIn = true).get
      buy.protocolFee should be > 4 * now.swap(2 * Erg, xIn = true).get.protocolFee
      leg(ctx, buy, ergRsv, carry(ctx, 3 * Erg))
      withClue("a leg priced at the old fee is refused once the config raises it: ") {
        val old = now.swap(2 * Erg, xIn = true).get
        refused(leg(ctx, old.copy(pool = pool, after = old.after.asInstanceOf[ErgoDexClArbPool].copy(config = cfg)),
          ergRsv, carry(ctx, 3 * Erg)))
      }
    }
  }

  "Tracking and discovery" should "follow positions and the config on mainnet only, and cap positions" in {
    nodeContext.getClient.execute { ctx =>
      val mainnet = new PoolShapes(NetworkType.MAINNET)
      mainnet.read(ergRsv, 1923141).map(_.deployment) shouldBe Some(PoolDeployment.ErgoDexCl)
      mainnet.read(configBox, 1913306).map(_.deployment) shouldBe Some(PoolDeployment.ErgoDexClConfig)
      PoolDeployment.dex(PoolDeployment.ErgoDexCl) shouldBe true
      PoolDeployment.dex(PoolDeployment.ErgoDexClConfig) shouldBe false
      val testnet = new PoolShapes(NetworkType.TESTNET)
      testnet.read(ergRsv, 1923141) shouldBe None
      testnet.read(configBox, 1913306) shouldBe None

      val many = (0 to ArbDiscovery.MaxPositions).map(i => position(ctx, id(400 + i), SigRsv -> 1000000L,
        a = 0L, b = 0L, feePpm = 3000L, erg = (i + 1) * Erg))
      val tracked = TrackedPools.seeded(height - 1, "bb" * 32,
        (many :+ configBox).map(b => mainnet.read(b, height - 1).get), cut = false)
      val found = ArbDiscovery.index(ctx, tracked, None, configs.BatchingConfig.Default, Set.empty, _ => true)
      found.positionsCut shouldBe true
      found.parsed.size shouldBe ArbDiscovery.MaxPositions
      withClue("the position holding the least ERG is the one left out: ") {
        found.parsed.keySet should not contain id(400)
      }
      withClue("a successor reads back as the position's next state: ") {
        val e = found.parsed(id(401))
        val moved = e.pool.swap(Erg, xIn = true).get
        val next = LDNodeFixtures.nodeBox(ctx, e.pool.successor(e.box.toInputUTXO(ctx), moved.after), 0, "ab" * 32)
        e.read(ctx, next).map(_.asInstanceOf[ErgoDexClArbPool].position) shouldBe
          Some(moved.after.asInstanceOf[ErgoDexClArbPool].position)
      }
    }
  }

  "A cycle" should "run through an ErgoDEX pool and a CL position, signed, the protocol fee paid" in {
    nodeContext.getClient.execute { ctx =>
      val cl = ErgoDexClArbPool.read(ergRsv, config(ctx)).get
      // An ErgoDEX pool paying 20% more ERG per SigRSV than the position's marginal price
      val rsv = 100000000L
      val price = 1.0 / cl.marginal(xIn = true)
      val pool = nativePool(ctx, id(310), SigRsv, (price * rsv * 1.2).toLong, rsv)
      val stake = arbStakeBox(ctx, wallet, 20 * Erg + ArbLeg.MinCarryValue)
      val bundles = ArbCycles.build(ctx, wallet, ArbDiscovery.index(height - 1, Seq(entry(ctx, pool),
        ArbPoolEntry(cl, ergRsv))), ArbBasis.Empty, Some(stake), Seq.empty, 1000000L, height, 150, 20 * Erg, 1000000L,
        wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute, _ => true)
      bundles should not be empty
      val legs = bundles.head.members
      legs.size shouldBe 2
      legs.head.inputIds should contain(ergRsv.boxId)
      legs.head.kind shouldBe ArbKind.ErgoDexCycle
      legs(1).inputIds should contain(pool.boxId)
      val first = node.rest.NodeCodecs.transaction(new com.google.gson.JsonParser().parse(legs.head.json).getAsJsonObject)
      withClue("the CL leg pays its protocol fee to the config's key: ") {
        first.outputs.exists(_.ergoTree.equalsIgnoreCase(cl.config.feeTo.ergoTreeHex)) shouldBe true
        first.dataInputs.map(_.boxId) shouldBe Seq(configBox.boxId)
      }
    }
  }

  it should "take at most the CL legs the cost budget allows in one build" in {
    nodeContext.getClient.execute { ctx =>
      val cfg = config(ctx)
      val entries = (0 until ArbCycles.MaxClLegs + 4).flatMap { i =>
        val token = id(500 + i)
        val clBox = position(ctx, id(600 + i), token -> 1000000L, a = 0L, b = 0L, feePpm = 3000L, erg = 100 * Erg)
        Seq(ArbPoolEntry(ErgoDexClArbPool.read(clBox, cfg).get, clBox),
          entry(ctx, nativePool(ctx, id(700 + i), token, 120 * Erg, 1000000L)))
      }
      val stake = arbStakeBox(ctx, wallet, 1000 * Erg + ArbLeg.MinCarryValue)
      val bundles = ArbCycles.build(ctx, wallet, ArbDiscovery.index(height - 1, entries), ArbBasis.Empty, Some(stake),
        Seq.empty, 1000000L, height, 150, 5 * Erg, 1000000L, wallet.contract, Deadline.now + 1.minute,
        Deadline.now + 1.minute, _ => true)
      val clBoxes = entries.filter(_.pool.kind == ArbPoolKind.ErgoDexCl).map(_.box.boxId).toSet
      bundles.flatMap(_.members).count(_.inputIds.exists(clBoxes.contains)) shouldBe ArbCycles.MaxClLegs
      bundles.size shouldBe ArbCycles.MaxClLegs
    }
  }

  "Machina grids" should "not sell a gap through a CL position" in {
    import support.MachinaFixtures._
    nodeContext.getClient.execute { ctx =>
      val gridBox = tokenGridOrder.copy(value = 1000000L)
      val grid = transactions.batching.ergodex.MachinaOrder.parseTokenGrid(
        gridBox.copy(boxId = gridBox.toInputUTXO(ctx).id.toString)).get
      val t2t = entry(ctx, fakuCigPool).pool
      val ergPool = entry(ctx, fakuErgoDexPool).pool
      ArbMachina.gridCycle(grid, Seq(t2t, ergPool)) shouldBe defined
      // The same ERG and FAKU as the ErgoDEX pool, at the CL script
      val faku = fakuErgoDexPool.assets(2)
      val cl = ErgoDexClArbPool.read(position(ctx, id(320), faku.tokenId -> faku.amount, a = 0L, b = 0L,
        feePpm = 3000L, erg = fakuErgoDexPool.value), config(ctx)).get
      ArbMachina.gridCycle(grid, Seq(t2t, cl)) shouldBe None
    }
  }

  private def vlq(n: Int): Array[Byte] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var v = n
    while (v >= 0x80) { out += ((v & 0x7f) | 0x80).toByte; v >>>= 7 }
    out += v.toByte
    out.toArray
  }
}
