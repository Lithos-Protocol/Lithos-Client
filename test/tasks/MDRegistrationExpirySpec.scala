package tasks

import lfsm.{LFSMHelpers, MDDatabase}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import storage.KeyValueStore

import java.nio.file.Files

/**
 * When this miner may take its own registration out of the dictionary.
 *
 * Removing it while a rollup opened under it is still live makes that rollup's honest submission
 * read as unregistered, and any finder can then take the bond. The window is symmetric around expiry
 * because a rollup opened at the last legal moment still runs its full lifetime afterwards.
 */
class MDRegistrationExpirySpec extends AnyFlatSpec with Matchers {

  private val validUntil = 1000000L

  "The removal danger window" should "open a rollup lifetime before expiry and close one after" in {
    MDSyncTask.removalUnsafeFrom(validUntil) shouldEqual validUntil - 720L
    MDSyncTask.removalSafeAt(validUntil) shouldEqual validUntil + 720L

    withClue("a rollup is challengeable for its holding period plus its evaluation period: ") {
      LFSMHelpers.ROLLUP_LIFETIME shouldEqual LFSMHelpers.HOLDING_PERIOD + LFSMHelpers.EVAL_PERIOD
    }
  }

  it should "still be open one block before it closes" in {
    // The boundary that matters: waiting until expiry is not enough, and stopping one block early is
    // the mistake this window exists to prevent.
    val safe = MDSyncTask.removalSafeAt(validUntil)
    (validUntil >= MDSyncTask.removalUnsafeFrom(validUntil)) shouldBe true
    (safe - 1L >= MDSyncTask.removalUnsafeFrom(validUntil)) shouldBe true
  }

  "The recorded expiry" should "round-trip, since the warning reads it rather than the dictionary" in {
    val dir = Files.createTempDirectory("md-expiry")
    val db = new MDDatabase(KeyValueStore.openOrThrow(KeyValueStore.DefaultBackend, dir))

    db.getRegistrationExpiry shouldBe None
    db.setRegistrationExpiry(validUntil) shouldBe true
    db.getRegistrationExpiry shouldEqual Some(validUntil)
    db.delRegistrationExpiry shouldBe true
    db.getRegistrationExpiry shouldBe None
    db.close()
  }
}
