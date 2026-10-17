package stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source

/**
 * The retarget rule against real mainnet epochs. Every difficulty here was read from a mainnet node, so an
 * exact match on each epoch is the node's own rule reproduced, compact-bits rounding included.
 */
class NextDifficultySpec extends AnyFlatSpec with Matchers {

  private def rows(name: String): Vector[Array[String]] = {
    val src = Source.fromResource(s"node/$name")
    try src.getLines().filterNot(_.startsWith("#")).map(_.split("\t")).toVector finally src.close()
  }

  /** Epochs 14760 to 14811: index, last height, last timestamp, difficulty. */
  private val ends = rows("mainnet-epoch-ends.tsv").map(r => (r(0).toInt, EpochEnd(r(1).toInt, r(2).toLong, BigInt(r(3)))))

  /** Blocks inside epoch 14811, then the first block of 14812: height, timestamp, difficulty. */
  private val progress = rows("mainnet-epoch-14811-progress.tsv").map(r => (r(0).toInt, r(1).toLong, BigInt(r(2))))

  /** Epoch 14812's difficulty, carried by its first block. */
  private val epoch14812 = progress.last._3

  private def table(index: Int): DifficultyEpoch = {
    val end = ends.find(_._1 == index).get._2
    DifficultyEpoch(index, DifficultyEpochs.startHeight(index), end.height, end.timestamp - 1,
      end.timestamp, end.difficulty.toString, complete = true, None)
  }

  /** Epoch 14811 as the stats table holds it while it is in progress, ending at `height`. */
  private def live(height: Int, timestamp: Long): DifficultyEpoch =
    DifficultyEpoch(14811, DifficultyEpochs.startHeight(14811), height, timestamp - 1, timestamp,
      progress.head._3.toString, complete = false, None)

  "NextDifficulty.after" should "reproduce every mainnet epoch's difficulty from the 9 epochs before it" in {
    val checked = (8 until ends.size - 1).map { i =>
      val window = ends.slice(i - 8, i + 1).map(_._2)
      withClue(s"epoch ${ends(i + 1)._1}: ") {
        NextDifficulty.after(window, NextDifficulty.Mainnet) shouldBe ends(i + 1)._2.difficulty
      }
    }
    checked should have size 43
  }

  it should "give the difficulty the newest epoch's first block carries" in {
    NextDifficulty.after(ends.takeRight(9).map(_._2), NextDifficulty.Mainnet) shouldBe epoch14812
  }

  it should "refuse epoch ends that are not 128 blocks apart, or not 9 of them" in {
    val nine = ends.takeRight(9).map(_._2)
    an[IllegalArgumentException] should be thrownBy NextDifficulty.after(nine.tail, NextDifficulty.Mainnet)
    an[IllegalArgumentException] should be thrownBy
      NextDifficulty.after(nine.updated(4, nine(4).copy(height = nine(4).height + 1)), NextDifficulty.Mainnet)
  }

  "NextDifficulty.estimate" should "be exact once the current epoch has reached its last block" in {
    val complete = (14803 to 14810).map(table)
    val last = ends.last._2
    NextDifficulty.estimate(complete, live(last.height, last.timestamp), NextDifficulty.Mainnet) shouldBe
      Right(NextDifficultyEstimate(14812, epoch14812, exact = true, last.height))
  }

  it should "project the current epoch's end at its pace so far, close to the real value late in the epoch" in {
    val complete = (14803 to 14810).map(table)
    val errors = progress.init.map { case (height, timestamp, _) =>
      val estimate = NextDifficulty.estimate(complete, live(height, timestamp), NextDifficulty.Mainnet).toOption.get
      estimate.exact shouldBe false
      estimate.epoch shouldBe 14812
      (BigDecimal(estimate.difficulty) / BigDecimal(epoch14812) - 1).abs
    }
    // 32, 63, 95 and 119 blocks into the epoch: 13.6%, 8.3%, 1.1% and 0.6% off. Block times are noisy enough
    // that an early projection is no better than the typical 6% epoch move; a late one is close.
    errors(2) should be < BigDecimal("0.02")
    errors(3) should be < BigDecimal("0.01")
    errors(3) should be < errors(0)
  }

  it should "say which epoch the table is missing, rather than estimate from a gap" in {
    val gapped = (14803 to 14810).filterNot(_ == 14806).map(table)
    val last = ends.last._2
    NextDifficulty.estimate(gapped, live(last.height, last.timestamp), NextDifficulty.Mainnet) shouldBe
      Left("the difficulty table does not hold epoch 14806 yet")
  }
}
