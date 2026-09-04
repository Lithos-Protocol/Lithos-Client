package tasks

import lfsm.{LFSMHelpers, MDDatabase}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.ergoplatform.sdk.ErgoId
import storage._

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

  "The warning" should "start a rollup lifetime before expiry and not before" in {
    // The decision warnIfExpiring makes, at the height it changes. One block early is silence and
    // one block late is the miner never being told at all.
    val opens = MDSyncTask.removalUnsafeFrom(validUntil)
    MDSyncTask.shouldWarn(opens - 1L, validUntil) shouldBe false
    MDSyncTask.shouldWarn(opens, validUntil) shouldBe true
  }

  it should "still be running at expiry and through the whole unsafe window" in {
    // Expiry is the middle of the window, not its end: a rollup opened at the last legal moment is
    // slashable for its full lifetime afterwards, so the warning has to outlive the registration.
    MDSyncTask.shouldWarn(validUntil, validUntil) shouldBe true
    MDSyncTask.shouldWarn(MDSyncTask.removalSafeAt(validUntil) - 1L, validUntil) shouldBe true
    MDSyncTask.shouldWarn(MDSyncTask.removalSafeAt(validUntil), validUntil) shouldBe true
  }

  "The entry layout" should "put the expiry where registrationExpiry reads it" in {
    // Built the way BlockReducer builds it, so a change to that layout fails here rather than
    // leaving the expiry read taking eight bytes of something else.
    val credential = Array.fill(32)(7.toByte)
    val entry = credential ++ scorex.utils.Longs.toByteArray(validUntil)

    entry.length shouldEqual MDSyncTask.EntrySize
    scorex.utils.Longs.fromByteArray(entry.slice(32, MDSyncTask.EntrySize)) shouldEqual validUntil
  }

  // ─── the schema guard ─────────────────────────────────────────────────────

  private def store(): MDDatabase =
    new MDDatabase(KeyValueStore.openOrThrow(
      KeyValueStore.DefaultBackend, Files.createTempDirectory("md-schema")))

  private val token = ErgoId.create("ab" * 32)

  /** A store whose deletes always fail, which is what a partially cleared store looks like. */
  private class DeleteRefusingStore(inner: KeyValueStore) extends KeyValueStore {
    override def path: java.nio.file.Path = inner.path
    override def backend: StorageBackend = inner.backend
    override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A] =
      inner.readSnapshot(f)
    override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = inner.get(key)
    override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
      inner.scanPrefix(prefix)
    override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
      inner.scanKeys(prefix)
    override def close(): Either[StoreError, Unit] = inner.close()

    override def write(mutations: Seq[KeyValueMutation],
                       durability: WriteDurability): Either[StoreError, Unit] =
      if (mutations.exists(_.isInstanceOf[KeyValueMutation.Delete]))
        Left(StoreError.WriteFailed(inner.path, new RuntimeException("delete refused")))
      else inner.write(mutations, durability)
  }

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

  "A clear that does not finish" should "leave the schema unstamped so the next start retries" in {
    // Stamping a partial clear marks surviving keys as current, which is the one thing the schema
    // exists to prevent. Deletes are refused here; puts still work, so a stamp would be visible.
    val db = new MDDatabase(new DeleteRefusingStore(KeyValueStore.openOrThrow(
      KeyValueStore.DefaultBackend, Files.createTempDirectory("md-schema-fail"))))
    db.setDataBoxToken(token) shouldBe true

    db.migrate() shouldBe false
    withClue("a store stamped after a failed clear is read under an encoding it was told to distrust: ") {
      db.getSchemaVersion shouldBe None
    }
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
