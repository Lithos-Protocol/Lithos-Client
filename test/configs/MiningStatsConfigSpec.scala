package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

class MiningStatsConfigSpec extends AnyFlatSpec with Matchers {
  private val shipped = Configuration(ConfigFactory.parseResources("application.conf").resolve())
  "Mining statistics configuration" should "default to 30 days and support independent pruning and persistence switches" in {
    StatsConfig(shipped).mining shouldBe MiningStatsConfig()
    val settings = StatsConfig(Configuration(ConfigFactory.parseString(
      "stats.mining.pruning.enabled=false\nstats.mining.historyDays=90\nstats.storage.enabled=false")
      .withFallback(shipped.underlying)))
    settings.mining.pruningEnabled shouldBe false
    settings.mining.historyDays shouldBe 90
    settings.storage.enabled shouldBe false
  }
  it should "reject settings that remove work bounds or freshness" in {
    Seq("historyDays=0", "historyDays=36501", "blocksPerRefresh=0", "blocksPerRefresh=101",
      "readTimeoutMs=0", "readBudgetMs=0", "staleAfterMs=10000").foreach { bad =>
      val configured = Configuration(ConfigFactory.parseString(s"stats.mining.$bad").withFallback(shipped.underlying))
      intercept[ConfigValidationException](StatsConfig(configured)).getMessage should include("stats.mining.")
      intercept[ConfigValidationException](Configs.validateAll(configured)).getMessage should include("stats.mining.")
    }
  }
}
