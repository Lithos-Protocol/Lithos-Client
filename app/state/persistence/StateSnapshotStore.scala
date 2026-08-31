package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{AuthenticatedDictionaryView, DeferredDictionary, DictionaryManifestHeader, MinerDictionaryMetadata, PlasmaDictionary, RollupMetadata}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import sigma.data.AvlTreeFlags
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, DictionaryTransform, QuarantineFault, SyncProtocolContext}
import storage.KeyValueMutation.{Delete, Put}
import storage.{KeyValueMutation, KeyValueStore, StoreError, WriteDurability}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.Manifest

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

sealed trait SnapshotError {
  def message: String
}

object SnapshotError {
  final case class Storage(error: StoreError) extends SnapshotError {
    override def message: String = error.message
  }
  final case class Corrupt(detail: String) extends SnapshotError {
    override def message: String = s"Corrupt synchronization snapshot: $detail"
  }
}

/** A checkpoint boundary and the recent canonical cursors needed to locate forks after restart. */
final case class PersistedSyncState(state: CommittedSyncState, recentCursors: Vector[SyncCursor])

sealed trait SnapshotDictionaryBase
object SnapshotDictionaryBase {
  /** Used before the first persistent checkpoint; the view is treated as immutable. */
  final case class Materialized(dictionary: AuthenticatedDictionaryView) extends SnapshotDictionaryBase
  /** Content-addressed dictionary from an older retained checkpoint. */
  final case class Persisted(digest: String) extends SnapshotDictionaryBase
  /** A rollup introduced after the older checkpoint starts from the protocol empty tree. */
  case object Empty extends SnapshotDictionaryBase
}

final case class SnapshotDictionarySource(expectedDigest: String,
                                          flags: AvlTreeFlags,
                                          parameters: PlasmaParameters,
                                          base: SnapshotDictionaryBase,
                                          transforms: Vector[DictionaryTransform])

/** A lagging checkpoint boundary that can be reconstructed one dictionary at a time. */
final case class SnapshotCheckpoint(metadata: CommittedSyncMetadata,
                                    recentCursors: Vector[SyncCursor],
                                    dictionaries: Map[DictionaryId, SnapshotDictionarySource])

trait StateSnapshotStore {
  /**
   * Persists a checkpoint. Dictionary serialization and database writes happen in this call so its
   * caller can put the whole operation on the blocking persistence dispatcher.
   */
  def save(snapshot: SnapshotCheckpoint, maxEntryBytes: Int): Either[SnapshotError, Unit]

  /** Generation keys, newest first, with the one the pointer names ahead of the rest. */
  def generationKeys(): Either[SnapshotError, Seq[Array[Byte]]]

  /** Loads one complete generation. */
  def load(key: Array[Byte]): Either[SnapshotError, PersistedSyncState]

  /** Loads one immutable content-addressed dictionary without loading any generation. */
  def loadDictionary(digest: String): Either[SnapshotError, PlasmaDictionary]

  def close(): Either[SnapshotError, Unit]
}

/** Configuration identity that must match before persisted protocol state can be reused. */
final case class StateSnapshotIdentity(networkType: String,
                                       rollupStartHeight: Int,
                                       localMinerHash: String,
                                       holdingErgoTree: String,
                                       evaluationErgoTree: String,
                                       payoutErgoTree: String,
                                       collateralErgoTree: String,
                                       minerDictionaryToken: String,
                                       minerDictionaryGenesisId: String,
                                       collateralToken: String)

object StateSnapshotIdentity {
  def apply(protocol: SyncProtocolContext): StateSnapshotIdentity =
    StateSnapshotIdentity(protocol.networkType.toString, protocol.rollupStartHeight,
      Hex.toHexString(protocol.localMinerHash), protocol.holdingErgoTree, protocol.evaluationErgoTree,
      protocol.payoutErgoTree, protocol.collateralErgoTree, protocol.minerDictionaryToken.toString,
      protocol.minerDictionaryGenesisId, protocol.collateralToken.toString)
}

/**
 * An atomic checkpoint pointer plus content-addressed immutable AVL blobs.
 *
 * A dictionary is sliced once and each serialized subtree is written before the next is produced.
 * The dictionary header is committed last, so a crash cannot make a partial blob readable. A
 * generation contains only metadata and digest references and is made current atomically.
 */
