package transactions.batching.arb

import node.MutationConversions._
import node.model.{NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import org.ergoplatform.appkit.BlockchainContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.LDNodeFixtures
import transactions.batching.ergodex.ErgoDexExecution
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBundle, CapitalOrigin}
import work.lithos.mutations.UTXO

import scala.concurrent.duration._

/**
 * Cycles built for one block and signed against the real pool contracts: every leg of every cycle here passed
 * its pool's script, so a cycle that builds is one that contract accepts. These pin the bundle each cycle
 * becomes, the stake returned whole, the profit's output, which pool box each leg spends when the batchers
 * have moved it, and the package parents a cycle has to name.
 */
class ArbCyclesSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val height = 1908201
  private val stakeValue = 10 * Erg + ArbLeg.MinCarryValue

  private def build(ctx: BlockchainContext, index: ArbIndex, stake: NodeBox, basis: ArbBasis = ArbBasis.Empty,
                    slots: Int = 8, reserve: String => Boolean = _ => true): Vector[CandidateBundle] =
    ArbCycles.build(ctx, wallet, index, basis, stake, height, slots, stake.value - ArbLeg.MinCarryValue, 1000000L,
      wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute, reserve)

  private def decoded(tx: CandidateTx): NodeTransaction =
    NodeCodecs.transaction(new com.google.gson.JsonParser().parse(tx.json).getAsJsonObject)

  private def legs(bundle: CandidateBundle): Vector[NodeTransaction] = bundle.members.map(decoded)

  /**
   * Every input a bundle spends that a transaction outside it created, among the basis and the bundles before
   * it, is named as a package parent; otherwise admission could keep the cycle and drop what it spends.
   */
  private def parentsDeclared(bundles: Seq[CandidateBundle], basis: Seq[CandidateBundle] = Seq.empty): Unit = {
    var creators = basis.flatMap(_.members).flatMap(tx => scala.util.Try(decoded(tx)).toOption.toSeq)
      .flatMap(tx => tx.outputs.map(_.boxId -> tx.id)).toMap
    bundles.foreach { bundle =>
      val own = bundle.members.map(_.id).toSet
      val outside = bundle.members.flatMap(_.inputIds).flatMap(creators.get).filterNot(own.contains).toSet
      bundle.packageParents.toSet shouldBe outside
      creators ++= legs(bundle).flatMap(tx => tx.outputs.map(_.boxId -> tx.id))
    }
  }

  /** The shape every cycle bundle has, whatever its pools: checked field by field off the signed bodies. */
  private def checkCycle(bundle: CandidateBundle, stakeIn: String, poolBoxes: Seq[String]): Unit = {
    val txs = legs(bundle)
    txs.size shouldBe poolBoxes.size
    bundle.members.foreach(_.kind shouldBe ArbLeg.Kind)
    txs.zip(poolBoxes).foreach { case (tx, pool) => tx.inputs.head.boxId shouldBe pool }
    txs.head.inputs.map(_.boxId).tail shouldBe Seq(stakeIn)
    txs.sliding(2).foreach {
      case Seq(a, b) => b.inputs(1).boxId shouldBe a.outputs(1).boxId
      case _ => ()
    }
    val last = txs.last
    last.outputs.size shouldBe 3
    last.outputs(1).ergoTree shouldBe wallet.contract.ergoTreeHex
    last.outputs(1).value shouldBe stakeValue
    last.outputs(1).assets shouldBe empty
    val profit = last.outputs(2)
    profit.ergoTree shouldBe wallet.contract.ergoTreeHex
    profit.value should be >= 1000000L
    bundle.capital.map(c => (c.origin, c.outputId, c.value, c.parentTxId)) shouldBe
      Seq((CapitalOrigin.Arbitrage, profit.boxId, profit.value, last.id))
    // Fee-less: no leg has a fee output, and every nanoERG of profit came out of the pools the cycle went through
    txs.init.foreach(_.outputs.size shouldBe 2)
    txs.map(tx => tx.outputs.head.value).sum - poolValues(txs) shouldBe -profit.value
  }

  /** What each leg's pool held before it, read from the inputs' boxes as this spec built them. */
  private var spentValues = Map.empty[String, Long]
  private def remember(boxes: NodeBox*): Unit = spentValues ++= boxes.map(b => b.boxId -> b.value)
  private def poolValues(txs: Seq[NodeTransaction]): Long = txs.map { tx =>
    val id = tx.inputs.head.boxId
    spentValues.getOrElse(id, txs.flatMap(_.outputs).find(_.boxId == id).map(_.value)
      .getOrElse(fail(s"no value known for pool box $id")))
  }.sum

  private def pair(ctx: BlockchainContext, token: Int = 101, nfts: (Int, Int) = (1, 2)): (NodeBox, NodeBox) = {
    val cheap = nativePool(ctx, id(nfts._1), id(token), 1000 * Erg, 1000000000000L)
    val dear = nativePool(ctx, id(nfts._2), id(token), 1100 * Erg, 1000000000000L)
    remember(cheap, dear)
    cheap -> dear
  }

  "A pair" should "buy where the token is cheap and sell where it is dear, in one bundle signed against both pools" in {
    nodeContext.getClient.execute { ctx =>
      val (cheap, dear) = pair(ctx)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val bundles = build(ctx, index(ctx, Seq(cheap, dear)), stake, slots = 2)
      bundles.size shouldBe 1
      checkCycle(bundles.head, stake.boxId, Seq(cheap.boxId, dear.boxId))
      bundles.head.packageParents shouldBe empty
    }
  }

  "A triangle through the LIT hub" should "sign through ERG:LIT, the LIT/RSN token pool and an ErgoDEX RSN pool" in {
    nodeContext.getClient.execute { ctx =>
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      val rsn = nativePool(ctx, id(3), Rsn, 2000 * Erg, 10000L)
      remember(canonical, litRsnPool, rsn)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val idx = index(ctx, Seq(canonical, litRsnPool, rsn))
      idx.routes.map(_.hops.size).distinct shouldBe Vector(3)
      val bundles = build(ctx, idx, stake, slots = 3)
      bundles.size shouldBe 1
      checkCycle(bundles.head, stake.boxId, Seq(canonical.boxId, litRsnPool.boxId, rsn.boxId))
    }
  }

  "A four-leg cycle" should "sign through all four pool kinds" in {
    nodeContext.getClient.execute { ctx =>
      val a = nativePool(ctx, id(4), id(104), 1000 * Erg, 1000000000000L)
      val aRsn = tokenPool(ctx, id(5), id(104), Rsn, 10000000000000L, 100000L)
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      remember(a, aRsn, canonical, litRsnPool)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val idx = index(ctx, Seq(a, aRsn, litRsnPool, canonical))
      idx.routes.map(_.hops.size).distinct shouldBe Vector(4)
      idx.pools.values.map(_.pool.kind).toSet shouldBe Set(ArbPoolKind.ErgoDexNative, ArbPoolKind.ErgoDexToken,
        ArbPoolKind.LithosDexNative, ArbPoolKind.LithosDexToken)
      val bundles = build(ctx, idx, stake, slots = 4)
      bundles.size shouldBe 1
      checkCycle(bundles.head, stake.boxId, Seq(a.boxId, aRsn.boxId, litRsnPool.boxId, canonical.boxId))
    }
  }

  "The shipped arb budget" should "hold eight legs of the costliest kind" in {
    nodeContext.getClient.execute { ctx =>
      val a = nativePool(ctx, id(4), id(104), 1000 * Erg, 1000000000000L)
      val aRsn = tokenPool(ctx, id(5), id(104), Rsn, 10000000000000L, 100000L)
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      val bundle = build(ctx, index(ctx, Seq(a, aRsn, litRsnPool, canonical)), stakeBox(ctx, wallet, stakeValue),
        slots = 4).head
      val limits = configs.CandidateConfig.Default.limitsFor(configs.CandidateSourceConfig.Arb)
      // One leg through each pool kind, so the dearest of the four bounds any leg
      bundle.members.size shouldBe 4
      limits.maxTxs.toLong * bundle.members.map(_.cost).max should be <= limits.maxCost
      limits.maxTxs.toLong * bundle.members.map(_.sizeBytes.toLong).max should be <= limits.maxBytes
    }
  }

  "Two cycles" should "run on one stake, the second spending the stake the first returned" in {
    nodeContext.getClient.execute { ctx =>
      val (p1, p2) = pair(ctx, 101, (1, 2))
      val (q1, q2) = pair(ctx, 102, (11, 12))
      val stake = stakeBox(ctx, wallet, stakeValue)
      val reserved = Vector.newBuilder[String]
      val bundles = build(ctx, index(ctx, Seq(p1, p2, q1, q2)), stake, slots = 4, reserve = { id => reserved += id; true })
      bundles.size shouldBe 2
      val first = legs(bundles.head)
      checkCycle(bundles.head, stake.boxId, first.map(_.inputs.head.boxId))
      checkCycle(bundles(1), first.last.outputs(1).boxId, legs(bundles(1)).map(_.inputs.head.boxId))
      bundles.map(legs(_).last.outputs(1).boxId) shouldBe reserved.result()
      bundles(1).packageParents shouldBe Seq(bundles.head.members.last.id)
      parentsDeclared(bundles)
      CandidateBundle.admit(bundles, 8)._1.size shouldBe 2
      CandidateBundle.admit(bundles.tail, 8)._1 shouldBe empty
    }
  }

  "Cycles sharing a pool" should "spend the box the earlier cycle left, at the state it left" in {
    nodeContext.getClient.execute { ctx =>
      val (cheap, dear) = pair(ctx)
      val dear2 = nativePool(ctx, id(7), id(101), 1080 * Erg, 1000000000000L)
      remember(dear2)
      val stake = stakeBox(ctx, wallet, 100 * Erg + ArbLeg.MinCarryValue)
      val bundles = ArbCycles.build(ctx, wallet, index(ctx, Seq(cheap, dear, dear2)), ArbBasis.Empty, stake, height, 4,
        100 * Erg, 1000000L, wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute, _ => true)
      bundles.size shouldBe 2
      val firstOuts = legs(bundles.head).flatMap(_.outputs).map(_.boxId).toSet
      val shared = legs(bundles(1)).map(_.inputs.head.boxId).filter(firstOuts.contains)
      shared should not be empty
      Seq(cheap, dear, dear2).map(_.boxId).foreach { confirmed =>
        bundles.flatMap(_.members).count(_.inputIds.contains(confirmed)) should be <= 1
      }
      parentsDeclared(bundles)
    }
  }

  /** A batcher's run that swaps `amount` nanoERG into `pool` for its token, signed as the ErgoDEX batcher would sign one. */
  private def batcherRun(ctx: BlockchainContext, pool: NodeBox, amount: Long): CandidateBundle = {
    val state = entry(ctx, pool).pool
    val swap = state.swap(amount, xIn = true).get
    val funding = LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, amount + 5 * Erg), 0, id(901)).toInputUTXO(ctx)
    val tx = ArbLeg.build(ctx, wallet, swap, pool.toInputUTXO(ctx), Seq(funding), wallet.contract, height)
    CandidateBundle(Vector(CandidateTx.signed(tx, ErgoDexExecution.Kind)))
  }

  "A cycle through a pool a batcher ran" should "build on the batcher's last successor and name its run as a parent" in {
    nodeContext.getClient.execute { ctx =>
      val (cheap, dear) = pair(ctx)
      val run = batcherRun(ctx, cheap, 2 * Erg)
      val successor = decoded(run.members.head).outputs.head
      remember(successor)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val bundles = build(ctx, index(ctx, Seq(cheap, dear)), stake, ArbBasis.of(Seq(run)), slots = 2)
      bundles.size shouldBe 1
      checkCycle(bundles.head, stake.boxId, Seq(successor.boxId, dear.boxId))
      bundles.flatMap(_.members).flatMap(_.inputIds) should not contain cheap.boxId
      bundles.head.packageParents shouldBe Seq(run.members.head.id)
      parentsDeclared(bundles, Seq(run))
      CandidateBundle.admit(run +: bundles, 8)._1.size shouldBe 2
      CandidateBundle.admit(bundles, 8)._1 shouldBe empty
      CandidateBundle.admit(bundles, 8, packageTxs = Set(run.members.head.id))._1.size shouldBe 1
    }
  }

  it should "leave a pool alone when a batcher spent its box and left no successor that reads" in {
    nodeContext.getClient.execute { ctx =>
      val (cheap, dear) = pair(ctx)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val opaque = CandidateBundle(Vector(CandidateTx("ee" * 32, "{}", "lithosdex-batch", Set(dear.boxId))))
      build(ctx, index(ctx, Seq(cheap, dear)), stake, ArbBasis.of(Seq(opaque)), slots = 2) shouldBe empty
      build(ctx, index(ctx, Seq(cheap, dear)), stake, slots = 2).size shouldBe 1
    }
  }

  "No cycle" should "be built on a stake a batcher's bundle spends" in {
    nodeContext.getClient.execute { ctx =>
      val (cheap, dear) = pair(ctx)
      val stake = stakeBox(ctx, wallet, stakeValue)
      val opaque = CandidateBundle(Vector(CandidateTx("ee" * 32, "{}", "ergodex-batch", Set(stake.boxId))))
      build(ctx, index(ctx, Seq(cheap, dear)), stake, ArbBasis.of(Seq(opaque)), slots = 2) shouldBe empty
    }
  }

  it should "be offered when the wallet will not hold its stake output, nor any built on it" in {
    nodeContext.getClient.execute { ctx =>
      val (p1, p2) = pair(ctx, 101, (1, 2))
      val (q1, q2) = pair(ctx, 102, (11, 12))
      val stake = stakeBox(ctx, wallet, stakeValue)
      val idx = index(ctx, Seq(p1, p2, q1, q2))
      build(ctx, idx, stake, slots = 4, reserve = _ => false) shouldBe empty
      var asked = 0
      val firstOnly = build(ctx, idx, stake, slots = 4, reserve = { _ => asked += 1; asked == 1 })
      firstOnly.size shouldBe 1
      asked shouldBe 2
    }
  }
}
