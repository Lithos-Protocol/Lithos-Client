package state.synchronization

import lfsm.states.{MinerDictionary, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxInput, TxOutput}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import work.lithos.mutations.{Contract, Token}

class MinerDictionaryReducerSpec extends AnyFlatSpec with Matchers {

  "BlockReducer Miner Dictionary replay" should "retain local membership across a foreign add" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 2)
    val local = wallet.contract
    val foreign = Contract.fromAddress(wallet.addresses(1))
    val protocol = ReducerFixtures.protocol().copy(localMinerHash = local.hashedPropBytes)
    val base = ReducerFixtures.emptyState()

    val localAdd = ReducerFixtures.minerAdd(base.minerTree, local, protocol.minerDictionaryToken,
      SyncFixtures.id(10001), SyncFixtures.id(10002), 100)
    val afterLocal = BlockReducer.applyBlock(base,
      BlockInfo(SyncFixtures.id(100), 100, Seq(localAdd), base.cursor.blockId), protocol)
      .toOption.get.state

    afterLocal.minerTree.hasMiner shouldBe true
    afterLocal.dataBoxToken shouldEqual Some(localAdd.outputs(1).assets.head.id)

    val foreignAdd = ReducerFixtures.minerAdd(afterLocal.minerTree, foreign, protocol.minerDictionaryToken,
      SyncFixtures.id(10101), SyncFixtures.id(10102), 101)
    val afterForeign = BlockReducer.applyBlock(afterLocal,
      BlockInfo(SyncFixtures.id(101), 101, Seq(foreignAdd), afterLocal.cursor.blockId), protocol)
      .toOption.get.state

    afterForeign.minerTree.hasMiner shouldBe true
    afterForeign.dataBoxToken shouldEqual afterLocal.dataBoxToken
    afterForeign.minerTree.dictionary.foldKeys(Set.empty[String])((keys, key) =>
      keys + org.bouncycastle.util.encoders.Hex.toHexString(key)) should contain allOf(
      local.hashedPropBytesHex, foreign.hashedPropBytesHex)
  }

  it should "clear membership and the data token only when the local miner is removed" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 2)
    val local = wallet.contract
    val foreign = Contract.fromAddress(wallet.addresses(1))
    val protocol = ReducerFixtures.protocol().copy(localMinerHash = local.hashedPropBytes)
    val base = ReducerFixtures.emptyState()
    val localAdd = ReducerFixtures.minerAdd(base.minerTree, local, protocol.minerDictionaryToken,
      SyncFixtures.id(20001), SyncFixtures.id(20002), 100)
    val first = BlockReducer.applyBlock(base,
      BlockInfo(SyncFixtures.id(100), 100, Seq(localAdd), base.cursor.blockId), protocol).toOption.get.state
    val foreignAdd = ReducerFixtures.minerAdd(first.minerTree, foreign, protocol.minerDictionaryToken,
      SyncFixtures.id(20101), SyncFixtures.id(20102), 101)
    val second = BlockReducer.applyBlock(first,
      BlockInfo(SyncFixtures.id(101), 101, Seq(foreignAdd), first.cursor.blockId), protocol).toOption.get.state

    val remove = ReducerFixtures.minerRemove(second.minerTree, local, protocol.minerDictionaryToken,
      SyncFixtures.id(20201), 102)
    val finalState = BlockReducer.applyBlock(second,
      BlockInfo(SyncFixtures.id(102), 102, Seq(remove), second.cursor.blockId), protocol).toOption.get.state

    finalState.minerTree.hasMiner shouldBe false
    finalState.dataBoxToken shouldBe None
    finalState.minerTree.dictionary.foldKeys(Set.empty[String])((keys, key) =>
      keys + org.bouncycastle.util.encoders.Hex.toHexString(key)) shouldEqual Set(foreign.hashedPropBytesHex)
  }

  /**
   * Eviction retires an entry without spending the miner's data box, so the reducer cannot take the
   * identity from a signer the way removal used to. It reads the dictionary's own variable instead,
   * and the two paths reduce identically because the tree does not care which one drove the spend.
   */
  it should "drop the entry on an eviction, which spends no data box" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 2)
    val local = wallet.contract
    val foreign = Contract.fromAddress(wallet.addresses(1))
    val protocol = ReducerFixtures.protocol().copy(localMinerHash = local.hashedPropBytes)
    val base = ReducerFixtures.emptyState()
    val localAdd = ReducerFixtures.minerAdd(base.minerTree, local, protocol.minerDictionaryToken,
      SyncFixtures.id(20001), SyncFixtures.id(20002), 100)
    val first = BlockReducer.applyBlock(base,
      BlockInfo(SyncFixtures.id(100), 100, Seq(localAdd), base.cursor.blockId), protocol).toOption.get.state
    val foreignAdd = ReducerFixtures.minerAdd(first.minerTree, foreign, protocol.minerDictionaryToken,
      SyncFixtures.id(20101), SyncFixtures.id(20102), 101)
    val second = BlockReducer.applyBlock(first,
      BlockInfo(SyncFixtures.id(101), 101, Seq(foreignAdd), first.cursor.blockId), protocol).toOption.get.state

    val evict = ReducerFixtures.minerEvict(second.minerTree, foreign, protocol.minerDictionaryToken,
      SyncFixtures.id(20301), 102)
    evict.inputs.size shouldBe 1
    val finalState = BlockReducer.applyBlock(second,
      BlockInfo(SyncFixtures.id(102), 102, Seq(evict), second.cursor.blockId), protocol).toOption.get.state

    // The evicted miner is gone; the local one is untouched and keeps its data token.
    finalState.minerTree.dictionary.foldKeys(Set.empty[String])((keys, key) =>
      keys + org.bouncycastle.util.encoders.Hex.toHexString(key)) shouldEqual Set(local.hashedPropBytesHex)
    finalState.minerTree.hasMiner shouldBe true
    finalState.dataBoxToken shouldEqual second.dataBoxToken
    finalState.minerTree.numMiners shouldBe 1
  }

  it should "enumerate every authenticated user leaf without retaining a second miner collection" in {
    // A non-power-of-two population leaves leaves at different depths, so slicing must visit both
    // those still embedded in the manifest and the detached subtrees.
    val entries = SyncFixtures.plasmaEntries(2051, 32)
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(entries: _*)
    val expected = entries.iterator.map(entry => Hex.toHexString(entry._1)).toSet
    val observed = scala.collection.mutable.Set.empty[String]

    dictionary.foreachKey { key =>
      observed += Hex.toHexString(key)
      // The callback owns a defensive copy; damaging it must not alter the prover's authenticated key.
      key(0) = (key(0) ^ 0xff).toByte
    }

    observed.toSet shouldEqual expected
    dictionary.foldKeys(Set.empty[String])((keys, key) => keys + Hex.toHexString(key)) shouldEqual expected
  }

  it should "omit both AVL sentinels from empty and shallow dictionaries" in {
    val dictionary = PlasmaDictionary.empty()
    dictionary.foldKeys(Vector.empty[String])((keys, key) => keys :+ Hex.toHexString(key)) shouldBe empty

    val entry = SyncFixtures.plasmaEntries(1, 32).head
    dictionary.insert(entry)
    dictionary.foldKeys(Vector.empty[String])((keys, key) => keys :+ Hex.toHexString(key)) shouldEqual
      Vector(Hex.toHexString(entry._1))
  }

}
