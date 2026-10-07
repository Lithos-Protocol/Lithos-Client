package api

import api.models.{CommitmentBlock, DifficultyCommitment}
import lfsm.LFSMHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import transactions.rollups._

import scala.util.{Failure, Success, Try}

/**
 * The commitment status `GET /mining/commitment` reports, derived from what was read.
 *
 * The page keys its warning and its send control off `state` and `canCommit`, so a status that calls
 * a miner committed when no commitment is in force hides the one fact that stops them being paid.
 */
class DifficultyCommitmentSpec extends AnyFlatSpec with Matchers {

  private val Tip = 10000
  private val Window = LFSMHelpers.NISP_WINDOW

  private def confirmed(entries: (Int, Long)*): Try[CommitmentState] =
    Success(CommitmentState(Tip, Some(CommitmentSchedule(Tip, entries.toVector)), None))

  private val noDataBox: Try[CommitmentState] = Success(CommitmentState(Tip, None, None))

  private def of(read: Try[CommitmentState],
                 registered: Either[String, Boolean] = Right(true),
                 outstanding: Option[CommitmentSends.Outstanding] = None,
                 autoCommit: Boolean = false,
                 transformsDisabled: Boolean = false): DifficultyCommitment =
    DifficultyCommitment.of(read, registered, outstanding, autoCommit, transformsDisabled, "4.0G")

  private def held(kind: String, score: Long, declared: Int): CommitmentSends.Outstanding =
    CommitmentSends.Outstanding(CommitmentSends.Sent("ab" * 32, kind, score, declared), None, "transaction to confirm")

  // ─── not committed ────────────────────────────────────────────────────────

  "An unregistered miner" should "be reported unregistered and free to register" in {
    val s = of(noDataBox, registered = Right(false))
    s.state shouldEqual DifficultyCommitment.Unregistered
    s.inForce shouldBe None
    s.canCommit shouldBe true
    s.blockedReason shouldBe None
  }

  it should "be blocked by auto-commit, and by disabled transforms ahead of it" in {
    of(noDataBox, registered = Right(false), autoCommit = true).blockedReason shouldBe Some(CommitmentBlock.AutoCommit)
    of(noDataBox, registered = Right(false), autoCommit = true, transformsDisabled = true).blockedReason shouldBe
      Some(CommitmentBlock.TransformsDisabled)
  }

  "A registration in flight" should "show its commitment and hold a second registration back" in {
    // A second registration built now would spend the same dictionary box as the first.
    val declared = Tip + CommitmentTransactions.DeclareAfter
    val s = of(noDataBox, registered = Right(false), outstanding = Some(held(CommitmentSends.Registration, 700L, declared)))
    s.state shouldEqual DifficultyCommitment.Registering
    s.canCommit shouldBe false
    s.blockedReason shouldBe Some(CommitmentBlock.InFlight)
    val c = s.inFlight.get.commitment
    c.servedFromHeight shouldBe Some(declared - CommitmentTransactions.ServeLead)
    c.inForceFromHeight shouldEqual declared + Window
  }

  "A registration the dictionary holds" should "wait for synchronization to record the data box" in {
    val s = of(noDataBox, registered = Right(true))
    s.state shouldEqual DifficultyCommitment.Registering
    s.blockedReason shouldBe Some(CommitmentBlock.Syncing)
  }

  "A dictionary that cannot vouch" should "never read as unregistered" in {
    // Reporting unregistered here would invite a registration that may already exist.
    val s = of(noDataBox, registered = Left("catching up"))
    s.state shouldEqual DifficultyCommitment.Unknown
    s.canCommit shouldBe false
  }

  "A failed read" should "be unknown, not uncommitted" in {
    val s = of(Failure(new RuntimeException("node down")))
    s.state shouldEqual DifficultyCommitment.Unknown
    s.blockedReason shouldBe Some(CommitmentBlock.Unavailable)
    s.reason.get should include("node down")
  }

  // ─── registered ───────────────────────────────────────────────────────────

