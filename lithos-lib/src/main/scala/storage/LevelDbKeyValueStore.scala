package storage

import org.iq80.leveldb.{DB, ReadOptions, WriteBatch, WriteOptions}
import scorex.db.LDBFactory
import storage.KeyValueMutation.{Delete, Put}
import storage.StoreError.{Closed, CloseFailed, InvalidArgument, OpenFailed, ReadFailed, WriteFailed}
import storage.WriteDurability.Synchronous

import java.nio.file.{Files, Path}
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.util.control.NonFatal

final class LevelDbKeyValueStore private (private val db: DB,
                                          override val path: Path,
                                          override val backend: StorageBackend) extends KeyValueStore {

  private val lifecycleLock = new ReentrantReadWriteLock()
  @volatile private var closed = false
  private var closeResult: Option[Either[StoreError, Unit]] = None

  override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] =
    validateBytes(key, "key").flatMap { safeKey =>
      withOpenRead("get") {
        Option(db.get(safeKey)).map(_.clone())
      }
    }

  override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
    validateBytes(prefix, "prefix").flatMap { safePrefix =>
      readSnapshot(_.scanPrefix(safePrefix))
    }

  override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
    validateBytes(prefix, "prefix").flatMap { safePrefix =>
      readSnapshot(_.scanKeys(safePrefix))
    }

  override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A] = {
    if (f == null)
      return Left(InvalidArgument("Read transaction callback cannot be null"))

    val lock = lifecycleLock.readLock()
    lock.lock()
    try {
      if (closed) Left(Closed(path))
      else {
        var snapshot: org.iq80.leveldb.Snapshot = null
        var view: LevelDbReadView = null
        var result: Either[StoreError, A] = null
        var closeFailure: Option[Throwable] = None
        try {
          snapshot = db.getSnapshot()
          val options = new ReadOptions().snapshot(snapshot).verifyChecksums(true)
          view = new LevelDbReadView(db, options, path)
          result = f(view)
          if (result == null)
            result = Left(InvalidArgument("Read transaction callback returned null"))
        } catch {
          case NonFatal(error) => result = Left(ReadFailed(path, "snapshot", error))
        } finally {
          if (view != null) view.invalidate()
          if (snapshot != null) {
            try snapshot.close()
            catch { case NonFatal(error) => closeFailure = Some(error) }
          }
        }
        closeFailure match {
          case Some(error) if result.isRight => Left(ReadFailed(path, "snapshot close", error))
          case _                             => result
        }
      }
    } finally lock.unlock()
  }

  override def write(mutations: Seq[KeyValueMutation],
                     durability: WriteDurability): Either[StoreError, Unit] = {
    if (durability == null)
      return Left(InvalidArgument("Write durability cannot be null"))

    val copied = validateMutations(mutations)
    copied.flatMap { operations =>
      val lock = lifecycleLock.readLock()
      lock.lock()
      try {
        if (closed) Left(Closed(path))
        else if (operations.isEmpty) Right(())
        else {
          var batch: WriteBatch = null
          var result: Either[StoreError, Unit] = null
          var closeFailure: Option[Throwable] = None
          try {
            batch = db.createWriteBatch()
            operations.foreach {
              case Put(key, value) => batch.put(key, value)
              case Delete(key)     => batch.delete(key)
            }
            db.write(batch, new WriteOptions().sync(durability == Synchronous))
            result = Right(())
          } catch {
            case NonFatal(error) => result = Left(WriteFailed(path, error))
          } finally {
            if (batch != null) {
              try batch.close()
              catch { case NonFatal(error) => closeFailure = Some(error) }
            }
          }
          closeFailure match {
            case Some(error) if result.isRight => Left(WriteFailed(path, error))
            case _ => result
          }
        }
      } finally lock.unlock()
    }
  }

  override def close(): Either[StoreError, Unit] = {
    val lock = lifecycleLock.writeLock()
    lock.lock()
    try {
      closeResult.getOrElse {
        closed = true
        val result = try {
          db.close()
          Right(())
        } catch {
          case NonFatal(error) => Left(CloseFailed(path, error))
        }
        closeResult = Some(result)
        result
      }
    } finally lock.unlock()
  }

  private def withOpenRead[A](operation: String)(f: => A): Either[StoreError, A] = {
    val lock = lifecycleLock.readLock()
    lock.lock()
    try {
      if (closed) Left(Closed(path))
      else {
        try Right(f)
        catch { case NonFatal(error) => Left(ReadFailed(path, operation, error)) }
      }
    } finally lock.unlock()
  }

  private def validateMutations(mutations: Seq[KeyValueMutation]): Either[StoreError, Vector[KeyValueMutation]] = {
    if (mutations == null) Left(InvalidArgument("Mutation sequence cannot be null"))
    else {
      try {
        val builder = Vector.newBuilder[KeyValueMutation]
        val iterator = mutations.iterator
        while (iterator.hasNext) {
          iterator.next() match {
            case Put(key, value) if key != null && value != null =>
              builder += Put(key.clone(), value.clone())
            case Delete(key) if key != null =>
              builder += Delete(key.clone())
            case Put(null, _) | Delete(null) =>
              return Left(InvalidArgument("Mutation key cannot be null"))
            case Put(_, null) =>
              return Left(InvalidArgument("Mutation value cannot be null"))
            case null =>
              return Left(InvalidArgument("Mutation cannot be null"))
          }
        }
        Right(builder.result())
      } catch {
        case NonFatal(error) => Left(WriteFailed(path, error))
      }
    }
  }

  private def validateBytes(bytes: Array[Byte], label: String): Either[StoreError, Array[Byte]] =
    if (bytes == null) Left(InvalidArgument(s"$label cannot be null"))
    else Right(bytes.clone())
}

