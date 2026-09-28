package transactions.emissions

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

/**
 * Which side builds the Activates at the queue head in each block: this miner's own candidate, for
 * the first block it carries them in, or the funded pass, from the block after. Without this the
 * funded copies reach the mempool while a served package still carries fee-less ones, and the next
 * candidate request evicts the funded copies from this node.
 *
 * Both sides run off the actor's thread, so every field is atomic, and each side marks itself before
 * reading the other's mark: at worst both stand aside for a round, never both take the Activates.
 */
final class QueueOwnership {

  /** The candidate's hold, with the block height of the build that recorded it. */
  private val hold = new AtomicReference[(Int, Option[QueueHold])]((0, None))

  /** The block height of the candidate build in flight, or 0. */
  private val building = new AtomicInteger(0)

  /** Whether a funded pass allowed to broadcast Activates is running. */
  private val fundedActivating = new AtomicBoolean(false)

  /** The last block height whose Activates were handed to the funded pass. */
  private val releasedFor = new AtomicInteger(0)

  /** Marks a candidate build for `height` as running. Runs on the actor's thread, before the build. */
  def candidateStarted(height: Int): Unit = building.set(height)

  /** The hold a build decides against, and whether the funded pass owns this block's Activates. */
  def candidateView(height: Int): (Option[QueueHold], Boolean) =
    (hold.get._2, fundedActivating.get || releasedFor.get >= height)

  /** Records a build's decision. A build for an older block never overwrites a newer one's. */
  def candidateDecided(height: Int, decided: Option[QueueHold]): Unit =
    hold.updateAndGet(current => if (height >= current._1) (height, decided) else current)

  def candidateFinished(height: Int): Unit = building.compareAndSet(height, 0)

  /** Hands this block's Activates to the funded pass. True only for the first handover at a height. */
  def release(height: Int): Boolean =
    releasedFor.getAndAccumulate(height, (a: Int, b: Int) => math.max(a, b)) < height

  /**
   * Whether a funded pass for `height` may broadcast Activates, marking it as doing so when it may.
   * Heights compare with `>=` so a pass reading the chain a block behind a candidate still yields.
   */
  def fundedStarts(height: Int): Boolean = {
    fundedActivating.set(true)
    val held = hold.get._2.exists(_.since >= height) || building.get >= height
    val may = releasedFor.get >= height || !held
    if (!may) fundedActivating.set(false)
    may
  }

  def fundedFinished(): Unit = fundedActivating.set(false)
}
