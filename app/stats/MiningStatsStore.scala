package stats

import configs.{NodeContext, StatsStorageConfig}
import org.bouncycastle.util.encoders.Hex
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import state.synchronization.SyncProtocolContext
import storage.{KeyValueMutation, KeyValueStore, WriteDurability}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths

object MiningStatsStore {
  // 4 adds the per-block finder fee. The version is part of the database path, so an older store is
  // left alone rather than migrated: its records predate the field and would not parse.
  val SchemaVersion: Int = 4
  def open(settings: StatsStorageConfig, node: NodeContext, protocol: SyncProtocolContext): MiningStatsStore = {
    val identity = Json.obj("schema" -> SchemaVersion, "network" -> protocol.networkType.toString,
      "startHeight" -> protocol.rollupStartHeight, "holding" -> protocol.holdingErgoTree,
      "collateral" -> protocol.collateralErgoTree, "collateralToken" -> protocol.collateralToken.toString,
      "evaluation" -> protocol.evaluationErgoTree, "payout" -> protocol.payoutErgoTree,
      "minerDictionaryToken" -> protocol.minerDictionaryToken.toString,
      "lithosDexPoolNFT" -> lithosdex.LDHelpers.getPoolNFT(protocol.networkType).toString,
      "lithosDexPoolTree" -> transactions.batching.lithosdex.DexContracts(protocol.networkType).liquidityPool.ergoTreeHex,
      "ergoDexPoolTree" -> transactions.batching.ergodex.ErgoDexContracts.NativePoolErgoTree,
      "feeTree" -> work.lithos.mutations.Contract.FEE.ergoTreeHex,
      "miner" -> node.getNodeWallet.contract.ergoTreeHex)
    // A separate database lets canonical replay and DEX observations make progress independently.
    val database = if (settings.enabled)
      Some(KeyValueStore.openOrThrow(settings.backend, Paths.get(settings.path).resolve(s"mining-v$SchemaVersion"))) else None
    try new MiningStatsStore(database, identity)
    catch { case scala.util.control.NonFatal(error) => database.foreach(_.close()); throw error }
  }
}

/** Single-worker repository. Every contribution and its cursor change in the same durable batch. */
class MiningStatsStore(database: Option[KeyValueStore], identity: JsObject) {
  import MiningStatsData._
  import KeyValueMutation.{Delete, Put}
  private var memory = Map.empty[String, Array[Byte]]
  private def key(value: String): Array[Byte] = value.getBytes(UTF_8)
  private def blockKey(height: Int): String = f"block/$height%010d"
  private def bucketKey(start: Long, width: Long): String = s"bucket/$width/" + f"$start%016d"
  private def get(name: String): Option[Array[Byte]] = database match {
    case Some(db) => KeyValueStore.orThrow(db.get(key(name)))
    case None => memory.get(name)
  }
  private def write(changes: Seq[KeyValueMutation]): Unit = database match {
    case Some(db) => KeyValueStore.orThrow(db.write(changes, WriteDurability.Synchronous))
    case None => memory = changes.foldLeft(memory) {
      case (current, Put(k, v)) => current.updated(new String(k, UTF_8), v)
      case (current, Delete(k)) => current - new String(k, UTF_8)
    }
  }
  private def encode[A: Writes](value: A): Array[Byte] = {
    val json = Json.toJson(value)
    val bytes = Json.stringify(json).getBytes(UTF_8)
    require(bytes.length <= 1024 * 1024, "mining statistics record exceeds 1 MiB")
    Json.stringify(Json.obj("data" -> json, "checksum" -> Hex.toHexString(Blake2b256.hash(bytes)))).getBytes(UTF_8)
  }
  private def decode[A: Reads](bytes: Array[Byte]): A = {
    require(bytes.length <= 2 * 1024 * 1024, "mining statistics record exceeds its size limit")
    val json = Json.parse(bytes)
    val data = (json \ "data").get
    require((json \ "checksum").as[String] == Hex.toHexString(Blake2b256.hash(Json.stringify(data).getBytes(UTF_8))),
      "mining statistics checksum differs")
    data.as[A]
  }
  get("identity") match {
    case Some(bytes) => require(decode[JsObject](bytes) == identity, "mining statistics identity differs; use a separate stats path")
    case None => write(Seq(Put(key("identity"), encode(identity))))
  }
  private def readCheckpoint(): Option[MiningLedgerState] = get("state").map { bytes =>
    val state = decode[MiningLedgerState](bytes)
    require(state.firstHeight > 0 && state.cursor.height >= state.firstHeight - 1 &&
      state.totals.blocks >= 0 && state.totals.payments >= 0 && BigInt(state.totals.grossPaidNanoErg) >= 0,
      "invalid mining statistics checkpoint")
    state
  }
  private var current = readCheckpoint()
  /** A failed write may already have committed. Reconcile from the atomic checkpoint before retrying. */
  def reloadCheckpoint(): Unit = current = readCheckpoint()
  def state: Option[MiningLedgerState] = current
  def block(height: Int): Option[MiningBlockRecord] = get(blockKey(height)).map { bytes =>
    val record = decode[MiningBlockRecord](bytes)
    require(record.cursor.height == height, "mining statistics record has the wrong height")
    validate(record)
    record
  }

