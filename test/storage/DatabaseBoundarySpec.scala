package storage

import contracts.specs.rollup.NispFixtures
import lfsm.MDDatabase
import nisp.NISPDatabase.{CURRENT_HEIGHT, LAST_HEIGHT}
import nisp.{NISPDatabase, NISPStorage}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Ints
import sigma.crypto.CryptoConstants
import storage.KeyValueMutation.{Delete, Put}

class DatabaseBoundarySpec extends AnyFlatSpec with Matchers {

  "NISPDatabase" should "persist a new NISP and both height markers in one batch" in {
    val store = new InMemoryKeyValueStore
    val database: NISPStorage = new NISPDatabase(store)
    val share = NispFixtures.superShare(100, CryptoConstants.dlogGroup.generator)

    database.addNISP(25L, share) shouldBe true

    store.recordedBatches should have size 1
    val batch = store.recordedBatches.head
    batch should have size 3
    mutationKeys(batch) should contain allOf (
      Ints.toByteArray(100).toVector,
      LAST_HEIGHT.toVector,
      CURRENT_HEIGHT.toVector
    )
    database.lastHeight.get.toVector shouldEqual Ints.toByteArray(100).toVector
    database.currentHeight.get.toVector shouldEqual Ints.toByteArray(100).toVector
    database.getNISP(100).get.score shouldEqual 25L
  }

  it should "remove a height range and advance its marker in one batch" in {
    val height10 = Ints.toByteArray(10)
    val height12 = Ints.toByteArray(12)
    val height15 = Ints.toByteArray(15)
    val store = new InMemoryKeyValueStore(Seq(
      height10 -> Array[Byte](1),
      height12 -> Array[Byte](2),
      height15 -> Array[Byte](3),
      LAST_HEIGHT -> height10,
      CURRENT_HEIGHT -> height15
    ))
    val database = new NISPDatabase(store)

    database.removeUntil(13) shouldBe true

    store.recordedBatches should have size 1
    store.recordedBatches.head.collect { case Delete(key) => key.toVector } should contain theSameElementsAs Seq(
      height10.toVector, height12.toVector
    )
    store.recordedBatches.head.collect { case Put(key, value) => key.toVector -> value.toVector } should contain only (
      LAST_HEIGHT.toVector -> height15.toVector
    )
    database.getNISPBytes(10) shouldBe None
    database.getNISPBytes(12) shouldBe None
    database.lastHeight.get.toVector shouldEqual height15.toVector
    database.currentHeight.get.toVector shouldEqual height15.toVector
  }

  it should "delete the final NISP and both markers in one batch" in {
    val height = Ints.toByteArray(20)
    val store = new InMemoryKeyValueStore(Seq(
      height -> Array[Byte](1),
      LAST_HEIGHT -> height,
      CURRENT_HEIGHT -> height
    ))
    val database = new NISPDatabase(store)

    database.removeLastNISP shouldBe true

    store.recordedBatches should have size 1
    store.recordedBatches.head.collect { case Delete(key) => key.toVector } should contain theSameElementsAs Seq(
      height.toVector, LAST_HEIGHT.toVector, CURRENT_HEIGHT.toVector
    )
    database.getNISPBytes(20) shouldBe None
    database.lastHeight shouldBe None
    database.currentHeight shouldBe None
  }

  "MDDatabase" should "use an injected store without changing its synchronous API" in {
    val store = new InMemoryKeyValueStore
    val database = new MDDatabase(store)
    val token = ErgoId.create("ab" * 32)

    database.setDataBoxToken(token) shouldBe true
    database.getDataBoxToken shouldEqual Some(token)
    database.size shouldEqual 1
    database.delDataBoxToken shouldBe true
    database.getDataBoxToken shouldBe None

    database.close()
    database.close()
    store.closeInvocations shouldEqual 1
  }

  it should "retain the public zero-argument database constructors" in {
    classOf[NISPDatabase].getConstructors.exists(_.getParameterCount == 0) shouldBe true
    classOf[MDDatabase].getConstructors.exists(_.getParameterCount == 0) shouldBe true
  }

  private def mutationKeys(mutations: Seq[KeyValueMutation]): Seq[Vector[Byte]] =
    mutations.map {
      case Put(key, _) => key.toVector
      case Delete(key) => key.toVector
    }
}
