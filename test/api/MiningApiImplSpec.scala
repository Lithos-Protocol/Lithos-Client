package api

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActor, TestKit, TestProbe}
import akka.util.Timeout
import api.models._
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import transactions.engine.{EngineBroadcast, TransactionEngine}
import transactions.rollups.{CommitmentProgress, CommitmentSends, CommitmentTransactions}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/**
 * What `POST /mining/commitment` decides before and after it asks the engine.
 *
 * Each refusal names a condition under which a send would be wrong or undone: auto-commit owning the
 * commitment, an earlier send not settled, a commitment still locked. The engine re-checks the data
 * box, but only this layer can tell a caller which of those it hit.
 */
class MiningApiImplSpec extends TestKit(ActorSystem("mining-api-impl-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val shipped = ConfigFactory.parseResources("application.conf").resolve()
  private val (nodeContext, _, _) = support.FakeNodeContext()
  private val Tip = 10000
  private val diff = "1.5M"
  private val score = DifficultyCommitment.scoreOf(diff).get

  private def entry(score: Long, declared: Int): CommitmentEntry =
    CommitmentEntry(score.toString, DifficultyCommitment.diffOf(score), declared, declared + 60)

  private def status(state: String = DifficultyCommitment.Active,
                     inForce: Option[CommitmentEntry] = None,
                     pending: Option[CommitmentEntry] = None,
                     block: Option[String] = None,
                     inFlight: Option[CommitmentInFlight] = None): DifficultyCommitment =
    DifficultyCommitment(state, None, Some(Tip), inForce, pending, inFlight, Some(Tip - 1), block.isEmpty, block,
      autoCommit = false, configDiff = "4.0G", DifficultyCommitment.Timing)

  private val active = status(inForce = Some(entry(4000000L, Tip - 1000)))

  /** An engine that answers every request with `answer`, counting what it was asked. */
  private def engine(answer: Any => Any): (ActorRef, AtomicInteger, TestProbe) = {
    val asked = new AtomicInteger()
    val probe = TestProbe()
    probe.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        asked.incrementAndGet()
        answer(msg) match {
          case MiningApiImplSpec.Silent => ()
          case reply => sender ! reply
        }
        TestActor.KeepRunning
      }
    })
    (probe.ref, asked, probe)
  }

  private def impl(read: => DifficultyCommitment, engineRef: ActorRef, overrides: String = "",
                   sends: CommitmentSends = new CommitmentSends): MiningApiImpl =
    new MiningApiImpl(nodeContext, Configuration(ConfigFactory.parseString(overrides).withFallback(shipped)),
      engineRef, sends) {
      override protected def readCommitment(): DifficultyCommitment = read
      override protected def engineTimeout: Timeout = Timeout(500.millis)
    }

  private def sent(score: Long, declared: Int = Tip + 65, outcome: String = EngineBroadcast.Accepted) =
    CommitmentProgress.Sent("cd" * 32, score, declared, outcome)

  // ─── refused before the engine is asked ───────────────────────────────────

  "A commitment request" should "refuse an unparseable or over-long diff with 400, asking nothing" in {
    val (ref, asked, _) = engine(_ => sent(score))
    for (bad <- Seq("1.5", "abc", "1." + "0" * 40 + "M")) withClue(s"'$bad': ") {
      a[LithosApiErrors.LithosBadRequest] should be thrownBy impl(active, ref).commit(CommitmentRequest(bad))
    }
    asked.get shouldBe 0
  }

  it should "refuse with 409 while auto-commit owns the commitment" in {
    // The loop would move the commitment back to stratum.diff on its next pass.
    val (ref, asked, _) = engine(_ => sent(score))
    val owned = status(block = Some(CommitmentBlock.AutoCommit))
    a[LithosApiErrors.LithosStateChanged] should be thrownBy impl(owned, ref).commit(CommitmentRequest(diff))
    asked.get shouldBe 0
  }

  it should "refuse with 409 while an earlier send has not settled" in {
    // A second change built now spends the data box the first one already spends.
    val (ref, asked, _) = engine(_ => sent(score))
    val held = status(block = Some(CommitmentBlock.InFlight), inFlight = Some(
      CommitmentInFlight("ab" * 32, CommitmentSends.Change, None, entry(score, Tip + 60))))
    val thrown = the[LithosApiErrors.LithosStateChanged] thrownBy impl(held, ref).commit(CommitmentRequest(diff))
    thrown.getMessage should include("ab" * 32)
    asked.get shouldBe 0
  }

  it should "refuse with 422 while the newest commitment is locked" in {
    val (ref, asked, _) = engine(_ => sent(score))
    val locked = status(block = Some(CommitmentBlock.Locked))
    a[LithosApiErrors.LithosUnprocessable] should be thrownBy impl(locked, ref).commit(CommitmentRequest(diff))
    asked.get shouldBe 0
  }

  it should "refuse with 503 while the commitment cannot be read" in {
    val (ref, asked, _) = engine(_ => sent(score))
    val unknown = status(DifficultyCommitment.Unknown, block = Some(CommitmentBlock.Unavailable))
    a[LithosApiErrors.LithosUnavailable] should be thrownBy impl(unknown, ref).commit(CommitmentRequest(diff))
    asked.get shouldBe 0
  }

  it should "refuse the diff that is already the newest commitment with 400" in {
    val (ref, asked, _) = engine(_ => sent(score))
    val pendingSame = status(inForce = Some(entry(4000000L, Tip - 1000)), pending = Some(entry(score, Tip - 10)))
    a[LithosApiErrors.LithosBadRequest] should be thrownBy impl(pendingSame, ref).commit(CommitmentRequest(diff))
    asked.get shouldBe 0
  }

  // ─── what is asked of the engine ──────────────────────────────────────────

  "An unregistered miner" should "be registered, not sent a change" in {
    val (ref, _, probe) = engine(_ => sent(score))
    val result = impl(status(DifficultyCommitment.Unregistered), ref).commit(CommitmentRequest(s" $diff "))
    probe.expectMsg(TransactionEngine.RegisterMiner(diff))
    result.kind shouldBe CommitmentSends.Registration
    result.servedFromHeight shouldBe Tip + 65 - CommitmentTransactions.ServeLead
  }

  "A registered miner" should "be sent a change, served at binding when it is a cut" in {
    val (ref, _, probe) = engine(_ => sent(score))
    val result = impl(active, ref).commit(CommitmentRequest(diff))
    probe.expectMsg(TransactionEngine.CommitDifficulty(diff))
    result.kind shouldBe CommitmentSends.Change
    // 1.5M is below the 4.0M in force, so the stratum keeps the old score until the new one binds.
    result.servedFromHeight shouldBe result.inForceFromHeight
    result.replaceableFromHeight shouldBe Tip + 65 + 780
  }

  it should "report an uncertain send as sent, not as a failure" in {
    // It may still confirm. A failure here invites a resend of the same box.
    val (ref, _, _) = engine(_ => sent(score, outcome = EngineBroadcast.Uncertain))
    impl(active, ref).commit(CommitmentRequest(diff)).outcome shouldBe EngineBroadcast.Uncertain
  }

  it should "say so when its request coalesced onto another diff's" in {
    // The engine keys these by kind, so the answer can be another caller's send.
    val (ref, _, _) = engine(_ => sent(score + 1000))
    a[LithosApiErrors.LithosStateChanged] should be thrownBy impl(active, ref).commit(CommitmentRequest(diff))
  }

  it should "map what the engine reports to the status a caller acts on" in {
    val cases: Seq[(Any, Class[_])] = Seq(
      CommitmentProgress.Waiting("data box spent") -> classOf[LithosApiErrors.LithosStateChanged],
      CommitmentProgress.Settled -> classOf[LithosApiErrors.LithosBadRequest],
      TransactionEngine.Deferred("difficulty-commitment", "admission limit") -> classOf[LithosApiErrors.LithosUnavailable],
      akka.actor.Status.Failure(new EngineBroadcast.SubmissionOutcomeException("ab" * 32, EngineBroadcast.Rejected,
        Some("double spend"))) -> classOf[LithosApiErrors.LithosUnprocessable],
      MiningApiImplSpec.Silent -> classOf[LithosApiErrors.LithosUnavailable])
    for ((answer, expected) <- cases) withClue(s"$answer: ") {
      val (ref, _, _) = engine(_ => answer)
      val thrown = intercept[RuntimeException](impl(active, ref).commit(CommitmentRequest(diff)))
      thrown.getClass shouldBe expected
    }
  }

  "Two requests at once" should "send one and refuse the other with 409" in {
    val (ref, asked, probe) = engine(_ => MiningApiImplSpec.Silent)
    val api = impl(active, ref)
    val first = scala.concurrent.Future(api.commit(CommitmentRequest(diff)))(system.dispatcher)
    probe.expectMsgType[TransactionEngine.CommitDifficulty]
    a[LithosApiErrors.LithosStateChanged] should be thrownBy api.commit(CommitmentRequest(diff))
    asked.get shouldBe 1
    scala.concurrent.Await.ready(first, 5.seconds)
  }

  // ─── the read the panels poll ─────────────────────────────────────────────

  "The status" should "be read once for every caller inside the cache window, and afresh after a send" in {
    val reads = new AtomicInteger()
    val (ref, _, _) = engine(_ => sent(score))
    val api = impl({ reads.incrementAndGet(); active }, ref)
    api.getCommitment
    api.getCommitment
    reads.get shouldBe 1
    api.commit(CommitmentRequest(diff))
    withClue("a send decides on a fresh read: ") { reads.get shouldBe 2 }
    api.getCommitment
    withClue("and leaves the cache empty, so the next read shows it: ") { reads.get shouldBe 3 }
  }
}

object MiningApiImplSpec {
  /** An engine answer that never arrives, so the request runs into its timeout. */
  case object Silent
}
