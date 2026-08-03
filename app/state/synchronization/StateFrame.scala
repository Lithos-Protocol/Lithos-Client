package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.NodeConfig
import node.NodeApi
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.{BlockInfo, NodeSync}
import state.messages.StateFrameMessages._
import utils.Globals

import javax.inject.Inject
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object StateFrame {
  private case object Tick
}

class StateFrame @Inject()() extends Actor with InjectedActorSupport with Subscribable {

  import StateFrame._


  val nodeConfig: NodeConfig = Globals.getNodeConfig

  private val nodeApi: NodeApi = nodeConfig.getNodeApi

  override val logger: Logger = LoggerFactory.getLogger("StateFrame")
  override var currentHeight: Int = chainHeight
  override var subscribers: Set[ActorRef] = Set.empty

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Tick)(context.dispatcher)

  logger.info(s"StateFrame initialized at chain height $currentHeight")

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = handleSubscriptions orElse {
    case Tick =>
      fetchBlock(currentHeight + 1) match {
        case Success(blockInfo) =>
          currentHeight = blockInfo.height
          logger.info(s"StateFrame advancing to height $currentHeight, broadcasting to ${subscribers.size} subscriber(s)")
          subscribers.foreach(_ ! NewBlock(blockInfo))
        case Failure(_) =>
//          ex match {
//            case _: NullPointerException =>
//              logger.warn(s"StateFrame failed to fetch block at height ${currentHeight + 1}, will re-attempt")
//            case _ =>
//              logger.error(s"StateFrame failed to fetch block at height ${currentHeight + 1}", ex)
//          }
      }
    case CheckBlock =>
      self ! Tick
  }

  private def chainHeight: Int =
    nodeConfig.getClient.execute(ctx => ctx.getHeight)

  private def fetchBlock(height: Int): Try[BlockInfo] =
    nodeApi.blockAt(height).map(_.head).map(NodeSync.blockInfo)
}
