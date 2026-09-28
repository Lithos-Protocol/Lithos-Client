package transactions.emissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Which side builds the Activates at the queue head in a block. The candidate holds them for the
 * first block it carries them in and the funded pass takes them from the next; the two must never
 * both take them, or the next candidate request evicts the funded copies from this node.
 */
class QueueOwnershipSpec extends AnyFlatSpec with Matchers {

  private val block = 1000

  "A funded pass" should "leave Activates the candidate holds for this block, and take them the block after" in {
    val o = new QueueOwnership
    o.candidateStarted(block)
    o.candidateDecided(block, Some(QueueHold(7L, block)))
    o.candidateFinished(block)

    o.fundedStarts(block) shouldBe false
    o.fundedStarts(block + 1) shouldBe true
  }

  it should "leave the Activates alone while a candidate build for the block is in flight" in {
    // The build may be about to carry them; it has not recorded a hold yet.
    val o = new QueueOwnership
    o.candidateStarted(block)
    o.fundedStarts(block) shouldBe false

    o.candidateFinished(block)
    o.fundedStarts(block) shouldBe true
  }

  it should "yield to a candidate reading the chain a block ahead of it" in {
    val o = new QueueOwnership
    o.candidateDecided(block + 1, Some(QueueHold(7L, block + 1)))
    o.fundedStarts(block) shouldBe false
  }

  it should "take a block's Activates once they are handed over, even while a candidate builds" in {
    val o = new QueueOwnership
    o.candidateStarted(block)
    o.release(block) shouldBe true
    o.fundedStarts(block) shouldBe true
  }

  "A candidate build" should "see that the funded pass owns the Activates while it broadcasts them" in {
    val o = new QueueOwnership
    o.fundedStarts(block) shouldBe true
    o.candidateView(block)._2 shouldBe true

    o.fundedFinished()
    o.candidateView(block)._2 shouldBe false
  }

  it should "not see ownership from a funded pass that was told to leave the Activates" in {
    // A pass holding back only runs Clears, so the candidate keeps carrying what it holds.
    val o = new QueueOwnership
    o.candidateDecided(block, Some(QueueHold(7L, block)))
    o.fundedStarts(block) shouldBe false
    o.candidateView(block)._2 shouldBe false
  }

  it should "see a handed-over block as the funded pass's for the rest of that block" in {
    val o = new QueueOwnership
    o.release(block)
    o.candidateView(block)._2 shouldBe true
    o.candidateView(block + 1)._2 shouldBe false
  }

  "A handover" should "count once per block height" in {
    val o = new QueueOwnership
    o.release(block) shouldBe true
    o.release(block) shouldBe false
    o.release(block - 1) shouldBe false
    o.release(block + 1) shouldBe true
  }

  "A hold" should "never be overwritten by a build for an older block" in {
    val o = new QueueOwnership
    o.candidateDecided(block + 1, Some(QueueHold(8L, block + 1)))
    o.candidateDecided(block, Some(QueueHold(7L, block)))
    o.candidateView(block + 1)._1 shouldEqual Some(QueueHold(8L, block + 1))
  }
}
