package state.synchronization

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import lfsm.states.{AuthenticatedDictionary, AuthenticatedDictionaryView, DictionaryManifestHeader,
  PlasmaDictionary, Rollup}
import node.MutationConversions._
import node.model.NodeBox
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import org.ergoplatform.appkit.ErgoValue
import sigma.AvlTree
import sigma.data.AvlTreeFlags
import state.messages.MempoolMessages.{MempoolChain, MempoolSnapshot, MempoolTransform}
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollup, RollupUnavailable}
import state.messages.SyncMessages._
import state.messages.BlockInfo
import state.persistence.StateSnapshotActor.{DictionaryLoadFailed, DictionaryLoaded,
  MaterializationPriority, MaterializeDictionary}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import work.lithos.mutations.InputUTXO
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.Manifest

import scala.concurrent.Await
import scala.concurrent.duration._

/** Concurrency and invalidation boundaries around the bounded materialized-dictionary cache. */
class LazyMaterializationSpec extends TestKit(ActorSystem("lazy-materialization"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100

  private final case class Fixture(handler: ActorRef,
                                   snapshots: TestProbe,
                                   requester: TestProbe,
                                   first: Rollup,
                                   second: Rollup,
                                   nodeContext: configs.NodeContext,
                                   protocol: SyncProtocolContext)

  "SyncHandler lazy materialization" should "deduplicate concurrent requests for one digest" in {
    val f = fixture()
    val firstRequester = TestProbe()
    val secondRequester = TestProbe()

    firstRequester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    load.priority shouldEqual MaterializationPriority.Normal
    secondRequester.send(f.handler, GetCurrentRollup(f.first.blockId))
    f.snapshots.expectNoMessage(150.millis)

    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, f.first.dictionary))
    firstRequester.expectMsgType[CurrentRollup].rollup.blockId shouldEqual f.first.blockId
    secondRequester.expectMsgType[CurrentRollup].rollup.blockId shouldEqual f.first.blockId
  }

  it should "let an ask time out and satisfy its retry from the original cold load" in {
    val f = fixture()
    val first = {
      implicit val timeout: Timeout = Timeout(100.millis)
      f.handler ? GetCurrentRollup(f.first.blockId)
    }
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    intercept[akka.pattern.AskTimeoutException](Await.result(first, 1.second))

    val retry = {
      implicit val timeout: Timeout = Timeout(2.seconds)
      f.handler ? GetCurrentRollup(f.first.blockId)
    }
    f.snapshots.expectNoMessage(150.millis)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken,
      f.first.dictionary))

    Await.result(retry, 2.seconds).asInstanceOf[CurrentRollup].rollup.blockId shouldEqual
      f.first.blockId
    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    f.requester.expectMsgType[CurrentRollup]
    f.snapshots.expectNoMessage(150.millis)
  }

  it should "evict the least-recent digest when its entry bound is reached" in {
    val f = fixture(cacheEntries = 1)

    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val firstLoad = f.snapshots.expectMsgType[MaterializeDictionary]
    f.snapshots.reply(DictionaryLoaded(firstLoad.source.expectedDigest, firstLoad.requestToken,
      f.first.dictionary))
    f.requester.expectMsgType[CurrentRollup]

    f.requester.send(f.handler, GetCurrentRollup(f.second.blockId))
    val secondLoad = f.snapshots.expectMsgType[MaterializeDictionary]
    f.snapshots.reply(DictionaryLoaded(secondLoad.source.expectedDigest, secondLoad.requestToken,
      f.second.dictionary))
    f.requester.expectMsgType[CurrentRollup]

    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    f.snapshots.expectMsgType[MaterializeDictionary].source.expectedDigest shouldEqual
      f.first.metadata.dictionaryDigest
  }

  it should "refuse a mismatched dictionary and surface storage failure without caching either" in {
    val f = fixture()
    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    val wrong = PlasmaDictionary.empty()
    wrong.insert(SyncFixtures.plasmaEntries(1, 32).head)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, wrong))
    f.requester.expectMsgType[RollupUnavailable].reason should include("materializer returned")

    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val retry = f.snapshots.expectMsgType[MaterializeDictionary]
    f.snapshots.reply(DictionaryLoadFailed(retry.source.expectedDigest, retry.requestToken, "disk unavailable"))
    f.requester.expectMsgType[RollupUnavailable].reason should include("disk unavailable")
  }

  it should "rebuild against a mempool revision that changes during a cold load" in {
    val f = fixture()
    val firstTransform = MempoolTransform(SyncFixtures.rollupTransform(
      1, startHeight, f.first.utxoId, SyncFixtures.id(7201)).tx)
    val secondTransform = MempoolTransform(SyncFixtures.rollupTransform(
      2, startHeight, f.first.utxoId, SyncFixtures.id(7202)).tx)
    val firstEnd = input(f, 7301)
    val secondEnd = input(f, 7302)
    f.handler ! MempoolSnapshot(1L,
      Map(f.first.utxoId -> MempoolChain(Seq(firstTransform))),
      Map(f.first.utxoId -> firstEnd), Set.empty)

    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    f.handler ! MempoolSnapshot(2L,
      Map(f.first.utxoId -> MempoolChain(Seq(secondTransform))),
      Map(f.first.utxoId -> secondEnd), Set.empty)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, f.first.dictionary))

    val current = f.requester.expectMsgType[CurrentRollup]
    current.mempoolState.map(_.asInput.id.toString) shouldEqual Some(secondEnd.id.toString)
    f.snapshots.expectNoMessage(150.millis)
  }

  it should "discard a completion when committed metadata changes during its load" in {
    val f = fixture()
    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    f.handler ! state.messages.RollupMessages.UpdateEvaluation(f.first.blockId)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, f.first.dictionary))

    f.requester.expectMsgType[RollupUnavailable].reason should include(
      "committed state changed during dictionary materialization")
  }

  it should "load only the dictionary a block can touch" in {
    val f = fixture()
    val tx = SyncFixtures.rollupTransform(3, startHeight, f.first.utxoId, SyncFixtures.id(7401)).tx
    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq(tx),
      SyncFixtures.id(startHeight - 1))

    f.requester.send(f.handler, ApplyBlock(block))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    load.priority shouldEqual MaterializationPriority.Critical
    load.source.expectedDigest shouldEqual f.first.metadata.dictionaryDigest
    f.snapshots.expectNoMessage(150.millis)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, f.first.dictionary))
    f.requester.expectMsgType[BlockCommitted].cursor shouldEqual
      SyncCursor(startHeight, block.id, block.parentId)

    f.requester.send(f.handler, GetCommittedState)
    f.requester.expectMsgType[CommittedState].state.rollups should contain key f.second.blockId
  }

  it should "apply a reducer-valid transition after loading only its dictionary" in {
    val f = fixture()
    val (entryKey, value) = SyncFixtures.plasmaEntries(1, 32).head
    val expectedDictionary = f.first.dictionary.copy()
    val insertion = expectedDictionary.insert(entryKey -> value)
    val submittedScore = BigInt(com.google.common.primitives.Longs.fromByteArray(value.take(8)))
    val outputId = SyncFixtures.id(7451)
    val tx = ReducerFixtures.submissionTx(45, f.first.utxoId, outputId, startHeight,
      entryKey, value, insertion.proof.ergoValue.toHex, expectedDictionary,
      numMiners = 1, totalScore = submittedScore, period = f.first.currentPeriod.get)
    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq(tx),
      SyncFixtures.id(startHeight - 1))

    f.requester.send(f.handler, ApplyBlock(block))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    load.source.expectedDigest shouldEqual f.first.metadata.dictionaryDigest
    f.snapshots.expectNoMessage(150.millis)
    f.snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken,
      f.first.dictionary))

    f.requester.expectMsgType[BlockCommitted].cursor shouldEqual
      SyncCursor(startHeight, block.id, block.parentId)
    f.requester.send(f.handler, GetCommittedState)
    val state = f.requester.expectMsgType[CommittedState].state
    state.quarantined shouldBe empty
    state.rollups.keySet shouldEqual Set(f.first.blockId, f.second.blockId)
    state.rollups(f.first.blockId).utxoId shouldEqual outputId
    state.rollups(f.first.blockId).metadata.dictionaryDigest shouldEqual
      org.bouncycastle.util.encoders.Hex.toHexString(expectedDictionary.digest)
    state.routes should contain(outputId -> f.first.blockId)
    state.routes should not contain key(f.first.utxoId)
  }

  it should "commit a block after quarantining only the rollup whose cold load failed" in {
    val f = fixture()
    val tx = SyncFixtures.rollupTransform(4, startHeight, f.first.utxoId, SyncFixtures.id(7501)).tx
    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq(tx),
      SyncFixtures.id(startHeight - 1))

    f.requester.send(f.handler, ApplyBlock(block))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    f.snapshots.reply(DictionaryLoadFailed(load.source.expectedDigest, load.requestToken,
      "injected materialization timeout"))

    f.requester.expectMsgType[BlockCommitted].cursor shouldEqual
      SyncCursor(startHeight, block.id, block.parentId)
    f.requester.send(f.handler, GetCommittedState)
    val state = f.requester.expectMsgType[CommittedState].state
    state.rollups should not contain key(f.first.blockId)
    state.quarantined(f.first.blockId).reason should include("injected materialization timeout")
    state.rollups should contain key f.second.blockId
  }

  it should "serve an oversized dictionary once while bypassing cache retention" in {
    val f = fixture()
    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    val firstLoad = f.snapshots.expectMsgType[MaterializeDictionary]
    val oversized = new WeightedDictionary(f.first.dictionary,
      SyncHandler.MaxMaterializedDictionaryCacheBytes + 1L)
    f.snapshots.reply(DictionaryLoaded(firstLoad.source.expectedDigest, firstLoad.requestToken,
      oversized))
    f.requester.expectMsgType[CurrentRollup].rollup.blockId shouldEqual f.first.blockId

    f.requester.send(f.handler, GetCurrentRollup(f.first.blockId))
    f.snapshots.expectMsgType[MaterializeDictionary].source.expectedDigest shouldEqual
      f.first.metadata.dictionaryDigest
  }

  it should "commit a block after faulting only a Miner Dictionary whose cold load failed" in {
    val f = fixture()
    val base = ReducerFixtures.emptyState(startHeight - 1, SyncFixtures.id(startHeight - 1))
    val tx = ReducerFixtures.minerAdd(base.minerTree, f.nodeContext.getNodeWallet.contract,
      f.protocol.minerDictionaryToken, SyncFixtures.id(7601), SyncFixtures.id(7602), startHeight)
    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq(tx),
      SyncFixtures.id(startHeight - 1))

    f.requester.send(f.handler, ApplyBlock(block))
    val load = f.snapshots.expectMsgType[MaterializeDictionary]
    f.snapshots.reply(DictionaryLoadFailed(load.source.expectedDigest, load.requestToken,
      "injected Miner Dictionary timeout"))

    f.requester.expectMsgType[BlockCommitted].cursor.height shouldEqual startHeight
    f.requester.send(f.handler, GetCommittedState)
    val state = f.requester.expectMsgType[CommittedState].state
    state.minerDictionaryFault.get should include("injected Miner Dictionary timeout")
    state.rollups.keySet shouldEqual Set(f.first.blockId, f.second.blockId)
  }

  private def fixture(cacheEntries: Int = 4): Fixture = {
    val snapshots = TestProbe()
    val requester = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val firstId = SyncFixtures.id(7001)
    val secondId = SyncFixtures.id(7002)
    val first = SyncFixtures.emptyRollup(firstId, SyncFixtures.id(7011), startHeight - 1)
    val second = SyncFixtures.emptyRollup(secondId, SyncFixtures.id(7012), startHeight - 1)
    val seeded = ReducerFixtures.emptyState(startHeight - 1, SyncFixtures.id(startHeight - 1)).copy(
      rollups = Map(firstId -> first, secondId -> second),
      routes = Map(first.utxoId -> firstId, second.utxoId -> secondId),
      rollupOrigins = Map(firstId -> SyncFixtures.id(7021), secondId -> SyncFixtures.id(7022)))
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.snapshots.enabled = false
         |sync.materializedDictionaryCacheEntries = $cacheEntries
         |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    handler ! MempoolSnapshot(0L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsg(Ready(seeded.cursor))
    Fixture(handler, snapshots, requester, first, second, nodeContext, protocol)
  }

  private def input(f: Fixture, number: Int): InputUTXO =
    f.nodeContext.getClient.execute { ctx =>
      NodeBox(SyncFixtures.id(number), SyncFixtures.id(number), 1000000L, 0, startHeight,
        f.nodeContext.getNodeWallet.contract.ergoTreeHex).toInputUTXO(ctx)
    }

  private final class WeightedDictionary(delegate: AuthenticatedDictionaryView,
                                         weight: Long) extends AuthenticatedDictionaryView {
    override def digest: Array[Byte] = delegate.digest
    override def ergoValue: ErgoValue[AvlTree] = delegate.ergoValue
    override def flags: AvlTreeFlags = delegate.flags
    override def parameters: PlasmaParameters = delegate.parameters
    override def materialized: Boolean = delegate.materialized
    override def estimatedHeapBytes: Long = weight
    override def copy(): AuthenticatedDictionary = delegate.copy()
    override def foreachKey(visit: Array[Byte] => Unit): Unit = delegate.foreachKey(visit)
    override def getManifest(): Manifest = delegate.getManifest()
    override def writeManifest(subtreeDepth: Int)
                              (writeSubtree: (Int, Array[Byte]) => Unit): DictionaryManifestHeader =
      delegate.writeManifest(subtreeDepth)(writeSubtree)
  }
}
