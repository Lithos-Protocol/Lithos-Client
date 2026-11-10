package stats

import node.model.{NodeAsset, NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.messages.{BlockInfo, BlockTx, NodeSync, TxOutput}
import transactions.batching.arb.{ArbBasis, ArbCycles, ArbLeg}
import transactions.batching.arb.ArbFixtures._
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, Token}

import scala.concurrent.duration._

/**
 * Arb cycles read back from a block. Every cycle here is the arb stage's own, built by `ArbCycles` and signed
 * against real pool contracts, so the shape recognised is the one the builder makes. Each refusal changes one
 * field of such a cycle.
 */
class ArbStatisticsSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbStatisticsSpec._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val mine = wallet.contract.ergoTreeHex

  private def cycles(block: BlockInfo, ctx: BlockchainContext, minerTree: String = mine) =
    BatchingStatistics.arbCycles(block, ctx.getNetworkType, minerTree)

  /** The first leg's pool NFT, which a cycle's record names. */
  private def firstNft(block: BlockInfo, legs: Seq[BlockTx]): String =
    block.inputBox(legs.head.inputs.head.id).get.assets.head.id.toString

  private def updated(txs: Vector[BlockTx], at: Int)(change: BlockTx => BlockTx): Vector[BlockTx] =
    txs.updated(at, change(txs(at)))

  private def output(tx: BlockTx, index: Int)(change: TxOutput => TxOutput): BlockTx =
    tx.copy(outputs = tx.outputs.updated(index, change(tx.outputs(index))))

  "A cycle the arb stage signed" should "be recorded once, at its profit, under its first pool's NFT" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      txs.size shouldBe 2
      val block = blockOf(txs, confirmed)
      val profit = txs.last.outputs(2)
      cycles(block, ctx) shouldBe Vector(BatchingStatistics.ArbCycle(
        BatchingFee(txs.last.id, firstNft(block, txs), "arb", profit.value.toString, "0", local = true), txs.map(_.id)))
      profit.value should be >= 1000000L
      // Another miner's cycle is still recorded, as not this client's
      cycles(block, ctx, Contract.SIGMA_FALSE.ergoTreeHex).map(_.fee.local) shouldBe Vector(false)
      // No leg reads as an order execution, so nothing is counted twice
      txs.foreach(tx => BatchingStatistics.read(block, tx, ctx.getNetworkType, mine) shouldBe empty)
    }
  }

  it should "be found among other transactions, in any order in the block" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val other = BlockTx("other", Seq.empty, Seq.empty, Seq.empty)
      val block = blockOf(other +: txs.reverse, confirmed)
      cycles(block, ctx).map(_.legs) shouldBe Vector(txs.map(_.id))
    }
  }

  "A four-leg cycle through every pool kind" should "be recorded, each leg's pool known to the batching stats" in {
    nodeContext.getClient.execute { ctx =>
      val a = nativePool(ctx, id(4), id(104), 1000 * Erg, 1000000000000L)
      val aRsn = tokenPool(ctx, id(5), id(104), Rsn, 10000000000000L, 100000L)
      val canonical = canonicalPool(ctx, 100000 * Erg, 10000000000000L)
      val (confirmed, txs) = over(ctx, wallet, Seq(a, aRsn, litRsnPool, canonical), cycles = 1, slots = 4)
      txs.size shouldBe 4
      val block = blockOf(txs, confirmed)
      txs.map(tx => BatchingStatistics.poolProtocol(block.inputBox(tx.inputs.head.id).get, ctx.getNetworkType)) shouldBe
        Vector(Some("ergodex"), Some("ergodex"), Some("lithosdex"), Some("lithosdex"))
      cycles(block, ctx).map(c => (c.legs, c.fee.grossNanoErg)) shouldBe
        Vector((txs.map(_.id), txs.last.outputs(2).value.toString))
    }
  }

  "Two cycles on one stake" should "be two records, the second starting from the stake the first returned" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 2)
      txs.size shouldBe 4
      val found = cycles(blockOf(txs, confirmed), ctx)
      found.map(_.legs) shouldBe Vector(txs.take(2).map(_.id), txs.drop(2).map(_.id))
      found.map(_.fee.grossNanoErg) shouldBe Vector(txs(1).outputs(2).value.toString, txs(3).outputs(2).value.toString)
    }
  }

  "A cycle whose profit goes to true" should "be recorded, since the holding top-up takes it from there too" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1, profitTo = Some(Contract.SIGMA_TRUE))
      txs.last.outputs(2).ergoTree shouldBe Contract.SIGMA_TRUE.ergoTreeHex
      cycles(blockOf(txs, confirmed), ctx).map(_.fee.local) shouldBe Vector(true)
    }
  }

  "A cycle started from an arb address" should "be recorded, as this client's when the address is its own key's" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 2, fromArbAddress = true)
      val arbTree = stakeContract(wallet).ergoTreeHex
      val block = blockOf(txs, confirmed)
      // The stake and every carry sit at the arb address; the profit is at the owner's plain key
      block.inputBox(txs.head.inputs(1).id).get.ergoTree shouldBe arbTree
      txs.foreach(_.outputs(1).ergoTree shouldBe arbTree)
      txs(1).outputs(2).ergoTree shouldBe mine
      val found = cycles(block, ctx)
      found.map(_.legs) shouldBe Vector(txs.take(2).map(_.id), txs.drop(2).map(_.id))
      found.map(_.fee.local) shouldBe Vector(true, true)
      found.map(_.fee.grossNanoErg) shouldBe Vector(txs(1).outputs(2).value.toString, txs(3).outputs(2).value.toString)
      // Read by a client with another key, it is another miner's cycle
      val stranger = "0008cd" + "02" + "11" * 32
      cycles(block, ctx, stranger).map(_.fee.local) shouldBe Vector(false, false)
    }
  }

  it should "be recorded with its profit at true, and not with its profit at another key or a third script" in {
    nodeContext.getClient.execute { ctx =>
      val (toTrue, trueTxs) = built(ctx, wallet, pairs = 1, profitTo = Some(Contract.SIGMA_TRUE), fromArbAddress = true)
      cycles(blockOf(trueTxs, toTrue), ctx).map(_.fee.local) shouldBe Vector(true)
      val (confirmed, txs) = built(ctx, wallet, pairs = 1, fromArbAddress = true)
      val otherKey = updated(txs, 1)(output(_, 2)(o => o.copy(ergoTree = "0008cd" + "02" + "11" * 32)))
      val elsewhere = updated(txs, 1)(output(_, 2)(o => o.copy(ergoTree = Contract.SIGMA_FALSE.ergoTreeHex)))
      cycles(blockOf(otherKey, confirmed), ctx) shouldBe empty
      cycles(blockOf(elsewhere, confirmed), ctx) shouldBe empty
    }
  }

  "A chain of legs" should "not be recorded when the stake does not come back whole" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val short = updated(txs, 1)(output(_, 1)(o => o.copy(value = o.value - 1)))
      val padded = updated(updated(txs, 1)(output(_, 1)(o => o.copy(value = o.value + 1))), 1)(
        output(_, 2)(o => o.copy(value = o.value - 1)))
      cycles(blockOf(short, confirmed), ctx) shouldBe empty
      cycles(blockOf(padded, confirmed), ctx) shouldBe empty
    }
  }

  it should "not be recorded when its profit holds a token or goes to a third tree" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val token = Token(ErgoId.create(id(901)), 1L)
      val withToken = updated(txs, 1)(output(_, 2)(o => o.copy(assets = Seq(token))))
      val elsewhere = updated(txs, 1)(output(_, 2)(o => o.copy(ergoTree = Contract.SIGMA_FALSE.ergoTreeHex)))
      cycles(blockOf(withToken, confirmed), ctx) shouldBe empty
      cycles(blockOf(elsewhere, confirmed), ctx) shouldBe empty
    }
  }

  it should "not be recorded when the carry changes tree on the way" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val moved = updated(txs, 0)(output(_, 1)(o => o.copy(ergoTree = Contract.SIGMA_TRUE.ergoTreeHex)))
      cycles(blockOf(moved, confirmed), ctx) shouldBe empty
    }
  }

  it should "not be recorded when the stake held a token or was no P2PK box" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val stakeId = txs.head.inputs(1).id
      def withStake(change: NodeBox => NodeBox) = confirmed.map(b => if (b.boxId == stakeId) change(b) else b)
      cycles(blockOf(txs, withStake(_.copy(assets = Seq(NodeAsset(id(902), 1L))))), ctx) shouldBe empty
      cycles(blockOf(txs, withStake(_.copy(ergoTree = Contract.SIGMA_TRUE.ergoTreeHex))), ctx) shouldBe empty
    }
  }

  it should "not be recorded when a leg pays a miner fee" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val fee = txs.last.outputs(2).copy(id = "fe" * 32, ergoTree = Contract.FEE.ergoTreeHex, index = 3)
      cycles(blockOf(updated(txs, 1)(tx => tx.copy(outputs = tx.outputs :+ fee)), confirmed), ctx) shouldBe empty
    }
  }

  it should "not be recorded when a leg's pool box does not hand its NFT on at output 0" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      val moved = updated(txs, 0)(output(_, 0)(o => o.copy(assets = o.assets.tail)))
      cycles(blockOf(moved, confirmed), ctx) shouldBe empty
    }
  }

  it should "not be recorded as a cycle of one leg" in {
    nodeContext.getClient.execute { ctx =>
      val (confirmed, txs) = built(ctx, wallet, pairs = 1)
      // The last leg alone, its carry input taken from the stake so the value would come back whole
      val stake = confirmed.find(_.boxId == txs.head.inputs(1).id).get
      val alone = Vector(txs.last.copy(inputs = txs.last.inputs.updated(1, txs.head.inputs(1))))
      val back = updated(alone, 0)(output(_, 1)(_.copy(value = stake.value)))
      cycles(blockOf(back, confirmed), ctx) shouldBe empty
    }
  }
}

