package configs

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext


class Contexts (system: ActorSystem) {

  // Name the dispatcher block when lookup fails during component construction.
  private def dispatcher(name: String): ExecutionContext =
    try system.dispatchers.lookup(Contexts.key(name))
    catch {
      case t: Throwable => Configs.fail(Contexts.key(name),
        s"no dispatcher could be loaded for this name: check the ${Contexts.key(name)} block in " +
          s"application.conf (${t.getMessage})")
    }

  val stratumContext:      ExecutionContext = dispatcher(Contexts.Stratum)
  val pollingContext:      ExecutionContext = dispatcher(Contexts.Polling)
  val syncContext:         ExecutionContext = dispatcher(Contexts.Sync)
  val txContext:      ExecutionContext = dispatcher(Contexts.Tx)
  val dexContext:     ExecutionContext = dispatcher(Contexts.Dex)
  val databaseContext: ExecutionContext = dispatcher(Contexts.Database)
}

object Contexts {

  final val Stratum: String = "stratum-dispatcher"
  final val Polling: String = "polling-dispatcher"
  final val Sync:    String = "sync-dispatcher"
  final val Tx:      String = "tx-dispatcher"
  final val Dex:     String = "dex-dispatcher"
  final val Database: String = "database-dispatcher"

  /** Dispatcher names shared with startup configuration validation. */
  final val Names: Seq[String] = Seq(Stratum, Polling, Sync, Tx, Dex, Database)

  def key(name: String): String = s"lithos-contexts.$name"
}
