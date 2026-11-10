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
import transactions.batching.ergodex.{ErgoDexPool, MachinaContracts, MachinaKind, MachinaOrder, MachinaPricing}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage}
import transactions.candidate.{CandidateBundle, CapitalOrigin}
import work.lithos.mutations.UTXO

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Machina orders the arb stage fills through LithosDex ERG pools, against real mainnet orders and the real
 * LithosDex ERG/FAKU pool: the pricing replayed to the unit on real ErgoDEX fills, the routing between the two
 * DEXes, every fill signed against the pool's and the order's own contracts, and a fill built on a LithosDex run's
 * last successor with a cycle through the same pool priced after it.
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
}
