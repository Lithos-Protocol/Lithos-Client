import api._
import com.google.inject.AbstractModule
import configs.{ConfigValidationException, NodeContext}
import org.slf4j.LoggerFactory
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import state.synchronization._
import tasks.{MDSyncTask, RollupSyncTask, StartMiningServer, StartupTasks}
import transactions.emissions.EmissionHandler
import transactions.rollups.{RollupEvaluator, SubmissionHandler, TransactionProcessor, TransactionPublisher}
import transactions.wallet.WalletManager
import utils.Globals

import scala.util.Try

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {

    // Every config is validated here, before anything binds, so a bad value stops startup with one
    // readable report naming every offending key instead of a crash inside whichever actor read it.
    try{
      configs.Configs.validateAll(configuration)
    } catch {
      case c: ConfigValidationException =>
        LoggerFactory.getLogger("Module").error("Got error during startup: ", c)
        Thread.sleep(1000)
        System.exit(0)
    }

    // Set critical configs and databases
    Globals.setConfigs(configuration)

    // Singular NodeContext to work from
    bind(classOf[NodeContext]).toInstance(Globals.getNodeConfig)

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
    bind(classOf[CollateralApi]).to(classOf[CollateralApiImpl])
    bind(classOf[InfoApi]).to(classOf[InfoApiImpl])
    bind(classOf[MiningApi]).to(classOf[MiningApiImpl])
    bind(classOf[PaymentsApi]).to(classOf[PaymentsApiImpl])
    bind(classOf[LithosDexApi]).to(classOf[LithosDexApiImpl])
    bind[StartupTasks](classOf[StartupTasks]).asEagerSingleton()
    //bind[StartStratumServer](classOf[StartStratumServer]).asEagerSingleton()
    bind[StartMiningServer](classOf[StartMiningServer]).asEagerSingleton()
    bind[RollupSyncTask](classOf[RollupSyncTask]).asEagerSingleton()
    bind[MDSyncTask](classOf[MDSyncTask]).asEagerSingleton()

  }
}
