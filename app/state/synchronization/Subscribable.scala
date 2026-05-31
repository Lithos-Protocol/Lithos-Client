package state.synchronization

import akka.actor.{Actor, ActorRef}
import org.slf4j.Logger
import state.synchronization.AutoSubscribable.AutoSubscribe
import state.synchronization.Subscribable.{Subscribe, SubscribeAck, SubscribeRejected}

/**
 * Actor which is publishing stateful updates based on height. Potential subscribers must be synced to the correct
 * height in order to receive broadcasted info. Actors not requiring synchronization may use AutoSubscribe to
 * still receive published updates.
 */
trait Subscribable {
  this: Actor =>
  protected var currentHeight: Int
  protected var subscribers: Set[ActorRef]
  protected val logger: Logger

  protected val handleSubscriptions: Receive = {
    case Subscribe(height, subscriber, _) =>
      val requester = sender()
      if (height == currentHeight) {
        subscribers = subscribers + subscriber
        requester ! SubscribeAck
        logger.info(s"Accepted subscription of ${subscriber.path.name} at height $height (${subscribers.size} total subscriber(s))")
      } else {
        requester ! SubscribeRejected(
          s"Height mismatch: subscriber is at $height but publisher is at $currentHeight"
        )
        logger.warn(s"Rejected subscription of ${subscriber.path.name}: height $height != $currentHeight")
      }
    case AutoSubscribe(subscriber) =>
      subscribers = subscribers + subscriber
      logger.info(s"Accepted auto-subscription of ${subscriber.path.name} (${subscribers.size} total subscriber(s))")
  }

}

object Subscribable {
  /** Sealed response type so tasks can pattern-match a single mapTo call. */
  sealed trait SubscribeResponse

  /** Sent by a task to register an actor with publisher.
   *  @param height          - The actor's current block height, which must equal the publisher's
   *                         current height for the subscription to be accepted
   *  @param subscriber      - The sync actor that will receive published broadcasts;
   *                         separating this from the sender lets a non-actor task drive
   *                         the handshake while the actor remains the actual subscriber
   *  @param additionalInfo  - Additional information which may be useful during the subscription process
   *                    */
  case class Subscribe(height: Int, subscriber: ActorRef, additionalInfo: Option[String] = None)

  /** Sent by publisher back to the requesting task when the height matched and
   *  the subscription was accepted.  The task should cancel its polling job on
   *  receipt of this message. */
  case object SubscribeAck extends SubscribeResponse

  /** Sent by publisher back to the requesting task when the height check failed.
   *  The task should load the next missing block, update the actor, and retry. */
  case class SubscribeRejected(reason: String) extends SubscribeResponse
}