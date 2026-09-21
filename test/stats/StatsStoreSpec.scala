package stats

import configs.StatsStorageConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import storage.{InMemoryKeyValueStore, KeyValueMutation, KeyValueStore, LevelDbKeyValueStore}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.JavaConverters._

class StatsStoreSpec extends AnyFlatSpec with Matchers {
  private val identity = Json.obj("schema" -> 1, "network" -> "TESTNET", "poolNFT" -> "pool")
  private val dayMs = 86400000L
  private def saved(time: Long, block: String = "block"): StoredDexStats = StoredDexStats(time, DexStatsSpec.data(block))

  "DEX observation queries" should "page sampled observations without confusing them with canonical history" in {
    val db = new InMemoryKeyValueStore
    val store = new KeyValueStatsStore(db, StatsStorageConfig(), identity)
    store.save(saved(dayMs, "old-branch"))
    store.save(saved(dayMs + 5 * 60000, "new-branch"))
    store.save(saved(dayMs * 2, "next-day"))
    val first = store.observations(1440, 2881, 1)
    first.observations.map(_.sourceBlockId) shouldBe Vector("old-branch")
    first.nextMinute shouldBe Some(1441)
    val second = store.observations(first.nextMinute.get, 2881, 10)
    second.observations.map(_.sourceBlockId) shouldBe Vector("new-branch")
    second.nextMinute shouldBe Some(2880)
    store.observations(second.nextMinute.get, 2881, 10).observations.map(_.sourceBlockId) shouldBe Vector("next-day")
    intercept[IllegalArgumentException](store.observations(0, 1, 501))
  }

  "Stats persistence" should "reopen the latest graph with exact accumulators and replace it after a same-height fork" in {
    val directory = Files.createTempDirectory("lithos-stats-store-")
    var current: Option[StatsStore] = None
    def open(): StatsStore = {
      val next = new KeyValueStatsStore(LevelDbKeyValueStore.openOrThrow(directory), StatsStorageConfig(), identity)
      current = Some(next)
      next
    }
    try {
      val first = saved(dayMs, "old-branch")
      open().save(first)
      current.get.close()
      val reopened = open()
      val restored = reopened.load().get
      restored.observedAt shouldBe first.observedAt
      restored.data.history shouldBe first.data.history
      restored.data.prices shouldBe first.data.prices
      restored.data.defaultFees shouldBe first.data.defaultFees
      val replacement = saved(dayMs + 60000, "replacement-branch")
      reopened.save(replacement)
      reopened.close()
      open().load().get.data.blockId shouldBe "replacement-branch"
    } finally {
      current.foreach(_.close())
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  it should "commit the snapshot and one compact sample in a single batch, coalescing the same sampling interval" in {
    val database = new InMemoryKeyValueStore
    val store = new KeyValueStatsStore(database, StatsStorageConfig(), identity)
    database.clearRecordedBatches()
    store.save(saved(dayMs))
    database.recordedBatches should have size 1
    database.recordedBatches.head should have size 3
    store.save(saved(dayMs + 60000, "newer"))
    val observations = KeyValueStore.orThrow(database.scanPrefix(KeyValueStatsStore.dayPrefix(1)))
    observations should have size 1
    (Json.parse(observations.head._2) \ "sourceBlockId").as[String] shouldBe "newer"
    store.load().get.data.blockId shouldBe "newer"
  }

  it should "prune bounded batches from the oldest day and preserve the boundary day and latest snapshot" in {
    val database = new InMemoryKeyValueStore
    val settings = StatsStorageConfig(sampleIntervalMinutes = 1, retentionDays = 30, pruneBatchSize = 2)
    val store = new KeyValueStatsStore(database, settings, identity)
    (0 until 5).foreach(minute => store.save(saved(minute * 60000L)))
    store.save(saved(dayMs, "boundary"))
    database.clearRecordedBatches()
    store.prune(31 * dayMs) shouldBe 2
    KeyValueStore.orThrow(database.scanKeys(KeyValueStatsStore.dayPrefix(0))) should have size 3
    store.prune(31 * dayMs) shouldBe 2
    store.prune(31 * dayMs) shouldBe 1
    store.prune(31 * dayMs) shouldBe 0
    KeyValueStore.orThrow(database.scanKeys(KeyValueStatsStore.dayPrefix(0))) shouldBe empty
    KeyValueStore.orThrow(database.scanKeys(KeyValueStatsStore.dayPrefix(1))) should have size 1
    store.load().get.data.blockId shouldBe "boundary"
    database.recordedBatches.foreach { batch =>
      batch.count(_.isInstanceOf[KeyValueMutation.Delete]) should be <= 2
    }
  }

  it should "retain observations with pruning off and resume pruning after reopening with it enabled" in {
    val database = new InMemoryKeyValueStore
    val disabled = new KeyValueStatsStore(database, StatsStorageConfig(pruningEnabled = false), identity)
    disabled.save(saved(0))
    disabled.prune(100 * dayMs) shouldBe 0
    KeyValueStore.orThrow(database.scanKeys(KeyValueStatsStore.dayPrefix(0))) should have size 1
    val enabled = new KeyValueStatsStore(database, StatsStorageConfig(), identity)
    enabled.prune(100 * dayMs) shouldBe 1
    enabled.load() should not be empty
  }

  it should "reject a different protocol identity without altering saved data" in {
    val database = new InMemoryKeyValueStore
    val store = new KeyValueStatsStore(database, StatsStorageConfig(), identity)
    store.save(saved(1))
    database.clearRecordedBatches()
    intercept[IllegalArgumentException] {
      new KeyValueStatsStore(database, StatsStorageConfig(), identity ++ Json.obj("network" -> "MAINNET"))
    }.getMessage should include("identity differs")
    database.recordedBatches shouldBe empty
    store.load().get.observedAt shouldBe 1L
  }

  "Stats snapshot decoding" should "reject damaged, oversized and unsupported records" in {
    val encoded = StatsStoreCodec.encode(saved(1))
    encoded(encoded.length - 1) = (encoded.last ^ 1).toByte
    intercept[IllegalArgumentException](StatsStoreCodec.decode(encoded)).getMessage should include("checksum")
    intercept[IllegalArgumentException](StatsStoreCodec.decode(new Array[Byte](StatsStoreCodec.MaxSnapshotBytes + 1)))
      .getMessage should include("size")
    val future = "{\"schema\":2}".getBytes(UTF_8)
    intercept[IllegalArgumentException](StatsStoreCodec.decode(scorex.crypto.hash.Blake2b256.hash(future) ++ future))
      .getMessage should include("schema")
  }

  it should "reject duplicate or reordered pool history rather than persist a misleading fee graph" in {
    val record = saved(1)
    intercept[IllegalArgumentException] {
      StatsStoreCodec.encode(record.copy(data = record.data.copy(history = record.data.history.reverse)))
    }.getMessage should include("ordered")
  }
}
