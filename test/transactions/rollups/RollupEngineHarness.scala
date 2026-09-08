package transactions.rollups

import akka.actor.{Actor, ActorRef}
import configs.NodeContext
import play.api.Configuration
import play.api.cache.SyncCacheApi
import transactions.engine.{EngineRollupCandidates, RollupExecution}
import transactions.rollups.TransactionMessages.{BatchAccepted, RollupBatch}
import scala.concurrent.ExecutionContext

private[rollups] abstract class EmptyRollupActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}

/** Exercises execution and candidate messages with deterministic wallet and synchronization probes. */
class RollupEngineHarness(config: Configuration, node: NodeContext, cacheApi: SyncCacheApi,
                         dataBoxes: DataBoxSource, sync: ActorRef, mempool: ActorRef, wallet: ActorRef)
  extends EmptyRollupActor with EngineRollupCandidates {
  private implicit val ec: ExecutionContext = context.dispatcher
  private val worker = context.system.dispatchers.lookup("lithos-contexts.critical-tx-dispatcher")
  private var busy = false
  private case object BatchDone
  private def execution() = new RollupExecution(node, wallet, sync, mempool, config, dataBoxes,
    node.getNodeApi, () => true, worker)
  private lazy val fundingFixture = execution()
  def initialTxInputs(info: RollupExecution.InitialTxInfo, isFPTx: Boolean) =
    fundingFixture.initialTxInputs(info, isFPTx)
  override protected def candidateExecution(alive: () => Boolean): RollupExecution = execution()
  override def receive: Receive = ({
    case RollupBatch(stubs) if !busy =>
      busy = true
      sender() ! BatchAccepted(stubs)
      execution().execute(stubs).onComplete(_ => self ! BatchDone)
    case _: RollupBatch => ()
    case BatchDone => busy = false
    case message: transactions.engine.EngineWalletMessages.MarkReservationUncertain => wallet ! message
  }: Receive).orElse(super.receive)
}