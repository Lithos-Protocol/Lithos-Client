package configs

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext


class Contexts (system: ActorSystem) {
  private val prefix = "lithos-contexts."

  // A missing or malformed dispatcher block would otherwise surface as an opaque ConfigException
  // from whoever looked up first; name the block to fix instead.
  private def dispatcher(name: String): ExecutionContext =
    try system.dispatchers.lookup(prefix + name)
    catch {
      case t: Throwable => Configs.fail(s"lithos-contexts.$name",
        s"no dispatcher could be loaded for this name: check the lithos-contexts.$name block in " +
          s"application.conf (${t.getMessage})")
    }

  val stratumContext:      ExecutionContext = dispatcher("stratum-dispatcher")
  val pollingContext:      ExecutionContext = dispatcher("polling-dispatcher")
  val syncContext:         ExecutionContext = dispatcher("sync-dispatcher")
  val txContext:      ExecutionContext = dispatcher("tx-dispatcher")
  val dexContext:     ExecutionContext = dispatcher("dex-dispatcher")
}
