package api

import api.models.CandidateSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.libs.json.Json

class CandidateSettingsSpec extends AnyFlatSpec with Matchers {
  private val shipped = Configuration(ConfigFactory.parseResources("application.conf").resolve())

  private def withOverrides(overrides: String): Configuration =
    Configuration(ConfigFactory.parseString(overrides).withFallback(shipped.underlying).resolve())

  "Candidate settings" should "report the shipped config, with every enabled source active" in {
    val settings = CandidateSettings.from(shipped)
    settings.blockTransactions shouldBe true
    settings.sources.map(_.name) shouldBe Seq("rollups", "emissions", "rent", "lithosdex", "ergodex")
    settings.sources.filter(_.active).map(_.name) shouldBe Seq("rollups", "emissions", "lithosdex", "ergodex")
    settings.mempoolRefreshMs shouldBe 20000
    settings.minCandidateChangeRevenue shouldBe 1000000L
    settings.collateralStrategy shouldBe "highestFee"
    settings.clearanceAge shouldBe 7200
  }

  it should "report enabled sources as not asked while block transactions are off" in {
    val settings = CandidateSettings.from(withOverrides("stratum.candidate.blockTransactions = false"))
    settings.sources.exists(_.active) shouldBe false
    // Enabled is the source's own setting, reported even while nothing asks it.
    settings.sources.filter(_.enabled).map(_.name) shouldBe Seq("rollups", "emissions", "lithosdex", "ergodex")
  }

  it should "mark a source active only when it would be asked" in {
    val settings = CandidateSettings.from(withOverrides(
      """stratum.candidate.blockTransactions = true
        |stratum.candidate.sources.ergodex.enabled = true
        |batching.ergodex.enabled = false
        |stratum.candidate.sources.emissions.maxTxs = 0
        |""".stripMargin))
    settings.sources.map(s => s.name -> s.active).toMap shouldBe Map(
      "rollups" -> true,
      // No slots is the same as off: the stratum never asks a source it cannot place.
      "emissions" -> false,
      "rent" -> false,
      "lithosdex" -> true,
      // Enabled as a source, but its batcher is off, so nothing would answer.
      "ergodex" -> false)
  }

  it should "serialise under the config key names" in {
    val json = Json.toJson(CandidateSettings.from(shipped))
    (json \ "blockTransactions").as[Boolean] shouldBe true
    (json \ "refreshForProtocolTxs").as[Boolean] shouldBe true
    (json \ "minNewProtocolTxs").as[Int] shouldBe 1
    (json \ "sources" \ 0 \ "maxBytes").as[Long] shouldBe 262144L
  }
}
