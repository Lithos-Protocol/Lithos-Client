package tasks

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import stratum.ErgoStratumServer
import stratum.data.{Data, Options}
import utils.Globals

import java.math.BigInteger
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
@Singleton
class StartStratumServer @Inject()(system: ActorSystem, config: Configuration, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("StartStratumServer")
  val taskConfig: TaskConfiguration = new TasksConfig(config).stratumServerTaskConfig

  val contexts: Contexts = new Contexts(system)
  val stratumParams: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig     = new StateConfig(config)

  val nodeConfig: NodeConfig = Globals.getNodeConfig
  val commitments = new CommitmentTransactions(nodeConfig, DataBoxSource.Stored)
  if(taskConfig.enabled) {
    logger.info("Starting Stratum Server for Lithos")

    //System.out.println(s"Stratum Server will initiate in ${taskConfig.startup.toString()}")
    val tau: Try[BigInteger] = nodeConfig.getClient.execute {
      ctx =>
        commitments.committedScore(ctx, stratumParams.diff, "stratum mining").map {
          s =>
            LFSMHelpers.convertTauOrScore(s).bigInteger
        }
    }
    tau match {
      case Success(t) =>
        val options = new Options(stratumParams.extraNonce1Size, 256,
          stratumParams.connectionTimeout, stratumParams.blockRefreshInterval,
          nodeConfig.getNodeUrl, t, new Data())
        logger.info("Stratum server starting at port " + stratumParams.stratumPort)
        if(stratumParams.reduceShareMessages) {
          val stratumTau = t.divide(new BigInteger("1000"))
          logger.info("Using reduced share messages. Stratum diff will be 1000x higher than real diff.")
          logger.info(s"Stratum is using tau ${stratumTau}" +
            s" (score: ${LFSMHelpers.convertTauOrScore(stratumTau)})")
        }
        logger.info(s"Using tau ${t} (score: ${LFSMHelpers.convertTauOrScore(t)}) for NISPs")
        val server = new ErgoStratumServer(options, true, true, nodeConfig.getClient,
          nodeConfig.getNodeWallet, nodeConfig.getNodeKey, stratumParams.reduceShareMessages, Globals.nispDB)


        cs.addTask(CoordinatedShutdown.PhaseServiceUnbind, "close-stratum-server"){
          () =>
            logger.info("Stopping stratum server")
            Await.result(Future(server.stopListening())(contexts.stratumContext), 10 seconds)

            Future(Done.getInstance())(contexts.stratumContext)
        }
        cs.addJvmShutdownHook{
          () =>
            logger.info("Stopping stratum server on JVM shutdown")
            server.stopListening();
        }
        system.scheduler.scheduleWithFixedDelay(initialDelay = taskConfig.startup, delay = taskConfig.interval)({
          () =>
            Try{
              if (server.pool.getBlockTemplate)
                logger.info("Found block with polling")
            }.recoverWith {
              case e =>
                logger.error("Encounter error while polling for block templates", e)
                Failure(e)
            }
        })(contexts.stratumContext)



        logger.info(s"Stratum server now listening for connections on port ${stratumParams.stratumPort}")
        Future(server.startListening(stratumParams.stratumPort))(contexts.stratumContext)


      case Failure(e) =>
        logger.error("Failed to start stratum due to error while retrieving difficulty value", e)
    }

  }else{
    logger.info("Stratum server was not enabled")
  }




  /** Important comment don't delete this
   *
   if(numBlocks % 5 == 0){
   Future{
   val tau = nodeConfig.getClient.execute{
   ctx =>
   commitments.committedScore(ctx, stratumParams.diff, "stratum mining").map{
   s =>
   LFSMHelpers.convertTauOrScore(s).bigInteger
   }
   }
   tau match {
   case Success(t) =>
   logger.info(s"Using commitment tau ${t} (diff: ${LFSMHelpers.convertTauOrScore(t)}) for NISPs")
   val stratumTau = Globals.getTau.get
   logger.info(s"Using stratum tau $stratumTau (diff: ${LFSMHelpers.convertTauOrScore(stratumTau)})")

   if(stratumTau != t){
   Globals.setTau(t)
   logger.info("Stratum difficulty updated to match commitment")
   }
   case Failure(e) =>
   logger.error("Failed to check if on-chain diff commitment matches stratum difficulty", e)
   }

   }(contexts.pollingContext)
   }
   if(numBlocks == Int.MaxValue - 1)
   numBlocks = 0
   */
}
