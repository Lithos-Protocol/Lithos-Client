package stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import storage._

import java.io.IOException
import java.nio.file.Files
import scala.collection.JavaConverters._

object MiningStatsStoreSpec {
  val identity = Json.obj("schema" -> 1, "miner" -> "local")
  def cursor(height: Int, branch: String = "a", time: Long = 0L): MiningCursor =
    MiningCursor(height, s"$branch-$height", s"$branch-${height - 1}", time + height.toLong * 1000)
  def record(at: MiningCursor, previous: MiningCursor, amount: Long = Long.MaxValue,
             finderFee: Long = 0L): MiningBlockRecord =
    MiningBlockRecord(at, previous, (BigInt(1) << 100).toString,
      Vector(LithosBlockRecord(at.blockId, at.height, at.timestamp, s"genesis-${at.blockId}",
        "holding", "collateral", "1000000", "2000000", finderFee.toString)),
      Vector(MiningPaymentRecord(s"pay-${at.blockId}", s"output-${at.blockId}", "payout", "nft", "mined", 1,
        at.blockId, at.height, at.timestamp, amount.toString)))
}

class MiningStatsStoreSpec extends AnyFlatSpec with Matchers {
  import MiningStatsStoreSpec._

  "Mining history" should "reopen a real database with exact totals and resume without duplicates" in {
    val path = Files.createTempDirectory("mining-stats-")
    var opened: Option[MiningStatsStore] = None
    def open(): MiningStatsStore = {
      val db = new MiningStatsStore(Some(LevelDbKeyValueStore.openOrThrow(path)), identity)
      opened = Some(db)
      db
    }
    try {
      val first = open()
      first.initialize(cursor(1))
      first.append(record(cursor(2), cursor(1)))
      first.close()
      val second = open()
      second.append(record(cursor(2), cursor(1)))
      second.append(record(cursor(3), cursor(2)))
      second.state.get.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(2, 2, (BigInt(Long.MaxValue) * 2).toString)
      second.close()
      open().state.get.cursor shouldBe cursor(3)
    } finally {
      opened.foreach(_.close())
      val files = Files.walk(path)
      try files.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally files.close()
    }
  }

  it should "remove displaced contributions and accept a replacement at the same height" in {
    val db = new MiningStatsStore(None, identity)
    db.initialize(cursor(1))
    db.append(record(cursor(2), cursor(1), 100))
    db.rollback()
    db.state.get.totals shouldBe MiningTotals()
    db.block(2) shouldBe None
    val replacement = cursor(2, "b").copy(parentId = cursor(1).blockId)
    db.append(record(replacement, cursor(1), 42))
    db.view("ready", 2, false, 0).payments.map(_.grossNanoErg) shouldBe Vector("42")
    db.state.get.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(1, 1, "42")
  }

