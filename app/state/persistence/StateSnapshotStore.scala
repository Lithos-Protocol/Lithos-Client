package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{AuthenticatedDictionaryView, MinerTree, NISPTree, PlasmaDictionary}
import org.ergoplatform.appkit.Address
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.data.AvlTreeFlags
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncState, QuarantineFault, SyncProtocolContext}
import storage.KeyValueMutation.{Delete, Put}
import storage.{KeyValueStore, StoreError, WriteDurability}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.Manifest

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
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

/** A committed state and the recent canonical cursors needed to locate forks after restart. */
final case class PersistedSyncState(state: CommittedSyncState, recentCursors: Vector[SyncCursor])

/** One generation, encoded. Each rollup is its own entry so no single value grows with the set. */
final case class SnapshotWrite(meta: Array[Byte], rollups: Seq[(String, Array[Byte])])

trait StateSnapshotStore {
  /** Writes an already-encoded generation. Encoding happens on the thread that owns the state. */
  def save(cursor: SyncCursor, version: Long, encoded: SnapshotWrite): Either[SnapshotError, Unit]

  /** Generation keys, newest first, with the one the pointer names ahead of the rest. */
  def generationKeys(): Either[SnapshotError, Seq[Array[Byte]]]

  /** Decodes one generation. Separate from listing so a caller stops at the first it accepts. */
  def load(key: Array[Byte]): Either[SnapshotError, PersistedSyncState]

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
      org.bouncycastle.util.encoders.Hex.toHexString(protocol.localMinerHash),
      protocol.holdingErgoTree, protocol.evaluationErgoTree, protocol.payoutErgoTree,
      protocol.collateralErgoTree, protocol.minerDictionaryToken.toString, protocol.minerDictionaryGenesisId,
      protocol.collateralToken.toString)
}

/**
 * Generation-based LevelDB snapshot repository, one entry per rollup plus a metadata entry.
 * The pointer commits with the metadata, so an interrupted write leaves the prior generation intact.
 */