object ArbStatisticsSpec {

  private val height = 1908201

  private def decoded(tx: CandidateTx): NodeTransaction =
    NodeCodecs.transaction(new com.google.gson.JsonParser().parse(tx.json).getAsJsonObject)

  /**
   * `pairs` two-leg cycles through made-up ErgoDEX pools, built on one stake by `ArbCycles` as the arb stage would
   * offer them: the boxes they spend from the chain, and their legs in spending order.
   */
  def built(ctx: BlockchainContext, wallet: mutations.NodeWallet, pairs: Int,
            profitTo: Option[Contract] = None, fromArbAddress: Boolean = false): (Seq[NodeBox], Vector[BlockTx]) = {
    val pools = (0 until pairs).flatMap { i =>
      Seq(nativePool(ctx, id(10 * i + 1), id(100 + i), 1000 * Erg, 1000000000000L),
        nativePool(ctx, id(10 * i + 2), id(100 + i), 1100 * Erg, 1000000000000L))
    }
    over(ctx, wallet, pools, cycles = pairs, slots = 2 * pairs, profitTo, fromArbAddress)
  }

  /** The cycles `ArbCycles` builds through `pools` on one stake, which must number `cycles`. */
  def over(ctx: BlockchainContext, wallet: mutations.NodeWallet, pools: Seq[NodeBox], cycles: Int, slots: Int,
           profitTo: Option[Contract] = None, fromArbAddress: Boolean = false): (Seq[NodeBox], Vector[BlockTx]) = {
    val stake =
      if (fromArbAddress) arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
      else stakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
    val bundles = ArbCycles.build(ctx, wallet, index(ctx, pools), ArbBasis.Empty, stake, height, slots,
      stake.value - ArbLeg.MinCarryValue, 1000000L, profitTo.getOrElse(wallet.contract),
      Deadline.now + 1.minute, Deadline.now + 1.minute, _ => true)
    require(bundles.size == cycles, s"expected $cycles cycles, built ${bundles.size}")
    (pools :+ stake, bundles.flatMap(_.members).map(m => NodeSync.blockTx(decoded(m))).toVector)
  }

  /** A block carrying `txs`, each input resolved as an indexed block resolves it: from `confirmed` or this block. */
  def blockOf(txs: Seq[BlockTx], confirmed: Seq[NodeBox]): BlockInfo = {
    val boxes = (confirmed.map(NodeSync.txOutput) ++ txs.flatMap(_.outputs)).map(b => b.id -> b).toMap
    BlockInfo("block", height, txs,
      resolvedInputs = txs.flatMap(_.inputs).flatMap(in => boxes.get(in.id).map(in.id -> _)).toMap)
  }
}
