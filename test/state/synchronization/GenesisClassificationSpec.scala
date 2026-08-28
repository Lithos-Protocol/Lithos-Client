package state.synchronization

import lfsm.states.PlasmaDictionary
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Longs
import state.messages.BlockInfo
import support.{ReducerFixtures, SyncFixtures}

/**
 * An untracked rollup's submission recreates the holding script, so shape alone cannot tell it from a
 * genesis. Misreading one reports a genesis failure per submission on the channel real faults use.
 */
class GenesisClassificationSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()

  "BlockReducer" should "treat a submission to an untracked rollup as unrelated, not as a genesis" in {
    val base = ReducerFixtures.emptyState()
    val (key, value) = submissionEntry
    val expected = PlasmaDictionary.empty()
    val insertion = expected.insert(key -> value)
    // Nothing in the base tracks this rollup, so the routing branch cannot claim the transaction.
    val tx = ReducerFixtures.submissionTx(number = 1, inputId = SyncFixtures.id(900001),
      outputId = SyncFixtures.id(900002), height = 100, key = key, value = value,
      proofHex = insertion.proof.ergoValue.toHex, outputDictionary = expected,
      numMiners = 1, totalScore = BigInt(12), period = 100L)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(tx), base.cursor.blockId,
      resolvedInputs = Map(SyncFixtures.id(900001) ->
        ReducerFixtures.resolvedInput(SyncFixtures.id(900001), None, 100)))

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.unauthenticatedGenesis shouldBe empty
    transition.unrecognizedGenesis shouldBe empty
    transition.relevantEvents shouldEqual 0
  }

  /** The empty tree is what separates the two, and a real genesis still has to authenticate. */
  it should "still report a holding output with an empty tree whose input is not collateral" in {
    val base = ReducerFixtures.emptyState()
    val counterfeitInput = SyncFixtures.id(910001)
    val tx = ReducerFixtures.genesisTx(1, counterfeitInput, SyncFixtures.id(910002), height = 100)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(tx), base.cursor.blockId,
      resolvedInputs = Map(counterfeitInput ->
        ReducerFixtures.resolvedInput(counterfeitInput, None, 100)))

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.unauthenticatedGenesis.map(_._1) shouldEqual Seq(tx.id)
    transition.state.rollups shouldBe empty
  }

  it should "still accept a collateral-authenticated genesis" in {
    val base = ReducerFixtures.emptyState()
    val collateralInput = SyncFixtures.id(920001)
    val output = SyncFixtures.id(920002)
    val tx = ReducerFixtures.genesisTx(2, collateralInput, output, height = 100)
    val blockId = SyncFixtures.id(100)
    val block = BlockInfo(blockId, 100, Seq(tx), base.cursor.blockId,
      resolvedInputs = Map(collateralInput -> ReducerFixtures.resolvedInput(
        collateralInput, Some(ReducerFixtures.CollateralToken), 100)))

    val transition = BlockReducer.applyBlock(base, block, protocol).toOption.value

    transition.unauthenticatedGenesis shouldBe empty
    transition.state.routes shouldEqual Map(output -> blockId)
  }

  private def submissionEntry: (Array[Byte], Array[Byte]) = {
    val key = SyncFixtures.plasmaEntries(1, 16).head._1
    key -> (Longs.toByteArray(12L) ++ Array.fill[Byte](8)(3))
  }
}