final class LevelDbStateSnapshotStore(store: KeyValueStore,
                                      retention: Int,
                                      identity: StateSnapshotIdentity) extends StateSnapshotStore {
  import LevelDbStateSnapshotStore._
  import SnapshotError._

  require(retention >= 2, "At least two snapshot generations must be retained")

  private val dataPrefix = bytes("sync/snapshot/gen/")
  private val currentKey = bytes("sync/snapshot/current")

  override def save(cursor: SyncCursor,
                    version: Long,
                    encoded: SnapshotWrite): Either[SnapshotError, Unit] = {
    val base = dataPrefix ++ bytes(f"${cursor.height}%010d-$version%020d-${cursor.blockId}")
    val rollupWrites = encoded.rollups.map { case (id, value) => Put(base ++ rollupKey(id), value) }
    for {
      // Rollups first and unpointed: a crash here leaves entries nothing names, which pruning removes.
      _ <- writeChunked(rollupWrites)
      _ <- store.write(Seq(Put(base ++ MetaSuffix, encoded.meta), Put(currentKey, base)),
        WriteDurability.Synchronous).left.map(Storage)
      _ <- prune(base)
    } yield ()
  }

  override def generationKeys(): Either[SnapshotError, Seq[Array[Byte]]] =
    store.readSnapshot { view =>
      for {
        current <- view.get(currentKey)
        keys <- view.scanKeys(dataPrefix)
      } yield current -> keys
    }.left.map(Storage).map { case (current, keys) =>
      val bases = keys.filter(endsWith(_, MetaSuffix)).map(key => key.dropRight(MetaSuffix.length))
      val newestFirst = bases.sortWith((a, b) => unsignedCompare(a, b) > 0)
      val pointed = current.toSeq.filter(key => bases.exists(java.util.Arrays.equals(_, key)))
      (pointed ++ newestFirst).foldLeft(Vector.empty[Array[Byte]]) { (result, key) =>
        if (result.exists(java.util.Arrays.equals(_, key))) result else result :+ key
      }
    }

  override def load(base: Array[Byte]): Either[SnapshotError, PersistedSyncState] =
    store.readSnapshot { view =>
      val loaded: Either[SnapshotError, PersistedSyncState] =
        view.get(base ++ MetaSuffix).left.map(Storage).flatMap {
          case None => Left(Corrupt(s"generation ${name(base)} has no metadata entry"))
          case Some(metaBytes) =>
            StateSnapshotCodec.decodeMeta(metaBytes, identity).left.map(Corrupt).flatMap { meta =>
              meta.rollupIds.foldLeft[Either[SnapshotError, Map[String, NISPTree]]](Right(Map.empty)) {
                (collected, id) =>
                  collected.flatMap { rollups =>
                    view.get(base ++ rollupKey(id)).left.map(Storage).flatMap {
                      case None => Left(Corrupt(s"generation ${name(base)} is missing rollup $id"))
                      case Some(value) => StateSnapshotCodec.decodeRollup(value, id)
                        .left.map(Corrupt).map(tree => rollups + (id -> tree))
                    }
                  }
              }.flatMap(rollups => StateSnapshotCodec.assemble(meta, rollups).left.map(Corrupt))
            }
        }
      Right[StoreError, Either[SnapshotError, PersistedSyncState]](loaded)
    }.left.map(Storage).flatMap(result => result)

  override def close(): Either[SnapshotError, Unit] = store.close().left.map(Storage)

  /** Keeps the newest generations whole and removes every entry belonging to the rest. */
  private def prune(current: Array[Byte]): Either[SnapshotError, Unit] =
    store.scanKeys(dataPrefix).left.map(Storage).flatMap { keys =>
      val bases = keys.filter(endsWith(_, MetaSuffix)).map(_.dropRight(MetaSuffix.length))
      val keep = bases.sortWith((a, b) => unsignedCompare(a, b) > 0).take(retention) :+ current
      val obsolete = keys.filterNot(key => keep.exists(base => startsWith(key, base)))
      writeChunked(obsolete.map(Delete))
    }

  /** Bounds one batch so a large generation does not build a single oversized write. */
  private def writeChunked(mutations: Seq[storage.KeyValueMutation]): Either[SnapshotError, Unit] =
    mutations.grouped(WriteChunk).foldLeft[Either[SnapshotError, Unit]](Right(())) { (result, chunk) =>
      result.flatMap(_ => store.write(chunk, WriteDurability.Buffered).left.map(Storage))
    }

  private def rollupKey(id: String): Array[Byte] = bytes(RollupInfix + id)

  private def name(key: Array[Byte]): String = new String(key, StandardCharsets.UTF_8)
}

object LevelDbStateSnapshotStore {
  private val MetaSuffix: Array[Byte] = "/meta".getBytes(StandardCharsets.UTF_8)
  private val RollupInfix: String = "/r/"
  private val WriteChunk: Int = 32

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
}

/** Everything in a generation except the rollups, which are stored one entry each. */
final case class SnapshotMeta(cursor: SyncCursor,
                              version: Long,
                              recentCursors: Vector[SyncCursor],
                              minerDictionaryFault: Option[String],
                               quarantined: Map[String, QuarantineFault],
                               routes: Map[String, String],
                               rollupOrigins: Map[String, String],
                               minerTree: MinerTree,
                              dataBoxToken: Option[ErgoId],
                              rollupIds: Seq[String])

/** Explicit binary schema for metadata plus native Plasma manifests. */
object StateSnapshotCodec {
  private val EnvelopeMagic = 0x4c535345
  private val MetaMagic = 0x4c53534d
  private val RollupMagic = 0x4c535352
  private val SchemaVersion = 6
  private val ToolkitVersion = "plasma-toolkit-1.1.0"
  private val MaxBlobBytes = 512 * 1024 * 1024
  private val MaxEntries = 1000000

