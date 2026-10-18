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
                                  @Named("transaction-engine") emissionHandler: ActorRef,
                                  @Named("ergodex-batcher") ergoDexBatcher: ActorRef,
                                  @Named("lithosdex-batcher") lithosDexBatcher: ActorRef,
                                  @Named("stats-collector") statsCollector: ActorRef) {

  val logger: Logger = LoggerFactory.getLogger("StartMiningServer")

  val taskConfig: TaskConfiguration = new TasksConfig(config).stratumServerTaskConfig
  val stratumParams: StratumConfig  = new StratumConfig(config)
  val nodeConfig: NodeConfig        = Globals.getNodeConfig
  val commitments                   = new CommitmentTransactions(nodeConfig, DataBoxSource.Stored)
  val contexts: Contexts            = new Contexts(system)
  private val statsConfig = StatsConfig(config)

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
          // Must be the multiplier the job itself advertises, or this banner reports a difficulty
          // no miner is ever served.
          val multiplier = stratumParams.reductionMultiplier
          val displayTau = t.divide(BigInteger.valueOf(multiplier))
          logger.info(s"Reduced share messages enabled: stratum diff is ${multiplier}x real diff")
          logger.info(s"Stratum tau: $displayTau (score: ${LFSMHelpers.convertTauOrScore(displayTau)})")
        }
        logger.info(s"Using tau $t (score: ${LFSMHelpers.convertTauOrScore(t)}) for NISPs")

        // Started only when enabled: the walk is a standing timer against the node, and a source
        // that is off is never asked for anything either.
        def limitsFor(name: String): configs.CandidateSourceConfig =
          stratumParams.candidate.sources.getOrElse(name, configs.CandidateConfig.Default.sources(name))
        val rentLimits = limitsFor(configs.CandidateSourceConfig.Rent)
        val rentSource = if (!rentLimits.enabled) None else Some(
          mining.MiningMessages.CandidateSource(configs.CandidateSourceConfig.Rent,
            system.actorOf(akka.actor.Props(new transactions.rent.StorageRentSource(
              nodeConfig, configs.RentConfig(config), rentLimits,
              stratumParams.candidate.useTruePropCollection)), "storage-rent-source")))

        // Batchers run from Module either way; this only decides whether the stratum asks them.
        def batcherSource(name: String, batcher: ActorRef): Option[mining.MiningMessages.CandidateSource] =
          if (!transactions.batching.Batcher.servesCandidates(config, name)) None
          else Some(mining.MiningMessages.CandidateSource(name, batcher))
        val lithosDexSource = batcherSource(configs.CandidateSourceConfig.LithosDex, lithosDexBatcher)
        val ergoDexSource = batcherSource(configs.CandidateSourceConfig.ErgoDex, ergoDexBatcher)

        // Upkeep: keyless maintenance of other protocols' boxes, off by default and never spending
        // this wallet. No actor exists unless the source is enabled with `maxTxs` above 0, config turns on a
        // job, and the stratum builds block transactions at all, so nothing scans a node for a source
        // that is never asked.
        val upkeepLimits = limitsFor(configs.CandidateSourceConfig.Upkeep)
        val upkeepConfig = configs.UpkeepConfig(config)
        val upkeepJobs =
          if (upkeepLimits.enabled && upkeepLimits.maxTxs > 0 && stratumParams.candidate.blockTransactions)
            transactions.upkeep.UpkeepRegistry.enabled(upkeepConfig)
          else Seq.empty[transactions.upkeep.UpkeepJob]
        val upkeepSource = if (!transactions.upkeep.UpkeepSource.runs(upkeepLimits, upkeepJobs)) None else {
          // Built here rather than inside the actor, so every incarnation after a restart shares it
          // and a refused box is not offered to the node again.
          val memory = new transactions.upkeep.UpkeepSource.Memory(upkeepConfig.retryAfterScans)
          Some(mining.MiningMessages.CandidateSource(configs.CandidateSourceConfig.Upkeep,
            system.actorOf(akka.actor.Props(new transactions.upkeep.UpkeepSource(
              nodeConfig, upkeepConfig, upkeepLimits, upkeepJobs, memory,
              stratumParams.candidate.useTruePropCollection,
              blockShare = stratumParams.candidate.blockShare)), "upkeep-source")))
        }
        // The builder bounds each source's answer by its limits again, so an opportunistic upkeep
        // share is let through here; with space = "fixed" this is the configured block unchanged.
        val candidateConfig = stratumParams.candidate.copy(sources = stratumParams.candidate.sources.map {
          case (name, limits) if name == configs.CandidateSourceConfig.Upkeep => name -> upkeepConfig.allowance(limits)
          case other => other
        })

        val server = new MiningStratumServer(
          system          = system,
          options         = options,
          useCollateral   = true,
          client          = nodeConfig.getClient,
          prover          = nodeConfig.getNodeWallet,
          apiKey          = nodeConfig.getNodeKey,
          reducedShareMessages = stratumParams.reduceShareMessages,
          reductionMultiplier = stratumParams.reductionMultiplier,
          nispDB          = Globals.nispDB,
          stateFrame      = stateFrame,
          forceConfigDiff = stratumParams.forceConfigDifficulty,
          diffRefreshInterval = stratumParams.diffRefreshInterval,
          candidateConfig = candidateConfig,
          // Asked in this order for this miner's own block, so rollup work — submissions and fraud
          // proofs first — gets the slots ahead of collateral-queue maintenance.
          txSources       = Seq(
            mining.MiningMessages.CandidateSource(
              configs.CandidateSourceConfig.Rollups, transactionProcessor),
            mining.MiningMessages.CandidateSource(
              configs.CandidateSourceConfig.Emissions, emissionHandler)) ++ rentSource ++ lithosDexSource ++
            ergoDexSource ++ upkeepSource, // last: it earns the least of the fee-less sources
          rotateExtraNonceInterval = stratumParams.rotateExtraNonceInterval,
          statsCollector = if (statsConfig.enabled) Some(statsCollector) else None,
          statsRefreshIntervalMs = statsConfig.refreshIntervalMs
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
        // block template fetch, so startListening is all that is needed here. A failed bind or accept
        // loop leaves rigs unable to connect, so it is logged rather than lost with the Future.
        val bindAddress = stratumParams.bindAddress
        val port        = stratumParams.stratumPort
        Future(server.startListening(port, bindAddress))(contexts.stratumContext)
          .failed.foreach { e =>
            logger.error(s"Stratum is not accepting rigs on $bindAddress, port $port: ${e.getMessage}. " +
              "Rigs cannot connect until the client restarts; check stratum.bindAddress and stratum.stratumPort", e)
          }(contexts.stratumContext)

      case Failure(e) =>
        logger.error("Failed to start mining server: could not retrieve on-chain difficulty", e)
    }
  } else {
    logger.info("Mining server was not enabled")
  }
}
