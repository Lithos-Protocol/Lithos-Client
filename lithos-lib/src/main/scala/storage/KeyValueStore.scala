package storage

import java.nio.file.Path

final case class StorageBackend(name: String, implementation: String)

sealed trait WriteDurability
object WriteDurability {
  case object Buffered extends WriteDurability
  case object Synchronous extends WriteDurability
}

sealed trait KeyValueMutation
object KeyValueMutation {
  final case class Put(key: Array[Byte], value: Array[Byte]) extends KeyValueMutation
  final case class Delete(key: Array[Byte]) extends KeyValueMutation
}

/** A consistent view of the database for the duration of one read transaction. */
trait KeyValueReadView {
  def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]]

  /** Returns entries in the backend's unsigned bytewise key order. */
  def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]]

  /** Returns matching keys without loading their values. */
  def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]]

  /**
   * Returns up to `limit` keys with `prefix`, starting at the first key >= `startAt` (or strictly > `startAt` when
   * `exclusive` is true) in unsigned bytewise order. Empty `startAt` starts at `prefix`.
   */
  def scanKeysRange(prefix: Array[Byte], startAt: Array[Byte], exclusive: Boolean,
                    limit: Int): Either[StoreError, Vector[Array[Byte]]] =
    if (prefix == null) Left(StoreError.InvalidArgument("prefix cannot be null"))
    else if (startAt == null) Left(StoreError.InvalidArgument("startAt cannot be null"))
    else if (limit < 0) Left(StoreError.InvalidArgument("limit cannot be negative"))
    else if (limit == 0) Right(Vector.empty)
    else scanKeys(prefix).map(_.iterator.filter(k => startAt.isEmpty || {
      val cmp = KeyValueStore.compareUnsigned(k, startAt)
      if (exclusive) cmp > 0 else cmp >= 0
    }).take(limit).toVector)

  /**
   * Returns up to `limit` entries with `prefix`, starting at the first key >= `startAt` (or strictly > `startAt` when
   * `exclusive` is true) in unsigned bytewise order. Empty `startAt` starts at `prefix`.
   */
  def scanPrefixRange(prefix: Array[Byte], startAt: Array[Byte], exclusive: Boolean,
                      limit: Int): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
    if (prefix == null) Left(StoreError.InvalidArgument("prefix cannot be null"))
    else if (startAt == null) Left(StoreError.InvalidArgument("startAt cannot be null"))
    else if (limit < 0) Left(StoreError.InvalidArgument("limit cannot be negative"))
    else if (limit == 0) Right(Vector.empty)
    else scanPrefix(prefix).map(_.iterator.filter { case (k, _) => startAt.isEmpty || {
      val cmp = KeyValueStore.compareUnsigned(k, startAt)
      if (exclusive) cmp > 0 else cmp >= 0
    }}.take(limit).toVector)

  /**
   * Returns up to `limit` entries with `prefix`, in reverse unsigned bytewise order, starting at the last key <=
   * `startAt` (or strictly < `startAt` when `exclusive` is true). Empty `startAt` starts at the end of `prefix`.
   */
  def scanPrefixReverse(prefix: Array[Byte], startAt: Array[Byte], exclusive: Boolean,
                        limit: Int): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
    if (prefix == null) Left(StoreError.InvalidArgument("prefix cannot be null"))
    else if (startAt == null) Left(StoreError.InvalidArgument("startAt cannot be null"))
    else if (limit < 0) Left(StoreError.InvalidArgument("limit cannot be negative"))
    else if (limit == 0) Right(Vector.empty)
    else scanPrefix(prefix).map(_.reverseIterator.filter { case (k, _) => startAt.isEmpty || {
      val cmp = KeyValueStore.compareUnsigned(k, startAt)
      if (exclusive) cmp < 0 else cmp <= 0
    }}.take(limit).toVector)
}

/** Backend-neutral operations required by the client repositories. */
trait KeyValueStore extends KeyValueReadView {
  def path: Path
  def backend: StorageBackend

  def write(mutations: Seq[KeyValueMutation],
            durability: WriteDurability = WriteDurability.Buffered): Either[StoreError, Unit]

  def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A]

  def close(): Either[StoreError, Unit]

  final def put(key: Array[Byte], value: Array[Byte],
                durability: WriteDurability = WriteDurability.Buffered): Either[StoreError, Unit] =
    write(Seq(KeyValueMutation.Put(key, value)), durability)

  final def delete(key: Array[Byte],
                   durability: WriteDurability = WriteDurability.Buffered): Either[StoreError, Unit] =
    write(Seq(KeyValueMutation.Delete(key)), durability)
}

trait KeyValueStoreFactory {
  def backendName: String
  def open(path: Path): Either[StoreError, KeyValueStore]
}

object KeyValueStore {
  def compareUnsigned(left: Array[Byte], right: Array[Byte]): Int = {
    val shared = math.min(left.length, right.length)
    var i = 0
    while (i < shared) {
      val cmp = (left(i) & 0xff) - (right(i) & 0xff)
      if (cmp != 0) return cmp
      i += 1
    }
    left.length - right.length
  }

  def orThrow[A](result: Either[StoreError, A]): A = result match {
    case Right(value) => value
    case Left(error)  => throw new StoreException(error)
  }

  /** Used where no configuration is in scope, which is the two disk-backed protocol databases. */
  val DefaultBackend: String = LevelDbKeyValueStore.backendName

  private val backends: Map[String, KeyValueStoreFactory] =
    Seq(LevelDbKeyValueStore).map(factory => factory.backendName -> factory).toMap

  def factoryFor(backendName: String): Either[StoreError, KeyValueStoreFactory] =
    backends.get(backendName.toLowerCase) match {
      case Some(factory) => Right(factory)
      case None => Left(StoreError.InvalidArgument(
        s"unknown storage backend '$backendName'; this build ships ${backends.keys.toSeq.sorted.mkString(", ")}"))
    }

  def open(backendName: String, path: Path): Either[StoreError, KeyValueStore] =
    factoryFor(backendName).flatMap(_.open(path))

  def openOrThrow(backendName: String, path: Path): KeyValueStore =
    orThrow(open(backendName, path))
}

