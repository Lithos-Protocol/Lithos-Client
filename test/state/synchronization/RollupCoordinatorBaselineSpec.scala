package state.synchronization

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import cache.RollupCache
import mutations.NodeWallet
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.messages.MempoolMessages.RebuildMempoolChains
import state.messages.RollupMessages.{Genesis, RollupTransform}
import state.messages.SyncMessages.{CompletedInitSync, FullSync, GetSynced}
import state.synchronization.AutoSubscribable.AutoSubscribe
import support.{FakeCache, FakeNodeContext, SyncFixtures}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

object RollupCoordinatorBaselineSpec {

  private class RecordingChild(cacheApi: SyncCacheApi, observer: ActorRef) extends Actor {
    private val cache = RollupCache(cacheApi)

    override def receive: Receive = {
      case genesis: Genesis =>
        cache.addToTreeCache(genesis.tree.utxoId, genesis.tree)
        observer ! genesis
      case transform: RollupTransform =>
        cache.get(transform.input.id).foreach { current =>
          val next = current.copy(utxoId = transform.output.id)
          cache.updateTreeCache(transform.input.id, transform.output.id, next)
        }
        observer ! transform
    }
  }

  private class RecordingFactory(cacheApi: SyncCacheApi, observer: ActorRef)
    extends RollupSynchronizer.RollupSyncFactory {

    val creations = new AtomicInteger(0)

    override def apply(rollupBlockId: String, prover: NodeWallet): Actor = {
      creations.incrementAndGet()
      new RecordingChild(cacheApi, observer)
    }
  }
}

/** Covers coordinator routing counts and same-child message order on the normal path. */
class RollupCoordinatorBaselineSpec extends TestKit(ActorSystem("rollup-coordinator-baseline"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  import RollupCoordinatorBaselineSpec._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "RollupCoordinator" should "route a 100-transform chain exactly once and in order" in {
    val cacheApi = new FakeCache
    val childObserver = TestProbe()
    val mempoolView = TestProbe()
    val requester = TestProbe()
    val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)
    val factory = new RecordingFactory(cacheApi, childObserver.ref)
    val coordinator = system.actorOf(Props(new RollupCoordinator(
      factory, Configuration.empty, nodeContext, cacheApi, mempoolView.ref)))

    val rollupId = SyncFixtures.id(1)
    val utxoIds = (0 to 100).map(index => SyncFixtures.id(1000 + index))
    val genesis = SyncFixtures.genesis(rollupId, utxoIds.head, height = 500000)
    val transforms = (1 to 100).map { index =>
      SyncFixtures.rollupTransform(
        number = index,
        height = 500000 + index,
        inputId = utxoIds(index - 1),
        outputId = utxoIds(index))
    }

    requester.send(coordinator, genesis)
    childObserver.expectMsg(genesis)
    transforms.foreach(requester.send(coordinator, _))

    val observed = childObserver.receiveN(100, 10.seconds).map(_.asInstanceOf[RollupTransform])
    observed shouldEqual transforms
    observed.map(_.input.id) shouldEqual utxoIds.dropRight(1)
    observed.map(_.output.id) shouldEqual utxoIds.tail
    factory.creations.get() shouldEqual 1

    awaitAssert(RollupCache(cacheApi).getTreeSet shouldEqual Set(utxoIds.last), 3.seconds, 20.millis)
    requester.send(coordinator, CompletedInitSync)
    mempoolView.expectMsg(AutoSubscribe(coordinator))
    mempoolView.expectNoMessage(200.millis)

    requester.send(coordinator, GetSynced)
    val synced = requester.expectMsgType[FullSync]
    synced.rollups should have size 1
    synced.rollups.head._1 shouldEqual utxoIds.last
    synced.rollups.head._2.utxoId shouldEqual utxoIds.last
    mempoolView.expectNoMessage(200.millis)
  }

  it should "forward one mempool rebuild request after initialization" in {
    val cacheApi = new FakeCache
    val childObserver = TestProbe()
    val mempoolView = TestProbe()
    val requester = TestProbe()
    val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)
    val factory = new RecordingFactory(cacheApi, childObserver.ref)
    val coordinator = system.actorOf(Props(new RollupCoordinator(
      factory, Configuration.empty, nodeContext, cacheApi, mempoolView.ref)))
    val genesis = SyncFixtures.genesis(SyncFixtures.id(2), SyncFixtures.id(2000), 600000)

    requester.send(coordinator, genesis)
    childObserver.expectMsg(genesis)
    requester.send(coordinator, CompletedInitSync)
    mempoolView.expectMsg(AutoSubscribe(coordinator))
    requester.send(coordinator, RebuildMempoolChains)
    mempoolView.expectMsg(RebuildMempoolChains)
    mempoolView.expectNoMessage(200.millis)
  }
}
