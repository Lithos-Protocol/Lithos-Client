package transactions.batching.ergodex

import node.MutationConversions._
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigma.ast.{ByteArrayConstant, Constant, ErgoTree, LongConstant, SType}
import support.MachinaFixtures
import support.MachinaFixtures.Fill

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

/**
 * Machina Finance orders filled through ErgoDEX native pools, against real mainnet fills: each order is parsed,
 * the filler's own size replayed to the unit, the best size found, and this client's fill signed against the
 * pinned pool script and the order's own script.
 */
class MachinaSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import MachinaFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def parsed(f: Fill): (MachinaOrder, ErgoDexPool) = {
    val pool = ErgoDexPool.native(f.pool).get
    (MachinaOrder.parse(f.order).get.routedThrough(pool.nft), pool)
  }

  private def withId(ctx: BlockchainContext, box: NodeBox): NodeBox = box.copy(boxId = box.toInputUTXO(ctx).id.toString)

  private def height(f: Fill): Int = math.max(f.pool.creationHeight, f.order.creationHeight) + 10

  private def sign(ctx: BlockchainContext, f: Fill, fill: ErgoDexFill, minerFee: Long) =
    ErgoDexExecution.assembled(ctx, wallet, f.pool.toInputUTXO(ctx), fill, height(f), minerFee, useTrueProp = false)

  "The pinned Machina templates" should "be the current contracts' templates, as deployed on mainnet" in {
    MachinaContracts.templates.map(t => t.kind -> t.templateHash).toMap shouldBe Map(
      MachinaKind.Limit -> "cd3b6b5003514f260004d8836904ac1e6c0d111d7d8bb29c1230ff8fe8e5d4a9",
      MachinaKind.Grid -> "38528b98b72b390f3c0354fc2d91663aa35a35e4341af3bb2ecfed60023b2195",
      MachinaKind.TokenGrid -> "1411a5a6aa0013f954a615e2814ec17edd045dfbb38517319efee0a123f90955")
    MachinaContracts.executableByTemplateHash.values.map(_.kind).toSet shouldBe
      Set(MachinaKind.Limit, MachinaKind.Grid)
  }

  "A live Machina order" should "parse as its kind, trading the token of the pool it was filled through" in {
    val kinds = Map(limitBuys -> MachinaKind.Limit, gridSells -> MachinaKind.Grid, gridBuys -> MachinaKind.Grid)
    fills.foreach { f =>
      withClue(f.name) {
        val (order, pool) = parsed(f)
        order.kind shouldBe kinds(f)
        order.tokenId shouldBe pool.yId
        if (f.boxSells) order.ask should not be empty else order.bid should not be empty
      }
    }
    MachinaOrder.parse(limitSellOrder).get.bid shouldBe None
    MachinaOrder.parse(limitBuys.order).get.ask shouldBe None
  }

  "An order of the older limit contract" should "not be parsed, since its bugs were patched in the current one" in {
    // It did not check the token a full fill pays a buying order in
    MachinaOrder.parse(oldLimit.order) shouldBe None
  }

  it should "reproduce the filler's own size to the unit: pool after, child and gap" in {
    fills.foreach { f =>
      withClue(f.name) {
        val (order, pool) = parsed(f)
        val trade = MachinaPricing.at(order, pool, f.tokensMoved, f.boxSells).get
        ErgoDexPool.native(f.poolAfter).get.copy(boxId = pool.boxId) shouldBe trade.poolAfter
        trade.childValue shouldBe f.child.value
        trade.childTokens shouldBe f.child.assets.headOption.map(_.amount).getOrElse(0L)
        trade.toOwner shouldBe (f.child.ergoTree != f.order.ergoTree)
        trade.gap shouldBe f.gap
      }
    }
  }

  it should "find a size earning at least the filler's, which no neighbouring size beats" in {
    fills.foreach { f =>
      withClue(f.name) {
        val (order, pool) = parsed(f)
        val best = MachinaPricing.best(order, pool).get
        best.gap should be >= f.gap
        Seq(best.tokens - 1, best.tokens + 1).flatMap(k => MachinaPricing.at(order, pool, k, best.boxSells))
          .foreach(neighbour => neighbour.gap should be <= best.gap)
      }
    }
  }

  it should "sign this client's best fill against the real pool and order, fee-less and paying a miner fee" in {
    nodeContext.getClient.execute { ctx =>
      fills.foreach { f =>
        withClue(f.name) {
          val (order, pool) = parsed(f)
          val fill = ErgoDexExecution.price(order, pool, 0L).get
          // A broadcast prices against its fee and refuses a fill that cannot then fund its takings
          Seq(0L, 1100000L).filter(fee => ErgoDexExecution.price(order, pool, 0L, minerFeeCeiling = fee).isDefined)
            .foreach { minerFee =>
              val outputs = sign(ctx, f, fill, minerFee).getOutputsToSpend.asScala
              outputs.head.getValue shouldBe fill.poolAfter.reservesX
              outputs(1).getValue shouldBe fill.rewardValue
              outputs(2).getValue shouldBe fill.revenue - minerFee
              // Every child names the box it came from: R4 on an owner payout, else the register after the prices
              val registers = outputs(1).getRegisters.asScala
              val toOwner = fill.machina.get.toOwner
              val binding = registers(if (toOwner) 0 else if (order.kind == MachinaKind.Limit) 3 else 2)
              Hex.toHexString(binding.getValue.asInstanceOf[sigma.Coll[Byte]].toArray) shouldBe f.order.boxId
            }
        }
      }
    }
  }

  // ─── the limit contract's sides ──────────────────────────────────────────

  private def fillOf(order: MachinaOrder, pool: ErgoDexPool, trade: MachinaTrade) =
    ErgoDexFill(order, pool, trade.tokens, trade.gap, trade.poolAfter, trade.childValue,
      if (trade.childTokens > 0) Seq(NodeAsset(order.tokenId, trade.childTokens)) else Seq.empty, Some(trade))

  private def signOn(ctx: BlockchainContext, poolBox: NodeBox, order: MachinaOrder, trade: MachinaTrade) = {
    val pool = ErgoDexPool.native(poolBox).get
    ErgoDexExecution.assembled(ctx, wallet, poolBox.toInputUTXO(ctx), fillOf(order, pool, trade),
      math.max(poolBox.creationHeight, order.box.creationHeight) + 10, 0L, useTrueProp = false)
  }

  /** `limitBuys`'s order holding `value`, at `price` if given. */
  private def buying(ctx: BlockchainContext, value: Long, price: Option[Long] = None): MachinaOrder = {
    val registers = limitBuys.order.additionalRegisters.values
    val box = limitBuys.order.copy(value = value, additionalRegisters = node.model.NodeRegisters(
      price.fold(registers)(p => registers.updated("R5", ErgoValue.of(p).toHex))))
    MachinaOrder.parse(withId(ctx, box)).get.routedThrough(parsed(limitBuys)._2.nft)
  }

  /** `tokens` units sold to `order` past its floor: priced with ERG to spare, then the spare taken back. */
  private def forced(order: MachinaOrder, pool: ErgoDexPool, tokens: Long): MachinaTrade = {
    val spare = 100000000L
    val roomy = order.copy(box = order.box.copy(value = order.box.value + spare))
    val trade = MachinaPricing.at(roomy, pool, tokens, boxSells = false).get
    trade.copy(childValue = trade.childValue - spare)
  }

  "A limit order buying tokens" should "keep more than 1,000,000 nanoERG, so it is recreated rather than paid out" in {
    nodeContext.getClient.execute { ctx =>
      val (live, pool) = parsed(limitBuys)
      val best = MachinaPricing.best(live, pool).get
      best.toOwner shouldBe false
      best.childValue should be > MachinaContracts.SafeMinBoxValue
      // Left exactly 1,000,000 after 2,828 units, the box would have to pay out; one unit fewer keeps it
      val atFloor = buying(ctx, MachinaContracts.SafeMinBoxValue + 2828 * live.bid.get)
      MachinaPricing.at(atFloor, pool, 2828, boxSells = false) shouldBe None
      val kept = MachinaPricing.at(atFloor, pool, 2827, boxSells = false).get
      forced(atFloor, pool, 2827) shouldBe kept
      signOn(ctx, limitBuys.pool, atFloor, kept)
      Try(signOn(ctx, limitBuys.pool, atFloor, forced(atFloor, pool, 2828))).isFailure shouldBe true
    }
  }

  it should "keep at least its own price, enough for one more unit" in {
    nodeContext.getClient.execute { ctx =>
      val pool = parsed(limitBuys)._2
      // After 49 units at 2,000,000 the box keeps 1,500,000: above the safe minimum, below its price
      val price = 2000000L
      val order = buying(ctx, 49 * price + 1500000L, Some(price))
      MachinaPricing.at(order, pool, 49, boxSells = false) shouldBe None
      MachinaPricing.at(order, pool, 48, boxSells = false).get.childValue shouldBe 1500000L + price
      MachinaPricing.best(order, pool).get.tokens shouldBe 48
      val kept = MachinaPricing.at(order, pool, 48, boxSells = false).get
      forced(order, pool, 48) shouldBe kept
      signOn(ctx, limitBuys.pool, order, kept)
      Try(signOn(ctx, limitBuys.pool, order, forced(order, pool, 49))).isFailure shouldBe true
    }
  }

  /** `limitBuys`'s pool holding `erg` and `tokens` of its token, priced above `limitSellOrder`'s ask. */
  private def poolHolding(ctx: BlockchainContext, erg: Long, tokens: Long): NodeBox = {
    val box = limitBuys.pool
    withId(ctx, box.copy(value = erg, assets = box.assets.updated(2, box.assets(2).copy(amount = tokens))))
  }

  "A limit order selling tokens" should "be recreated with what it has left, bound in R7, when part is bought" in {
    nodeContext.getClient.execute { ctx =>
      val poolBox = poolHolding(ctx, 6700000000L, 136815L)
      val pool = ErgoDexPool.native(poolBox).get
      val order = MachinaOrder.parse(limitSellOrder).get.routedThrough(pool.nft)
      val trade = MachinaPricing.best(order, pool).get
      trade.boxSells shouldBe true
      trade.tokens should (be > 0L and be < order.tokens)
      trade.toOwner shouldBe false
      trade.childValue shouldBe limitSellOrder.value + trade.tokens * order.ask.get
      val child = signOn(ctx, poolBox, order, trade).getOutputsToSpend.get(1)
      child.getErgoTree.bytesHex shouldBe limitSellOrder.ergoTree
      child.getTokens.get(0).getValue shouldBe order.tokens - trade.tokens
      Hex.toHexString(child.getRegisters.get(3).getValue.asInstanceOf[sigma.Coll[Byte]].toArray) shouldBe
        limitSellOrder.boxId
    }
  }

  it should "pay its owner, bound in R4, when all of it is bought" in {
    nodeContext.getClient.execute { ctx =>
      val poolBox = poolHolding(ctx, 6600000000000L, 136815369L)
      val pool = ErgoDexPool.native(poolBox).get
      val order = MachinaOrder.parse(limitSellOrder).get.routedThrough(pool.nft)
      val trade = MachinaPricing.best(order, pool).get
      trade.tokens shouldBe order.tokens
      trade.toOwner shouldBe true
      val payout = signOn(ctx, poolBox, order, trade).getOutputsToSpend.get(1)
      payout.getErgoTree.bytes.toSeq shouldBe order.ownerPropBytes.toSeq
      payout.getValue shouldBe limitSellOrder.value + order.tokens * order.ask.get
      payout.getTokens shouldBe empty
      Hex.toHexString(payout.getRegisters.get(0).getValue.asInstanceOf[sigma.Coll[Byte]].toArray) shouldBe
        limitSellOrder.boxId
    }
  }

  // ─── what is not filled ──────────────────────────────────────────────────

  "A Machina order" should "not be filled through a pool of another token" in {
    val (order, _) = parsed(gridSells)
    val other = ErgoDexPool.native(support.ErgoDexFixtures.poolBox).get
    other.yId should not be order.tokenId
    ErgoDexExecution.price(order.routedThrough(other.nft), other, 0L) shouldBe None
  }

  it should "not be filled when neither of its prices leaves a gap against the pool" in {
    nodeContext.getClient.execute { ctx =>
      val (live, pool) = parsed(gridSells)
      // An ask far above the pool's price and a bid far below it
      val prices = ErgoValue.of(Colls.fromArray(Array(Long.MaxValue / 4, 1L)), scalaLongType).toHex
      val fair = MachinaOrder.parse(withId(ctx, gridSells.order.copy(additionalRegisters =
        node.model.NodeRegisters(gridSells.order.additionalRegisters.values.updated("R5", prices))))).get
      fair.ask shouldBe Some(Long.MaxValue / 4)
      MachinaPricing.best(fair.routedThrough(pool.nft), pool) shouldBe None
      MachinaPricing.best(live, pool) should not be empty
    }
  }

  it should "not be parsed holding a token other than its own" in {
    nodeContext.getClient.execute { ctx =>
      val foreign = withId(ctx, gridSells.order.copy(assets = Seq(NodeAsset("5e" * 32, 10L))))
      MachinaOrder.parse(foreign) shouldBe None
    }
  }

  // ─── chained with ErgoDEX orders ─────────────────────────────────────────

  /** A native v1 redemption of a thousandth of the circulating LP of `pool`, built from the published tree. */
  private def redeemOn(ctx: BlockchainContext, poolBox: NodeBox): ErgoDexOrder = {
    val source = Source.fromResource("ergodex/redeem-v1.hex")
    val tree = try ErgoTree.fromHex(source.mkString.trim) finally source.close()
    val pool = ErgoDexPool.native(poolBox).get
    val owner = ErgoTree.fromHex(support.ErgoDexFixtures.orderBox.ergoTree).constants.head
    val terms: Map[Int, Constant[SType]] = Map(0 -> owner,
      11 -> ByteArrayConstant(Hex.decode(pool.nft)).asInstanceOf[Constant[SType]],
      12 -> LongConstant(6000000L).asInstanceOf[Constant[SType]],
      16 -> LongConstant(2000000L).asInstanceOf[Constant[SType]])
    val script = ErgoTree(tree.header, tree.constants.zipWithIndex.map { case (c, i) => terms.getOrElse(i, c) },
      tree.root.right.get)
    val circulating = Long.MaxValue - pool.lpSupply
    val box = support.ErgoDexFixtures.orderBox.copy(value = 8000000L, ergoTree = script.bytesHex,
      assets = Seq(NodeAsset(pool.lpId, circulating / 1000)), creationHeight = poolBox.creationHeight)
    ErgoDexOrder.parse(withId(ctx, box)).get
  }

  "A Machina fill and an ErgoDEX order on one pool" should "chain in one run, each against the pool the last left" in {
    nodeContext.getClient.execute { ctx =>
      val (machina, pool) = parsed(gridSells)
      val redeem = redeemOn(ctx, gridSells.pool)
      val run = ErgoDexExecution.run(ctx, wallet, gridSells.pool.toInputUTXO(ctx), pool, Seq(machina, redeem), 3, 0L,
        height(gridSells), 0L, useTrueProp = false, Deadline.now + 1.minute)
      val chain = run.chain.get
      chain.transactions should have size 2
      chain.fills.map(_.order.boxId).toSet shouldBe Set(machina.boxId, redeem.boxId)
      chain.fills(1).pool shouldBe chain.fills.head.poolAfter
      chain.transactions(1).getSignedInputs.get(0).getId shouldBe chain.transactions.head.getOutputsToSpend.get(0).getId
      chain.takings.value shouldBe chain.fills.map(_.revenue).sum
    }
  }

  "An unconfirmed spend of a Machina order" should "be a competitor's fill when it recreates any pool, else its owner closing it" in {
    val (order, _) = parsed(gridSells)
    def spentBy(outputs: Seq[NodeBox]) = Map(order.boxId -> Vector(state.synchronization.CompleteMempool.MempoolTx(
      "spender", node.model.NodeTransaction("spender", Seq.empty, Seq.empty, outputs), 100)))
    // The live fill went through this pool; one through another pool of the token is a fill all the same
    val otherPool = gridSells.poolAfter.copy(assets = gridSells.poolAfter.assets.updated(0, NodeAsset("ab" * 32, 1L)))
    ErgoDexBatching.withdrawn(order, spentBy(Seq(gridSells.poolAfter, gridSells.child))) shouldBe false
    ErgoDexBatching.withdrawn(order, spentBy(Seq(otherPool, gridSells.child))) shouldBe false
    ErgoDexBatching.withdrawn(order, spentBy(Seq(gridSells.child.copy(ergoTree = support.ErgoDexFixtures.orderBox.ergoTree)))) shouldBe true
  }

  "Machina's own templates" should "be what the live orders' trees carry" in {
    fills.foreach { f =>
      val hash = Hex.toHexString(Blake2b256(ErgoTree.fromHex(f.order.ergoTree).template))
      withClue(f.name)(MachinaContracts.executableByTemplateHash.contains(hash) shouldBe true)
    }
    Try(MachinaOrder.parse(support.ErgoDexFixtures.orderBox)).toOption.flatten shouldBe None
  }
}