final class LevelDbStateSnapshotStore(store: KeyValueStore,
                                      retention: Int,
                                      identity: StateSnapshotIdentity) extends StateSnapshotStore {
  import LevelDbStateSnapshotStore._
  import SnapshotError._

  require(retention >= 2, "At least two snapshot generations must be retained")

  private val generationPrefix = bytes("sync/snapshot/gen/")
  private val dictionaryPrefix = bytes("sync/snapshot/blob/")
  private val currentKey = bytes("sync/snapshot/current")
  private val logger: Logger = LoggerFactory.getLogger("LevelDbStateSnapshotStore")
  private val lifecycleLock = new ReentrantReadWriteLock()
  // A digest is immutable only while its committed blob is readable. A proven-corrupt blob must not
  // pass the cheap reuse check on a later checkpoint; the next materialized source rewrites it.
  private val invalidDictionaries = ConcurrentHashMap.newKeySet[String]()

  override def save(snapshot: SnapshotCheckpoint,
                    maxEntryBytes: Int): Either[SnapshotError, Unit] = {
    val state = snapshot.metadata
    val base = generationPrefix ++ bytes(
      f"${state.cursor.height}%010d-${state.version}%020d-${state.cursor.blockId}")

    val expectedIds: Set[DictionaryId] =
      state.rollups.keySet.map(id => DictionaryId.Rollup(id): DictionaryId) + DictionaryId.Miner
    def metadataDigest(id: DictionaryId): String = id match {
      case DictionaryId.Rollup(blockId) => state.rollups(blockId).dictionaryDigest
      case DictionaryId.Miner => state.minerDictionary.dictionaryDigest
    }

    val committed = withWriteLock(for {
      _ <- if (snapshot.dictionaries.keySet == expectedIds) Right(()) else Left(Corrupt(
        "checkpoint dictionary sources do not exactly match its tracked metadata"))
      _ <- if (snapshot.dictionaries.forall { case (id, source) =>
        source.expectedDigest == metadataDigest(id)
      }) Right(()) else Left(Corrupt(
        "checkpoint dictionary source digest does not match its entity metadata"))
      _ <- snapshot.dictionaries.toSeq.sortBy(_._1.value)
        .foldLeft[Either[SnapshotError, Unit]](Right(())) {
        case (result, (_, source)) => result.flatMap(_ => ensureDictionary(source, maxEntryBytes))
      }
      meta <- StateSnapshotCodec.encodeMeta(snapshot, identity, maxEntryBytes).left.map(Corrupt)
      // The metadata and pointer are the only generation commit. All referenced blobs are complete.
      _ <- store.write(Seq(Put(base ++ MetaSuffix, meta), Put(currentKey, base)),
        WriteDurability.Synchronous).left.map(Storage)
    } yield ())
    committed.map { _ =>
      // The pointer commit above is the durability boundary. Cleanup is best effort and retried by
      // every later save; reporting it as a failed checkpoint would retain an unnecessary journal.
      //
      // It runs after the write lock is released. Deciding what to delete means decoding every
      // retained generation and scanning every blob key, and holding the exclusive lock across that
      // added all of it to the window in which no dictionary can be materialized — including the
      // reconstructions a canonical block is waiting on. Only the delete batches take the lock.
      pruneGenerations(base).left.foreach(error =>
        logger.warn(s"Checkpoint ${state.cursor.blockId}@${state.cursor.height} committed, but " +
          s"generation pruning failed: ${error.message}"))
      collectUnreferencedDictionaries().left.foreach(error =>
        logger.warn(s"Checkpoint ${state.cursor.blockId}@${state.cursor.height} committed, but " +
          s"dictionary garbage collection failed: ${error.message}"))
    }
  }

  override def generationKeys(): Either[SnapshotError, Seq[Array[Byte]]] =
    withReadLock(store.readSnapshot { view =>
      for {
        current <- view.get(currentKey)
        keys <- view.scanKeys(generationPrefix)
      } yield current -> keys
    }.left.map(Storage).map { case (current, keys) =>
      val bases = keys.filter(endsWith(_, MetaSuffix)).map(_.dropRight(MetaSuffix.length))
      val newestFirst = bases.sortWith((left, right) => unsignedCompare(left, right) > 0)
      val pointed = current.toSeq.filter(key => bases.exists(java.util.Arrays.equals(_, key)))
      distinctKeys(pointed ++ newestFirst)
    })

  override def load(base: Array[Byte]): Either[SnapshotError, PersistedSyncState] =
    withReadLock(store.readSnapshot { view =>
      val copied: Either[SnapshotError, (Array[Byte], Map[String, Array[Byte]])] =
        view.get(base ++ MetaSuffix).left.map(Storage).flatMap {
          case None => Left(Corrupt(s"generation ${name(base)} has no metadata entry"))
          case Some(encodedMeta) => StateSnapshotCodec.decodeMeta(encodedMeta, identity)
            .left.map(Corrupt).flatMap { meta =>
              meta.dictionaryDigests.toSeq.sorted.foldLeft[
                Either[SnapshotError, Map[String, Array[Byte]]]](Right(Map.empty)) {
                case (result, digest) => result.flatMap { headers =>
                  view.get(dictionaryBase(digest) ++ HeaderSuffix).left.map(Storage).flatMap {
                    case Some(header) => Right(headers + (digest -> header))
                    case None => Left(Corrupt(s"dictionary $digest has no committed header"))
                  }
                }
              }.map(encodedMeta -> _)
            }
        }
      Right[StoreError, Either[SnapshotError, (Array[Byte], Map[String, Array[Byte]])]](copied)
    }.left.map(Storage).flatMap(result => result).flatMap { case (encodedMeta, headers) =>
      for {
        meta <- StateSnapshotCodec.decodeMeta(encodedMeta, identity).left.map(Corrupt)
        references <- headers.foldLeft[
          Either[SnapshotError, Map[String, DeferredDictionary]]](Right(Map.empty)) {
          case (result, (digest, encoded)) => result.flatMap { found =>
            decodeDictionaryReference(digest, encoded).map(reference => found + (digest -> reference))
          }
        }
        miner <- references.get(meta.metadata.minerDictionary.dictionaryDigest).toRight(Corrupt(
          s"Miner Dictionary ${meta.metadata.minerDictionary.dictionaryDigest} has no decoded reference"))
        rollups <- meta.metadata.rollups.toSeq.foldLeft[
          Either[SnapshotError, Map[String, AuthenticatedDictionaryView]]](Right(Map.empty)) {
          case (result, (id, rollup)) => result.flatMap { found =>
            references.get(rollup.dictionaryDigest).toRight(Corrupt(
              s"rollup $id dictionary ${rollup.dictionaryDigest} has no decoded reference"))
              .map(dictionary => found + (id -> dictionary))
          }
        }
        assembled <- StateSnapshotCodec.assemble(meta, rollups, miner).left.map(Corrupt)
      } yield assembled
    })

  override def loadDictionary(digest: String): Either[SnapshotError, PlasmaDictionary] = withReadLock {
    val base = dictionaryBase(digest)
    val loaded = for {
      encodedHeader <- required(base ++ HeaderSuffix, s"dictionary $digest has no committed header")
      header <- StateSnapshotCodec.decodeDictionaryHeader(encodedHeader).left.map(Corrupt)
      _ <- if (Hex.toHexString(header.digest) == digest) Right(())
      else Left(Corrupt(s"dictionary key $digest contains digest ${Hex.toHexString(header.digest)}"))
      // Values returned by the store are owned copies. Decode after each database call has released
      // its view; only this one dictionary's serialized subtrees are live during reconstruction.
      subtrees <- (0 until header.subtreeCount).foldLeft[
        Either[SnapshotError, Vector[Array[Byte]]]](Right(Vector.empty)) { (result, index) =>
        result.flatMap { found =>
          required(subtreeKey(base, index), s"dictionary $digest is missing subtree $index")
            .flatMap(value => StateSnapshotCodec.decodeDictionarySubtree(value, index)
              .left.map(Corrupt).map(found :+ _))
        }
      }
      dictionary <- PlasmaDictionary.fromManifest(header.flags, header.parameters,
        Manifest(header.digest, header.manifest, subtrees)).left.map { error =>
        Corrupt(s"dictionary $digest could not be loaded: ${message(error)}")
      }
    } yield dictionary
    loaded match {
      case Left(Corrupt(_)) => invalidDictionaries.add(digest)
      case _ => ()
    }
    loaded
  }

  private def decodeDictionaryReference(digest: String,
                                        encoded: Array[Byte]): Either[SnapshotError, DeferredDictionary] = {
    for {
      header <- StateSnapshotCodec.decodeDictionaryHeader(encoded).left.map(Corrupt)
      _ <- if (Hex.toHexString(header.digest) == digest) Right(())
      else Left(Corrupt(s"dictionary key $digest contains digest ${Hex.toHexString(header.digest)}"))
    } yield DeferredDictionary(header.digest, header.flags, header.parameters)
  }

  override def close(): Either[SnapshotError, Unit] =
    withWriteLock(store.close().left.map(Storage))

  private def ensureDictionary(dictionary: AuthenticatedDictionaryView,
                               maxEntryBytes: Int): Either[SnapshotError, Unit] = {
    val digest = Hex.toHexString(dictionary.digest)
    val base = dictionaryBase(digest)

    reusableDictionary(base, digest, dictionary.flags, dictionary.parameters) match {
      case Left(error) => Left(error)
      case Right(true) => Right(())
      case Right(false) =>
        for {
          stale <- store.scanKeys(base).left.map(Storage)
          _ <- writeChunked(stale.map(Delete))
          header <- streamDictionary(dictionary, base, maxEntryBytes)
          _ <- if (java.util.Arrays.equals(header.digest, dictionary.digest) &&
            header.flags == dictionary.flags && header.parameters == dictionary.parameters) Right(())
          else Left(Corrupt("serialized dictionary header does not match its source"))
          encoded <- StateSnapshotCodec.encodeDictionaryHeader(header, maxEntryBytes).left.map(Corrupt)
          // Header-last is the per-blob commit marker.
          _ <- store.put(base ++ HeaderSuffix, encoded, WriteDurability.Synchronous).left.map(Storage)
          _ = invalidDictionaries.remove(digest)
        } yield ()
    }
  }

  private def ensureDictionary(source: SnapshotDictionarySource,
                               maxEntryBytes: Int): Either[SnapshotError, Unit] = {
    val base = dictionaryBase(source.expectedDigest)
    reusableDictionary(base, source.expectedDigest, source.flags, source.parameters).flatMap {
      case true => Right(())
      case false =>
        for {
          starting <- source.base match {
            case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
            case SnapshotDictionaryBase.Persisted(digest) => loadDictionary(digest)
            case SnapshotDictionaryBase.Empty => Right(PlasmaDictionary.empty(source.flags, source.parameters))
          }
          materialized <- source.transforms.foldLeft[
            Either[SnapshotError, AuthenticatedDictionaryView]](Right(starting)) {
            case (result, transform) => result.flatMap(dictionary =>
              transform.replay(dictionary).left.map(Corrupt))
          }
          actual = Hex.toHexString(materialized.digest)
          _ <- if (actual == source.expectedDigest) Right(()) else Left(Corrupt(
            s"checkpoint materialized dictionary $actual, expected ${source.expectedDigest}"))
          _ <- if (materialized.flags == source.flags && materialized.parameters == source.parameters)
            Right(()) else Left(Corrupt("checkpoint dictionary parameters changed during replay"))
          _ <- ensureDictionary(materialized, maxEntryBytes)
        } yield ()
    }
  }

  private def reusableDictionary(base: Array[Byte],
                                 digest: String,
                                 flags: AvlTreeFlags,
                                 parameters: PlasmaParameters): Either[SnapshotError, Boolean] =
    if (invalidDictionaries.contains(digest)) Right(false)
    else store.get(base ++ HeaderSuffix).left.map(Storage).flatMap {
      case None => Right(false)
      case Some(value) => StateSnapshotCodec.decodeDictionaryHeader(value) match {
        case Left(_) => Right(false)
        case Right(header) if Hex.toHexString(header.digest) != digest || header.flags != flags ||
          header.parameters != parameters => Right(false)
        case Right(header) => store.scanKeys(base ++ SubtreeInfix).left.map(Storage).map { keys =>
          keys.size == header.subtreeCount && (0 until header.subtreeCount).forall { index =>
            keys.exists(java.util.Arrays.equals(_, subtreeKey(base, index)))
          }
        }
      }
    }

  private def streamDictionary(dictionary: AuthenticatedDictionaryView,
                               base: Array[Byte],
                               maxEntryBytes: Int): Either[SnapshotError, DictionaryManifestHeader] =
    try {
      var failure = Option.empty[SnapshotError]
      val header = dictionary.writeManifest(SubtreeDepth) { (index, bytes) =>
        if (failure.isEmpty) {
          StateSnapshotCodec.encodeDictionarySubtree(bytes, maxEntryBytes, index) match {
            case Left(reason) => failure = Some(Corrupt(reason))
            case Right(encoded) =>
              store.put(subtreeKey(base, index), encoded, WriteDurability.Buffered) match {
                case Left(error) => failure = Some(Storage(error))
                case Right(()) => ()
              }
          }
        }
      }
      failure.fold[Either[SnapshotError, DictionaryManifestHeader]](Right(header))(Left(_))
    } catch {
      case NonFatal(error) => Left(Corrupt(s"dictionary serialization failed: ${message(error)}"))
    }

  /** Keeps complete generation records; dictionaries are shared and collected separately. */
  private def pruneGenerations(current: Array[Byte]): Either[SnapshotError, Unit] =
    withReadLock(store.scanKeys(generationPrefix).left.map(Storage)).flatMap { keys =>
      val bases = keys.filter(endsWith(_, MetaSuffix)).map(_.dropRight(MetaSuffix.length))
      val keep = distinctKeys(bases.sortWith((left, right) => unsignedCompare(left, right) > 0)
        .take(retention) :+ current)
      val obsolete = keys.filterNot(key => keep.exists(base => startsWith(key, base)))
      deleteExclusively(obsolete)
    }

  /**
   * Removes a content-addressed blob only when no retained generation metadata references it.
   */
  private def collectUnreferencedDictionaries(): Either[SnapshotError, Unit] =
    withReadLock(for {
      generationEntries <- store.scanPrefix(generationPrefix).left.map(Storage)
      referenced <- generationEntries.filter(entry => endsWith(entry._1, MetaSuffix))
        .foldLeft[Either[SnapshotError, Set[String]]](Right(Set.empty)) {
          case (result, (_, value)) => result.flatMap { digests =>
            StateSnapshotCodec.decodeMeta(value, identity).left.map(Corrupt).map { meta =>
              digests ++ meta.dictionaryDigests
            }
          }
        }
      blobKeys <- store.scanKeys(dictionaryPrefix).left.map(Storage)
    } yield blobKeys.filterNot { key =>
      dictionaryDigestFromKey(key).exists(referenced.contains)
    }).flatMap(deleteExclusively)

  /** Deletions exclude readers; an empty set takes no lock at all. */
  private def deleteExclusively(obsolete: Seq[Array[Byte]]): Either[SnapshotError, Unit] =
    if (obsolete.isEmpty) Right(()) else withWriteLock(writeChunked(obsolete.map(Delete)))

  private def required(key: Array[Byte], absent: String): Either[SnapshotError, Array[Byte]] =
    store.get(key).left.map(Storage).flatMap {
      case Some(value) => Right(value)
      case None => Left(Corrupt(absent))
    }

  private def writeChunked(mutations: Seq[KeyValueMutation]): Either[SnapshotError, Unit] =
    mutations.grouped(WriteChunk).foldLeft[Either[SnapshotError, Unit]](Right(())) {
      case (result, chunk) => result.flatMap(_ =>
        store.write(chunk, WriteDurability.Buffered).left.map(Storage))
    }

  private def dictionaryBase(digest: String): Array[Byte] = dictionaryPrefix ++ bytes(digest)

  private def dictionaryDigestFromKey(key: Array[Byte]): Option[String] = {
    val text = name(key)
    val prefix = name(dictionaryPrefix)
    if (!text.startsWith(prefix)) None
    else text.substring(prefix.length).split('/').headOption.filter(_.nonEmpty)
  }

  private def name(key: Array[Byte]): String = new String(key, StandardCharsets.UTF_8)

  private def withReadLock[A](operation: => A): A = {
    val lock = lifecycleLock.readLock()
    lock.lock()
    try operation finally lock.unlock()
  }

  private def withWriteLock[A](operation: => A): A = {
    val lock = lifecycleLock.writeLock()
    lock.lock()
    try operation finally lock.unlock()
  }
}

