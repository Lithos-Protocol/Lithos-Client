import actors.{MDSynchronizer, PDSynchronizer, RollupCoordinator, RollupSynchronizer, SyncHandler}
import api.{BlocksApi, BlocksApiImpl, CollateralApi, CollateralApiImpl, DexApi, DexApiImpl, InfoApi, InfoApiImpl, MiningApi, MiningApiImpl, PaymentsApi, PaymentsApiImpl}
import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import tasks.{MDSyncTask, PDSyncTask, RollupSyncTask, StartupTasks, StratumServer}
import utils.Globals

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {
    System.out.println("Binding Tasks")
    // Set critical configs and databases
    Globals.setConfigs(configuration)

    // Bindings
    bindActor(classOf[RollupCoordinator], "rollup-coordinator", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[SyncHandler], "sync-handler", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[MDSynchronizer], "md-synchronizer", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[PDSynchronizer], "pd-synchronizer", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))

    bindActorFactory(classOf[RollupSynchronizer], classOf[RollupSynchronizer.RollupSyncFactory])
    bind(classOf[BlocksApi]).to(classOf[BlocksApiImpl])
    bind(classOf[CollateralApi]).to(classOf[CollateralApiImpl])
    bind(classOf[InfoApi]).to(classOf[InfoApiImpl])
    bind(classOf[MiningApi]).to(classOf[MiningApiImpl])
    bind(classOf[PaymentsApi]).to(classOf[PaymentsApiImpl])
    bind(classOf[DexApi]).to(classOf[DexApiImpl])
    bind[StartupTasks](classOf[StartupTasks]).asEagerSingleton()
    bind[StratumServer](classOf[StratumServer]).asEagerSingleton()
    bind[RollupSyncTask](classOf[RollupSyncTask]).asEagerSingleton()
    bind[MDSyncTask](classOf[MDSyncTask]).asEagerSingleton()
    bind[PDSyncTask](classOf[PDSyncTask]).asEagerSingleton()
  }
}