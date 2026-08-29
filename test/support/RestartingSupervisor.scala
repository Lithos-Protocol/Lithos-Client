package support

import akka.actor.{Actor, ActorKilledException, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}

/** Test-only parent that gives actor-incarnation tests a real restart boundary. */
object RestartingSupervisor {
  case object GetChild
  final case class Child(ref: ActorRef)

  def props(childProps: Props): Props = Props(new RestartingSupervisor(childProps))
}

final class RestartingSupervisor(childProps: Props) extends Actor {
  import RestartingSupervisor._

  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: ActorKilledException => SupervisorStrategy.Restart
    case _: java.util.concurrent.TimeoutException => SupervisorStrategy.Restart
    case _ => SupervisorStrategy.Escalate
  }

  private val child = context.actorOf(childProps, "child")

  override def receive: Receive = {
    case GetChild => sender() ! Child(child)
  }
}
