package stats

import api.models.{LDPriceHistory, LDPricePoint, LDRecentActivity}
import configs.{NodeContext, StatsStorageConfig}
import lithosdex.LDHelpers
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import storage.KeyValueMutation.{Delete, Put}
import storage.{KeyValueStore, WriteDurability}
import transactions.batching.lithosdex.{DexContracts, LDBoxes}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.security.MessageDigest

final case class StoredDexStats(observedAt: Long, data: DexStatsData)

/** Historical observations, not a canonical ledger: these can include mempool prices and old forks. */
final case class DexObservation(observedAt: Long, sourceHeight: Int, sourceBlockId: String,
                                price: LDPricePoint, confirmedPool: LDBoxes.PoolSnapshot,
                                historyComplete: Boolean)

trait StatsStore {
  def load(): Option[StoredDexStats]
  def save(snapshot: StoredDexStats): Unit
  def prune(now: Long): Int
  def observations(fromMinute: Long, untilMinute: Long, limit: Int): DexObservationPage =
    throw new UnsupportedOperationException("observation history is unavailable")
  def close(): Unit
}

final case class DexObservationPage(observations: Vector[DexObservation], nextMinute: Option[Long])

object StatsStore {
  def open(settings: StatsStorageConfig, node: NodeContext): StatsStore = {
    val network = node.getNetwork
    val identity = Json.obj(
      "schema" -> StatsStoreCodec.SchemaVersion,
      "network" -> network.toString,
      "poolNFT" -> LDHelpers.getPoolNFT(network).toString,
      "tokenY" -> LDHelpers.getTokenY(network).toString,
      "poolTree" -> DexContracts(network).liquidityPool.ergoTreeHex)
    val database = KeyValueStore.openOrThrow(settings.backend, Paths.get(settings.path))
    try new KeyValueStatsStore(database, settings, identity)
    catch {
      case scala.util.control.NonFatal(error) =>
        database.close()
        throw error
    }
  }
}

/** Latest graph state and its sample commit together. Pruning never touches the latest snapshot. */
final class KeyValueStatsStore(database: KeyValueStore, settings: StatsStorageConfig,
                               identity: JsObject) extends StatsStore {
  import KeyValueStatsStore._
  import KeyValueStore.orThrow

  private val identityBytes = Json.toBytes(identity)
  orThrow(database.get(IdentityKey)) match {
    case Some(bytes) => require(Json.parse(bytes) == identity,
      "stats database schema or protocol identity differs; use a separate stats.storage.path")
    case None => orThrow(database.put(IdentityKey, identityBytes, WriteDurability.Synchronous))
  }

  override def load(): Option[StoredDexStats] =
    orThrow(database.get(LatestKey)).map(StatsStoreCodec.decode)

  /** At most one daily partition (1440 keys) and 500 decoded samples per request. */
  override def observations(fromMinute: Long, untilMinute: Long, limit: Int): DexObservationPage = {
    require(fromMinute >= 0 && untilMinute > fromMinute && untilMinute <= Long.MaxValue / MinuteMs && limit > 0 && limit <= 500,
      "invalid DEX observation page")
    val day = fromMinute / MinutesPerDay
    val end = math.min(untilMinute, (day + 1) * MinutesPerDay)
    val keys = orThrow(database.scanKeys(dayPrefix(day)))
    require(keys.size <= MinutesPerDay, "DEX observation partition exceeds its limit")
    val minutes = keys.map(k => day * MinutesPerDay + new String(k, UTF_8).split('/').last.toLong)
      .filter(m => m >= fromMinute && m < end).sorted
    val selected = minutes.take(limit)
    val samples = selected.map { minute =>
      val bytes = orThrow(database.get(observationKey(minute))).getOrElse(
        throw new IllegalStateException("missing DEX observation"))
      require(bytes.length <= 65536, "DEX observation exceeds its size limit")
      Json.parse(bytes).as[DexObservation](StatsStoreCodec.observationFormat)
    }.toVector
    val next = if (minutes.size > limit) selected.last + 1 else end
    DexObservationPage(samples, if (next < untilMinute) Some(next) else None)
  }

  override def save(snapshot: StoredDexStats): Unit = {
    val encoded = StatsStoreCodec.encode(snapshot)
    val observedMinute = snapshot.observedAt / MinuteMs
    val day = observedMinute / MinutesPerDay
    val sampleMinute = day * MinutesPerDay +
      (observedMinute % MinutesPerDay) / settings.sampleIntervalMinutes * settings.sampleIntervalMinutes
    val oldestDay = readOldestDay().fold(day)(math.min(_, day))
    val observation = DexObservation(snapshot.observedAt, snapshot.data.height, snapshot.data.blockId,
      snapshot.data.current, snapshot.data.history.last, snapshot.data.complete)
    val sample = Json.toBytes(Json.toJson(observation)(StatsStoreCodec.observationFormat))
    orThrow(database.write(Vector(
      Put(LatestKey, encoded), Put(observationKey(sampleMinute), sample),
      Put(OldestDayKey, oldestDay.toString.getBytes(UTF_8))), WriteDurability.Synchronous))
  }

  override def prune(now: Long): Int = {
    if (!settings.pruningEnabled) return 0
    // Retain the boundary day in full. This can keep up to one extra day of observations.
    val cutoffDay = now / DayMs - settings.retentionDays
    readOldestDay() match {
      case Some(day) if day < cutoffDay =>
        // One key per minute bounds this scan at 1440 keys, even with pruning disabled for years.
        val keys = orThrow(database.scanKeys(dayPrefix(day)))
        require(keys.size <= MinutesPerDay, "stats observation partition exceeds its minute limit")
        val expired = keys.take(settings.pruneBatchSize)
        val nextDay = if (expired.size == keys.size) day + 1 else day
        orThrow(database.write(expired.map(Delete) :+
          Put(OldestDayKey, nextDay.toString.getBytes(UTF_8)), WriteDurability.Synchronous))
        expired.size
      case _ => 0
    }
  }

  override def close(): Unit = orThrow(database.close())

  private def readOldestDay(): Option[Long] = orThrow(database.get(OldestDayKey)).map { bytes =>
    val day = new String(bytes, UTF_8).toLong
    require(day >= 0, "invalid stats pruning cursor")
    day
  }
}

