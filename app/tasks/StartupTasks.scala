package tasks

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs._
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import utils.Globals

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

@Singleton
class StartupTasks @Inject()(system: ActorSystem, config: Configuration, cs: CoordinatedShutdown) {

  private val logger: Logger = LoggerFactory.getLogger("StartupTasks")
  private val contexts: Contexts = new Contexts(system)


  // Check node is live and indexed
  Globals.getNodeConfig.getNodeApi.indexedHeight() match {
    case Failure(exception) =>
      logger.error(s"Got error when checking node during startup", exception)
      logger.error("Aborting startup...")
      throw exception
    case Success(value) =>
      logger.info(s"Got indexed node with heights: ${value}")
  }

  private val nispDB = Globals.nispDB
  private val mdDb = Globals.mdDB
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