  /**
   * Encodes a generation as one metadata entry plus one entry per rollup.
   * `maxEntryBytes` bounds a single entry, so a large rollup set no longer builds one huge value.
   */
  def encode(persisted: PersistedSyncState,
             identity: StateSnapshotIdentity,
             maxEntryBytes: Int): Either[String, SnapshotWrite] =
    attempt {
      val state = persisted.state
      val rollups = state.rollups.toSeq.sortBy(_._1)
      val encodedRollups = rollups.map { case (id, tree) =>
        val payload = writeBytes { out =>
          out.writeInt(RollupMagic)
          writeString(out, id)
          writeRollup(out, tree)
        }
        id -> sealEntry(payload, maxEntryBytes, s"rollup $id")
      }

      val meta = writeBytes { out =>
        out.writeInt(MetaMagic)
        out.writeInt(SchemaVersion)
        writeString(out, ToolkitVersion)
        writeIdentity(out, identity)
        writeCursor(out, state.cursor)
        out.writeLong(state.version)
        writeCount(out, persisted.recentCursors.size, "recent cursor count")
        persisted.recentCursors.foreach(writeCursor(out, _))
        out.writeBoolean(state.minerDictionaryFault.isDefined)
        state.minerDictionaryFault.foreach(writeString(out, _))

        val faults = state.quarantined.toSeq.sortBy(_._1)
        writeCount(out, faults.size, "quarantine count")
        faults.foreach { case (_, fault) =>
          writeString(out, fault.rollupId)
          out.writeInt(fault.genesisHeight)
          writeString(out, fault.collateralBoxId)
          writeString(out, fault.reason)
          out.writeBoolean(fault.retryable)
          out.writeInt(fault.attempts)
          out.writeBoolean(fault.removalHeight.isDefined)
          fault.removalHeight.foreach(out.writeInt)
          out.writeBoolean(fault.removalWarningLogged)
        }

        val routes = state.routes.toSeq.sortBy(_._1)
        writeCount(out, routes.size, "route count")
        routes.foreach { case (utxoId, rollupId) =>
          writeString(out, utxoId)
          writeString(out, rollupId)
        }

        val origins = state.rollupOrigins.toSeq.sortBy(_._1)
        writeCount(out, origins.size, "rollup origin count")
        origins.foreach { case (rollupId, collateralBoxId) =>
          writeString(out, rollupId)
          writeString(out, collateralBoxId)
        }

        writeMinerTree(out, state.minerTree)
        out.writeBoolean(state.dataBoxToken.isDefined)
        state.dataBoxToken.foreach(token => writeString(out, token.toString))
        writeStrings(out, rollups.map(_._1))
      }

      SnapshotWrite(sealEntry(meta, maxEntryBytes, "snapshot metadata"), encodedRollups)
    }

  def decodeMeta(encoded: Array[Byte],
                 expectedIdentity: StateSnapshotIdentity): Either[String, SnapshotMeta] =
    attempt {
      val in = openEntry(encoded)
      require(in.readInt() == MetaMagic, "invalid metadata magic")
      require(in.readInt() == SchemaVersion, "unsupported snapshot schema version")
      require(readString(in) == ToolkitVersion, "unsupported Plasma Toolkit version")
      require(readIdentity(in) == expectedIdentity,
        "snapshot network, wallet, start height, or protocol identity does not match this client")
      val cursor = readCursor(in)
      val version = in.readLong()
      val recentCursors = readCount(in, "recent cursor count")(_ => readCursor(in))
      val minerDictionaryFault = if (in.readBoolean()) Some(readString(in)) else None
      val quarantined = readCount(in, "quarantine count") { _ =>
        val rollupId = readString(in)
        val genesisHeight = in.readInt()
        val collateralBoxId = readString(in)
        val reason = readString(in)
        val retryable = in.readBoolean()
        val attempts = in.readInt()
        val removalHeight = if (in.readBoolean()) Some(in.readInt()) else None
        val warningLogged = in.readBoolean()
        rollupId -> QuarantineFault(rollupId, genesisHeight, collateralBoxId, reason,
          retryable, attempts, removalHeight, warningLogged)
      }.toMap
      val routes = readCount(in, "route count") { _ => readString(in) -> readString(in) }.toMap
      val origins = readCount(in, "rollup origin count") { _ =>
        readString(in) -> readString(in)
      }.toMap
      val minerTree = readMinerTree(in)
      val dataToken = if (in.readBoolean()) Some(ErgoId.create(readString(in))) else None
      val rollupIds = readStrings(in)
      require(in.available() == 0, "trailing bytes after snapshot metadata")
      SnapshotMeta(cursor, version, recentCursors, minerDictionaryFault, quarantined, routes, origins,
        minerTree, dataToken, rollupIds)
    }

