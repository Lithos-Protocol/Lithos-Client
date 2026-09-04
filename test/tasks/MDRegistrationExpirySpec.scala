package tasks

import lfsm.{LFSMHelpers, MDDatabase}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.ergoplatform.sdk.ErgoId
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

  "The entry layout" should "put the expiry in its last eight bytes" in {
    // What the warning reads: the dictionary entry is the credential the registration minted with
    // the expiry appended, and the contract binds that value to the one the transaction declared.
    MDSyncTask.EntrySize shouldEqual 40
  }

  // ─── the schema guard ─────────────────────────────────────────────────────

  private def store(): MDDatabase =
    new MDDatabase(KeyValueStore.openOrThrow(
      KeyValueStore.DefaultBackend, Files.createTempDirectory("md-schema")))

  private val token = ErgoId.create("ab" * 32)

  "A store written before the schema existed" should "be cleared and stamped" in {
    val db = store()
    db.setDataBoxToken(token) shouldBe true

    db.migrate() shouldBe true
    db.getDataBoxToken shouldBe None
    db.getSchemaVersion shouldEqual Some(MDDatabase.Schema)
    db.close()
  }

  "A store at an older schema" should "be cleared too" in {
    val db = store()
    db.setSchemaVersion(MDDatabase.Schema - 1) shouldBe true
    db.setDataBoxToken(token) shouldBe true

    db.migrate() shouldBe true
    db.getDataBoxToken shouldBe None
    db.getSchemaVersion shouldEqual Some(MDDatabase.Schema)
    db.close()
  }

  "A store already at this schema" should "be left alone" in {
    // The half that matters on every ordinary startup: a migration that ran unconditionally would
    // drop the data-box token each time and make this miner look unregistered until sync republished.
    val db = store()
    db.migrate() shouldBe true
    db.setDataBoxToken(token) shouldBe true

    db.migrate() shouldBe false
    db.getDataBoxToken shouldEqual Some(token)
    db.close()
  }
}
