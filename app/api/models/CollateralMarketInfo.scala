package api.models

import play.api.libs.json._

/**
 * One snapshot of the collateral market, for the panel's stat strip.
 *
 * ERG amounts are nanoERG decimal strings; permit and emission amounts are LIT base units as
 * decimal strings. Counts are integers.
 */
case class CollateralMarketInfo(syncHeight: Int,
                                activationCounter: Int,
                                queueHead: Long,
                                queueTail: Long,
                                queueLength: Long,
                                activeSetSize: Int,
                                activeSetMax: Int,
                                bootstrapping: Boolean,
                                proofsOfSpendAvailable: Int,
                                queuedNanoErgs: String,
                                activeNanoErgs: String,
                                permitLitLocked: String,
                                principalNanoErgs: String,
                                currentPermitLit: String,
                                permitFloorLit: String,
                                permitCeilingLit: String,
                                permitSlopeLitPerBox: String,
                                emissionPhase: String,
                                foundersActive: Boolean,
                                currentEpoch: Int,
                                nextPhaseChangeHeight: Option[Int],
                                emissionRatePublicLit: String,
                                emissionRatePrivateLit: String,
                                emissionRateTotalLit: String,
                                scheduleEndsAtHeight: Int)

object CollateralMarketInfo {

  // Hand-rolled because 25 fields exceed the case-class limit Json.format relies on.
  implicit lazy val jsonFormat: Format[CollateralMarketInfo] = new Format[CollateralMarketInfo] {
    override def reads(json: JsValue): JsResult[CollateralMarketInfo] =
      for {
        syncHeight <- (json \ "syncHeight").validate[Int]
        activationCounter <- (json \ "activationCounter").validate[Int]
        queueHead <- (json \ "queueHead").validate[Long]
        queueTail <- (json \ "queueTail").validate[Long]
        queueLength <- (json \ "queueLength").validate[Long]
        activeSetSize <- (json \ "activeSetSize").validate[Int]
        activeSetMax <- (json \ "activeSetMax").validate[Int]
        bootstrapping <- (json \ "bootstrapping").validate[Boolean]
        proofsOfSpendAvailable <- (json \ "proofsOfSpendAvailable").validate[Int]
        queuedNanoErgs <- (json \ "queuedNanoErgs").validate[String]
        activeNanoErgs <- (json \ "activeNanoErgs").validate[String]
        permitLitLocked <- (json \ "permitLitLocked").validate[String]
        principalNanoErgs <- (json \ "principalNanoErgs").validate[String]
        currentPermitLit <- (json \ "currentPermitLit").validate[String]
        permitFloorLit <- (json \ "permitFloorLit").validate[String]
        permitCeilingLit <- (json \ "permitCeilingLit").validate[String]
        permitSlopeLitPerBox <- (json \ "permitSlopeLitPerBox").validate[String]
        emissionPhase <- (json \ "emissionPhase").validate[String]
        foundersActive <- (json \ "foundersActive").validate[Boolean]
        currentEpoch <- (json \ "currentEpoch").validate[Int]
        nextPhaseChangeHeight <- (json \ "nextPhaseChangeHeight").validateOpt[Int]
        emissionRatePublicLit <- (json \ "emissionRatePublicLit").validate[String]
        emissionRatePrivateLit <- (json \ "emissionRatePrivateLit").validate[String]
        emissionRateTotalLit <- (json \ "emissionRateTotalLit").validate[String]
        scheduleEndsAtHeight <- (json \ "scheduleEndsAtHeight").validate[Int]
      } yield CollateralMarketInfo(
        syncHeight, activationCounter, queueHead, queueTail, queueLength,
        activeSetSize, activeSetMax, bootstrapping, proofsOfSpendAvailable,
        queuedNanoErgs, activeNanoErgs, permitLitLocked, principalNanoErgs,
        currentPermitLit, permitFloorLit, permitCeilingLit, permitSlopeLitPerBox,
        emissionPhase, foundersActive, currentEpoch, nextPhaseChangeHeight,
        emissionRatePublicLit, emissionRatePrivateLit, emissionRateTotalLit,
        scheduleEndsAtHeight)

    override def writes(o: CollateralMarketInfo): JsValue = JsObject(Seq(
      "syncHeight" -> JsNumber(o.syncHeight),
      "activationCounter" -> JsNumber(o.activationCounter),
      "queueHead" -> JsNumber(o.queueHead),
      "queueTail" -> JsNumber(o.queueTail),
      "queueLength" -> JsNumber(o.queueLength),
      "activeSetSize" -> JsNumber(o.activeSetSize),
      "activeSetMax" -> JsNumber(o.activeSetMax),
      "bootstrapping" -> JsBoolean(o.bootstrapping),
      "proofsOfSpendAvailable" -> JsNumber(o.proofsOfSpendAvailable),
      "queuedNanoErgs" -> JsString(o.queuedNanoErgs),
      "activeNanoErgs" -> JsString(o.activeNanoErgs),
      "permitLitLocked" -> JsString(o.permitLitLocked),
      "principalNanoErgs" -> JsString(o.principalNanoErgs),
      "currentPermitLit" -> JsString(o.currentPermitLit),
      "permitFloorLit" -> JsString(o.permitFloorLit),
      "permitCeilingLit" -> JsString(o.permitCeilingLit),
      "permitSlopeLitPerBox" -> JsString(o.permitSlopeLitPerBox),
      "emissionPhase" -> JsString(o.emissionPhase),
      "foundersActive" -> JsBoolean(o.foundersActive),
      "currentEpoch" -> JsNumber(o.currentEpoch),
      "nextPhaseChangeHeight" -> o.nextPhaseChangeHeight.map(v => JsNumber(v): JsValue).getOrElse(JsNull),
      "emissionRatePublicLit" -> JsString(o.emissionRatePublicLit),
      "emissionRatePrivateLit" -> JsString(o.emissionRatePrivateLit),
      "emissionRateTotalLit" -> JsString(o.emissionRateTotalLit),
      "scheduleEndsAtHeight" -> JsNumber(o.scheduleEndsAtHeight)
    ))
  }

}
