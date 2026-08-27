import api._
import com.google.inject.AbstractModule
import configs.{ConfigValidationException, NodeContext}
import org.slf4j.LoggerFactory
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import state.synchronization._
import state.persistence.StateSnapshotActor
import tasks.{MDSyncTask, RollupSyncTask, StartMiningServer, StartupTasks}
import transactions.emissions.EmissionHandler
import transactions.rollups.{DataBoxSource, RollupEvaluator, SubmissionHandler, TransactionProcessor, TransactionPublisher}
import transactions.wallet.WalletManager
import utils.Globals

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {

    // Validate all configuration before constructing components that depend on it.
    haltOnConfigProblem {
      configs.Configs.validateAll(configuration)

      // Convert NodeConfig startup failures into the same concise configuration report.
      Globals.setConfigs(configuration)
    }

    // Share one NodeContext across all consumers.
    bind(classOf[NodeContext]).toInstance(Globals.getNodeConfig)

    // Compile contracts once because the Sigma compiler mutates shared state during construction.
    bind(classOf[SyncProtocolContext]).toInstance {
      val nodeContext = Globals.getNodeConfig
      SyncProtocolContext(nodeContext.getNetwork, new configs.SyncConfig(configuration).startHeight,
        nodeContext.getNodeWallet.contract.hashedPropBytes)
    }

    // Bind the disk-backed data-box source so consumers can substitute it in tests.
    bind(classOf[DataBoxSource]).toInstance(DataBoxSource.Stored)

    // Bindings
    bindActor(classOf[StateSnapshotActor], "state-snapshot", p => p.withDispatcher("lithos-contexts.database-dispatcher"))
    bindActor(classOf[StateFrame], "state-frame", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MempoolView], "mempool-view", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[SyncHandler], "sync-handler", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))

    bindActor(classOf[WalletManager],        "wallet-manager",        p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[SubmissionHandler],    "submission-handler",    p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[RollupEvaluator],      "rollup-evaluator",      p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[TransactionProcessor], "transaction-processor", p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[TransactionPublisher], "transaction-publisher", p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[EmissionHandler],      "emission-handler",      p => p.withDispatcher("lithos-contexts.tx-dispatcher"))

    bind(classOf[BlocksApi]).to(classOf[BlocksApiImpl])
    bind(classOf[CollateralMarketApi]).to(classOf[CollateralMarketApiImpl])
    bind(classOf[InfoApi]).to(classOf[InfoApiImpl])
    bind(classOf[MiningApi]).to(classOf[MiningApiImpl])
    bind(classOf[PaymentsApi]).to(classOf[PaymentsApiImpl])
    bind(classOf[LithosDexApi]).to(classOf[LithosDexApiImpl])
    bind(classOf[WalletApi]).to(classOf[WalletApiImpl])
    bind[StartupTasks](classOf[StartupTasks]).asEagerSingleton()
    //bind[StartStratumServer](classOf[StartStratumServer]).asEagerSingleton()
    bind[StartMiningServer](classOf[StartMiningServer]).asEagerSingleton()
    bind[RollupSyncTask](classOf[RollupSyncTask]).asEagerSingleton()
    bind[MDSyncTask](classOf[MDSyncTask]).asEagerSingleton()

  }

  /** Reports configuration failures, flushes the logger, and exits with a failure status. */
  private def haltOnConfigProblem[A](f: => A): A =
    try f
    catch {
      case c: ConfigValidationException =>
        LoggerFactory.getLogger("Module").error("Got error during startup: ", c)
        removeOwnPidFile()
        Thread.sleep(1000)
        System.exit(1)
        throw c
    }

  /** Removes Play's PID file after early startup failure, but only when it names this process. */
  private def removeOwnPidFile(): Unit = {
    val logger = LoggerFactory.getLogger("Module")
    Try {
      val configured = configuration.getOptional[String]("play.server.pidfile.path").map(_.trim)
      // Play's sentinel for "write no pid file at all".
      if (!configured.contains("/dev/null")) {
        val path = configured.filter(_.nonEmpty).map(Paths.get(_)).getOrElse {
          val dir = sys.props.getOrElse("play.server.dir", sys.props.getOrElse("user.dir", "."))
          Paths.get(dir).resolve("RUNNING_PID")
        }
        // Java 8 exposes the process identifier as the prefix of "<pid>@<host>".
        val ownPid = ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')
        if (Files.exists(path)) {
          val recorded = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim
          if (recorded == ownPid) {
            Files.delete(path)
            logger.info(s"Removed $path so the next start is not refused")
          } else
            logger.warn(s"Left $path in place: it names pid $recorded, not this process ($ownPid)")
        }
      }
    }.failed.foreach(ex =>
      // PID cleanup failure must not replace the original configuration error.
      logger.warn(s"Could not remove Play's RUNNING_PID file: ${ex.getMessage}"))
  }
}
