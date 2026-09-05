package state.synchronization

import lfsm.LFSMPhase
import lfsm.states.{PlasmaDictionary, RollupInfoState}
import org.ergoplatform.appkit.ErgoValue
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Longs
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxInput}
import support.{ReducerFixtures, SyncFixtures}
import work.lithos.mutations.Token
import org.ergoplatform.sdk.ErgoId

/**
 * The rollup transitions the reducer has to follow on chain: the bond ledger, the box value, the
 * NFT and the top-up path.
 *
 * A rollup this client cannot follow is one it cannot submit to, so every case here that should
 * fault must fault for its own stated reason rather than by falling into a neighbouring check.
 */
class RollupTransitionSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()
  private val rollupId = SyncFixtures.id(120001)
  private val baseUtxo = SyncFixtures.id(120002)

  private def base(dictionary: PlasmaDictionary = PlasmaDictionary.empty()) =
    ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, baseUtxo, dictionary)

  private def blockOf(txs: BlockTx*) =
    BlockInfo(SyncFixtures.id(100), 100, txs, SyncFixtures.id(99))

  private def nisp(score: Long): Array[Byte] = Longs.toByteArray(score) ++ Array.fill[Byte](8)(3)

  private val minerKey = SyncFixtures.plasmaEntries(1, 16).head._1

  // ─── the top-up path ──────────────────────────────────────────────────────

  "A top-up" should "be followed, and its value accumulated" in {
    val state = base()
    val tx = ReducerFixtures.topUpTx(1, baseUtxo, SyncFixtures.id(120003), 100,
      PlasmaDictionary.empty(), numMiners = 0, totalScore = BigInt(0), period = 99L,
      added = 900000000L)

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined shouldBe empty
    transition.relevantEvents shouldEqual 1
    val followed = transition.state.rollups(rollupId)
    followed.value shouldEqual ReducerFixtures.RollupValue + 900000000L
    followed.utxoId shouldEqual SyncFixtures.id(120003)
    followed.numMiners shouldEqual 0
    followed.state shouldEqual RollupInfoState.holding(99L, 99L, 0L)
  }

  /**
   * More than one may appear in the rollup's own block, each spending the previous successor. The
   * total is what the evaluation transform later declares as the distributable reward, so a run
   * that stops accumulating part way disagrees with the chain from there on.
   */
  it should "be followed in a chain within one block" in {
    val state = base()
    val first = ReducerFixtures.topUpTx(2, baseUtxo, SyncFixtures.id(120004), 100,
      PlasmaDictionary.empty(), 0, BigInt(0), 99L, added = 100L)
    val second = ReducerFixtures.topUpTx(3, SyncFixtures.id(120004), SyncFixtures.id(120005), 100,
      PlasmaDictionary.empty(), 0, BigInt(0), 99L, added = 200L,
      inputValue = ReducerFixtures.RollupValue + 100L)

    val transition = BlockReducer.applyBlock(state, blockOf(first, second), protocol).toOption.value

    transition.quarantined shouldBe empty
    transition.state.rollups(rollupId).value shouldEqual ReducerFixtures.RollupValue + 300L
    transition.state.routes shouldEqual Map(SyncFixtures.id(120005) -> rollupId)
  }

  it should "not be recognised when the value does not rise" in {
    val state = base()
    val tx = ReducerFixtures.topUpTx(4, baseUtxo, SyncFixtures.id(120006), 100,
      PlasmaDictionary.empty(), 0, BigInt(0), 99L, added = 0L)

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    // Falls through to submission handling, which has no key/value pair to work from.
    transition.quarantined.map(_.rollupId) shouldEqual Seq(rollupId)
  }

  it should "not be recognised when it moves anything besides the value" in {
    val state = base()
    val tx = ReducerFixtures.topUpTx(5, baseUtxo, SyncFixtures.id(120007), 100,
      PlasmaDictionary.empty(), numMiners = 1, totalScore = BigInt(0), period = 99L, added = 500L)

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined.map(_.rollupId) shouldEqual Seq(rollupId)
  }

  // ─── submission bonds ─────────────────────────────────────────────────────

  "A submission" should "raise the box and the ledger by the same priced bond" in {
    val state = base()
    val value = nisp(500000L)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val tx = ReducerFixtures.submissionTx(6, baseUtxo, SyncFixtures.id(120008), 100,
      minerKey, value, insertion.proof.ergoValue.toHex, expected,
      numMiners = 1, totalScore = BigInt(500000), period = 99L)

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined shouldBe empty
    val followed = transition.state.rollups(rollupId)
    val bond = ReducerFixtures.bondFor(value)
    followed.value shouldEqual ReducerFixtures.RollupValue + bond
    followed.totalBond shouldEqual bond
    followed.totalReward shouldEqual ReducerFixtures.RollupValue
  }

  it should "fault when the ledger and the box disagree" in {
    val state = base()
    val value = nisp(500000L)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val bond = ReducerFixtures.bondFor(value)
    val txId = SyncFixtures.id(130001)
    val output = ReducerFixtures.holdingOutput(SyncFixtures.id(120009), txId, 100, expected,
      numMiners = 1, totalScore = BigInt(500000), period = 99L, bond = bond,
      value = ReducerFixtures.RollupValue + bond + 1L)
    val pair = ErgoValue.pairOf(
      ReducerFixtures.collBytes(minerKey), ReducerFixtures.collBytes(value)).toHex
    val tx = BlockTx(txId,
      Seq(TxInput(baseUtxo, Some(InputSpendingProof(
        Map("1" -> pair, "2" -> insertion.proof.ergoValue.toHex))))),
      Seq.empty, Seq(output))

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined.map(_.rollupId) shouldEqual Seq(rollupId)
    transition.quarantined.head.reason should include("should have raised the box")
  }

  it should "fault when the bond does not match the submitted score" in {
    val state = base()
    val value = nisp(500000L)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val txId = SyncFixtures.id(130002)
    val output = ReducerFixtures.holdingOutput(SyncFixtures.id(120010), txId, 100, expected,
      numMiners = 1, totalScore = BigInt(500000), period = 99L, bond = 1L,
      value = ReducerFixtures.RollupValue + 1L)
    val pair = ErgoValue.pairOf(
      ReducerFixtures.collBytes(minerKey), ReducerFixtures.collBytes(value)).toHex
    val tx = BlockTx(txId,
      Seq(TxInput(baseUtxo, Some(InputSpendingProof(
        Map("1" -> pair, "2" -> insertion.proof.ergoValue.toHex))))),
      Seq.empty, Seq(output))

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined.head.reason should include("bond ledger of")
  }

  // ─── the register itself ──────────────────────────────────────────────────

  "A rollup output" should "fault deterministically when R7 is the previous layout" in {
    val state = base()
    val value = nisp(500000L)
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val txId = SyncFixtures.id(130003)
    val output = ReducerFixtures
      .holdingOutput(SyncFixtures.id(120011), txId, 100, expected, 1, BigInt(500000), 99L)
    val stale = output.copy(registers = output.registers.updated(3, ErgoValue.of(99L).toHex))
    val pair = ErgoValue.pairOf(
      ReducerFixtures.collBytes(minerKey), ReducerFixtures.collBytes(value)).toHex
    val tx = BlockTx(txId,
      Seq(TxInput(baseUtxo, Some(InputSpendingProof(
        Map("1" -> pair, "2" -> insertion.proof.ergoValue.toHex))))),
      Seq.empty, Seq(stale))

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined.head.reason should include("three-element Coll[Long]")
  }

  it should "fault when the successor drops the rollup NFT" in {
    val state = base()
    val tx = ReducerFixtures.topUpTx(7, baseUtxo, SyncFixtures.id(120012), 100,
      PlasmaDictionary.empty(), 0, BigInt(0), 99L, added = 500L,
      nft = SyncFixtures.id(999999))

    val transition = BlockReducer.applyBlock(state, blockOf(tx), protocol).toOption.value

    transition.quarantined.head.reason should include("rollup NFT")
  }

  // ─── the evaluation transform ─────────────────────────────────────────────

  "The evaluation transform" should "keep the bonds out of the declared reward" in {
    val dictionary = PlasmaDictionary.empty()
    val value = nisp(500000L)
    val insertion = dictionary.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val bond = ReducerFixtures.bondFor(value)
    val submitted = ReducerFixtures.submissionTx(8, baseUtxo, SyncFixtures.id(120013), 100,
      minerKey, value, insertion.proof.ergoValue.toHex, dictionary, 1, BigInt(500000), 99L)
    val afterSubmission = BlockReducer
      .applyBlock(base(), blockOf(submitted), protocol).toOption.value.state

    val held = ReducerFixtures.RollupValue + bond
    val toEval = ReducerFixtures.holdingToEvaluationTx(9, SyncFixtures.id(120013),
      SyncFixtures.id(120014), 101, dictionary, 1, BigInt(500000), period = 459L,
      nispBlock = 99L, bond = bond, value = held)
    val afterEval = BlockReducer.applyBlock(afterSubmission,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEval), SyncFixtures.id(100)), protocol)
      .toOption.value.state
    afterEval.rollups shouldBe empty // no local miner, so it stops being tracked

    // Re-run with the local miner, so the payout transform is reachable.
    val mine = protocol.copy(localMinerHash = minerKey)
    val minedSubmission = BlockReducer
      .applyBlock(base(), blockOf(submitted), mine).toOption.value.state
    val minedEval = BlockReducer.applyBlock(minedSubmission,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEval), SyncFixtures.id(100)), mine)
      .toOption.value.state
    minedEval.rollups(rollupId).phase shouldEqual LFSMPhase.EVAL

    val toPayout = ReducerFixtures.evaluationToPayoutTx(10, SyncFixtures.id(120014),
      SyncFixtures.id(120015), 102, dictionary, 1, BigInt(500000),
      totalReward = ReducerFixtures.RollupValue, bond = bond, value = held)
    val afterPayout = BlockReducer.applyBlock(minedEval,
      BlockInfo(SyncFixtures.id(102), 102, Seq(toPayout), SyncFixtures.id(101)), mine)
      .toOption.value

    afterPayout.quarantined shouldBe empty
    val paying = afterPayout.state.rollups(rollupId)
    paying.phase shouldEqual LFSMPhase.PAYOUT
    paying.state.totalErgReward shouldEqual ReducerFixtures.RollupValue
    paying.state.totalBond shouldEqual bond
    paying.value shouldEqual held
  }

  it should "fault when the declared reward includes the bonds" in {
    val dictionary = PlasmaDictionary.empty()
    val value = nisp(500000L)
    val insertion = dictionary.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val bond = ReducerFixtures.bondFor(value)
    val mine = protocol.copy(localMinerHash = minerKey)
    val submitted = ReducerFixtures.submissionTx(11, baseUtxo, SyncFixtures.id(120016), 100,
      minerKey, value, insertion.proof.ergoValue.toHex, dictionary, 1, BigInt(500000), 99L)
    val afterSubmission = BlockReducer
      .applyBlock(base(), blockOf(submitted), mine).toOption.value.state

    val held = ReducerFixtures.RollupValue + bond
    val toEval = ReducerFixtures.holdingToEvaluationTx(12, SyncFixtures.id(120016),
      SyncFixtures.id(120017), 101, dictionary, 1, BigInt(500000), 459L, 99L, bond, held)
    val afterEval = BlockReducer.applyBlock(afterSubmission,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEval), SyncFixtures.id(100)), mine)
      .toOption.value.state

    val toPayout = ReducerFixtures.evaluationToPayoutTx(13, SyncFixtures.id(120017),
      SyncFixtures.id(120018), 102, dictionary, 1, BigInt(500000),
      totalReward = held, bond = bond, value = held)
    val transition = BlockReducer.applyBlock(afterEval,
      BlockInfo(SyncFixtures.id(102), 102, Seq(toPayout), SyncFixtures.id(101)), mine)
      .toOption.value

    transition.quarantined.head.reason should include("declared a reward")
  }

  // ─── fraud proofs ─────────────────────────────────────────────────────────

  "A fraud proof" should "take the entry's own bond out of the box, the ledger and into the prover" in {
    val dictionary = PlasmaDictionary.empty()
    val value = nisp(500000L)
    val insertion = dictionary.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val bond = ReducerFixtures.bondFor(value)
    val mine = protocol.copy(localMinerHash = SyncFixtures.plasmaEntries(2, 16)(1)._1)
    val submitted = ReducerFixtures.submissionTx(14, baseUtxo, SyncFixtures.id(120019), 100,
      minerKey, value, insertion.proof.ergoValue.toHex, dictionary, 1, BigInt(500000), 99L)
    val afterSubmission = BlockReducer
      .applyBlock(base(), blockOf(submitted), mine).toOption.value.state
    // Tracked only because the local miner is claimed; forced so the chain stays followed.
    val tracked = afterSubmission.copy(rollups = afterSubmission.rollups
      .updated(rollupId, afterSubmission.rollups(rollupId).copy(hasMiner = true)))

    val held = ReducerFixtures.RollupValue + bond
    val toEval = ReducerFixtures.holdingToEvaluationTx(15, SyncFixtures.id(120019),
      SyncFixtures.id(120020), 101, dictionary, 1, BigInt(500000), 459L, 99L, bond, held)
    val afterEval = BlockReducer.applyBlock(tracked,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEval), SyncFixtures.id(100)), mine)
      .toOption.value.state

    val drained = dictionary.copy()
    val lookup = drained.lookUp(minerKey)
    val deletion = drained.delete(minerKey)
    val slash = ReducerFixtures.fraudProofTx(16, SyncFixtures.id(120020), SyncFixtures.id(120021),
      SyncFixtures.id(120022), 102, minerKey, lookup.proof.ergoValue.toHex,
      deletion.proof.ergoValue.toHex, drained, numMiners = 0, totalScore = BigInt(0),
      period = 459L, slashed = bond, nispBlock = 99L, inputValue = held, inputBond = bond)

    val transition = BlockReducer.applyBlock(afterEval,
      BlockInfo(SyncFixtures.id(102), 102, Seq(slash), SyncFixtures.id(101)), mine)
      .toOption.value

    transition.quarantined shouldBe empty
    val followed = transition.state.rollups(rollupId)
    followed.value shouldEqual ReducerFixtures.RollupValue
    followed.totalBond shouldEqual 0L
  }

  it should "fault when the prover is paid something other than the slashed bond" in {
    val dictionary = PlasmaDictionary.empty()
    val value = nisp(500000L)
    val insertion = dictionary.insert(minerKey -> _root_.nisp.NispCommitment.fromPayload(value).bytes)
    val bond = ReducerFixtures.bondFor(value)
    val mine = protocol.copy(localMinerHash = SyncFixtures.plasmaEntries(2, 16)(1)._1)
    val submitted = ReducerFixtures.submissionTx(17, baseUtxo, SyncFixtures.id(120023), 100,
      minerKey, value, insertion.proof.ergoValue.toHex, dictionary, 1, BigInt(500000), 99L)
    val afterSubmission = BlockReducer
      .applyBlock(base(), blockOf(submitted), mine).toOption.value.state
    val tracked = afterSubmission.copy(rollups = afterSubmission.rollups
      .updated(rollupId, afterSubmission.rollups(rollupId).copy(hasMiner = true)))

    val held = ReducerFixtures.RollupValue + bond
    val toEval = ReducerFixtures.holdingToEvaluationTx(18, SyncFixtures.id(120023),
      SyncFixtures.id(120024), 101, dictionary, 1, BigInt(500000), 459L, 99L, bond, held)
    val afterEval = BlockReducer.applyBlock(tracked,
      BlockInfo(SyncFixtures.id(101), 101, Seq(toEval), SyncFixtures.id(100)), mine)
      .toOption.value.state

    val drained = dictionary.copy()
    val lookup = drained.lookUp(minerKey)
    val deletion = drained.delete(minerKey)
    val slash = ReducerFixtures.fraudProofTx(19, SyncFixtures.id(120024), SyncFixtures.id(120025),
      SyncFixtures.id(120026), 102, minerKey, lookup.proof.ergoValue.toHex,
      deletion.proof.ergoValue.toHex, drained, 0, BigInt(0), 459L, bond, 99L, held, bond)
    val shortPaid = slash.copy(outputs =
      slash.outputs.updated(1, slash.outputs(1).copy(value = bond - 1L)))

    val transition = BlockReducer.applyBlock(afterEval,
      BlockInfo(SyncFixtures.id(102), 102, Seq(shortPaid), SyncFixtures.id(101)), mine)
      .toOption.value

    transition.quarantined.head.reason should include("to its prover")
  }

  // ─── genesis ──────────────────────────────────────────────────────────────

  "A genesis" should "be refused when its NFT is not minted from its collateral box" in {
    val collateral = SyncFixtures.id(140001)
    val genesis = ReducerFixtures.genesisTx(20, collateral, SyncFixtures.id(140002), 100)
    val wrongNFT = genesis.copy(outputs = Seq(genesis.outputs.head.copy(
      assets = Seq(Token(ErgoId.create(SyncFixtures.id(140003)), 1L)))))
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(wrongNFT), SyncFixtures.id(99),
      resolvedInputs = Map(collateral ->
        ReducerFixtures.resolvedInput(collateral, Some(ReducerFixtures.CollateralToken), 100)))

    val transition = BlockReducer
      .applyBlock(ReducerFixtures.emptyState(), block, protocol).toOption.value

    transition.state.rollups shouldBe empty
    transition.quarantined.head.reason should include("rollup NFT")
  }

  it should "be refused when its bond ledger does not start empty" in {
    val collateral = SyncFixtures.id(140004)
    val txId = SyncFixtures.id(140005)
    val output = ReducerFixtures.holdingOutput(SyncFixtures.id(140006), txId, 100,
      PlasmaDictionary.empty(), period = 100L, bond = 5L, nft = collateral)
    val tx = BlockTx(txId, Seq(TxInput(collateral, None)), Seq.empty, Seq(output))
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(tx), SyncFixtures.id(99),
      resolvedInputs = Map(collateral ->
        ReducerFixtures.resolvedInput(collateral, Some(ReducerFixtures.CollateralToken), 100)))

    val transition = BlockReducer
      .applyBlock(ReducerFixtures.emptyState(), block, protocol).toOption.value

    transition.state.rollups shouldBe empty
    transition.quarantined.head.reason should include("bond ledger must be empty")
  }
}
