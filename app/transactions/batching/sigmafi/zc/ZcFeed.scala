package transactions.batching.sigmafi.zc

import akka.actor.{Actor, ActorRef, Cancellable}
import state.messages.StateFrameMessages.NewBlock
import state.synchronization.{AutoSubscribable, CompleteMempool}
import stats.ZcFills

import javax.inject.{Inject, Named}
import scala.concurrent.duration._

/**
 * Feeds the ZC tracker: each block the canonical sync commits, and every two seconds the mempool observation the
 * mempool view already holds, which starts no walk of its own. Each message only hands the tracker a reference;
 * the tracker's own worker does every read and parse, so nothing here can block or throw. The same tick advances the
 * fill history, which runs on its own thread.
 */
class ZcFeed(tracker: ZcTracker, stateFrame: ActorRef, mempoolView: ActorRef, fills: Option[ZcFills]) extends Actor {

  @Inject def this(tracker: ZcTracker, @Named("state-frame") stateFrame: ActorRef,
                   @Named("mempool-view") mempoolView: ActorRef, fills: ZcFills) =
    this(tracker, stateFrame, mempoolView, Some(fills))

  def this(tracker: ZcTracker, stateFrame: ActorRef, mempoolView: ActorRef) = this(tracker, stateFrame, mempoolView, None)

  private case object Tick

  private val ticker: Option[Cancellable] =
    if (tracker.enabled) Some(context.system.scheduler.scheduleWithFixedDelay(1.second, ZcFeed.Interval, self, Tick)(
      context.dispatcher))
    else None

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = {
    case Tick =>
      // Re-sent every tick so a restarted publisher cannot silently drop this subscriber
      stateFrame ! AutoSubscribable.AutoSubscribe(self)
      mempoolView ! CompleteMempool.Latest
      tracker.poke()
      fills.foreach(_.tick())
    case NewBlock(block) => tracker.offer(block)
    case observation: CompleteMempool.Observation => tracker.observe(observation)
  }
}

object ZcFeed {
  final val Interval: FiniteDuration = 2.seconds
}
