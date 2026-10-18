package transactions.engine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import transactions.candidate.BlockTxMessages.CandidateTxsDropped
import transactions.engine.TransactionEngine._
import transactions.engine.execution.{ForwardsAPIExecution, RollupExecution}
import transactions.engine.wallet.EngineFunding
import transactions.engine.wallet.EngineWalletMessages.MarkReservationUncertain
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class TransactionEngineSpec extends TestKit(ActorSystem("transaction-engine-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val intent = EngineIntent.Dex(DexIntent.Swap(api.models.LDSwapExecuteRequest("1000000", true, "1")), "request")

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
      override protected lazy val reconciler = new EngineReconciler(ctx, replies.ref, replies.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def executeOptional(work: EngineIntent, alive: () => Boolean): Any = {
          calls.incrementAndGet()
          entered.ref ! "entered"
          gate.get(10, TimeUnit.SECONDS)
          Accepted(work.key, "cd" * 32)
      }
    }))
    try {
      replies.send(engine, Submit(intent))
      entered.expectMsg("entered")
      replies.send(engine, Submit(intent))
      replies.send(engine, Submit(intent.copy(requestId = "x" * 129)))
      replies.expectMsgType[Deferred](1.second)
      gate.complete(())
      replies.expectMsg(Accepted(intent.key, "cd" * 32))
      replies.expectMsg(Accepted(intent.key, "cd" * 32))
      calls.get shouldBe 1
    } finally { gate.complete(()); system.stop(engine) }
  }

  it should "admit a NISP batch while the optional lane is full" in {
    // Each lane is limited by its own depth. Compared against the whole schedule, 256 optional
    // entries refused every rollup batch, which is the work with a deadline.
    val (ctx, _, _) = FakeNodeContext()
    val replies = TestProbe()
    val entered = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val reconciler = new EngineReconciler(ctx, replies.ref, replies.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def executeOptional(work: EngineIntent, alive: () => Boolean): Any = {
        entered.ref ! "entered"
        gate.get(10, TimeUnit.SECONDS)
        Accepted(work.key, "cd" * 32)
      }
    }))
    val stubs = Seq(transactions.rollups.TransactionMessages.RollupTxStub("a" * 64, Some(900L),
      transactions.rollups.TransactionMessages.RollupTxType.HoldingTransform))
    val submitter = TestProbe()
    try {
      (1 to 256).foreach(i => submitter.send(engine, Submit(intent.copy(requestId = s"optional-$i"))))
      entered.expectMsg("entered")
      submitter.send(engine, Submit(intent.copy(requestId = "one-too-many")))
      submitter.expectMsgType[Deferred](5.seconds).key shouldBe intent.copy(requestId = "one-too-many").key
      submitter.send(engine, transactions.rollups.TransactionMessages.RollupBatch(stubs))
      submitter.expectMsg(5.seconds, transactions.rollups.TransactionMessages.BatchAccepted(stubs))
    } finally { gate.complete(()); system.stop(engine) }
  }

  it should "let queued work progress after a slow reconciliation receives another tick" in {
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val reconciler = new EngineReconciler(ctx, events.ref, events.ref) {
        override def reconcile(): Unit = {
          events.ref ! "reconcile"
          gate.get(10, TimeUnit.SECONDS)
        }
      }
      override protected def executeOptional(work: EngineIntent, alive: () => Boolean): Any = {
          events.ref ! "execute"
          Completed(work.key)
      }
    }))
    try {
      events.expectMsg("reconcile")
      events.send(engine, Submit(intent))
      events.send(engine, Reconcile)
      events.send(engine, Submit(intent.copy(requestId = "x" * 129)))
      events.expectMsgType[Deferred]
      gate.complete(())
      events.expectMsg("execute")
      events.expectMsg(Completed(intent.key))
    } finally { gate.complete(()); system.stop(engine) }
  }

  "A scheduled forwards request" should "hand its execution the schedule's deadline, so nothing is sent once it passes" in {
    val (ctx, _, _) = FakeNodeContext()
    val entered = TestProbe()
    val replies = TestProbe()
    val gate = new CompletableFuture[Unit]()
    @volatile var eligible: () => Boolean = () => true
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val reconciler = new EngineReconciler(ctx, replies.ref, replies.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def newForwardsExecution(stillEligible: () => Boolean): ForwardsAPIExecution = {
        eligible = stillEligible
        new ForwardsAPIExecution(ctx, EngineFunding(replies.ref, 1.second, ec), None) {
          override def cancel(boxId: String): api.models.DFCancelResult = {
            entered.ref ! "entered"
            gate.get(10, TimeUnit.SECONDS)
            api.models.DFCancelResult("cd" * 32, "accepted", "0", "0", "0")
          }
        }
      }
    }))
    try {
      val now = System.currentTimeMillis()
      replies.send(engine, Schedule(EngineIntent.Forwards(ForwardsIntent.Cancel("ab" * 32)), now, now + 2000))
      entered.expectMsg(5.seconds, "entered")
      eligible() shouldBe true
      awaitCond(!eligible(), 5.seconds)
      gate.complete(())
      replies.fishForMessage(5.seconds) {
        case _: api.models.DFCancelResult => true
        case _ => false
      }
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
      override protected lazy val reconciler = new EngineReconciler(ctx, events.ref, events.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def executeOptional(work: EngineIntent, alive: () => Boolean): Any = {
          if (incarnation == 1) {
            events.ref ! "old worker"
            gate.get(10, TimeUnit.SECONDS)
            events.ref ! alive()
          }
          Accepted(work.key, incarnation.toString)
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

  "A dropped candidate height" should "end the bond leases the engine holds for it" in {
    // The engine answers for emissions and rollups in one mailbox, and emissions matches first. A
    // drop it does not pass on leaves the bond box withheld until a later rollup build starts.
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val wallet = TestProbe()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val reconciler = new EngineReconciler(ctx, events.ref, events.ref) {
        override def reconcile(): Unit = ()
      }
      // The wallet side is this same actor, so its messages are seen on the way in.
      override def receive: Receive = ({
        case uncertain: MarkReservationUncertain => wallet.ref ! uncertain
      }: Receive).orElse(super.receive)
    }))
    try {
      events.send(engine, RollupExecution.CandidateLeaseTaken(500, "bond"))
      events.send(engine, CandidateTxsDropped(500))
      wallet.expectMsg(5.seconds, MarkReservationUncertain("bond"))
    } finally system.stop(engine)
  }

  it should "keep the leases a rebuild of the same height takes" in {
    // A rebuild drops the refused package and builds the height again. Moving past the height, the
    // engine marked the rebuild's own bond leases uncertain on arrival, so the next refresh freed a
    // box the rebuilt candidate still offered.
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val wallet = TestProbe()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored) {
      override protected lazy val reconciler = new EngineReconciler(ctx, events.ref, events.ref) {
        override def reconcile(): Unit = ()
      }
      override def receive: Receive = ({
        case uncertain: MarkReservationUncertain => wallet.ref ! uncertain
      }: Receive).orElse(super.receive)
    }))
    try {
      events.send(engine, RollupExecution.CandidateLeaseTaken(500, "refused-package"))
      events.send(engine, CandidateTxsDropped(500, rebuilding = true))
      wallet.expectMsg(5.seconds, MarkReservationUncertain("refused-package"))
      events.send(engine, RollupExecution.CandidateLeaseTaken(500, "rebuilt"))
      wallet.expectNoMessage(500.millis)
      events.send(engine, CandidateTxsDropped(500))
      wallet.expectMsg(5.seconds, MarkReservationUncertain("rebuilt"))
    } finally system.stop(engine)
  }
}
