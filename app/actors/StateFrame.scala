package actors

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeConfig, SyncConfig}
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.StateFrameMessages.{Attach, Detach}
import state.messages.SyncMessages.HandledBlock
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals

import javax.inject.Inject
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class StateFrame @Inject()(config: Configuration) extends Actor {
  import StateFrame._

  private val logger: Logger     = LoggerFactory.getLogger("StateFrame")
  private val syncConfig: SyncConfig = new SyncConfig(config)
  private val nodeConfig: NodeConfig = Globals.getNodeConfig
  private val nodeDataSource         = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]

  implicit val timeout: Timeout = Timeout(10 seconds)

  override def receive: Receive = idle(Set.empty)

  // No subscribers — scheduler is stopped.
  private def idle(subscribers: Set[ActorRef]): Receive = {
    case Attach(ref) =>
      val startHeight = chainHeight
      val newSubs     = subscribers + ref
      logger.info(s"Actor ${ref.path.name} attached, starting StateFrame at height $startHeight (${newSubs.size} total)")
      val scheduler = context.system.scheduler.scheduleWithFixedDelay(
        syncConfig.listeningInterval,
        syncConfig.listeningInterval,
        self,
        Tick
      )(context.dispatcher)
      context.become(running(newSubs, startHeight, scheduler))

    case Detach(_) => // nothing to cancel while idle
  }

  // Scheduler running — waiting for the next Tick to arrive.
  private def running(subscribers: Set[ActorRef], currentHeight: Int, scheduler: Cancellable): Receive = {
    case Attach(ref) =>
      logger.info(s"Actor ${ref.path.name} attached to StateFrame (${subscribers.size + 1} total)")
      context.become(running(subscribers + ref, currentHeight, scheduler))

    case Detach(ref) =>
      val newSubs = subscribers - ref
      logger.info(s"Actor ${ref.path.name} detached from StateFrame (${newSubs.size} remaining)")
      if (newSubs.isEmpty) {
        scheduler.cancel()
        context.become(idle(Set.empty))
      } else {
        context.become(running(newSubs, currentHeight, scheduler))
      }

    case Tick =>
      val tip = chainHeight
      if (currentHeight <= tip) {
        Try(fetchBlock(currentHeight)) match {
          case Success(blockInfo) =>
            logger.info(s"StateFrame broadcasting block at height $currentHeight to ${subscribers.size} subscriber(s)")
            // Move to broadcasting state immediately so subsequent Ticks are dropped.
            context.become(broadcasting(subscribers, currentHeight, scheduler))
            val acks = subscribers.map(ref => (ref ? BlockMessage(blockInfo)).mapTo[HandledBlock])
            Future.sequence(acks)(implicitly, context.dispatcher).onComplete {
              case Success(_) =>
                logger.info(s"All subscribers acknowledged block at height $currentHeight")
                self ! AdvanceHeight(currentHeight + 1)
              case Failure(e) =>
                logger.error(s"One or more subscribers failed to ack block at height $currentHeight — advancing anyway", e)
                self ! AdvanceHeight(currentHeight + 1)
            }(context.dispatcher)
          case Failure(e) =>
            logger.error(s"Failed to fetch block at height $currentHeight", e)
        }
      }
  }

  // A broadcast is in flight — ignore ticks until every subscriber has replied.
  private def broadcasting(subscribers: Set[ActorRef], currentHeight: Int, scheduler: Cancellable): Receive = {
    case Tick =>
      logger.debug(s"StateFrame ignoring tick while broadcasting block $currentHeight")

    case Attach(ref) =>
      logger.info(s"Actor ${ref.path.name} attached while broadcasting (${subscribers.size + 1} total)")
      context.become(broadcasting(subscribers + ref, currentHeight, scheduler))

    case Detach(ref) =>
      val newSubs = subscribers - ref
      logger.info(s"Actor ${ref.path.name} detached while broadcasting (${newSubs.size} remaining)")
      context.become(broadcasting(newSubs, currentHeight, scheduler))

    // Sent by the Future callback once all acks (or timeouts) are resolved.
    case AdvanceHeight(nextHeight) =>
      if (subscribers.isEmpty) {
        scheduler.cancel()
        context.become(idle(Set.empty))
      } else {
        context.become(running(subscribers, nextHeight, scheduler))
      }
  }

  private def chainHeight: Int =
    nodeConfig.getClient.execute(_.getHeight)

  private def fetchBlock(height: Int): BlockInfo = {
    val header = nodeDataSource.getNodeBlocksApi.getFullBlockAt(height).execute().body().get(0)
    val block  = nodeDataSource.getNodeBlocksApi.getFullBlockById(header).execute().body()
    BlockInfo.fromSync(block)
  }
}

object StateFrame {
  // Internal messages — not part of the public API.
  private[actors] case object Tick
  private[actors] case class AdvanceHeight(height: Int)
}
