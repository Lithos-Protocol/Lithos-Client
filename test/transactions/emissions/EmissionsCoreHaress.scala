package transactions.emissions

import akka.actor.{Actor, ActorRef}
import configs.NodeContext
import play.api.Configuration
import transactions.emissions.EmissionsCore.{Collateralize, DriveQueue}
import transactions.engine.EngineIntent

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

private[emissions] abstract class EmptyEmissionActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}

/**
 * Isolates the engine's emission behavior from wallet and rollup admission in domain tests.
 *
 * Timer ticks are dispatched the way TransactionEngine dispatches `EngineIntent.Queue` and
 * `EngineIntent.Collateralize`: through `executeEmission`, releasing the shared pass lock with
 * `finishEmission` on completion. A tick arriving while a pass runs is refused by `executeEmission`
 * itself, which is the behaviour these tests are about.
 */
class EmissionsCoreHaress(override protected val config: Configuration,
                          override protected val emissionNodeContext: NodeContext,
                          override protected val emissionWalletManager: ActorRef)
  extends EmptyEmissionActor with EmissionsCore {

  private implicit val harnessEc: ExecutionContext = context.dispatcher
  private case class PassDone()

  override def receive: Receive = harnessReceive.orElse(super.receive)

  private def harnessReceive: Receive = {
    case DriveQueue => dispatch(EngineIntent.Queue)
    case Collateralize => dispatch(EngineIntent.Collateralize)
    case PassDone() => finishEmission()
  }

  private def dispatch(intent: EngineIntent): Unit =
    Try(executeEmission(intent)) match {
      case Success(running) => running.onComplete(_ => self ! PassDone())
      case Failure(_) => ()
    }
}