object LevelDbStateSnapshotStore {
  private val MetaSuffix = bytes("/meta")
  private val HeaderSuffix = bytes("/header")
  private val SubtreeInfix = bytes("/s/")
  private val SubtreeDepth = 8
  private val WriteChunk = 32

  private def subtreeKey(base: Array[Byte], index: Int): Array[Byte] =
    base ++ SubtreeInfix ++ bytes(f"$index%08d")

  private def bytes(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)

  private def startsWith(value: Array[Byte], prefix: Array[Byte]): Boolean =
    value.length >= prefix.length && java.util.Arrays.equals(value.take(prefix.length), prefix)

  private def endsWith(value: Array[Byte], suffix: Array[Byte]): Boolean =
    value.length > suffix.length && java.util.Arrays.equals(value.takeRight(suffix.length), suffix)

  private def unsignedCompare(left: Array[Byte], right: Array[Byte]): Int = {
    val length = math.min(left.length, right.length)
    var index = 0
    while (index < length) {
      val compared = java.lang.Integer.compare(left(index) & 0xff, right(index) & 0xff)
      if (compared != 0) return compared
      index += 1
    }
    java.lang.Integer.compare(left.length, right.length)
  }

  private def distinctKeys(keys: Seq[Array[Byte]]): Vector[Array[Byte]] =
    keys.foldLeft(Vector.empty[Array[Byte]]) { (result, key) =>
      if (result.exists(java.util.Arrays.equals(_, key))) result else result :+ key
    }

