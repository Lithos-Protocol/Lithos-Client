package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.BlockInfo
import state.messages.SyncMessages._
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}

/** Holds the block-height lifecycle for terminal and retry-exhausted quarantine diagnostics. */
class QuarantineExpirySpec extends TestKit(ActorSystem("quarantine-expiry"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100
  private val rollupId = SyncFixtures.id(760001)

  "Quarantine retention" should "derive its deadline from the rollup start and remove it at that height" in {
    val handler = newHandler(retentionBlocks = 3)
    val requester = TestProbe()
    val seed = seeded(QuarantineFault(rollupId, 99, SyncFixtures.id(760002),
      "contract state mismatch", retryable = false))
    restore(requester, handler, seed)

    applyEmpty(requester, handler, 100)
    var fault = current(requester, handler).quarantined(rollupId)
    fault.removalHeight shouldEqual Some(102)
    fault.removalWarningLogged shouldBe false

    applyEmpty(requester, handler, 101)
    fault = current(requester, handler).quarantined(rollupId)
    fault.removalWarningLogged shouldBe true

    applyEmpty(requester, handler, 102)
    current(requester, handler).quarantined shouldBe empty
  }

  it should "drop an already-expired terminal fault on the first catch-up block" in {
    val handler = newHandler(retentionBlocks = 3)
    val requester = TestProbe()
    val seed = seeded(QuarantineFault(rollupId, 80, SyncFixtures.id(760004),
      "old contract state mismatch", retryable = false))
    restore(requester, handler, seed)

    applyEmpty(requester, handler, 100)
    current(requester, handler).quarantined shouldBe empty
  }

  it should "never age a fault while another repair attempt remains" in {
    val handler = newHandler(retentionBlocks = 2)
    val requester = TestProbe()
    val seed = seeded(QuarantineFault(rollupId, 80, SyncFixtures.id(760003),
      "temporary index gap", retryable = true, attempts = 2))
    restore(requester, handler, seed)

    (100 to 104).foreach(height => applyEmpty(requester, handler, height))

    val fault = current(requester, handler).quarantined(rollupId)
    fault.attempts shouldEqual 2
    fault.removalHeight shouldBe empty
    fault.removalWarningLogged shouldBe false
  }

  private def seeded(fault: QuarantineFault): CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(quarantined = Map(fault.rollupId -> fault))

  private def restore(requester: TestProbe,
                      handler: akka.actor.ActorRef,
                      seed: CommittedSyncState): Unit = {
    requester.send(handler, RestoreCommittedState(seed, Vector(seed.cursor)))
    requester.expectMsgType[BlockCommitted]
  }

  private def applyEmpty(requester: TestProbe,
                         handler: akka.actor.ActorRef,
                         height: Int): Unit = {
    val block = BlockInfo(SyncFixtures.id(height), height, Seq.empty, SyncFixtures.id(height - 1))
    requester.send(handler, ApplyBlock(block))
    requester.expectMsgType[BlockCommitted].cursor.height shouldEqual height
  }

  private def current(requester: TestProbe,
                      handler: akka.actor.ActorRef): CommittedSyncState = {
    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state
  }

  private def newHandler(retentionBlocks: Int): akka.actor.ActorRef = {
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.quarantine.repairAttempts = 3
         |sync.quarantine.retentionBlocks = $retentionBlocks
         |sync.snapshots.enabled = false
         |""".stripMargin))
    system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
  }
}
