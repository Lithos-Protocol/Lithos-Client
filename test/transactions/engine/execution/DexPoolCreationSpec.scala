package transactions.engine.execution

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActor, TestKit, TestProbe}
import api.LithosApiErrors.LithosBadRequest
import api.models._
import api.{IndexedPool, LDPoolIndex, PoolIndexSnapshot}
import configs.VerifiedPool
import lithosdex.{LDGenesisBuilder, LDHelpers}
import node.MutationConversions._
import node.{NodeApi, NodeError}
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{ChainFixtures, FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.LDPoolCreation
import transactions.engine.wallet.EngineFunding
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{Token, UTXO}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Creating a pool through the API, against a fixed index snapshot and a probe standing in for the wallet: what
 * the quote says, which requests are refused before any funds are reserved, and that every box the chain
 * passes from one transaction to the next is held before the first send.
 */
class DexPoolCreationSpec extends TestKit(ActorSystem("dex-pool-creation-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val tokenX = ErgoId.create("c4" * 32)
  private val tokenY = ErgoId.create("c5" * 32)
  private val AmountX = (100L * Parameters.OneErg).toString
  private val AmountY = "1000000000"

  private def indexed(kind: VerifiedPool.Kind, nft: String, x: Option[ErgoId], y: ErgoId, verified: Boolean) =
    IndexedPool(kind, nft, "00" * 32, 100, x.map(_.toString), y.toString, 0L, 0L, 0L, 0L, 0L, Seq.empty, 0L, "", "",
      verified, None, None, None)

  private val listed = Seq(
    indexed(VerifiedPool.Kind.N2T, "b1" * 32, None, tokenY, verified = true),
    indexed(VerifiedPool.Kind.T2T, "b2" * 32, Some(tokenY), tokenX, verified = false),
    indexed(VerifiedPool.Kind.N2T, "b3" * 32, None, tokenX, verified = false))

  private case class Fixture(creation: DexPoolCreation, wallet: TestProbe, node: configs.NodeContext, api: NodeApi)

  private def fixture(status: String = "ready"): Fixture = {
    val api = mock[NodeApi]
    val (node, _, _) = FakeNodeContext(api, numAddresses = 1)
    val index = new LDPoolIndex(node, system) {
      override def snapshot(): PoolIndexSnapshot =
        PoolIndexSnapshot(status, 100, truncated = false, if (status == "ready") listed else Seq.empty, Map.empty)
    }
    val probe = TestProbe()
    Fixture(new DexPoolCreation(node, EngineFunding(probe.ref, 2.seconds, ec), index), probe, node, api)
  }

  private val n2tPlan = LDPoolCreation.Plan(None, tokenY, 100L * Parameters.OneErg, 1000000000L,
    LDHelpers.GENESIS_FEE_PARAMS.toSeq)

  "A creation quote" should "put the whole liquidity in the creator's provision, all but MIN_SUPPLY of the genesis supply" in {
    val q = fixture().creation.check(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY))
    q.kind shouldBe "n2t"
    (q.reserveX, q.reserveY) shouldBe ((AmountX, AmountY))
    q.shares shouldBe LDGenesisBuilder.GENESIS_PROVISION_SHARES.toString
    q.lockedShares shouldBe LDHelpers.MIN_SUPPLY.toString
    q.lockedShare shouldBe 1e-6
    q.lockedErg shouldBe LDHelpers.VAULT_MIN.toString
    q.lockedTokens shouldBe empty
    q.totalErgRequired shouldBe n2tPlan.ergRequired.toString
    q.tokensRequired shouldBe Seq(LDTokenAmount(tokenY.toString, AmountY))
    q.networkFee shouldBe (4 * Parameters.MinFee).toString
    q.feeParams shouldBe LDFeeParams(LDHelpers.GENESIS_FEE_PARAMS, LDHelpers.FEE_DENOM)
  }

  it should "name the pools already trading the pair, with either token first" in {
    val f = fixture()
    val t2t = f.creation.check(LDPoolCreateRequest(tokenX.toString, tokenY.toString, "5000000000", "2000000000"))
    t2t.warnings shouldBe Seq(s"pool ${"b2" * 32} already trades this pair")
    t2t.kind shouldBe "t2t"
    t2t.lockedErg shouldBe (LDHelpers.T2T_POOL_MIN_VALUE + LDHelpers.T2T_VAULT_MIN_VALUE).toString
    t2t.lockedTokens shouldBe Seq(LDTokenAmount(tokenX.toString, "1"), LDTokenAmount(tokenY.toString, "1"))
    f.creation.check(LDPoolCreateRequest("ERG", tokenY.toString, (20000L * Parameters.OneErg).toString, AmountY))
      .warnings shouldBe Seq(s"pool ${"b1" * 32} already trades this pair, and is on the verified list")
    fixture(status = "building").creation.check(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY))
      .warnings.head should include("still building")
  }

  it should "warn when the ERG an ERG/token pool must keep is more than its locked share" in {
    val c = fixture().creation
    c.check(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY)).warnings.last should include("full exit")
    c.check(LDPoolCreateRequest("ERG", tokenY.toString, (20000L * Parameters.OneErg).toString, AmountY))
      .warnings.exists(_.contains("full exit")) shouldBe false
    c.check(LDPoolCreateRequest(tokenX.toString, tokenY.toString, "10", "10")).warnings.exists(_.contains("full exit")) shouldBe false
  }

  "A creation request" should "be refused when its tokens, amounts or fees cannot make a pool" in {
    val c = fixture().creation
    def refused(r: LDPoolCreateRequest, says: String) =
      (the[LithosBadRequest] thrownBy c.check(r)).getMessage should include(says)
    refused(LDPoolCreateRequest(tokenY.toString, "ERG", AmountX, AmountY), "'tokenY' must be a 64-character hex")
    refused(LDPoolCreateRequest(tokenY.toString, tokenY.toString, AmountX, AmountY), "must be different")
    refused(LDPoolCreateRequest("c4", tokenY.toString, AmountX, AmountY), "or ERG")
    refused(LDPoolCreateRequest("ERG", tokenY.toString, (LDHelpers.MIN_RENT - 1L).toString, AmountY), "at least 10000000")
    refused(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, "0"), "must be positive")
    refused(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY,
      feeParams = Some(LDFeeParams(9985L, 15L, 15L, 1000L))), "feeDenom 10000")
    refused(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY,
      feeParams = Some(LDFeeParams(0L, 15L, 15L, LDHelpers.FEE_DENOM))), "dexFeeNum in (0")
    refused(LDPoolCreateRequest("ERG", tokenY.toString, Long.MaxValue.toString, AmountY), "overflow")
  }

  it should "take the creator's own fees" in {
    fixture().creation.check(LDPoolCreateRequest("ERG", tokenY.toString, AmountX, AmountY,
      feeParams = Some(LDFeeParams(9970L, 0L, 30L, LDHelpers.FEE_DENOM)))).feeParams shouldBe
      LDFeeParams(9970L, 0L, 30L, LDHelpers.FEE_DENOM)
  }

  private def execute(ack: Option[Boolean]) = LDPoolCreateExecuteRequest("ERG", tokenY.toString, AmountX, AmountY,
    acknowledgeLocked = ack)

  "A creation" should "need acknowledgeLocked before any funds are reserved" in {
    val f = fixture()
    (the[LithosBadRequest] thrownBy f.creation.create(execute(None))).getMessage should include("acknowledgeLocked")
    a[LithosBadRequest] should be thrownBy f.creation.create(execute(Some(false)))
    f.wallet.expectNoMessage(200.millis)
  }

  it should "hold every box the chain passes on before the first send, and give all back when that send fails" in {
    val f = fixture()
    val result = Future(Try(f.creation.create(execute(Some(true)))))

    val select = f.wallet.expectMsgType[SelectInputs](10.seconds)
    (select.erg, select.tokens) shouldBe ((n2tPlan.fundingValue, n2tPlan.tokens))
    val funding = f.node.getClient.execute { ctx: BlockchainContext =>
      LDNodeFixtures.nodeBox(ctx, UTXO(f.node.getNodeWallet.contract, select.erg, select.tokens), index = 0,
        txId = "9b" * 32).toInputUTXO(ctx)
    }
    f.wallet.reply(WalletInputs(Seq(funding), select.reservationId))

    // In chain order: the change after each NFT mint, then both pool NFT boxes and the change genesis spends
    val held = (1 to 5).map { _ =>
      val m = f.wallet.expectMsgType[ReserveKnownInputs](10.seconds)
      f.wallet.reply(WalletInputs(m.inputs, m.reservationId))
      m
    }
    held.foreach(_.inputs should have size 1)
    val boxes = held.map(_.inputs.head)
    boxes(2).tokens shouldBe Seq(Token(funding.id, 1L))
    boxes(3).tokens shouldBe Seq(Token(boxes(0).id, 1L))
    boxes.map(_.contract.ergoTreeHex).distinct shouldBe Seq(f.node.getNodeWallet.contract.ergoTreeHex)

    // The first send stops on a stale mempool, before anything reaches the node
    f.wallet.expectMsg(10.seconds, CompleteMempool.Refresh)
    f.wallet.reply(CompleteMempool.Observation(0L, None, Some("stale for this test")))
    val released = f.wallet.receiveN(7, 10.seconds).collect { case ReleaseInputs(id) => id }.toSet
    released shouldBe held.map(_.reservationId).toSet + select.reservationId
    Await.result(result, 10.seconds).failed.get.getMessage should include("stale for this test")
  }

  private val TxIdField = "\"id\"\\s*:\\s*\"([0-9a-f]{64})\"".r

  it should "stop at the first send the node refuses, and give back what it held for the sends after it" in {
    val f = fixture()
    val anchor = "ce" * 32
    when(f.api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    val sends = new AtomicInteger()
    when(f.api.sendTransaction(anyString())).thenAnswer { inv =>
      val id = TxIdField.findFirstMatchIn(inv.getArgument[String](0)).get.group(1)
      if (sends.getAndIncrement() == 0) Success("\"" + id + "\"") else Failure(NodeError.Rejected("Double spending attempt"))
    }
    val held = new ConcurrentLinkedQueue[(String, String)]()
    val released = new ConcurrentLinkedQueue[String]()
    f.wallet.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        msg match {
          case s: SelectInputs => sender ! WalletInputs(Seq(f.node.getClient.execute { ctx: BlockchainContext =>
            LDNodeFixtures.nodeBox(ctx, UTXO(f.node.getNodeWallet.contract, s.erg, s.tokens), index = 0,
              txId = "9c" * 32).toInputUTXO(ctx)
          }), s.reservationId)
          case k: ReserveKnownInputs =>
            held.add(k.inputs.head.id.toString -> k.reservationId)
            sender ! WalletInputs(k.inputs, k.reservationId)
          case CompleteMempool.Refresh => sender ! CompleteMempool.Observation(1L,
            Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None)
          case _: PinEngineInputs => sender ! true
          case ReleaseInputs(id) => released.add(id)
          case _ =>
        }
        TestActor.KeepRunning
      }
    })

    val created = f.creation.create(execute(Some(true)))
    created.outcome shouldBe "rejected"
    created.steps.map(s => s.step -> s.outcome) shouldBe Seq(LDPoolCreation.PoolNftStep -> "accepted",
      LDPoolCreation.VaultNftStep -> "rejected", LDPoolCreation.OwnerNftStep -> DexPoolCreation.Unsent,
      LDPoolCreation.GenesisStep -> DexPoolCreation.Unsent)
    sends.get shouldBe 2
    // The vault mint spent the first held box, and its refusal frees that one; the rest come back here
    val reservations = held.asScala.toSeq.map(_._2)
    awaitAssert(released.asScala.toSet shouldBe reservations.drop(1).toSet, 5.seconds)
  }
}
