package state.messages

import akka.actor.ActorRef

object StateFrameMessages {

  /** Sealed response type so tasks can pattern-match a single mapTo call. */
  sealed trait SubscribeResponse

  /** Sent by a task to register its sync actor with StateFrame.
   *  @param height     the actor's current block height, which must equal StateFrame's
   *                    current height for the subscription to be accepted
   *  @param subscriber the sync actor that will receive NewBlock broadcasts;
   *                    separating this from the sender lets a non-actor task drive
   *                    the handshake while the actor remains the actual subscriber */
  case class Subscribe(height: Int, subscriber: ActorRef)

  /**
   * Automatically subscribe to StateFrame without height verification. Used for
   * actors which want block updates but don't necessarily care about the current height or
   * ordering of blocks. (e.g MempoolView)
   */
  case class AutoSubscribe(subscriber: ActorRef)

  /** Sent by StateFrame back to the requesting task when the height matched and
   *  the subscription was accepted.  The task should cancel its polling job on
   *  receipt of this message. */
  case object SubscribeAck extends SubscribeResponse

  /** Sent by StateFrame back to the requesting task when the height check failed.
   *  The task should load the next missing block, update the actor, and retry. */
  case class SubscribeRejected(reason: String) extends SubscribeResponse

  /** Broadcast by StateFrame to every registered subscriber whenever a new block
   *  is detected on the chain. */
  case class NewBlock(blockInfo: BlockInfo)
}
