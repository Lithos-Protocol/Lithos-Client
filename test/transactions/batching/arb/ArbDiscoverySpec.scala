package transactions.batching.arb

import api.IndexedPool
import configs.{BatchingConfig, VerifiedPool}
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString, eq => same}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.LoggerFactory
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.ergodex.ErgoDexContracts
import transactions.batching.lithosdex.{DexContracts, LDDeployments}

import scala.util.Success

/**
 * Discovery against a mocked node: ErgoDEX pools from the two script scans, LithosDex pools from the pool
 * index's list with each box read by its NFT. These pin which pools make the index, that a stale or moving
 * index gives none, and that one behind the height asked for costs no scan.
 */
class ArbDiscoverySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbFixtures._

  private val tip = 1908200
  private val logger = LoggerFactory.getLogger("ArbDiscoverySpec")

  private def ldPool(nft: String, box: NodeBox, n2t: Option[lithosdex.contracts.LDContracts],
                     t2t: Option[lithosdex.contracts.LDT2TContracts]): IndexedPool =
    IndexedPool(if (n2t.isDefined) VerifiedPool.Kind.N2T else VerifiedPool.Kind.T2T, nft, box.boxId, tip - 3,
      None, "", 0L, 0L, 0L, 0L, 0L, Seq.empty, 0L, "", "", verified = false, None, n2t, t2t)

  private case class Node(api: NodeApi, ldPools: Seq[IndexedPool], boxes: Map[String, NodeBox])

  /**
   * Two ErgoDEX pools of one token, one too old and one denied; an ErgoDEX RSN pool; the canonical ERG:LIT pool
   * and the LIT/RSN token pool, each listed with its deployment.
   */
  private def node(heights: Seq[BlockchainIndexHeight] = Seq(BlockchainIndexHeight(tip, tip))): Node = {
    val api = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)
    nodeContext.getClient.execute { ctx =>
      val boxes = Map(
        "cheap" -> nativePool(ctx, id(1), id(101), 1000 * Erg, 1000000000000L),
        "dear" -> nativePool(ctx, id(2), id(101), 1100 * Erg, 1000000000000L),
        "old" -> nativePool(ctx, id(3), id(101), 1200 * Erg, 1000000000000L),
        "denied" -> nativePool(ctx, id(4), id(101), 1300 * Erg, 1000000000000L),
        "rsn" -> nativePool(ctx, id(5), Rsn, 2000 * Erg, 10000L),
        "canonical" -> canonicalPool(ctx, 100000 * Erg, 10000000000000L),
        "litRsn" -> litRsnPool)
      val height = heights.iterator
      var last = heights.head
      when(api.indexedHeight()).thenAnswer(_ => { if (height.hasNext) last = height.next(); Success(last) })
      when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
        val first = inv.getArgument[Paging](1).offset == 0
        if (inv.getArgument[String](0) == ErgoDexContracts.NativePoolErgoTree && first)
          Success(Seq(LDNodeFixtures.indexed(boxes("cheap"), tip - 5), LDNodeFixtures.indexed(boxes("dear"), tip - 5),
            LDNodeFixtures.indexed(boxes("old"), tip - BatchingConfig.Default.maxPoolAgeBlocks - 1),
            LDNodeFixtures.indexed(boxes("denied"), tip - 5), LDNodeFixtures.indexed(boxes("rsn"), tip - 5)))
        else Success(Seq.empty[IndexedBox])
      }
      when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
        val nft = inv.getArgument[String](0)
        Success(boxes.values.filter(_.assets.headOption.exists(_.tokenId == nft)).map(LDNodeFixtures.indexed(_, tip - 3)).toSeq)
      }
      val t2t = LDDeployments.verifyT2T(litRsnPool.ergoTree, litRsnPool.assets.map(a => a.tokenId -> a.amount))
      Node(api, Seq(ldPool(CanonicalNft, boxes("canonical"), Some(DexContracts(ctx)), None),
        ldPool(litRsnPool.assets.head.tokenId, litRsnPool, None, t2t)), boxes)
    }
  }

  private def discover(n: Node, atLeast: Int = tip, ldPools: Option[Seq[IndexedPool]] = None): Option[ArbIndex] = {
    val (nodeContext, _, _) = FakeNodeContext(n.api, numAddresses = 1)
    nodeContext.getClient.execute(ctx => ArbDiscovery.discover(n.api, ctx, BatchingConfig.Default,
      Set(id(4)), ldPools.getOrElse(n.ldPools), atLeast, logger))
  }

  "Discovery" should "index ErgoDEX pools from the scans and LithosDex pools by their NFT, less old and denied pools" in {
    val n = node()
    val found = discover(n).get
    found.height shouldBe tip
    found.pools.keySet shouldBe Set(id(1), id(2), id(5), CanonicalNft, litRsnPool.assets.head.tokenId)
    found.pools.values.map(_.pool.kind).toSet shouldBe Set(ArbPoolKind.ErgoDexNative, ArbPoolKind.LithosDexNative,
      ArbPoolKind.LithosDexToken)
    found.pools(CanonicalNft).box.boxId shouldBe n.boxes("canonical").boxId
    // The pair both ways, and the triangle through the LIT hub both ways
    found.routes.map(_.hops.size).sorted shouldBe Vector(2, 2, 3, 3)
    found.cut shouldBe false
  }

  it should "leave out a LithosDex pool whose box is not at its deployment's tree" in {
    val n = node()
    val misfiled = n.ldPools.map(p => if (p.nft == CanonicalNft) p.copy(n2t = None, t2t = n.ldPools(1).t2t) else p)
    discover(n, ldPools = Some(misfiled)).get.pools.keySet should not contain CanonicalNft
  }

  it should "give nothing when the index moved while it read, or is behind the chain" in {
    discover(node(Seq(BlockchainIndexHeight(tip, tip), BlockchainIndexHeight(tip + 1, tip + 1)))) shouldBe None
    val behind = node(Seq(BlockchainIndexHeight(tip - 1, tip)))
    discover(behind) shouldBe None
    verify(behind.api, never()).unspentBoxesByErgoTree(same(ErgoDexContracts.NativePoolErgoTree), any[Paging],
      any[SortDirection], any[MempoolOptions])
  }

  it should "cost no scan while the index is below the height asked for" in {
    val n = node()
    discover(n, atLeast = tip + 1) shouldBe None
    verify(n.api, never()).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
    verify(n.api, never()).unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
  }
}
