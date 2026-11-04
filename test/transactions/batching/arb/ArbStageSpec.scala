package transactions.batching.arb

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{ArbConfig, BatchingConfig}
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString, eq => same}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.ergodex.ErgoDexContracts
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestArbTxs}
import transactions.engine.wallet.EngineWalletMessages.RefreshBoxes
import transactions.engine.wallet.EngineWalletState

import scala.concurrent.duration._
import scala.util.Success

object ArbStageSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The arb stage against a mocked node and a real wallet actor holding the stake. These pin that a round is
 * answered with a signed cycle once discovery has read the block being built on, that discovery reads each
 * block once however many rounds ask, that a refresh rebuilding the same cycle is offered again, that a new
 * parent at the same height is read again, and that a round at a height discovery has not reached gets nothing.
 */
class ArbStageSpec extends TestKit(ActorSystem("arb-stage-spec", ArbStageSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  import ArbFixtures._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val tip = 1908200

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = support.CanonicalNodeBox(boxId = "00" * 32, transactionId = f"$value%064x", value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex, assets = Seq.empty),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = f"$value%064x",
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  "The arb stage" should "offer a signed cycle once discovery has read the block, and the same cycle again on a refresh" in {
    val api = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val (cheap, dear) = nodeContext.getClient.execute { ctx =>
      nativePool(ctx, id(1), id(101), 1000 * Erg, 1000000000000L) ->
        nativePool(ctx, id(2), id(101), 1100 * Erg, 1000000000000L)
    }
    val boxes = Seq(walletBox(wallet, 12 * Erg), walletBox(wallet, 15 * Erg))
    when(api.indexerEnabled).thenReturn(false)
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(tip, tip)))
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(boxes) else Success(Seq.empty[WalletBox])
    }
    when(api.boxById(anyString())).thenAnswer { inv =>
      Success(boxes.map(_.box).find(_.boxId == inv.getArgument[String](0)))
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
      val first = inv.getArgument[Paging](1).offset == 0
      if (inv.getArgument[String](0) == ErgoDexContracts.NativePoolErgoTree && first)
        Success(Seq(LDNodeFixtures.indexed(cheap, tip - 5), LDNodeFixtures.indexed(dear, tip - 5)))
      else Success(Seq.empty[IndexedBox])
    }

    val engine = system.actorOf(Props(new EngineWalletState(nodeContext, configs.WalletConfig.Default, 0, 10 * Erg)))
    engine ! RefreshBoxes
    val stage = system.actorOf(Props(new ArbStage(nodeContext, ArbConfig.Default.copy(enabled = true),
      BatchingConfig.Default, Set.empty, () => Seq.empty, engine, useTrueProp = false, buildBudgetMs = 10000L,
      tickMs = 200L)))
    val probe = TestProbe()

    stage ! PrepareBlockTxs(tip + 1, 8)
    var offered = Seq.empty[Seq[String]]
    awaitAssert({
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = false, Seq.empty))
      val reply = probe.expectMsgType[BlockTxsReady]
      reply.blockHeight shouldBe tip + 1
      reply.bundles should not be empty
      offered = reply.bundles.map(_.members.map(_.id))
    }, 30.seconds, 300.millis)
    // A ten percent gap: the stake caps each pass at 10 ERG, so the pair is taken again while it still pays
    offered.foreach(_.size shouldBe 2)
    offered.size should be <= 4

    withClue("a refresh at the same height builds the same cycles, whose stake outputs the wallet already holds: ") {
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = true, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles.map(_.members.map(_.id)) shouldBe offered
    }
    verify(api, times(1)).unspentBoxesByErgoTree(same(ErgoDexContracts.NativePoolErgoTree), any[Paging],
      any[SortDirection], any[MempoolOptions])

    withClue("a new parent at the same height, or a refused package, has the pools read again: ") {
      stage ! CandidateTxsDropped(tip + 1, rebuilding = true)
      awaitAssert(verify(api, times(2)).unspentBoxesByErgoTree(same(ErgoDexContracts.NativePoolErgoTree),
        any[Paging], any[SortDirection], any[MempoolOptions]), 10.seconds, 200.millis)
      stage ! CandidateTxsDropped(tip + 1, rebuilding = false)
      awaitAssert({
        probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = true, Seq.empty))
        probe.expectMsgType[BlockTxsReady].bundles.map(_.members.map(_.id)) shouldBe offered
      }, 10.seconds, 200.millis)
      verify(api, times(2)).unspentBoxesByErgoTree(same(ErgoDexContracts.NativePoolErgoTree), any[Paging],
        any[SortDirection], any[MempoolOptions])
    }

    withClue("a block discovery has not read yet gets no cycles, and costs no scan while the index is behind: ") {
      // Settle: the wallet checks the stake for the new height meanwhile, so only the pools can be what is missing.
      // An empty answer cannot be polled for, and several ticks have to pass to show none scans
      stage ! PrepareBlockTxs(tip + 2, 8)
      Thread.sleep(1500)
      probe.send(stage, RequestArbTxs(tip + 2, 8, refresh = false, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
      verify(api, times(2)).unspentBoxesByErgoTree(same(ErgoDexContracts.NativePoolErgoTree), any[Paging],
        any[SortDirection], any[MempoolOptions])
    }
  }

  it should "throw away a discovery that started before a rebuilding drop, and read the pools again" in {
    val api = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)
    val (cheap, dear) = nodeContext.getClient.execute { ctx =>
      nativePool(ctx, id(1), id(101), 1000 * Erg, 1000000000000L) ->
        nativePool(ctx, id(2), id(101), 1100 * Erg, 1000000000000L)
    }
    // The first scan waits until released, so the drop lands while that discovery is out
    val gate = new java.util.concurrent.CountDownLatch(1)
    val scans = new java.util.concurrent.atomic.AtomicInteger(0)
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(tip, tip)))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
      if (inv.getArgument[String](0) == ErgoDexContracts.NativePoolErgoTree && inv.getArgument[Paging](1).offset == 0) {
        if (scans.incrementAndGet() == 1) gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        Success(Seq(LDNodeFixtures.indexed(cheap, tip - 5), LDNodeFixtures.indexed(dear, tip - 5)))
      } else Success(Seq.empty[IndexedBox])
    }
    val stage = system.actorOf(Props(new ArbStage(nodeContext, ArbConfig.Default.copy(enabled = true),
      BatchingConfig.Default, Set.empty, () => Seq.empty, TestProbe().ref, useTrueProp = false, buildBudgetMs = 10000L,
      tickMs = 200L)))
    stage ! PrepareBlockTxs(tip + 1, 8)
    awaitAssert(scans.get shouldBe 1, 10.seconds, 100.millis)
    stage ! CandidateTxsDropped(tip + 1, rebuilding = true)
    gate.countDown()
    awaitAssert(scans.get shouldBe 2, 10.seconds, 100.millis)
  }
}
