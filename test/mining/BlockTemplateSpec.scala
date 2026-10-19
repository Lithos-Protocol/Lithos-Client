package mining

import lfsm.LFSMHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import stratum.BlockTemplate
import stratum.data.MiningCandidate

import java.math.BigInteger
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.collection.JavaConverters._

/**
 * `BlockTemplate` derives per JOB what share validation used to recompute per SHARE, and builds its
 * job params in the constructor rather than lazily into a field every connection actor reads.
 *
 * Two different defects live here. The arithmetic one is pure waste — three BigInteger divisions on
 * the hottest path in the client for an answer fixed for the job's life. The publication one is not:
 * `Announcement.miningJob` passes `getJobParams` as a supplier evaluated on the connection actor's
 * thread, and `JSONArray`'s backing list carries no final-field guarantee, so a lazily-populated
 * non-volatile field could hand a miner a half-built array.
 */
class BlockTemplateSpec extends AnyFlatSpec with Matchers {

  private val tau4G = BigInteger.valueOf(4000000000L)

  private def candidate(height: Long = 479451L, b: BigInteger = BigInteger.ONE): MiningCandidate =
    new MiningCandidate(Array.fill[Byte](32)(7), height, 3, b, "02" * 33, null, null)

  private def template(tau: BigInteger = tau4G,
                       usedCollateral: Boolean = true,
                       reduced: Boolean = false): BlockTemplate =
    new BlockTemplate("1a", candidate(), tau, usedCollateral, reduced)

  /** Exactly the expression share validation used to evaluate per share. */
  private def oldRealTau(tau: BigInteger): BigInteger =
    LFSMHelpers.convertTauOrScore(LFSMHelpers.convertTauOrScore(scala.math.BigInt(tau))).bigInteger

  "realTau" should "equal the expression share validation used to compute per share" in {
    Seq(
      tau4G,
      BigInteger.valueOf(1L),
      BigInteger.valueOf(1000L),
      BigInteger.valueOf(Long.MaxValue),
      LFSMHelpers.TARGET_MAX_LITHOS.bigInteger
    ).foreach { tau =>
      withClue(s"tau=$tau: ") {
        template(tau).realTau shouldEqual oldRealTau(tau)
      }
    }
  }

  "registerSubmit" should "refuse a repeat, and past the cap forget the oldest submission first" in {
    val t = template()
    def submit(i: Int): Boolean =
      t.registerSubmit(Array[Byte](1, 2, 3, 4), java.nio.ByteBuffer.allocate(4).putInt(i).array(), "0", Array.emptyByteArray)

    submit(0) shouldBe true
    submit(0) shouldBe false
    (1 to BlockTemplate.MAX_SUBMISSIONS).foreach(i => submit(i) shouldBe true)
    submit(BlockTemplate.MAX_SUBMISSIONS) shouldBe false
    submit(0) shouldBe true
  }

  "superShareThreshold" should "be realTau divided by the NISP coefficient" in {
    val t = template()
    t.superShareThreshold shouldEqual
      t.realTau.divide(BigInteger.valueOf(LFSMHelpers.NISP_COEFFICIENT))
  }

  it should "be strictly harder than the miner's own difficulty" in {
    // The whole point of a super share: it is a share that also clears a much higher bar. If these
    // ever inverted, every ordinary share would be recorded as a NISP.
    val t = template()
    t.superShareThreshold should be < t.realTau
  }

  "a zero-tau template" should "not throw at construction" in {
    // The legacy three-argument constructor passes zero. Dividing by it would abort job creation
    // rather than reject a share, which is the more expensive failure.
    noException should be thrownBy new BlockTemplate("1a", candidate(), true)
    val t = new BlockTemplate("1a", candidate(), true)
    t.realTau shouldEqual BigInteger.ZERO
    t.superShareThreshold shouldEqual BigInteger.ZERO
  }

  "getJobParams" should "report the reduced difficulty only when reducedShareMessages is on" in {
    // Larger tau is an EASIER target, so dividing by the NISP coefficient hands the miner a
    // ten-thousand-times harder one and a ten-thousandth of the shares. Backwards floods the client.
    val full = template(reduced = false).getJobParams.getString(6)
    val reduced = template(reduced = true).getJobParams.getString(6)

    full shouldEqual tau4G.toString
    reduced shouldEqual tau4G.divide(BigInteger.valueOf(10000L)).toString
    BigInt(reduced) should be < BigInt(full)
  }

  "assignedThreshold" should "be exactly the difficulty the notify advertises" in {
    // Hashrate accounting credits each share TARGET_MAX over this field. If it ever drifted from the
    // notify, the rate would be off by whatever factor separates the two.
    Seq(false, true).foreach { reduced =>
      val t = template(reduced = reduced)
      t.getJobParams.getString(6) shouldEqual t.assignedThreshold.toString
    }
    template(reduced = false).assignedThreshold shouldEqual tau4G
    template(reduced = true).assignedThreshold shouldEqual
      tau4G.divide(BigInteger.valueOf(LFSMHelpers.NISP_COEFFICIENT))
  }

  it should "be tau cut by each configured reduction multiplier" in {
    // The notify and the work credit both read this field, so the multiplier has to reach it rather
    // than the NISP coefficient the five-argument constructor uses.
    configs.StratumConfig.ReductionMultipliers.foreach { m =>
      val t = new BlockTemplate("1a", candidate(), tau4G, true, true, m)
      withClue(s"multiplier $m: ") {
        t.assignedThreshold shouldEqual tau4G.divide(BigInteger.valueOf(m))
        t.getJobParams.getString(6) shouldEqual t.assignedThreshold.toString
      }
    }
  }

  it should "ignore the multiplier while reduced reporting is off" in {
    new BlockTemplate("1a", candidate(), tau4G, true, false, 10).assignedThreshold shouldEqual tau4G
  }

  it should "leave the super-share threshold at the NISP coefficient whatever the multiplier" in {
    // A super share is what a NISP is built from. Reporting less often must not change what counts.
    configs.StratumConfig.ReductionMultipliers.foreach { m =>
      new BlockTemplate("1a", candidate(), tau4G, true, true, m).superShareThreshold shouldEqual
        template().superShareThreshold
    }
  }

  it should "carry the job id, height and message the miner needs" in {
    val params = template().getJobParams
    params.getString(0) shouldEqual "1a"
    params.getLong(1) shouldEqual 479451L
    params.getString(2) shouldEqual "07" * 32
  }

  it should "return the same array to every thread that asks at once" in {
    // Fails only under a race on the pre-fix code, so treat it as a smoke test — the structural fix
    // is building it in the constructor. Kept because it states the property that has to hold.
    val t = template()
    val pool = Executors.newFixedThreadPool(16)
    try {
      val tasks: java.util.List[Callable[String]] =
        (1 to 200).map(_ => new Callable[String] { def call(): String = t.getJobParams.toString })
          .toList.asJava
      val results = pool.invokeAll(tasks).asScala.map(_.get)
      results.distinct.size shouldEqual 1
      results.head should include("1a")
    } finally {
      pool.shutdown()
      pool.awaitTermination(10, TimeUnit.SECONDS)
    }
  }
}
