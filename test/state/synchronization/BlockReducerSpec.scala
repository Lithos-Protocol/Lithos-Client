package state.synchronization

import lfsm.LFSMPhase
import lfsm.states.PlasmaDictionary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import scorex.utils.Longs
import state.messages.SyncMessages.SyncCursor
import state.messages.{BlockInfo, BlockTx}
import state.synchronization.SyncApplyError.{InvalidProof, NonContiguousBlock, ParentMismatch, StateInvariant}
import support.{ReducerFixtures, SyncFixtures}

class BlockReducerSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()

  "BlockReducer" should "commit an unrelated block with an exact cursor and one version increment" in {
    val base = ReducerFixtures.emptyState()
    val nextId = SyncFixtures.id(100)
    val block = BlockInfo(nextId, 100, Seq.empty, parentId = base.cursor.blockId)

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.state.cursor shouldEqual SyncCursor(100, nextId, base.cursor.blockId)
    transition.state.version shouldEqual base.version + 1L
    transition.relevantEvents shouldEqual 0
    transition.stagedDictionaryCopies shouldEqual 0
    transition.state.rollups shouldEqual base.rollups
    transition.state.minerTree.dictionary should be theSameInstanceAs base.minerTree.dictionary
  }

  it should "reject a height gap without changing the committed state" in {
    val base = ReducerFixtures.emptyState()
    val digestBefore = base.minerTree.dictionary.digest.clone()
    val block = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, parentId = base.cursor.blockId)

    BlockReducer.applyBlock(base, block, protocol) shouldEqual
      Left(NonContiguousBlock(expectedHeight = 100, actualHeight = 101))
    assertBaseUnchanged(base, height = 99, version = 7L, digestBefore)
  }

  it should "reject a parent mismatch without changing the committed state" in {
    val base = ReducerFixtures.emptyState()
    val digestBefore = base.minerTree.dictionary.digest.clone()
    val wrongParent = SyncFixtures.id(98)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, parentId = wrongParent)

    BlockReducer.applyBlock(base, block, protocol) shouldEqual
      Left(ParentMismatch(expectedParent = base.cursor.blockId, actualParent = wrongParent))
    assertBaseUnchanged(base, height = 99, version = 7L, digestBefore)
  }

  it should "ignore a holding-shaped counterfeit and accept a collateral-authenticated genesis" in {
    val base = ReducerFixtures.emptyState()
    val counterfeitInput = SyncFixtures.id(400001)
    val authenticInput = SyncFixtures.id(400002)
    val counterfeitOutput = SyncFixtures.id(400003)
    val authenticOutput = SyncFixtures.id(400004)
    val counterfeit = ReducerFixtures.genesisTx(1, counterfeitInput, counterfeitOutput, height = 100)
    val authentic = ReducerFixtures.genesisTx(2, authenticInput, authenticOutput, height = 100)
    val blockId = SyncFixtures.id(100)
    val block = BlockInfo(
      id = blockId,
      height = 100,
      txs = Seq(counterfeit, authentic),
      parentId = base.cursor.blockId,
      resolvedInputs = Map(
        counterfeitInput -> ReducerFixtures.resolvedInput(counterfeitInput, None, 100),
        authenticInput -> ReducerFixtures.resolvedInput(
          authenticInput, Some(ReducerFixtures.CollateralToken), 100)))

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.relevantEvents shouldEqual 1
    transition.state.rollups.keySet shouldEqual Set(blockId)
    transition.state.routes shouldEqual Map(authenticOutput -> blockId)
    transition.state.rollupOrigins shouldEqual Map(blockId -> authenticInput)
    transition.state.routes should not contain counterfeitOutput
  }

  it should "apply a same-block genesis and its ordered child transform" in {
    val base = ReducerFixtures.emptyState()
    val collateralInput = SyncFixtures.id(500001)
    val genesisOutput = SyncFixtures.id(500002)
    val childOutput = SyncFixtures.id(500003)
    val genesis = ReducerFixtures.genesisTx(10, collateralInput, genesisOutput, height = 100)
    val key = SyncFixtures.plasmaEntries(1, 16).head._1
    val value = Longs.toByteArray(12L) ++ Array.fill[Byte](8)(3)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(key -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val child = ReducerFixtures.submissionTx(
      number = 11,
      inputId = genesisOutput,
      outputId = childOutput,
      height = 100,
      key = key,
      value = value,
      proofHex = insertion.proof.ergoValue.toHex,
      outputDictionary = expected,
      numMiners = 1,
      totalScore = BigInt(12),
      period = 100L,
      nft = collateralInput)
    val blockId = SyncFixtures.id(100)
    val block = BlockInfo(
      id = blockId,
      height = 100,
      txs = Seq(genesis, child),
      parentId = base.cursor.blockId,
      resolvedInputs = Map(collateralInput -> ReducerFixtures.resolvedInput(
        collateralInput, Some(ReducerFixtures.CollateralToken), 100)))

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.relevantEvents shouldEqual 2
    transition.stagedDictionaryCopies shouldEqual 1
    transition.state.routes shouldEqual Map(childOutput -> blockId)
    transition.state.rollupOrigins shouldEqual Map(blockId -> collateralInput)
    transition.state.rollups(blockId).numMiners shouldEqual 1
    transition.state.rollups(blockId).dictionary.digest should contain theSameElementsInOrderAs expected.digest
  }

  it should "copy one dictionary for multiple ordered transforms of the same rollup" in {
    val baseDictionary = PlasmaDictionary.empty()
    val rollupId = SyncFixtures.id(600001)
    val baseUtxo = SyncFixtures.id(600002)
    val base = ReducerFixtures.stateWithRollup(
      height = 99,
      blockId = SyncFixtures.id(99),
      rollupId = rollupId,
      utxoId = baseUtxo,
      dictionary = baseDictionary)
    val expected = PlasmaDictionary.empty()
    val entries = SyncFixtures.plasmaEntries(2, 16)
    val firstValue = Longs.toByteArray(4L) ++ entries.head._2.drop(8)
    val secondValue = Longs.toByteArray(7L) ++ entries(1)._2.drop(8)
    val firstInsertion = expected.insert(entries.head._1 -> _root_.nisp.NispCommitment.fromPayload(firstValue).bytes)
    val middleUtxo = SyncFixtures.id(600003)
    val first = ReducerFixtures.submissionTx(20, baseUtxo, middleUtxo, 100,
      entries.head._1, firstValue, firstInsertion.proof.ergoValue.toHex, expected,
      numMiners = 1, totalScore = BigInt(4), period = 99L)
    val secondInsertion = expected.insert(entries(1)._1 -> _root_.nisp.NispCommitment.fromPayload(secondValue).bytes)
    val finalUtxo = SyncFixtures.id(600004)
    val firstBond = ReducerFixtures.bondFor(firstValue)
    val second = ReducerFixtures.submissionTx(21, middleUtxo, finalUtxo, 100,
      entries(1)._1, secondValue, secondInsertion.proof.ergoValue.toHex, expected,
      numMiners = 2, totalScore = BigInt(11), period = 99L,
      inputValue = ReducerFixtures.RollupValue + firstBond, inputBond = firstBond)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(first, second), base.cursor.blockId)

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.relevantEvents shouldEqual 2
    transition.stagedDictionaryCopies shouldEqual 1
    transition.state.routes shouldEqual Map(finalUtxo -> rollupId)
    transition.state.rollupOrigins shouldEqual base.rollupOrigins
    transition.state.rollups(rollupId).dictionary.digest should contain theSameElementsInOrderAs expected.digest
    baseDictionary.digest should contain theSameElementsInOrderAs PlasmaDictionary.empty().digest
  }

  // Digest equality is required; valid proof serializations need not be byte-identical.
  it should "commit a submission whose proof bytes differ but whose digest matches" in {
    val baseDictionary = PlasmaDictionary.empty()
    val rollupId = SyncFixtures.id(700001)
    val inputUtxo = SyncFixtures.id(700002)
    val outputUtxo = SyncFixtures.id(700003)
    val base = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, inputUtxo, baseDictionary)
    val key = SyncFixtures.plasmaEntries(1, 16).head._1
    val value = Longs.toByteArray(5L) ++ Array.fill[Byte](8)(2)
    val expected = PlasmaDictionary.empty()
    expected.insert(key -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val tx = ReducerFixtures.submissionTx(30, inputUtxo, outputUtxo, 100,
      key, value, ReducerFixtures.proofHex(Array[Byte](1, 2, 3)), expected,
      numMiners = 1, totalScore = BigInt(5), period = 99L)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(tx), base.cursor.blockId)

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.quarantined shouldBe empty
    transition.state.routes shouldEqual Map(outputUtxo -> rollupId)
    transition.state.rollups(rollupId).dictionary.digest should
      contain theSameElementsInOrderAs expected.digest
  }

  // A rollup-scoped fault must not stop the cursor or discard unrelated state.
  it should "quarantine a rollup whose output digest is wrong and still commit the block" in {
    val baseDictionary = PlasmaDictionary.empty()
    val rollupId = SyncFixtures.id(800001)
    val inputUtxo = SyncFixtures.id(800002)
    val base = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, inputUtxo, baseDictionary)
    val digestBefore = baseDictionary.digest.clone()
    val key = SyncFixtures.plasmaEntries(1, 16).head._1
    // A priced score, so this reaches the digest check rather than faulting on the bond first.
    val value = Longs.toByteArray(8L) ++ Array.fill[Byte](8)(1)
    val proofSource = PlasmaDictionary.empty()
    val insertion = proofSource.insert(key -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val wrongDigest = PlasmaDictionary.empty()
    val tx = ReducerFixtures.submissionTx(40, inputUtxo, SyncFixtures.id(800003), 100,
      key, value, insertion.proof.ergoValue.toHex, wrongDigest,
      numMiners = 1, totalScore = BigInt(8), period = 99L)
    val blockId = SyncFixtures.id(100)
    val block = BlockInfo(blockId, 100, Seq(tx), base.cursor.blockId)

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.state.cursor.blockId shouldEqual blockId
    transition.quarantined.map(_.rollupId) shouldEqual Seq(rollupId)
    transition.state.rollups.keySet should not contain rollupId
    transition.state.routes shouldBe empty
    transition.state.rollupOrigins shouldBe empty
    transition.state.quarantined(rollupId).collateralBoxId shouldEqual base.rollupOrigins(rollupId)
    assertRollupBaseUnchanged(base, rollupId, inputUtxo, digestBefore)
  }

  it should "keep the Miner Dictionary and other rollups when one rollup is quarantined" in {
    val healthyDictionary = PlasmaDictionary.empty()
    val brokenDictionary = PlasmaDictionary.empty()
    val healthyId = SyncFixtures.id(810001)
    val brokenId = SyncFixtures.id(810002)
    val healthyUtxo = SyncFixtures.id(810003)
    val brokenUtxo = SyncFixtures.id(810004)
    val healthyNext = SyncFixtures.id(810005)
    val withHealthy = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), healthyId, healthyUtxo,
      healthyDictionary)
    val base = ReducerFixtures.addRollup(withHealthy, brokenId, brokenUtxo, brokenDictionary)

    val entries = SyncFixtures.plasmaEntries(2, 16)
    val healthyValue = Longs.toByteArray(6L) ++ entries.head._2.drop(8)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(entries.head._1 -> _root_.nisp.NispCommitment.fromPayload(healthyValue).bytes)
    val good = ReducerFixtures.submissionTx(50, healthyUtxo, healthyNext, 100,
      entries.head._1, healthyValue, insertion.proof.ergoValue.toHex, expected,
      numMiners = 1, totalScore = BigInt(6), period = 99L)
    val bad = ReducerFixtures.submissionTx(51, brokenUtxo, SyncFixtures.id(810006), 100,
      entries(1)._1, entries(1)._2, ReducerFixtures.proofHex(Array[Byte](9)), PlasmaDictionary.empty(),
      numMiners = 1, totalScore = BigInt(0), period = 99L,
      nft = base.rollupOrigins(brokenId))
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(bad, good), base.cursor.blockId)

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.quarantined.map(_.rollupId) shouldEqual Seq(brokenId)
    transition.state.rollups.keySet shouldEqual Set(healthyId)
    transition.state.routes shouldEqual Map(healthyNext -> healthyId)
    transition.state.rollupOrigins shouldEqual Map(healthyId -> base.rollupOrigins(healthyId))
    transition.minerDictionaryFault shouldBe empty
    transition.state.minerTree.dictionary should be theSameInstanceAs base.minerTree.dictionary
  }


  /**
   * `Payout.ergo`'s drain path constrains almost nothing, and the reducer demands context variables it
   * does not supply. What keeps that unreachable is `numMiners == 0` implying `hasMiner == false`, so a
   * drained rollup is dropped at the Evaluation transition before it can ever reach Payout.
   *
   * The whole sequence runs here, because the earlier version of this test asserted the invariant over a
   * state with one miner in it and could not fail.
   *
   * TODO: Add payout drain path to client?
   */
  it should "drop a rollup whose last miner was slashed before it can reach the payout phase" in {
    val key = SyncFixtures.plasmaEntries(1, 16).head._1
    val protocolWithMiner = protocol.copy(localMinerHash = key)
    val rollupId = SyncFixtures.id(900001)
    val utxoId = SyncFixtures.id(900002)
    val base = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, utxoId,
      PlasmaDictionary.empty())

    def assertInvariant(state: CommittedSyncState, stage: String): Unit =
      withClue(s"after $stage, origin keys must match active rollups: ") {
        state.rollupOrigins.keySet shouldEqual state.rollups.keySet
      }
    def assertTreeInvariant(state: CommittedSyncState, stage: String): Unit =
      state.rollups.values.foreach { tree =>
        withClue(s"after $stage, rollup ${tree.blockId} claims the local miner with an empty tree: ") {
          (tree.numMiners == 0 && tree.hasMiner) shouldBe false
        }
      }

    // 1. The local miner submits. The rollup now claims it, and holds their bond.
    val value = Longs.toByteArray(9L) ++ Array.fill[Byte](8)(4)
    val bond = ReducerFixtures.bondFor(value)
    val held = ReducerFixtures.RollupValue + bond
    val tracked = PlasmaDictionary.empty()
    val insertion = tracked.insert(key -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val submitted = ReducerFixtures.submissionTx(60, utxoId, SyncFixtures.id(900003), 100,
      key, value, insertion.proof.ergoValue.toHex, tracked,
      numMiners = 1, totalScore = BigInt(9), period = 99L)
    val afterSubmission = BlockReducer.applyBlock(base,
      BlockInfo(SyncFixtures.id(100), 100, Seq(submitted), base.cursor.blockId), protocolWithMiner)
      .toOption.value.state
    afterSubmission.rollups(rollupId).hasMiner shouldBe true
    afterSubmission.rollups(rollupId).numMiners shouldEqual 1
    assertInvariant(afterSubmission, "the submission")
    assertTreeInvariant(afterSubmission, "the submission")

    // 2. Holding to Evaluation conserves the tree, so the rollup survives on its local claim.
    val evalUtxo = SyncFixtures.id(900004)
    val toEvaluation = ReducerFixtures.holdingToEvaluationTx(61, SyncFixtures.id(900003), evalUtxo,
      101, tracked, numMiners = 1, totalScore = BigInt(9), period = 99L + 360L, nispBlock = 99L,
      bond = bond, value = held)
    val afterEvaluation = BlockReducer.applyBlock(afterSubmission,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEvaluation), SyncFixtures.id(100)), protocolWithMiner)
      .toOption.value.state
    afterEvaluation.rollups(rollupId).phase shouldEqual LFSMPhase.EVAL
    assertInvariant(afterEvaluation, "the evaluation transform")
    assertTreeInvariant(afterEvaluation, "the evaluation transform")

    // 3. A fraud proof slashes the only miner, which is this client. The tree is empty again.
    val drained = tracked.copy()
    val lookup = drained.lookUp(key)
    val deletion = drained.delete(key)
    val payoutUtxo = SyncFixtures.id(900005)
    val slashed = ReducerFixtures.fraudProofTx(62, evalUtxo, SyncFixtures.id(900006), payoutUtxo, 102,
      key, lookup.proof.ergoValue.toHex, deletion.proof.ergoValue.toHex, drained,
      numMiners = 0, totalScore = BigInt(0), period = 99L + 360L, slashed = bond,
      nispBlock = 99L, inputValue = held, inputBond = bond)
    val afterSlash = BlockReducer.applyBlock(afterEvaluation,
      BlockInfo(SyncFixtures.id(102), 102, Seq(slashed), SyncFixtures.id(101)), protocolWithMiner)
      .toOption.value.state
    afterSlash.rollups(rollupId).numMiners shouldEqual 0
    afterSlash.rollups(rollupId).hasMiner shouldBe false
    assertInvariant(afterSlash, "the fraud proof")
    assertTreeInvariant(afterSlash, "the fraud proof")

    // 4. The Payout transition drops it, so no tracked rollup can ever reach the drain path.
    val toPayout = ReducerFixtures.evaluationToPayoutTx(63, payoutUtxo, SyncFixtures.id(900007),
      103, drained, numMiners = 0, totalScore = BigInt(0),
      totalReward = ReducerFixtures.RollupValue, value = ReducerFixtures.RollupValue)
    val afterPayout = BlockReducer.applyBlock(afterSlash,
      BlockInfo(SyncFixtures.id(103), 103, Seq(toPayout), SyncFixtures.id(102)), protocolWithMiner)
      .toOption.value.state

    afterPayout.rollups.keySet should not contain rollupId
    afterPayout.routes shouldBe empty
    afterPayout.rollupOrigins shouldBe empty
    assertInvariant(afterPayout, "the payout transform")
    assertTreeInvariant(afterPayout, "the payout transform")
  }

  private def assertBaseUnchanged(base: CommittedSyncState,
                                  height: Int,
                                  version: Long,
                                  digestBefore: Array[Byte]): Unit = {
    base.cursor.height shouldEqual height
    base.version shouldEqual version
    base.minerTree.dictionary.digest should contain theSameElementsInOrderAs digestBefore
  }

  private def assertRollupBaseUnchanged(base: CommittedSyncState,
                                        rollupId: String,
                                        inputUtxo: String,
                                        digestBefore: Array[Byte]): Unit = {
    base.cursor shouldEqual SyncCursor(99, SyncFixtures.id(99), SyncFixtures.id(98))
    base.version shouldEqual 7L
    base.routes shouldEqual Map(inputUtxo -> rollupId)
    base.rollups(rollupId).utxoId shouldEqual inputUtxo
    base.rollups(rollupId).dictionary.digest should contain theSameElementsInOrderAs digestBefore
  }
}