  private def message(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
}

/** One small generation record. Authenticated dictionaries live in content-addressed blobs. */
final case class SnapshotMeta(metadata: CommittedSyncMetadata,
                              recentCursors: Vector[SyncCursor]) {
  def dictionaryDigests: Set[String] =
    metadata.rollups.valuesIterator.map(_.dictionaryDigest).toSet +
      metadata.minerDictionary.dictionaryDigest
}

/** Explicit binary schema for checkpoint metadata and streamed native Plasma manifests. */
object StateSnapshotCodec {
  private val EnvelopeMagic = 0x4c535345
  private val MetaMagic = 0x4c53534d
  private val DictionaryMagic = 0x4c535344
  private val SubtreeMagic = 0x4c535353
  private val SchemaVersion = 9
  private val ToolkitVersion = "plasma-toolkit-1.1.0"
  private val MaxBlobBytes = 512 * 1024 * 1024
  private val MaxEntries = 1000000

  def encodeMeta(snapshot: SnapshotCheckpoint,
                 identity: StateSnapshotIdentity,
                 maxEntryBytes: Int): Either[String, Array[Byte]] = attempt {
    val metadata = snapshot.metadata
    val payload = writeBytes { out =>
      out.writeInt(MetaMagic)
      out.writeInt(SchemaVersion)
      writeString(out, ToolkitVersion)
      writeIdentity(out, identity)
      writeCursor(out, metadata.cursor)
      out.writeLong(metadata.version)
      writeCount(out, snapshot.recentCursors.size, "recent cursor count")
      snapshot.recentCursors.foreach(writeCursor(out, _))
      out.writeBoolean(metadata.minerDictionaryFault.isDefined)
      metadata.minerDictionaryFault.foreach(writeString(out, _))

      val faults = metadata.quarantined.toSeq.sortBy(_._1)
      writeCount(out, faults.size, "quarantine count")
      faults.foreach { case (_, fault) => writeQuarantine(out, fault) }

      writeStringMap(out, metadata.routes, "route count")
      writeStringMap(out, metadata.rollupOrigins, "rollup origin count")
      writeMinerMetadata(out, metadata.minerDictionary)
      out.writeBoolean(metadata.dataBoxToken.isDefined)
      metadata.dataBoxToken.foreach(token => writeString(out, token.toString))

      val rollups = metadata.rollups.toSeq.sortBy(_._1)
      writeCount(out, rollups.size, "rollup metadata count")
      rollups.foreach { case (id, rollup) =>
        writeString(out, id)
        writeRollupMetadata(out, rollup)
      }
    }
    sealEntry(payload, maxEntryBytes, "snapshot metadata")
  }

