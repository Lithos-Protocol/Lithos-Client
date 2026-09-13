package transactions.batching.ergodex

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{BatchingConfig, CandidateSourceConfig}
import node.model._
import node.{NodeApi, NodeError}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{timeout, times, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.ErgoDexFixtures._
import support.{ChainFixtures, FakeNodeContext}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, ChainFromMempool, IncludeExisting, RequestBlockTxs, Supersede}
import transactions.candidate.CandidateBundle

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ErgoDexSourceSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

class ErgoDexSourceSpec extends TestKit(ActorSystem("ergodex-source-spec", ErgoDexSourceSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val anchor = "cc" * 32
  private val tip = poolBox.creationHeight + 10
  private val poolNft = ErgoDexPool.native(poolBox).get.nft
  private val buyV1 = ErgoDexContracts.orders.find(c => c.kind == OrderKind.SwapBuy && c.version == "v1").get
  private val revenue = ErgoDexExecution.price(ErgoDexOrder.parse(orderBox).get,
    ErgoDexPool.native(poolBox).get, 0L).get.revenue

  private val heights = new AtomicInteger(poolBox.creationHeight + 100)

  private def id(seed: String): String = (seed * 64).take(64)

  private def mempoolTx(txId: String, spends: Seq[String], outputs: Seq[NodeBox] = Seq.empty) =
    CompleteMempool.MempoolTx(txId,
      NodeTransaction(txId, spends.map(NodeInput(_, NodeSpendingProof.empty)), Seq.empty, outputs), 100)

  private val walletBox = NodeBox(id("9"), id("8"), 2000000000L, 0, orderBox.creationHeight - 5,
    "0008cd02" + "11" * 32)

  private class Fixture(broadcast: Boolean = false, discoverable: Boolean = true,
                        initialOrder: NodeBox = orderBox, initialPool: NodeBox = poolBox) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    @volatile var mempool: Vector[CompleteMempool.MempoolTx] = Vector.empty
    @volatile var mempoolFailure: Option[String] = None
    @volatile var orders: Seq[NodeBox] = Seq(initialOrder)
    @volatile var pool: NodeBox = initialPool
    @volatile var poolUnspent: Boolean = true
    val observations = new AtomicInteger(0)

    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh) {
          observations.incrementAndGet()
          sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor,
            mempool.map(_.id).toSet, mempool.flatMap(_.body.inputs.map(_.boxId)).toSet, System.nanoTime(),
            transactions = mempool)), mempoolFailure)
        }
        this
      }
    })

    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(tip).copy(bestFullHeaderId = Some(anchor))))
    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (discoverable && inv.getArgument[String](0) == ErgoDexOrder.parse(initialOrder).get.contract.templateHash)
          Success(Seq(IndexedBox(initialOrder, "", initialOrder.creationHeight + 1, 1L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[String](0) == poolNft) Success(Seq(IndexedBox(pool, "", tip - 1, 2L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success((orders ++ Seq(pool).filter(_ => poolUnspent) :+ walletBox).filter(box => wanted.contains(box.boxId)))
    }
    when(api.sendTransaction(anyString())).thenReturn(Failure(NodeError.Rejected("refused by the test")))

    val source: ActorRef = system.actorOf(Props(new ErgoDexSource(nodeContext,
      BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = broadcast),
      CandidateSourceConfig.Default.copy(maxTxs = 8), engine.ref, useTrueProp = false)))

    def offered(): Seq[CandidateBundle] = {
      val height = heights.incrementAndGet()
      val requester = TestProbe()
      requester.send(source, RequestBlockTxs(height, 8))
      requester.expectMsgType[BlockTxsReady].bundles
    }

    def offeredOnceTracked(): Seq[CandidateBundle] =
      awaitAssert({ val bundles = offered(); bundles should not be empty; bundles }, 30.seconds, 250.millis)

    def stop(): Unit = system.stop(source)
  }

  "The source" should "offer a fee-less execution that supersedes a competitor's unconfirmed one" in {
    val f = new Fixture()
    val competitor = id("b")
    f.mempool = Vector(mempoolTx(competitor, Seq(poolBox.boxId, orderBox.boxId),
      Seq(poolBox.copy(boxId = id("d")))))

    val bundles = f.offeredOnceTracked()
    bundles should have size 1
    bundles.head.members should have size 1
    bundles.head.interactions shouldBe Seq(Supersede(Set(competitor)))
    bundles.head.capital.map(_.value) shouldBe Seq(revenue)
    f.stop()
  }

  it should "discover and execute native deposits and redemptions for candidates and broadcasts" in {
    val (nodeContext, _, _) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    val fixtures = nodeContext.getClient.execute { ctx =>
      Seq(OrderKind.Deposit, OrderKind.Redeem).map { kind =>
        support.ErgoDexLiquidityFixtures.order(ctx, kind).box -> support.ErgoDexLiquidityFixtures.poolBox(ctx)
      }
    }
    fixtures.foreach { case (placed, pool) =>
      val f = new Fixture(broadcast = true, initialOrder = placed, initialPool = pool)
      val bundle = f.offeredOnceTracked().head
      bundle.members.last.inputIds should contain(placed.boxId)
      bundle.capital.map(_.value) shouldBe Seq(support.ErgoDexLiquidityFixtures.Fee)
      verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
      f.stop()
    }
  }

  it should "leave alone an order its owner is taking back" in {
    val f = new Fixture()
    f.offeredOnceTracked() should not be empty

    f.mempool = Vector(mempoolTx(id("b"), Seq(orderBox.boxId)))
    f.offered() shouldBe empty
    f.stop()
  }

  it should "offer nothing without a fresh mempool observation, and resume once one arrives" in {
    val f = new Fixture()
    f.offeredOnceTracked() should not be empty

    f.mempoolFailure = Some("mempool membership or chain anchor changed")
    f.offered() shouldBe empty

    f.mempoolFailure = None
    f.offered() should not be empty
    f.stop()
  }

  it should "offer nothing against a pool box the indexer lists but the chain has spent" in {
    val f = new Fixture()
    f.offeredOnceTracked() should not be empty

    f.poolUnspent = false
    f.offered() shouldBe empty

    f.poolUnspent = true
    f.offered() should not be empty
    f.stop()
  }

  it should "rebuild a cached height when a wallet places an order between mempool refreshes" in {
    val f = new Fixture(discoverable = false)
    val height = heights.incrementAndGet()
    val requester = TestProbe()
    requester.send(f.source, RequestBlockTxs(height, 8))
    requester.expectMsgType[BlockTxsReady].bundles shouldBe empty
    val placement = id("b")
    f.mempool = Vector(mempoolTx(placement, Seq(walletBox.boxId), Seq(orderBox)))
    requester.send(f.source, RequestBlockTxs(height, 8))
    requester.expectMsgType[BlockTxsReady].bundles shouldBe empty
    val before = f.observations.get()
    requester.send(f.source, RequestBlockTxs(height, 8, refresh = true))
    val bundles = requester.expectMsgType[BlockTxsReady].bundles
    bundles should have size 1
    bundles.head.members.head.id shouldBe placement
    bundles.head.members.last.inputIds should contain(orderBox.boxId)
    f.observations.get() should be > before
    f.stop()
  }

  it should "execute an order a wallet placed in the mempool, carrying the placement ahead of it" in {
    val f = new Fixture(discoverable = false)
    val placement = id("b")
    f.mempool = Vector(mempoolTx(placement, Seq(walletBox.boxId), Seq(orderBox)))

    val bundles = f.offeredOnceTracked()
    bundles should have size 1
    bundles.head.members.map(_.id) should have size 2
    bundles.head.members.head.id shouldBe placement
    bundles.head.members.head.kind shouldBe CandidateTx.MempoolAncestor
    bundles.head.members(1).inputIds should contain(orderBox.boxId)
    bundles.head.interactions should contain allOf(ChainFromMempool(placement), IncludeExisting(placement))
    bundles.head.carriesItsParents shouldBe true
    bundles.head.capital.map(_.value) shouldBe Seq(revenue)
    f.stop()
  }

  it should "not execute an order whose placement spends a pool or another order" in {
    val f = new Fixture(discoverable = false)
    f.mempool = Vector(mempoolTx(id("b"), Seq(poolBox.boxId, walletBox.boxId),
      Seq(poolBox.copy(boxId = id("d")), orderBox)))
    f.offered() shouldBe empty

    f.mempool = Vector(mempoolTx(id("c"), Seq(walletBox.boxId), Seq(orderBox)))
    f.offered() should not be empty
    f.stop()
  }

  it should "carry every P2PK ancestor before the placement and execution" in {
    val f = new Fixture(discoverable = false)
    val change = walletBox.copy(boxId = id("4"))
    val parent = mempoolTx(id("c"), Seq(walletBox.boxId), Seq(change))
    val placement = mempoolTx(id("b"), Seq(change.boxId), Seq(orderBox))
    f.mempool = Vector(placement, parent)
    val bundle = f.offered().head
    bundle.members.take(2).map(_.id) shouldBe Vector(parent.id, placement.id)
    bundle.members.last.inputIds should contain(orderBox.boxId)
    CandidateBundle.select(Seq(bundle), 3) should have size 3
    CandidateBundle.select(Seq(bundle), 2) shouldBe empty
    bundle.interactions should contain allOf(ChainFromMempool(parent.id), IncludeExisting(parent.id))

    f.mempool = Vector(placement, parent.copy(body = parent.body.copy(
      inputs = Seq(NodeInput(poolBox.boxId, NodeSpendingProof.empty)))))
    f.offered() shouldBe empty
    f.stop()
  }

  it should "not carry a placement whose input another unconfirmed transaction also spends" in {
    val f = new Fixture(discoverable = false)
    val placement = mempoolTx(id("b"), Seq(walletBox.boxId), Seq(orderBox))
    f.mempool = Vector(placement, mempoolTx(id("c"), Seq(walletBox.boxId)))
    f.offered() shouldBe empty

    f.mempool = Vector(placement)
    f.offered() should not be empty
    f.stop()
  }

  it should "offer nothing against a pool box an unconfirmed transaction created" in {
    val f = new Fixture()
    f.offeredOnceTracked() should not be empty

    f.mempool = Vector(mempoolTx(id("b"), Seq(id("7")), Seq(poolBox)))
    f.offered() shouldBe empty

    f.mempool = Vector.empty
    f.offered() should not be empty
    f.stop()
  }

  it should "forget an order that does not come back, until a scan finds it again" in {
    val f = new Fixture()
    f.offeredOnceTracked() should not be empty

    f.orders = Seq.empty
    f.offered() shouldBe empty

    f.orders = Seq(orderBox)
    f.offered() shouldBe empty

    f.source ! ErgoDexSource.ScanTick
    f.offeredOnceTracked() should not be empty
    f.stop()
  }

  "Broadcasting" should "not resend a refused execution until its pool moves" in {
    val f = new Fixture(broadcast = true)
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())

    val before = f.observations.get
    awaitAssert({ f.source ! ErgoDexSource.ScanTick; f.observations.get should be > before },
      30.seconds, 250.millis)
    Thread.sleep(3000)
    verify(f.api, times(1)).sendTransaction(anyString())

    f.pool = f.nodeContext.getClient.execute { ctx =>
      import node.MutationConversions._
      val moved = poolBox.copy(value = poolBox.value + 1000000000L)
      moved.copy(boxId = moved.toInputUTXO(ctx).id.toString())
    }
    val settled = f.observations.get
    awaitAssert({ f.source ! ErgoDexSource.ScanTick; f.observations.get should be > settled },
      30.seconds, 250.millis)
    verify(f.api, timeout(30000).times(2)).sendTransaction(anyString())
    f.stop()
  }

  it should "skip an order an unconfirmed transaction already claims" in {
    val f = new Fixture(broadcast = true)
    f.mempool = Vector(mempoolTx(id("b"), Seq(orderBox.boxId)))
    awaitAssert(f.observations.get should be >= 1, 30.seconds, 250.millis)

    Thread.sleep(3000)
    verify(f.api, times(0)).sendTransaction(anyString())

    f.mempool = Vector.empty
    awaitAssert({ f.source ! ErgoDexSource.ScanTick; f.observations.get should be > 1 }, 30.seconds, 250.millis)
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
    f.stop()
  }
}
