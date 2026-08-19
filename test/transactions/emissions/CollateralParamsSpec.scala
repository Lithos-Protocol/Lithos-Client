package transactions.emissions

import lfsm.CollateralParams
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `permitAt` prices a Join, from parameters that live in the emission config's R4 — on chain, and
 * settable by whoever controls that box.
 *
 * The BigInt-then-clamp shape exists precisely so that no parameter choice can overflow and make
 * every Join unsatisfiable. That is a liveness property of the whole queue, not a nicety: an
 * overflowing permit stops every lender joining, including this one, and the client would report it
 * as "joins stopped working".
 */
class CollateralParamsSpec extends AnyFlatSpec with Matchers {

  private val floor = 1000000000000L      // 1,000 LIT
  private val ceiling = 30000000000000L   // the 30,000 LIT thermostat ceiling
  private val slope = 1000000000L

  private def params(f: Long = floor, c: Long = ceiling, s: Long = slope): Seq[Long] = Seq(f, c, s)

  "permitAt" should "start at the floor with an empty queue" in {
    CollateralParams.permitAt(0L, params()) shouldEqual floor
  }

  it should "rise with the backlog until it reaches the ceiling" in {
    CollateralParams.permitAt(1L, params()) shouldEqual (floor + slope)
    CollateralParams.permitAt(10L, params()) shouldEqual (floor + 10 * slope)

    val atCeiling = (ceiling - floor) / slope
    CollateralParams.permitAt(atCeiling, params()) shouldEqual ceiling
    CollateralParams.permitAt(atCeiling + 1, params()) shouldEqual ceiling
  }

  it should "clamp rather than overflow for any parameter choice" in {
    // The whole point of widening to BigInt first. Every one of these overflows a Long partway
    // through, and a wrapped negative permit is a Join nobody can satisfy.
    val hostile = Seq(
      (Long.MaxValue, Long.MaxValue, Long.MaxValue),
      (Long.MaxValue, 1L, Long.MaxValue),
      (0L, Long.MaxValue, Long.MaxValue),
      (Long.MaxValue / 2, Long.MaxValue, Long.MaxValue / 2)
    )
    val backlogs = Seq(0L, 1L, 1000L, Int.MaxValue.toLong, Long.MaxValue)

    for ((f, c, s) <- hostile; b <- backlogs) {
      withClue(s"floor=$f ceiling=$c slope=$s backlog=$b: ") {
        val permit = CollateralParams.permitAt(b, Seq(f, c, s))
        noException should be thrownBy permit
        permit should be <= c
      }
    }
  }

  it should "never return more than the ceiling" in {
    Seq(0L, 1L, 500L, 100000L, Long.MaxValue).foreach { backlog =>
      withClue(s"backlog=$backlog: ") {
        CollateralParams.permitAt(backlog, params()) should be <= ceiling
      }
    }
  }

  it should "handle a zero slope as a flat price" in {
    Seq(0L, 1L, 10000L).foreach { backlog =>
      CollateralParams.permitAt(backlog, params(s = 0L)) shouldEqual floor
    }
  }

  it should "handle a zero floor and a zero permit" in {
    // A permit of zero is legitimate — the contract reads the LIT entry with a getOrElse default,
    // and the builder omits the token entirely. This is where that starts.
    CollateralParams.permitAt(0L, params(f = 0L, s = 0L)) shouldEqual 0L
  }

  it should "reject a parameter set too short to be an R4" in {
    // Registers come off a box anyone can construct, so a short Coll is untrusted input rather than
    // an impossibility. Failing loudly here beats indexing past the end deep inside a build.
    an[IllegalArgumentException] should be thrownBy CollateralParams.permitAt(0L, Seq(1L, 2L))
    an[IllegalArgumentException] should be thrownBy CollateralParams.permitAt(0L, Seq.empty[Long])
  }

  it should "accept a longer parameter set, reading only the first three" in {
    CollateralParams.permitAt(1L, Seq(floor, ceiling, slope, 999L, 111L)) shouldEqual (floor + slope)
  }

  it should "tolerate a ceiling below the floor by clamping to the ceiling" in {
    // A misconfigured R4. The clamp is `>=`, so the ceiling wins and stays satisfiable.
    CollateralParams.permitAt(0L, params(f = 5000L, c = 100L)) shouldEqual 100L
  }
}
