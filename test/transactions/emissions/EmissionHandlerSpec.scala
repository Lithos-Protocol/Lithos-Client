package transactions.emissions

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.FakeNodeContext
import transactions.BlockTxMessages.{BlockTxsReady, RequestBlockTxs}
import transactions.emissions.EmissionHandler.{Collateralize, DriveQueue}

import scala.concurrent.duration._
import scala.util.Success

/**
 * `EmissionHandler`'s two timers, and the one lock that covers both.
 *
 * Join, Activate and Clear all spend the emission box and all chain off the same tip, so two passes
 * running at once build on that tip together and one is a guaranteed double spend — taking every
 * chained transaction behind it down with it. The intervals make this collide on schedule: 120s and
 * 600s coincide every ten minutes, and both first passes used to land at exactly 60s.
 *
 * The node lookups are stubbed to find no emission box, so a pass fails fast and predictably. What
 * is under test is which passes are allowed to start, not what they build.
 */
object EmissionHandlerSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class EmissionHandlerSpec extends TestKit(ActorSystem("emission-handler-spec", EmissionHandlerSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def conf(enabled: Boolean = true, autoCollateralize: Boolean = false): Configuration =
    Configuration.from(Map(
      "emission.enabled" -> enabled,
      "emission.autoCollateralize" -> autoCollateralize,
      // Long intervals so nothing fires on its own; every tick in this spec is deliberate.
      "emission.queueInterval" -> 3600000,
      "emission.collateralizeInterval" -> 3600000
    ))

  private case class Fixture(handler: ActorRef, api: NodeApi, wallet: TestProbe, probe: TestProbe)

  /**
   * @param slowLookup make the emission-box lookup block, so a pass can be caught mid-flight
   */
  private def fixture(configuration: Configuration = conf(), slowLookup: Boolean = false): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, _) = FakeNodeContext(api)
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)

    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { _ =>
        calls.incrementAndGet()
        if (slowLookup) Thread.sleep(6000)
        Success(Seq.empty[IndexedBox]) // no emission box: the pass fails fast
      }

    val wallet = TestProbe()
    val handler = system.actorOf(Props(new EmissionHandler(configuration, ctx, wallet.ref)))
    Fixture(handler, api, wallet, TestProbe())
  }

  private def lookupCount(f: Fixture): Int = {
    val captor = org.mockito.Mockito.mockingDetails(f.api).getInvocations
    captor.toArray.count(_.toString.contains("unspentBoxesByTokenId"))
  }

  // ─── the shared lock ──────────────────────────────────────────────────────

  "The two timers" should "never run a pass at the same time" in {
    // One lock for BOTH, not one each. They had a guard apiece, so neither could overlap itself —
    // and nothing stopped them overlapping each other.
    val f = fixture(conf(autoCollateralize = true), slowLookup = true)

    f.handler ! DriveQueue
    Thread.sleep(1500)          // the queue pass is inside its slow lookup
    val duringFirst = lookupCount(f)
    duringFirst shouldEqual 1

    f.handler ! Collateralize
    Thread.sleep(1500)
    withClue("the collateralize pass must have been skipped while the queue pass ran: ") {
      lookupCount(f) shouldEqual duringFirst
    }
  }

  it should "let the next pass run once the first has finished" in {
    // The flag is cleared on every completion path, so a failed pass does not wedge the queue.
    val f = fixture(conf(autoCollateralize = true))

    f.handler ! DriveQueue
    Thread.sleep(2000)
    val afterFirst = lookupCount(f)
    afterFirst should be >= 1

    f.handler ! DriveQueue
    Thread.sleep(2000)
    withClue("a second pass should have started once the first released the lock: ") {
      lookupCount(f) should be > afterFirst
    }
  }

  // ─── the block-assembly path ──────────────────────────────────────────────

  "RequestBlockTxs" should "answer empty at once when emission is disabled" in {
    // A miner is waiting. Nothing may be looked up, and nothing may be built.
    val f = fixture(conf(enabled = false))
    f.probe.send(f.handler, RequestBlockTxs(500, 5))
    f.probe.expectMsgType[BlockTxsReady](5.seconds) shouldEqual BlockTxsReady(500, Seq.empty)
    lookupCount(f) shouldEqual 0
  }

  it should "answer empty at once when the block has no room" in {
    val f = fixture()
    f.probe.send(f.handler, RequestBlockTxs(500, 0))
    f.probe.expectMsgType[BlockTxsReady](5.seconds) shouldEqual BlockTxsReady(500, Seq.empty)
    lookupCount(f) shouldEqual 0
  }

  it should "answer rather than hang when there is no emission box to spend" in {
    // The failure has to reach the miner as an empty answer, not as a timeout — `blockTxTimeout`
    // expiring costs the block its inserted transactions either way, but a reply keeps the builder's
    // bookkeeping straight.
    val f = fixture()
    f.probe.send(f.handler, RequestBlockTxs(500, 5))
    f.probe.expectMsgType[BlockTxsReady](15.seconds).txs shouldBe empty
  }

  it should "not be blocked by a pass that is already running" in {
    // The fee-less build takes no wallet input and shares no state with a funded pass, so a block
    // must be served whatever the timers are doing.
    val f = fixture(slowLookup = true)
    f.handler ! DriveQueue
    Thread.sleep(1000)

    f.probe.send(f.handler, RequestBlockTxs(500, 5))
    f.probe.expectMsgType[BlockTxsReady](20.seconds).blockHeight shouldEqual 500
  }

  // ─── self-collateralization is off unless asked for ───────────────────────

  "A Collateralize tick" should "do nothing while autoCollateralize is off" in {
    // Anything that locks user funds ships disabled. The pass runs but `selfCollateralize` returns
    // before touching the wallet.
    val f = fixture(conf(autoCollateralize = false))
    f.handler ! Collateralize
    Thread.sleep(1500)
    f.wallet.expectNoMessage(500.millis)
  }
}
