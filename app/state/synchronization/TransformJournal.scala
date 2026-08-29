package state.synchronization

import lfsm.states.{AuthenticatedDictionary, AuthenticatedDictionaryView, MinerDictionaryMetadata, PlasmaDictionary, RollupMetadata}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.ErgoId
import state.messages.SyncMessages.SyncCursor

import scala.util.control.NonFatal

sealed trait DictionaryId {
  def value: String
}

object DictionaryId {
  final case class Rollup(blockId: String) extends DictionaryId {
    override val value: String = s"rollup:$blockId"
  }
  case object Miner extends DictionaryId {
    override val value: String = "miner-dictionary"
  }
}

/** An owned, replayable authenticated-map mutation. Every byte array is copied at construction. */
sealed trait DictionaryOperation {
  def transactionId: String
  def estimatedBytes: Long
  def applyTo(dictionary: AuthenticatedDictionary): Unit
}

object DictionaryOperation {
  final class Insert private (val transactionId: String,
                              private val ownedEntries: Vector[(Array[Byte], Array[Byte])])
    extends DictionaryOperation {
    def entries: Vector[(Array[Byte], Array[Byte])] =
      ownedEntries.map { case (key, value) => key.clone() -> value.clone() }
    override val estimatedBytes: Long =
      ownedEntries.foldLeft(0L) { case (size, (key, value)) => size + key.length + value.length }
    override def applyTo(dictionary: AuthenticatedDictionary): Unit =
      dictionary.insert(ownedEntries.map { case (key, value) => key.clone() -> value.clone() }: _*)
  }

  object Insert {
    def apply(transactionId: String, entries: Seq[(Array[Byte], Array[Byte])]): Insert =
      new Insert(transactionId, entries.toVector.map { case (key, value) => key.clone() -> value.clone() })
  }

  final class Delete private (val transactionId: String, private val ownedKeys: Vector[Array[Byte]])
    extends DictionaryOperation {
    def keys: Vector[Array[Byte]] = ownedKeys.map(_.clone())
    override val estimatedBytes: Long = ownedKeys.foldLeft(0L)(_ + _.length)
    override def applyTo(dictionary: AuthenticatedDictionary): Unit =
      dictionary.delete(ownedKeys.map(_.clone()): _*)
  }

  object Delete {
    def apply(transactionId: String, keys: Seq[Array[Byte]]): Delete =
      new Delete(transactionId, keys.toVector.map(_.clone()))
  }
}