  def decodeMeta(encoded: Array[Byte],
                 expectedIdentity: StateSnapshotIdentity): Either[String, SnapshotMeta] = attempt {
    val in = openEntry(encoded)
    require(in.readInt() == MetaMagic, "invalid metadata magic")
    require(in.readInt() == SchemaVersion, "unsupported snapshot schema version")
    require(readString(in) == ToolkitVersion, "unsupported Plasma Toolkit version")
    require(readIdentity(in) == expectedIdentity,
      "snapshot network, wallet, start height, or protocol identity does not match this client")
    val cursor = readCursor(in)
    val version = in.readLong()
    val recentCursors = readCount(in, "recent cursor count")(_ => readCursor(in))
    val minerFault = if (in.readBoolean()) Some(readString(in)) else None
    val quarantined = readCount(in, "quarantine count")(_ => readQuarantine(in))
      .map(fault => fault.rollupId -> fault).toMap
    val routes = readStringMap(in, "route count")
    val origins = readStringMap(in, "rollup origin count")
    val miner = readMinerMetadata(in)
    val token = if (in.readBoolean()) Some(ErgoId.create(readString(in))) else None
    val rollups = readCount(in, "rollup metadata count") { _ =>
      readString(in) -> readRollupMetadata(in)
    }.toMap
    requireDictionaryDigest(miner.dictionaryDigest, "Miner Dictionary")
    rollups.foreach { case (id, rollup) =>
      require(rollup.blockId == id, s"rollup metadata $id names block ${rollup.blockId}")
      requireDictionaryDigest(rollup.dictionaryDigest, s"rollup $id")
    }
    require(in.available() == 0, "trailing bytes after snapshot metadata")
    SnapshotMeta(CommittedSyncMetadata(cursor, version, rollups, routes, origins, miner, token,
      minerFault, quarantined), recentCursors)
  }

