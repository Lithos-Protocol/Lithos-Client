package transactions.batching.arb

import arbitrage.contracts.ArbStakeContract
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.NetworkType
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.ast.ErgoTree
import work.lithos.mutations.Contract

import java.util.concurrent.atomic.AtomicInteger
import scala.util.{Failure, Success}

/**
 * The arb stake at its own address. These pin which box at the address is taken as the stake, that a cycle's
 * output is followed by id through a found block, no block and a reorg without reading the address again, and
 * that nothing is handed out on a read that failed or for a block it was not checked against.
 */
class ArbStakeSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbStakeSpec._

  /**
   * A node whose confirmed UTXO set and whose index are set apart, since the index can trail the set.
   *
   * @param utxo    boxes the confirmed UTXO set holds, which a read by id answers from
   * @param indexed boxes the index lists at the arb address, newest first
   */
  private class Chain(var utxo: Map[String, NodeBox] = Map.empty, var indexed: Vector[NodeBox] = Vector.empty) {
    val api: NodeApi = mock[NodeApi]
    val scans = new AtomicInteger()
    val reads = new AtomicInteger()
    @volatile var failing = false
    @volatile var index = true

    when(api.indexerEnabled).thenAnswer(_ => index)
    when(api.boxById(anyString())).thenAnswer { inv =>
      reads.incrementAndGet()
      if (failing) Failure(new RuntimeException("node unavailable")) else Success(utxo.get(inv.getArgument[String](0)))
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        scans.incrementAndGet()
        val paging = inv.getArgument[Paging](1)
        if (failing) Failure(new RuntimeException("node unavailable"))
        else Success((if (inv.getArgument[SortDirection](2) == SortDirection.Asc) indexed.reverse else indexed)
          .filter(_.ergoTree == inv.getArgument[String](0)).slice(paging.offset, paging.offset + paging.limit)
          .map(box => IndexedBox(box, "", 100, 1L)))
      }

    /** A block created `box`: in the UTXO set and listed by the index. */
    def arrive(box: NodeBox): NodeBox = { utxo += box.boxId -> box; indexed = box +: indexed; box }

    /** A block spent `id`: gone from both. */
    def spend(id: String): Unit = { utxo -= id; indexed = indexed.filterNot(_.boxId == id) }

    def stake(target: Long = 10 * Erg): ArbStake = new ArbStake(() => api, StakeContract, NetworkType.MAINNET, target)
  }

  "The arb stake" should "be the largest ERG-only box of at least 0.1 ERG at the arb address" in {
    val chain = new Chain
    chain.arrive(box(1, 3 * Erg))
    val largest = chain.arrive(box(2, 12 * Erg))
    chain.arrive(box(3, 50 * Erg, assets = Seq(NodeAsset(id(900), 1L))))
    chain.arrive(box(4, 60 * Erg, tree = Contract.SIGMA_TRUE.ergoTreeHex))
    chain.arrive(box(5, ArbStake.MinStakeNanoErg - 1))
    chain.stake().at(100, "p100").map(_.boxId) shouldBe Some(largest.boxId)
  }

  it should "be a box under the configured size when that is all the address holds" in {
    val chain = new Chain
    val small = chain.arrive(box(1, ArbStake.MinStakeNanoErg))
    chain.stake(target = 10 * Erg).at(100, "p100").map(_.boxId) shouldBe Some(small.boxId)
  }

  it should "never be a box under 0.1 ERG, though it is the only one there" in {
    val chain = new Chain
    chain.arrive(box(1, ArbStake.MinStakeNanoErg - 1))
    chain.stake().at(100, "p100") shouldBe empty
  }

  it should "pass over a box the index still lists but the UTXO set no longer holds" in {
    val chain = new Chain
    val live = chain.arrive(box(1, 5 * Erg))
    val spent = box(2, 12 * Erg)
    chain.indexed = spent +: chain.indexed
    chain.stake().at(100, "p100").map(_.boxId) shouldBe Some(live.boxId)
  }

  it should "be read once per height and parent, and again for a new parent at the same height" in {
    val chain = new Chain
    chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100") should not be empty
    val (scans, reads) = (chain.scans.get, chain.reads.get)
    stake.at(100, "p100") should not be empty
    (chain.scans.get, chain.reads.get) shouldBe (scans, reads)
    stake.at(100, "other") should not be empty
    chain.reads.get shouldBe reads + 1
    // Followed by id from here on: the address is not read again
    chain.scans.get shouldBe scans
  }

  it should "not be handed out, nor the node read, while arb is off" in {
    val chain = new Chain
    chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake(target = 0L)
    stake.at(100, "p100") shouldBe empty
    stake.active shouldBe false
    (chain.scans.get, chain.reads.get) shouldBe (0, 0)
  }

  it should "not be handed out without the node's index, which the address is read from" in {
    val chain = new Chain
    chain.arrive(box(1, 10 * Erg))
    chain.index = false
    val stake = chain.stake()
    stake.at(100, "p100") shouldBe empty
    chain.scans.get shouldBe 0
    stake.boxes().isFailure shouldBe true
  }

  it should "be taken up without a restart once a box arrives at an empty address" in {
    val chain = new Chain
    val stake = chain.stake()
    stake.at(100, "p100") shouldBe empty
    // The same block is not read twice for nothing
    val scans = chain.scans.get
    stake.at(100, "p100") shouldBe empty
    chain.scans.get shouldBe scans
    val arrived = chain.arrive(box(1, 10 * Erg))
    stake.at(101, "p101").map(_.boxId) shouldBe Some(arrived.boxId)
  }

  "A cycle's stake output" should "become the stake once a block spends the stake, with no read of the address" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    val next = box(2, 10 * Erg)
    stake.handOn(first.boxId, next.boxId, 100) shouldBe true
    val scans = chain.scans.get
    chain.spend(first.boxId)
    chain.utxo += next.boxId -> next
    stake.at(101, "p101").map(_.boxId) shouldBe Some(next.boxId)
    chain.scans.get shouldBe scans
  }

  it should "be the last of a chain of cycles a block carried, or the last one it did carry" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    val outs = (2 to 4).map(i => box(i, 10 * Erg))
    outs.foreach(out => stake.handOn(first.boxId, out.boxId, 100) shouldBe true)
    chain.spend(first.boxId)
    // The block carried the first two cycles only: the second's output is the one left unspent
    chain.utxo += outs(1).boxId -> outs(1)
    stake.at(101, "p101").map(_.boxId) shouldBe Some(outs(1).boxId)
  }

  it should "be dropped at the next height when no block spent the stake" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    val never = box(2, 10 * Erg)
    stake.handOn(first.boxId, never.boxId, 100) shouldBe true
    stake.at(101, "p101").map(_.boxId) shouldBe Some(first.boxId)
    // Were it still followed, a box of that id turning up later would be taken for the stake
    chain.spend(first.boxId)
    chain.utxo += never.boxId -> never
    stake.at(102, "p102") shouldBe empty
  }

  it should "give way to the stake before it when a reorg undoes the block that spent it" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    val next = box(2, 10 * Erg)
    stake.handOn(first.boxId, next.boxId, 100)
    chain.utxo = Map(next.boxId -> next)
    stake.at(101, "ours").map(_.boxId) shouldBe Some(next.boxId)
    // Another block replaces ours at the same height: its output never existed and the old stake is unspent
    chain.utxo = Map(first.boxId -> first)
    val scans = chain.scans.get
    stake.at(101, "theirs").map(_.boxId) shouldBe Some(first.boxId)
    chain.scans.get shouldBe scans
  }

  it should "be refused when it does not replace the stake, the stake was not checked for its height, or it is no box id" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.handOn(first.boxId, id(2), 100) shouldBe false
    stake.at(100, "p100")
    stake.handOn(id(77), id(2), 100) shouldBe false
    stake.handOn(first.boxId, id(2), 101) shouldBe false
    stake.handOn(first.boxId, "not-a-box-id", 100) shouldBe false
    stake.handOn(first.boxId, id(2), 100) shouldBe true
  }

  it should "be refused past 256 followed at once, while each already followed is still accepted" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    (1 to ArbStake.MaxSuccessors).foreach(i => stake.handOn(first.boxId, id(1000 + i), 100) shouldBe true)
    stake.handOn(first.boxId, id(5000), 100) shouldBe false
    stake.handOn(first.boxId, id(1001), 100) shouldBe true
  }

  "A stake a block spent with no cycle output" should "be replaced by whatever else the address holds" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val other = chain.arrive(box(2, 4 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100").map(_.boxId) shouldBe Some(first.boxId)
    chain.spend(first.boxId)
    stake.at(101, "p101").map(_.boxId) shouldBe Some(other.boxId)
  }

  "A node read that fails" should "hand out nothing and change nothing, so the stake is back once the node answers" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    val next = box(2, 10 * Erg)
    stake.handOn(first.boxId, next.boxId, 100)
    chain.spend(first.boxId)
    chain.utxo += next.boxId -> next
    chain.failing = true
    stake.at(101, "p101") shouldBe empty
    // The output is still followed: once the node answers it is found by id, not by a scan
    chain.failing = false
    val scans = chain.scans.get
    stake.at(101, "p101").map(_.boxId) shouldBe Some(next.boxId)
    chain.scans.get shouldBe scans
  }

  "A paused stake" should "be handed to no build and follow no output until it is resumed" in {
    val chain = new Chain
    val first = chain.arrive(box(1, 10 * Erg))
    val stake = chain.stake()
    stake.at(100, "p100")
    stake.pause()
    stake.paused shouldBe true
    stake.current shouldBe empty
    stake.at(100, "p100") shouldBe empty
    stake.at(101, "p101") shouldBe empty
    stake.handOn(first.boxId, id(2), 100) shouldBe false
    stake.resume()
    stake.at(101, "p101").map(_.boxId) shouldBe Some(first.boxId)
  }

  "A read of the arb address" should "page through every box up to its cap" in {
    val chain = new Chain
    (1 to 250).foreach(i => chain.arrive(box(i, Erg)))
    val stake = chain.stake()
    stake.boxes().get.size shouldBe 250
    chain.scans.get shouldBe 3
    // Past the cap the newest are read and the oldest page with them, so a stake older than a flood of boxes is seen
    (251 to ArbStake.MaxBoxes + 200).foreach(i => chain.arrive(box(i, Erg)))
    val read = stake.boxes().get
    read.size shouldBe ArbStake.MaxBoxes + 100
    read.map(_.boxId) should contain(id(1))
    read.map(_.boxId).distinct.size shouldBe read.size
  }
}

object ArbStakeSpec {

  final val Erg = 1000000000L

  val StakeContract: Contract = Contract(ErgoTree.fromHex(ArbStakeContract.PinnedTreeHex))

  def id(seed: Int): String = f"$seed%064x"

  /** A box at the arb address, or at `tree`, as the node reports one. Its id is made up from `seed`. */
  def box(seed: Int, value: Long, assets: Seq[NodeAsset] = Seq.empty,
          tree: String = StakeContract.ergoTreeHex): NodeBox =
    NodeBox(id(seed), id(seed + 100000), value, 0, 100, tree, assets)
}
