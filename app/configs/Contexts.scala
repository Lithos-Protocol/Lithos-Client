package configs

import akka.actor.ActorSystem

import javax.inject.Inject
import scala.concurrent.ExecutionContext


class Contexts (system: ActorSystem) {
  private val prefix = "lithos-contexts."
  val stratumContext:      ExecutionContext = system.dispatchers.lookup(prefix+"stratum-dispatcher")
  val pollingContext:      ExecutionContext = system.dispatchers.lookup(prefix+"polling-dispatcher")
}
