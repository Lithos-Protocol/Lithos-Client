package api.models

import scala.util.{Failure, Success, Try}

/**
 * Every numeric amount in the `lithosdex` tag crosses the wire as a decimal STRING, not a JSON number.
 *
 * `accX` / `accY` are BigInt scaled by 1e27 and run well past the exact range of a double, so a JS
 * client parsing them as numbers corrupts them silently. Rather than have two rules — strings for the
 * accumulators, numbers for everything else — every amount is a string and there is one rule.
 *
 * Counts that are not amounts stay numbers, matching the spec: block heights, `timestamp`, the fee
 * numerators on [[LDFeeParams]], and every ratio typed as a double.
 */
object LDAmounts {

  def apply(v: Long): String = v.toString

  def apply(v: BigInt): String = v.toString

  /**
   * Parse a request field, naming it so a bad body reads as a bad body rather than a stack trace.
   *
   * Range-checked rather than truncated.
   */
  def parseLong(field: String, raw: String): Long =
    Try(BigInt(raw.trim)) match {
      case Success(v) if v.isValidLong => v.toLong
      case Success(v) => throw new IllegalArgumentException(
        s"'$field' is out of range for a 64-bit amount, got '$v'")
      case Failure(_) => throw new IllegalArgumentException(
        s"'$field' must be a decimal integer string, got '$raw'")
    }

  def parseLong(field: String, raw: Option[String]): Option[Long] =
    raw.map(r => parseLong(field, r))
}
