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

  it should "page bounded forward and reverse prefix ranges in unsigned bytewise order" in withStore { store =>
    val p = "fill/".getBytes("UTF-8")
    val entries = Vector(
      "block/0001" -> "b1",
      "fill/0001/a" -> "1a",
      "fill/0001/b" -> "1b",
      "fill/0002/a" -> "2a",
      "fill/0003/a" -> "3a",
      "share/0001" -> "s1"
    )
    store.write(entries.map { case (k, v) => Put(k.getBytes("UTF-8"), v.getBytes("UTF-8")) }) shouldBe Right(())

    def s(bs: Array[Byte]) = new String(bs, "UTF-8")
    def b(str: String) = str.getBytes("UTF-8")

    // Forward key range: inclusive, exclusive, and empty start
    KeyValueStore.orThrow(store.scanKeysRange(p, Array.emptyByteArray, exclusive = false, limit = 2)).map(s) shouldEqual
      Vector("fill/0001/a", "fill/0001/b")
    KeyValueStore.orThrow(store.scanKeysRange(p, b("fill/0001/b"), exclusive = false, limit = 2)).map(s) shouldEqual
      Vector("fill/0001/b", "fill/0002/a")
    KeyValueStore.orThrow(store.scanKeysRange(p, b("fill/0001/b"), exclusive = true, limit = 2)).map(s) shouldEqual
      Vector("fill/0002/a", "fill/0003/a")
    KeyValueStore.orThrow(store.scanKeysRange(p, b("fill/0003/a"), exclusive = true, limit = 5)).map(s) shouldBe empty
    KeyValueStore.orThrow(store.scanKeysRange(p, Array.emptyByteArray, exclusive = false, limit = 0)) shouldBe empty

    // Forward entry range
    KeyValueStore.orThrow(store.scanPrefixRange(p, b("fill/0001/b"), exclusive = true, limit = 1))
      .map { case (k, v) => s(k) -> s(v) } shouldEqual Vector("fill/0002/a" -> "2a")

    // Reverse entry range: from end, inclusive, exclusive, between keys, and prefix at very end of DB
    KeyValueStore.orThrow(store.scanPrefixReverse(p, Array.emptyByteArray, exclusive = false, limit = 2))
      .map { case (k, v) => s(k) -> s(v) } shouldEqual Vector("fill/0003/a" -> "3a", "fill/0002/a" -> "2a")
    KeyValueStore.orThrow(store.scanPrefixReverse(p, b("fill/0002/a"), exclusive = false, limit = 2))
      .map { case (k, v) => s(k) -> s(v) } shouldEqual Vector("fill/0002/a" -> "2a", "fill/0001/b" -> "1b")
    KeyValueStore.orThrow(store.scanPrefixReverse(p, b("fill/0002/a"), exclusive = true, limit = 5))
      .map { case (k, v) => s(k) -> s(v) } shouldEqual Vector("fill/0001/b" -> "1b", "fill/0001/a" -> "1a")
    KeyValueStore.orThrow(store.scanPrefixReverse(p, b("fill/0001/a"), exclusive = true, limit = 5)) shouldBe empty
    KeyValueStore.orThrow(store.scanPrefixReverse(b("share/"), Array.emptyByteArray, exclusive = false, limit = 5))
      .map { case (k, v) => s(k) -> s(v) } shouldEqual Vector("share/0001" -> "s1")
    KeyValueStore.orThrow(store.scanPrefixReverse(b("missing/"), Array.emptyByteArray, exclusive = false, limit = 5)) shouldBe empty
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
