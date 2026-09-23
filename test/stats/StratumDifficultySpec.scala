package stats

import lfsm.LFSMHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import stratum.BlockTemplate
import stratum.data.MiningCandidate

import java.math.BigInteger

class StratumDifficultySpec extends AnyFlatSpec with Matchers {
  private val score = 140000L
  private val tau = LFSMHelpers.convertTauOrScore(BigInt(score))

  private def template(reduced: Boolean) = new BlockTemplate("1", new MiningCandidate(
    Array.fill[Byte](32)(7), 1L, 3, BigInteger.ONE, "02" * 33, null, null), tau.bigInteger, true, reduced)

  "The stratum's difficulties" should "name the scores a job's own thresholds stand for" in {
    // Derived apart from the job, so this pins the two to the same numbers.
    Seq(false, true).foreach { reduced =>
      val d = StratumDifficulty.of(tau, reduced, forced = false).get
      val job = template(reduced)
      d.served shouldBe score.toString
      d.advertised shouldBe (LFSMHelpers.TARGET_MAX_LITHOS / BigInt(job.assignedThreshold)).toString
      d.superShare shouldBe (LFSMHelpers.TARGET_MAX_LITHOS / BigInt(job.superShareThreshold)).toString
    }
  }

  it should "advertise the super-share score under reduced reporting and the served one otherwise" in {
    StratumDifficulty.of(tau, reduced = false, forced = false).get.advertised shouldBe score.toString
    val reduced = StratumDifficulty.of(tau, reduced = true, forced = false).get
    reduced.advertised shouldBe reduced.superShare
    BigInt(reduced.superShare) shouldBe BigInt(score) * LFSMHelpers.NISP_COEFFICIENT
  }

  it should "carry the commitment in force and a pending one with its start height" in {
    val d = StratumDifficulty.of(tau, reduced = true, forced = false, committed = Some(score),
      pending = Some(200000L -> 1060), checkedHeight = Some(1010)).get
    d.committed shouldBe Some("140000")
    d.pending shouldBe Some("200000")
    d.pendingFromHeight shouldBe Some(1060)
    d.checkedHeight shouldBe Some(1010)
  }

  it should "report nothing for a zero tau rather than divide by it" in {
    StratumDifficulty.of(BigInt(0), reduced = true, forced = false) shouldBe None
  }
}
