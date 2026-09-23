package controllers

import configs.StatsConfig
import org.bouncycastle.util.encoders.Hex
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.mvc.{Action, AnyContent}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scorex.crypto.hash.Blake2b256
import stats._

import scala.concurrent.{Future, Promise}

class StatsApiControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val apiKey = "stats-test-key"
  private val config = Configuration("lithos.apiKeyHash" -> Hex.toHexString(Blake2b256.hash(apiKey)))
  private val controller = new StatsApiController(stubControllerComponents(), new StatsCache(StatsConfig.Default), config,
    mock[MiningStatsRefresh])
  private val hour = MiningHistory.HourMs
  private val source = MiningCursor(25, "aa" * 32, "bb" * 32, hour)

  private def call(action: Action[AnyContent], query: String = "") =
    action.apply(FakeRequest(GET, "/stats" + query).withHeaders("api_key" -> apiKey))

  private def withMining(mining: MiningStatsRefresh, cache: StatsCache = new StatsCache(StatsConfig.Default)) =
    new StatsApiController(stubControllerComponents(), cache, config, mining)

  "Statistics" should "serve the overview without an API key and forbid HTTP caching" in {
    val result = controller.getStats().apply(FakeRequest(GET, "/stats"))
    status(result) shouldBe OK
    header("Cache-Control", result) shouldBe Some("no-store")
    (contentAsJson(result) \ "local" \ "stratum" \ "status").as[String] shouldBe "waiting"
    val mining = contentAsJson(result) \ "mining"
    (mining \ "status").as[String] shouldBe "loading"
    (mining \ "paymentScope").as[String] shouldBe "primary-mining-address"
    (mining \ "paymentAmountScope").as[String] shouldBe "gross-output-including-bond-refund"
    (mining \ "totalsScope").as[String] shouldBe "retained-history"
    (mining \ "totals" \ "grossPaidNanoErg").as[String] shouldBe "0"
  }

  it should "authenticate local producer detail before reading data or validating a range" in {
    val mining = mock[MiningStatsRefresh]
    val cache = mock[StatsCache]
    val c = withMining(mining, cache)
    Seq(FakeRequest(GET, "/stats?from=bad"),
      FakeRequest(GET, "/stats?from=bad").withHeaders("api_key" -> "wrong")).foreach { request =>
      val denied = c.getLocalMiningStats().apply(request)
      status(denied) shouldBe FORBIDDEN
      header("Cache-Control", denied) shouldBe Some("no-store")
    }
    verifyNoInteractions(mining, cache)
  }

  /** Everything but the local producer detail is chain-derived, so a dashboard reads it unattended. */
  it should "serve every chain-derived endpoint anonymously, still without HTTP caching" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.view).thenReturn(MiningStatsView(status = "disabled"))
    when(mining.collateral).thenReturn(CollateralStats(status = "disabled"))
    when(mining.buckets(0L, hour, hour)).thenReturn(Future.successful(
      MiningBucketHistory(source, None, 0L, hour, hour, Vector.empty, partial = true)))
    when(mining.hashrate(0L, hour, hour)).thenReturn(Future.successful(
      LithosHashrateEstimate("insufficient-data", "disabled", source, 0L, 0L, 0L, "0", None, None, partial = true)))
    when(mining.localHistory("shares", 0L, hour)).thenReturn(Future.successful(Vector.empty))
    when(mining.difficulty(None, None, 256)).thenReturn(Future.successful(
      DifficultyEpochHistory(source, DifficultyEpochs.EpochLength, Vector.empty, None, None, None,
        backfillComplete = false)))
    val c = withMining(mining)
    val range = s"?from=0&until=$hour"
    Seq(c.getStats() -> "", c.getMiningTotals() -> "", c.getCollateralStats() -> "",
      c.getMiningBuckets() -> range, c.getMiningHashrate() -> range,
      c.getLocalMiningSummary() -> "", c.getLocalMiningHistory() -> range,
      c.getDifficultyEpochs(None, None, None) -> "").foreach { case (action, query) =>
      val result = action.apply(FakeRequest(GET, "/stats" + query))
      status(result) shouldBe OK
      header("Cache-Control", result) shouldBe Some("no-store")
    }
  }

  it should "serve the difficulty curve by epoch and reject a window no page could satisfy" in {
    val mining = mock[MiningStatsRefresh]
    val epoch = DifficultyEpoch.of(6599, DifficultySample(844673, 1000L, (BigInt(1) << 70).toString),
      DifficultySample(844800, 128000L, (BigInt(1) << 70).toString), complete = true)
    when(mining.difficulty(Some(6599), Some(6599), 8)).thenReturn(Future.successful(
      DifficultyEpochHistory(source, 128, Vector(epoch), Some(epoch.copy(index = 6600, complete = false)),
        Some(6599), Some(6599), backfillComplete = true, status = "ready")))
    val c = withMining(mining)
    val result = c.getDifficultyEpochs(Some(6599), Some(6599), Some(8)).apply(FakeRequest(GET, "/stats"))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "epochLength").as[Int] shouldBe 128
    (json \ "backfillComplete").as[Boolean] shouldBe true
    (json \ "epochs")(0).as[play.api.libs.json.JsObject].value("difficulty") shouldBe
      play.api.libs.json.JsString((BigInt(1) << 70).toString)
    (json \ "epochs")(0).as[play.api.libs.json.JsObject].value("hashesPerSecond") shouldBe
      play.api.libs.json.JsString((BigInt(1) << 70).toString)
    (json \ "current" \ "complete").as[Boolean] shouldBe false

    // A rejected window never reaches the worker, so none of these need a stub.
    Seq(c.getDifficultyEpochs(Some(9), Some(4), None),
      c.getDifficultyEpochs(Some(-1), None, None),
      c.getDifficultyEpochs(None, None, Some(0)),
      c.getDifficultyEpochs(None, None, Some(DifficultyEpochs.MaxPage + 1))).foreach { action =>
      val denied = action.apply(FakeRequest(GET, "/stats"))
      status(denied) shouldBe BAD_REQUEST
      (contentAsJson(denied) \ "error").as[Int] shouldBe BAD_REQUEST
      header("Cache-Control", denied) shouldBe Some("no-store")
    }
    verify(mining, never()).difficulty(Some(9), Some(4), 256)
  }

  it should "derive an aggregate worker hashrate without exposing session detail" in {
    val cache = mock[StatsCache]
    when(cache.settings).thenReturn(StatsConfig.Default)
    val observation = LocalMiningObservation("shares", "session-7", 3L, 0L, 60000L,
      Map("accepted" -> "90", "acceptedAssignedWork" -> "1200000", "superShares" -> "6",
        "rejected20" -> "2"))
    when(cache.localMiningViews).thenReturn(
      Map("shares" -> LocalMiningActivityView("ready", observation, deduplicationComplete = true)))
    when(cache.recentWork).thenReturn(Vector(
      WorkSample("session-7", 30000L, BigInt(300000), 30L), WorkSample("session-7", 60000L, BigInt(1200000), 90L)))
    // The job being served is the height a rollup would start at, which the NISP window counts back from.
    val job = ActiveStratumJob("7", 1000, "aa" * 32, "bb" * 32, "publication", 0L, "genesis", None)
    when(cache.snapshot(anyLong())).thenReturn(
      StatsView(enabled = true, LocalStatsView(StratumStatsView("active", activeJob = Some(job)))))
    val result = withMining(mock[MiningStatsRefresh], cache).getLocalMiningSummary()
      .apply(FakeRequest(GET, "/stats"))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    // The window's last 30 seconds, not the whole minute of session.
    (json \ "hashesPerSecond").as[String] shouldBe "30000"
    (json \ "sessionHashesPerSecond").as[String] shouldBe "20000"
    (json \ "windowShares").as[Long] shouldBe 60L
    (json \ "acceptedShares").as[Long] shouldBe 90L
    (json \ "rejectedShares").as[Long] shouldBe 2L
    (json \ "superShares").as[Long] shouldBe 6L
    (json \ "reducedReporting").as[Boolean] shouldBe false
    (json \ "nisp" \ "atHeight").as[Int] shouldBe 1000
    (json \ "nisp" \ "held").as[Boolean] shouldBe false
    // The session identifier and the fraud list stay behind the api key.
    (json \ "session").toOption shouldBe None
    (json \ "fraud").toOption shouldBe None
  }

  it should "expose exact retained totals and their coverage without changing the existing snapshot" in {
    val mining = mock[MiningStatsRefresh]
    val amount = "922337203685477580812345"
    when(mining.view).thenReturn(MiningStatsView(status = "catching-up", persistent = false,
      sourceHeight = Some(25), sourceBlockId = Some(source.blockId), targetHeight = Some(30),
      fromHeight = Some(10), fromTimestamp = Some(120000),
      totals = MiningTotals(accounting = MiningAccountingTotals(Map("local.payout.rewardNanoErg" -> amount)))))
    val result = call(withMining(mining).getMiningTotals())
    status(result) shouldBe OK
    header("Cache-Control", result) shouldBe Some("no-store")
    val json = contentAsJson(result)
    (json \ "totals" \ "local.payout.rewardNanoErg").as[String] shouldBe amount
    (json \ "status").as[String] shouldBe "catching-up"
    (json \ "fromHeight").as[Int] shouldBe 10
    (json \ "targetHeight").as[Int] shouldBe 30
    (json \ "observedAt").toOption shouldBe None
    (json \ "persistent").as[Boolean] shouldBe false
    (contentAsJson(call(controller.getStats())) \ "mining" \ "totals" \ "accounting").toOption shouldBe None
  }

  it should "return bucket coverage and exact metrics while awaiting history asynchronously" in {
    val mining = mock[MiningStatsRefresh]
    val pending = Promise[MiningBucketHistory]()
    when(mining.buckets(0L, hour, hour)).thenReturn(pending.future)
    val result = call(withMining(mining).getMiningBuckets(), s"?from=0&until=$hour")
    result.isCompleted shouldBe false
    pending.success(MiningBucketHistory(source, Some(120000L), 0L, hour, hour,
      Vector(MiningBucket(0L, hour, MiningAccountingTotals(Map("chain.blocks" -> "24")))),
      partial = true, status = "stale"))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "buckets")(0).as[play.api.libs.json.JsObject].value("totals") shouldBe
      play.api.libs.json.Json.obj("chain.blocks" -> "24")
    (json \ "partial").as[Boolean] shouldBe true
    (json \ "retainedFrom").as[Long] shouldBe 120000L
    (json \ "status").as[String] shouldBe "stale"
    verify(mining).buckets(0L, hour, hour)
  }

  it should "accept daily intervals and preserve sparse hashrate metadata" in {
    val mining = mock[MiningStatsRefresh]
    val day = MiningHistory.DayMs
    val estimate = LithosHashrateEstimate("sparse", "ready", source, 120000L, hour, 2L,
      "100000000000000000000", Some("28735632183908045"), Some(1 / math.sqrt(2)), partial = true)
    when(mining.hashrate(0L, day, day)).thenReturn(Future.successful(estimate))
    val result = call(withMining(mining).getMiningHashrate(), s"?from=0&until=$day&interval=day")
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "hashesPerSecond").as[String] shouldBe estimate.hashesPerSecond.get
    (json \ "status").as[String] shouldBe "sparse"
    (json \ "sourceStatus").as[String] shouldBe "ready"
    (json \ "population").as[String] shouldBe estimate.population
    (json \ "formula").as[String] shouldBe estimate.formula
    verify(mining).hashrate(0L, day, day)
  }

  it should "leave an unavailable hashrate absent instead of reporting zero" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.hashrate(0L, hour, hour)).thenReturn(Future.successful(
      LithosHashrateEstimate("insufficient-data", "catching-up", source, 0L, hour,
        0L, "0", None, None, partial = true)))
    val result = call(withMining(mining).getMiningHashrate(), s"?from=0&until=$hour")
    status(result) shouldBe OK
    (contentAsJson(result) \ "hashesPerSecond").toOption shouldBe None
    (contentAsJson(result) \ "relativeSamplingError").toOption shouldBe None
  }

  it should "reject malformed, unaligned, reversed and oversized ranges before querying history" in {
    val mining = mock[MiningStatsRefresh]
    val c = withMining(mining)
    val queries = Seq("", s"?until=$hour", "?from=0", "?from=no&until=1",
      "?from=0&until=9223372036854775808", s"?from=1&until=$hour", "?from=0&until=0",
      s"?from=$hour&until=0", s"?from=-$hour&until=$hour", s"?from=0&until=${hour * 501}",
      s"?from=0&until=$hour&interval=week", s"?from=0&until=$hour&interval=day")
    for (action <- Seq(c.getMiningBuckets(), c.getMiningHashrate()); query <- queries) {
      val result = call(action, query)
      status(result) shouldBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[Int] shouldBe BAD_REQUEST
      header("Cache-Control", result) shouldBe Some("no-store")
    }
    verifyNoInteractions(mining)
  }

  it should "return a retryable unavailable response without exposing storage errors" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.buckets(0L, hour, hour)).thenReturn(Future.failed(new IllegalStateException("worker busy")))
    when(mining.hashrate(0L, hour, hour)).thenReturn(Future.failed(new IllegalArgumentException("private database path")))
    val c = withMining(mining)
    Seq(c.getMiningBuckets(), c.getMiningHashrate()).foreach { action =>
      val result = call(action, s"?from=0&until=$hour")
      status(result) shouldBe SERVICE_UNAVAILABLE
      header("Retry-After", result) shouldBe Some("1")
      header("Cache-Control", result) shouldBe Some("no-store")
      contentAsString(result) should not include "private database path"
    }
  }

  it should "preserve partial and stale collateral inventory metadata" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.collateral).thenReturn(CollateralStats("stale", Some(1000L), Some(source),
      1000, "92233720368547758080", partial = true))
    val result = call(withMining(mining).getCollateralStats())
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "partial").as[Boolean] shouldBe true
    (json \ "status").as[String] shouldBe "stale"
    (json \ "nanoErg").as[String] shouldBe "92233720368547758080"
    (json \ "source" \ "blockId").as[String] shouldBe source.blockId
  }

  /** The bid book rides on the inventory response, so its shape is part of that endpoint's contract. */
  it should "serialise the bid distribution as exact strings beside the inventory" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.collateral).thenReturn(CollateralStats("ready", Some(1000L), Some(source), 4,
      "11660000000", partial = false,
      fees = CollateralFeeStats.of(Vector(0L, 0L, 2000000L, 9223372036854775807L), unreadable = 1)))
    val fees = contentAsJson(call(withMining(mining).getCollateralStats())) \ "fees"

    (fees \ "atFloor").as[Int] shouldBe 2
    (fees \ "bidding").as[Int] shouldBe 2
    (fees \ "unreadable").as[Int] shouldBe 1
    withClue("a total past Long must not have been summed into one: ") {
      (fees \ "totalNanoErg").as[String] shouldBe "9223372036856775807"
      (fees \ "bestNanoErg").as[String] shouldBe "9223372036854775807"
    }
    (fees \ "buckets").as[Seq[play.api.libs.json.JsObject]].map(b => (b \ "boxes").as[Int]).sum shouldBe 4
    withClue("only the open-ended top band omits its upper bound: ") {
      (fees \ "buckets").as[Seq[play.api.libs.json.JsObject]]
        .count(b => (b \ "toNanoErg").toOption.isEmpty) shouldBe 1
    }
  }

  it should "keep local producer sessions, stopped state and fraud deduplication limits visible" in {
    val cache = mock[StatsCache]
    when(cache.settings).thenReturn(StatsConfig.Default)
    val observation = LocalMiningObservation("fraud", "session-1", 2L, 1000L, 2000L,
      Map("discoveriesWithinSession" -> "101", "discoveryEvictions" -> "1"),
      Vector(FraudObservation("rollup", "miner", "contract", 1500L, Some("tx"))), stopped = true)
    when(cache.localMiningViews).thenReturn(Map("fraud" -> LocalMiningActivityView("stopped", observation, false)))
    val result = call(withMining(mock[MiningStatsRefresh], cache).getLocalMiningStats())
    status(result) shouldBe OK
    val producer = contentAsJson(result) \ "producers" \ "fraud"
    (producer \ "status").as[String] shouldBe "stopped"
    (producer \ "deduplicationComplete").as[Boolean] shouldBe false
    (producer \ "observation" \ "session").as[String] shouldBe "session-1"
    (producer \ "observation" \ "fraud")(0).as[play.api.libs.json.JsObject].value("transactionId") shouldBe
      play.api.libs.json.JsString("tx")
  }

  it should "represent disabled collection and missing producers without invented activity" in {
    val mining = mock[MiningStatsRefresh]
    when(mining.view).thenReturn(MiningStatsView(status = "disabled"))
    when(mining.collateral).thenReturn(CollateralStats(status = "disabled"))
    val c = withMining(mining, new StatsCache(StatsConfig.Default.copy(enabled = false)))
    val local = contentAsJson(call(c.getLocalMiningStats()))
    (local \ "enabled").as[Boolean] shouldBe false
    (local \ "producers").as[Map[String, play.api.libs.json.JsValue]] shouldBe empty
    (contentAsJson(call(c.getMiningTotals())) \ "status").as[String] shouldBe "disabled"
    (contentAsJson(call(c.getCollateralStats())) \ "status").as[String] shouldBe "disabled"
  }
}
