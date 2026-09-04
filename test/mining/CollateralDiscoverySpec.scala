package mining

import configs.CandidateConfig
import lfsm.{EmissionSchedule, LFSMHelpers}
import mutations.NodeWallet
import node.NodeApi
import node.MutationConversions._
import node.model._
import org.ergoplatform.appkit.{Address, BlockchainContext}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import stratum.CollateralNotFoundException
import support.{CollateralNodeFixtures, FakeNodeContext}

import scala.util.{Failure, Success, Try}

/**
 * What `loadCollateral` sees before it ranks, which is what decides whose collateral box a block pays.
 *
 * The collateral token index is not the active set: it returns the emission singleton and every
 * unconsumed proof-of-spend box alongside live boxes. At a full 100 live boxes one 100-box page
 * cannot hold them all, and the index is read ascending, so the box that falls outside is the newest
 * one — which is exactly where a fresh high bid sits.
 */
class CollateralDiscoverySpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val baseFee = 5000000L
  private val topFee = 9000000L

  /** Distinct lenders, so each live box is a different key and a different box id. */
  private def lenderAt(ctx: BlockchainContext, i: Int): Address =
    ctx.newProverBuilder().withDLogSecret(BigInt(1000 + i).bigInteger).build().getAddress

  private def liveBox(ctx: BlockchainContext, i: Int, fee: Long, at: Int): IndexedBox =
    CollateralNodeFixtures.indexed(
      CollateralNodeFixtures.collateralBox(ctx, lenderAt(ctx, i), carriedLit = 0L,
        index = i % 8, txId = f"$i%064x", feeValue = fee),
      height = at)

  private def emissionCarrier(ctx: BlockchainContext): IndexedBox =
    CollateralNodeFixtures.indexed(CollateralNodeFixtures.emissionBox(ctx), height = 1)

  private def spentCarrier(ctx: BlockchainContext, i: Int): IndexedBox =
    CollateralNodeFixtures.indexed(
      CollateralNodeFixtures.proofOfSpendBox(ctx, Array.fill[Byte](32)(i.toByte),
        index = i % 8, txId = f"${900000 + i}%064x"),
      height = 2)

  /**
   * A node whose token index answers from `pages`, one call per offset.
   *
   * Offsets outside `pages` answer empty, which is what exhaustion looks like; an offset in `failing`
   * answers a failure, which is what a degraded indexer looks like part way through a scan.
   */
  private def nodeOver(pages: Seq[Seq[IndexedBox]], batch: Int,
                       failing: Set[Int] = Set.empty): (NodeApi, () => Int) = {
    val api = mock[NodeApi]
    var calls = 0
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        calls += 1
        val paging = inv.getArgument[Paging](1)
        val index = paging.offset / batch
        if (failing.contains(index)) Failure(new RuntimeException(s"indexer refused page $index"))
        else Success(pages.lift(index).getOrElse(Seq.empty[IndexedBox]))
      }
    (api, () => calls)
  }

  private def builderOver(wallet: NodeWallet, api: NodeApi, batch: Int): CandidateTxBuilder =
    new CandidateTxBuilder(wallet, api, CandidateConfig.Default.copy(collateralPoolSize = batch))

  /** The offline context every property runs inside, with the wallet the builder is constructed on. */
  private def withNode[A](f: (BlockchainContext, NodeWallet) => A): A = {
    val (node, _, wallet) = FakeNodeContext()
    node.getClient.execute(c => f(c, wallet))
  }

  // ─── the whole active set, not one page of it ─────────────────────────────

  "A live box beyond the first page" should "still be ranked" in {
    withNode { (c, wallet) =>
      // Page one is the emission singleton and 99 live boxes at the base bid; the highest bidder is
      // the hundredth live box, which the singleton pushed onto page two.
      val winner = liveBox(c, 99, topFee, at = 500)
      val pageOne = emissionCarrier(c) +: (0 until 99).map(i => liveBox(c, i, baseFee, at = 500))
      val (api, calls) = nodeOver(Seq(pageOne, Seq(winner)), batch = 100)

      val loaded = builderOver(wallet, api, batch = 100).loadCollateral(c)
      loaded.size shouldBe EmissionSchedule.MAX_ACTIVE

      val best = CandidateTxBuilder.bestCandidates(loaded, blockHeight = 520)
      withClue("the highest bid was on page two, so one page of the index cannot rank: ") {
        best.map(_.id) shouldEqual Seq(winner.toInputUTXO(c).id.toString)
      }
      calls() should be > 1
    }
  }

  "An overdue box beyond the first page" should "win regardless of its bid" in {
    withNode { (c, wallet) =>
      // Proof-of-spend boxes take half of page one. The overdue box bids nothing and is oldest, so
      // the age rule has to reach it before the bidding boxes on page one can be compared.
      val pageOne = (0 until 50).map(i => spentCarrier(c, i)) ++
        (0 until 50).map(i => liveBox(c, i, topFee, at = 500))
      val pageTwo = Seq(liveBox(c, 90, baseFee, at = 300))
      val (api, _) = nodeOver(Seq(pageOne, pageTwo), batch = 100)

      val loaded = builderOver(wallet, api, batch = 100).loadCollateral(c)
      loaded.size shouldBe 51

      val best = CandidateTxBuilder.bestCandidates(loaded, blockHeight = 520)
      best.size shouldBe 1
      withClue("age 220 is past the overdue line, so it outranks every bid on page one: ") {
        best.head.inclusionHeight shouldEqual 300
      }
    }
  }

  // ─── the batch size is not the market ─────────────────────────────────────

  "A smaller configured pool size" should "change the query batch and not the winner" in {
    withNode { (c, wallet) =>
      val boxes = (0 until 40).map(i => liveBox(c, i, if (i == 37) topFee else baseFee, at = 500))
      val expected = CandidateTxBuilder.bestCandidates(
        builderOver(wallet, nodeOver(Seq(boxes), batch = 100)._1, batch = 100).loadCollateral(c), 520)

      // 1 is the boundary and the one that matters: `ConfigValidation` permits it, and 40 boxes at a
      // batch of 1 is 40 requests. A scan bounded by pages rather than by boxes truncates here while
      // every larger batch passes.
      Seq(1, 2, 5, 10, 25).foreach { batch =>
        val paged = boxes.grouped(batch).toSeq
        val (api, calls) = nodeOver(paged, batch)
        val loaded = builderOver(wallet, api, batch).loadCollateral(c)

        withClue(s"batch $batch dropped boxes: ") { loaded.size shouldBe boxes.size }
        CandidateTxBuilder.bestCandidates(loaded, 520).map(_.id) shouldEqual expected.map(_.id)
        withClue(s"batch $batch should have taken more than one page: ") {
          calls() should be > 1
        }
      }
    }
  }

  // ─── an incomplete answer is not an answer ────────────────────────────────

  "A page that fails part way through a scan" should "fail the load rather than rank a prefix" in {
    withNode { (c, wallet) =>
      // Ranking the prefix silently pays a different lender. Failing here leaves the last complete
      // set in place, and the next refresh tries again.
      // 99 live boxes and the emission singleton, so the scan has not yet seen the whole active set
      // and must ask for page two.
      val pageOne = emissionCarrier(c) +: (0 until 99).map(i => liveBox(c, i, baseFee, at = 500))
      val (api, _) = nodeOver(Seq(pageOne, Seq(liveBox(c, 100, topFee, at = 500))),
        batch = 100, failing = Set(1))

      val thrown = intercept[CollateralNotFoundException] {
        builderOver(wallet, api, batch = 100).loadCollateral(c)
      }
      thrown.getMessage should include("offset 100")
    }
  }

  "A scan over the protocol's whole carrier population" should "reach the live boxes at the end of it" in {
    withNode { (c, wallet) =>
      // The worst case the protocol can produce: the emission singleton, one unrecycled retirement
      // proof behind each live box, and the full active set. The index is read ascending and the
      // proofs are older, so every live box sits behind 101 carriers that are not part of the market.
      val carriers = (emissionCarrier(c) +: (0 until EmissionSchedule.MAX_ACTIVE).map(spentCarrier(c, _))) ++
        (0 until EmissionSchedule.MAX_ACTIVE).map(i => liveBox(c, i, baseFee, at = 500))
      carriers.size shouldBe CandidateTxBuilder.MaxCollateralCarriers

      val (api, _) = nodeOver(carriers.grouped(20).toSeq, batch = 20)
      builderOver(wallet, api, batch = 20).loadCollateral(c).size shouldBe EmissionSchedule.MAX_ACTIVE
    }
  }

  "A scan that finds the whole active set" should "stop asking the node" in {
    withNode { (c, wallet) =>
      // 100 live boxes is the protocol's cap, so nothing on a later page can outrank what is held.
      val pages = (0 until EmissionSchedule.MAX_ACTIVE).map(i => liveBox(c, i, baseFee, at = 500))
        .grouped(50).toSeq
      val (api, calls) = nodeOver(pages ++ Seq(Seq(liveBox(c, 200, topFee, at = 500))), batch = 50)

      builderOver(wallet, api, batch = 50).loadCollateral(c).size shouldBe EmissionSchedule.MAX_ACTIVE
      calls() shouldEqual 2
    }
  }
}
