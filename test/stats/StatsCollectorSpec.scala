package stats

import akka.actor.{Actor, ActorIdentity, ActorSystem, Identify, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.StatsConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import stats.StatsCollector.StratumObserved

import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._

class StatsCollectorSpec extends TestKit(ActorSystem("stats-collector-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val job = ActiveStratumJob("1", 100, "parent", "work", "publication", 1000L, "solo", None)

  "Stats collection" should "recover a missed snapshot and reject duplicates within a producer session" in {
    val cache = new StatsCache(StatsConfig.Default)
    val actor = system.actorOf(Props(new StatsCollector(cache)).withDispatcher("lithos-contexts.stats-dispatcher"))
    val source = TestProbe()
    val session = UUID.randomUUID()
    val first = StratumObserved(session, 1, 1000, System.nanoTime(), 2, Some(job))
    source.send(actor, first)
    awaitAssert(cache.snapshot().local.stratum.activeJob shouldBe Some(job))
    source.send(actor, first.copy(sequence = 3, activeJob = None))
    source.send(actor, first.copy(sequence = 2))
    source.send(actor, first.copy(sequence = 3))
    source.send(actor, Identify("processed"))
    source.expectMsgType[ActorIdentity].correlationId shouldBe "processed"
    cache.snapshot().local.stratum.activeJob shouldBe None
    source.send(actor, first.copy(session = UUID.randomUUID(), sequence = 1))
    awaitAssert(cache.snapshot().local.stratum.activeJob shouldBe Some(job))
    system.stop(source.ref)
    awaitAssert(cache.snapshot().local.stratum.status shouldBe "stopped")
    cache.snapshot().local.stratum.activeJob shouldBe None
    system.stop(actor)
  }

  it should "report age from the observation and retain the last job only as stale data" in {
    val cache = new StatsCache(StatsConfig(staleAfterMs = 2000))
    cache.publish(StratumStatsView("active", Some(1000), 2, Some(job)), 100L)
    cache.snapshot(100L + 2.seconds.toNanos).local.stratum.status shouldBe "active"
    val stale = cache.snapshot(101L + 2.seconds.toNanos).local.stratum
    stale.status shouldBe "stale"
    stale.activeJob shouldBe Some(job)
    cache.publish(StratumStatsView("waiting", Some(2000)), 200L)
    cache.snapshot(200L).local.stratum.activeJob shouldBe None
  }

  it should "recover after collector replacement from the producer's next snapshot" in {
    val cache = new StatsCache(StatsConfig.Default)
    val source = TestProbe()
    val event = StratumObserved(UUID.randomUUID(), 12, 1000, System.nanoTime(), 2, Some(job))
    val first = system.actorOf(Props(new StatsCollector(cache)))
    source.send(first, event)
    awaitAssert(cache.snapshot().local.stratum.status shouldBe "active")
    source.watch(first)
    system.stop(first)
    source.expectTerminated(first)
    val next = system.actorOf(Props(new StatsCollector(cache)))
    source.send(next, event.copy(sequence = 13, activeJob = None))
    awaitAssert(cache.snapshot().local.stratum.status shouldBe "waiting")
    system.stop(next)
  }

  it should "keep a disabled collector empty" in {
    val cache = new StatsCache(StatsConfig(enabled = false))
    val actor = system.actorOf(Props(new StatsCollector(cache)))
    actor ! StratumObserved(UUID.randomUUID(), 1, 1000, System.nanoTime(), 2, Some(job))
    cache.snapshot().enabled shouldBe false
    cache.snapshot().local.stratum.status shouldBe "disabled"
    system.stop(actor)
  }

  "Stats mailbox" should "let a producer finish while the consumer is blocked and overflowing" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val consumer = system.actorOf(Props(new Actor {
      override def receive: Receive = { case _ => entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
    }).withDispatcher("lithos-contexts.stats-dispatcher").withMailbox("stats-mailbox"))
    val result = TestProbe()
    try {
      consumer ! "hold"
      entered.await(3, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      val producer = system.actorOf(Props(new Actor {
        override def receive: Receive = { case "send" =>
          (1 to 300).foreach(i => consumer ! i)
          result.ref ! "sent"
        }
      }))
      producer ! "send"
      result.expectMsg(1.second, "sent")
      // The current snapshot remains readable while that dedicated thread is blocked.
      new StatsCache(StatsConfig.Default).snapshot().local.stratum.status shouldBe "waiting"
      system.stop(producer)
    } finally {
      release.countDown()
      system.stop(consumer)
    }
  }

  "Package JSON" should "keep amounts exact and distinguish unknown cost from zero" in {
    val pkg = BlockPackageView("genesis", "collateral", 3, Long.MaxValue, Vector("rollups"), Vector.empty,
      Vector(PackageTransactionView("genesis", "genesis", 200, Long.MaxValue, true),
        PackageTransactionView("extra", "ancestor", 0, 0L, false),
        PackageTransactionView("last", "payout", 100, Long.MaxValue, false)))
    val json = Json.toJson(pkg)(StatsView.packageWrites)
    (json \ "expectedRevenueNanoErg").as[String] shouldBe Long.MaxValue.toString
    (json \ "knownCost").as[String] shouldBe (BigInt(Long.MaxValue) * 2).toString
    (json \ "allCostsKnown").as[Boolean] shouldBe false
    (json \ "transactionScope").as[String] shouldBe "client-supplied"
    ((json \ "transactions")(1) \ "cost").asOpt[String] shouldBe None
  }
}
