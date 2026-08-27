package storage

import storage.KeyValueMutation.{Delete, Put}
import storage.StoreError.{Closed, InvalidArgument, ReadFailed}

import java.nio.file.{Path, Paths}
import scala.util.control.NonFatal

final class InMemoryKeyValueStore(initialEntries: Seq[(Array[Byte], Array[Byte])] = Seq.empty)
  extends KeyValueStore {

  override val path: Path = Paths.get("in-memory-test-store").toAbsolutePath.normalize()
  override val backend: StorageBackend = StorageBackend("memory", getClass.getName)

  private var entries: Map[Vector[Byte], Array[Byte]] = initialEntries.map {
    case (key, value) => key.toVector -> value.clone()
  }.toMap
  private var batches = Vector.empty[Vector[KeyValueMutation]]
  private var closed = false
  private var closes = 0

  def recordedBatches: Vector[Vector[KeyValueMutation]] = synchronized {
    batches.map(_.map(copyMutation))
  }

  def clearRecordedBatches(): Unit = synchronized {
    batches = Vector.empty
  }

  def closeInvocations: Int = synchronized(closes)

  override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = synchronized {
    if (closed) Left(Closed(path))
    else if (key == null) Left(InvalidArgument("key cannot be null"))
    else Right(entries.get(key.toVector).map(_.clone()))
  }

  override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] = synchronized {
    if (closed) Left(Closed(path))
    else if (prefix == null) Left(InvalidArgument("prefix cannot be null"))
    else Right(scan(entries, prefix))
  }

  override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
    scanPrefix(prefix).map(_.map(_._1))

  override def write(mutations: Seq[KeyValueMutation],
                     durability: WriteDurability): Either[StoreError, Unit] = synchronized {
    if (closed) Left(Closed(path))
    else if (durability == null) Left(InvalidArgument("Write durability cannot be null"))
    else copyMutations(mutations).map { copied =>
      var next = entries
      copied.foreach {
        case Put(key, value) => next = next.updated(key.toVector, value.clone())
        case Delete(key) => next = next - key.toVector
      }
      entries = next
      batches :+= copied
    }
  }

  override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A] = {
    if (f == null) return Left(InvalidArgument("Read transaction callback cannot be null"))

    val snapshot = synchronized {
      if (closed) return Left(Closed(path))
      entries.map { case (key, value) => key -> value.clone() }
    }
    val view = new MemoryReadView(snapshot)
    try {
      Option(f(view)).getOrElse(Left(InvalidArgument("Read transaction callback returned null")))
    } catch {
      case NonFatal(error) => Left(ReadFailed(path, "snapshot", error))
    } finally view.invalidate()
  }

  override def close(): Either[StoreError, Unit] = synchronized {
    if (!closed) {
      closed = true
      closes += 1
    }
    Right(())
  }

  private def copyMutations(mutations: Seq[KeyValueMutation]): Either[StoreError, Vector[KeyValueMutation]] = {
    if (mutations == null) Left(InvalidArgument("Mutation sequence cannot be null"))
    else {
      val copied = Vector.newBuilder[KeyValueMutation]
      val iterator = mutations.iterator
      while (iterator.hasNext) {
        iterator.next() match {
          case Put(null, _) | Delete(null) => return Left(InvalidArgument("Mutation key cannot be null"))
          case Put(_, null) => return Left(InvalidArgument("Mutation value cannot be null"))
          case mutation @ Put(_, _) => copied += copyMutation(mutation)
          case mutation @ Delete(_) => copied += copyMutation(mutation)
          case null => return Left(InvalidArgument("Mutation cannot be null"))
        }
      }
      Right(copied.result())
    }
  }

  private def copyMutation(mutation: KeyValueMutation): KeyValueMutation = mutation match {
    case Put(key, value) => Put(key.clone(), value.clone())
    case Delete(key) => Delete(key.clone())
  }

  private def scan(source: Map[Vector[Byte], Array[Byte]],
                   prefix: Array[Byte]): Vector[(Array[Byte], Array[Byte])] = {
    val expected = prefix.toVector
    source.iterator
      .filter { case (key, _) => key.startsWith(expected) }
      .toVector
      .sortWith { case ((left, _), (right, _)) => compareUnsigned(left, right) < 0 }
      .map { case (key, value) => key.toArray -> value.clone() }
  }

  private def compareUnsigned(left: Vector[Byte], right: Vector[Byte]): Int = {
    val sharedLength = Math.min(left.length, right.length)
    var index = 0
    while (index < sharedLength) {
      val comparison = (left(index) & 0xff) - (right(index) & 0xff)
      if (comparison != 0) return comparison
      index += 1
    }
    left.length - right.length
  }

  private final class MemoryReadView(snapshot: Map[Vector[Byte], Array[Byte]]) extends KeyValueReadView {
    private var active = true

    override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = {
      if (!active) Left(Closed(path))
      else if (key == null) Left(InvalidArgument("key cannot be null"))
      else Right(snapshot.get(key.toVector).map(_.clone()))
    }

    override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] = {
      if (!active) Left(Closed(path))
      else if (prefix == null) Left(InvalidArgument("prefix cannot be null"))
      else Right(scan(snapshot, prefix))
    }

    override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
      scanPrefix(prefix).map(_.map(_._1))

    def invalidate(): Unit = active = false
  }
}
