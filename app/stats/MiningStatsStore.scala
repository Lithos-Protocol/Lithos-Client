package stats

import configs.{NodeContext, StatsStorageConfig}
import org.bouncycastle.util.encoders.Hex
import org.slf4j.LoggerFactory
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import state.synchronization.SyncProtocolContext
import storage.{KeyValueMutation, KeyValueReadView, KeyValueStore, WriteDurability}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object MiningStatsStore {
  // 8 records what this wallet earned lending collateral in each block record. The version is part of
  // the database path, so an older store is not migrated in place: its block records would not parse.
  // A new store rebuilds them from the chain, and carries over what the chain cannot rebuild.
  val SchemaVersion: Int = 8
  private val logger = LoggerFactory.getLogger("MiningStatistics")
  private val VersionedDirectory = """mining-v(\d+)""".r

  /** What a store records it was written for. A store only opens for the same identity. */
  private[stats] def identityOf(node: NodeContext, protocol: SyncProtocolContext, schema: Int = SchemaVersion): JsObject =
    Json.obj("schema" -> schema, "network" -> protocol.networkType.toString,
      "startHeight" -> protocol.rollupStartHeight, "holding" -> protocol.holdingErgoTree,
      "collateral" -> protocol.collateralErgoTree, "collateralToken" -> protocol.collateralToken.toString,
      "evaluation" -> protocol.evaluationErgoTree, "payout" -> protocol.payoutErgoTree,
      "minerDictionaryToken" -> protocol.minerDictionaryToken.toString,
      "lithosDexPoolNFT" -> lithosdex.LDHelpers.getPoolNFT(protocol.networkType).toString,
      "lithosDexPoolTree" -> transactions.batching.lithosdex.DexContracts(protocol.networkType).liquidityPool.ergoTreeHex,
      "ergoDexPoolTree" -> transactions.batching.ergodex.ErgoDexContracts.NativePoolErgoTree,
      "feeTree" -> work.lithos.mutations.Contract.FEE.ergoTreeHex,
      "miner" -> node.getNodeWallet.contract.ergoTreeHex)

  def open(settings: StatsStorageConfig, node: NodeContext, protocol: SyncProtocolContext): MiningStatsStore = {
    val identity = identityOf(node, protocol)
    // A separate database lets canonical replay and DEX observations make progress independently.
    val root = Paths.get(settings.path)
    val database = if (settings.enabled)
      Some(KeyValueStore.openOrThrow(settings.backend, root.resolve(s"mining-v$SchemaVersion"))) else None
    val store = try new MiningStatsStore(database, identity)
    catch { case NonFatal(error) => database.foreach(_.close()); throw error }
    if (settings.enabled) olderSchemas(root).lastOption.foreach { case (version, path) =>
      carryOver(store, settings.backend, version, path)
    }
    if (settings.pruneOldSchemas) olderSchemas(root).foreach { case (version, path) => remove(version, path) }
    store
  }

  /** Mining statistics directories under `root` written by an older schema, oldest first. */
  private[stats] def olderSchemas(root: Path): Vector[(Int, Path)] =
    if (!Files.isDirectory(root)) Vector.empty
    else {
      val listing = Files.list(root)
      try listing.iterator().asScala.toVector.flatMap { path =>
        path.getFileName.toString match {
          case VersionedDirectory(version) if Files.isDirectory(path) && Try(version.toInt).toOption.exists(_ < SchemaVersion) =>
            Some(version.toInt -> path)
          case _ => None
        }
      }.sortBy(_._1)
      finally listing.close()
    }

  /** Once, into a store this version has not written yet. A failure leaves the new store to rebuild alone. */
  private def carryOver(store: MiningStatsStore, backend: String, version: Int, path: Path): Unit =
    if (store.fresh) Try {
      val older = KeyValueStore.openOrThrow(backend, path)
      try store.carryOver(older, version) finally older.close()
    } match {
      case Success(copied) => logger.info(s"Carried $copied local and difficulty records over from mining statistics v$version")
      case Failure(error) => logger.warn(s"Did not carry mining statistics over from v$version: ${error.getMessage}")
    }

  /** Deletes an older schema's directory. Statistics never stop the client, so a failure is only logged. */
  private[stats] def remove(version: Int, path: Path): Unit = Try {
    val walk = Files.walk(path)
    val files = try walk.iterator().asScala.toVector finally walk.close()
    val bytes = files.filter(Files.isRegularFile(_)).map(Files.size).sum
    files.sortBy(-_.getNameCount).foreach(Files.delete)
    bytes
  } match {
    case Success(bytes) => logger.info(s"Removed mining statistics v$version (${bytes / (1024 * 1024)} MiB) at $path")
    case Failure(error) => logger.warn(s"Could not remove mining statistics v$version at $path: ${error.getMessage}")
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
  private def epochKey(index: Int): String = f"epoch/$index%010d"
  private def get(name: String): Option[Array[Byte]] = database match {
    case Some(db) => KeyValueStore.orThrow(db.get(key(name)))
    case None => memory.get(name)
  }
  /** Keys under a prefix in byte order, which the zero-padded heights make height order. */
  private def keysWithPrefix(prefix: String): Vector[String] = (database match {
    case Some(db) => KeyValueStore.orThrow(db.scanKeys(key(prefix))).map(new String(_, UTF_8))
    case None => memory.keysIterator.filter(_.startsWith(prefix)).toVector
  }).sorted
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
      Vector(Put(key(blockKey(record.cursor.height)), encode(record))) ++ bucketChanges(record, 1) ++
        ledgerAdditions(record) ++ lendingTotalsChange(record, 1))
  }

  /** Removes one displaced block. The previous cursor remains valid even at the retention boundary. */
  def rollback(): Unit = {
    val base = current.get
    val record = requiredBlock(base.cursor.height)
    saveState(base.copy(cursor = record.previous, totals = base.totals.include(record, -1)),
      Vector(Delete(key(blockKey(record.cursor.height)))) ++ bucketChanges(record, -1) ++ ledgerRemovals(record) ++
        lendingTotalsChange(record, -1))
  }

  /** Keep a rollback buffer even on a quiet/old chain; totals describe all retained records. */
  def prune(cutoff: Long, limit: Int, rollbackBlocks: Int): Int = {
    var removed = 0
    while (removed < limit && current.exists(s => s.firstHeight <= s.cursor.height - rollbackBlocks) &&
      requiredBlock(current.get.firstHeight).cursor.timestamp < cutoff) {
      val base = current.get
      val record = requiredBlock(base.firstHeight)
      saveState(base.copy(firstHeight = base.firstHeight + 1, totals = base.totals.include(record, -1)),
        Vector(Delete(key(blockKey(base.firstHeight)))) ++ bucketChanges(record, -1) ++ ledgerRemovals(record) ++
          lendingTotalsChange(record, -1))
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

  /* ---- Local payout ledger ----
     Indexes the block records by what a payments view asks of them: this client's payouts by
     height, the rollups it has a claim in, and the fraud proofs it won. Each entry is written and
     removed with the block that confirmed it, so rollback and pruning keep it exact. */

  private def ledgerAdditions(record: MiningBlockRecord): Vector[KeyValueMutation] = {
    val at = record.cursor
    val rollups = record.activity.rollups
    val submitted = rollups.filter(a => a.kind == "submission" && a.local).map(_.rollupNft).toSet
    def isTracked(nft: String) = submitted.contains(nft) || keysWithPrefix(s"ledger/claim/$nft/").nonEmpty
    record.payments.map(p => Put(key(PaymentLedger.paymentKey(p)), encode(p))) ++
      rollups.filter(PaymentLedger.tracked(_, isTracked)).map(a =>
        Put(key(PaymentLedger.claimKey(at.height, a)), encode(LedgerEvent(at.height, at.timestamp, a)))) ++
      rollups.filter(a => a.kind == "fraudProof" && a.local).map(a =>
        Put(key(PaymentLedger.bountyKey(at.height, a)), encode(LedgerEvent(at.height, at.timestamp, a)))) ++
      record.lending.map(l => Put(key(Lending.key(at.height)), encode(l)))
  }

  /** Every key the record could have written. Deleting one that was never written is harmless. */
  private def ledgerRemovals(record: MiningBlockRecord): Vector[KeyValueMutation] = {
    val height = record.cursor.height
    record.payments.map(p => Delete(key(PaymentLedger.paymentKey(p)))) ++
      record.activity.rollups.flatMap(a =>
        Vector(Delete(key(PaymentLedger.claimKey(height, a))), Delete(key(PaymentLedger.bountyKey(height, a))))) :+
      Delete(key(Lending.key(height)))
  }

  /** The retained window's lending totals, written with the records so a page reads only its own rows. */
  private def lendingTotalsChange(record: MiningBlockRecord, sign: Int): Vector[KeyValueMutation] =
    record.lending.toVector.map { l =>
      val previous = get("lending/totals").map(decode[LendingTotals]).getOrElse(LendingTotals.Zero)
      Put(key("lending/totals"), encode(previous.add(l, sign)))
    }

  /** A page of this wallet's spent positions, newest first, with totals over the whole retained window. */
  def lending(offset: Int, limit: Int): LendingPage = {
    require(offset >= 0 && limit > 0 && limit <= Lending.MaxLimit, "invalid lending page")
    val base = current.getOrElse(throw new IllegalStateException("mining history is loading"))
    val keys = keysWithPrefix("ledger/lending/").reverse
    val page = keys.slice(offset, offset + limit).map(name => decode[LendingRecord](get(name).getOrElse(
      throw new IllegalStateException(s"ledger entry $name disappeared during the read"))))
    val totals = get("lending/totals").map(decode[LendingTotals]).getOrElse(LendingTotals.Zero)
    require(totals.positions == keys.size, "lending totals disagree with the records")
    LendingPage("unverified", base.cursor, base.firstHeight, block(base.firstHeight).map(_.cursor.timestamp),
      page, offset, keys.size, totals)
  }

  /** A page of payouts, newest first, with every open claim and bounty in the retained window. */
  def ledger(offset: Int, limit: Int, sort: String = "paid", ascending: Boolean = false): PaymentLedgerPage = {
    val base = current.getOrElse(throw new IllegalStateException("mining history is loading"))
    val paymentKeys = keysWithPrefix("ledger/payment/")
    val pageKeys = PaymentLedger.page(paymentKeys, offset, limit, sort, ascending)
    val paid = paymentKeys.map(k => PaymentLedger.paymentKeyParts(k).nft).toSet
    val openKeys = keysWithPrefix("ledger/claim/").filterNot(k => paid.contains(PaymentLedger.claimKeyNft(k)))
    def read[A: Reads](name: String): A = decode[A](get(name).getOrElse(
      throw new IllegalStateException(s"ledger entry $name disappeared during the read")))
    PaymentLedgerPage("unverified", base.cursor, base.firstHeight,
      pageKeys.map(read[MiningPaymentRecord]), offset, paymentKeys.size, sort, ascending,
      PaymentLedger.claims(openKeys.map(read[LedgerEvent])),
      keysWithPrefix("ledger/bounty/").reverse.map(k => PaymentLedger.bounty(read[LedgerEvent](k))))
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

  /* ---- Difficulty epochs ----
     Header-derived and independent of the block records: an epoch is only written once its last
     block is a full epoch behind the tip, so nothing here is ever rolled back or pruned with a
     block record. The table therefore outlives the retention window, which is the point of it. */

  def epochBounds: Option[(Int, Int)] = for {
    oldest <- get("epoch/oldest").map(decode[Int])
    newest <- get("epoch/newest").map(decode[Int])
    if oldest <= newest
  } yield (oldest, newest)

  def epoch(index: Int): Option[DifficultyEpoch] = get(epochKey(index)).map { bytes =>
    val record = decode[DifficultyEpoch](bytes)
    require(record.index == index, "difficulty epoch record has the wrong index")
    record
  }

  /**
   * Writes epochs and moves whichever marker they extend. Backfill walks downwards and forward
   * collection upwards, so a batch touches one end or the other but never both.
   */
  def saveEpochs(epochs: Seq[DifficultyEpoch]): Unit = if (epochs.nonEmpty) {
    epochs.foreach(validateEpoch)
    val indices = epochs.map(_.index)
    val bounds = epochBounds
    val oldest = math.min(indices.min, bounds.map(_._1).getOrElse(indices.min))
    val newest = math.max(indices.max, bounds.map(_._2).getOrElse(indices.max))
    write(epochs.toVector.map(e => Put(key(epochKey(e.index)), encode(e))) ++
      Vector(Put(key("epoch/oldest"), encode(oldest)), Put(key("epoch/newest"), encode(newest))))
  }

  /**
   * Drops the oldest epochs beyond the cap, at most `limit` per call. Steady state removes one at
   * a time; the batch bound only matters for a table that has been idle across many epochs.
   */
  def pruneEpochs(floor: Int, limit: Int = 64): Int = epochBounds match {
    case Some((oldest, newest)) if oldest < floor =>
      val end = math.min(math.min(floor, oldest + limit), newest + 1)
      write((oldest until end).toVector.map(i => Delete(key(epochKey(i)))) :+
        Put(key("epoch/oldest"), encode(end)))
      end - oldest
    case _ => 0
  }

  def epochRange(fromIndex: Int, toIndex: Int): Vector[DifficultyEpoch] = {
    require(fromIndex >= 0 && toIndex >= fromIndex, "invalid difficulty epoch range")
    require(toIndex - fromIndex < DifficultyEpochs.MaxEpochs, "difficulty epoch range exceeds the table")
    (fromIndex to toIndex).toVector.flatMap(epoch)
  }

  private def validateEpoch(record: DifficultyEpoch): Unit = {
    require(record.index >= 0 && record.startHeight == DifficultyEpochs.startHeight(record.index) &&
      record.endHeight >= record.startHeight && record.endHeight <= DifficultyEpochs.endHeight(record.index),
      "difficulty epoch does not match its index")
    require(record.endTimestamp >= record.startTimestamp && BigInt(record.difficulty) >= 0,
      "invalid difficulty epoch record")
  }

  /** Sampled local observations are not canonical contributions and are never rolled back with a block. */
  def saveLocal(observations: Iterable[LocalMiningObservation]): Unit = observations.foreach { observation =>
    require(LocalMiningStats.Kinds.contains(observation.kind) && observation.counters.size <= 32 &&
      observation.fraud.size <= 100 && observation.superShareHeights.size <= NispStatus.RequiredShares &&
      observation.counters.values.forall(v => BigInt(v) >= 0),
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
      recent.lastOption.map(_.difficulty))
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
    require(record.lending.forall(l => l.blockId == at.blockId && l.height == at.height && l.timestamp == at.timestamp &&
      record.blocks.exists(_.collateralBoxId == l.collateralBoxId) && BigInt(l.postedNanoErg) >= 0 &&
      BigInt(l.coinbaseNanoErg) >= 0 && BigInt(l.feesNanoErg) >= 0 && l.joinedHeight.forall(_ <= at.height)),
      "invalid lending record")
  }

  /**
   * Whether this store still wants a carry-over: it has not started its history, and no carry-over has finished.
   * One that stopped part way runs again, skipping what it already copied.
   */
  private[stats] def fresh: Boolean = current.isEmpty && get("carried").isEmpty

  /**
   * Copies what the chain cannot rebuild from a store an older schema wrote: local observations, and the
   * difficulty epoch table, which would otherwise cost a header read per epoch to backfill. Only from a store of
   * the same network, contracts and miner, and only records that parse here. The epoch table comes over whole or
   * not at all, since a gap in it would never be backfilled.
   *
   * @return how many records were copied
   */
  private[stats] def carryOver(older: KeyValueReadView, version: Int): Int = {
    val theirs = KeyValueStore.orThrow(older.get(key("identity"))).map(decode[JsObject])
    require(theirs.exists(_ - "schema" == identity - "schema"),
      "it was written for another network, contract set or miner")
    def names(prefix: String) = KeyValueStore.orThrow(older.scanKeys(key(prefix))).map(new String(_, UTF_8))
    def read(name: String) = KeyValueStore.orThrow(older.get(key(name)))
    def parses(name: String, bytes: Array[Byte]): Boolean = Try(name match {
      case "local/oldest-hour" => decode[Long](bytes)
      case "epoch/oldest" | "epoch/newest" => decode[Int](bytes)
      case n if n.startsWith("epoch/") => validateEpoch(decode[DifficultyEpoch](bytes))
      case _ => decode[LocalMiningObservation](bytes)
    }).isSuccess
    val epochs = names("epoch/")
    val epochsParse = epochs.forall(name => read(name).exists(parses(name, _)))
    var copied = 0
    // Read and written a batch at a time, so a long history never sits in memory whole
    (names("local/") ++ (if (epochsParse) epochs else Vector.empty)).grouped(512).foreach { batch =>
      val puts = batch.filter(name => get(name).isEmpty)
        .flatMap(name => read(name).filter(parses(name, _)).map(bytes => Put(key(name), bytes)))
      if (puts.nonEmpty) write(puts)
      copied += puts.size
    }
    write(Seq(Put(key("carried"), encode(version))))
    copied
  }
  private def saveState(next: MiningLedgerState, changes: Vector[KeyValueMutation]): Unit = {
    write(changes :+ Put(key("state"), encode(next)))
    current = Some(next)
  }
  def close(): Unit = database.foreach(db => KeyValueStore.orThrow(db.close()))
}
