package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{AuthenticatedDictionaryView, MinerTree, NISPTree, PlasmaDictionary}
import org.ergoplatform.appkit.Address
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.data.AvlTreeFlags
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncState, SyncProtocolContext}
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

trait StateSnapshotStore {
  def save(persisted: PersistedSyncState): Either[SnapshotError, Unit]

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
                                       minerDictionaryToken: String,
                                       minerDictionaryGenesisId: String,
                                       collateralToken: String)

object StateSnapshotIdentity {
  def apply(protocol: SyncProtocolContext): StateSnapshotIdentity =
    StateSnapshotIdentity(protocol.networkType.toString, protocol.rollupStartHeight,
      org.bouncycastle.util.encoders.Hex.toHexString(protocol.localMinerHash),
      protocol.holdingErgoTree, protocol.evaluationErgoTree, protocol.payoutErgoTree,
      protocol.minerDictionaryToken.toString, protocol.minerDictionaryGenesisId,
      protocol.collateralToken.toString)
}

/** Atomic, generation-based LevelDB snapshot repository. */
final class LevelDbStateSnapshotStore(store: KeyValueStore,
                                      manifestDepth: Int,
                                      retention: Int,
                                      identity: StateSnapshotIdentity) extends StateSnapshotStore {
  import SnapshotError._

  require(retention >= 2, "At least two snapshot generations must be retained")

  private val dataPrefix = bytes("sync/snapshot/data/")
  private val currentKey = bytes("sync/snapshot/current")

  override def save(persisted: PersistedSyncState): Either[SnapshotError, Unit] = {
    val cursor = persisted.state.cursor
    val suffix = f"${cursor.height}%010d-${persisted.state.version}%020d-${cursor.blockId}"
    val generationKey = dataPrefix ++ bytes(suffix)
    for {
      encoded <- StateSnapshotCodec.encode(persisted, manifestDepth, identity).left.map(Corrupt)
      _ <- store.write(Seq(Put(generationKey, encoded), Put(currentKey, generationKey)),
        WriteDurability.Synchronous).left.map(Storage)
      _ <- prune(generationKey)
    } yield ()
  }

  /** Lists generation keys without decoding snapshot payloads. */
  override def generationKeys(): Either[SnapshotError, Seq[Array[Byte]]] =
    store.readSnapshot { view =>
      for {
        current <- view.get(currentKey)
        keys <- view.scanKeys(dataPrefix)
      } yield current -> keys
    }.left.map(Storage).map { case (current, keys) =>
      val newestFirst = keys.sortWith((a, b) => unsignedCompare(a, b) > 0)
      val pointed = current.toSeq.filter(key => keys.exists(java.util.Arrays.equals(_, key)))
      (pointed ++ newestFirst).foldLeft(Vector.empty[Array[Byte]]) { (result, key) =>
        if (result.exists(java.util.Arrays.equals(_, key))) result else result :+ key
      }
    }

  override def load(key: Array[Byte]): Either[SnapshotError, PersistedSyncState] =
    store.get(key).left.map(Storage).flatMap {
      case Some(encoded) => StateSnapshotCodec.decode(encoded, identity).left.map(Corrupt)
      case None => Left(Corrupt(s"snapshot generation ${new String(key, StandardCharsets.UTF_8)} is absent"))
    }

  override def close(): Either[SnapshotError, Unit] = store.close().left.map(Storage)

  private def prune(current: Array[Byte]): Either[SnapshotError, Unit] =
    store.scanKeys(dataPrefix).left.map(Storage).flatMap { keys =>
      val keep = keys.sortWith((a, b) => unsignedCompare(a, b) > 0).take(retention)
      val obsolete = keys.filterNot { key =>
        java.util.Arrays.equals(key, current) || keep.exists(java.util.Arrays.equals(_, key))
      }
      store.write(obsolete.map(Delete), WriteDurability.Buffered).left.map(Storage)
    }

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

  private def bytes(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
}

/** Explicit binary schema for metadata plus native Plasma manifests. */
object StateSnapshotCodec {
  private val EnvelopeMagic = 0x4c535345
  private val PayloadMagic = 0x4c535350
  private val SchemaVersion = 3
  private val ToolkitVersion = "plasma-toolkit-1.1.0"
  private val MaxBlobBytes = 512 * 1024 * 1024
  private val MaxEntries = 1000000

  def encode(persisted: PersistedSyncState,
             manifestDepth: Int,
             identity: StateSnapshotIdentity): Either[String, Array[Byte]] =
    attempt {
      val state = persisted.state
      val payloadBytes = writeBytes { out =>
        out.writeInt(PayloadMagic)
        out.writeInt(SchemaVersion)
        writeString(out, ToolkitVersion)
        writeIdentity(out, identity)
        writeCursor(out, state.cursor)
        out.writeLong(state.version)
        out.writeInt(persisted.recentCursors.size)
        persisted.recentCursors.foreach(writeCursor(out, _))
        out.writeBoolean(state.minerDictionaryFault.isDefined)
        state.minerDictionaryFault.foreach(writeString(out, _))

        val rollups = state.rollups.toSeq.sortBy(_._1)
        out.writeInt(rollups.size)
        rollups.foreach { case (id, tree) =>
          writeString(out, id)
          writeDictionary(out, tree.dictionary, manifestDepth)
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

        val routes = state.routes.toSeq.sortBy(_._1)
        out.writeInt(routes.size)
        routes.foreach { case (utxoId, rollupId) =>
          writeString(out, utxoId)
          writeString(out, rollupId)
        }

        writeDictionary(out, state.minerTree.dictionary, manifestDepth)
        out.writeInt(state.minerTree.numMiners)
        out.writeInt(state.minerTree.startHeight)
        val minerMap = state.minerTree.minerMap.toSeq.sortBy(_._1)
        out.writeInt(minerMap.size)
        minerMap.foreach { case (hash, (address, token)) =>
          writeString(out, hash)
          writeString(out, address.toString)
          writeString(out, token.toString)
        }
        out.writeBoolean(state.minerTree.hasMiner)
        writeString(out, state.minerTree.utxoId)
        out.writeBoolean(state.minerTree.synced)
        out.writeInt(state.minerTree.syncHeight)
        out.writeInt(state.minerTree.savedHeight)

        out.writeBoolean(state.dataBoxToken.isDefined)
        state.dataBoxToken.foreach(token => writeString(out, token.toString))
      }

      val checksum = Blake2b256.hash(payloadBytes)
      writeBytes { out =>
        out.writeInt(EnvelopeMagic)
        writeByteArray(out, payloadBytes)
        writeByteArray(out, checksum)
      }
    }

  def decode(encoded: Array[Byte],
             expectedIdentity: StateSnapshotIdentity): Either[String, PersistedSyncState] =
    attempt {
      val envelope = new DataInputStream(new ByteArrayInputStream(encoded))
      require(envelope.readInt() == EnvelopeMagic, "invalid envelope magic")
      val payload = readByteArray(envelope)
      val checksum = readByteArray(envelope)
      require(envelope.available() == 0, "trailing bytes after snapshot envelope")
      require(java.util.Arrays.equals(Blake2b256.hash(payload), checksum), "payload checksum mismatch")

      val in = new DataInputStream(new ByteArrayInputStream(payload))
      require(in.readInt() == PayloadMagic, "invalid payload magic")
      require(in.readInt() == SchemaVersion, "unsupported snapshot schema version")
      require(readString(in) == ToolkitVersion, "unsupported Plasma Toolkit version")
      require(readIdentity(in) == expectedIdentity,
        "snapshot network, wallet, start height, or protocol identity does not match this client")
      val cursor = readCursor(in)
      val version = in.readLong()
      val recentCursors = readCount(in, "recent cursor count")(_ => readCursor(in))
      val minerDictionaryFault = if (in.readBoolean()) Some(readString(in)) else None

      val rollups = readCount(in, "rollup count") { _ =>
        val id = readString(in)
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
        val blockId = readString(in)
        val utxoId = readString(in)
        id -> NISPTree(dictionary, numMiners, totalScore, currentPeriod, totalReward, startHeight,
          hasMiner, phase, minerSet, evaluated, blockId, utxoId)
      }.toMap

      val routes = readCount(in, "route count") { _ => readString(in) -> readString(in) }.toMap
      val minerDictionary = readDictionary(in)
      val minerCount = in.readInt()
      val minerStart = in.readInt()
      val minerMap = readCount(in, "miner map count") { _ =>
        val hash = readString(in)
        hash -> (Address.create(readString(in)), ErgoId.create(readString(in)))
      }.toMap
      val hasMiner = in.readBoolean()
      val minerUtxo = readString(in)
      val synced = in.readBoolean()
      val syncHeight = in.readInt()
      val savedHeight = in.readInt()
      val dataToken = if (in.readBoolean()) Some(ErgoId.create(readString(in))) else None
      require(in.available() == 0, "trailing bytes after snapshot payload")

      val minerTree = MinerTree(minerDictionary, minerCount, minerStart, minerMap, hasMiner,
        minerUtxo, synced, syncHeight, savedHeight)
      PersistedSyncState(
        CommittedSyncState(cursor, version, rollups, routes, minerTree, dataToken, minerDictionaryFault),
        recentCursors)
    }

  private def writeIdentity(out: DataOutputStream, identity: StateSnapshotIdentity): Unit = {
    writeString(out, identity.networkType)
    out.writeInt(identity.rollupStartHeight)
    writeString(out, identity.localMinerHash)
    writeString(out, identity.holdingErgoTree)
    writeString(out, identity.evaluationErgoTree)
    writeString(out, identity.payoutErgoTree)
    writeString(out, identity.minerDictionaryToken)
    writeString(out, identity.minerDictionaryGenesisId)
    writeString(out, identity.collateralToken)
  }

  private def readIdentity(in: DataInputStream): StateSnapshotIdentity =
    StateSnapshotIdentity(readString(in), in.readInt(), readString(in), readString(in),
      readString(in), readString(in), readString(in), readString(in), readString(in))

  private def writeDictionary(out: DataOutputStream,
                              dictionary: AuthenticatedDictionaryView,
                              depth: Int): Unit = {
    val manifest = dictionary.getManifest(depth)
    out.writeByte(dictionary.flags.serializeToByte)
    out.writeInt(dictionary.parameters.keySize)
    out.writeBoolean(dictionary.parameters.valueSizeOpt.isDefined)
    dictionary.parameters.valueSizeOpt.foreach(out.writeInt)
    writeByteArray(out, manifest.digest)
    writeByteArray(out, manifest.bytes)
    out.writeInt(manifest.subTrees.size)
    manifest.subTrees.foreach(writeByteArray(out, _))
  }

  private def readDictionary(in: DataInputStream) = {
    val flags = AvlTreeFlags(in.readByte())
    val keySize = in.readInt()
    val valueSize = if (in.readBoolean()) Some(in.readInt()) else None
    val digest = readByteArray(in)
    val bytes = readByteArray(in)
    val subTrees = readCount(in, "manifest subtree count")(_ => readByteArray(in))
    val manifest = Manifest(digest, bytes, subTrees)
    PlasmaDictionary.fromManifest(flags, PlasmaParameters(keySize, valueSize), manifest) match {
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
    out.writeInt(values.size)
    values.foreach(writeString(out, _))
  }

  private def readStrings(in: DataInputStream): Vector[String] =
    readCount(in, "string count")(_ => readString(in))

  private def writeString(out: DataOutputStream, value: String): Unit =
    writeByteArray(out, value.getBytes(StandardCharsets.UTF_8))

  private def readString(in: DataInputStream): String =
    new String(readByteArray(in), StandardCharsets.UTF_8)

  private def writeByteArray(out: DataOutputStream, value: Array[Byte]): Unit = {
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
