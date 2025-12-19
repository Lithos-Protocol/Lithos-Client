import actors.{SyncCoordinator, SyncHandler, TreeSynchronizer}
import api.{BlocksApi, BlocksApiImpl, CollateralApi, CollateralApiImpl, InfoApi, InfoApiImpl, MiningApi, MiningApiImpl, PaymentsApi, PaymentsApiImpl}
import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import tasks.{BlockPolling, StartupTasks, StratumServer}
import utils.Globals

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {
    System.out.println("Binding Tasks")
    // Set critical configs and databases
    Globals.setConfigs(configuration)

    // Bindings
    bindActor(classOf[SyncCoordinator], "sync-coordinator", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActor(classOf[SyncHandler], "sync-handler", p => p.withDispatcher("lithos-contexts.sync-dispatcher"))
    bindActorFactory(classOf[TreeSynchronizer], classOf[TreeSynchronizer.SyncFactory])
    bind(classOf[BlocksApi]).to(classOf[BlocksApiImpl])
    bind(classOf[CollateralApi]).to(classOf[CollateralApiImpl])
    bind(classOf[InfoApi]).to(classOf[InfoApiImpl])
    bind(classOf[MiningApi]).to(classOf[MiningApiImpl])
    bind(classOf[PaymentsApi]).to(classOf[PaymentsApiImpl])
    bind[StartupTasks](classOf[StartupTasks]).asEagerSingleton()
    bind[StratumServer](classOf[StratumServer]).asEagerSingleton()
    bind[BlockPolling](classOf[BlockPolling]).asEagerSingleton()
  }
}