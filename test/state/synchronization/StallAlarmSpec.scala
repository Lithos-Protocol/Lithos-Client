package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.AppenderBase
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import play.api.Configuration
import state.messages.StateFrameMessages.{CheckBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures}

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * Retries never stop, so the alarm is the only thing that makes a stuck cursor visible.
 * It is a log line, so this captures the appender rather than asserting on actor messages.
 */
class StallAlarmSpec extends TestKit(ActorSystem("stall-alarm"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "StateFrame" should "report a cursor that has not advanced after the configured attempts" in {
    val captured = new CapturingAppender
    withAppender(captured) {
      val (frame, sync) = started(retriesBeforeAlarm = 3)
      (1 to 3).foreach { _ =>
        val applied = sync.expectMsgType[ApplyBlock].blockInfo
        sync.reply(BlockRejected(None, applied.id, "state invariant failed"))
        sync.expectMsgType[MarkStale]
        frame ! CheckBlock
        sync.expectMsgType[BeginCatchUp]
      }
      // A bare Boolean inside awaitAssert never throws, so the assertion has to be explicit.
      awaitAssert(captured.errors.count(_.contains("has not advanced past height")) shouldBe 1,
        3.seconds, 100.millis)
      frame ! akka.actor.PoisonPill
    }
  }

  /**
   * An index behind the cursor keyed the count at the committed height and a poll failure at the one
   * after it, so a node alternating between them reset the count every other attempt.
   */
  it should "count a lagging index and a failing poll toward the same alarm" in {
    val captured = new CapturingAppender
    withAppender(captured) {
      val (frame, sync) = stalling(retriesBeforeAlarm = 4)
      // Six ticks over the two shapes: under the old keying neither ever reached four in a row.
      (1 to 6).foreach { _ =>
        frame ! CheckBlock
        sync.fishForMessage(3.seconds) { case _: MarkStale => true; case _ => false }
      }
      awaitAssert(captured.errors.exists(_.contains("has not advanced past height")) shouldBe true,
        5.seconds, 100.millis)
      frame ! akka.actor.PoisonPill
    }
  }

  /** A node that alternates between a lagging index and an unreadable one, at a fixed cursor. */
  private def stalling(retriesBeforeAlarm: Int): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val api = ChainFixtures.nodeAt(startHeight + 3)
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    org.mockito.Mockito.doAnswer(new org.mockito.stubbing.Answer[scala.util.Try[node.model.BlockchainIndexHeight]] {
      override def answer(invocation: org.mockito.invocation.InvocationOnMock)
        : scala.util.Try[node.model.BlockchainIndexHeight] =
        if (calls.getAndIncrement() % 2 == 0)
          // Below the committed cursor, which is IndexBehind rather than a fork.
          scala.util.Success(node.model.BlockchainIndexHeight(startHeight - 4, startHeight + 3))
        else scala.util.Failure(new java.net.ConnectException("index unavailable"))
    }).when(api).indexedHeight()

    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.minerDictionary.bootstrap = false
         |sync.retriesBeforeAlarm = $retriesBeforeAlarm
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val restore = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))
    (frame, sync)
  }

  private def withAppender(appender: CapturingAppender)(body: => Unit): Unit = {
    val logger = LoggerFactory.getLogger("StateFrame").asInstanceOf[LogbackLogger]
    appender.setContext(logger.getLoggerContext)
    appender.start()
    logger.addAppender(appender)
    try body finally {
      logger.detachAppender(appender)
      appender.stop()
    }
  }

  private def started(retriesBeforeAlarm: Int): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(
      ChainFixtures.nodeAt(startHeight + 3), numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.retriesBeforeAlarm = $retriesBeforeAlarm
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val restore = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))
    sync.expectMsgType[BeginCatchUp]
    (frame, sync)
  }

  private class CapturingAppender extends AppenderBase[ILoggingEvent] {
    private val captured = mutable.Buffer.empty[String]
    override def append(event: ILoggingEvent): Unit =
      if (event.getLevel == Level.ERROR) synchronized { captured += event.getFormattedMessage }
    def errors: Seq[String] = synchronized(captured.toVector)
  }
}