  def initialize(anchor: MiningCursor): Unit = {
    require(current.forall(s => s.firstHeight > s.cursor.height), "cannot replace nonempty mining history")
    saveState(MiningLedgerState(anchor.height + 1, anchor, MiningTotals()), Vector.empty)
  }

  def append(record: MiningBlockRecord): Unit = {
    val base = current.getOrElse(throw new IllegalStateException("mining history has no anchor"))
    if (record.cursor == base.cursor) return // An acknowledged durable commit may be replayed.
    validate(record)
    require(record.previous == base.cursor && record.cursor.height == base.cursor.height + 1 &&
      record.cursor.parentId == base.cursor.blockId, "mining block is not contiguous")
    require(record.cursor.timestamp > base.cursor.timestamp, "mining block timestamp did not increase")
    saveState(base.copy(cursor = record.cursor, totals = base.totals.include(record, 1)),
      Vector(Put(key(blockKey(record.cursor.height)), encode(record))) ++ bucketChanges(record, 1))
  }

  /** Removes one displaced block. The previous cursor remains valid even at the retention boundary. */
  def rollback(): Unit = {
    val base = current.get
    val record = requiredBlock(base.cursor.height)
    saveState(base.copy(cursor = record.previous, totals = base.totals.include(record, -1)),
      Vector(Delete(key(blockKey(record.cursor.height)))) ++ bucketChanges(record, -1))
  }

  /** Keep a rollback buffer even on a quiet/old chain; totals describe all retained records. */
  def prune(cutoff: Long, limit: Int, rollbackBlocks: Int): Int = {
    var removed = 0
    while (removed < limit && current.exists(s => s.firstHeight <= s.cursor.height - rollbackBlocks) &&
      requiredBlock(current.get.firstHeight).cursor.timestamp < cutoff) {
      val base = current.get
      val record = requiredBlock(base.firstHeight)
      saveState(base.copy(firstHeight = base.firstHeight + 1, totals = base.totals.include(record, -1)),
        Vector(Delete(key(blockKey(base.firstHeight)))) ++ bucketChanges(record, -1))
      removed += 1
    }
    removed
  }

  private def bucketChanges(record: MiningBlockRecord, sign: Int): Vector[KeyValueMutation] = {
    val contribution = MiningAccounting.contribution(record)
    Vector(MiningHistory.HourMs, MiningHistory.DayMs).map { width =>
      val start = MiningHistory.bucketStart(record.cursor.timestamp, width)
      val name = bucketKey(start, width)
      val previous = get(name).map(decode[MiningBucket]).map(_.totals).getOrElse(MiningAccountingTotals())
      val totals = previous.combine(contribution, sign)
      if (totals.amount("chain.blocks") == 0) Delete(key(name))
      else Put(key(name), encode(MiningBucket(start, width, totals)))
    }
  }

  /** Worker-only queries: explicit page/range caps, with a cursor identifying the consistent snapshot. */
  def history(fromHeight: Int, limit: Int): MiningHistoryPage = {
    require(fromHeight >= 0 && limit > 0 && limit <= MiningHistory.MaxResults, "invalid mining history page")
    val base = current.getOrElse(throw new IllegalStateException("mining history is loading"))
    val start = math.max(fromHeight, base.firstHeight)
    val records = Vector.newBuilder[MiningBlockRecord]
    var height = start
    var count = 0
    var bytesRead = 0L
    var full = false
    while (!full && height <= base.cursor.height && count < limit) {
      val bytes = get(blockKey(height)).getOrElse(throw new IllegalStateException(s"missing mining statistics block $height"))
      require(bytes.length <= 2 * 1024 * 1024, "mining history record exceeds its size limit")
      if (bytesRead + bytes.length > 8 * 1024 * 1024) full = true
      else {
        val record = decode[MiningBlockRecord](bytes)
        validate(record)
        require(record.cursor.height == height, "mining history record has the wrong height")
        records += record
        count += 1
        bytesRead += bytes.length
        height += 1
      }
    }
    MiningHistoryPage(base.cursor, base.firstHeight, records.result(), if (height <= base.cursor.height) Some(height) else None)
  }

