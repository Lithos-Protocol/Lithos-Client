package stats

import sigma.util.NBitsUtils

/** The last block of one difficulty epoch, which is all the retarget rule reads of it. */
final case class EpochEnd(height: Int, timestamp: Long, difficulty: BigInt)

/**
 * The difficulty the epoch after `epoch - 1` will have.
 *
 * @param epoch      the epoch the difficulty applies to
 * @param exact      true once the epoch before it has ended, so this is the value its blocks will carry
 * @param fromHeight the newest block the figure was computed from
 */
final case class NextDifficultyEstimate(epoch: Int, difficulty: BigInt, exact: Boolean, fromHeight: Int)

/**
 * Ergo's difficulty retarget, as the node computes it at an epoch's first block.
 *
 * Mainnet uses EIP-37: a linear fit over the last 8 epochs' durations, limited to a 50% move, averaged with
 * the last epoch's duration alone and limited again. Testnet uses the fit alone. Both read only the last
 * block of each of the last 9 epochs, so the next epoch's difficulty is public once the current one ends,
 * and closely predictable before that from its pace so far.
 */
object NextDifficulty {

  /**
   * @param blockIntervalMs the target block time
   * @param eip37           whether the EIP-37 limits and average apply, as on mainnet
   */
  final case class Rule(blockIntervalMs: Long, eip37: Boolean)

  val Mainnet: Rule = Rule(blockIntervalMs = 120000L, eip37 = true)
  val Testnet: Rule = Rule(blockIntervalMs = 45000L, eip37 = false)

  def rule(mainnet: Boolean): Rule = if (mainnet) Mainnet else Testnet

  final val EpochLength: Int = DifficultyEpochs.EpochLength

  /** Epochs whose durations the fit reads. One more epoch end than this is needed. */
  final val UseLastEpochs: Int = 8

  private final val Precision = BigInt(1000000000)

  /**
   * The difficulty of the epoch after the last in `ends`, from the last blocks of the 9 epochs before it,
   * oldest first. Rounded through the compact nBits form exactly as the node does.
   */
  def after(ends: Seq[EpochEnd], rule: Rule): BigInt = {
    require(ends.size == UseLastEpochs + 1, s"the rule reads ${UseLastEpochs + 1} epoch ends, got ${ends.size}")
    require(ends.sliding(2).forall { case Seq(a, b) => b.height - a.height == EpochLength },
      s"epoch ends must be $EpochLength blocks apart")
    val predictive = fitted(ends, rule)
    if (!rule.eip37) predictive
    else {
      val last = ends.last.difficulty
      val limitedPredictive = limited(predictive, last)
      val classic = epochDifficulty(ends(ends.size - 2), ends.last, rule)
      normalized(limited((classic + limitedPredictive) / 2, last))
    }
  }

  /**
   * The next epoch's difficulty from the difficulty table.
   *
   * @param complete the 8 epochs before `current`, oldest first, each ending at its last block
   * @param current  the epoch in progress, ending at the tip. When it has not reached its last block, its end
   *                 is projected at its pace so far, measured from the end of the epoch before it.
   */
  def estimate(complete: Seq[DifficultyEpoch], current: DifficultyEpoch, rule: Rule): Either[String, NextDifficultyEstimate] = {
    val wanted = (current.index - UseLastEpochs) until current.index
    val byIndex = complete.map(e => e.index -> e).toMap
    wanted.find(i => !byIndex.get(i).exists(e => e.complete && e.endHeight == DifficultyEpochs.endHeight(i))) match {
      case Some(missing) => Left(s"the difficulty table does not hold epoch $missing yet")
      case None =>
        val ends = wanted.map(i => EpochEnd(byIndex(i).endHeight, byIndex(i).endTimestamp, BigInt(byIndex(i).difficulty)))
        val previous = ends.last
        val lastHeight = DifficultyEpochs.endHeight(current.index)
        val elapsed = current.endTimestamp - previous.timestamp
        val intervals = current.endHeight - previous.height
        if (intervals <= 0 || elapsed <= 0) Left(s"epoch ${current.index} has no blocks timed after its start yet")
        else {
          val exact = current.endHeight == lastHeight
          val end = if (exact) current.endTimestamp else previous.timestamp + elapsed * EpochLength / intervals
          val difficulty = after(ends :+ EpochEnd(lastHeight, end, BigInt(current.difficulty)), rule)
          Right(NextDifficultyEstimate(current.index + 1, difficulty, exact, current.endHeight))
        }
    }
  }

  /** The node's linear fit of each epoch's implied difficulty, extended one epoch past the last. */
  private def fitted(ends: Seq[EpochEnd], rule: Rule): BigInt =
    if (ends.head.timestamp >= ends.last.timestamp) normalized(ends.head.difficulty)
    else {
      val data = ends.sliding(2).map { case Seq(start, end) => (BigInt(end.height), epochDifficulty(start, end, rule)) }.toSeq
      val n = data.size
      val xy = data.map { case (x, y) => x * y }.sum
      val x = data.map(_._1).sum
      val x2 = data.map { case (h, _) => h * h }.sum
      val y = data.map(_._2).sum
      val b = (xy * n - x * y) * Precision / (x2 * n - x * x)
      val a = (y * Precision - b * x) / n / Precision
      val point = data.map(_._1).max + EpochLength
      normalized(a + b * point / Precision)
    }

  /** The difficulty that would have made `end`'s epoch take exactly the target time. */
  private def epochDifficulty(start: EpochEnd, end: EpochEnd, rule: Rule): BigInt =
    end.difficulty * rule.blockIntervalMs * EpochLength / (end.timestamp - start.timestamp)

  /** Within half and one and a half times `last`. */
  private def limited(value: BigInt, last: BigInt): BigInt =
    if (value > last) value.min(last * 3 / 2) else value.max(last / 2)

  private def normalized(value: BigInt): BigInt =
    NBitsUtils.decodeCompactBits(NBitsUtils.encodeCompactBits(value))
}
