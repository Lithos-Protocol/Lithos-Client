package transactions.batching.sigmafi

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{NodeContext, SigmaFiBatchingConfig}
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{ErgoClient, NetworkType, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, never, spy, timeout, verify, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.{BeforeAndAfterAll, LoneElement}
import org.scalatestplus.mockito.MockitoSugar
import sigmafi.contracts.SigmaFiContracts
import state.synchronization.CompleteMempool
import support.FakeNodeContext
import transactions.candidate.BlockTxMessages.{BlockTxsReady, RequestBlockTxs, Supersede}
import transactions.candidate.CandidateBundle
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.Token

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Success

object SigmaFiBatcherSpec {
  /** Contract signing and a mempool observation each outlast akka's 3-second default. */
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The SigmaFi batcher against a mocked node and engine: which bonds this miner's block liquidates, which pending
 * spends it supersedes, and which bonds it broadcasts and when.
 */
class SigmaFiBatcherSpec extends TestKit(ActorSystem("sigmafi-batcher-spec", SigmaFiBatcherSpec.config))
  with AnyFlatSpecLike with SigmaFiFixtures with BeforeAndAfterAll with MockitoSugar with LoneElement {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val anchor = "cc" * 32
  private val pendingTx = "77" * 32

  private class Fixture(broadcast: Boolean = false, maxInputs: Int = Int.MaxValue) {
    val api: NodeApi = mock[NodeApi]
    val client: ErgoClient = FakeNodeContext.offlineClient()
    private val prover = spy(FakeNodeContext.proverWith(client, 1))
    doAnswer { invocation: org.mockito.invocation.InvocationOnMock =>
      require(invocation.getArgument[UnsignedTransaction](0).getInputs.size <= maxInputs, "batch cost limit")
      invocation.callRealMethod()
    }.when(prover).sign(any[UnsignedTransaction])
    val wallet: NodeWallet = NodeWallet(prover)
    val tip: Int = client.execute(_.getHeight)
    val nodeContext: NodeContext = new NodeContext {
      override def getNetwork: NetworkType = NetworkType.MAINNET
      override def getExplorerURL: String = ""
      override def getClient: ErgoClient = client
      override def getNodeWallet: NodeWallet = wallet
      override def getNodeKey: String = "test-api-key"
      override def getNodeApi: NodeApi = api
      override def getNodeUrl: String = "http://127.0.0.1:9052/"
    }

    private def bond(index: Int, maturity: Int, repayToken: Option[ErgoId] = None, fee: Long = 50000000L,
                     liqHash: Array[Byte] = liq.hash): NodeBox = client.execute { ctx =>
      nodeBox(ctx, bondUTXO(repayToken, contractOf(miner(ctx)), maturity, collateral = Seq(Token(tokenB, index.toLong)),
        fee = fee, asset = ErgoId.create(f"$index%02x" * 32), liqHash = liqHash), index, "e7" * 32)
    }

    /** Past the block-first window, so broadcastable. */
    val pastWindow: NodeBox = bond(1, tip - 10)
    /** Due in the block being built, so still inside the block-first window. */
    val inWindow: NodeBox = bond(2, tip + 1)
    val notDue: NodeBox = bond(3, tip + 5)
    val underFloor: NodeBox = bond(4, tip - 10, fee = 10000000L)
    val otherScript: NodeBox = bond(5, tip - 10, liqHash = Array.fill(32)(1.toByte))
    val tokenBond: NodeBox = bond(6, tip - 20, repayToken = Some(tokenA))
    /** Due, but a pending transaction already liquidates it. */
    val pending: NodeBox = bond(7, tip - 30)

    val ergBonds: Seq[NodeBox] = Seq(pastWindow, inWindow, notDue, underFloor, otherScript, pending)
    val all: Seq[NodeBox] = ergBonds :+ tokenBond

    val mempool: Vector[CompleteMempool.MempoolTx] = Vector(CompleteMempool.MempoolTx(pendingTx,
      NodeTransaction(pendingTx, Seq(NodeInput(pending.boxId, NodeSpendingProof.empty)), Seq.empty, Seq.empty), 100))
    val sent = new ConcurrentLinkedQueue[String]()

    val engine: TestProbe = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        msg match {
          case CompleteMempool.Refresh =>
            sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor, mempool.map(_.id).toSet,
              mempool.flatMap(_.body.inputs.map(_.boxId)).toSet, System.nanoTime(), transactions = mempool)), None)
          case _: PinEngineInputs => sender ! true
          case _ => ()
        }
        this
      }
    })

    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(tip).copy(bestFullHeaderId = Some(anchor))))
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(tip, tip)))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[String](0) == SigmaFiContracts.ergBond.ergoTreeHex && inv.getArgument[Paging](1).offset == 0)
          Success(ergBonds.map(box => IndexedBox(box, "", 100, 2L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[Paging](1).offset == 0) Success(Seq(IndexedBox(tokenBond, "", 100, 3L)))
        else Success(Seq.empty[IndexedBox])
      }
    // The pool-aware read leaves out what the mempool spends; the confirmed read still has it
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(all.filter(b => wanted.contains(b.boxId) && b.boxId != pending.boxId))
    }
    when(api.boxById(anyString())).thenAnswer(inv => Success(all.find(_.boxId == inv.getArgument[String](0))))
    when(api.sendTransaction(anyString())).thenAnswer { inv =>
      val json = inv.getArgument[String](0)
      sent.add(json)
      Success("\"" + """"id"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(json).get.group(1) + "\"")
    }

    val batcher: ActorRef = system.actorOf(Props(new SigmaFiBatcher(nodeContext,
      SigmaFiBatchingConfig(SigmaFiBatchingConfig.Default.batching.copy(scanIntervalMs = 3600000L, broadcast = broadcast),
        broadcastAfterBlocks = 2, minBatchingFeeNanoErg = 50000000L),
      servesCandidates = true, engine.ref, useTrueProp = false)))

    /** What this miner's block for the tip's successor is offered, once a scan has tracked the due bonds. */
    def offeredBatches(slots: Int = 10): Seq[CandidateBundle] = awaitAssert({
      val requester = TestProbe()
      requester.send(batcher, RequestBlockTxs(tip + 1, slots, refresh = true))
      val bundles = requester.expectMsgType[BlockTxsReady].bundles
      bundles should not be empty
      bundles
    }, 30.seconds, 250.millis)

    def offered(): CandidateBundle = offeredBatches().loneElement

    def stop(): Unit = system.stop(batcher)
  }

  "The SigmaFi batcher" should "liquidate every bond due in this miner's block in one transaction, its fees to the pool" in {
    val f = new Fixture()
    val bundle = f.offered()
    val member = bundle.members.loneElement
    member.kind shouldBe SigmaFiTransactions.Kind
    member.inputIds shouldBe Set(f.pastWindow, f.inWindow, f.tokenBond, f.pending).map(_.boxId)
    bundle.capital.loneElement.value shouldBe 4 * 50000000L
    f.stop()
  }

  it should "supersede a pending spend of a due bond, rather than leave the bond to it" in {
    val f = new Fixture()
    f.offered().interactions shouldBe Seq(Supersede(Set(pendingTx)))
    f.stop()
  }

  it should "leave out bonds not due, under the fee floor, or under another liquidation script" in {
    val f = new Fixture()
    val inputs = f.offered().members.loneElement.inputIds
    Seq(f.notDue, f.underFloor, f.otherScript).foreach(b => inputs should not contain b.boxId)
    f.stop()
  }

  it should "broadcast only bonds past the block-first window that nothing pending spends" in {
    val f = new Fixture(broadcast = true)
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
    val sent = f.sent.asScala.loneElement
    sent should include(f.pastWindow.boxId)
    sent should include(f.tokenBond.boxId)
    sent should not include f.inWindow.boxId
    sent should not include f.pending.boxId
    f.stop()
  }

  it should "broadcast nothing with broadcast off" in {
    val f = new Fixture()
    f.offered()
    verify(f.api, never()).sendTransaction(anyString())
    f.stop()
  }

  it should "split a combined build that exceeds the signing limit while retaining every valid bond" in {
    val f = new Fixture(maxInputs = 2)
    val bundles = f.offeredBatches()
    bundles should have size 2
    val inputs = bundles.flatMap(_.members.flatMap(_.inputIds))
    inputs.toSet shouldBe Set(f.pastWindow, f.inWindow, f.tokenBond, f.pending).map(_.boxId)
    inputs.distinct.size shouldBe inputs.size
    bundles.map(_.capital.loneElement.value).sum shouldBe 4 * 50000000L
    f.offeredBatches(slots = 1) should have size 1
    f.stop()
  }
}
