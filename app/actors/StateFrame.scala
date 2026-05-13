package actors

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.NodeConfig
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.BlockInfo
import state.messages.StateFrameMessages._
import utils.Globals

import javax.inject.Inject
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object StateFrame {
  private case object Tick
}

class StateFrame @Inject()() extends Actor with InjectedActorSupport {
  import StateFrame._

  val logger: Logger = LoggerFactory.getLogger("StateFrame")
  val nodeConfig: NodeConfig = Globals.getNodeConfig

  private val dataSource: NodeAndExplorerDataSourceImpl =
    nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]

  private var currentHeight: Int = chainHeight
  private var subscribers: Set[ActorRef] = Set.empty

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Tick)(context.dispatcher)

  logger.info(s"StateFrame initialized at chain height $currentHeight")

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = {

    case Tick =>
      val latestHeight = chainHeight
      if (latestHeight > currentHeight) {
        fetchBlock(currentHeight + 1) match {
          case Success(blockInfo) =>
            currentHeight = blockInfo.height
            logger.info(s"StateFrame advancing to height $currentHeight, broadcasting to ${subscribers.size} subscriber(s)")
            subscribers.foreach(_ ! NewBlock(blockInfo))
          case Failure(ex) =>
            ex match {
              case _: NullPointerException =>
                logger.warn(s"StateFrame failed to fetch block at height ${currentHeight + 1}, will re-attempt")
              case _ =>
                logger.error(s"StateFrame failed to fetch block at height ${currentHeight + 1}", ex)
            }
        }
      }

    case Subscribe(height, subscriber) =>
      val requester = sender()
      if (height == currentHeight) {
        subscribers = subscribers + subscriber
        requester ! SubscribeAck
        logger.info(s"StateFrame accepted subscription of ${subscriber.path.name} at height $height (${subscribers.size} total subscriber(s))")
      } else {
        requester ! SubscribeRejected(
          s"Height mismatch: subscriber is at $height but StateFrame is at $currentHeight"
        )
        logger.warn(s"StateFrame rejected subscription of ${subscriber.path.name}: height $height != $currentHeight")
      }
    case AutoSubscribe(subscriber) =>
      subscribers = subscribers + subscriber
      logger.info(s"StateFrame accepted auto-subscription of ${subscriber.path.name} (${subscribers.size} total subscriber(s))")
    case CheckBlock =>
      self ! Tick
  }

  private def chainHeight: Int =
    nodeConfig.getClient.execute(ctx => ctx.getHeight)

  private def fetchBlock(height: Int): Try[BlockInfo] = Try {
    val blockHeader = dataSource
      .getNodeBlocksApi.getFullBlockAt(height)
      .execute()
      .body()
      .get(0)
    val fullBlock = dataSource
      .getNodeBlocksApi.getFullBlockById(blockHeader)
      .execute()
      .body()
    BlockInfo.fromSync(fullBlock)
  }
}
