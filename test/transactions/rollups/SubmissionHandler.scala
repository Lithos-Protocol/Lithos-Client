package transactions.rollups

import akka.actor.{Actor, ActorRef}
import configs.NodeContext
import play.api.Configuration
import play.api.cache.SyncCacheApi

private[rollups] abstract class EmptyRollupActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}

/** Isolates the engine's rollup behavior from wallet and DEX admission in domain tests. */
class RollupEngineHarness(override protected val config: Configuration,
                         override protected val rollupNodeContext: NodeContext,
                         override protected val cacheApi: SyncCacheApi,
                         override protected val dataBoxes: DataBoxSource,
                         override protected val syncHandler: ActorRef,
                         override protected val mempoolView: ActorRef,
                         override protected val walletManager: ActorRef)
  extends EmptyRollupActor with EngineRollups
