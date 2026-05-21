package actors

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import cache.RollupCache
import configs.{NodeConfig, StateConfig, StratumConfig}
import lfsm.LFSMPhase
import lfsm.states.NISPTree
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import sigma.data.AvlTreeFlags
import state.AutoSubscribable.AutoSubscribe
import state.Subscribable.{Subscribe, SubscribeAck, SubscribeRejected}
import state.messages.DictionaryMessages.DictionaryTransform
import state.{LFSMTransformer, Subscribable}
import state.messages.MempoolMessages.{RebuildMempoolChains, ResetMempoolState}
import state.messages.StateFrameMessages.NewBlock
import state.messages.{BlockInfo, BlockMessage, BlockTx}
import state.messages.RollupMessages._
import state.messages.SyncMessages._
import utils.{Globals, Helpers}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
 * Actor whose goal is to process blocks and route transforms to the appropriate area. During initial synchronization,
 * this actor will only deal with rollups. Post-sync, the actor will accept subscriptions with token information
 * to route transforms to non-rollup sync actors after blocks have been parsed.
 * @param config
 * @param rollupCoordinator
 * @param cacheApi
 */
class SyncHandler @Inject()(config: Configuration, @Named("rollup-coordinator") rollupCoordinator: ActorRef,
                            cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport with Subscribable{
  implicit val timeout: Timeout = 5 seconds


  val nodeConfig: NodeConfig         = Globals.getNodeConfig
  val client: ErgoClient             = nodeConfig.getClient
  val prover: NodeWallet             = nodeConfig.getNodeWallet
  lazy val relErgoTrees: Seq[String] = Helpers.rollupErgoTrees(client)
  val rollupCache: RollupCache       = RollupCache(cacheApi)
  val stratumConfig: StratumConfig   = new StratumConfig(config)
  val stateConfig: StateConfig       = new StateConfig(config)

  override val logger: Logger         = LoggerFactory.getLogger("SyncHandler")
  // SyncHandler subscribers are synchronizers which are receiving non-rollup transforms or updates
  override var subscribers: Set[ActorRef] = Set.empty
  override var currentHeight: Int = -1
  private var tokenMap: Map[String, ActorRef] = Map.empty[String, ActorRef]
  import context.dispatcher


  override def receive: Receive = rejectSubscriptions orElse {
    case BlockMessage(blockInfo) =>
      logger.info(s"Got block message ${blockInfo.id} with height ${blockInfo.height}")
      currentHeight = blockInfo.height
      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(rollupCoordinator ! _)
      relevantTransactions.genTxs.foreach(rollupCoordinator ! _)
    case GetSynced =>
      sender() ! NoRollups()
      logger.warn("Tried to GetSynced during initial synchronization, returned NoRollups()")
    case CompletedInitSync =>
      logger.info("Got initial sync completion message")
      rollupCoordinator ! CompletedInitSync
      logger.info("SyncHandler will now accept subscriptions for transform routing")
      context.become(postSync())
  }

  /**
   * SyncHandler will reject subscriptions it receives until its own subscription to StateFrame is accepted.
   */
  private val rejectSubscriptions: Receive = {
    case Subscribe(_, _, _) =>
      sender() ! SubscribeRejected("Not synced: Can't accept subscribers until SyncHandler has fully synchronized")
    case AutoSubscribe(_) =>
      sender() ! SubscribeRejected("Can't auto-subscribe: SyncHandler does not accept auto-subscriptions")
  }
  /**
   * When subscribing, a tokenId must be passed in so that relevant transforms may be tracked
   */
  override protected val handleSubscriptions: Receive = {
    case Subscribe(height, subscriber, additionalInfo) =>
      val requester = sender()
      if(additionalInfo.isDefined) {
        if (height == currentHeight) {
          tokenMap = tokenMap + (additionalInfo.get -> subscriber)
          subscribers = subscribers + subscriber
          requester ! SubscribeAck
          logger.info(s"Accepted subscription of ${subscriber.path.name} at" +
            s" height $height (${subscribers.size} total subscriber(s)) with token ${additionalInfo.get}")
        } else {
          requester ! SubscribeRejected(
            s"Height mismatch: subscriber is at $height but publisher is at $currentHeight"
          )
          logger.warn(s"Rejected subscription of ${subscriber.path.name}: height $height != $currentHeight")
        }
      } else {
        requester ! SubscribeRejected(
          s"Can't track: subscriber did not provide a tokenId in additionalInfo"
        )
        logger.warn(s"Rejected subscription of ${subscriber.path.name}: No tokenId provided")
      }
    case AutoSubscribe(_) =>
      sender() ! SubscribeRejected("Can't auto-subscribe: SyncHandler does not accept auto-subscriptions")
  }


  private def postSync(): Receive = handleSubscriptions orElse {
    case BlockMessage(blockInfo) =>
      currentHeight = blockInfo.height
      logger.info(s"Post-sync block message ${blockInfo.id} with height ${blockInfo.height}")
      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(rollupCoordinator ! _)
      relevantTransactions.genTxs.foreach(rollupCoordinator ! _)
      sender() ! HandledBlock(blockInfo)

    case NewBlock(blockInfo) =>
      currentHeight = blockInfo.height
      logger.info(s"StateFrame block ${blockInfo.id} at height ${blockInfo.height}")
      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(rollupCoordinator ! _)
      relevantTransactions.genTxs.foreach(rollupCoordinator ! _)
      relevantTransactions.dictionaryMap.foreach{
        dm =>
          if(dm._2.nonEmpty)
            dm._2.foreach(tokenMap(dm._1) ! _)
      }
      // RollupCoordinator can send this back to MempoolView to signify that all transforms are pre-applied
      rollupCoordinator ! RebuildMempoolChains
//      if (!stateConfig.disableTransforms.getOrElse(false)) {
//        val handler = self
//        (rollupCoordinator ? GetSynced).mapTo[SyncMessage].onComplete {
//          case Failure(ex) =>
//            logger.error("Failed to query sync state for transforms after NewBlock", ex)
//          case Success(FullSync(nispTrees)) =>
//            Future(LFSMTransformer.onSync(client, cacheApi, prover, stratumConfig.diff, nispTrees, handler,
//              stateConfig.autoCommit.getOrElse(true), stateConfig.autoCollat, stateConfig.maxCollat)).failed.foreach { ex =>
//              logger.error(s"Error during onSync transforms: ${ex.getMessage}", ex)
//            }
//          case Success(PartialSync(syncedTrees, _)) =>
//            Future(LFSMTransformer.onSync(client, cacheApi, prover, stratumConfig.diff, syncedTrees, handler,
//              stateConfig.autoCommit.getOrElse(true), stateConfig.autoCollat, stateConfig.maxCollat)).failed.foreach { ex =>
//              logger.error(s"Error during onSync transforms: ${ex.getMessage}", ex)
//            }
//          case Success(NoRollups()) =>
//            logger.info("No rollups present, skipping transforms")
//        }
//      }

    case GetSynced =>
      val originalSender = sender()
      (rollupCoordinator ? GetSynced).mapTo[SyncMessage].onComplete {
        case Failure(exception) =>
          logger.error("Got error while asking for sync", exception)
        case Success(msg) =>
          originalSender ! msg
      }
    case getCurrentRollup: GetCurrentRollup =>
      rollupCoordinator.forward(getCurrentRollup)
    case updateEvaluation: UpdateEvaluation =>
      rollupCoordinator ! updateEvaluation
    case resetMempoolState: ResetMempoolState =>
      rollupCoordinator ! resetMempoolState
    case removeTree: RemoveRollup =>
      logger.info(s"Removing rollup ${removeTree.blockId} due to: ${removeTree.reason}")
      rollupCoordinator ! removeTree
  }
  // efficiency matters here
  private def buildRelevantTransactions(blockInfo: BlockInfo): RelevantTransactions = {
    client.execute{
      ctx =>
        blockInfo.txs.foldLeft((RelevantTransactions.empty, Set.empty[String])){
          (relAcc, tx) =>
            val rel = relAcc._1
            val trackedOutputs = relAcc._2
            if(hasRollupInput(tx)) {
              // We can only add an output to tracking if we know it is a rollup output. That way we avoid
              // adding final payout boxes.
              val nextTrackedOutputs = if(hasRollupOutput(tx)) trackedOutputs + tx.outputs.head.id else trackedOutputs

              rel.copy(transforms = rel.transforms ++ Seq(RollupTransform(blockInfo, tx))) -> nextTrackedOutputs
            } else {
              val optGenesis = makeGenesis(ctx, blockInfo, tx)
              if (optGenesis.isDefined) {
                // We don't need to add to tracked outputs since spending a genesis will always lead to having a rollup
                // output
                rel.copy(genTxs = rel.genTxs ++ Seq(Genesis(optGenesis.get, blockInfo))) -> trackedOutputs
              } else if (hasRollupOutput(tx)) {
                rel.copy(transforms = rel.transforms ++ Seq(RollupTransform(blockInfo, tx))) -> (trackedOutputs + tx.outputs.head.id)
              } else if(hasChainedOutput(tx, trackedOutputs)) {
                // UNDER NO CIRCUMSTANCE should you add the output of a rollup picked up this way
                // to tracked outputs, as there is no guarantee the resulting output is a rollup

                // In general, trackedOutputs set is only needed for payout txs which spend the entire rollup
                // and are chained off of another payout transaction of the same rollup. hasRollupOutput MUST
                // be used in all other cases to avoid critical sync failures.
                rel.copy(transforms = rel.transforms ++ Seq(RollupTransform(blockInfo, tx))) -> trackedOutputs
              } else {
                // Is non-rollup transform
                val singleton = tx.outputs.head.assets.headOption
                singleton match {
                  case Some(nft) =>
                    val nftId = nft.id.toString
                    if(tokenMap.contains(nftId)){
                      rel.copy(
                        dictionaryMap = rel.dictionaryMap +
                          (nftId -> (rel.dictionaryMap.getOrElse(nftId, Seq.empty) :+ DictionaryTransform(blockInfo, tx)))
                      ) -> trackedOutputs
                    } else {
                      rel -> trackedOutputs
                    }
                  case None =>
                    rel -> trackedOutputs
                }
              }
            }
        }._1
    }
  }

  private def makeGenesis(ctx: BlockchainContext, blockInfo: BlockInfo, tx: BlockTx): Option[NISPTree] = {
    // Is holding contract
    tx.outputs.find(o => o.ergoTree == relErgoTrees.head).flatMap{
      output =>
        val numMiners = output.registers.applyOrElse(1, "none")
        numMiners match {
          case "none" =>
            logger.warn("Got malformed holding output")
            None
          case ergoVal: String =>
            //TODO: Add checks for collateral utxo and others to prevent sync issues from malformed transactions
            if (ergoVal == ErgoValue.of(0).toHex) {
              val holdingBox = output.toInput(ctx)
              val newTree    = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
              Some(
                NISPTree(newTree, 0, BigInt(0), Some(holdingBox.registers(3).getValue.asInstanceOf[Long]),
                  holdingBox.value, holdingBox.registers(3).getValue.asInstanceOf[Long].toInt, hasMiner = false, LFSMPhase.HOLDING,
                  blockId = blockInfo.id, utxoId = output.id)
              )
            } else {
              //logger.warn("Got malformed holding output")
              None
            }
        }
    }
  }
  private def hasRollupOutput(tx: BlockTx): Boolean = {
    relErgoTrees.contains(tx.outputs.head.ergoTree)
  }
  // TODO: Analyze closely for potentially dropped blocks

  private def hasRollupInput(tx: BlockTx): Boolean = {
    rollupCache.getTreeSet.contains(tx.inputs.head.id)
  }

  private def hasChainedOutput(tx: BlockTx, trackedOutputs: Set[String]) = {
    trackedOutputs.contains(tx.inputs.head.id)
  }

}
