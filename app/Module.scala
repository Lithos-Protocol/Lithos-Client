import api._
import com.google.inject.AbstractModule
import configs.{ConfigValidationException, NodeContext}
import org.slf4j.LoggerFactory
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import state.synchronization._
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

    // Every config is validated here, before anything binds, so a bad value stops startup with one
    // readable report naming every offending key instead of a crash inside whichever actor read it.
    haltOnConfigProblem {
      configs.Configs.validateAll(configuration)

      // Inside the same handler: NodeConfig's constructor raises the same exception for the four
      // errors a user hits most - a bad networkType, an unreadable keystore, a wrong password, an
      // unreachable node. Outside it those surfaced as a Guice CreationException with the report
      // buried in the stack trace, which is the outcome validateAll exists to avoid.
      Globals.setConfigs(configuration)
    }

    // Singular NodeContext to work from
    bind(classOf[NodeContext]).toInstance(Globals.getNodeConfig)

    // The miner-dictionary data box, which lives in a disk-backed singleton. Bound rather than
    // reached through Globals so SubmissionHandler can be built against a supplied one.
    bind(classOf[DataBoxSource]).toInstance(DataBoxSource.Stored)

    // Bindings
    bindActor(classOf[StateFrame], "state-frame", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MempoolView], "mempool-view", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[RollupCoordinator], "rollup-coordinator", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[SyncHandler], "sync-handler", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MDSynchronizer], "md-synchronizer", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))

    bindActor(classOf[WalletManager],        "wallet-manager",        p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[SubmissionHandler],    "submission-handler",    p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[RollupEvaluator],      "rollup-evaluator",      p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[TransactionProcessor], "transaction-processor", p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[TransactionPublisher], "transaction-publisher", p => p.withDispatcher("lithos-contexts.tx-dispatcher"))
    bindActor(classOf[EmissionHandler],      "emission-handler",      p => p.withDispatcher("lithos-contexts.tx-dispatcher"))

    bindActorFactory(classOf[RollupSynchronizer], classOf[RollupSynchronizer.RollupSyncFactory])

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

  /**
   * Print a configuration problem and stop, rather than letting it become a Guice stack trace.
   *
   * Exits non-zero: a config the user has to fix is a failed start, and a supervisor reading 0
   * treats it as a clean shutdown and neither restarts nor alarms. The sleep is there so the
   * asynchronous appender flushes the report before the JVM goes.
   */
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

  /**
   * Delete Play's RUNNING_PID before an early exit.
   *
   * Play writes it before the application starts and removes it from the server's own stop path.
   * Exiting from inside `configure` never reaches that, so the file outlives the process and the
   * next start refuses with "This application is already running" - one config mistake turning into
   * a second, unrelated one that reads like a stuck instance.
   *
   * Only if it holds THIS pid. A file naming another process belongs to a client that really is
   * running, and deleting it would let a second one start over the top.
   */
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
        // getName is "<pid>@<host>" on every JVM this runs on; Java 8 has no ProcessHandle.
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
      // Never in place of the report above: a pid file that survives costs one manual delete,
      // whereas losing the reason the client stopped costs the user the whole diagnosis.
      logger.warn(s"Could not remove Play's RUNNING_PID file: ${ex.getMessage}"))
  }
}
