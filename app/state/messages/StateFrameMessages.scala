package state.messages

import akka.actor.ActorRef

object StateFrameMessages {
  case class Attach(ref: ActorRef)
  case class Detach(ref: ActorRef)
}