  "A first commitment that has not aged" should "be waiting, with nothing in force and the change locked" in {
    val declared = Tip - 10
    val s = of(confirmed(declared -> 700L))
    s.state shouldEqual DifficultyCommitment.Waiting
    s.inForce shouldBe None
    s.pending.map(_.score) shouldBe Some("700")
    s.pending.flatMap(_.servedFromHeight) shouldBe Some(declared - CommitmentTransactions.ServeLead)
    s.blockedReason shouldBe Some(CommitmentBlock.Locked)
    s.replaceableFromHeight shouldBe Some(CommitmentTransactions.replaceableFrom(declared))
  }

  "An aged commitment" should "be active, and replaceable once the spacing has passed" in {
    val declared = Tip - Window - LFSMHelpers.ROLLUP_LIFETIME.toInt
    val s = of(confirmed(declared -> 700L, (declared - 2000) -> 500L))
    s.state shouldEqual DifficultyCommitment.Active
    s.inForce.map(_.score) shouldBe Some("700")
    s.pending shouldBe None
    s.canCommit shouldBe true

    of(confirmed((declared + 1) -> 700L, (declared - 2000) -> 500L)).blockedReason shouldBe Some(CommitmentBlock.Locked)
  }

  "A pending raise" should "keep the old score in force and be served early" in {
    val s = of(confirmed(Tip -> 900L, (Tip - 2000) -> 500L))
    s.state shouldEqual DifficultyCommitment.Active
    s.inForce.map(_.score) shouldBe Some("500")
    s.pending.flatMap(_.servedFromHeight) shouldBe Some(Tip - CommitmentTransactions.ServeLead)
  }

  "A pending cut" should "be served only once it binds" in {
    val s = of(confirmed(Tip -> 300L, (Tip - 2000) -> 500L))
    s.pending.flatMap(_.servedFromHeight) shouldBe Some(Tip + Window)
  }

  "A change in the mempool" should "be in flight whoever sent it" in {
    val old = Tip - 2000
    val next = (Tip + CommitmentTransactions.DeclareAfter) -> 900L
    val read = Success(CommitmentState(Tip, Some(CommitmentSchedule(Tip, Vector(old -> 500L))),
      Some(UnconfirmedCommitments("cd" * 32, Vector(next, old -> 500L)))))
    val s = of(read)
    s.inFlight.map(_.txId) shouldBe Some("cd" * 32)
    s.inFlight.map(_.kind) shouldBe Some(CommitmentSends.Change)
    s.blockedReason shouldBe Some(CommitmentBlock.InFlight)
  }

  "This client's own change" should "stop being in flight once the confirmed list shows it" in {
    val declared = Tip - 10
    of(confirmed(declared -> 900L, (Tip - 2000) -> 500L), outstanding = Some(held(CommitmentSends.Change, 900L, declared)))
      .inFlight shouldBe None
    of(confirmed((Tip - 2000) -> 500L), outstanding = Some(held(CommitmentSends.Change, 900L, Tip + 65)))
      .inFlight.map(_.commitment.score) shouldBe Some("900")
  }

  "The status" should "serialize without absent fields" in {
    val json = Json.toJson(of(noDataBox, registered = Right(false)))
    (json \ "state").as[String] shouldEqual "unregistered"
    (json \ "inForce").toOption shouldBe None
    (json \ "timing" \ "replaceableAfterBlocks").as[Int] shouldEqual 845
  }

  // ─── the diff a request names ─────────────────────────────────────────────

  "scoreOf" should "convert a diff exactly as the commitment transaction does" in {
    val viaTx = LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum("1.5M").get)).toLong
    DifficultyCommitment.scoreOf("1.5M") shouldEqual Success(viaTx)
    DifficultyCommitment.scoreOf(" 1.5M ") shouldEqual Success(viaTx)
  }

  it should "refuse anything stratum.diff would not parse, or that cannot be committed" in {
    for (bad <- Seq("", "1.5", "1.5m", "abc", "0M", "-1M", "NaNM", "99999999P"))
      withClue(s"'$bad': ") { DifficultyCommitment.scoreOf(bad).isFailure shouldBe true }
  }

  "diffOf" should "write a score the way stratum.diff parses it" in {
    val score = DifficultyCommitment.scoreOf("1.5M").get
    DifficultyCommitment.scoreOf(DifficultyCommitment.diffOf(score)).get shouldEqual score +- 1L
  }
}
