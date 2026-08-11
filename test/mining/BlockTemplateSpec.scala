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

  private val shippedTau = BigInteger.valueOf(4000000000L) // stratum.diff = "4.0G"

  private def candidate(height: Long = 479451L, b: BigInteger = BigInteger.ONE): MiningCandidate =
    new MiningCandidate(Array.fill[Byte](32)(7), height, 3, b, "02" * 33, null, null)

  private def template(tau: BigInteger = shippedTau,
                       usedCollateral: Boolean = true,
                       reduced: Boolean = false): BlockTemplate =
    new BlockTemplate("1a", candidate(), tau, usedCollateral, reduced)

  /** Exactly the expression share validation used to evaluate per share. */
  private def oldRealTau(tau: BigInteger): BigInteger =
    LFSMHelpers.convertTauOrScore(LFSMHelpers.convertTauOrScore(scala.math.BigInt(tau))).bigInteger

  "realTau" should "equal the expression share validation used to compute per share" in {
    Seq(
      shippedTau,
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
    // Larger tau is an EASIER target, so dividing by 1000 hands the miner a thousand-times harder
    // one and a thousandth of the shares. Getting this backwards floods the client instead.
    val full = template(reduced = false).getJobParams.getString(6)
    val reduced = template(reduced = true).getJobParams.getString(6)

    full shouldEqual shippedTau.toString
    reduced shouldEqual shippedTau.divide(BigInteger.valueOf(1000L)).toString
    BigInt(reduced) should be < BigInt(full)
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
