package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

class OracleConfigSpec extends AnyFlatSpec with Matchers {
  "Oracle defaults" should "match the shipped config and work when the block is absent" in {
    OracleConfig(Configuration(ConfigFactory.parseResources("application.conf").resolve())) shouldBe OracleConfig.Default
    OracleConfig(Configuration.empty) shouldBe OracleConfig.Default
  }
  it should "allow another configured feed and an empty list on networks without a deployment" in {
    val custom = Configuration.from(Map("oracles.feeds" -> Seq(Map("id" -> "erg-xau", "name" -> "Gold",
      "network" -> "MAINNET", "poolNft" -> ("ab" * 32), "ergoTreeHash" -> ("cd" * 32),
      "baseAsset" -> "ERG", "quoteAsset" -> "XAU", "baseUnit" -> "nanoERG"))))
    OracleConfig(custom).feeds.head.quoteAsset shouldBe "XAU"
    OracleConfig(Configuration.from(Map("oracles.feeds" -> Seq.empty[Map[String, String]]))).feeds shouldBe empty
  }
  it should "reject invalid limits and deployment pins before startup" in {
    Seq("oracles.defaultHistoryLimit=201", "oracles.readTimeoutMs=0", "oracles.readBudgetMs=0",
      "oracles.feeds=[{id=bad}]").foreach { text =>
      an[ConfigValidationException] should be thrownBy OracleConfig.validate(Configuration(ConfigFactory.parseString(text)))
    }
  }
}
