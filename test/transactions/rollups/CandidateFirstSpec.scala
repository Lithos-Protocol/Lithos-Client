package transactions.rollups

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.helpers.NOPLogger
import transactions.rollups.TransactionMessages.RollupTxStub
import transactions.rollups.TransactionMessages.RollupTxType._

/**
 * The rule deciding when the funded path may send a stub this miner's own block could carry
 * fee-less. Heights are around block 1000, with windows opened at 900: 260 blocks left, far from
 * the ten-block margin unless a test says otherwise.
 */
class CandidateFirstSpec extends AnyFlatSpec with Matchers {

  private def rule(holdArrivals: Boolean = true) = new CandidateFirst(NOPLogger.NOP_LOGGER, holdArrivals)

  private def transform(rollup: String = "a" * 64) = RollupTxStub(rollup, Some(900L), HoldingTransform)
  private def submission(period: Long = 900L) = RollupTxStub("b" * 64, Some(period), NISPSubmission)
  private def fraudProof(miner: Byte, period: Long = 900L) =
    RollupTxStub("c" * 64, Some(period), NISPEvaluation, fpInfo = Some(Array.fill[Byte](32)(miner) -> "fp"))

  private val all: CandidateFirst.StubKey => Boolean = _ => true
  private val none: CandidateFirst.StubKey => Boolean = _ => false

  "A stub its candidate built" should "be held until a chain check past that block still finds it queued" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.built(1000, Seq(s), Seq(s), all)
    r.fundable(s) shouldBe false

    r.checked(1000, all)
    r.fundable(s) shouldBe false
    withClue("the next block starting is not enough; whether the last one took it is not known yet: ") {
      r.candidateAsked(1001)
      r.fundable(s) shouldBe false
    }
    r.checked(1001, all)
    r.fundable(s) shouldBe true
  }

  it should "not be offered to a candidate once its block has passed" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.built(1000, Seq(s), Seq(s), all)
    r.offerable(s, 1000) shouldBe true
    r.offerable(s, 1001) shouldBe false
    r.checked(1001, all)
    r.offerable(s, 1001) shouldBe false
  }

  it should "leave no record behind when the check finds the work done" in {
    // Otherwise a later stub for the same work would inherit a release it never earned
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.built(1000, Seq(s), Seq(s), all)
    r.candidateAsked(1001)
    r.checked(1001, none)
    r.arrived(s)
    r.fundable(s) shouldBe false
    r.offerable(s, 1001) shouldBe true
  }

  it should "keep a report for a block that has passed from holding it" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1001)
    r.built(1000, Seq(s), Seq(s), all)
    r.fundable(s) shouldBe true
  }

  it should "ignore a stub the report names that has left the queue" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.built(1000, Seq(s), Seq(s), none)
    r.fundable(s) shouldBe true
  }

  "A submission or fraud proof" should "go out at once with ten blocks or fewer left in its window" in {
    // Opened at 640, the last valid block is 999: ten blocks left at 990, eleven at 989
    val s = submission(period = 640L)
    CandidateFirst.nearDeadline(s, 989) shouldBe false
    CandidateFirst.nearDeadline(s, 990) shouldBe true
    CandidateFirst.nearDeadline(fraudProof(1, period = 640L), 990) shouldBe true

    val r = rule()
    r.candidateAsked(989)
    r.built(989, Seq(s), Seq(s), all)
    r.fundable(s) shouldBe false
    r.candidateAsked(990)
    r.fundable(s) shouldBe true
  }

  "A transform or payout" should "never count as near a deadline" in {
    CandidateFirst.nearDeadline(RollupTxStub("a" * 64, Some(0L), EvalTransform), 100000) shouldBe false
    CandidateFirst.nearDeadline(RollupTxStub("a" * 64, None, Payout), 100000) shouldBe false
  }

  "A stub arriving while candidates are built" should "wait for a build to try it" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.arrived(s)
    r.fundable(s) shouldBe false
    r.built(1000, Seq(s), Seq(s), all)
    r.fundable(s) shouldBe false
  }

  it should "go to the funded path when a build tries it and fails" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.arrived(s)
    r.built(1000, Seq(s), Seq.empty, all)
    r.fundable(s) shouldBe true
    r.offerable(s, 1000) shouldBe false
  }

  it should "go to the funded path when its block ends untried" in {
    val r = rule()
    val s = transform()
    r.candidateAsked(1000)
    r.arrived(s)
    r.candidateAsked(1001)
    r.fundable(s) shouldBe true
  }

  it should "not be held while nothing is building candidates" in {
    val r = rule()
    val s = transform()
    r.arrived(s)
    r.fundable(s) shouldBe true

    withClue("the candidate path last asked two blocks ago: ") {
      val stale = rule()
      stale.candidateAsked(1000)
      stale.checked(1002, all)
      stale.arrived(s)
      stale.fundable(s) shouldBe true
    }
  }

  it should "not be held when no later build could bring it into the served job" in {
    val r = rule(holdArrivals = false)
    val s = transform()
    r.candidateAsked(1000)
    r.arrived(s)
    r.fundable(s) shouldBe true
  }

  it should "not be held when it is an evaluation stub, which no candidate carries" in {
    val r = rule()
    val s = RollupTxStub("c" * 64, Some(900L), NISPEvaluation)
    r.candidateAsked(1000)
    r.arrived(s)
    r.fundable(s) shouldBe true
  }

  "Fraud proofs on one rollup" should "be held apart by the miner they accuse" in {
    val r = rule()
    val (first, second) = (fraudProof(1), fraudProof(2))
    r.candidateAsked(1000)
    r.built(1000, Seq(first, second), Seq(first), all)
    r.fundable(first) shouldBe false
    r.fundable(second) shouldBe true
  }
}
