package transactions.batching.arb

import configs.BatchingConfig
import lithosdex.contracts.LDT2TContracts
import node.model.NodeBox
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.batching.tracking.TrackerFixtures.{shapes, tx}
import transactions.batching.tracking.{PoolDeployment, TrackedBox, TrackedPools}

/**
 * Discovery from the tracked pools, with no node: which pools make the index, that a LithosDex pool needs its
 * vault read as standing once, and that a block moving no pool's box, or moving one, re-reads only what changed.
 */
class ArbDiscoverySpec extends AnyFlatSpec with Matchers {
  import ArbFixtures._

  private val tip = 1908200
  private val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)

  /**
   * Two ErgoDEX pools of one token, one too old and one denied; an ErgoDEX RSN pool; the canonical ERG:LIT pool
   * and the LIT/RSN token pool.
   */
  private lazy val boxes: Map[String, NodeBox] = nodeContext.getClient.execute { ctx =>
    Map(
      "cheap" -> nativePool(ctx, id(1), id(101), 1000 * Erg, 1000000000000L),
      "dear" -> nativePool(ctx, id(2), id(101), 1100 * Erg, 1000000000000L),
      "old" -> nativePool(ctx, id(3), id(101), 1200 * Erg, 1000000000000L),
      "denied" -> nativePool(ctx, id(4), id(101), 1300 * Erg, 1000000000000L),
      "rsn" -> nativePool(ctx, id(5), Rsn, 2000 * Erg, 10000L),
      "canonical" -> canonicalPool(ctx, 100000 * Erg, 10000000000000L),
      "litRsn" -> litRsnPool)
  }

  private def tracked: TrackedPools = TrackedPools.seeded(tip, "h", boxes.toSeq.map { case (name, box) =>
    val included = if (name == "old") tip - BatchingConfig.Default.maxPoolAgeBlocks - 1 else tip - 5
    shapes.read(box, included).get
  }, cut = false)

  private def discover(state: TrackedPools, previous: Option[ArbIndex] = None,
                       stands: PoolDeployment => Boolean = _ => true): ArbIndex =
    nodeContext.getClient.execute(ctx => ArbDiscovery.index(ctx, state, previous, BatchingConfig.Default,
      Set(id(4)), stands))

  "Discovery" should "index tracked ErgoDEX and LithosDex pools, less old and denied ones" in {
    val found = discover(tracked)
    (found.height, found.headerId) shouldBe (tip -> "h")
    found.pools.keySet shouldBe Set(id(1), id(2), id(5), CanonicalNft, litRsnPool.assets.head.tokenId)
    found.pools.values.map(_.pool.kind).toSet shouldBe Set(ArbPoolKind.ErgoDexNative, ArbPoolKind.LithosDexNative,
      ArbPoolKind.LithosDexToken)
    found.pools(CanonicalNft).box.boxId shouldBe boxes("canonical").boxId
    // The pair both ways, and the triangle through the LIT hub both ways
    found.routes.map(_.hops.size).sorted shouldBe Vector(2, 2, 3, 3)
    found.cut shouldBe false
  }

  it should "leave out a LithosDex pool whose vault does not stand, and read a standing vault once" in {
    var reads = 0
    val noCanonical: PoolDeployment => Boolean = {
      case PoolDeployment.LithosDexNative(_) => reads += 1; false
      case _ => reads += 1; true
    }
    val first = discover(tracked, stands = noCanonical)
    first.parsed.keySet should not contain CanonicalNft
    // Parsed, though without ERG:LIT no cycle passes through it
    first.parsed.keySet should contain(litRsnPool.assets.head.tokenId)
    reads shouldBe 2
    // The token pool's vault was kept as standing; the canonical one is asked again
    val second = discover(tracked, Some(first), noCanonical)
    reads shouldBe 3
    // The token pool's box moves, so it is parsed again, but its vault is not read again
    val rsnNft = litRsnPool.assets.head.tokenId
    val movedRsn = shapes.read(litRsnPool, tip - 5).get.copy(box = litRsnPool.copy(boxId = "aa" * 32))
    discover(TrackedPools.seeded(tip, "h", tracked.boxes.values.filterNot(_.nft == rsnNft).toSeq :+ movedRsn,
      cut = false), Some(second), noCanonical)
    reads shouldBe 4
    withClue("a vault read that throws leaves its pool out without failing discovery: ") {
      // Without the LIT hub the RSN pool closes no cycle, so only the pair is left
      discover(tracked, stands = _ => throw new RuntimeException("node down")).pools.keySet shouldBe Set(id(1), id(2))
    }
  }

  it should "read at most a few new vaults per build" in {
    // Token pools under made-up deployments, each its own vault; the vault reads come before any box is parsed
    val prov = ErgoId.create(id(900))
    val pools = (1 to ArbDiscovery.MaxVaultReads + 3).map { i =>
      val nft = id(800 + i)
      TrackedBox(litRsnPool.copy(boxId = id(700 + i),
        assets = litRsnPool.assets.updated(0, litRsnPool.assets.head.copy(tokenId = nft))), tip - 5,
        PoolDeployment.LithosDexToken(LDT2TContracts(ErgoId.create(nft), ErgoId.create(id(600 + i)), prov)))
    }
    var reads = 0
    discover(TrackedPools.seeded(tip, "h", pools, cut = false), stands = _ => { reads += 1; false })
    reads shouldBe ArbDiscovery.MaxVaultReads
  }

  it should "keep an unmoved pool's parsed state and the routes, and re-read only a pool that moved" in {
    val first = discover(tracked)
    val quiet = tracked.applied(tip + 1, "h1", "h", Seq.empty, shapes, 1000)
    val same = discover(quiet, Some(first))
    same.routes should be theSameInstanceAs first.routes
    same.pools(id(1)) should be theSameInstanceAs first.pools(id(1))
    same.headerId shouldBe "h1"

    val moved = nodeContext.getClient.execute(ctx => nativePool(ctx, id(1), id(101), 1010 * Erg, 990000000000L))
    val traded = quiet.applied(tip + 2, "h2", "h1", Seq(tx("t", Seq(boxes("cheap").boxId), Seq(moved))), shapes, 1000)
    val next = discover(traded, Some(same))
    next.routes should be theSameInstanceAs first.routes
    next.pools(id(1)).box.boxId shouldBe moved.boxId
    next.pools(id(2)) should be theSameInstanceAs first.pools(id(2))

    withClue("a new pool changes the set, so the routes are enumerated again: ") {
      val added = nodeContext.getClient.execute(ctx => nativePool(ctx, id(6), Rsn, 3000 * Erg, 10000L))
      val grown = traded.applied(tip + 3, "h3", "h2", Seq(tx("t2", Seq.empty, Seq(added))), shapes, 1000)
      val wider = discover(grown, Some(next))
      wider.routes should not be theSameInstanceAs(first.routes)
      wider.pools.keySet should contain(id(6))
    }
  }
}