  def encodeDictionaryHeader(header: DictionaryManifestHeader,
                             maxEntryBytes: Int): Either[String, Array[Byte]] = attempt {
    val payload = writeBytes { out =>
      out.writeInt(DictionaryMagic)
      out.writeInt(SchemaVersion)
      out.writeByte(header.flags.serializeToByte)
      out.writeInt(header.parameters.keySize)
      out.writeBoolean(header.parameters.valueSizeOpt.isDefined)
      header.parameters.valueSizeOpt.foreach(out.writeInt)
      writeByteArray(out, header.digest)
      writeByteArray(out, header.manifest)
      writeCount(out, header.subtreeCount, "manifest subtree count")
    }
    sealEntry(payload, maxEntryBytes, "dictionary header")
  }

  def decodeDictionaryHeader(encoded: Array[Byte]): Either[String, DictionaryManifestHeader] = attempt {
    val in = openEntry(encoded)
    require(in.readInt() == DictionaryMagic, "invalid dictionary header magic")
    require(in.readInt() == SchemaVersion, "unsupported dictionary header version")
    val flags = AvlTreeFlags(in.readByte())
    val keySize = in.readInt()
    val valueSize = if (in.readBoolean()) Some(in.readInt()) else None
    val digest = readByteArray(in)
    val manifest = readByteArray(in)
    val count = readCountValue(in, "manifest subtree count")
    require(in.available() == 0, "trailing bytes after dictionary header")
    DictionaryManifestHeader(flags, PlasmaParameters(keySize, valueSize), digest, manifest, count)
  }

