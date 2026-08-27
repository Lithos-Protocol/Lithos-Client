package state.messages

import akka.actor.ActorRef

object StateFrameMessages {

  /** Broadcast to registered subscribers when StateFrame detects a canonical block. */
  case class NewBlock(blockInfo: BlockInfo)

  case object CheckBlock
  case object StartSynchronization
}
