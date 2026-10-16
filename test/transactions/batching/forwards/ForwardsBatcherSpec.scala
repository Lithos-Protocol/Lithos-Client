package transactions.batching.forwards

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{ForwardsBatchingConfig, NodeContext}
import forwards.contracts.DFTerms
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{ErgoClient, NetworkType, Parameters}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{timeout, verify, when}
import org.scalatest.{BeforeAndAfterAll, LoneElement}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import transactions.candidate.BlockTxMessages.{BlockTxsReady, RequestBlockTxs, Supersede}
import transactions.candidate.CandidateBundle
import transactions.engine.wallet.EngineWalletMessages._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Success

object ForwardsBatcherSpec {
  /** Contract signing and a mempool observation each outlast akka's 3-second default. */
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The forwards batcher against a mocked node and engine: what it offers this miner's block and when, what it
 * leaves alone in the mempool, and what it broadcasts with whose funds.
 */
class ForwardsBatcherSpec extends TestKit(ActorSystem("forwards-batcher-spec", ForwardsBatcherSpec.config))
  with AnyFlatSpecLike with ForwardsFixtures with BeforeAndAfterAll with MockitoSugar with LoneElement {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val anchor = "cc" * 32

  /** 100 blocks into epoch 964, past the default broadcastAfterBlocks of 96. */
  private val LateLastBlock = 964 * 128 + 100

  private def mempoolTx(txId: String, spends: Seq[String], outputs: Seq[NodeBox]) =
    CompleteMempool.MempoolTx(txId, NodeTransaction(txId, spends.map(NodeInput(_, NodeSpendingProof.empty)),
      Seq.empty, outputs), 100)

  /**
   * @param lastBlock the chain's last block, so the tip is one above it
   * @param accEpoch  the confirmed accumulator's last recorded epoch
   * @param windows   a position per window, long the maker and short the taker
   */
  private class Fixture(lastBlock: Int = fixtureLastBlock, broadcast: Boolean = false,
                        accEpoch: Int = fixtureEpoch - 1, windows: Seq[DFTerms] = Seq.empty,
                        deployed: Boolean = true, scanIntervalMs: Long = 3600000L) {
    val api: NodeApi = mock[NodeApi]
    val client: ErgoClient = lastBlockClient(lastBlock)
    val wallet: NodeWallet = walletOf(client)
    val tip: Int = lastBlock + 1
    val nodeContext: NodeContext = new NodeContext {
      override def getNetwork: NetworkType = NetworkType.MAINNET
      override def getExplorerURL: String = ""
      override def getClient: ErgoClient = client
      override def getNodeWallet: NodeWallet = wallet
      override def getNodeKey: String = "test-api-key"
      override def getNodeApi: NodeApi = api
      override def getNodeUrl: String = "http://127.0.0.1:9052/"
    }

    val (confirmedAcc, positions, funding) = client.execute { ctx =>
      (nodeBox(ctx, accUTXO(AccState(accEpoch, flatRing(STRIKE), 100L * LIT)), 0, "aa" * 32),
        windows.zipWithIndex.map { case (t, i) =>
          nodeBox(ctx, positionUTXO(maker(ctx), taker(ctx), t), i + 1, "cd" * 32)
        },
        walletBox(ctx, wallet, 10L * Parameters.MinFee))
    }

    /** The accumulator after a spend from the confirmed one, as an unconfirmed output of `txId`. */
    def successor(txId: String, lastEpoch: Int): NodeBox = client.execute { ctx =>
      nodeBox(ctx, accUTXO(AccState(lastEpoch, flatRing(STRIKE), 99L * LIT)), 0, txId)
    }

    /** The confirmed accumulator the node reports; a test replaces it to model a block that recorded. */
    @volatile var accBox: NodeBox = confirmedAcc

    @volatile var mempool: Vector[CompleteMempool.MempoolTx] = Vector.empty
    val selections = new ConcurrentLinkedQueue[SelectInputs]()
    val pins = new ConcurrentLinkedQueue[PinEngineInputs]()
    val uncertain = new ConcurrentLinkedQueue[String]()
    val observations = new AtomicInteger(0)
    val sent = new ConcurrentLinkedQueue[String]()

    val engine: TestProbe = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        msg match {
          case CompleteMempool.Refresh =>
            observations.incrementAndGet()
            sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor, mempool.map(_.id).toSet,
              mempool.flatMap(_.body.inputs.map(_.boxId)).toSet, System.nanoTime(), transactions = mempool)), None)
          case select: SelectInputs =>
            selections.add(select)
            sender ! WalletInputs(Seq(funding), select.reservationId)
          case HoldReservationForCandidate(id) => sender ! ReservationHeldForCandidate(id, accepted = true)
          case pin: PinEngineInputs =>
            pins.add(pin)
            sender ! true
          case MarkReservationUncertain(id) => uncertain.add(id)
          case _ => ()
        }
        this
      }
    })

    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(tip).copy(bestFullHeaderId = Some(anchor))))
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[String](0) == contracts.accNFT.toString) Success(Seq(IndexedBox(accBox, "", 100, 1L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[String](0) == contracts.position.ergoTreeHex && inv.getArgument[Paging](1).offset == 0)
          Success(positions.map(box => IndexedBox(box, "", 100, 2L)))
        else Success(Seq.empty[IndexedBox])
      }
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success((accBox +: positions).filter(box => wanted.contains(box.boxId)))
    }
    when(api.sendTransaction(anyString())).thenAnswer { inv =>
      val json = inv.getArgument[String](0)
      sent.add(json)
      Success("\"" + """"id"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(json).get.group(1) + "\"")
    }

    val batcher: ActorRef = system.actorOf(Props(new ForwardsBatcher(nodeContext,
      ForwardsBatchingConfig(ForwardsBatchingConfig.Default.batching.copy(scanIntervalMs = scanIntervalMs,
        broadcast = broadcast), ForwardsBatchingConfig.Default.broadcastAfterBlocks),
      servesCandidates = true, engine.ref, if (deployed) Some(contracts) else None, 5.seconds)))

    /** Waits for a scan to have read the positions, which it does after recording what it read of the accumulator. */
    def awaitScanned(): Unit =
      verify(api, timeout(20000).atLeastOnce())
        .unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])

    /** What this miner's block for the tip's successor is offered. `refresh` rebuilds rather than replaying. */
    def offered(refresh: Boolean = true): Seq[CandidateBundle] = {
      val requester = TestProbe()
      requester.send(batcher, RequestBlockTxs(tip + 1, 10, refresh))
      requester.expectMsgType[BlockTxsReady].bundles
    }

    def stop(): Unit = system.stop(batcher)
  }

  // ─── this miner's block ───────────────────────────────────────────────────

  "The forwards batcher" should "offer the epoch's recording on its own, funded by one held confirmed wallet box" in {
    val f = new Fixture()
    f.awaitScanned()
    val bundle = f.offered().loneElement
    bundle.members.map(_.kind) shouldBe Vector(ForwardsTransactions.Kind)
    bundle.members.head.inputIds shouldBe Set(f.accBox.boxId, f.funding.id.toString)
    bundle.interactions shouldBe empty

    val selection = f.selections.asScala.loneElement
    (selection.confirmedOnly, selection.single) shouldBe ((true, true))
    withClue("a refresh carries the same recording, and holds no second box: ") {
      f.offered().loneElement.members.head.id shouldBe bundle.members.head.id
      f.selections.size shouldBe 1
    }
    f.uncertain shouldBe empty
    f.stop()
  }

  it should "give the held wallet box back as uncertain once the epoch is recorded by someone else" in {
    val f = new Fixture()
    f.awaitScanned()
    f.offered().loneElement
    val reservation = f.selections.asScala.loneElement.reservationId
    f.uncertain shouldBe empty

    // Another miner's block recorded the epoch: this block has nothing to record, and the box is not needed
    f.accBox = f.client.execute(ctx => nodeBox(ctx, accUTXO(AccState(fixtureEpoch, flatRing(STRIKE), 99L * LIT)), 0, "a2" * 32))
    f.offered() shouldBe empty
    awaitAssert(f.uncertain.asScala.toSeq shouldBe Seq(reservation))
    f.stop()
  }

  it should "give the held wallet box back when it stops" in {
    val f = new Fixture()
    f.awaitScanned()
    f.offered().loneElement
    val reservation = f.selections.asScala.loneElement.reservationId
    f.stop()
    awaitAssert(f.uncertain.asScala.toSeq shouldBe Seq(reservation))
  }

  it should "settle a due position in the block, carrying the recording in it instead of using a wallet box" in {
    val ended = terms(fixtureEpoch - 20, 8)
    val f = new Fixture(windows = Seq(ended))
    f.awaitScanned()
    // Until the scan's result reaches the mailbox the position is not tracked, and only the recording is offered
    val bundle = awaitAssert({
      val offered = f.offered().loneElement
      offered.members.head.inputIds should contain(f.positions.head.boxId)
      offered
    }, 30.seconds, 250.millis)
    bundle.members should have size 1
    bundle.members.head.inputIds shouldBe Set(f.accBox.boxId, f.positions.head.boxId)
    f.stop()
  }

  it should "offer nothing past broadcastAfterBlocks while broadcasting, and the recording when not" in {
    val quiet = new Fixture(lastBlock = LateLastBlock)
    ForwardsBatcher.blocksIntoEpoch(quiet.tip) shouldBe 100
    quiet.awaitScanned()
    quiet.offered().loneElement.members should have size 1
    quiet.stop()

    val broadcasting = new Fixture(lastBlock = LateLastBlock, broadcast = true)
    broadcasting.awaitScanned()
    broadcasting.offered() shouldBe empty
    broadcasting.stop()
  }

  it should "leave the accumulator to a live mempool spend, and build again once it is gone" in {
    val f = new Fixture()
    // A settlement someone broadcast: it deposits but records nothing, so it stays valid in the next block
    f.mempool = Vector(mempoolTx("b1" * 32, Seq(f.accBox.boxId), Seq(f.successor("b1" * 32, fixtureEpoch - 1))))
    f.awaitScanned()
    f.offered() shouldBe empty
    f.mempool = Vector.empty
    f.offered().loneElement.members should have size 1
    f.stop()
  }

  it should "supersede a mempool recording of an epoch already over, which no block can include" in {
    val f = new Fixture(accEpoch = fixtureEpoch - 2)
    val stale = "b2" * 32
    f.mempool = Vector(mempoolTx(stale, Seq(f.accBox.boxId), Seq(f.successor(stale, fixtureEpoch - 1))))
    f.awaitScanned()
    val bundle = f.offered().loneElement
    bundle.interactions shouldBe Seq(Supersede(Set(stale)))
    bundle.members.head.inputIds should contain(f.accBox.boxId)
    f.stop()
  }

  it should "stay idle on a network with no accumulator deployed" in {
    val f = new Fixture(deployed = false)
    verify(f.api, org.mockito.Mockito.after(2500).never()).info()
    f.offered() shouldBe empty
    f.stop()
  }

  // ─── broadcasts ───────────────────────────────────────────────────────────

  it should "broadcast the recording past broadcastAfterBlocks, its fee paid by a wallet box the engine pins" in {
    val f = new Fixture(lastBlock = LateLastBlock, broadcast = true)
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
    f.sent.asScala.loneElement should include(f.accBox.boxId)
    val pin = f.pins.asScala.loneElement
    pin.hold.walletInputIds shouldBe Set(f.funding.id.toString)
    f.selections.asScala.loneElement.erg shouldBe Parameters.MinFee + work.lithos.mutations.UTXO.MIN_CHANGE
    f.stop()
  }

  it should "not broadcast onto a mempool recording of an epoch already over, until the node drops it" in {
    val f = new Fixture(lastBlock = LateLastBlock, broadcast = true, accEpoch = fixtureEpoch - 2, scanIntervalMs = 2000L)
    val stale = "b3" * 32
    f.mempool = Vector(mempoolTx(stale, Seq(f.accBox.boxId), Seq(f.successor(stale, fixtureEpoch - 1))))
    // Two passes have each taken an observation and found only the stale tip to build on
    awaitAssert(f.observations.get should be >= 2, 30.seconds, 250.millis)
    verify(f.api, org.mockito.Mockito.never()).sendTransaction(anyString())

    f.mempool = Vector.empty
    verify(f.api, timeout(30000).times(1)).sendTransaction(anyString())
    f.stop()
  }
}
