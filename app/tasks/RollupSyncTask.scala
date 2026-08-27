package tasks

import akka.actor.{ActorRef, ActorSystem}
import configs.{Contexts, TasksConfig}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.StateFrameMessages.StartSynchronization

import javax.inject.{Inject, Named, Singleton}

/** Starts the single canonical synchronization pipeline after the configured startup delay. */
@Singleton
class RollupSyncTask @Inject()(system: ActorSystem,
                               config: Configuration,
                               @Named("state-frame") stateFrame: ActorRef) {

  private val logger: Logger = LoggerFactory.getLogger("RollupSyncTask")
  private val taskConfig = new TasksConfig(config).rollupSyncTask
  private val pollingContext = new Contexts(system).pollingContext

  if (taskConfig.enabled) {
    logger.info(s"Canonical synchronization will start after ${taskConfig.startup}")
    system.scheduler.scheduleOnce(taskConfig.startup, stateFrame, StartSynchronization)(pollingContext, ActorRef.noSender)
  } else logger.info("Canonical synchronization is disabled")
}
