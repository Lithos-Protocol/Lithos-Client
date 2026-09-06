package transactions.emissions

import akka.actor.{Actor, ActorRef}
import configs.NodeContext
import play.api.Configuration

private[emissions] abstract class EmptyEmissionActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}

class EmissionEngineHarness(override protected val config: Configuration,
                            override protected val emissionNodeContext: NodeContext,
                            override protected val emissionWalletManager: ActorRef)
  extends EmptyEmissionActor with EngineEmissions
