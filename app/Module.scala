import api.{BlocksApi, BlocksApiImpl, CollateralApi, CollateralApiImpl, InfoApi, InfoApiImpl, MiningApi, MiningApiImpl, PaymentsApi, PaymentsApiImpl}
import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import tasks.{BlockPolling, StratumServer}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {
    System.out.println("Binding Tasks")
    bind(classOf[BlocksApi]).to(classOf[BlocksApiImpl])
    bind(classOf[CollateralApi]).to(classOf[CollateralApiImpl])
    bind(classOf[InfoApi]).to(classOf[InfoApiImpl])
    bind(classOf[MiningApi]).to(classOf[MiningApiImpl])
    bind(classOf[PaymentsApi]).to(classOf[PaymentsApiImpl])
    bind[StratumServer](classOf[StratumServer]).asEagerSingleton()
    bind[BlockPolling](classOf[BlockPolling]).asEagerSingleton()
  }
}