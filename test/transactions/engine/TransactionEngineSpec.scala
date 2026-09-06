package transactions.engine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.engine.TransactionEngine._
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class TransactionEngineSpec extends TestKit(ActorSystem("transaction-engine-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val intent = HoldingTransform("ab" * 32, 100L,
    transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE)

  "DEX admission" should "bound strings nested inside typed request models" in {
    DexIntent.fitsBudget(DexIntent.Swap(api.models.LDSwapExecuteRequest("1000000", true, "1"))) shouldBe true
    DexIntent.fitsBudget(DexIntent.Swap(api.models.LDSwapExecuteRequest("1" * 20000, true, "1"))) shouldBe false
  }

  "Engine admission" should "remain responsive and coalesce duplicates while its worker is blocked" in {
    val (ctx, _, _) = FakeNodeContext()
    val entered = TestProbe()
    val replies = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val calls = new AtomicInteger()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val execution = new HoldingTransformExecution(ctx, replies.ref, replies.ref, replies.ref) {
        override def reconcile(): Unit = ()
        override def execute(work: HoldingTransform, alive: () => Boolean): Outcome = {
          calls.incrementAndGet()
          entered.ref ! "entered"
          gate.get(10, TimeUnit.SECONDS)
          Accepted(work.key, "cd" * 32)
        }
      }
    }))
    try {
      replies.send(engine, Submit(intent))
      entered.expectMsg("entered")
      replies.send(engine, Submit(intent))
      replies.send(engine, Submit(intent.copy(blockId = "invalid")))
      replies.expectMsgType[Rejected](1.second)
      gate.complete(())
      replies.expectMsg(Accepted(intent.key, "cd" * 32))
      replies.expectMsg(Accepted(intent.key, "cd" * 32))
      calls.get shouldBe 1
    } finally { gate.complete(()); system.stop(engine) }
  }

  it should "let queued work progress after a slow reconciliation receives another tick" in {
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val execution = new HoldingTransformExecution(ctx, events.ref, events.ref, events.ref) {
        override def reconcile(): Unit = {
          events.ref ! "reconcile"
          gate.get(10, TimeUnit.SECONDS)
        }
        override def execute(work: HoldingTransform, alive: () => Boolean): Outcome = {
          events.ref ! "execute"
          Completed(work.key)
        }
      }
    }))
    try {
      events.expectMsg("reconcile")
      events.send(engine, Submit(intent))
      events.send(engine, Reconcile)
      events.send(engine, Submit(intent.copy(blockId = "invalid")))
      events.expectMsgType[Rejected]
      gate.complete(())
      events.expectMsg("execute")
      events.expectMsg(Completed(intent.key))
    } finally { gate.complete(()); system.stop(engine) }
  }

  "An engine restart" should "invalidate an outstanding worker and ignore its obsolete completion" in {
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val oldReply = TestProbe()
    val freshReply = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val incarnations = new AtomicInteger()
    val parent = TestProbe()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      private val incarnation = incarnations.incrementAndGet()
      override def preStart(): Unit = { super.preStart(); events.ref ! incarnation }
      override def receive: Receive = ({ case "restart" => throw new RuntimeException("test restart") }: Receive)
        .orElse(super.receive)
      override protected lazy val execution = new HoldingTransformExecution(ctx, events.ref, events.ref, events.ref) {
        override def reconcile(): Unit = ()
        override def execute(work: HoldingTransform, alive: () => Boolean): Outcome = {
          if (incarnation == 1) {
            events.ref ! "old worker"
            gate.get(10, TimeUnit.SECONDS)
            events.ref ! alive()
          }
          Accepted(work.key, incarnation.toString)
        }
      }
    }))
    try {
      events.expectMsg(1)
      oldReply.send(engine, Submit(intent))
      events.expectMsg("old worker")
      engine ! "restart"
      events.expectMsg(2)
      freshReply.send(engine, Submit(intent))
      gate.complete(())
      events.expectMsg(false)
      freshReply.expectMsg(Accepted(intent.key, "2"))
      oldReply.expectNoMessage(200.millis)
    } finally { gate.complete(()); system.stop(engine) }
  }
}
