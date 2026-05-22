package state.messages

import akka.actor.ActorRef

object StateFrameMessages {

  /** Broadcast by StateFrame to every registered subscriber whenever a new block
   *  is detected on the chain. */
  case class NewBlock(blockInfo: BlockInfo)

  case object CheckBlock
}
