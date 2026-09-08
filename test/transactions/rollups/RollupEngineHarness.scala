package transactions.rollups

import akka.actor.{Actor, ActorRef}
import configs.NodeContext
import play.api.Configuration
import play.api.cache.SyncCacheApi
import transactions.rollups.TransactionMessages.{BatchAccepted, RollupBatch}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

private[rollups] abstract class EmptyRollupActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}

/**
 * Isolates the engine's rollup behavior from wallet and DEX admission in domain tests.
 *
 * Batch admission mirrors what TransactionEngine does with an `EngineIntent.Rollups`: dispatch
 * through `executeRollupBatch`, acknowledge only once it is accepted, and release the lock with
 * `finishRollupBatch` on completion. Reproduced here rather than shortcut, so these tests exercise
 * the same entry points production uses.
 */
class RollupEngineHarness(override protected val config: Configuration,
                         override protected val rollupNodeContext: NodeContext,
                         override protected val cacheApi: SyncCacheApi,
                         override protected val dataBoxes: DataBoxSource,
                         override protected val syncHandler: ActorRef,
                         override protected val mempoolView: ActorRef,
                         override protected val walletManager: ActorRef)
  extends EmptyRollupActor with RollupCore {

  private implicit val harnessEc: ExecutionContext = context.dispatcher
  private case class BatchDone()

  override def receive: Receive = harnessReceive.orElse(super.receive)

  private def harnessReceive: Receive = {
    case RollupBatch(stubs) =>
      val replyTo = sender()
      // Admission refusal throws, and an unacknowledged batch is what tells its sender to keep the
      // stubs and offer them again.
      Try(executeRollupBatch(stubs)) match {
        case Success(running) =>
          replyTo ! BatchAccepted(stubs)
          running.onComplete(_ => self ! BatchDone())
        case Failure(_) => ()
      }
    case BatchDone() => finishRollupBatch()
  }
}
