package transactions.emissions

import lfsm.EmissionSchedule
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The emission-phase labels and the next-change height the market snapshot reports.
 *
 * Checked against the schedule's own numbers rather than symbols: decay steps land every
 * `P1_DECAY_LENGTH` epochs until epoch 14, the founder rate ends at epoch 8, fixed rates hold from
 * epochs 14-15 and 16 onward, and the schedule ends at activation 2,825,000 - which is NOT an epoch
 * boundary, so ENDED must be gated on the final block, not on an epoch count.
 */
class EmissionPhaseSpec extends AnyFlatSpec with Matchers {

  private val Epoch = EmissionSchedule.EPOCH_LENGTH

  private def atEpoch(e: Int): Int = e * Epoch

  "phaseAt" should "report DECAYING through the public decay, including where founders are paid" in {
    EmissionSchedule.phaseAt(0) shouldBe "DECAYING"
    EmissionSchedule.phaseAt(atEpoch(7)) shouldBe "DECAYING"
    EmissionSchedule.phaseAt(atEpoch(13) + Epoch - 1) shouldBe "DECAYING"
  }

  it should "report STEPPED across epochs 14 and 15" in {
    EmissionSchedule.phaseAt(atEpoch(14)) shouldBe "STEPPED"
    EmissionSchedule.phaseAt(atEpoch(15) + Epoch - 1) shouldBe "STEPPED"
  }

  it should "report FLAT_50 from epoch 16 until the final block" in {
    EmissionSchedule.phaseAt(atEpoch(16)) shouldBe "FLAT_50"
    EmissionSchedule.phaseAt(EmissionSchedule.FINAL_BLOCK - 1) shouldBe "FLAT_50"
  }

  it should "report ENDED only at the final BLOCK, which is past the last full epoch" in {
    // 2,825,000 = 22 epochs + 75,000 blocks, so epoch arithmetic alone would call this FLAT_50.
    val lastFullEpoch = 22 * Epoch - 1
    EmissionSchedule.phaseAt(lastFullEpoch) shouldBe "FLAT_50"
    EmissionSchedule.phaseAt(lastFullEpoch + 1) shouldBe "FLAT_50" // still short of FINAL_BLOCK
    EmissionSchedule.phaseAt(EmissionSchedule.FINAL_BLOCK) shouldBe "ENDED"
    EmissionSchedule.phaseAt(EmissionSchedule.FINAL_BLOCK + 1) shouldBe "ENDED"
  }

  "nextChangeAt" should "name the next decay step while decaying" in {
    EmissionSchedule.nextChangeAt(0) shouldBe Some(atEpoch(2))
    EmissionSchedule.nextChangeAt(atEpoch(2)) shouldBe Some(atEpoch(4))
    EmissionSchedule.nextChangeAt(atEpoch(6) + 1) shouldBe Some(atEpoch(8))
  }

  it should "name epoch 8 once, where the founder period ends on a decay boundary" in {
    EmissionSchedule.nextChangeAt(atEpoch(8) - 1) shouldBe Some(atEpoch(8))
    EmissionSchedule.nextChangeAt(atEpoch(8)) shouldBe Some(atEpoch(10))
  }

  it should "name the fixed phase starts after the decay ends" in {
    EmissionSchedule.nextChangeAt(atEpoch(12)) shouldBe Some(atEpoch(14))
    EmissionSchedule.nextChangeAt(atEpoch(14)) shouldBe Some(atEpoch(16))
  }

  it should "fall through to the final block during the flat phase, whatever the offset into its epoch" in {
    EmissionSchedule.nextChangeAt(atEpoch(16)) shouldBe Some(EmissionSchedule.FINAL_BLOCK)
    EmissionSchedule.nextChangeAt(atEpoch(22) + 74999) shouldBe Some(EmissionSchedule.FINAL_BLOCK)
  }

  it should "be None once ended" in {
    EmissionSchedule.nextChangeAt(EmissionSchedule.FINAL_BLOCK) shouldBe None
    EmissionSchedule.nextChangeAt(EmissionSchedule.FINAL_BLOCK + 1000) shouldBe None
  }
}