  def decodeRollup(encoded: Array[Byte], expectedId: String): Either[String, NISPTree] =
    attempt {
      val in = openEntry(encoded)
      require(in.readInt() == RollupMagic, "invalid rollup magic")
      require(readString(in) == expectedId, s"rollup entry does not name $expectedId")
      val tree = readRollup(in)
      require(in.available() == 0, "trailing bytes after rollup entry")
      tree
    }

  /** Rebuilds committed state once every named rollup has been read back. */
  def assemble(meta: SnapshotMeta, rollups: Map[String, NISPTree]): Either[String, PersistedSyncState] =
    attempt {
      PersistedSyncState(
        CommittedSyncState(meta.cursor, meta.version, rollups, meta.routes, meta.rollupOrigins, meta.minerTree,
          meta.dataBoxToken, meta.minerDictionaryFault, meta.quarantined),
        meta.recentCursors)
    }

  private def sealEntry(payload: Array[Byte], maxEntryBytes: Int, what: String): Array[Byte] = {
    require(payload.length <= maxEntryBytes,
      s"$what is ${payload.length} bytes, over the configured $maxEntryBytes limit")
    val checksum = Blake2b256.hash(payload)
    writeBytes { out =>
      out.writeInt(EnvelopeMagic)
      writeByteArray(out, payload)
      writeByteArray(out, checksum)
    }
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

  private def writeRollup(out: DataOutputStream, tree: NISPTree): Unit = {
    writeDictionary(out, tree.dictionary)
    out.writeInt(tree.numMiners)
    writeBigInt(out, tree.totalScore)
    writeOptionalLong(out, tree.currentPeriod)
    out.writeLong(tree.totalReward)
    out.writeInt(tree.startHeight)
    out.writeBoolean(tree.hasMiner)
    out.writeByte(phaseByte(tree.phase))
    writeStrings(out, tree.minerSet.toSeq.sorted)
    out.writeBoolean(tree.evaluated)
    writeString(out, tree.blockId)
    writeString(out, tree.utxoId)
  }

  private def readRollup(in: DataInputStream): NISPTree = {
    val dictionary = readDictionary(in)
    val numMiners = in.readInt()
    val totalScore = readBigInt(in)
    val currentPeriod = readOptionalLong(in)
    val totalReward = in.readLong()
    val startHeight = in.readInt()
    val hasMiner = in.readBoolean()
    val phase = readPhase(in.readByte())
    val minerSet = readStrings(in).toSet
    val evaluated = in.readBoolean()
    NISPTree(dictionary, numMiners, totalScore, currentPeriod, totalReward, startHeight,
      hasMiner, phase, minerSet, evaluated, readString(in), readString(in))
  }

  private def writeMinerTree(out: DataOutputStream, tree: MinerTree): Unit = {
    writeDictionary(out, tree.dictionary)
    out.writeInt(tree.numMiners)
    out.writeInt(tree.startHeight)
    val minerMap = tree.minerMap.toSeq.sortBy(_._1)
    writeCount(out, minerMap.size, "miner map count")
    minerMap.foreach { case (hash, (address, token)) =>
      writeString(out, hash)
      writeString(out, address.toString)
      writeString(out, token.toString)
    }
    out.writeBoolean(tree.hasMiner)
    writeString(out, tree.utxoId)
    out.writeBoolean(tree.synced)
    out.writeInt(tree.syncHeight)
    out.writeInt(tree.savedHeight)
  }

  private def readMinerTree(in: DataInputStream): MinerTree = {
    val dictionary = readDictionary(in)
    val numMiners = in.readInt()
    val startHeight = in.readInt()
    val minerMap = readCount(in, "miner map count") { _ =>
      readString(in) -> (Address.create(readString(in)), ErgoId.create(readString(in)))
    }.toMap
    MinerTree(dictionary, numMiners, startHeight, minerMap, in.readBoolean(), readString(in),
      in.readBoolean(), in.readInt(), in.readInt())
  }

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

  private def writeDictionary(out: DataOutputStream, dictionary: AuthenticatedDictionaryView): Unit = {
    val manifest = dictionary.getManifest()
    out.writeByte(dictionary.flags.serializeToByte)
    out.writeInt(dictionary.parameters.keySize)
    out.writeBoolean(dictionary.parameters.valueSizeOpt.isDefined)
    dictionary.parameters.valueSizeOpt.foreach(out.writeInt)
    writeByteArray(out, manifest.digest)
    writeByteArray(out, manifest.bytes)
    writeCount(out, manifest.subTrees.size, "manifest subtree count")
    manifest.subTrees.foreach(writeByteArray(out, _))
  }

  private def readDictionary(in: DataInputStream) = {
    val flags = AvlTreeFlags(in.readByte())
    val keySize = in.readInt()
    val valueSize = if (in.readBoolean()) Some(in.readInt()) else None
    val digest = readByteArray(in)
    val bytes = readByteArray(in)
    val subTrees = readCount(in, "manifest subtree count")(_ => readByteArray(in))
    PlasmaDictionary.fromManifest(flags, PlasmaParameters(keySize, valueSize),
      Manifest(digest, bytes, subTrees)) match {
      case Right(dictionary) => dictionary
      case Left(error) => throw new IllegalArgumentException(
        s"Plasma manifest could not be loaded: ${error.getMessage}", error)
    }
  }

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

  private def writeBigInt(out: DataOutputStream, value: BigInt): Unit = writeByteArray(out, value.toByteArray)
  private def readBigInt(in: DataInputStream): BigInt = BigInt(readByteArray(in))

  private def writeStrings(out: DataOutputStream, values: Seq[String]): Unit = {
    writeCount(out, values.size, "string count")
    values.foreach(writeString(out, _))
  }

  private def readStrings(in: DataInputStream): Vector[String] =
    readCount(in, "string count")(_ => readString(in))

  private def writeString(out: DataOutputStream, value: String): Unit =
    writeByteArray(out, value.getBytes(StandardCharsets.UTF_8))

  private def readString(in: DataInputStream): String =
    new String(readByteArray(in), StandardCharsets.UTF_8)

  /** Writes refuse what reads would refuse, so a saved entry can always be loaded back. */
  private def writeByteArray(out: DataOutputStream, value: Array[Byte]): Unit = {
    require(value.length <= MaxBlobBytes,
      s"byte-array of ${value.length} exceeds the $MaxBlobBytes limit its decoder enforces")
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

  private def readCount[A](in: DataInputStream, label: String)(read: Int => A): Vector[A] = {
    val count = in.readInt()
    require(count >= 0 && count <= MaxEntries, s"invalid $label $count")
    Vector.tabulate(count)(read)
  }

  private def writeCount(out: DataOutputStream, size: Int, label: String): Unit = {
    require(size <= MaxEntries, s"$label of $size exceeds the $MaxEntries limit its decoder enforces")
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
      case NonFatal(ex) => Left(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
    }
}
