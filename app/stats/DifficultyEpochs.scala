package stats

import play.api.libs.json.{Json, OFormat, OWrites}

/**
 * One difficulty epoch, and the whole reason this table exists.
 *
 * Ergo recalculates difficulty only at heights congruent to 1 modulo the epoch length, so every
 * block in `[startHeight, endHeight]` carries the same required difficulty. Storing a point per
 * block therefore stored the same number 128 times; one record per epoch says the same thing and
 * is cheap enough to keep for years rather than for the retention window.
 *
 * `hashesPerSecond` divides the work of the epoch's block intervals by the time those same
 * intervals took, so both sides count `endHeight - startHeight` gaps. It is absent for an epoch
 * whose timestamps did not advance.
 */
final case class DifficultyEpoch(index: Int, startHeight: Int, endHeight: Int,
                                 startTimestamp: Long, endTimestamp: Long,
                                 difficulty: String, complete: Boolean,
                                 hashesPerSecond: Option[String])

object DifficultyEpoch {
  implicit val format: OFormat[DifficultyEpoch] = Json.format[DifficultyEpoch]

  /** Fills in the derived rate, so no caller has to decide which block gaps the elapsed time covers. */
  def of(index: Int, start: DifficultySample, end: DifficultySample, complete: Boolean): DifficultyEpoch = {
    require(end.height >= start.height, "a difficulty epoch cannot end before it starts")
    val intervals = end.height - start.height
    val elapsed = end.timestamp - start.timestamp
    val rate =
      if (intervals > 0 && elapsed > 0)
        Some(((BigInt(start.difficulty) * intervals * 1000) / elapsed).toString)
      else None
    DifficultyEpoch(index, start.height, end.height, start.timestamp, end.timestamp,
      start.difficulty, complete, rate)
  }
}

/** Height, timestamp and required difficulty from one canonical header. */
final case class DifficultySample(height: Int, timestamp: Long, difficulty: String)

object DifficultySample {
  implicit val format: OFormat[DifficultySample] = Json.format[DifficultySample]
}

/**
 * A page of the epoch table. `current` is the epoch in progress, which is recomputed every cycle
 * rather than stored, so it is the only entry that can still change.
 */
final case class DifficultyEpochHistory(source: MiningCursor, epochLength: Int,
                                        epochs: Vector[DifficultyEpoch],
                                        current: Option[DifficultyEpoch],
                                        oldestIndex: Option[Int], newestIndex: Option[Int],
                                        backfillComplete: Boolean, status: String = "unverified")

object DifficultyEpochHistory {
  import MiningStatsData.cursorFormat
  implicit val writes: OWrites[DifficultyEpochHistory] = Json.writes[DifficultyEpochHistory]
}

object DifficultyEpochs {
  /**
   * The epoch length this table assumes. EIP-37 set it to 128 on mainnet at its activation height,
   * and testnet has used 128 throughout. Mainnet blocks below the activation belong to 1024-block
   * epochs, so the table floors there rather than describing them with the wrong length.
   */
  val EpochLength: Int = 128

  /** Mainnet only. Below this, difficulty readjusted on 1024-block epochs. */
  val Eip37ActivationHeight: Int = 844673

  /**
   * How many finalized epochs to keep: about 2 years of mainnet history for roughly 1 MB. The
   * table is a display series, not an input to any mining decision, so the cap is a constant
   * rather than another configurable retention knob.
   */
  val MaxEpochs: Int = 4096

  /** Epochs never finalize within this distance of the tip, so a stored record cannot be reorged. */
  val ReorgMargin: Int = EpochLength

  def indexOf(height: Int): Int = {
    require(height >= 1, "heights below 1 are not in a difficulty epoch")
    (height - 1) / EpochLength
  }
  def startHeight(index: Int): Int = index * EpochLength + 1
  def endHeight(index: Int): Int = (index + 1) * EpochLength

  /** The oldest epoch this table may describe, given the network and how much of it we keep. */
  def floorIndex(newest: Int, mainnet: Boolean): Int = {
    val retained = newest - MaxEpochs + 1
    val supported = if (mainnet) indexOf(Eip37ActivationHeight) else 0
    math.max(0, math.max(retained, supported))
  }

  /**
   * The newest epoch whose last block is at least `ReorgMargin` behind `tip`. The epoch holding
   * `tip - ReorgMargin` is not itself safe, since its own end is nearer the tip than that.
   */
  def finalizableIndex(tip: Int): Option[Int] =
    if (tip <= EpochLength + ReorgMargin) None else Some(indexOf(tip - ReorgMargin) - 1)

  /**
   * The most epochs one request may return. Kept well below `MaxEpochs` because a page is the only
   * part of this table that is ever resident: the records themselves stay in the key-value store
   * and are read by range. No chart resolves more points than this anyway.
   */
  val MaxPage: Int = 512

  /** Validates a requested window, so a bad range is a 400 rather than a worker exception. */
  def resolve(from: Option[Int], to: Option[Int], limit: Int): (Option[Int], Option[Int], Int) = {
    require(limit > 0 && limit <= MaxPage, s"'limit' must be between 1 and $MaxPage epochs")
    require(from.forall(_ >= 0), "'from' must be a nonnegative epoch index")
    require(to.forall(_ >= 0), "'to' must be a nonnegative epoch index")
    for (f <- from; t <- to) require(t >= f, "'to' must not precede 'from'")
    (from, to, limit)
  }
}
