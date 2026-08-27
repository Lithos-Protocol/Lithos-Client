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
  def orThrow[A](result: Either[StoreError, A]): A = result match {
    case Right(value) => value
    case Left(error)  => throw new StoreException(error)
  }
}

