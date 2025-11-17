import com.google.inject.AbstractModule
import play.api.{Configuration, Environment}
import play.libs.akka.AkkaGuiceSupport
import tasks.{BlockPolling, StratumServer}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with AkkaGuiceSupport
{
  @Override
  override def configure(): Unit = {
    System.out.println("Binding Tasks")
    bind[StratumServer](classOf[StratumServer]).asEagerSingleton()
    bind[BlockPolling](classOf[BlockPolling]).asEagerSingleton()
  }
}