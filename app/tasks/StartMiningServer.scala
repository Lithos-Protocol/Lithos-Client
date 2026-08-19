package tasks

import akka.Done
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import mining.MiningStratumServer
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import stratum.data.{Data, Options}
import utils.Globals

import java.math.BigInteger
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * Eager singleton that bootstraps the actor-based mining server.
 *
 * Mirrors the setup logic of StartStratumServer but creates a MiningStratumServer
 * instead of ErgoStratumServer.  Block-template polling is handled internally by
 * LithosPool, so no external scheduler is registered here.
 *
 * Uses the same `stratum-server` task config key as the legacy server — only one
 * of the two startup tasks should be enabled at a time via application.conf.
 */
@Singleton
class StartMiningServer @Inject()(system: ActorSystem, config: Configuration,
                                  cs: CoordinatedShutdown,
                                  @Named("state-frame") stateFrame: ActorRef,
                                  @Named("transaction-processor") transactionProcessor: ActorRef,
                                  @Named("emission-handler") emissionHandler: ActorRef) {

  val logger: Logger = LoggerFactory.getLogger("StartMiningServer")

  val taskConfig: TaskConfiguration = new TasksConfig(config).stratumServerTaskConfig
  val stratumParams: StratumConfig  = new StratumConfig(config)
  val nodeConfig: NodeConfig        = Globals.getNodeConfig
  val commitments                   = new CommitmentTransactions(nodeConfig, DataBoxSource.Stored)
  val contexts: Contexts            = new Contexts(system)

  if (taskConfig.enabled) {
    logger.info("Starting actor-based Mining Server")

    // Retrieve the committed difficulty (tau) from the on-chain state, identical
    // to the approach used by StartStratumServer.
    val tau: Try[BigInteger] = {
      if(!stratumParams.forceConfigDifficulty) {
        nodeConfig.getClient.execute { ctx =>
          commitments.committedScore(ctx, stratumParams.diff, "stratum mining").map { s =>
            LFSMHelpers.convertTauOrScore(s).bigInteger
          }
        }
      }else{
        logger.warn("forceConfigDiff is enabled in the configuration file.")
        logger.warn("NISPs mined and submitted in this manner may be considered fraudulent")
        Try{
            LFSMHelpers.parseDiffValueForStratum(stratumParams.diff).get
        }
      }
    }

    tau match {
      case Success(t) =>
        val options = new Options(
          stratumParams.extraNonce1Size,
          256,
          stratumParams.connectionTimeout,
          stratumParams.blockRefreshInterval,
          nodeConfig.getNodeUrl,
          t,
          new Data()
        )


        if (stratumParams.reduceShareMessages) {
          val displayTau = t.divide(new BigInteger("1000"))
          logger.info("Reduced share messages enabled — stratum diff is 1000x real diff")
          logger.info(s"Stratum tau: $displayTau (score: ${LFSMHelpers.convertTauOrScore(displayTau)})")
        }
        logger.info(s"Using tau $t (score: ${LFSMHelpers.convertTauOrScore(t)}) for NISPs")

        val server = new MiningStratumServer(
          system          = system,
          options         = options,
          useCollateral   = true,
          client          = nodeConfig.getClient,
          prover          = nodeConfig.getNodeWallet,
          apiKey          = nodeConfig.getNodeKey,
          reducedShareMessages = stratumParams.reduceShareMessages,
          nispDB          = Globals.nispDB,
          stateFrame      = stateFrame,
          forceConfigDiff = stratumParams.forceConfigDifficulty,
          diffRefreshInterval = stratumParams.diffRefreshInterval,
          candidateConfig = stratumParams.candidate,
          // Asked in this order for this miner's own block, so rollup work — submissions and fraud
          // proofs first — gets the slots ahead of collateral-queue maintenance.
          txSources       = Seq(transactionProcessor, emissionHandler),
          rotateExtraNonceInterval = stratumParams.rotateExtraNonceInterval
        )

        // ── shutdown hooks ───────────────────────────────────────────────────
        cs.addTask(CoordinatedShutdown.PhaseServiceUnbind, "close-mining-server") { () =>
          logger.info("Stopping actor-based mining server")
          Await.result(Future(server.stopListening())(contexts.stratumContext), 10 seconds)
          Future(Done.getInstance())(contexts.stratumContext)
        }
        cs.addJvmShutdownHook { () =>
          logger.info("Stopping actor-based mining server on JVM shutdown")
          server.stopListening()
        }

        // ── start TCP listener ───────────────────────────────────────────────
        // LithosPool.preStart handles node connectivity checks and the initial
        // block template fetch, so startListening is all that is needed here.
        logger.info(s"Mining server listening on port ${stratumParams.stratumPort}")
        Future(server.startListening(stratumParams.stratumPort))(contexts.stratumContext)

      case Failure(e) =>
        logger.error("Failed to start mining server: could not retrieve on-chain difficulty", e)
    }
  } else {
    logger.info("Mining server was not enabled")
  }
}
