package configs

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/**
 * A `lithos.conf` built on an older release's `application.conf` must start under this release. Each fixture is the
 * file a release shipped, read as Play reads `lithos.conf`: the miner's own keys, then the old file, then every
 * `reference.conf` on the classpath, which is where this release keeps its defaults for blocks added since.
 *
 * This is the rule for new keys: one the client requires and no older file carries fails here until it has a
 * default in `conf/reference.conf` or in code.
 */
class OldConfigsSpec extends AnyFlatSpec with Matchers {

  /** Releases whose shipped `application.conf` is kept under `test/resources/config`. */
  private val Releases = Seq("1.0.6", "1.0.7", "1.0.8")

  private def asRun(file: Config): Configuration = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${"ab" * 32}"""").withFallback(file)
    .withFallback(ConfigFactory.defaultReference()).resolve())

  private def shippedBy(release: String): Config = ConfigFactory.parseResources(s"config/application-$release.conf")

  Releases.foreach { release =>
    s"The application.conf $release shipped" should "pass this release's startup validation" in {
      shippedBy(release).hasPath("node") shouldBe true
      try Configs.validateAll(asRun(shippedBy(release)))
      catch { case t: ConfigValidationException => fail(t.getMessage) }
    }

    it should "give Akka a dispatcher for every worker this release looks up" in {
      val system = akka.actor.ActorSystem(s"old-config-${release.replace('.', '-')}", asRun(shippedBy(release)).underlying)
      try Contexts.Names.foreach { name =>
        withClue(name)(noException should be thrownBy system.dispatchers.lookup(Contexts.key(name)))
      } finally akka.testkit.TestKit.shutdownActorSystem(system)
    }
  }

  "Every default conf/reference.conf carries" should "equal what the shipped application.conf sets for it" in {
    // Read by path: as a resource, reference.conf is every library's merged with this one
    val reference = ConfigFactory.parseFile(new java.io.File("conf/reference.conf"))
    val shipped = ConfigFactory.parseResources("application.conf")
    reference.isEmpty shouldBe false
    import scala.collection.JavaConverters._
    reference.entrySet.asScala.map(_.getKey).foreach { path =>
      withClue(path)(shipped.getValue(path).unwrapped shouldBe reference.getValue(path).unwrapped)
    }
  }
}