object KeyValueStatsStore {
  private[stats] val IdentityKey = "stats/identity".getBytes(UTF_8)
  private[stats] val LatestKey = "stats/dex/latest".getBytes(UTF_8)
  private val OldestDayKey = "stats/dex/oldest-day".getBytes(UTF_8)
  private val MinuteMs = 60000L
  private val MinutesPerDay = 1440
  private val DayMs = MinuteMs * MinutesPerDay

  private[stats] def dayPrefix(day: Long): Array[Byte] = f"stats/dex/observations/$day%012d/".getBytes(UTF_8)
  private[stats] def observationKey(minute: Long): Array[Byte] =
    dayPrefix(minute / MinutesPerDay) ++ f"${minute % MinutesPerDay}%04d".getBytes(UTF_8)
}

/** Versioned JSON with a checksum; graph reconstruction and decoding stay on the storage worker. */
private[stats] object StatsStoreCodec {
  val MaxSnapshotBytes: Int = 8 * 1024 * 1024
  val SchemaVersion = 1

  private implicit val integerFormat: Format[BigInt] = Format(
    Reads.StringReads.map(BigInt(_)), Writes(value => JsString(value.toString)))
  implicit val poolFormat: OFormat[LDBoxes.PoolSnapshot] = Json.format[LDBoxes.PoolSnapshot]
  implicit val observationFormat: OFormat[DexObservation] = Json.format[DexObservation]

  def encode(snapshot: StoredDexStats): Array[Byte] = {
    validate(snapshot)
    val data = snapshot.data
    // Derived graph buckets are rebuilt on restore, so a calculation fix also applies to saved data.
    val payload = Json.toBytes(Json.obj(
      "schema" -> SchemaVersion, "observedAt" -> snapshot.observedAt,
      "height" -> data.height, "blockId" -> data.blockId, "fromHeight" -> data.fromHeight,
      "history" -> data.history, "complete" -> data.complete, "current" -> data.current,
      "decimals" -> data.decimals, "timestamps" -> data.timestamps.map { case (height, time) => height.toString -> time },
      "recent" -> data.recent))
    require(payload.length <= MaxSnapshotBytes - 32, "stats snapshot exceeds its byte limit")
    Blake2b256.hash(payload) ++ payload
  }

  def decode(bytes: Array[Byte]): StoredDexStats = {
    require(bytes.length > 32 && bytes.length <= MaxSnapshotBytes, "invalid stats snapshot size")
    val payload = bytes.drop(32)
    require(MessageDigest.isEqual(bytes.take(32), Blake2b256.hash(payload)), "stats snapshot checksum mismatch")
    val json = Json.parse(payload)
    require((json \ "schema").as[Int] == SchemaVersion, "unsupported stats snapshot schema")
    val height = (json \ "height").as[Int]
    val history = (json \ "history").as[Vector[LDBoxes.PoolSnapshot]]
    val complete = (json \ "complete").as[Boolean]
    val current = (json \ "current").as[LDPricePoint]
    val decimals = (json \ "decimals").as[Int]
    val fromHeight = (json \ "fromHeight").as[Int]
    val times = (json \ "timestamps").as[Map[String, Long]].map { case (h, time) => h.toInt -> time }
    val recent = (json \ "recent").as[LDRecentActivity]
    val data = DexStatsData(height, (json \ "blockId").as[String], fromHeight, history, complete,
      current, decimals, times, Map.empty, api.models.LDFeeHistory(Vector.empty), recent)
    val snapshot = StoredDexStats((json \ "observedAt").as[Long], data)
    validate(snapshot)
    val prices = LDPriceHistory.Ranges.map { case (name, blocks) =>
      name -> api.DexHistory.price(history, complete || history.head.height < height - blocks,
        current, decimals, Some(name), None, _ => times)
    }
    snapshot.copy(data = data.copy(prices = prices,
      defaultFees = api.DexHistory.fees(history, complete, fromHeight, height, 720, _ => times)))
  }

  private def validate(snapshot: StoredDexStats): Unit = {
    val data = snapshot.data
    require(snapshot.observedAt >= 0, "invalid stats observation time")
    require(data.height >= 0 && data.fromHeight == math.max(0, data.height - LDPriceHistory.Ranges.values.max),
      "invalid stats history window")
    require(data.blockId.nonEmpty && data.blockId.length <= 128, "invalid stats block identity")
    require(data.history.nonEmpty && data.history.size <= 10000, "invalid stats history size")
    require(data.decimals >= 0 && data.decimals <= 255, "invalid stats token decimals")
    require(data.current.height == data.height && data.current.price >= 0 && !data.current.price.isInfinity &&
      !data.current.price.isNaN, "invalid stats current price")
    require(data.timestamps.size <= 250 && data.recent.activity.size <= LDRecentActivity.MaxLimit,
      "stats snapshot exceeds its graph bounds")
    require(data.history.forall(s => s.height >= 0 && s.height <= data.height && s.globalIndex >= 0),
      "invalid stats pool position")
    require(data.history.sliding(2).forall {
      case Vector(a, b) => a.height <= b.height && a.globalIndex < b.globalIndex
      case _ => true
    }, "stats history is not ordered")
  }
}
