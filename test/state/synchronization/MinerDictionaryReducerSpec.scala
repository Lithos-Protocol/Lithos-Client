package state.synchronization

import lfsm.states.MinerTree
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
    afterForeign.minerTree.minerMap.keySet should contain allOf(
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
    finalState.minerTree.minerMap.keySet shouldEqual Set(foreign.hashedPropBytesHex)
  }

}