  def encodeDictionarySubtree(bytes: Array[Byte],
                              maxEntryBytes: Int,
                              index: Int): Either[String, Array[Byte]] = attempt {
    val payload = writeBytes { out =>
      out.writeInt(SubtreeMagic)
      out.writeInt(SchemaVersion)
      out.writeInt(index)
      writeByteArray(out, bytes)
    }
    sealEntry(payload, maxEntryBytes, s"dictionary subtree $index")
  }

  def decodeDictionarySubtree(encoded: Array[Byte], expectedIndex: Int): Either[String, Array[Byte]] = attempt {
    val in = openEntry(encoded)
    require(in.readInt() == SubtreeMagic, "invalid dictionary subtree magic")
    require(in.readInt() == SchemaVersion, "unsupported dictionary subtree version")
    require(in.readInt() == expectedIndex, s"dictionary subtree does not name index $expectedIndex")
    val bytes = readByteArray(in)
    require(in.available() == 0, "trailing bytes after dictionary subtree")
    bytes
  }

  def assemble(meta: SnapshotMeta,
               rollupDictionaries: Map[String, AuthenticatedDictionaryView],
               minerDictionary: AuthenticatedDictionaryView): Either[String, PersistedSyncState] =
    CommittedSyncMetadata.materialize(meta.metadata, rollupDictionaries, minerDictionary)
      .map(PersistedSyncState(_, meta.recentCursors))

  private def writeRollupMetadata(out: DataOutputStream, rollup: RollupMetadata): Unit = {
    out.writeInt(rollup.numMiners)
    writeBigInt(out, rollup.totalScore)
    writeOptionalLong(out, rollup.currentPeriod)
    out.writeLong(rollup.totalReward)
    out.writeInt(rollup.startHeight)
    out.writeBoolean(rollup.hasMiner)
    out.writeByte(phaseByte(rollup.phase))
    out.writeBoolean(rollup.evaluated)
    writeString(out, rollup.blockId)
    writeString(out, rollup.utxoId)
    writeString(out, rollup.dictionaryDigest)
  }

  private def readRollupMetadata(in: DataInputStream): RollupMetadata =
    RollupMetadata(in.readInt(), readBigInt(in), readOptionalLong(in), in.readLong(), in.readInt(),
      in.readBoolean(), readPhase(in.readByte()), in.readBoolean(), readString(in), readString(in),
      readString(in))

  private def writeMinerMetadata(out: DataOutputStream, miner: MinerDictionaryMetadata): Unit = {
    out.writeInt(miner.numMiners)
    out.writeInt(miner.startHeight)
    out.writeBoolean(miner.hasMiner)
    writeString(out, miner.utxoId)
    out.writeBoolean(miner.synced)
    out.writeInt(miner.syncHeight)
    out.writeInt(miner.savedHeight)
    writeString(out, miner.dictionaryDigest)
  }

  private def readMinerMetadata(in: DataInputStream): MinerDictionaryMetadata =
    MinerDictionaryMetadata(in.readInt(), in.readInt(), in.readBoolean(), readString(in),
      in.readBoolean(), in.readInt(), in.readInt(), readString(in))

  private def writeQuarantine(out: DataOutputStream, fault: QuarantineFault): Unit = {
    writeString(out, fault.rollupId)
    out.writeInt(fault.genesisHeight)
    writeString(out, fault.collateralBoxId)
    writeString(out, fault.reason)
    out.writeBoolean(fault.retryable)
    out.writeInt(fault.attempts)
    out.writeBoolean(fault.removalHeight.isDefined)
    fault.removalHeight.foreach(out.writeInt)
    out.writeBoolean(fault.removalWarningLogged)
    writeString(out, fault.utxoId)
  }

  private def readQuarantine(in: DataInputStream): QuarantineFault = {
    val rollupId = readString(in)
    QuarantineFault(rollupId, in.readInt(), readString(in), readString(in), in.readBoolean(),
      in.readInt(), if (in.readBoolean()) Some(in.readInt()) else None, in.readBoolean(),
      readString(in))
  }

  private def writeStringMap(out: DataOutputStream,
                             values: Map[String, String],
                             label: String): Unit = {
    val sorted = values.toSeq.sortBy(_._1)
    writeCount(out, sorted.size, label)
    sorted.foreach { case (key, value) => writeString(out, key); writeString(out, value) }
  }

  private def readStringMap(in: DataInputStream, label: String): Map[String, String] =
    readCount(in, label)(_ => readString(in) -> readString(in)).toMap

  private def writeIdentity(out: DataOutputStream, identity: StateSnapshotIdentity): Unit = {
    writeString(out, identity.networkType)
    out.writeInt(identity.rollupStartHeight)
    writeString(out, identity.localMinerHash)
    writeString(out, identity.holdingErgoTree)
    writeString(out, identity.evaluationErgoTree)
    writeString(out, identity.payoutErgoTree)
    writeString(out, identity.collateralErgoTree)
    writeString(out, identity.minerDictionaryToken)
    writeString(out, identity.minerDictionaryGenesisId)
    writeString(out, identity.collateralToken)
  }

