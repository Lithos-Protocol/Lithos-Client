package transactions.engine.wallet

import akka.actor.Props
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.NodeContext
import node.MutationConversions._
import node.model.{ConfirmationRange, IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection, WalletBox}
import node.NodeApi
import org.ergoplatform.appkit.Parameters
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.engine.wallet.EngineWalletState.planRewardChunks
import transactions.engine.wallet.EngineWalletState
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.InputUTXO

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

/**
 * The reward sweep's packing rule and its lease discipline.
 *
 * The consolidation rule: dust is never dropped for being small - the sweep exists to consolidate
 * it - so every box handed in comes back out, ordered smallest first, in batches no larger than the
 * transaction input cap.
 *
 * The lease discipline: a batch that fails to BUILD after its send-boundary acknowledgement must be
 * CANCELLED, never released - the lease is Submitting there, which ReleaseInputs would not touch,
 * and Submitting has no TTL, so the wrong ending strands the boxes until restart. The full
 * broadcast is not exercised offline (OFFCHAIN-TESTS 1.1: the mocked client answers only what
 * opening a context needs), so what is pinned here is everything up to that boundary.
 */
object RewardSweepSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s").withFallback(com.typesafe.config.ConfigFactory.load())
}

class RewardSweepSpec extends TestKit(akka.actor.ActorSystem("reward-sweep-spec", RewardSweepSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Fee = Parameters.MinFee

  // ---- planner ----

  behavior.of("planRewardChunks")

  private def box(value: Long): InputUTXO = {
    val (_, _, wallet) = FakeNodeContext(mock(classOf[NodeApi]), numAddresses = 1)
    FakeNodeContext.offlineClient().execute { ctx =>
      support.CanonicalNodeBox(boxId = f"$value%064x", transactionId = "cc" * 32, value = value, index = 0,
        creationHeight = 1, ergoTree = wallet.contract.ergoTreeHex, assets = Seq.empty)
        .toInputUTXO(ctx)
    }
  }

  it should "keep every box - nothing dropped for being small" in {
    // 0.00001..0.004 ERG: every one of these is dust against one network fee.
    val boxes = (1L to 40L).map(v => box(v * 10000L))
    val chunks = planRewardChunks(boxes, Fee, maxInputs = 75)
    chunks.flatten.map(_.value).sorted shouldEqual boxes.map(_.value).sorted
  }

  it should "order batches smallest-first so dust consolidates early" in {
    val boxes = Seq(3000000000L, 1000L, 2000000000L, 5000L).map(box)
    val chunks = planRewardChunks(boxes, Fee, maxInputs = 75)
    val sums = chunks.map(_.map(_.value).sum)
    sums shouldEqual sums.sorted
  }

  it should "never exceed the input cap per batch" in {
    val boxes = (1L to 200L).map(box)
    planRewardChunks(boxes, Fee, maxInputs = 75).foreach(_.size should be <= 75)
  }

  it should "return empty for nothing unlocked" in {
    planRewardChunks(Seq.empty, Fee, maxInputs = 75) shouldBe empty
  }

  // ---- reservation lifecycle through the actor ----

  private class TestableEngineWalletState(ctx: NodeContext) extends EngineWalletState(ctx) {
    override def receive: Receive = ({
      case state.synchronization.CompleteMempool.Refresh =>
        sender() ! state.synchronization.CompleteMempool.Observation(1L,
          Some(state.synchronization.CompleteMempool.Snapshot("aa" * 32, Set.empty, Set.empty, System.nanoTime())), None)
    }: Receive).orElse(super.receive)
  }

  /** A started manager whose node reports exactly `rewards`, first refresh settled. Mirrors EngineWalletStateSpec's fixture. */
  private def fixtureWithRewards(rewards: Seq[Long], indexed: Boolean = true): (TestProbe, akka.actor.ActorRef) = {
    val api = mock(classOf[NodeApi])
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val master = wallet.rewardTrees.find(_._2 == wallet.prover.getAddress).get._1
    val derived = wallet.rewardTrees.find(_._2 == wallet.p2pk).get._1
    val boxes = rewards.zipWithIndex.map { case (v, i) =>
      IndexedBox(
        box = support.CanonicalNodeBox(boxId = f"$v%064x", transactionId = "bb" * 32, value = v, index = 0,
          creationHeight = 1, ergoTree = if (indexed && i % 2 == 1) derived else master),
        address = "reward", inclusionHeight = 1, globalIndex = v)
    }
    when(api.indexerEnabled).thenReturn(indexed)
    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some("aa" * 32))))
    when(api.boxById(anyString())).thenAnswer(inv => Success(boxes.find(_.box.boxId == inv.getArgument[String](0)).map(_.box)))
    when(api.sendTransaction(anyString())).thenReturn(scala.util.Failure(new RuntimeException("response lost")))
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      val p = inv.getArgument[Paging](1)
      Success(boxes.filter(_.box.ergoTree == master).slice(p.offset, p.offset + p.limit).map { entry =>
        val box = entry.box
        WalletBox(box, "reward", Some(1000), box.transactionId, box.index, Some(1),
          None, None, spent = false, onchain = true, Seq.empty)
      })
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(boxes.filter(_.box.ergoTree == inv.getArgument[String](0))
          .drop(inv.getArgument[Paging](1).offset)
          .take(inv.getArgument[Paging](1).limit))
      }

    val probe = TestProbe()
    val mgr = system.actorOf(Props(new TestableEngineWalletState(ctx)))
    mgr ! RefreshBoxes
    // The refresh runs off the mailbox; settle it before asserting, as EngineWalletStateSpec does.
    Thread.sleep(1200)
    (probe, mgr)
  }
  "An unlocked coinbase set" should "be reported by GetUnlockedRewards and held by a claim's leases" in {
    val (probe, mgr) = fixtureWithRewards(Seq(3000000000L, 3100000000L))

    probe.send(mgr, GetUnlockedRewards)
    val summary = probe.expectMsgType[RewardSummary](20.seconds)
    summary.unlockedBoxes shouldEqual 2
    summary.lockedBoxes shouldEqual 0
    summary.unlockedNanoErgs shouldEqual 6100000000L

    // Both reward scripts must sign. A lost broadcast response keeps their leases owned.
    probe.send(mgr, ClaimUnlockedRewards)
    probe.receiveOne(30.seconds) match {
      case RewardsClaimed(chunks) =>
        chunks should have size 1
        chunks.head.outcome shouldBe "uncertain"
        chunks.head.txId should fullyMatch regex "[0-9a-f]{64}"
      case other                => fail(s"unexpected reply: $other")
    }

    probe.send(mgr, GetUnlockedRewards)
    val held = probe.expectMsgType[RewardSummary](20.seconds)
    held.unlockedBoxes shouldEqual 0
    held.lockedBoxes shouldEqual 0
  }

  it should "claim wallet-reported master rewards without an indexer" in {
    val (probe, mgr) = fixtureWithRewards(Seq(3000000000L), indexed = false)
    probe.send(mgr, GetUnlockedRewards)
    probe.expectMsgType[RewardSummary](20.seconds).unlockedBoxes shouldBe 1
    probe.send(mgr, ClaimUnlockedRewards)
    val result = probe.expectMsgType[RewardsClaimed](30.seconds)
    result.chunks should have size 1
    result.chunks.head.boxes shouldBe 1
    result.chunks.head.outcome shouldBe "uncertain"
    probe.send(mgr, GetUnlockedRewards)
    probe.expectMsgType[RewardSummary](20.seconds).unlockedBoxes shouldBe 0
  }
}
