package storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import storage.KeyValueMutation.{Delete, Put}
import storage.StoreError.{Closed, InvalidArgument}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

trait KeyValueStoreContract { self: AnyFlatSpec with Matchers =>

  protected def openStore(path: Path): Either[StoreError, KeyValueStore]

  protected def expectedBackendName: String

  "A key-value store backend" should "own copies of all keys and values" in withStore { store =>
    val key = Array[Byte](1, 2)
    val value = Array[Byte](3, 4)
    store.put(key, value) shouldBe Right(())

    key(0) = 9
    value(0) = 9
    val first = KeyValueStore.orThrow(store.get(Array[Byte](1, 2))).get
    first.toVector shouldEqual Vector[Byte](3, 4)

    first(0) = 8
    KeyValueStore.orThrow(store.get(Array[Byte](1, 2))).get.toVector shouldEqual Vector[Byte](3, 4)
  }

  it should "return prefix entries in unsigned bytewise order" in withStore { store =>
    val entries = Vector(
      Array[Byte](1, -1) -> Array[Byte](4),
      Array[Byte](2, 0) -> Array[Byte](5),
      Array[Byte](1, -128) -> Array[Byte](3),
      Array[Byte](1, 0) -> Array[Byte](1),
      Array[Byte](1, 127) -> Array[Byte](2)
    )
    store.write(entries.map { case (key, value) => Put(key, value) }) shouldBe Right(())

    val result = KeyValueStore.orThrow(store.scanPrefix(Array[Byte](1)))
    result.map { case (key, _) => key.map(_ & 0xff).toVector } shouldEqual Vector(
      Vector(1, 0), Vector(1, 127), Vector(1, 128), Vector(1, 255)
    )

    result.head._1(0) = 7
    result.head._2(0) = 7
    val unchanged = KeyValueStore.orThrow(store.scanPrefix(Array[Byte](1))).head
    unchanged._1.toVector shouldEqual Vector[Byte](1, 0)
    unchanged._2.toVector shouldEqual Vector[Byte](1)
  }

  it should "keep a read snapshot stable across a committed write" in withStore { store =>
    val key = Array[Byte](3)
    store.put(key, Array[Byte](10)) shouldBe Right(())

    val observed = KeyValueStore.orThrow {
      store.readSnapshot { view =>
        for {
          before <- view.get(key)
          _ <- store.put(key, Array[Byte](11))
          after <- view.get(key)
        } yield (before.get.toVector, after.get.toVector)
      }
    }
    observed shouldEqual (Vector[Byte](10), Vector[Byte](10))
    KeyValueStore.orThrow(store.get(key)).get.toVector shouldEqual Vector[Byte](11)
  }

  it should "validate a complete batch before changing state" in withStore { store =>
    val validKey = Array[Byte](4)
    val result = store.write(Vector(
      Put(validKey, Array[Byte](1)),
      Put(null, Array[Byte](2))
    ))
    result shouldBe Left(InvalidArgument("Mutation key cannot be null"))
    KeyValueStore.orThrow(store.get(validKey)) shouldBe None

    store.write(Vector(Put(validKey, Array[Byte](3)), Delete(validKey), Put(validKey, Array[Byte](4)))) shouldBe Right(())
    KeyValueStore.orThrow(store.get(validKey)).get.toVector shouldEqual Vector[Byte](4)
  }

  it should "reject invalid input with typed errors" in withStore { store =>
    store.get(null) shouldBe Left(InvalidArgument("key cannot be null"))
    store.scanPrefix(null) shouldBe Left(InvalidArgument("prefix cannot be null"))
    store.write(null) shouldBe Left(InvalidArgument("Mutation sequence cannot be null"))
    store.write(Vector.empty, null) shouldBe Left(InvalidArgument("Write durability cannot be null"))
  }

  it should "close idempotently and reject later operations" in withStore { store =>
    store.backend.name shouldEqual expectedBackendName
    store.close() shouldBe Right(())
    store.close() shouldBe Right(())
    store.get(Array[Byte](1)) shouldBe Left(Closed(store.path))
    store.put(Array[Byte](1), Array[Byte](2)) shouldBe Left(Closed(store.path))
    store.readSnapshot(_ => Right(())) shouldBe Left(Closed(store.path))
  }

  private def withStore(test: KeyValueStore => Any): Unit = {
    val directory = Files.createTempDirectory("lithos-store-contract-")
    var store: Option[KeyValueStore] = None
    try {
      val opened = KeyValueStore.orThrow(openStore(directory))
      store = Some(opened)
      test(opened)
    } finally {
      store.foreach(_.close())
      deleteTree(directory)
    }
  }

  private def deleteTree(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
