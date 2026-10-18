package configs

import com.typesafe.config.ConfigFactory
import lfsm.{Deployment, DeploymentSpec}
import org.ergoplatform.appkit.NetworkType
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import play.api.Configuration

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * `node.deployment`: off by default, and refused whenever installing it could repoint a miner at
 * contracts it did not mean to mine against.
 */
class DeploymentConfigSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = Deployment.clear()

  private val shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  private val logger = LoggerFactory.getLogger("DeploymentConfigSpec")

  private def written(json: String): Path = {
    val f = Files.createTempFile("deployment", ".json")
    f.toFile.deleteOnExit()
    Files.write(f, json.getBytes(StandardCharsets.UTF_8))
  }

  "DeploymentConfig.Default" should "equal what the shipped application.conf parses, and be off" in {
    DeploymentConfig(shipped) shouldEqual DeploymentConfig.Default
    DeploymentConfig.Default.configured shouldBe false
  }

  "No descriptor" should "install nothing and leave every network on its constants" in {
    DeploymentConfig.install(DeploymentConfig.Default, NetworkType.MAINNET, logger) shouldBe None
    Deployment.current shouldBe None
  }

  "A descriptor" should "be installed for the network it names" in {
    val f = written(DeploymentSpec.descriptor(NetworkType.TESTNET).toJson)
    val d = DeploymentConfig.install(DeploymentConfig(f.toString, allowOnMainnet = false), NetworkType.TESTNET, logger)
    d.map(_.ids.fingerprint) shouldEqual Some(DeploymentSpec.fakeIds().fingerprint)
    Deployment.overrideFor(NetworkType.TESTNET).map(_.fingerprint) shouldEqual d.map(_.ids.fingerprint)
  }

  it should "be refused when the file does not exist" in {
    DeploymentConfig.problems(DeploymentConfig("/no/such/deployment.json", allowOnMainnet = false),
      Some(NetworkType.TESTNET)).head should include("does not exist")
  }

  it should "be refused when it names the other network" in {
    val f = written(DeploymentSpec.descriptor(NetworkType.TESTNET).toJson)
    val cfg = DeploymentConfig(f.toString, allowOnMainnet = true)
    DeploymentConfig.problems(cfg, Some(NetworkType.MAINNET)).exists(_.contains("written for TESTNET")) shouldBe true
    a[ConfigValidationException] should be thrownBy DeploymentConfig.install(cfg, NetworkType.MAINNET, logger)
    Deployment.current shouldBe None
  }

  it should "be refused on MAINNET unless allowOnMainnet is set" in {
    val f = written(DeploymentSpec.descriptor(NetworkType.MAINNET).toJson)
    val refused = DeploymentConfig(f.toString, allowOnMainnet = false)
    DeploymentConfig.problems(refused, Some(NetworkType.MAINNET)).exists(_.contains("allowOnMainnet")) shouldBe true
    a[ConfigValidationException] should be thrownBy DeploymentConfig.install(refused, NetworkType.MAINNET, logger)
    Deployment.current shouldBe None

    DeploymentConfig.problems(refused.copy(allowOnMainnet = true), Some(NetworkType.MAINNET)) shouldBe empty
  }

  it should "be reported by the startup validation pass under node.deployment.file" in {
    val incomplete = written("{\"network\": \"TESTNET\"}").toString
    val broken = Configuration(ConfigFactory.parseString(
      s"""node.deployment.file = "$incomplete"
         |node.networkType = "TESTNET"
         |""".stripMargin).withFallback(shipped.underlying).resolve())
    val v = new ConfigValidator(broken)
    DeploymentConfig.validate(v)
    val thrown = the[ConfigValidationException] thrownBy v.finish()
    thrown.getMessage should include("node.deployment.file")
    thrown.getMessage should include("litId")
  }
}
