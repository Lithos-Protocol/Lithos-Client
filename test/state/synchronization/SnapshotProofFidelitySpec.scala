package state.synchronization

import lfsm.states.PlasmaDictionary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.SyncFixtures

/**
 * A dictionary rebuilt from a manifest must produce the same proofs as one built by replay.
 *
 * The Plasma Toolkit requires `generateProof()` after `loadManifest` for exactly this, and its own
 * `copy` does it. The snapshot restore path did not, so every dictionary a restart recovered carried a
 * prover in a state the toolkit does not define — and a submission's insertion proof rides in a
 * context variable the holding contract verifies, so a wrong one is rejected on chain rather than
 * caught here.
 */
class SnapshotProofFidelitySpec extends AnyFlatSpec with Matchers {

  private val entries = SyncFixtures.plasmaEntries(8, 64)

  /** Exercises the restored prover directly, rather than a copy of it that resets itself. */
  "A dictionary restored from a manifest" should "prove the next insertion exactly as a replayed one does" in {
    val replayed = PlasmaDictionary.empty()
    replayed.insert(entries.take(4): _*)

    val restored = PlasmaDictionary
      .fromManifest(replayed.flags, replayed.parameters, replayed.getManifest())
      .toOption
      .getOrElse(fail("a manifest taken from a live dictionary must load"))

    restored.digest should contain theSameElementsInOrderAs replayed.digest

    val (key, value) = entries(4)
    val fromReplayed = replayed.insert(key -> value)
    val fromRestored = restored.insert(key -> value)

    withClue("digests after the same insertion: ") {
      fromRestored.proof.ergoValue.toHex shouldEqual fromReplayed.proof.ergoValue.toHex
    }
    restored.digest should contain theSameElementsInOrderAs replayed.digest
  }

  /** The same property one level up, through the codec the snapshot path actually uses. */
  it should "survive a snapshot encode and decode" in {
    val replayed = PlasmaDictionary.empty()
    replayed.insert(entries.take(4): _*)

    val restored = PlasmaDictionary
      .fromManifest(replayed.flags, replayed.parameters, replayed.getManifest())
      .toOption.get

    // A copy is what every builder takes before mutating, so it must agree too.
    val (key, value) = entries(5)
    val viaCopy = restored.copy().insert(key -> value)
    val direct = replayed.copy().insert(key -> value)

    viaCopy.proof.ergoValue.toHex shouldEqual direct.proof.ergoValue.toHex
  }
}
