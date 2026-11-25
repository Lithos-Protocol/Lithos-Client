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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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

  it should "admit a join of as many positions as the API accepts" in {
    // The API allowed 25 positions while admission stopped at 10, so 11 to 25 came back Deferred,
    // which the API then cast into a 500.
    val (ctx, _, _) = FakeNodeContext()
    val replies = TestProbe()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
      override protected lazy val reconciler = new EngineReconciler(ctx, replies.ref, replies.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def executeEmission(intent: EngineIntent): scala.concurrent.Future[Any] =
        scala.concurrent.Future.successful("joined")
    }))
    def join(count: Int) = JoinCollateral(api.models.CollateralJoinExecuteRequest(count, Some(true), None, None))
    try {
      replies.send(engine, join(execution.CollateralExecution.MaxJoinCount))
      replies.fishForSpecificMessage(5.seconds) { case "joined" => () }
      replies.send(engine, join(execution.CollateralExecution.MaxJoinCount + 1))
      replies.fishForSpecificMessage(5.seconds) { case refused: Deferred => refused }
    } finally system.stop(engine)
  }

  it should "let queued work progress after a slow reconciliation receives another tick" in {
    val (ctx, _, _) = FakeNodeContext()
    val events = TestProbe()
    val gate = new CompletableFuture[Unit]()
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, replies.ref, replies.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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
    val engine = system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache, play.api.Configuration(com.typesafe.config.ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored,
      transactions.batching.arb.ArbFixtures.idleStake(ctx)) {
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

  /**
   * An engine whose arb reserve is due at once and reports each attempt to `events` instead of sending.
   *
   * @param target the stake's size, 0 for a client with arb off
   */
  private def reserving(target: Long, events: TestProbe, paused: Boolean = false) =
    reservingWith(target, events, paused)._2

  /**
   * [[reserving]], with the stake the engine was built on.
   *
   * @param outcome what each stubbed reserve attempt concludes
   * @param quick   maintenance and reserve attempts every 200 ms, so several attempts fit a short test
   * @param real    runs the real reserve and reclaim against `api` rather than the stub
   */
  private def reservingWith(target: Long, events: TestProbe, paused: Boolean,
                            outcome: String = transactions.engine.execution.ArbReserveExecution.OutcomeHeld,
                            quick: Boolean = false, real: Boolean = false,
                            api: _root_.node.NodeApi = org.scalatestplus.mockito.MockitoSugar.mock[_root_.node.NodeApi]) = {
    val (ctx, _, wallet) = FakeNodeContext(api)
    val stakes = new transactions.batching.arb.ArbStake(() => ctx.getNodeApi,
      transactions.batching.arb.ArbFixtures.stakeContract(wallet), ctx.getNetwork, target)
    if (paused) stakes.pause()
    stakes -> system.actorOf(Props(new TransactionEngine(ctx, events.ref, events.ref, new support.FakeCache,
      play.api.Configuration(ConfigFactory.load()), transactions.rollups.DataBoxSource.Stored, stakes) {
      override protected def arbReserveDelayMs: Long = 0L
      override protected def arbReserveIntervalMs: Long = if (quick) 200L else super.arbReserveIntervalMs
      override protected def maintenanceIntervalMs: Long = if (quick) 200L else super.maintenanceIntervalMs
      override protected lazy val reconciler = new EngineReconciler(ctx, events.ref, events.ref) {
        override def reconcile(): Unit = ()
      }
      override protected def executeOptional(work: EngineIntent, alive: () => Boolean): Any = work match {
        case EngineIntent.ArbReserve | EngineIntent.ArbReclaim if real =>
          if (work == EngineIntent.ArbReserve) events.ref ! "arb-reserve"
          super.executeOptional(work, alive)
        case EngineIntent.ArbReserve =>
          events.ref ! "arb-reserve"
          transactions.engine.execution.ArbReserveExecution.Status(outcome, 0L, target)
        case other => Completed(other.key)
      }
    }))
  }

  /** Reserve attempts that reach `events` within `wait`. */
  private def attempts(events: TestProbe, wait: FiniteDuration): Int =
    events.receiveWhile(wait, wait) { case message => message }.count(_ == "arb-reserve")

  /** Whether an arb reserve attempt reaches `events` within `wait`, whatever else the engine sends there. */
  private def attempted(events: TestProbe, wait: FiniteDuration): Boolean =
    events.receiveWhile(wait, wait) { case message => message }.contains("arb-reserve")

  "The arb reserve" should "be attempted by a client with arb on and auto-reserve on" in {
    val events = TestProbe()
    val engine = reserving(10000000000L, events)
    try attempted(events, 5.seconds) shouldBe true finally system.stop(engine)
  }

  it should "never be attempted after a reclaim, nor with arb off" in {
    val events = TestProbe()
    val reclaimed = reserving(10000000000L, events, paused = true)
    val off = reserving(0L, events)
    try attempted(events, 4.seconds) shouldBe false finally { system.stop(reclaimed); system.stop(off) }
  }

  it should "be due again once arb is resumed after a reclaim, though an attempt had already found a stake" in {
    val events = TestProbe()
    val (stakes, engine) = reservingWith(10000000000L, events, paused = false)
    def reserved(): Any = events.fishForMessage(25.seconds) { case "arb-reserve" => true; case _ => false }
    try {
      // The first attempt finds a stake, which ends attempts for good until a resume re-arms them
      reserved()
      stakes.pause()
      val caller = TestProbe()
      caller.send(engine, ResumeArb)
      caller.expectMsg(5.seconds, ArbResumed(autoReserve = true))
      stakes.paused shouldBe false
      // The next maintenance pass, at most fifteen seconds on
      reserved()
    } finally system.stop(engine)
  }

  it should "stop once an attempt finds a stake held, and keep trying while the wallet is short" in {
    import transactions.engine.execution.ArbReserveExecution.{OutcomeHeld, OutcomeShort}
    val (heldEvents, shortEvents) = (TestProbe(), TestProbe())
    val held = reservingWith(10000000000L, heldEvents, paused = false, outcome = OutcomeHeld, quick = true)._2
    val short = reservingWith(10000000000L, shortEvents, paused = false, outcome = OutcomeShort, quick = true)._2
    try {
      // Maintenance ticks once a second, so four seconds hold three or four attempts where nothing ends them
      attempts(shortEvents, 4.seconds) should be >= 3
      attempts(heldEvents, 4.seconds) shouldBe 1
    } finally { system.stop(held); system.stop(short) }
  }

  "The real arb reserve and reclaim" should "run through the engine: a stake found ends attempts, and a reclaim of " +
    "an empty address answers nothing and leaves arb running" in {
    import _root_.node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
    import org.mockito.ArgumentMatchers.{any, anyString}
    import org.mockito.Mockito.{atLeastOnce, verify, when}
    val api = org.scalatestplus.mockito.MockitoSugar.mock[_root_.node.NodeApi]
    @volatile var atAddress = Seq.empty[NodeBox]
    when(api.indexerEnabled).thenReturn(true)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
      val tree = inv.getArgument[String](0)
      scala.util.Success(atAddress.filter(_.ergoTree == tree).map(IndexedBox(_, "", 100, 1L)))
    }
    when(api.boxById(anyString())).thenAnswer { inv =>
      scala.util.Success(atAddress.find(_.boxId == inv.getArgument[String](0)))
    }
    val target = 10000000000L
    // Arb off, so no reserve runs beside the reclaim and the wallet is never asked
    val (idle, reclaiming) = reservingWith(0L, TestProbe(), paused = false, quick = true, real = true, api = api)
    try withClue("a reclaim of an empty address sends nothing and leaves the stake unpaused: ") {
      val reclaimer = TestProbe()
      reclaimer.send(reclaiming, Submit(EngineIntent.ArbReclaim))
      reclaimer.expectMsg(10.seconds, None)
      idle.paused shouldBe false
      verify(api, atLeastOnce()).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
    } finally system.stop(reclaiming)

    // Every fake context holds the same key, so the second engine's arb address is the first's
    atAddress = Seq(NodeBox("ab" * 32, "cd" * 32, target, 0, 100, idle.tree))
    val events = TestProbe()
    val (stakes, engine) = reservingWith(target, events, paused = false, quick = true, real = true, api = api)
    stakes.tree shouldBe idle.tree
    try withClue("a stake of full size at the address is found held by the first attempt, and none follows: ") {
      attempts(events, 3.seconds) shouldBe 1
    } finally system.stop(engine)
  }

  "A resume" should "be deferred with arb off, and leave a stake it did not resume paused" in {
    val events = TestProbe()
    val (stakes, engine) = reservingWith(0L, events, paused = true)
    try {
      val caller = TestProbe()
      caller.send(engine, ResumeArb)
      caller.expectMsgType[Deferred](5.seconds).reason should include("off")
      stakes.paused shouldBe true
    } finally system.stop(engine)
  }
}