  it should "commit record and cursor atomically and leave both unchanged on a failed write" in {
    val memory = new InMemoryKeyValueStore
    var failWrites = false
    var failAfterCommit = false
    val backend = new KeyValueStore {
      override def path = memory.path
      override def backend = memory.backend
      override def get(k: Array[Byte]) = memory.get(k)
      override def scanPrefix(k: Array[Byte]) = memory.scanPrefix(k)
      override def scanKeys(k: Array[Byte]) = memory.scanKeys(k)
      override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]) = memory.readSnapshot(f)
      override def close() = memory.close()
      override def write(m: Seq[KeyValueMutation], d: WriteDurability) = {
        if (failAfterCommit) memory.write(m, d)
        if (failWrites || failAfterCommit) Left(StoreError.WriteFailed(path, new IOException("disk failure"))) else memory.write(m, d)
      }
    }
    val db = new MiningStatsStore(Some(backend), identity)
    db.initialize(cursor(1))
    memory.clearRecordedBatches()
    failWrites = true
    intercept[StoreException](db.append(record(cursor(2), cursor(1))))
    db.state.get.cursor shouldBe cursor(1)
    db.block(2) shouldBe None
    failWrites = false
    db.append(record(cursor(2), cursor(1)))
    memory.recordedBatches.map(_.size) shouldBe Vector(4) // record, two buckets and checkpoint
    failWrites = true
    intercept[StoreException](db.rollback())
    db.state.get.cursor shouldBe cursor(2)
    db.block(2) should not be empty
    new MiningStatsStore(Some(backend), identity).state shouldBe db.state
    // The backend completed a rollback, then reported an I/O error. The next cycle must read its durable result.
    failWrites = false
    failAfterCommit = true
    intercept[StoreException](db.rollback())
    db.state.get.cursor shouldBe cursor(2)
    db.reloadCheckpoint()
    db.state.get.cursor shouldBe cursor(1)
    db.state.get.totals shouldBe MiningTotals()
    failAfterCommit = false
    db.append(record(cursor(2), cursor(1), 7))
    db.state.get.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(1, 1, "7")
  }

  it should "bound pruning and preserve rollback records while subtracting expired totals" in {
    val db = new MiningStatsStore(None, identity)
    db.initialize(cursor(1))
    (2 to 270).foreach(h => db.append(record(cursor(h), cursor(h - 1), 1)))
    db.prune(Long.MaxValue, limit = 2, rollbackBlocks = 256) shouldBe 2
    db.state.get.firstHeight shouldBe 4
    db.state.get.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(267, 267, "267")
    db.prune(Long.MaxValue, limit = 100, rollbackBlocks = 256) shouldBe 11
    db.state.get.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(256, 256, "256")
    (1 to 256).foreach(_ => db.rollback())
    db.state.get.totals shouldBe MiningTotals()
    db.initialize(cursor(500))
    db.state.get.firstHeight shouldBe 501
  }

  it should "refuse identity mismatches and corrupt records without overwriting them" in {
    val memory = new InMemoryKeyValueStore
    val db = new MiningStatsStore(Some(memory), identity)
    db.initialize(cursor(1))
    memory.clearRecordedBatches()
    intercept[IllegalArgumentException](new MiningStatsStore(Some(memory), identity ++ Json.obj("miner" -> "other")))
    memory.recordedBatches shouldBe empty
    KeyValueStore.orThrow(memory.put("state".getBytes("UTF-8"), "{}".getBytes("UTF-8")))
    intercept[Exception](new MiningStatsStore(Some(memory), identity))
  }

  it should "reject gaps and duplicate payment outputs before advancing a cursor" in {
    val db = new MiningStatsStore(None, identity)
    db.initialize(cursor(1))
    intercept[IllegalArgumentException](db.append(record(cursor(3), cursor(1))))
    val next = record(cursor(2), cursor(1))
    intercept[IllegalArgumentException](db.append(next.copy(payments = next.payments ++ next.payments)))
    db.state.get.cursor shouldBe cursor(1)
  }

  it should "correct hourly and daily buckets on rollback and pruning and bound history pages" in {
    val db = new MiningStatsStore(None, identity)
    val start = MiningHistory.DayMs * 2
    val anchor = cursor(1).copy(timestamp = start - 1)
    val first = cursor(2).copy(timestamp = start + 1)
    val second = cursor(3).copy(timestamp = start + MiningHistory.HourMs + 1)
    db.initialize(anchor)
    db.append(record(first, anchor, 11))
    db.append(record(second, first, 22))
    val buckets = db.buckets(start, start + MiningHistory.HourMs * 2, MiningHistory.HourMs)
    buckets.buckets.map(_.totals.amount("local.payout.grossNanoErg")) shouldBe Vector(BigInt(11), BigInt(22))
    buckets.partial shouldBe true // the last bucket has not finished yet
    db.buckets(start, start + MiningHistory.DayMs, MiningHistory.DayMs).buckets.head.totals
      .amount("chain.difficultySum") shouldBe (BigInt(1) << 101)
    db.history(0, 1).nextHeight shouldBe Some(3)
    db.history(3, 1).records.map(_.cursor.height) shouldBe Vector(3)
    db.rollback()
    db.buckets(start, start + MiningHistory.HourMs * 2, MiningHistory.HourMs).buckets should have size 1
    db.prune(Long.MaxValue, 1, rollbackBlocks = 0) shouldBe 1
    db.buckets(start, start + MiningHistory.DayMs, MiningHistory.DayMs).buckets shouldBe empty
    intercept[IllegalArgumentException](db.history(0, 501))
    intercept[IllegalArgumentException](db.buckets(1, start, MiningHistory.HourMs))
    intercept[IllegalArgumentException](db.buckets(0, MiningHistory.HourMs * 501, MiningHistory.HourMs))
  }

  it should "sample local sessions independently of canonical rollback and prune them in bounded passes" in {
    val db = new MiningStatsStore(None, identity)
    val hour = MiningHistory.HourMs
    val first = LocalMiningObservation("shares", "a", 1, hour, hour + 1, Map("accepted" -> "100"))
    db.saveLocal(Vector(first, first.copy(sequence = 2, counters = Map("accepted" -> "200")), first))
    db.localHistory("shares", hour, hour * 2).head.counters("accepted") shouldBe "200"
    val restarted = first.copy(session = "b", startedAt = hour + 3, sequence = 1, counters = Map("accepted" -> "1"))
    db.saveLocal(Vector(restarted, first.copy(sequence = 3)))
    db.localHistory("shares", hour, hour * 2).head.session shouldBe "b"
    db.initialize(cursor(1))
    db.append(record(cursor(2), cursor(1)))
    db.rollback()
    db.localHistory("shares", hour, hour * 2) should have size 1
    db.pruneLocal(hour * 2, 1)
    db.localHistory("shares", hour, hour * 2) shouldBe empty
  }

  "Hashrate estimates" should "use confirmed difficulty sums, covered time and explicit sampling uncertainty" in {
    val hour = MiningHistory.HourMs
    val at = cursor(20).copy(timestamp = hour)
    val totals = MiningAccountingTotals(Map("lithos.blocks" -> "4", "lithos.difficultySum" -> "3600000"))
    val history = MiningBucketHistory(at, Some(0), 0, hour, hour,
      Vector(MiningBucket(0, hour, totals)), partial = false, status = "ready")
    val estimate = MiningHistory.hashrate(history)
    estimate.hashesPerSecond shouldBe Some("1000")
    estimate.sampleCount shouldBe 4
    estimate.relativeSamplingError shouldBe Some(0.5)
    estimate.status shouldBe "sparse"
    estimate.partial shouldBe false
    val shortened = MiningHistory.hashrate(history.copy(retainedFrom = Some(hour / 2), partial = true))
    shortened.hashesPerSecond shouldBe Some("2000")
    shortened.from shouldBe hour / 2
    MiningHistory.hashrate(history.copy(buckets = Vector.empty)).hashesPerSecond shouldBe None
  }

  it should "report whole hashes per second when the work does not divide evenly" in {
    // A fractional string read by an integer parser comes out as nothing, which blanked the rate.
    val hour = MiningHistory.HourMs
    val totals = MiningAccountingTotals(Map("lithos.blocks" -> "89", "lithos.difficultySum" -> "990588108800"))
    val history = MiningBucketHistory(cursor(20).copy(timestamp = hour), Some(0), 0, hour, hour,
      Vector(MiningBucket(0, hour, totals)), partial = false, status = "ready")
    MiningHistory.hashrate(history).hashesPerSecond shouldBe Some("275163363")
  }
}
