package state.synchronization

import lfsm.states.MinerDictionary
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, never, times, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.messages.{BlockTx, TxOutput}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import work.lithos.mutations.Contract

import java.util.concurrent.atomic.AtomicInteger
import scala.util.{Success, Try}

/** Covers seed-height selection, data-box recovery, and live-tip verification. */
class MinerDictionaryBootstrapSpec extends AnyFlatSpec with Matchers with MockitoSugar
  with OptionValues with EitherValues {

  private val genesisId = SyncFixtures.id(70000)
  private val protocol = ReducerFixtures.protocol().copy(minerDictionaryGenesisId = genesisId)

  "MinerDictionaryBootstrap" should "rebuild the dictionary the block path would have produced" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)

    val rebuilt = new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)

    val tree = rebuilt.value.minerTree
    tree.numMiners shouldEqual 3
    tree.dictionary.digest should contain theSameElementsInOrderAs chain.last.tree.dictionary.digest
    tree.utxoId shouldEqual chain.last.outputId
  }

  // Later transforms remain for canonical block synchronization to replay once.
  it should "keep the state as of the seed height, not the tip" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)

    val rebuilt = new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(201)

    val tree = rebuilt.value.minerTree
    tree.numMiners shouldEqual 2
    tree.dictionary.digest should contain theSameElementsInOrderAs chain(1).tree.dictionary.digest
  }

  it should "record the local miner and their data box while walking" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val local = wallet.contract
    val localProtocol = protocol.copy(localMinerHash = local.hashedPropBytes)
    val chain = registrationChain(count = 1, firstHeight = 200, miners = Seq(local))
    val nodeApi = indexerFor(chain)

    val tree = new MinerDictionaryBootstrap(nodeApi, localProtocol, maxTransforms = 100)
      .run(500).value.minerTree

    tree.hasMiner shouldBe true
    tree.dictionary.foldKeys(Set.empty[String])((keys, key) =>
      keys + org.bouncycastle.util.encoders.Hex.toHexString(key)) should contain(local.hashedPropBytesHex)
  }

  /** Recovers the local data-box token with the seed that will publish it. */
  it should "carry this miner's data box out with the seed" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val local = wallet.contract
    val localProtocol = protocol.copy(localMinerHash = local.hashedPropBytes)
    val chain = registrationChain(count = 1, firstHeight = 200, miners = Seq(local))
    val nodeApi = indexerFor(chain)

    val seed = new MinerDictionaryBootstrap(nodeApi, localProtocol, maxTransforms = 100).run(500).value

    seed.dataBoxToken.value shouldEqual chain.head.tx.outputs(1).assets.head.id
  }

  it should "leave the data box behind when the registration is above the seed height" in {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val local = wallet.contract
    val localProtocol = protocol.copy(localMinerHash = local.hashedPropBytes)
    val chain = registrationChain(count = 1, firstHeight = 200, miners = Seq(local))
    val nodeApi = indexerFor(chain)

    val seed = new MinerDictionaryBootstrap(nodeApi, localProtocol, maxTransforms = 100).run(199).value

    seed.dataBoxToken shouldBe empty
    seed.minerTree.hasMiner shouldBe false
  }

  // A reconstructed tip must match the live dictionary digest before publication.
  it should "refuse a walk whose digest does not match the live dictionary box" in {
    val chain = registrationChain(count = 2, firstHeight = 200)
    val nodeApi = indexerFor(chain, liveDigestOverride = Some(MinerDictionary.initialState.dictionary))

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)
      .left.value should include("does not match the live dictionary box")
  }

  /**
   * A registration confirming mid-walk must be continued from, not restarted.
   *
   * Restarting makes a walk cost grow with the whole chain every time the chain moves, so past the
   * point where one walk takes longer than the gap between registrations it never completes at all.
   * Both behaviours end at the same tree, so the genesis read count is what separates them.
   */
  it should "resume from the box it reached when the dictionary advances mid-walk" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)
    val midpoint = chain(1).outputId
    val reads = new AtomicInteger(0)

    // The first read ends the walk; by the second, the registration that raced it has landed.
    doAnswer { _: InvocationOnMock =>
      val box =
        if (reads.incrementAndGet() == 1) unspentBox(midpoint, chain(1).tx.id, chain(1).height)
        else spentBox(midpoint, chain(2).tx.id, chain(2).height)
      Success(Some(box)): Try[Option[IndexedBox]]
    }.when(nodeApi).indexedBoxById(midpoint)

    val rebuilt = new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)

    rebuilt.value.minerTree.numMiners shouldEqual 3
    rebuilt.value.minerTree.utxoId shouldEqual chain.last.outputId
    verify(nodeApi, times(1)).indexedBoxById(genesisId)
  }

  /**
   * The seed is frozen by the transform that crosses `seedHeight`, not by a segment boundary.
   *
   * A resume whose first segment ended below the seed height must still pick the seed up from the
   * later transform, or the rebuilt dictionary is short by everything the resume replayed.
   */
  it should "freeze the seed at the seed height across a resumed walk" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)
    val midpoint = chain(1).outputId
    val reads = new AtomicInteger(0)

    doAnswer { _: InvocationOnMock =>
      val box =
        if (reads.incrementAndGet() == 1) unspentBox(midpoint, chain(1).tx.id, chain(1).height)
        else spentBox(midpoint, chain(2).tx.id, chain(2).height)
      Success(Some(box)): Try[Option[IndexedBox]]
    }.when(nodeApi).indexedBoxById(midpoint)

    // Seeded at 201: the third registration is above it and belongs to block synchronization, and
    // the first segment ends before ever reaching that height.
    val seed = new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(201).value

    seed.minerTree.numMiners shouldEqual 2
    seed.minerTree.dictionary.digest should
      contain theSameElementsInOrderAs chain(1).tree.dictionary.digest
  }

  /**
   * A tip that moved is resumed from rather than restarted, but not without limit: a live box the
   * walk can never reach still has to end in a reported failure instead of an unbounded walk.
   */
  it should "give up after bounding how many times it resumes a moving tip" in {
    val chain = registrationChain(count = 2, firstHeight = 200)
    val nodeApi = indexerFor(chain, liveBoxIdOverride = Some(SyncFixtures.id(79999)))

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)
      .left.value should include(s"advanced ${MinerDictionaryBootstrap.MaxResumes} times")
  }

  it should "give up rather than walk an unbounded history" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 1).run(500)
      .left.value should include("maxTransforms")
  }

  /** The bound is the number of transforms permitted, not the number after which one more runs. */
  it should "walk exactly the configured maximum and refuse one more" in {
    val chain = registrationChain(count = 3, firstHeight = 200)
    val nodeApi = indexerFor(chain)

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 3).run(500).isRight shouldBe true
    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 2).run(500)
      .left.value should include("maxTransforms")
  }

  /**
   * The live box is asked for directly.
   */
  it should "ask the index for the unspent dictionary box rather than its whole history" in {
    val chain = registrationChain(count = 2, firstHeight = 200)
    val nodeApi = indexerFor(chain)

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500).isRight shouldBe true

    verify(nodeApi).unspentBoxesByTokenId(any[String], any[Paging], any[SortDirection],
      any[MempoolOptions])
    verify(nodeApi, never()).boxesByTokenId(any[String], any[Paging])
  }

  it should "report a gap in the indexer rather than publish a short dictionary" in {
    val chain = registrationChain(count = 2, firstHeight = 200)
    val nodeApi = indexerFor(chain)
    when(nodeApi.indexedTransactionById(chain(1).tx.id)).thenReturn(Success(None))

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)
      .left.value should include("has no transaction")
  }

  it should "use transaction inclusion instead of the box's redundant spending height" in {
    val chain = registrationChain(count = 2, firstHeight = 200)
    val nodeApi = indexerFor(chain)
    when(nodeApi.indexedBoxById(chain.head.inputId)).thenReturn(Success(Some(
      spentBox(chain.head.inputId, chain.head.tx.id, chain.head.height)
        .copy(spendingHeight = None))))

    new MinerDictionaryBootstrap(nodeApi, protocol, maxTransforms = 100).run(500)
      .value.minerTree.numMiners shouldEqual 2
  }

  private final case class Step(tx: BlockTx, tree: MinerDictionary, inputId: String, outputId: String, height: Int)

  /** One registration per step, each spending the previous dictionary box. */
  private def registrationChain(count: Int,
                                firstHeight: Int,
                                miners: Seq[Contract] = Seq.empty): Vector[Step] = {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 8)
    val contracts =
      if (miners.nonEmpty) miners
      else (0 until count).map(i => Contract.fromAddress(wallet.addresses(i)))

    val start = MinerDictionary.initialState.copy(utxoId = genesisId)
    (0 until count).foldLeft((start, Vector.empty[Step])) {
      case ((tree, steps), index) =>
        val height = firstHeight + index
        val outputId = SyncFixtures.id(71000 + index)
        val tx = ReducerFixtures.minerAdd(tree, contracts(index), protocol.minerDictionaryToken,
          outputId, SyncFixtures.id(72000 + index), height)
        val next = BlockReducer
          .applyMinerDictionaryTransform(seedState(tree), height, tx, protocol).toOption.get.minerTree
        (next, steps :+ Step(tx, next, tree.utxoId, outputId, height))
    }._2
  }

  private def seedState(tree: MinerDictionary): CommittedSyncState =
    CommittedSyncState(state.messages.SyncMessages.SyncCursor(0, "", ""), 0L,
      Map.empty, Map.empty, Map.empty, tree, None)

  /** A node whose box index answers the spend links the walk follows. */
  private def indexerFor(chain: Vector[Step],
                         liveDigestOverride: Option[lfsm.states.AuthenticatedDictionaryView] = None,
                         liveBoxIdOverride: Option[String] = None): NodeApi = {
    val nodeApi = mock[NodeApi]

    chain.foreach { step =>
      when(nodeApi.indexedBoxById(step.inputId))
        .thenReturn(Success(Some(spentBox(step.inputId, step.tx.id, step.height))))
      when(nodeApi.indexedTransactionById(step.tx.id))
        .thenReturn(Success(Some(indexedTransaction(step.tx, step.height))))
    }
    when(nodeApi.indexedBoxById(chain.last.outputId))
      .thenReturn(Success(Some(unspentBox(chain.last.outputId, chain.last.tx.id, chain.last.height))))

    val liveDictionary = liveDigestOverride.getOrElse(chain.last.tree.dictionary)
    val liveId = liveBoxIdOverride.getOrElse(chain.last.outputId)
    val liveOutput = ReducerFixtures.dictionaryOutput(liveId, chain.last.tx.id, chain.last.height,
      liveDictionary, protocol.minerDictionaryToken)
    // The bootstrap asks for the unspent box directly rather than filtering the token's history.
    when(nodeApi.unspentBoxesByTokenId(any[String], any[Paging], any[SortDirection],
      any[MempoolOptions]))
      .thenReturn(Success(Seq(indexedBox(liveOutput, chain.last.height, None, None))))
    nodeApi
  }

  private def spentBox(boxId: String, spentBy: String, height: Int): IndexedBox =
    IndexedBox(NodeBox(boxId, "", 1000000L, 0, height, "miner-dictionary"), "", height, 0L,
      Some(spentBy), Some(height), None)

  private def unspentBox(boxId: String, txId: String, height: Int): IndexedBox =
    IndexedBox(NodeBox(boxId, txId, 1000000L, 0, height, "miner-dictionary"), "", height, 0L, None, None, None)

  private def indexedTransaction(tx: BlockTx, height: Int): IndexedTransaction =
    IndexedTransaction(
      id = tx.id,
      inputs = tx.inputs.zipWithIndex.map { case (in, index) =>
        IndexedBox(NodeBox(in.id, "", 1000000L, index, height, "miner-dictionary"), "", height, 0L,
          Some(tx.id), Some(height),
          in.spendingProof.map(p => NodeSpendingProof("", p.ext)))
      },
      dataInputs = Seq.empty,
      outputs = tx.outputs.map(indexedBox(_, height, None, None)),
      inclusionHeight = height, numConfirmations = 10, blockId = "", timestamp = 0L,
      index = 0, globalIndex = 0L, size = 0)

  private def indexedBox(out: TxOutput,
                         height: Int,
                         spentBy: Option[String],
                         spendingHeight: Option[Int]): IndexedBox =
    IndexedBox(
      NodeBox(out.id, out.txId, out.value, out.index, out.creationHeight, out.ergoTree,
        out.assets.map(t => NodeAsset(t.id.toString, t.amount)),
        NodeRegisters(out.registers.zipWithIndex.map { case (hex, i) => s"R${i + 4}" -> hex }.toMap)),
      "", height, 0L, spentBy, spendingHeight, None)
}
