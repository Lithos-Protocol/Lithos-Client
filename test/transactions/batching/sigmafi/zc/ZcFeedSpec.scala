package transactions.batching.sigmafi.zc

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.ZcConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import state.messages.BlockInfo
import state.messages.StateFrameMessages.NewBlock
import state.synchronization.{AutoSubscribable, CompleteMempool}
import support.LDNodeFixtures
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** The feed's wiring: it subscribes to the sync, asks for the held mempool observation, and hands both on. */
class ZcFeedSpec extends TestKit(ActorSystem("ZcFeedSpec")) with AnyFlatSpecLike with Matchers with Eventually
  with BeforeAndAfterAll {

  import ZcFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val tip = 1908200

  private def chain(): ScriptedChain = {
    val c = new ScriptedChain
    c.extend(tip - 40, tip)
    c.indexed = tip
    c.scans(LDNodeFixtures.indexed(order(id(1)), tip - 3))
    c
  }

  "The feed" should "subscribe to the sync, ask for the held observation, and pass blocks and observations on" in {
    val c = chain()
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    val (frame, mempool) = (TestProbe(), TestProbe())
    val feed = system.actorOf(Props(new ZcFeed(t, frame.ref, mempool.ref)))
    frame.expectMsg(5.seconds, AutoSubscribable.AutoSubscribe(feed))
    mempool.expectMsg(5.seconds, CompleteMempool.Latest)
    eventually(t.view.map(_.book.height) shouldBe Some(tip))
    c.extend(tip + 1, tip + 1)
    c.block(tip + 1)
    feed ! NewBlock(BlockInfo(c.header(tip + 1).id, tip + 1, Seq.empty, c.header(tip).id))
    eventually(t.view.map(_.book.height) shouldBe Some(tip + 1))
    val spend = nodeTx("a0" * 32, Seq(id(1)), Seq(wallet(id(2))))
    feed ! CompleteMempool.Observation(1L, Some(snapshot(c.header(tip + 1).id, spend)), None)
    eventually(t.view.flatMap(_.spentBy(id(1))) shouldBe Some(spend.id))
    system.stop(feed)
  }

  it should "send nothing and start nothing when tracking is off" in {
    val c = chain()
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = false, 1000))
    val (frame, mempool) = (TestProbe(), TestProbe())
    val feed = system.actorOf(Props(new ZcFeed(t, frame.ref, mempool.ref)))
    frame.expectNoMessage(3.seconds)
    mempool.expectNoMessage(100.millis)
    t.view shouldBe None
    c.scanPages.get shouldBe 0
    system.stop(feed)
  }
}
