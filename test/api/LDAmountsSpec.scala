package api

import api.models.LDAmounts
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The wire encoding for every LithosDex amount, and the one place a request's numbers are believed.
 *
 * Amounts cross as decimal strings because `accX`/`accY` are BigInt scaled by 1e27 and a JS client
 * parsing them as numbers corrupts them silently. That makes `parseLong` the boundary at which a
 * caller-supplied string becomes an amount this client will spend against — so it has to reject
 * anything a Long cannot hold rather than truncate it.
 */
class LDAmountsSpec extends AnyFlatSpec with Matchers {

  "parseLong" should "accept an ordinary decimal amount" in {
    LDAmounts.parseLong("amountIn", " 1500000000 ") shouldEqual 1500000000L
    LDAmounts.parseLong("minOutput", "0") shouldEqual 0L
    LDAmounts.parseLong("bound", Long.MaxValue.toString) shouldEqual Long.MaxValue
    LDAmounts.parseLong("bound", Long.MinValue.toString) shouldEqual Long.MinValue
  }

  it should "reject a value one past Long.MaxValue instead of wrapping it negative" in {
    // `BigInt(...).toLong` returned Long.MinValue here. On `amountIn` that is a negative swap the
    // builder then refuses for the wrong reason; the field name in the error is what a caller acts on.
    val past = (BigInt(Long.MaxValue) + 1).toString
    val thrown = intercept[IllegalArgumentException](LDAmounts.parseLong("amountIn", past))
    thrown.getMessage should include("amountIn")
    thrown.getMessage should include("out of range")
  }

  it should "reject a value past 2^64 instead of wrapping it small and positive" in {
    // The dangerous direction. 2^64 + 1 truncated to 1, so a caller asking for an impossible floor
    // got a floor of one nanoERG — which is no floor at all, and the swap executes at any price the
    // pool happens to be at.
    val wrapped = (BigInt(2).pow(64) + 1).toString
    intercept[IllegalArgumentException](LDAmounts.parseLong("minOutput", wrapped))
  }

  it should "reject text that is not a decimal integer" in {
    intercept[IllegalArgumentException](LDAmounts.parseLong("shares", "1.5"))
    intercept[IllegalArgumentException](LDAmounts.parseLong("shares", "0x10"))
    intercept[IllegalArgumentException](LDAmounts.parseLong("shares", ""))
    intercept[IllegalArgumentException](LDAmounts.parseLong("shares", "1e9"))
  }

  it should "carry the same rule through the optional form" in {
    LDAmounts.parseLong("maxAmountX", None) shouldEqual None
    LDAmounts.parseLong("maxAmountX", Some("42")) shouldEqual Some(42L)
    intercept[IllegalArgumentException](
      LDAmounts.parseLong("maxAmountX", Some((BigInt(2).pow(64) + 1).toString)))
  }

  "apply" should "write amounts as strings on both the Long and BigInt paths" in {
    LDAmounts(1234L) shouldEqual "1234"
    LDAmounts(BigInt(10).pow(27) * 5) shouldEqual "5000000000000000000000000000"
  }
}
