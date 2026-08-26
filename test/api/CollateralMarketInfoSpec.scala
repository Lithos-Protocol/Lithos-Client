package api

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsNull, JsSuccess, Json}
import api.models.CollateralMarketInfo

/**
 * `CollateralMarketInfo` carries 25 fields, past the case-class limit `Json.format` relies on, so
 * its codec is hand-rolled. Hand-rolled codecs are exactly where a renamed field silently stops
 * round-tripping, which is why this spec exists: one round trip through both directions, with the
 * optional present and absent.
 */
class CollateralMarketInfoSpec extends AnyFlatSpec with Matchers {

  private val sample = CollateralMarketInfo(
    syncHeight = 484719,
    activationCounter = 1234,
    queueHead = 410L,
    queueTail = 1247L,
    queueLength = 837L,
    activeSetSize = 97,
    activeSetMax = 100,
    bootstrapping = false,
    proofsOfSpendAvailable = 2,
    queuedNanoErgs = "2439835500000",
    activeNanoErgs = "282755000000",
    permitLitLocked = "12840000000",
    principalNanoErgs = "2915000000",
    currentPermitLit = "19370000000",
    permitFloorLit = "1000000000000",
    permitCeilingLit = "30000000000000",
    permitSlopeLitPerBox = "10000000000",
    emissionPhase = "DECAYING",
    foundersActive = true,
    currentEpoch = 0,
    nextPhaseChangeHeight = Some(250000),
    emissionRatePublicLit = "500000000000",
    emissionRatePrivateLit = "140000000000",
    emissionRateTotalLit = "640000000000",
    scheduleEndsAtHeight = 2825000
  )

  "The market snapshot codec" should "round-trip every field" in {
    val parsed = Json.parse(Json.toJson(sample).toString()).as[CollateralMarketInfo]
    parsed shouldEqual sample
  }

  it should "carry nextPhaseChangeHeight as JSON null when absent, not drop or zero it" in {
    val ended = sample.copy(emissionPhase = "ENDED", nextPhaseChangeHeight = None)
    val raw = Json.toJson(ended)
    (raw \ "nextPhaseChangeHeight").toOption shouldBe Some(JsNull)
    raw.as[CollateralMarketInfo] shouldEqual ended
  }

  it should "reject a payload missing a required field rather than defaulting it" in {
    val raw = Json.toJson(sample).asInstanceOf[play.api.libs.json.JsObject]
    val missing = play.api.libs.json.JsObject(raw.fields.filterNot(_._1 == "permitCeilingLit"))
    missing.validate[CollateralMarketInfo].isError shouldBe true
  }
}
