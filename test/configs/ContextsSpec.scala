package configs

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
 * Every dispatcher `Contexts` names resolves against the shipped `application.conf`.
 *
 * `system.dispatchers.lookup` throws `ConfigurationException` for an id that is not configured, and
 * `Contexts`' fields are `val`s — so a typo or a missing block does not degrade, it fails Guice at
 * construction and the client does not boot. Nothing else in the suite constructs a component that
 * builds `Contexts`, so without this the only thing that would catch it is starting the app.
 *
 * Same reason as [[ConfigDefaultsSpec]]: config and the code that reads it are two sources for one
 * fact, and a comment is not a mechanism.
 */
class ContextsSpec extends TestKit(ActorSystem("contexts-spec",
  ConfigFactory.parseResources("application.conf").resolve().getConfig("lithos").withFallback(
    ConfigFactory.load())))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Contexts" should "resolve every dispatcher it names from the shipped configuration" in {
    val contexts = new Contexts(system)

    // Touched individually so a failure names the one that is missing rather than the first.
    contexts.stratumContext should not be null
    contexts.pollingContext should not be null
    contexts.syncContext should not be null
    contexts.txContext should not be null
    contexts.dexContext should not be null
  }
}
