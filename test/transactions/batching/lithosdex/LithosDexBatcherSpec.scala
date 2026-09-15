package transactions.batching.lithosdex

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{BatchingConfig, LithosDexBatchingConfig}
import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderTerms, LDOrderContracts}
import node.model._
import node.{NodeApi, NodeError}
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{timeout, times, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.Batcher
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, RequestBlockTxs, Supersede}
import transactions.candidate.CandidateBundle
import work.lithos.mutations.{Contract, Token, UTXO}

import sigma.crypto.CryptoConstants
import sigma.data.{COR, ProveDlog}

import java.math.BigInteger
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object LithosDexBatcherSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The LithosDex batcher against a mocked node: what it offers this miner's block, what it displaces to
 * do so, when it closes a run with a flush, and what it broadcasts.
 */
class LithosDexBatcherSpec extends TestKit(ActorSystem("lithosdex-batcher-spec", LithosDexBatcherSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val anchor = "cc" * 32
  private val heights = new AtomicInteger(200000)
  private val Fee = 6000000L
  private val ownerNft = ErgoId.create("f0" * 32)

  private def id(seed: String): String = (seed * 64).take(64)

  private def mempoolTx(txId: String, spends: Seq[String], outputs: Seq[NodeBox] = Seq.empty) =
    CompleteMempool.MempoolTx(txId,
      NodeTransaction(txId, spends.map(NodeInput(_, NodeSpendingProof.empty)), Seq.empty, outputs), 100)

  private sealed trait Kind
  private case object Sell extends Kind
  private case object Redemption extends Kind

  /**
   * @param indexProvisions whether the index lists the provision at all
   * @param utxoProvisions  whether the UTXO set still holds it, independently of the index
   * @param undersized      adds a higher-fee sell whose reward box is under the node's minimum
   * @param observes        whether the engine answers a mempool observation at all
   * @param placed          the order is created by `placement`, a wallet's unconfirmed transaction, rather than confirmed
   * @param claimed         the order is created by `claim`, which settles the provision against the vault in the
   *                        same transaction, as the order API places a redemption with fees owed
   */
  private class Fixture(kind: Kind = Sell, broadcast: Boolean = false, autoFlush: Boolean = true,
                        indexProvisions: Boolean = true, utxoProvisions: Boolean = true,
                        undersized: Boolean = false, scanIntervalMs: Long = 3600000L,
                        maxSkippedOrders: Int = BatchingConfig.Default.maxSkippedOrders,
                        observes: Boolean = true, buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs,
                        placed: Boolean = false, claimed: Boolean = false,
                        maxAncestorTxs: Int = BatchingConfig.Default.maxAncestorTxs) {
    val api: NodeApi = mock[NodeApi]
    private val placementId = id("e")
    val walletBox: NodeBox = NodeBox(id("9"), id("8"), 2000000000L, 0, 100, "0008cd02" + "11" * 32)
    @volatile var walletUnspent: Boolean = true
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    val (pool, vault, order, provisions, extras) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, LDHelpers.getPoolNFT(ctx.getNetworkType), Fee, 2000000L)
      val poolBox = LDNodeFixtures.poolBox(ctx, reservesX = 10L * Parameters.OneErg, reservesY = 10000L * 1000000L,
        pendingX = 20000000L, pendingY = 30000000L)
      val vaultBox = LDNodeFixtures.vaultBox(ctx, balanceX = LDHelpers.VAULT_MIN)
      val (orderUtxo, provs) = kind match {
        case Sell =>
          UTXO(LDOrderContracts.swapSell(terms, Parameters.OneErg, 1L), Parameters.OneErg + Fee + Parameters.MinFee) -> Seq.empty[NodeBox]
        case Redemption =>
          UTXO(LDOrderContracts.redeem(terms), Parameters.MinFee + Fee, Seq(Token(ownerNft, 1L))) ->
            Seq(LDNodeFixtures.provisionBox(ctx, 10000000000L, ownerNft))
      }
      // An 80-key OR as owner makes the reward box 2,802 bytes, over what 1,000,000 nanoERG covers
      val bad = if (!undersized) Seq.empty[NodeBox] else {
        val g = CryptoConstants.dlogGroup
        val manyKeys = COR((1 to 80).map(i => ProveDlog(g.exponentiate(g.generator, BigInteger.valueOf(1000L + i)))))
        val badFee = Fee + 3000000L
        Seq(LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.swapSell(terms.copy(redeemer = manyKeys, executorFee = badFee),
          Parameters.OneErg, 1L), Parameters.OneErg + badFee + UTXO.MIN_CHANGE), index = 6, txId = "cd" * 32))
      }
      (poolBox, vaultBox, LDNodeFixtures.nodeBox(ctx, orderUtxo, index = if (claimed) 2 else if (placed) 0 else 5,
        txId = if (placed || claimed) placementId else "cd" * 32), provs, bad)
    }

    @volatile var mempool: Vector[CompleteMempool.MempoolTx] = Vector.empty
    val placement: CompleteMempool.MempoolTx = mempoolTx(placementId, Seq(walletBox.boxId), Seq(order))

    /** The provision `claim` leaves for the order, and the claim: vault, provision, wallet in; vault, provision, order, change, fee out. */
    val (claimedProvision, claim) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val successor = LDNodeFixtures.provisionBox(ctx, 10000000000L, ownerNft, index = 1, txId = placementId)
      val change = NodeBox(id("7"), placementId, 1000000000L, 3, 100, walletBox.ergoTree)
      val fee = NodeBox(id("6"), placementId, 2000000L, 4, 100, Contract.FEE.ergoTreeHex)
      successor -> mempoolTx(placementId, Seq(vault.boxId) ++ provisions.map(_.boxId) :+ walletBox.boxId,
        Seq(LDNodeFixtures.vaultBox(ctx, balanceX = LDHelpers.VAULT_MIN, index = 0, txId = placementId),
          successor, order, change, fee))
    }

    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh && observes)
          sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor,
            mempool.map(_.id).toSet, mempool.flatMap(_.body.inputs.map(_.boxId)).toSet, System.nanoTime(),
            transactions = mempool)), None)
        this
      }
    })

    private val orderTemplate = LDOrderContracts.template(LithosDexOrder.parse(order).get.kind).templateHash
    private val poolNft = LDHelpers.getPoolNFT(nodeContext.getNetwork).toString
    private val vaultNft = LDHelpers.getVaultNFT(nodeContext.getNetwork).toString

    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[String](0) == orderTemplate && !placed)
          Success((order +: extras).map(box => IndexedBox(box, "", 100, 1L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case `poolNft` => Success(Seq(IndexedBox(pool, "", 100, 2L)))
          case `vaultNft` => Success(Seq(IndexedBox(vault, "", 100, 3L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    // Honours the mempool option as the node does: excluding mempool-spent boxes hides a provision a
    // claim is spending, which is exactly what a block superseding that claim must still see
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val options = inv.getArgument[MempoolOptions](3)
        val spent = mempool.flatMap(_.body.inputs.map(_.boxId)).toSet
        if (indexProvisions && inv.getArgument[Paging](1).offset == 0)
          Success(provisions.filterNot(box => options.excludeMempoolSpent && spent.contains(box.boxId))
            .map(box => IndexedBox(box, "", 100, 4L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success((Seq(order, pool, vault) ++ extras ++ (if (utxoProvisions) provisions else Seq.empty) ++
        Seq(walletBox).filter(_ => walletUnspent))
        .filter(box => wanted.contains(box.boxId)))
    }
    when(api.sendTransaction(anyString())).thenReturn(Failure(NodeError.Rejected("refused by the test")))
    // The broadcast boundary reads the chain tip before it sends
    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(200000).copy(bestFullHeaderId = Some(anchor))))

    val batcher: ActorRef = system.actorOf(Props(new LithosDexBatcher(nodeContext,
      LithosDexBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = scanIntervalMs, broadcast = broadcast,
        maxSkippedOrders = maxSkippedOrders, maxAncestorTxs = maxAncestorTxs), autoFlush),
      servesCandidates = true, engine.ref, useTrueProp = false, buildBudgetMs)))

    /** Box ids of the last read of tracked orders, which is the read naming this fixture's own order. */
    def lastOrderRead(): Seq[String] = {
      val reads = ArgumentCaptor.forClass(classOf[Seq[String]])
      verify(api, org.mockito.Mockito.atLeastOnce()).boxesWithPoolByIds(reads.capture())
      reads.getAllValues.asScala.filter(_.contains(order.boxId)).last
    }

    /** Waits until a scan has finished: four template reads per scan, and a scan starts only after the last was delivered. */
    def awaitScanned(): Unit =
      verify(api, timeout(20000).atLeast(5))
        .unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])

    def offered(): Seq[CandidateBundle] = {
      val requester = TestProbe()
      requester.send(batcher, RequestBlockTxs(heights.incrementAndGet(), 8))
      requester.expectMsgType[BlockTxsReady].bundles
    }

    def offeredOnceTracked(): Seq[CandidateBundle] =
      awaitAssert({ val bundles = offered(); bundles should not be empty; bundles }, 30.seconds, 250.millis)

    def stop(): Unit = system.stop(batcher)
  }

  "The LithosDex batcher" should "close a fee-less run with a flush, superseding a direct swap on the pool" in {
    val f = new Fixture()
    val directSwap = id("b")
    f.mempool = Vector(mempoolTx(directSwap, Seq(f.pool.boxId), Seq(f.pool.copy(boxId = id("d")))))

    val bundle = f.offeredOnceTracked().head
    bundle.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind, LithosDexExecution.FlushKind)
    bundle.interactions shouldBe Seq(Supersede(Set(directSwap)))
    bundle.capital.map(_.value) shouldBe Seq(Fee)
    f.stop()
  }

  /** A candidate request carrying the placement makes the node evict it; every request here is a new height. */
  it should "keep executing a wallet-placed order after the node evicts its placement, until its input is spent" in {
    val f = new Fixture(placed = true)
    f.mempool = Vector(f.placement)
    val first = f.offered().head
    first.members.map(_.kind) shouldBe Vector(CandidateTx.MempoolAncestor, LithosDexExecution.Kind, LithosDexExecution.FlushKind)
    first.members.head.id shouldBe f.placement.id

    f.mempool = Vector.empty
    val carried = f.offered().head
    carried.members.head.id shouldBe f.placement.id
    carried.members(1).inputIds should contain(f.order.boxId)
    f.offered().head.members.head.id shouldBe f.placement.id

    f.walletUnspent = false
    f.offered() shouldBe empty
    withClue("a placement whose input was spent is forgotten, not held for when a read passes again: ") {
      f.walletUnspent = true
      f.offered() shouldBe empty
    }
    f.stop()
  }

  it should "execute no order a wallet has not yet confirmed when maxAncestorTxs is 0" in {
    val f = new Fixture(placed = true, maxAncestorTxs = 0)
    f.mempool = Vector(f.placement)
    f.offered() shouldBe empty
    f.stop()
  }

  it should "offer the run without a flush when autoFlush is off" in {
    val f = new Fixture(autoFlush = false)
    f.offeredOnceTracked().head.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind)
    f.stop()
  }

  it should "leave the flush out rather than displace a claim already spending the vault" in {
    val f = new Fixture()
    f.offeredOnceTracked().head.members.map(_.kind) should contain(LithosDexExecution.FlushKind)

    f.mempool = Vector(mempoolTx(id("b"), Seq(f.vault.boxId)))
    val bundle = f.offered().head
    bundle.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind)
    bundle.interactions shouldBe empty
    f.stop()
  }

  /**
   * A claim cannot be the competing spend here: it needs the ownership NFT, which the order holds, so it
   * would spend the order too and leave it withdrawn. A refresh needs no NFT, and a mempool-aware read
   * would hide the provision it spends.
   */
  it should "redeem against the owner's provision while a refresh spends it, displacing the refresh" in {
    val f = new Fixture(kind = Redemption)
    val refresh = id("b")
    f.mempool = Vector(mempoolTx(refresh, Seq(f.provisions.head.boxId)))

    val bundle = f.offeredOnceTracked().head
    bundle.members.head.inputIds should contain allOf(f.pool.boxId, f.provisions.head.boxId, f.order.boxId)
    bundle.interactions shouldBe Seq(Supersede(Set(refresh)))
    bundle.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind, LithosDexExecution.FlushKind)
    f.stop()
  }

  it should "redeem an order placed by the claim that settled its provision, against the provision the claim leaves" in {
    val f = new Fixture(kind = Redemption, claimed = true)
    f.mempool = Vector(f.claim)
    val bundle = f.offered().head
    withClue("the claim spends the vault, so the run carries it and goes without a flush: ") {
      bundle.members.map(_.kind) shouldBe Vector(CandidateTx.MempoolAncestor, LithosDexExecution.Kind)
    }
    bundle.members.head.id shouldBe f.claim.id
    bundle.members(1).inputIds should contain allOf(f.pool.boxId, f.claimedProvision.boxId, f.order.boxId)
    bundle.interactions.collect { case s: Supersede => s } shouldBe empty
    f.stop()
  }

  it should "offer no redemption against a provision the index lists but the chain has spent" in {
    val f = new Fixture(kind = Redemption)
    f.offeredOnceTracked() should not be empty
    // The same order and provision, with the provision gone from the UTXO set. The first scan runs a second after start.
    val spent = new Fixture(kind = Redemption, utxoProvisions = false)
    Thread.sleep(4000)
    spent.offered() shouldBe empty
    f.stop()
    spent.stop()
  }

  it should "execute the rest of a run when its highest-fee order cannot be built, then stop reading that order" in {
    val f = new Fixture(undersized = true, scanIntervalMs = 500L)
    val bad = f.extras.head.boxId
    // Both orders are tracked once a scan has finished. The first build must offer the good one; a retry
    // would hide an order the pass failed to move past.
    f.awaitScanned()
    val bundle = f.offered().headOption.getOrElse(fail("the first build to see both orders offered nothing"))
    bundle.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind, LithosDexExecution.FlushKind)
    bundle.members.head.inputIds should contain(f.order.boxId)
    bundle.members.flatMap(_.inputIds) should not contain bad
    bundle.capital.map(_.value) shouldBe Seq(Fee)

    // Later scans leave the refused order out, so builds stop reading it back
    awaitAssert({
      f.offered() should not be empty
      f.lastOrderRead() should not contain bad
    }, 20.seconds, 500.millis)
    f.stop()
  }

  it should "keep reading an unbuildable order back when maxSkippedOrders is 0" in {
    val f = new Fixture(undersized = true, scanIntervalMs = 500L, maxSkippedOrders = 0)
    val bad = f.extras.head.boxId
    f.awaitScanned()
    f.offered() should not be empty
    // Several scans after the refusal, which is when a remembered order would have left the tracked set
    Thread.sleep(2500)
    f.offered() should not be empty
    f.lastOrderRead() should contain(bad)
    f.stop()
  }

  it should "answer within its build budget when a mempool observation never arrives" in {
    val f = new Fixture(observes = false, buildBudgetMs = 500L)
    val asked = Deadline.now
    f.offered() shouldBe empty
    (Deadline.now - asked).toMillis should be < 5000L
    f.stop()
  }

  it should "offer nothing for a redemption whose provision cannot be found" in {
    val f = new Fixture(kind = Redemption)
    f.offeredOnceTracked() should not be empty
    // The same order with the provision missing from the index. The first scan runs a second after start.
    val none = new Fixture(kind = Redemption, indexProvisions = false)
    Thread.sleep(4000)
    none.offered() shouldBe empty
    f.stop()
    none.stop()
  }

  it should "broadcast the execution alone, never a flush" in {
    val f = new Fixture(broadcast = true)
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
    Thread.sleep(3000)
    verify(f.api, times(1)).sendTransaction(anyString())
    f.stop()
  }

  /** Module builds this batcher through Guice; see the ErgoDEX batcher spec for why instantiation is not run. */
  it should "be buildable by Module from configuration alone" in {
    import scala.collection.JavaConverters._
    val point = com.google.inject.spi.InjectionPoint.forConstructorOf(classOf[LithosDexBatcher])
    point.getDependencies.asScala.map(_.getKey) shouldBe Seq(
      com.google.inject.Key.get(classOf[configs.NodeContext]),
      com.google.inject.Key.get(classOf[play.api.Configuration]),
      com.google.inject.Key.get(classOf[ActorRef], com.google.inject.name.Names.named("transaction-engine")))

    // Shipped with block transactions off, so the stratum never asks and the batcher stays idle. Turned
    // on, the shipped LithosDex source serves this miner's block, so a request really builds.
    val shipped = play.api.Configuration(LithosDexBatcherSpec.config)
    transactions.batching.Batcher.servesCandidates(shipped, LithosDexBatchingConfig.Name) shouldBe false
    val mining = play.api.Configuration(ConfigFactory.parseString("stratum.candidate.blockTransactions = true")
      .withFallback(LithosDexBatcherSpec.config))
    transactions.batching.Batcher.servesCandidates(mining, LithosDexBatchingConfig.Name) shouldBe true

    val (nodeContext, _, _) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    // A stale observation, so the build offers nothing at once rather than waiting out the ask
    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh)
          sender ! CompleteMempool.Observation(1L, None, Some("no mempool in this test"))
        this
      }
    })
    val batcher = system.actorOf(Props(classOf[LithosDexBatcher], nodeContext, mining, engine.ref))
    val requester = TestProbe()
    requester.send(batcher, RequestBlockTxs(heights.incrementAndGet(), 8))
    requester.expectMsgType[BlockTxsReady].bundles shouldBe empty
    system.stop(batcher)
  }
}
