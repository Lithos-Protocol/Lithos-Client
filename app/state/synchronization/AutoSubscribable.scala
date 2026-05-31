package state.synchronization

import akka.actor.{Actor, ActorRef}
import org.slf4j.Logger
import state.synchronization.AutoSubscribable.AutoSubscribe

/**
 * Actor which is publishing information not dependent on height. To receive updates, an actor
 * must send AutoSubscribe to a publisher implementing this trait.
 */
trait AutoSubscribable {
  this: Actor =>
  protected var subscribers: Set[ActorRef]
  protected val logger: Logger

  protected val handleSubscriptions: Receive = {
    case AutoSubscribe(subscriber) =>
      subscribers = subscribers + subscriber
      logger.info(s"Publisher accepted auto-subscription of ${subscriber.path.name} (${subscribers.size} total subscriber(s))")
  }

}

object AutoSubscribable {
  /**
   * Automatically subscribe to publisher without height verification. Used by and for
   * actors which want updates but do not require (or in the case of publishers, provide) stateful info (e.g MempoolView)
   */
  case class AutoSubscribe(subscriber: ActorRef)
}