  private def readIdentity(in: DataInputStream): StateSnapshotIdentity =
    StateSnapshotIdentity(readString(in), in.readInt(), readString(in), readString(in),
      readString(in), readString(in), readString(in), readString(in), readString(in), readString(in))

  private def writeCursor(out: DataOutputStream, cursor: SyncCursor): Unit = {
    out.writeInt(cursor.height)
    writeString(out, cursor.blockId)
    writeString(out, cursor.parentId)
  }

  private def readCursor(in: DataInputStream): SyncCursor =
    SyncCursor(in.readInt(), readString(in), readString(in))

  private def writeOptionalLong(out: DataOutputStream, value: Option[Long]): Unit = {
    out.writeBoolean(value.isDefined)
    value.foreach(out.writeLong)
  }

  private def readOptionalLong(in: DataInputStream): Option[Long] =
    if (in.readBoolean()) Some(in.readLong()) else None

  private def writeBigInt(out: DataOutputStream, value: BigInt): Unit =
    writeByteArray(out, value.toByteArray)

  private def readBigInt(in: DataInputStream): BigInt = BigInt(readByteArray(in))

  private def sealEntry(payload: Array[Byte], maxEntryBytes: Int, what: String): Array[Byte] = {
    val checksum = Blake2b256.hash(payload)
    val encoded = writeBytes { out =>
      out.writeInt(EnvelopeMagic)
      writeByteArray(out, payload)
      writeByteArray(out, checksum)
    }
    require(encoded.length <= maxEntryBytes,
      s"$what is ${encoded.length} bytes, over the configured $maxEntryBytes limit")
    encoded
  }

  private def openEntry(encoded: Array[Byte]): DataInputStream = {
    val envelope = new DataInputStream(new ByteArrayInputStream(encoded))
    require(envelope.readInt() == EnvelopeMagic, "invalid envelope magic")
    val payload = readByteArray(envelope)
    val checksum = readByteArray(envelope)
    require(envelope.available() == 0, "trailing bytes after snapshot envelope")
    require(java.util.Arrays.equals(Blake2b256.hash(payload), checksum), "payload checksum mismatch")
    new DataInputStream(new ByteArrayInputStream(payload))
  }

  private def writeString(out: DataOutputStream, value: String): Unit =
    writeByteArray(out, value.getBytes(StandardCharsets.UTF_8))

  private def readString(in: DataInputStream): String =
    new String(readByteArray(in), StandardCharsets.UTF_8)

  private def writeByteArray(out: DataOutputStream, value: Array[Byte]): Unit = {
    require(value.length <= MaxBlobBytes,
      s"byte-array of ${value.length} exceeds the $MaxBlobBytes decoder limit")
    out.writeInt(value.length)
    out.write(value)
  }

  private def readByteArray(in: DataInputStream): Array[Byte] = {
    val length = in.readInt()
    require(length >= 0 && length <= MaxBlobBytes, s"invalid byte-array length $length")
    val value = new Array[Byte](length)
    in.readFully(value)
    value
  }

  private def requireDictionaryDigest(value: String, label: String): Unit =
    require(value.length == 66 && value.forall(character =>
      (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')),
      s"$label has an invalid dictionary digest")

  private def readCount[A](in: DataInputStream, label: String)(read: Int => A): Vector[A] = {
    val count = readCountValue(in, label)
    Vector.tabulate(count)(read)
  }

  private def readCountValue(in: DataInputStream, label: String): Int = {
    val count = in.readInt()
    require(count >= 0 && count <= MaxEntries, s"invalid $label $count")
    count
  }

  private def writeCount(out: DataOutputStream, size: Int, label: String): Unit = {
    require(size >= 0 && size <= MaxEntries,
      s"$label of $size exceeds the $MaxEntries decoder limit")
    out.writeInt(size)
  }

  private def phaseByte(phase: LFSMPhase): Byte = phase match {
    case LFSMPhase.HOLDING => 0
    case LFSMPhase.EVAL => 1
    case LFSMPhase.PAYOUT => 2
  }

  private def readPhase(value: Byte): LFSMPhase = value match {
    case 0 => LFSMPhase.HOLDING
    case 1 => LFSMPhase.EVAL
    case 2 => LFSMPhase.PAYOUT
    case other => throw new IllegalArgumentException(s"unknown LFSM phase $other")
  }

  private def writeBytes(write: DataOutputStream => Unit): Array[Byte] = {
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    try {
      write(out)
      out.flush()
      bytes.toByteArray
    } finally out.close()
  }

  private def attempt[A](operation: => A): Either[String, A] =
    try Right(operation)
    catch {
      case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    }
}
