package transactions.batching.arb

import node.MutationConversions._
import node.model.{NodeAsset, NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.MachinaFixtures
import transactions.batching.ergodex.{ErgoDexPool, MachinaContracts, MachinaFill, MachinaKind, MachinaOrder, MachinaPricing}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage}
import transactions.candidate.{CandidateBundle, CapitalOrigin}
import work.lithos.mutations.UTXO

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Machina orders the arb stage fills through LithosDex ERG pools, against real mainnet orders and the real
 * LithosDex ERG/FAKU pool: the pricing replayed to the unit on real ErgoDEX fills, the routing between the two
 * DEXes, every fill signed against the pool's and the order's own contracts, and a fill built on a LithosDex run's
 * last successor with a cycle through the same pool priced after it. Then token/token grids: the one open on
 * mainnet and two real fills replayed to the child, and the fill and the sale of its gap signed against the real
 * grid, the ErgoDEX FAKU/`ebb40eca…` token pool and FAKU's ERG pools.
 */
class ArbMachinaSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._
  import MachinaFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val height = 1913000

  private def withId(ctx: BlockchainContext, box: NodeBox): NodeBox = box.copy(boxId = box.toInputUTXO(ctx).id.toString)

  private def decoded(tx: CandidateTx): NodeTransaction =
    NodeCodecs.transaction(new com.google.gson.JsonParser().parse(tx.json).getAsJsonObject)

  private val faku: String = lithosDexFakuPool.assets(1).tokenId
  private val ldNft: String = lithosDexFakuPool.assets.head.tokenId

  private def lithosDex(ctx: BlockchainContext, price: Double): NodeBox = lithosDexFakuPoolAt(ctx, price)

  /** The index the stage would hold over `boxes`, with every one of them parsed. */
  private def indexOf(ctx: BlockchainContext, boxes: NodeBox*): ArbIndex = {
    val entries = boxes.map(entry(ctx, _))
    ArbDiscovery.index(height - 1, entries).copy(parsed = entries.map(e => e.nft -> e).toMap)
  }

  private def build(ctx: BlockchainContext, index: ArbIndex, machina: Seq[MachinaOrder],
                    basis: ArbBasis = ArbBasis.Empty, stake: Option[NodeBox] = None,
                    minFill: Long = 1000000L): Vector[CandidateBundle] =
    ArbCycles.build(ctx, wallet, index, basis, stake, machina, minFill, height, 8,
      stake.fold(0L)(_.value - ArbLeg.MinCarryValue), 1000000L, wallet.contract, Deadline.now + 1.minute,
      Deadline.now + 1.minute, _ => true)

  private def parsed(f: Fill): MachinaOrder = MachinaOrder.parse(f.order).get

  "A Machina fill priced through any ERG pool" should "reproduce the ErgoDEX batcher's fills to the unit, and its best size" in {
    fills.foreach { f =>
      withClue(f.name) {
        val pool = ErgoDexPool.native(f.pool).get
        val order = parsed(f).routedThrough(pool.nft)
        val fill = ArbMachina.at(order, ErgoDexArbPool(pool), f.tokensMoved, f.boxSells).get
        fill.gap shouldBe f.gap
        fill.childValue shouldBe f.child.value
        fill.childTokens shouldBe f.child.assets.headOption.map(_.amount).getOrElse(0L)
        fill.toOwner shouldBe (f.child.ergoTree != f.order.ergoTree)
        fill.swap.after shouldBe ErgoDexArbPool(ErgoDexPool.native(f.poolAfter).get.copy(boxId = pool.boxId))
        ArbMachina.best(order, ErgoDexArbPool(pool)).map(_.gap) shouldBe MachinaPricing.best(order, pool).map(_.gap)
      }
    }
  }

  "A Machina order" should "be routed to LithosDex only when a LithosDex pool leaves it more than ErgoDEX does" in {
    nodeContext.getClient.execute { ctx =>
      val f = gridSells
      val ergoDex = ErgoDexPool.native(f.pool).get
      val order = parsed(f)
      val throughErgoDex = MachinaPricing.best(order.routedThrough(ergoDex.nft), ergoDex).get.gap
      val ask = order.ask.get.toDouble
      val dearer = entry(ctx, lithosDex(ctx, ask * 1.5)).pool
      val cheaper = entry(ctx, lithosDex(ctx, ask * 1.02)).pool
      val dearGap = ArbMachina.best(order.routedThrough(ldNft), dearer).get.gap
      dearGap should be > throughErgoDex
      ArbMachina.best(order.routedThrough(ldNft), cheaper).fold(0L)(_.gap) should be < throughErgoDex

      ArbMachina.preferLithosDex(Seq(order), Map(order.boxId -> throughErgoDex), Seq(dearer), 1000000L) shouldBe
        Vector((order.boxId, ldNft, dearGap))
      ArbMachina.preferLithosDex(Seq(order), Map(order.boxId -> throughErgoDex), Seq(cheaper), 1000000L) shouldBe empty
      withClue("a gap no more than ErgoDEX's stays with ErgoDEX: ") {
        ArbMachina.preferLithosDex(Seq(order), Map(order.boxId -> dearGap), Seq(dearer), 1000000L) shouldBe empty
      }
      withClue("no ErgoDEX pool fills it, so LithosDex does: ") {
        ArbMachina.preferLithosDex(Seq(order), Map.empty, Seq(cheaper), 1000000L).map(_._2) shouldBe Vector(ldNft)
      }
      withClue("a gap under the floor is not taken: ") {
        ArbMachina.preferLithosDex(Seq(order), Map.empty, Seq(dearer), dearGap + 1) shouldBe empty
      }
    }
  }

  "A Machina fill through the LithosDex ERG/FAKU pool" should "be signed against that pool's and the order's own contracts, its gap to the pot" in {
    nodeContext.getClient.execute { ctx =>
      // Each order on the side its own mainnet fill took, the pool priced so that side leaves a gap
      val orders = fills.map(f => (f.name, parsed(f), f.boxSells)) :+
        (("limit-sell-order", MachinaOrder.parse(limitSellOrder).get, true))
      orders.foreach { case (name, live, sells) =>
        withClue(name) {
          val box = lithosDex(ctx, if (sells) live.ask.get * 1.5 else live.bid.get / 1.5)
          val order = live.routedThrough(ldNft)
          val fill = ArbMachina.best(order, entry(ctx, box).pool).get
          fill.boxSells shouldBe sells
          val bundles = build(ctx, indexOf(ctx, box), Seq(order))
          bundles.size shouldBe 1
          val bundle = bundles.head
          bundle.members.map(_.kind) shouldBe Vector(ArbLeg.Kind)
          bundle.packageParents shouldBe empty
          val tx = decoded(bundle.members.head)
          tx.inputs.map(_.boxId) shouldBe Seq(box.boxId, order.boxId)
          tx.outputs.size shouldBe 3
          tx.outputs.head.ergoTree shouldBe box.ergoTree
          tx.outputs.head.assets.head shouldBe box.assets.head
          tx.outputs.head.value - box.value shouldBe fill.swap.poolErgDelta
          val child = tx.outputs(1)
          child.ergoTree shouldBe (if (fill.toOwner) Hex.toHexString(order.ownerPropBytes) else order.box.ergoTree)
          child.value shouldBe fill.childValue
          child.assets.map(_.amount).sum shouldBe fill.childTokens
          val takings = tx.outputs(2)
          takings.ergoTree shouldBe wallet.contract.ergoTreeHex
          takings.value shouldBe fill.gap
          bundle.capital.map(c => (c.origin, c.outputId, c.value)) shouldBe
            Seq((CapitalOrigin.ExecutorReward, takings.boxId, fill.gap))
        }
      }
    }
  }

  /** A Machina limit order for LIT selling `lit` units at `ask`, at the real limit contract for LIT. */
  private def litOrder(ctx: BlockchainContext, lit: Long, ask: Long): MachinaOrder = {
    val limit = MachinaContracts.templates.find(_.kind == MachinaKind.Limit).get
    val registers = limitSellOrder.additionalRegisters.values.updated("R5", ErgoValue.of(ask).toHex)
    MachinaOrder.parse(withId(ctx, limitSellOrder.copy(ergoTree = limit.treeHex(Lit),
      assets = Seq(NodeAsset(Lit, lit)), additionalRegisters = node.model.NodeRegisters(registers)))).get
  }

  "A Machina order for LIT" should "be routed through ERG:LIT, its only pool, and filled there" in {
    nodeContext.getClient.execute { ctx =>
      // 10 nanoERG a unit in the pool, against an ask of 8
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      val order = litOrder(ctx, 1000000000L, 8L)
      val routes = ArbMachina.preferLithosDex(Seq(order), Map.empty,
        Seq(entry(ctx, canonical).pool, entry(ctx, lithosDex(ctx, 30000.0)).pool), 1000000L)
      routes.map(r => r._1 -> r._2) shouldBe Vector(order.boxId -> CanonicalNft)
      val bundles = build(ctx, indexOf(ctx, canonical), Seq(order.routedThrough(CanonicalNft)))
      bundles.size shouldBe 1
      val tx = decoded(bundles.head.members.head)
      tx.inputs.map(_.boxId) shouldBe Seq(canonical.boxId, order.boxId)
      tx.outputs(2).value shouldBe routes.head._3
    }
  }

  /** A swap of `erg` nanoERG into `pool` for its token, signed as a LithosDex batcher run would leave it. */
  private def lithosDexRun(ctx: BlockchainContext, pool: NodeBox, erg: Long): CandidateBundle = {
    val swap = entry(ctx, pool).pool.swap(erg, xIn = true).get
    val funding = support.LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, erg + 5 * Erg), 0, id(951)).toInputUTXO(ctx)
    val tx = ArbLeg.build(ctx, wallet, swap, pool.toInputUTXO(ctx), Seq(funding), wallet.contract, height)
    CandidateBundle(Vector(CandidateTx.signed(tx, "lithosdex-batch")))
  }

  "A fill after a LithosDex run" should "build on the run's last successor, name it, and leave a cycle through the pool to price after it" in {
    nodeContext.getClient.execute { ctx =>
      val order = parsed(gridSells).routedThrough(ldNft)
      val ask = order.ask.get.toDouble
      val ld = lithosDex(ctx, ask * 1.5)
      val run = lithosDexRun(ctx, ld, Erg)
      val successor = decoded(run.members.head).outputs.head
      // FAKU is cheaper on this ErgoDEX pool than on the LithosDex one, so a pair through both pays
      val ergoDex = nativePool(ctx, id(31), faku, 1000 * Erg, (1000 * Erg / (ask * 1.1)).toLong)
      val index = indexOf(ctx, ld, ergoDex)
      val bundles = build(ctx, index, Seq(order), ArbBasis.of(Seq(run)),
        stake = Some(arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)))
      bundles.size should be >= 2

      val fill = bundles.head
      val fillTx = decoded(fill.members.head)
      fillTx.inputs.map(_.boxId) shouldBe Seq(successor.boxId, order.boxId)
      fill.packageParents shouldBe Seq(run.members.head.id)
      bundles.flatMap(_.members).flatMap(_.inputIds) should not contain ld.boxId

      withClue("the cycle through the LithosDex pool spends what the fill left, and names the fill: ") {
        val cycle = bundles(1)
        val legs = cycle.members.map(decoded)
        legs.map(_.inputs.head.boxId) should contain(fillTx.outputs.head.boxId)
        cycle.packageParents should contain(fill.members.head.id)
      }
      CandidateBundle.admit(run +: bundles, 32)._1.size shouldBe 1 + bundles.size
      CandidateBundle.admit(bundles, 32)._1 shouldBe empty
    }
  }

  "A Machina order" should "not be filled when a batcher's bundle spends its box, or when no pool it names is held" in {
    nodeContext.getClient.execute { ctx =>
      val order = parsed(gridSells).routedThrough(ldNft)
      val ld = lithosDex(ctx, order.ask.get * 1.5)
      build(ctx, indexOf(ctx, ld), Seq(order)) should have size 1
      val spent = CandidateBundle(Vector(CandidateTx("ee" * 32, "{}", "ergodex-batch", Set(order.boxId))))
      build(ctx, indexOf(ctx, ld), Seq(order), ArbBasis.of(Seq(spent))) shouldBe empty
      build(ctx, indexOf(ctx, ld), Seq(order.routedThrough(id(77)))) shouldBe empty
    }
  }

  it should "not be filled for less than the least a fill must leave" in {
    nodeContext.getClient.execute { ctx =>
      val order = parsed(gridSells).routedThrough(ldNft)
      val ld = lithosDex(ctx, order.ask.get * 1.5)
      val gap = ArbMachina.best(order, entry(ctx, ld).pool).get.gap
      build(ctx, indexOf(ctx, ld), Seq(order), minFill = gap) should have size 1
      build(ctx, indexOf(ctx, ld), Seq(order), minFill = gap + 1) shouldBe empty
    }
  }

  it should "be refused to the batcher when its fill cannot be signed, and not when its pool box cannot be read" in {
    nodeContext.getClient.execute { ctx =>
      val order = parsed(gridSells).routedThrough(ldNft)
      val ld = lithosDex(ctx, order.ask.get * 1.5)
      def built(index: ArbIndex, orders: Seq[MachinaOrder]): (Vector[CandidateBundle], Vector[String]) = {
        var refused = Vector.empty[String]
        ArbCycles.build(ctx, wallet, index, ArbBasis.Empty, None, orders, 1000000L, height, 8, 0L, 1000000L,
          wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute, _ => true, id => refused :+= id) -> refused
      }
      // Its box claims an id its contents do not give, so the fill cannot spend it
      val forged = order.copy(box = order.box.copy(boxId = "ab" * 32))
      built(indexOf(ctx, ld), Seq(forged)) shouldBe (Vector.empty -> Vector(forged.boxId))
      val unreadable = ArbPoolEntry(entry(ctx, ld).pool, ld.copy(boxId = "cd" * 32))
      val index = ArbDiscovery.index(height - 1, Seq.empty).copy(parsed = Map(ldNft -> unreadable))
      built(index, Seq(order)) shouldBe (Vector.empty -> Vector.empty)
      built(indexOf(ctx, ld), Seq(order))._1 should have size 1
    }
  }

  "Two Machina orders on one LithosDex pool" should "be filled in turn, the second on the box the first left" in {
    nodeContext.getClient.execute { ctx =>
      val sells = parsed(gridSells).routedThrough(ldNft)
      val other = MachinaOrder.parse(limitSellOrder).get.routedThrough(ldNft)
      val ld = lithosDex(ctx, math.max(sells.ask.get, other.ask.get) * 1.5)
      val bundles = build(ctx, indexOf(ctx, ld), Seq(sells, other))
      bundles.size shouldBe 2
      val (first, second) = (decoded(bundles.head.members.head), decoded(bundles(1).members.head))
      first.inputs.head.boxId shouldBe ld.boxId
      second.inputs.head.boxId shouldBe first.outputs.head.boxId
      bundles(1).packageParents shouldBe Seq(bundles.head.members.head.id)
      bundles.flatMap(_.members.head.inputIds).toSet should contain allOf(sells.boxId, other.boxId)
    }
  }

  "The fill transaction" should "carry the order's context variables as its contract reads them" in {
    nodeContext.getClient.execute { ctx =>
      val order = parsed(gridSells).routedThrough(ldNft)
      val ld = lithosDex(ctx, order.ask.get * 1.5)
      val fill = ArbMachina.best(order, entry(ctx, ld).pool).get
      val tx = ArbMachina.build(ctx, wallet, fill, ld.toInputUTXO(ctx), wallet.contract, height)
      val proof = tx.getSignedInputs.asScala(1)
      proof.getContextVars.asScala.map { case (k, v) => k.toInt -> v.getValue }.toMap shouldBe
        Map(0 -> fill.boxSells, 1 -> 1)
    }
  }

  // ─── token/token grids ───────────────────────────────────────────────────

  private def grid(box: NodeBox): MachinaOrder = MachinaOrder.parseTokenGrid(box).get

  "A token/token grid order" should "parse with its base token first, its own token, its ask and its bid" in {
    val order = grid(tokenGridOrder)
    order.kind shouldBe MachinaKind.TokenGrid
    order.base shouldBe Some(faku)
    order.tokenId shouldBe tokenGridOrder.assets(1).tokenId
    (order.ask, order.bid, order.tokens, order.baseHeld) shouldBe ((Some(50L), Some(10L), 200L, 500L))
    withClue("it is never taken for an ERG/token order, nor an ERG/token order for it: ") {
      MachinaOrder.parse(tokenGridOrder) shouldBe None
      MachinaOrder.parseTokenGrid(gridSells.order) shouldBe None
    }
    Seq(tokenGridBuys, tokenGridSells).foreach(f => grid(f.order).base shouldBe Some(faku))
  }

  it should "not parse holding a third token, its own token first, or a constant its contract does not carry" in {
    nodeContext.getClient.execute { ctx =>
      val third = withId(ctx, tokenGridOrder.copy(assets = tokenGridOrder.assets :+ NodeAsset("5e" * 32, 1L)))
      MachinaOrder.parseTokenGrid(third) shouldBe None
      val swapped = withId(ctx, tokenGridOrder.copy(assets = tokenGridOrder.assets.reverse))
      MachinaOrder.parseTokenGrid(swapped) shouldBe None
      // The last constant, the bid's index in R5, made 2: same template, another contract
      val tree = tokenGridOrder.ergoTree.replace("0500040005000402d804", "0500040005000404d804")
      tree should not equal tokenGridOrder.ergoTree
      Hex.toHexString(scorex.crypto.hash.Blake2b256(sigma.ast.ErgoTree.fromHex(tree).template)) shouldBe
        MachinaContracts.templates.find(_.kind == MachinaKind.TokenGrid).get.templateHash
      MachinaOrder.parseTokenGrid(withId(ctx, tokenGridOrder.copy(ergoTree = tree))) shouldBe None
      MachinaOrder.parseTokenGrid(withId(ctx, tokenGridOrder)) should not be empty
    }
  }

  "A mainnet token/token grid fill" should "leave the child this client builds for the same fill, byte for byte" in {
    nodeContext.getClient.execute { ctx =>
      Seq(tokenGridBuys -> false, tokenGridSells -> true).foreach { case (f, sells) =>
        withClue(f.name) {
          val order = grid(f.order)
          val moved = math.abs(f.child.assets(1).amount - f.order.assets(1).amount)
          // Any token/token pool of the pair: the child depends only on the order, its side and the size
          val pool = ErgoDexArbPool(ErgoDexPool.token(tokenPool(ctx, id(41), faku, order.tokenId,
            100000000L, 100000L)).get)
          val fill = ArbMachina.at(order.routedThrough(pool.nft), pool, moved, sells).get
          val input = f.order.toInputUTXO(ctx)
          val child = MachinaFill.child(input, order, fill.childValue, fill.childTokens, fill.toOwner, fill.childBase)
          child.contract.ergoTreeHex shouldBe f.child.ergoTree
          child.value shouldBe f.child.value
          child.tokens.map(t => t.id.toString -> t.amount) shouldBe f.child.assets.map(a => a.tokenId -> a.amount)
          child.registers.map(_.toHex) shouldBe Seq("R4", "R5", "R6").map(f.child.additionalRegisters.values)
          // The filler's context variables: the side, and the child's index, there 1
          MachinaFill.input(input, order, sells, 1).ctxVars.map(v => v.getId.toString -> v.getValue.toHex).toMap shouldBe
            contextVars(f, 1)
        }
      }
    }
  }

  /** The live grid order holding `value`, enough for its child to carry the id register it lacks. */
  private def funded(ctx: BlockchainContext, value: Long = 1000000L): MachinaOrder =
    grid(withId(ctx, tokenGridOrder.copy(value = value)))

  /** Grid cycles built for `orders` through `pools` on a stake at the arb address, and the stake. */
  private def gridBuild(ctx: BlockchainContext, pools: Seq[NodeBox], orders: Seq[MachinaOrder],
                        minProfit: Long = 1000000L, slots: Int = 8,
                        refuse: String => Unit = _ => ()): (Vector[CandidateBundle], NodeBox) = {
    val stake = arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
    ArbCycles.build(ctx, wallet, indexOf(ctx, pools: _*), ArbBasis.Empty, Some(stake), orders, 1000000L, height, slots,
      stake.value - ArbLeg.MinCarryValue, minProfit, wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute,
      _ => true, refuse) -> stake
  }

  "A token/token grid order" should "be filled through its pair's token pool, its gap sold through its base token's deepest ERG pool, on the stake" in {
    nodeContext.getClient.execute { ctx =>
      val order = funded(ctx)
      val (bundles, stake) = gridBuild(ctx, Seq(fakuCigPool, fakuErgoDexPool, lithosDexFakuPool), Seq(order))
      bundles.size shouldBe 1
      val bundle = bundles.head
      bundle.members.map(_.kind) shouldBe Vector(ArbLeg.Kind, ArbLeg.Kind)
      val Seq(fill, sale) = bundle.members.map(decoded)
      val arbTree = stakeContract(wallet).ergoTreeHex

      // The fill: the token pool, the stake carried through, the order; its child at output 2
      fill.inputs.map(_.boxId) shouldBe Seq(fakuCigPool.boxId, stake.boxId, order.boxId)
      fill.outputs.size shouldBe 3
      fill.outputs.head.value shouldBe fakuCigPool.value
      val carry = fill.outputs(1)
      carry.ergoTree shouldBe arbTree
      carry.value shouldBe stake.value
      val child = fill.outputs(2)
      child.ergoTree shouldBe order.box.ergoTree
      child.value shouldBe order.box.value
      // It bids 10 FAKU units for each unit of its token: it buys all its 500 FAKU allow, keeping one
      val bought = child.assets(1).amount - 200L
      bought shouldBe 49L
      child.assets.map(_.amount) shouldBe Seq(500L - 10L * bought, 200L + bought)
      val paid = fill.outputs.head.assets(2).amount - fakuCigPool.assets(2).amount
      carry.assets.map(a => a.tokenId -> a.amount) shouldBe Seq(faku -> (10L * bought - paid))

      // The sale: FAKU's deepest ERG pool, the ErgoDEX one, not the LithosDex one
      sale.inputs.map(_.boxId) shouldBe Seq(fakuErgoDexPool.boxId, carry.boxId)
      sale.outputs(1).ergoTree shouldBe arbTree
      sale.outputs(1).value shouldBe stake.value
      sale.outputs(1).assets shouldBe empty
      val profit = sale.outputs(2)
      profit.value shouldBe fakuErgoDexPool.value - sale.outputs.head.value
      profit.value should be >= 1000000L
      bundle.capital.map(c => (c.origin, c.outputId, c.value)) shouldBe
        Seq((CapitalOrigin.Arbitrage, profit.boxId, profit.value))
      bundle.packageParents shouldBe empty
    }
  }

  it should "be sold through LithosDex when that is its base token's deepest ERG pool" in {
    nodeContext.getClient.execute { ctx =>
      val shallow = nativePool(ctx, id(42), faku, 10 * Erg, 300000L)
      val (bundles, _) = gridBuild(ctx, Seq(fakuCigPool, shallow, lithosDexFakuPool), Seq(funded(ctx)))
      decoded(bundles.head.members(1)).inputs.head.boxId shouldBe lithosDexFakuPool.boxId
    }
  }

  it should "not be filled when the ERG its sale returns is under the least profit" in {
    nodeContext.getClient.execute { ctx =>
      val order = funded(ctx)
      val pools = Seq(fakuCigPool, fakuErgoDexPool)
      val profit = decoded(gridBuild(ctx, pools, Seq(order))._1.head.members(1)).outputs(2).value
      gridBuild(ctx, pools, Seq(order), minProfit = profit)._1 should have size 1
      gridBuild(ctx, pools, Seq(order), minProfit = profit + 1)._1 shouldBe empty
    }
  }

  it should "need the stake, two slots, a token pool of its pair and an ERG pool of its base" in {
    nodeContext.getClient.execute { ctx =>
      val order = funded(ctx)
      build(ctx, indexOf(ctx, fakuCigPool, fakuErgoDexPool), Seq(order)) shouldBe empty
      gridBuild(ctx, Seq(fakuCigPool, fakuErgoDexPool), Seq(order), slots = 1)._1 shouldBe empty
      gridBuild(ctx, Seq(fakuErgoDexPool), Seq(order))._1 shouldBe empty
      gridBuild(ctx, Seq(fakuCigPool), Seq(order))._1 shouldBe empty
      gridBuild(ctx, Seq(fakuCigPool, fakuErgoDexPool), Seq(order))._1 should have size 1
    }
  }

  it should "be filled through a LithosDex token/token pool and sold through ERG:LIT, at the real grid contract for RSN" in {
    nodeContext.getClient.execute { ctx =>
      // A grid holding LIT, its base, bidding half again what LIT/RSN asks for RSN, and selling none
      val pool = entry(ctx, litRsnPool).pool.asInstanceOf[LithosDexT2TArbPool].state
      val bid = (BigInt(pool.reservesX) * 3 / (BigInt(pool.reservesY) * 2) + 1).toLong
      val prices = ErgoValue.of(sigma.Colls.fromArray(Array(Long.MaxValue / 4, bid)),
        org.ergoplatform.appkit.scalaapi.scalaLongType).toHex
      val box = withId(ctx, tokenGridOrder.copy(value = 1000000L,
        ergoTree = MachinaContracts.tokenGrid.treeHex(Rsn), assets = Seq(NodeAsset(Lit, bid * 1000L + 1L)),
        additionalRegisters = node.model.NodeRegisters(tokenGridOrder.additionalRegisters.values.updated("R5", prices))))
      val order = grid(box)
      (order.base, order.tokenId, order.tokens) shouldBe ((Some(Lit), Rsn, 0L))
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      val (bundles, stake) = gridBuild(ctx, Seq(litRsnPool, canonical), Seq(order))
      bundles.size shouldBe 1
      val Seq(fill, sale) = bundles.head.members.map(decoded)
      fill.inputs.map(_.boxId) shouldBe Seq(litRsnPool.boxId, stake.boxId, order.boxId)
      fill.outputs(2).assets.map(_.tokenId) shouldBe Seq(Lit, Rsn)
      sale.inputs.head.boxId shouldBe canonical.boxId
      sale.outputs(1).value shouldBe stake.value
      sale.outputs(2).value should be >= 1000000L
    }
  }

  "The live token/token grid order" should "not be filled, its child unable to hold the id register at its value, and be refused" in {
    nodeContext.getClient.execute { ctx =>
      val order = grid(tokenGridOrder)
      tokenGridOrder.value shouldBe 162720L
      var refused = Vector.empty[String]
      gridBuild(ctx, Seq(fakuCigPool, fakuErgoDexPool), Seq(order), refuse = id => refused :+= id)._1 shouldBe empty
      refused shouldBe Vector(order.boxId)
    }
  }

  "A grid cycle and a pool cycle" should "share the stake, the grid's first, the pool cycle priced on the ERG pool the sale left" in {
    nodeContext.getClient.execute { ctx =>
      // FAKU dearer on LithosDex than on ErgoDEX, so a pair pays besides the grid
      val (bundles, stake) = gridBuild(ctx, Seq(fakuCigPool, fakuErgoDexPool, lithosDex(ctx, 33000.0)),
        Seq(funded(ctx)))
      bundles.size should be >= 2
      val gridLegs = bundles.head.members.map(decoded)
      gridLegs.head.inputs(1).boxId shouldBe stake.boxId
      val next = bundles(1).members.map(decoded)
      next.head.inputs(1).boxId shouldBe gridLegs.last.outputs(1).boxId
      bundles(1).packageParents should contain(bundles.head.members.last.id)
      next.map(_.inputs.head.boxId) should contain(gridLegs.last.outputs.head.boxId)
    }
  }
}
