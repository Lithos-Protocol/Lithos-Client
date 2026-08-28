package state.synchronization

import lfsm.states.PlasmaDictionary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{ReducerFixtures, SyncFixtures}

class CommittedSyncStateSpec extends AnyFlatSpec with Matchers {

  "CommittedSyncState" should "reject a route whose rollup is not committed" in {
    val base = ReducerFixtures.emptyState()

    val error = intercept[IllegalArgumentException] {
      base.copy(routes = Map(SyncFixtures.id(1) -> SyncFixtures.id(2)))
    }

    error.getMessage should include("Every rollup route must name committed state")
  }

  it should "reject a route whose UTXO differs from the committed rollup" in {
    val rollupId = SyncFixtures.id(10)
    val committedUtxo = SyncFixtures.id(11)
    val staleUtxo = SyncFixtures.id(12)
    val base = ReducerFixtures.stateWithRollup(
      height = 99,
      blockId = SyncFixtures.id(99),
      rollupId = rollupId,
      utxoId = committedUtxo,
      dictionary = PlasmaDictionary.empty())

    val error = intercept[IllegalArgumentException] {
      base.copy(routes = Map(staleUtxo -> rollupId))
    }

    error.getMessage should include("Every route must match its rollup's current UTXO")
  }

  it should "return routed rollups in deterministic UTXO order" in {
    val firstId = SyncFixtures.id(20)
    val firstUtxo = SyncFixtures.id(21)
    val secondId = SyncFixtures.id(30)
    val secondUtxo = SyncFixtures.id(31)
    val first = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99),
      firstId, firstUtxo, PlasmaDictionary.empty())
    val second = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99),
      secondId, secondUtxo, PlasmaDictionary.empty())
    val combined = first.copy(
      rollups = first.rollups + (secondId -> second.rollups(secondId)),
      rollupOrigins = first.rollupOrigins + (secondId -> second.rollupOrigins(secondId)),
      routes = Map(secondUtxo -> secondId, firstUtxo -> firstId))

    combined.routedRollups.map(_._1) shouldEqual Seq(firstUtxo, secondUtxo)
  }
}
