package lfsm

import org.ergoplatform.appkit.ErgoId

import java.math.{BigDecimal, BigInteger, RoundingMode}
import scala.util.Try

/**
 * Helpers for Lithos Finite State Machine
 */
object LFSMHelpers {
  // Target max used in contracts, 2^255 - 1
  final val TARGET_MAX_LITHOS = BigInt("57896044618658097711785492504343953926634992332820282019728792003956564819949")
  // Rigel miner uses 2^256 as target max
  final val TARGET_MAX_STRATUM = BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639936")

  final val HOLDING_PERIOD = 360L // 360 Blocks, or 12 hours
  final val EVAL_PERIOD    = 360L
  // TODO: Change back FP token after first week of testnet
  final val FP_TOKEN       = ErgoId.create("5a3f8a958178fc6e3b37aeea8fb94d8e6d33a7e4d2c7e70aa7db4e13c08a9903")

  /**
   * Parse diff string and return its tau value
   * @param diffValue String such as "4.0G", "2.411T". Supports up to Petahash difficulty
   * @return Tau value of this difficulty
   */
  def parseDiffValueForStratum(diffValue: String): Try[BigInteger] = {
    Try {
      val pow = diffValue.last
      val num = diffValue.substring(0, diffValue.length-1).toDouble

      val powOfTen = pow match {
        case 'K' => 3
        case 'M' => 6
        case 'G' => 9
        case 'T' => 12
        case 'P' => 15
        case _ => throw new NumberFormatException("Failed to parse Power of Ten in difficulty value")
      }
      getTauFromDiffForStratum(num, powOfTen)
    }
  }

  /**
   * Get Tau from difficulty, represented as (diff E powOfTen)
   * @param diff Difficulty represented as double
   * @param powOfTen Pow of Ten to use for difficulty
   * @return Tau value as BigInteger for this difficulty
   */
  def getTauFromDiffForStratum(diff: Double, powOfTen: Int): BigInteger = {
    val diffValue = BigDecimal.valueOf(diff).scaleByPowerOfTen(powOfTen)
    val targetMax = new BigDecimal(TARGET_MAX_STRATUM.bigInteger)
    val result = targetMax.divide(diffValue, 2, RoundingMode.DOWN)
    result.toBigInteger
  }

  /**
   * Converts between Tau value and Share Score value using TARGET_MAX_LITHOS
   *
   * NOTE: This is different from Tau and difficulty, as we are using different target maximum values
   * in Stratum vs in contracts. We should treat the Tau value as the absolute truth to avoid confusion.
   * Additionally, once we have a Tau value, we should never attempt to convert it back to a difficulty.
   * @param dividend Tau or Score value
   * @return If dividend was Tau, returns Score. If dividend was Score, returns Tau
   */
  def convertTauOrScore(dividend: BigInt): BigInt = {
    TARGET_MAX_LITHOS / dividend
  }

  /**
   * Creates value of UTXO based on miner's score, total score, and total value in payout box
   * @param score Miner's score, parsed from first 8 bytes of submitted NISP
   * @param totalScore Sum of all scores, from R6 of Payout Box. NOTE: Despite being a BigInt, this is a score value
   * @param totalValue Total value of mining rewards, from R7 of Payout Box
   * @return ERG value of miner's output box
   */
  def paymentFromScore(score: Long, totalScore: BigInt, totalValue: Long): Long = {
    ((BigInt(totalValue) * BigInt(score)) / totalScore).toLong
  }
}
