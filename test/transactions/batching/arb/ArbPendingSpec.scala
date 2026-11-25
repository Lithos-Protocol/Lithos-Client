package transactions.batching.arb

import node.model.{NodeAsset, NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, ChainFromPackage, IncludeExisting, Supersede}
import transactions.candidate.CandidateBundle

import scala.concurrent.duration._
import scala.io.Source

/**
 * Pending pool spends, on mainnet block 1,920,122: wallet `…37f477` sold LIT into the canonical ERG:LIT pool, and
 * `…82166d` back-ran it with twelve legs in four loops (canonical pool, a LithosDex LIT token/token pool, an ErgoDEX
 * ERG pool), netting 0.725 ERG. Replayed as if all thirteen were pending on the block before. These pin that the
 * swap is carried and the arb superseded; that the arb stage then finds its own cycle behind the swap, signed
 * against the real pools, with the swap carried first and the arb declared displaced; and the bounds that leave a
 * pool on its confirmed box.
 */
class ArbPendingSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val height = 1920122

  private lazy val (txs, boxes, parentId) = {
    val source = Source.fromResource("arb/backrun-1920122.json")
    try {
      val doc = new com.google.gson.JsonParser().parse(source.mkString).getAsJsonObject
      val txs = (0 until doc.getAsJsonArray("transactions").size).map(i =>
        NodeCodecs.transaction(doc.getAsJsonArray("transactions").get(i).getAsJsonObject)).toVector
      val boxes = (0 until doc.getAsJsonArray("boxes").size).map(i =>
        NodeCodecs.box(doc.getAsJsonArray("boxes").get(i).getAsJsonObject)).toVector
      (txs, boxes, doc.get("parentId").getAsString)
    } finally source.close()
  }

  /** The swap, then the arb's twelve legs in block order. */
  private def swap: NodeTransaction = txs.head
  private def arb: Vector[NodeTransaction] = txs.tail
  private def canonicalNft: String = swap.outputs.head.assets.head.tokenId
  private def poolBoxes: Vector[NodeBox] = boxes.filterNot(_.ergoTree.startsWith("0008cd"))
  private def pools: Map[String, NodeBox] = poolBoxes.map(b => b.assets.head.tokenId -> b).toMap

  private def snapshot(pending: Seq[NodeTransaction]): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot(parentId, pending.map(_.id).toSet, pending.flatMap(_.inputs.map(_.boxId)).toSet,
      System.nanoTime(), transactions = pending.map(t => CompleteMempool.MempoolTx(t.id, t, 0)).toVector.sortBy(_.id))

  private def reader(known: Seq[NodeBox]): Seq[String] => Map[String, NodeBox] =
    ids => known.filter(b => ids.contains(b.boxId)).map(b => b.boxId -> b).toMap

  private def view(pending: Seq[NodeTransaction] = txs, known: Seq[NodeBox] = boxes): ArbPending =
    ArbPending.of(snapshot(pending), 1L, pools, reader(known))

  /** The arb legs that spend a box the walks reach: the canonical box the swap leaves, and the confirmed boxes. */
  private def firstLegs: Set[String] = Set(1, 2, 3, 5, 6, 11, 12).map(i => txs(i).id)

  "The recorded back-run" should "carry the direct swap and stop at the arb, superseding each leg the walks reach" in {
    val pending = view()
    pending.chains.keySet shouldBe Set(canonicalNft)
    val chain = pending.chains(canonicalNft)
    chain.members.map(_.id) shouldBe Vector(swap.id)
    chain.members.map(_.kind) shouldBe Vector(CandidateTx.MempoolAncestor)
    chain.confirmedId shouldBe swap.inputs.head.boxId
    chain.tip.boxId shouldBe swap.outputs.head.boxId
    pending.superseded shouldBe firstLegs
    pending.key shouldBe (firstLegs + swap.id).toVector.sorted
  }

  "A carried transaction" should "travel as the body the node holds, inputs, proofs and outputs unchanged" in {
    txs.foreach { tx =>
      val carried = CandidateTx.ancestor(tx)
      carried.id shouldBe tx.id
      carried.inputIds shouldBe tx.inputs.map(_.boxId).toSet
      val back = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(carried.json).getAsJsonObject)
      back.copy(size = None, cost = None) shouldBe tx.copy(size = None, cost = None)
      back.inputs.map(_.spendingProof.extension.keys.toVector) shouldBe tx.inputs.map(_.spendingProof.extension.keys.toVector)
    }
  }

  "A direct swap" should "be carried on its own, never superseded" in {
    val pending = view(Seq(swap))
    pending.chains(canonicalNft).members.map(_.id) shouldBe Vector(swap.id)
    pending.superseded shouldBe empty
  }

  "A cycle whose funding box cannot be read" should "be carried, and its pool-spending ancestors leave the pools confirmed" in {
    val pending = view(known = Seq.empty)
    pending.superseded shouldBe empty
    // The swap and the first leg would be carried, but the next leg's funding came from a leg on another pool
    pending.chains shouldBe empty
  }

  "A closed cycle funded from a box holding an unrelated token" should "still be superseded" in {
    val token = id(777)
    val (funding, funded) = withToken(token, 5L, 5L)
    view(funded, boxes.map(b => if (b.boxId == funding.boxId) funding else b)).superseded shouldBe firstLegs
  }

  it should "be carried when one unit of that token goes missing over the cycle" in {
    val token = id(777)
    val (funding, funded) = withToken(token, 5L, 4L)
    view(funded, boxes.map(b => if (b.boxId == funding.boxId) funding else b)).superseded shouldBe empty
  }

  "A contested pool box" should "leave its pool on the confirmed box" in {
    val rival = swap.copy(id = "dd" * 32, outputs = swap.outputs.zipWithIndex.map { case (o, i) =>
      o.copy(boxId = f"$i%02x" + "ee" * 31, transactionId = "dd" * 32)
    })
    val pending = view(txs :+ rival)
    pending.chains.get(canonicalNft) shouldBe None
  }

  "A pool chain" should "be left confirmed past four carried transactions" in {
    val chain = swaps(5)
    view(chain.map(_._1), boxes ++ chain.map(_._2)).chains.get(canonicalNft) shouldBe None
    val four = swaps(4)
    view(four.map(_._1), boxes ++ four.map(_._2)).chains(canonicalNft).members.size shouldBe 4
  }

  "The arb stage" should "find a cycle behind the swap, carry the swap first, and declare the arb displaced" in {
    nodeContext.getClient.execute { ctx =>
      val stake = arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
      val pending = view()
      val bundles = build(ctx, stake, pending)
      bundles should not be empty
      val first = bundles.head
      first.members.head.id shouldBe swap.id
      first.members.head.kind shouldBe CandidateTx.MempoolAncestor
      first.interactions should contain allOf(ChainFromMempool(swap.id), IncludeExisting(swap.id))
      first.interactions.collect { case ChainFromPackage(p) => p } should not contain swap.id
      first.carriesItsParents shouldBe true
      val ownLegs = first.members.tail
      ownLegs.head.inputIds should contain(swap.outputs.head.boxId)
      withClue("the gap the swap opened pays, as the recorded arber's first loop took 0.427 ERG on 10: ") {
        first.capital.map(_.value).sum should be > 100000000L
      }
      val displaced = first.interactions.collect { case Supersede(ids) => ids }.flatten.toSet
      displaced should contain(txs(1).id)
      displaced should not contain swap.id
      withClue("no cycle is placed before the swap: nothing of ours spends a box the swap spends: ") {
        bundles.flatMap(_.members).filter(_.kind != CandidateTx.MempoolAncestor).flatMap(_.inputIds).toSet
          .intersect(swap.inputs.map(_.boxId).toSet) shouldBe empty
      }
      withClue("the swap is carried once, by the first bundle through its pool: ") {
        bundles.flatMap(_.members).count(_.id == swap.id) shouldBe 1
      }
      withClue("admission keeps the bundle whole, with the swap in front: ") {
        val (kept, selected, _) = CandidateBundle.admit(bundles, 150)
        kept.headOption shouldBe Some(first)
        selected.head.id shouldBe swap.id
      }
    }
  }

  it should "spend the confirmed pool box, carrying nothing, without the pending view" in {
    nodeContext.getClient.execute { ctx =>
      val stake = arbStakeBox(ctx, wallet, 10 * Erg + ArbLeg.MinCarryValue)
      val bundles = build(ctx, stake, ArbPending.Empty)
      bundles.flatMap(_.members).map(_.kind) should not contain CandidateTx.MempoolAncestor
      bundles.flatMap(_.interactions).collect { case s: Supersede => s } shouldBe empty
    }
  }

  private def build(ctx: org.ergoplatform.appkit.BlockchainContext, stake: NodeBox, pending: ArbPending)
  : Vector[CandidateBundle] =
    ArbCycles.build(ctx, wallet, index(ctx, poolBoxes, height - 1), ArbBasis.Empty, Some(stake), Seq.empty,
      1000000L, height, 150, 10 * Erg, 1000000L, wallet.contract, Deadline.now + 1.minute, Deadline.now + 1.minute,
      _ => true, pending = pending)

  /**
   * The arb with an unrelated token in its funding box and `back` units of it in its last leg's output at the
   * arber's script. Only the classifier reads these, so ids are left as recorded.
   */
  private def withToken(token: String, in: Long, back: Long): (NodeBox, Vector[NodeTransaction]) = {
    val fundingId = arb.head.inputs(1).boxId
    val funding = boxes.find(_.boxId == fundingId).get
    val last = arb.last
    val paid = last.outputs(1)
    val changed = last.copy(outputs = last.outputs.updated(1, paid.copy(assets = paid.assets :+ NodeAsset(token, back))))
    funding.copy(assets = funding.assets :+ NodeAsset(token, in)) -> (txs.init :+ changed)
  }

  /**
   * `n` direct swaps on the canonical pool in a row, each by the recorded seller from a box of its own, the pool's
   * reserves left as the first left them. Only the classifier reads these.
   */
  private def swaps(n: Int): Vector[(NodeTransaction, NodeBox)] = {
    val seller = swap.inputs(1).boxId
    val sellerBox = boxes.find(_.boxId == seller).get
    (0 until n).foldLeft(Vector.empty[(NodeTransaction, NodeBox)]) { (acc, i) =>
      val txId = f"${0xa0 + i}%02x" + "ab" * 31
      val poolIn = acc.lastOption.map(_._1.outputs.head.boxId).getOrElse(swap.inputs.head.boxId)
      val funding = sellerBox.copy(boxId = f"${0xa0 + i}%02x" + "cd" * 31)
      val outputs = swap.outputs.zipWithIndex.map { case (o, k) =>
        o.copy(boxId = f"${0xa0 + i}%02x" + f"$k%02x" + "ef" * 30, transactionId = txId)
      }
      acc :+ (swap.copy(id = txId, inputs = Seq(swap.inputs.head.copy(boxId = poolIn), swap.inputs(1).copy(boxId = funding.boxId)),
        outputs = outputs) -> funding)
    }
  }
}
