package configs

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext


class Contexts (system: ActorSystem) {

  // A missing or malformed dispatcher block would otherwise surface as an opaque ConfigException
  // from whoever looked up first; name the block to fix instead.
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
}

object Contexts {

  final val Stratum: String = "stratum-dispatcher"
  final val Polling: String = "polling-dispatcher"
  final val Sync:    String = "sync-dispatcher"
  final val Tx:      String = "tx-dispatcher"
  final val Dex:     String = "dex-dispatcher"

  /** Every dispatcher this class looks up. `Configs.validateAll` checks exactly these blocks exist,
   * so the two cannot drift into validating a name nothing resolves or missing one that is. */
  final val Names: Seq[String] = Seq(Stratum, Polling, Sync, Tx, Dex)

  def key(name: String): String = s"lithos-contexts.$name"
}
