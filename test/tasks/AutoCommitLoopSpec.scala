package tasks

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * When a registration or commitment the loop sent stops holding it back.
 *
 * Until this client has synced the block carrying it, the dictionary and the data box still read as
 * they did before the transaction, so acting on them builds a second copy that spends the same boxes.
 */
class AutoCommitLoopSpec extends AnyFlatSpec with Matchers {

  "A confirmed transaction" should "hold the loop until synchronization reaches its block" in {
    MDSyncTask.awaitingSync(includedAt = Some(1000), syncedTo = Some(999)) shouldBe true
    MDSyncTask.awaitingSync(includedAt = Some(1000), syncedTo = Some(1000)) shouldBe false
    MDSyncTask.awaitingSync(includedAt = Some(1000), syncedTo = Some(1200)) shouldBe false
  }

  it should "hold the loop while synchronization has no cursor at all" in {
    MDSyncTask.awaitingSync(includedAt = Some(1000), syncedTo = None) shouldBe true
  }

  "A transaction the node never confirmed" should "not hold the loop" in {
    // It left the mempool without landing, so the next pass has to be free to build a replacement.
    MDSyncTask.awaitingSync(includedAt = None, syncedTo = Some(999)) shouldBe false
    MDSyncTask.awaitingSync(includedAt = None, syncedTo = None) shouldBe false
  }
}
