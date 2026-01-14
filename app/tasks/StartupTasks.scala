package tasks

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
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
class StartupTasks @Inject()(system: ActorSystem, config: Configuration, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("StartupTasks")
  val contexts: Contexts = new Contexts(system)
  val nispDB = Globals.nispDB
  val mdDb = Globals.mdDB
  cs.addTask(CoordinatedShutdown.PhaseServiceUnbind, "close-db"){
    () =>
      logger.info("Closing DBs")
      Await.result(Future(nispDB.close())(contexts.pollingContext), 10 seconds)
      Await.result(Future(mdDb.close())(contexts.pollingContext), 10 seconds)
      Future(Done.getInstance())(contexts.pollingContext)
  }

  cs.addJvmShutdownHook{
    () =>
      logger.info("Stopping dbs on JVM shutdown")
      nispDB.close()
      mdDb.close()
  }


}