  def buckets(from: Long, until: Long, width: Long): MiningBucketHistory = {
    MiningHistory.validateRange(from, until, width)
    val base = current.getOrElse(throw new IllegalStateException("mining history is loading"))
    val first = block(base.firstHeight).map(_.previous.timestamp)
    val rows = Vector.iterate(from, ((until - from) / width).toInt)(_ + width).flatMap { start =>
      get(bucketKey(start, width)).map(decode[MiningBucket])
    }
    MiningBucketHistory(base.cursor, first, from, until, width, rows,
      first.forall(_ > from) || base.cursor.timestamp < until)
  }

  /** Sampled local observations are not canonical contributions and are never rolled back with a block. */
  def saveLocal(observations: Iterable[LocalMiningObservation]): Unit = observations.foreach { observation =>
    require(LocalMiningStats.Kinds.contains(observation.kind) && observation.counters.size <= 32 &&
      observation.fraud.size <= 100 && observation.counters.values.forall(v => BigInt(v) >= 0),
      "invalid local statistics observation")
    val latestKey = s"local/latest/${observation.kind}"
    val previous = get(latestKey).map(decode[LocalMiningObservation])
    if (previous.forall(p => if (p.session == observation.session) p.sequence < observation.sequence
      else p.startedAt <= observation.startedAt)) {
      val hour = observation.observedAt / MiningHistory.HourMs
      val oldest = get("local/oldest-hour").map(decode[Long]).fold(hour)(math.min(_, hour))
      write(Vector(Put(key(latestKey), encode(observation)),
        Put(key(s"local/hour/$hour/${observation.kind}"), encode(observation)),
        Put(key("local/oldest-hour"), encode(oldest))))
    }
  }

  def localHistory(kind: String, from: Long, until: Long): Vector[LocalMiningObservation] = {
    require(LocalMiningStats.Kinds.contains(kind), "unknown local statistics kind")
    MiningHistory.validateRange(from, until, MiningHistory.HourMs)
    (from / MiningHistory.HourMs until until / MiningHistory.HourMs).flatMap { hour =>
      get(s"local/hour/$hour/$kind").map(decode[LocalMiningObservation])
    }.toVector
  }

  def pruneLocal(cutoff: Long, limit: Int): Unit = {
    get("local/oldest-hour").map(decode[Long]).foreach { first =>
      val end = math.min(cutoff / MiningHistory.HourMs, first + limit)
      if (end > first) {
        val removed = (first until end).flatMap(hour => LocalMiningStats.Kinds.toVector.map(kind =>
          Delete(key(s"local/hour/$hour/$kind")))).toVector
        write(removed :+ Put(key("local/oldest-hour"), encode(end)))
      }
    }
  }

  def view(status: String, target: Int, persistent: Boolean, now: Long): MiningStatsView = {
    val base = current.get
    val recentStart = math.max(base.firstHeight, base.cursor.height - 119)
    val recent = (recentStart to base.cursor.height).map(requiredBlock).toVector
    MiningStatsView(status, Some(now), persistent, Some(base.cursor.height), Some(base.cursor.blockId),
      Some(target), Some(base.firstHeight), block(base.firstHeight).map(_.cursor.timestamp), Some(recentStart),
      base.totals, recent.flatMap(_.blocks).takeRight(50).reverse,
      recent.flatMap(_.payments).takeRight(50).reverse,
      recent.map(r => MiningDifficultyPoint(r.cursor.height, r.cursor.timestamp, r.difficulty)))
  }

  private def requiredBlock(height: Int): MiningBlockRecord = block(height).getOrElse(
    throw new IllegalStateException(s"missing mining statistics block at height $height"))
  private def validate(record: MiningBlockRecord): Unit = {
    val at = record.cursor
    require(at.height == record.previous.height + 1 && at.parentId == record.previous.blockId &&
      at.timestamp > record.previous.timestamp && BigInt(record.difficulty) >= 0, "invalid mining block record")
    require(record.blocks.forall(b => b.blockId == at.blockId && b.height == at.height && b.timestamp == at.timestamp &&
      BigInt(b.collateralNanoErg) >= 0 && BigInt(b.initialHoldingNanoErg) >= 0), "invalid Lithos block record")
    require(record.payments.forall(p => p.blockId == at.blockId && p.height == at.height && p.timestamp == at.timestamp &&
      p.minedHeight <= at.height && BigInt(p.grossNanoErg) >= 0), "invalid mining payment record")
    require(record.blocks.map(_.transactionId).distinct.size == record.blocks.size &&
      record.payments.map(_.outputId).distinct.size == record.payments.size, "duplicate mining records")
  }
  private def saveState(next: MiningLedgerState, changes: Vector[KeyValueMutation]): Unit = {
    write(changes :+ Put(key("state"), encode(next)))
    current = Some(next)
  }
  def close(): Unit = database.foreach(db => KeyValueStore.orThrow(db.close()))
}
