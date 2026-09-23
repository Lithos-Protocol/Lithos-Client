package transactions.rollups

import lfsm.LFSMHelpers.NISP_WINDOW
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The display reading of the commitment list. It has to agree with the rule the NISP readers use,
 * or the panel would name a different score from the one a NISP is judged against.
 */
class CommitmentScheduleSpec extends AnyFlatSpec with Matchers {

  "A commitment schedule" should "put the newest entry in force once it has aged past the window" in {
    val s = CommitmentSchedule(1000 + NISP_WINDOW, Vector(1000 -> 700L, 400 -> 500L))
    s.inForce shouldBe Some(700L)
    s.pending shouldBe None
  }

  it should "keep the previous entry in force while the newest waits, and say when it lands" in {
    val s = CommitmentSchedule(1000 + NISP_WINDOW - 1, Vector(1000 -> 700L, 400 -> 500L))
    s.inForce shouldBe Some(500L)
    s.pending shouldBe Some(700L -> (1000 + NISP_WINDOW))
  }

  it should "have nothing in force when the only entry has not aged yet" in {
    val s = CommitmentSchedule(1010, Vector(1000 -> 700L))
    s.inForce shouldBe None
    s.pending shouldBe Some(700L -> (1000 + NISP_WINDOW))
  }

  it should "have nothing at all when there are no commitments" in {
    val s = CommitmentSchedule(1000, Vector.empty)
    s.inForce shouldBe None
    s.pending shouldBe None
  }
}
