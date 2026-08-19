import transactions.emissions.EmissionHandler
import transactions.rollups.{RollupEvaluator, SubmissionHandler, TransactionProcessor, TransactionPublisher}
import transactions.wallet.WalletManager
import api.{BlocksApi, BlocksApiImpl, CollateralApi, CollateralApiImpl, DexApi, DexApiImpl, InfoApi, InfoApiImpl, LithosDexApi, LithosDexApiImpl, MiningApi, MiningApiImpl, PaymentsApi, PaymentsApiImpl}
import com.google.inject.AbstractModule
import configs.NodeContext
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import state.synchronization.{MDSynchronizer, MempoolView, PDSynchronizer, RollupCoordinator, RollupSynchronizer, StateFrame, SyncHandler}
import tasks.{MDSyncTask, PDSyncTask, RollupSyncTask, StartMiningServer, StartupTasks, StartStratumServer}
import utils.Globals

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {
    System.out.println("Binding Tasks")
    // Set critical configs and databases
    Globals.setConfigs(configuration)

    // The one NodeConfig, handed to whoever asks for a NodeContext. Bound to the instance Globals
    // already built rather than to the class, so Guice cannot construct a second: each one unlocks
    // secret storage, builds its own prover and opens its own client, and two would derive lender
    // keys from separate sequences.
    bind(classOf[NodeContext]).toInstance(Globals.getNodeConfig)

    // Bindings
    bindActor(classOf[StateFrame], "state-frame", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MempoolView], "mempool-view", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[RollupCoordinator], "rollup-coordinator", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[SyncHandler], "sync-handler", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MDSynchronizer], "md-synchronizer", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[PDSynchronizer], "pd-synchronizer", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))

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
    bind(classOf[DexApi]).to(classOf[DexApiImpl])
    bind(classOf[LithosDexApi]).to(classOf[LithosDexApiImpl])
    bind[StartupTasks](classOf[StartupTasks]).asEagerSingleton()
    //bind[StartStratumServer](classOf[StartStratumServer]).asEagerSingleton()
    bind[StartMiningServer](classOf[StartMiningServer]).asEagerSingleton()
    bind[RollupSyncTask](classOf[RollupSyncTask]).asEagerSingleton()
    bind[MDSyncTask](classOf[MDSyncTask]).asEagerSingleton()
    bind[PDSyncTask](classOf[PDSyncTask]).asEagerSingleton()
  }
}
