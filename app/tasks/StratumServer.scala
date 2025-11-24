package tasks

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown, actorRef2Scala}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import configs.{Contexts, NodeConfig, StateConfig, StratumConfig, TasksConfig}
import configs.TasksConfig.TaskConfiguration
import lfsm.LFSMHelpers
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import stratum.ErgoStratumServer
import stratum.data.{Data, Options}
import utils.Helpers

import java.time.LocalDateTime
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
@Singleton
class StratumServer @Inject()(system: ActorSystem, config: Configuration, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("StratumServer")
  val taskConfig: TaskConfiguration = new TasksConfig(config).stratumServerTaskConfig

  val contexts: Contexts = new Contexts(system)
  val stratumParams: StratumConfig = new StratumConfig(config)
  val nodeConfig: NodeConfig = new NodeConfig(config)
  val stateConfig: StateConfig     = new StateConfig(config)
  if(taskConfig.enabled) {
    logger.info("Starting Stratum Server for Lithos")

    //System.out.println(s"Stratum Server will initiate in ${taskConfig.startup.toString()}")
    val tau = LFSMHelpers.parseDiffValueForStratum(stratumParams.diff)
    tau match {
      case Success(t) =>
        val options = new Options(stratumParams.extraNonce1Size, 256,
          stratumParams.connectionTimeout, stratumParams.blockRefreshInterval,
          nodeConfig.getNodeApi, t, new Data())
        logger.info("Stratum server starting at port " + stratumParams.stratumPort)
        if(stratumParams.reduceShareMessages)
          logger.info("Using reduced share messages. Stratum diff will be 1000x lower than real diff.")
        val server = new ErgoStratumServer(options, true, true, nodeConfig.getClient,
          nodeConfig.prover, nodeConfig.getNodeKey, stratumParams.reduceShareMessages)


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
       // System.out.println("Fully stopped listening")

      case Failure(e) =>
        System.out.println("Failed to start stratum due to invalid difficulty value")
    }

  }else{
    logger.info("Stratum server was not enabled")
  }

  private def stopStratum(server: ErgoStratumServer): Unit = {

    System.out.println("Stopping stratum server")
    server.stopListening()
  }

  def startStratum(): Unit = {

    implicit val taskContext: ExecutionContext = contexts.stratumContext


  }


}