object LevelDbKeyValueStore extends KeyValueStoreFactory {
  override val backendName: String = "leveldb"

  override def open(path: Path): Either[StoreError, KeyValueStore] = {
    if (path == null) Left(InvalidArgument("Database path cannot be null"))
    else {
      var normalized = path
      try {
        normalized = path.toAbsolutePath.normalize()
        Files.createDirectories(normalized)
        val options = new org.iq80.leveldb.Options().createIfMissing(true)
        val factory = LDBFactory.factory
        val db = factory.open(normalized.toFile, options)
        Right(new LevelDbKeyValueStore(
          db,
          normalized,
          StorageBackend(backendName, s"${factory.getClass.getName}/${db.getClass.getName}")
        ))
      } catch {
        case NonFatal(error) => Left(OpenFailed(normalized, error))
      }
    }
  }

  def openOrThrow(path: Path): KeyValueStore = KeyValueStore.orThrow(open(path))
}

private final class LevelDbReadView(db: DB, options: ReadOptions, storePath: Path) extends KeyValueReadView {
  @volatile private var active = true

  override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = {
    if (!active) Left(Closed(storePath))
    else if (key == null) Left(InvalidArgument("key cannot be null"))
    else {
      try Right(Option(db.get(key.clone(), options)).map(_.clone()))
      catch { case NonFatal(error) => Left(ReadFailed(storePath, "snapshot get", error)) }
    }
  }

  override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
    scan(prefix, "snapshot prefix scan")(entry => entry.getKey.clone() -> entry.getValue.clone())

  override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
    scan(prefix, "snapshot key scan")(_.getKey.clone())

  private def scan[A](prefix: Array[Byte], operation: String)
                     (read: java.util.Map.Entry[Array[Byte], Array[Byte]] => A):
  Either[StoreError, Vector[A]] = {
    if (!active) Left(Closed(storePath))
    else if (prefix == null) Left(InvalidArgument("prefix cannot be null"))
    else {
      var iterator: org.iq80.leveldb.DBIterator = null
      var result: Either[StoreError, Vector[A]] = null
      var closeFailure: Option[Throwable] = None
      try {
        val safePrefix = prefix.clone()
        val entries = Vector.newBuilder[A]
        iterator = db.iterator(options)
        iterator.seek(safePrefix)
        var scanning = true
        while (scanning && iterator.hasNext) {
          val entry = iterator.next()
          if (startsWith(entry.getKey, safePrefix)) entries += read(entry)
          else scanning = false
        }
        result = Right(entries.result())
      } catch {
        case NonFatal(error) => result = Left(ReadFailed(storePath, operation, error))
      } finally {
        if (iterator != null) {
          try iterator.close()
          catch { case NonFatal(error) => closeFailure = Some(error) }
        }
      }
      closeFailure match {
        case Some(error) if result.isRight => Left(ReadFailed(storePath, "snapshot iterator close", error))
        case _ => result
      }
    }
  }

  def invalidate(): Unit = active = false

  private def startsWith(value: Array[Byte], prefix: Array[Byte]): Boolean = {
    if (value.length < prefix.length) false
    else {
      var index = 0
      while (index < prefix.length && value(index) == prefix(index)) index += 1
      index == prefix.length
    }
  }
}
