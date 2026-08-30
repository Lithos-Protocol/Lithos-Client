package state.synchronization

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.Rollup
import node.MutationConversions._
import node.model.NodeBox
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolChain, MempoolSnapshot, MempoolTransform}
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollup, GetCurrentRollupCritical}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{DictionaryLoadFailed, DictionaryLoaded,
  MaterializationPriority, MaterializeDictionary, PromoteMaterialization}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import work.lithos.mutations.InputUTXO

import scala.concurrent.duration._

/** Multi-root barriers and their concurrency/failure boundaries. */
class ProjectionFanoutSpec extends TestKit(ActorSystem("projection-fanout"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100

  "SyncHandler projection fanout" should
    "deduplicate every cold root across concurrent full-view consumers" in {
      val f = fixture(rollupCount = 16)
      val first = TestProbe()
      val second = TestProbe()

      first.send(f.handler, GetSynced)
      second.send(f.handler, GetSynced)
      val loads = receiveLoads(f.snapshots, f.rollups.size)
      loads.map(_.source.expectedDigest).toSet should have size f.rollups.size
      all(loads.map(_.priority)) shouldEqual MaterializationPriority.Normal
      f.snapshots.expectNoMessage(150.millis)

      loads.foreach(load => f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
        load.requestToken, f.byDigest(load.source.expectedDigest).dictionary)))

      val firstView = first.expectMsgType[FullSync]
      val secondView = second.expectMsgType[FullSync]
      firstView.rollups should have size f.rollups.size
      secondView.rollups shouldEqual firstView.rollups
      firstView.projections.keySet shouldEqual f.rollups.map(_.blockId).toSet
      secondView.projections shouldEqual firstView.projections
    }

  it should "let an independent consumer finish without waiting for the full-view barrier" in {
    val f = fixture(rollupCount = 12)
    val fullView = TestProbe()
    val oneRollup = TestProbe()

    fullView.send(f.handler, GetSynced)
    val loads = receiveLoads(f.snapshots, f.rollups.size)
    val selected = f.rollups(5)
    val selectedLoad = loads.find(_.source.expectedDigest ==
      selected.metadata.dictionaryDigest).get

    oneRollup.send(f.handler, GetCurrentRollup(selected.blockId))
    f.snapshots.expectNoMessage(150.millis)
    f.snapshots.reply(DictionaryLoaded(selectedLoad.source.expectedDigest,
      selectedLoad.requestToken, selected.dictionary))

    oneRollup.expectMsgType[CurrentRollup].rollup.blockId shouldEqual selected.blockId
    fullView.expectNoMessage(150.millis)

    loads.filterNot(_ == selectedLoad).foreach { load =>
      f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken,
        f.byDigest(load.source.expectedDigest).dictionary))
    }
    fullView.expectMsgType[FullSync].projections should have size f.rollups.size
  }

  it should "keep public single-rollup reads normal" in {
    val f = fixture(rollupCount = 2)
    val public = TestProbe()
    val selected = f.rollups.head

    public.send(f.handler, GetCurrentRollup(selected.blockId))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    load.priority shouldEqual MaterializationPriority.Normal
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken,
      selected.dictionary))
    public.expectMsgType[CurrentRollup].rollup.blockId shouldEqual selected.blockId
  }

  it should "promote a deduplicated bulk load for an internal critical consumer" in {
    val f = fixture(rollupCount = 4)
    val fullView = TestProbe()
    val critical = TestProbe()

    fullView.send(f.handler, GetSynced)
    val loads = receiveLoads(f.snapshots, f.rollups.size)
    all(loads.map(_.priority)) shouldEqual MaterializationPriority.Normal
    val selected = f.rollups(2)
    val selectedLoad = loads.find(_.source.expectedDigest ==
      selected.metadata.dictionaryDigest).get

    critical.send(f.handler, GetCurrentRollupCritical(selected.blockId))
    f.snapshots.expectMsg(PromoteMaterialization(selectedLoad.requestToken))
    f.snapshots.expectNoMessage(150.millis)
    f.snapshots.reply(DictionaryLoaded(selectedLoad.source.expectedDigest,
      selectedLoad.requestToken, selected.dictionary))
    critical.expectMsgType[CurrentRollup].rollup.blockId shouldEqual selected.blockId

    loads.filterNot(_ == selectedLoad).foreach { load =>
      f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken,
        f.byDigest(load.source.expectedDigest).dictionary))
    }
    fullView.expectMsgType[FullSync].projections should have size f.rollups.size
  }

  it should "finish against the newest revision without repeating cached cold loads" in {
    val f = fixture(rollupCount = 10, cacheEntries = 32)
    val requester = TestProbe()

    requester.send(f.handler, GetSynced)
    val loads = receiveLoads(f.snapshots, f.rollups.size)
    val newest = snapshot(f, revision = 2L, outputOffset = 900000)
    f.handler ! newest
    loads.foreach(load => f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
      load.requestToken, f.byDigest(load.source.expectedDigest).dictionary)))

    val view = requester.expectMsgType[FullSync]
    f.snapshots.expectNoMessage(150.millis)
    view.projections.keySet shouldEqual f.rollups.map(_.blockId).toSet
    view.projections.foreach { case (blockId, projection) =>
      val tree = f.rollups.find(_.blockId == blockId).get
      projection.asInput.id.toString shouldEqual newest.endInputs(tree.utxoId).id.toString
    }
  }

  it should "reuse unchanged projections after their confirmed dictionaries leave the cache" in {
    val f = fixture(rollupCount = 8, cacheEntries = 2)
    val first = TestProbe()
    val repeated = TestProbe()

    first.send(f.handler, GetSynced)
    val loads = receiveLoads(f.snapshots, f.rollups.size)
    loads.foreach(load => f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest,
      load.requestToken, f.byDigest(load.source.expectedDigest).dictionary)))
    val firstView = first.expectMsgType[FullSync]
    firstView.projections should have size f.rollups.size

    repeated.send(f.handler, GetSynced)
    f.snapshots.expectNoMessage(250.millis)
    repeated.expectMsgType[FullSync].projections shouldEqual firstView.projections
  }

  it should "fail only the dependent full-view operation when one cold root is unavailable" in {
    val f = fixture(rollupCount = 8)
    val fullView = TestProbe()
    val independent = TestProbe()

    fullView.send(f.handler, GetSynced)
    val loads = receiveLoads(f.snapshots, f.rollups.size)
    val failed = loads.head
    val healthy = loads(1)
    f.snapshots.reply(DictionaryLoadFailed(failed.source.expectedDigest, failed.requestToken,
      "injected unavailable root"))
    fullView.expectMsgType[SyncUnavailable].status.asInstanceOf[Stale].reason should
      include("injected unavailable root")

    independent.send(f.handler, GetCurrentRollup(
      f.byDigest(healthy.source.expectedDigest).blockId))
    f.snapshots.reply(DictionaryLoaded(healthy.source.expectedDigest, healthy.requestToken,
      f.byDigest(healthy.source.expectedDigest).dictionary))
    independent.expectMsgType[CurrentRollup]

    val untouched = loads.drop(2).head
    f.snapshots.reply(DictionaryLoaded(untouched.source.expectedDigest, untouched.requestToken,
      f.byDigest(untouched.source.expectedDigest).dictionary))
    f.requester.send(f.handler, GetSyncStatus)
    f.requester.expectMsgType[Ready]
  }

  private final case class Fixture(handler: ActorRef,
                                   snapshots: TestProbe,
                                   requester: TestProbe,
                                   rollups: Vector[Rollup],
                                   nodeContext: configs.NodeContext,
                                   ergoTree: String) {
    val byDigest: Map[String, Rollup] =
      rollups.map(rollup => rollup.metadata.dictionaryDigest -> rollup).toMap
  }

  private def fixture(rollupCount: Int, cacheEntries: Int = 64): Fixture = {
    val snapshots = TestProbe()
    val requester = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val rollups = Vector.tabulate(rollupCount) { index =>
      val dictionary = lfsm.states.PlasmaDictionary.empty()
      dictionary.insert(SyncFixtures.plasmaEntries(1, 32 + index).head)
      SyncFixtures.emptyRollup(SyncFixtures.id(700000 + index),
        SyncFixtures.id(710000 + index), startHeight - 1).copy(dictionary = dictionary)
    }
    val seeded = ReducerFixtures.emptyState(startHeight - 1, SyncFixtures.id(startHeight - 1)).copy(
      rollups = rollups.map(tree => tree.blockId -> tree).toMap,
      routes = rollups.map(tree => tree.utxoId -> tree.blockId).toMap,
      rollupOrigins = rollups.zipWithIndex.map { case (tree, index) =>
        tree.blockId -> SyncFixtures.id(720000 + index)
      }.toMap)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.snapshots.enabled = false
         |sync.materializedDictionaryCacheEntries = $cacheEntries
         |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    val built = Fixture(handler, snapshots, requester, rollups, nodeContext,
      wallet.contract.ergoTreeHex)
    handler ! snapshot(built, revision = 1L, outputOffset = 800000)
    requester.send(handler, MarkReady)
    requester.expectMsg(Ready(seeded.cursor))
    built
  }

  private def snapshot(f: Fixture, revision: Long, outputOffset: Int): MempoolSnapshot = {
    val chains = f.rollups.zipWithIndex.map { case (tree, index) =>
      val transform = MempoolTransform(SyncFixtures.rollupTransform(index + 1, startHeight,
        tree.utxoId, SyncFixtures.id(outputOffset + index)).tx)
      tree.utxoId -> MempoolChain(Seq(transform))
    }.toMap
    val endInputs = f.nodeContext.getClient.execute { ctx =>
      f.rollups.zipWithIndex.map { case (tree, index) =>
        val outputId = SyncFixtures.id(outputOffset + index)
        tree.utxoId -> NodeBox(outputId, outputId, 1000000L, 0, startHeight,
          f.ergoTree).toInputUTXO(ctx)
      }.toMap
    }
    MempoolSnapshot(revision, chains, endInputs, Set.empty)
  }

  private def receiveLoads(probe: TestProbe, count: Int): Vector[MaterializeDictionary] =
    Vector.fill(count)(probe.expectMsgType[MaterializeDictionary](3.seconds))
}
