package storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import storage.StoreError.InvalidArgument

import java.nio.file.Path

class LevelDbKeyValueStoreSpec extends AnyFlatSpec with Matchers with KeyValueStoreContract {

  override protected def openStore(path: Path): Either[StoreError, KeyValueStore] =
    LevelDbKeyValueStore.open(path)

  override protected val expectedBackendName: String = "leveldb"

  "The LevelDB store factory" should "reject a null path without throwing" in {
    LevelDbKeyValueStore.open(null) shouldBe Left(InvalidArgument("Database path cannot be null"))
  }
}
