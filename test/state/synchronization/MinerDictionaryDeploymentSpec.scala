package state.synchronization

import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.{BlockInfo, BlockTx, TxInput, TxOutput}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import work.lithos.mutations.Token

/**
 * Replaying across the dictionary's own deployment.
 *
 * The token is minted into an ordinary P2PK box first and only moved into the genesis box by a second
 * transaction. On token shape alone that mint reads as a dictionary transform whose input is not the
 * tracked singleton, so every cold sync from below the deployment faulted the dictionary and lost
 * registration and commitment proofs until a repair rebuilt it.
 */
class MinerDictionaryDeploymentSpec extends AnyFlatSpec with Matchers {

  private val startHeight = 500

  private val protocol = ReducerFixtures.protocol(minerDictionaryStartHeight = startHeight)

  private def base = ReducerFixtures.emptyState(
    height = startHeight - 2, blockId = SyncFixtures.id(startHeight - 2))

  private def genesisId: String = base.minerTree.utxoId

  /** The mint: the singleton lands in a P2PK box the deployment will spend, below the start height. */
  private def mint(height: Int): BlockTx = {
    val txId = SyncFixtures.id(40000 + height)
    BlockTx(txId, Seq(TxInput(SyncFixtures.id(41000 + height), None)), Seq.empty,
      Seq(TxOutput(SyncFixtures.id(42000 + height), 1000000L, "p2pk", Seq.empty,
        Seq(Token(protocol.minerDictionaryToken, 1L)), txId, height, 0)))
  }

  /** The deployment: that box becomes the configured genesis box, at the start height. */
  private def deploy(height: Int): BlockTx = {
    val txId = SyncFixtures.id(43000 + height)
    BlockTx(txId, Seq(TxInput(SyncFixtures.id(42000 + (height - 1)), None)), Seq.empty,
      Seq(TxOutput(genesisId, 1000000L, "miner-dictionary", Seq.empty,
        Seq(Token(protocol.minerDictionaryToken, 1L)), txId, height, 0)))
  }

  private def replay(from: CommittedSyncState, height: Int, tx: BlockTx) =
    BlockReducer.applyBlock(from,
      BlockInfo(SyncFixtures.id(height), height, Seq(tx), from.cursor.blockId), protocol)

  // ─── below the start height nothing is a transform ────────────────────────

  "The token mint that funds the deployment" should "not fault the dictionary" in {
    val after = replay(base, startHeight - 1, mint(startHeight - 1))
    after.isRight shouldBe true

    val state = after.toOption.get.state
    withClue("its input is not the tracked singleton, so classifying it as a transform faults: ") {
      state.minerDictionaryFault shouldBe empty
    }
    state.minerTree.utxoId shouldEqual genesisId
  }

  "The deployment transaction itself" should "anchor the genesis box rather than reduce as a change" in {
    val first = replay(base, startHeight - 1, mint(startHeight - 1)).toOption.get.state
    val after = replay(first, startHeight, deploy(startHeight))
    after.isRight shouldBe true

    val state = after.toOption.get.state
    state.minerDictionaryFault shouldBe empty
    state.minerTree.numMiners shouldEqual 0
    state.minerTree.utxoId shouldEqual genesisId
  }

  // ─── above it, the first real spend applies exactly once ──────────────────

  "The first spend of the genesis box" should "be applied once, after the deployment replays clean" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val local = wallet.contract

    val afterMint = replay(base, startHeight - 1, mint(startHeight - 1)).toOption.get.state
    val afterDeploy = replay(afterMint, startHeight, deploy(startHeight)).toOption.get.state

    val add = ReducerFixtures.minerAdd(afterDeploy.minerTree, local, protocol.minerDictionaryToken,
      SyncFixtures.id(44001), SyncFixtures.id(44002), startHeight + 1)
    val state = replay(afterDeploy, startHeight + 1, add).toOption.get.state

    state.minerDictionaryFault shouldBe empty
    state.minerTree.numMiners shouldEqual 1
    state.minerTree.dictionary.foldKeys(Set.empty[String])((keys, key) =>
      keys + org.bouncycastle.util.encoders.Hex.toHexString(key)) shouldEqual
      Set(local.hashedPropBytesHex)
  }

  /**
   * The two predicates have to agree. Materialization decides which dictionaries to load before the
   * reducer runs, so one recognizing the mint and the other not would prepare a block against a
   * dictionary that is never touched.
   */
  "The materialization pre-scan" should "agree with the reducer below the start height" in {
    val block = BlockInfo(SyncFixtures.id(startHeight - 1), startHeight - 1,
      Seq(mint(startHeight - 1)), base.cursor.blockId)
    SyncHandler.dictionariesTouchedBy(base, block, protocol) shouldBe empty
  }

  it should "agree with the reducer above it" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val add = ReducerFixtures.minerAdd(base.minerTree, wallet.contract,
      protocol.minerDictionaryToken, SyncFixtures.id(45001), SyncFixtures.id(45002), startHeight + 1)
    val block = BlockInfo(SyncFixtures.id(startHeight + 1), startHeight + 1, Seq(add),
      base.cursor.blockId)
    SyncHandler.dictionariesTouchedBy(base, block, protocol) should not be empty
  }
}
