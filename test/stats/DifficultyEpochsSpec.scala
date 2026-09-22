package stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DifficultyEpochsSpec extends AnyFlatSpec with Matchers {
  import DifficultyEpochs._

  private def sample(height: Int, timestamp: Long, difficulty: BigInt = BigInt(1) << 60) =
    DifficultySample(height, timestamp, difficulty.toString)

  "Epoch indexing" should "match where Ergo actually readjusts difficulty" in {
    // The node recalculates when parentHeight % 128 == 0, so the new value takes effect at
    // heights congruent to 1 and an epoch spans [128k + 1, 128k + 128].
    indexOf(1) shouldBe 0
    indexOf(128) shouldBe 0
    indexOf(129) shouldBe 1
    indexOf(256) shouldBe 1
    startHeight(0) shouldBe 1
    endHeight(0) shouldBe 128
    startHeight(1) shouldBe 129
    endHeight(1) shouldBe 256
    (1 to 2000).foreach { h =>
      val index = indexOf(h)
      withClue(s"height $h in epoch $index: ") {
        startHeight(index) should be <= h
        endHeight(index) should be >= h
      }
    }
    an[IllegalArgumentException] should be thrownBy indexOf(0)
  }

  it should "only finalize epochs a full margin behind the tip" in {
    finalizableIndex(1) shouldBe None
    finalizableIndex(EpochLength + ReorgMargin) shouldBe None
    // 257 - 128 = 129, which is epoch 1; epoch 1 ends at 256 and is not yet safe, so 0 is.
    finalizableIndex(257) shouldBe Some(0)
    val tip = 10000
    val last = finalizableIndex(tip).get
    endHeight(last) should be <= tip - ReorgMargin
    endHeight(last + 1) should be > tip - ReorgMargin
  }

  it should "floor mainnet at the EIP-37 activation and elsewhere at the retained window" in {
    // Below the activation, mainnet difficulty moved on 1024-block epochs, which this table
    // deliberately does not describe.
    val activation = indexOf(Eip37ActivationHeight)
    floorIndex(activation + 10, mainnet = true) shouldBe activation
    floorIndex(activation + MaxEpochs * 2, mainnet = true) shouldBe activation + MaxEpochs + 1
    floorIndex(10, mainnet = false) shouldBe 0
    floorIndex(MaxEpochs * 2, mainnet = false) shouldBe MaxEpochs + 1
  }

  "An epoch" should "rate its work over the intervals its timestamps actually cover" in {
    val difficulty = BigInt(1) << 40
    // 127 gaps of one second each: the work of 127 intervals over the 127 seconds they took.
    val epoch = DifficultyEpoch.of(0, sample(1, 0L, difficulty), sample(128, 127000L, difficulty),
      complete = true)
    epoch.hashesPerSecond shouldBe Some((difficulty * 127 * 1000 / 127000).toString)
    epoch.hashesPerSecond shouldBe Some(difficulty.toString)
    epoch.complete shouldBe true
    epoch.startHeight shouldBe 1
    epoch.endHeight shouldBe 128
  }

  it should "omit the rate rather than divide by a span of zero" in {
    DifficultyEpoch.of(3, sample(385, 5000L), sample(385, 5000L), complete = false)
      .hashesPerSecond shouldBe None
    DifficultyEpoch.of(3, sample(385, 5000L), sample(400, 5000L), complete = false)
      .hashesPerSecond shouldBe None
    an[IllegalArgumentException] should be thrownBy
      DifficultyEpoch.of(3, sample(400, 1000L), sample(385, 5000L), complete = true)
  }

  "The epoch table" should "round-trip, track its bounds and prune from the oldest end in bounded batches" in {
    val db = new MiningStatsStore(None, MiningStatsStoreSpec.identity)
    db.epochBounds shouldBe None
    db.epochRange(0, 4) shouldBe empty

    def epochAt(index: Int) = DifficultyEpoch.of(index,
      sample(startHeight(index), index.toLong * 256000),
      sample(endHeight(index), index.toLong * 256000 + 127000), complete = true)

    // Forward collection writes upwards and backfill downwards; both must move only their own marker.
    db.saveEpochs((10 to 14).map(epochAt))
    db.epochBounds shouldBe Some((10, 14))
    db.saveEpochs((5 to 9).map(epochAt))
    db.epochBounds shouldBe Some((5, 14))
    db.epochRange(5, 14).map(_.index) shouldBe (5 to 14)
    db.epoch(7).map(_.startHeight) shouldBe Some(startHeight(7))
    db.epoch(99) shouldBe None

    // A gap is skipped rather than faked: the range returns what exists.
    db.saveEpochs(Seq(epochAt(20)))
    db.epochRange(5, 20).map(_.index) shouldBe ((5 to 14) :+ 20)

    db.pruneEpochs(floor = 8) shouldBe 3
    db.epochBounds shouldBe Some((8, 20))
    db.epoch(7) shouldBe None
    db.pruneEpochs(floor = 8) shouldBe 0
    db.pruneEpochs(floor = 18, limit = 2) shouldBe 2
    db.epochBounds shouldBe Some((10, 20))
    an[IllegalArgumentException] should be thrownBy db.epochRange(4, 3)
    an[IllegalArgumentException] should be thrownBy db.saveEpochs(Seq(epochAt(3).copy(startHeight = 1)))
  }

  "A requested window" should "reject a range no page could satisfy" in {
    resolve(None, None, 256) shouldBe ((None, None, 256))
    resolve(Some(4), Some(9), 8) shouldBe ((Some(4), Some(9), 8))
    an[IllegalArgumentException] should be thrownBy resolve(None, None, 0)
    an[IllegalArgumentException] should be thrownBy resolve(None, None, MaxPage + 1)
    an[IllegalArgumentException] should be thrownBy resolve(Some(-1), None, 8)
    an[IllegalArgumentException] should be thrownBy resolve(Some(9), Some(4), 8)
  }
}