final case class DictionaryTransform(dictionaryId: DictionaryId,
                                     beforeDigest: String,
                                     operations: Vector[DictionaryOperation],
                                     afterDigest: String) {
  require(operations.nonEmpty, "A dictionary transform must contain at least one operation")

  val estimatedBytes: Long = operations.foldLeft(0L)(_ + _.estimatedBytes)

  def replay(base: AuthenticatedDictionaryView): Either[String, AuthenticatedDictionaryView] =
    try {
      val actualBefore = Hex.toHexString(base.digest)
      if (actualBefore != beforeDigest)
        Left(s"${dictionaryId.value} journal expected digest $beforeDigest but materialized $actualBefore")
      else {
        val dictionary = base.copy()
        operations.foreach(_.applyTo(dictionary))
        val actualAfter = Hex.toHexString(dictionary.digest)
        if (actualAfter != afterDigest)
          Left(s"${dictionaryId.value} journal produced digest $actualAfter, expected $afterDigest")
        else Right(dictionary)
      }
    } catch {
      case NonFatal(ex) => Left(s"Could not replay ${dictionaryId.value}: " +
        Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
    }
}

/** Complete small state at a block boundary; no authenticated dictionary object is retained. */
final case class CommittedSyncMetadata(cursor: SyncCursor,
                                       version: Long,
                                       rollups: Map[String, RollupMetadata],
                                       routes: Map[String, String],
                                       rollupOrigins: Map[String, String],
                                       minerDictionary: MinerDictionaryMetadata,
                                       dataBoxToken: Option[ErgoId],
                                       minerDictionaryFault: Option[String],
                                       quarantined: Map[String, QuarantineFault])

object CommittedSyncMetadata {
  def from(state: CommittedSyncState): CommittedSyncMetadata =
    CommittedSyncMetadata(state.cursor, state.version, state.rollups.map { case (id, rollup) =>
      id -> rollup.metadata
    }, state.routes, state.rollupOrigins, state.minerTree.metadata, state.dataBoxToken,
      state.minerDictionaryFault, state.quarantined)

  def materialize(metadata: CommittedSyncMetadata,
                  rollupDictionaries: Map[String, AuthenticatedDictionaryView],
                  minerDictionary: AuthenticatedDictionaryView): Either[String, CommittedSyncState] =
    try {
      val rollups = metadata.rollups.map { case (id, rollupMetadata) =>
        val dictionary = rollupDictionaries.getOrElse(id,
          throw new IllegalStateException(s"No materialized dictionary for rollup $id"))
        id -> rollupMetadata.materialize(dictionary)
      }
      Right(CommittedSyncState(metadata.cursor, metadata.version, rollups, metadata.routes,
        metadata.rollupOrigins, metadata.minerDictionary.materialize(minerDictionary),
        metadata.dataBoxToken, metadata.minerDictionaryFault, metadata.quarantined))
    } catch {
      case NonFatal(ex) => Left(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
    }
}

/** One atomically committed block in the volatile journal. */
final case class TransformJournalEntry(cursor: SyncCursor,
                                       metadata: CommittedSyncMetadata,
                                       transforms: Vector[DictionaryTransform]) {
  require(cursor == metadata.cursor, "Journal cursor and metadata boundary must match")
  val estimatedBytes: Long = transforms.foldLeft(0L)(_ + _.estimatedBytes)
}

/** Volatile by policy: restart discards it and catches up from the newest checkpoint. */
final case class TransformJournal(baseCursor: SyncCursor,
                                  entries: Vector[TransformJournalEntry] = Vector.empty) {
  require(entries.headOption.forall(entry =>
    entry.cursor.height == baseCursor.height + 1 && entry.cursor.parentId == baseCursor.blockId),
    "Journal must begin immediately after its checkpoint")
  require(entries.zip(entries.drop(1)).forall { case (left, right) =>
    right.cursor.height == left.cursor.height + 1 && right.cursor.parentId == left.cursor.blockId
  }, "Journal entries must form one contiguous canonical chain")

  def append(entry: TransformJournalEntry): TransformJournal = {
    val previous = entries.lastOption.map(_.cursor).getOrElse(baseCursor)
    require(entry.cursor.height == previous.height + 1 && entry.cursor.parentId == previous.blockId,
      "Cannot append a non-contiguous journal entry")
    copy(entries = entries :+ entry)
  }

  def indexOf(blockId: String): Int = entries.indexWhere(_.cursor.blockId == blockId)
  def through(index: Int): TransformJournal = copy(entries = entries.take(index + 1))
  def suffixAfter(index: Int): Vector[TransformJournalEntry] = entries.drop(index + 1)
  def estimatedBytes: Long = entries.foldLeft(0L)(_ + _.estimatedBytes)

  def replaceTipMetadata(metadata: CommittedSyncMetadata): TransformJournal = {
    require(entries.nonEmpty && entries.last.cursor == metadata.cursor,
      "Only the journal tip metadata may be replaced")
    copy(entries = entries.updated(entries.size - 1, entries.last.copy(metadata = metadata)))
  }
}

object TransformJournal {
  /** Reconstructs one boundary from the checkpoint base without retaining intermediate trees. */
  def materialize(base: CommittedSyncState,
                  entries: Seq[TransformJournalEntry]): Either[String, CommittedSyncState] = {
    var rollupDictionaries: Map[String, AuthenticatedDictionaryView] =
      base.rollups.map { case (id, rollup) => id -> rollup.dictionary }
    var minerDictionary: AuthenticatedDictionaryView = base.minerTree.dictionary
    var failure = Option.empty[String]

    entries.iterator.takeWhile(_ => failure.isEmpty).foreach { entry =>
      entry.transforms.foreach { transform =>
        if (failure.isEmpty) transform.dictionaryId match {
          case DictionaryId.Rollup(id) =>
            val starting = rollupDictionaries.getOrElse(id, PlasmaDictionary.empty())
            transform.replay(starting) match {
              case Right(dictionary) => rollupDictionaries += id -> dictionary
              case Left(reason) => failure = Some(reason)
            }
          case DictionaryId.Miner => transform.replay(minerDictionary) match {
            case Right(dictionary) => minerDictionary = dictionary
            case Left(reason) => failure = Some(reason)
          }
        }
      }
      rollupDictionaries = rollupDictionaries.filter { case (id, _) => entry.metadata.rollups.contains(id) }
      entry.metadata.rollups.keysIterator.filterNot(rollupDictionaries.contains).foreach { id =>
        // A genesis with no same-block insertion is the authenticated empty dictionary.
        val empty = PlasmaDictionary.empty()
        if (Hex.toHexString(empty.digest) == entry.metadata.rollups(id).dictionaryDigest)
          rollupDictionaries += id -> empty
        else failure = Some(s"Journal introduced rollup $id without its dictionary transform")
      }
    }

    failure match {
      case Some(reason) => Left(reason)
      case None => entries.lastOption match {
        case Some(last) => CommittedSyncMetadata.materialize(last.metadata, rollupDictionaries,
          minerDictionary)
        case None => Right(base)
      }
    }
  }
}
