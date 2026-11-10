package transactions.engine.execution

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import node.MutationConversions._
import node.model._
import node.rest.NodeCodecs
import node.{NodeApi, NodeError}
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{ChainFixtures, FakeNodeContext, LDNodeFixtures}
import transactions.batching.arb.{ArbFixtures, ArbStake}
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.util.{Failure, Success, Try}

/**
 * The arb stake's two moves between the wallet and the arb address, each signed by the wallet's real key and
 * sent through the real send boundary to a node stub. These pin when the reserve sends and when it must not,
 * that its one protocol output is the stake at the arb address with everything else back at the wallet, and
 * that a reclaim stops arb first and returns every box at the address.
 */
class ArbReserveExecutionSpec extends TestKit(ActorSystem("arb-reserve-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  import ArbReserveExecution._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher

  private val Erg = Parameters.OneErg
  private val Stake = 10 * Erg
  private val anchor = "cc" * 32
  private val tokenId = "5e" * 32

  /**
   * Stands in for the engine: answers the balance, hands out `funding` for any selection, and lets every send
   * through. `selections` counts the selections asked for.
   */
  private class Owner(spendable: Long, funding: Seq[InputUTXO], selections: AtomicInteger,
                      onSelect: AtomicReference[() => Unit]) extends Actor {
    override def receive: Receive = {
      case GetSpendableBalance => sender() ! SpendableBalance(spendable)
      case request: SelectInputs =>
        selections.incrementAndGet()
        onSelect.get.apply()
        sender() ! WalletInputs(funding, request.reservationId)
      case CompleteMempool.Refresh =>
        sender() ! CompleteMempool.Observation(1L,
          Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None)
      case _: PinEngineInputs => sender() ! true
      case _ => ()
    }
  }

  /**
   * @param atAddress what the node's index lists at the arb address, set per test
   * @param sent      every transaction the node was handed, decoded
   */
  private case class Fixture(execution: ArbReserveExecution, stakes: ArbStake, api: NodeApi,
                             wallet: mutations.NodeWallet, atAddress: AtomicReference[Seq[NodeBox]],
                             pending: AtomicReference[Seq[NodeBox]],
                             sent: AtomicReference[Vector[NodeTransaction]], selections: AtomicInteger,
                             onSelect: AtomicReference[() => Unit],
                             stakeTree: String)

  /**
   * @param walletValues plain wallet boxes any selection is handed, all of them
   * @param tokenBox     adds a wallet box holding a token to that selection
   * @param respond      the node's answer to a send, given the transaction's own id
   */
  private def fixture(spendable: Long, walletValues: Seq[Long] = Seq(15 * Erg), tokenBox: Boolean = false,
                      respond: String => Try[String] = id => Success(id), target: Long = Stake,
                      limits: configs.WalletConfig = configs.WalletConfig.Default): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val stakeContract = ArbFixtures.stakeContract(wallet)
    val funding = ctx.getClient.execute { c =>
      val plain = walletValues.zipWithIndex.map { case (value, i) =>
        LDNodeFixtures.nodeBox(c, UTXO(wallet.contract, value), i, ArbFixtures.id(300)).toInputUTXO(c)
      }
      if (!tokenBox) plain
      else plain :+ LDNodeFixtures.nodeBox(c, UTXO(wallet.contract, Erg, Seq(Token(ErgoId.create(tokenId), 7L))), 0,
        ArbFixtures.id(301)).toInputUTXO(c)
    }
    val atAddress = new AtomicReference(Seq.empty[NodeBox])
    // Boxes only a read that includes the mempool lists
    val pending = new AtomicReference(Seq.empty[NodeBox])
    val sent = new AtomicReference(Vector.empty[NodeTransaction])
    val selections = new AtomicInteger()
    // Run as the wallet is asked for funding, for a test that changes something between the checks and the send
    val onSelect = new AtomicReference[() => Unit](() => ())
    when(api.indexerEnabled).thenReturn(true)
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val paging = inv.getArgument[Paging](1)
        val listed = atAddress.get ++ (if (inv.getArgument[MempoolOptions](3).includeUnconfirmed) pending.get else Seq.empty)
        Success(listed.filter(_.ergoTree == inv.getArgument[String](0))
          .slice(paging.offset, paging.offset + paging.limit).map(box => IndexedBox(box, "", 100, 1L)))
      }
    when(api.sendTransaction(anyString())).thenAnswer { inv =>
      val tx = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(inv.getArgument[String](0)).getAsJsonObject)
      sent.set(sent.get :+ tx)
      respond(tx.id)
    }
    val owner: ActorRef = system.actorOf(Props(new Owner(spendable, funding, selections, onSelect)))
    val stakes = new ArbStake(() => api, stakeContract, ctx.getNetwork, target)
    Fixture(new ArbReserveExecution(ctx, api, owner, stakes, limits), stakes, api, wallet, atAddress, pending, sent, selections, onSelect,
      stakeContract.ergoTreeHex)
  }

  /** A box at the fixture's arb address, with an id that is the hash of its contents. */
  private def stakeBox(f: Fixture, value: Long, seed: Int, tokens: Seq[Token] = Seq.empty): NodeBox =
    FakeNodeContext.offlineClient().execute((c: BlockchainContext) =>
      LDNodeFixtures.nodeBox(c, UTXO(Contract(sigma.ast.ErgoTree.fromHex(f.stakeTree)), value, tokens), 0,
        ArbFixtures.id(seed)))

  // ─── when the reserve sends ───────────────────────────────────────────────

  "A stake of full size at the arb address" should "be told apart from a smaller box, a token box and another script's" in {
    val tree = "aa"
    def at(value: Long, assets: Seq[NodeAsset] = Seq.empty, script: String = tree) =
      Seq(NodeBox("01" * 32, "02" * 32, value, 0, 100, script, assets))
    funded(at(Stake), tree, Stake) shouldBe true
    funded(at(Stake - 1), tree, Stake) shouldBe false
    funded(at(Stake, Seq(NodeAsset(tokenId, 1L))), tree, Stake) shouldBe false
    funded(at(Stake, script = "bb"), tree, Stake) shouldBe false
    funded(Seq.empty, tree, Stake) shouldBe false
  }

  "The reserve" should "send nothing while a box of the stake's size is at the arb address" in {
    val f = fixture(spendable = 100 * Erg)
    f.atAddress.set(Seq(stakeBox(f, Stake, 1)))
    f.execution.execute(() => true).outcome shouldBe OutcomeHeld
    f.selections.get shouldBe 0
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "send nothing while one is in the mempool on its way, and not call that a stake held" in {
    val f = fixture(spendable = 100 * Erg)
    f.pending.set(Seq(stakeBox(f, Stake, 1)))
    // Pending, so the engine looks again: a transaction that never confirms must not end the attempts
    f.execution.execute(() => true).outcome shouldBe OutcomePending
    f.selections.get shouldBe 0
    verify(f.api, never()).sendTransaction(anyString())
    // Once it is gone from the mempool unconfirmed, the stake is sent again
    f.pending.set(Seq.empty)
    f.execution.execute(() => true).outcome shouldBe EngineBroadcast.Accepted
  }

  it should "send nothing while the stake in use is of full size, though the address read does not list it" in {
    val f = fixture(spendable = 100 * Erg)
    val stake = stakeBox(f, Stake, 1)
    f.atAddress.set(Seq(stake))
    when(f.api.boxById(anyString())).thenAnswer(inv => Success(Some(stake).filter(_.boxId == inv.getArgument[String](0))))
    f.stakes.at(100, "p100").map(_.boxId) shouldBe Some(stake.boxId)
    // More boxes than one read returns now crowd the stake out of it
    f.atAddress.set(Seq.empty)
    f.execution.execute(() => true).outcome shouldBe OutcomeHeld
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "send nothing from a wallet under twice the stake, and say what the wallet holds" in {
    val f = fixture(spendable = 2 * Stake - 1)
    val status = f.execution.execute(() => true)
    status shouldBe Status(OutcomeShort, 2 * Stake - 1, Stake)
    f.selections.get shouldBe 0
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "send one box of exactly the stake to the arb address, with the change and every token back at the wallet" in {
    val f = fixture(spendable = 2 * Stake, walletValues = Seq(6 * Erg, 6 * Erg), tokenBox = true)
    // A smaller box someone sent there does not stand in for the stake
    f.atAddress.set(Seq(stakeBox(f, Stake - 1, 1)))
    val status = f.execution.execute(() => true)
    status.outcome shouldBe EngineBroadcast.Accepted
    f.selections.get shouldBe 1
    val tx = f.sent.get.head
    status.txId shouldBe Some(tx.id)
    tx.inputs.size shouldBe 3
    tx.outputs.head.ergoTree shouldBe f.stakeTree
    tx.outputs.head.value shouldBe Stake
    tx.outputs.head.assets shouldBe empty
    val fee = tx.outputs.filter(_.ergoTree == Contract.FEE.ergoTreeHex)
    fee.map(_.value) shouldBe Seq(Fee)
    // 13 ERG in: the stake, the fee, and everything else as change at the first address, token included
    val change = tx.outputs.filter(_.ergoTree == f.wallet.contract.ergoTreeHex)
    change.map(_.value).sum shouldBe 13 * Erg - Stake - Fee
    change.flatMap(_.assets) shouldBe Seq(NodeAsset(tokenId, 7L))
    tx.outputs.size shouldBe 1 + fee.size + change.size
  }

  it should "ask the wallet for the stake, the fee and room for a change box" in {
    // A wallet that hands over the stake and fee alone did not cover what was asked, so the funding is refused
    // as a shortfall before any build; change under a box's minimum could not be returned
    val f = fixture(spendable = 2 * Stake, walletValues = Seq(Stake + Fee + ChangeFloor - 1))
    a[mutations.NotEnoughInputsException] should be thrownBy f.execution.execute(() => true)
    verify(f.api, never()).sendTransaction(anyString())
    val enough = fixture(spendable = 2 * Stake, walletValues = Seq(Stake + Fee + ChangeFloor))
    enough.execution.execute(() => true).outcome shouldBe EngineBroadcast.Accepted
    enough.sent.get.head.outputs.filter(_.ergoTree == enough.wallet.contract.ergoTreeHex).map(_.value) shouldBe
      Seq(ChangeFloor)
  }

  it should "send nothing when the stake is paused after its checks, while the wallet is funding it" in {
    val f = fixture(spendable = 2 * Stake)
    f.onSelect.set(() => f.stakes.pause())
    an[IllegalArgumentException] should be thrownBy f.execution.execute(() => true)
    f.selections.get shouldBe 1
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "send nothing once the stake was reclaimed, without reading the node" in {
    val f = fixture(spendable = 100 * Erg)
    f.stakes.pause()
    f.execution.execute(() => true).outcome shouldBe OutcomePaused
    verify(f.api, never()).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "send nothing when the arb address cannot be read" in {
    val f = fixture(spendable = 100 * Erg)
    when(f.api.indexerEnabled).thenReturn(false)
    an[IllegalArgumentException] should be thrownBy f.execution.execute(() => true)
    f.selections.get shouldBe 0
    verify(f.api, never()).sendTransaction(anyString())
  }

  // ─── the reclaim ──────────────────────────────────────────────────────────

  "A reclaim" should "stop arb, then send every box at the arb address back to the first address" in {
    val f = fixture(spendable = 0L)
    val boxes = Seq(stakeBox(f, Stake, 1), stakeBox(f, 2 * Erg, 2, Seq(Token(ErgoId.create(tokenId), 3L))),
      stakeBox(f, 1000000L, 3))
    f.atAddress.set(boxes)
    val result = f.execution.reclaim(() => true).get
    result.outcome shouldBe EngineBroadcast.Accepted
    result.boxes shouldBe 3
    result.nanoErgs shouldBe Stake + 2 * Erg + 1000000L
    result.left shouldBe 0
    f.stakes.paused shouldBe true
    val tx = f.sent.get.head
    result.txId shouldBe tx.id
    tx.inputs.map(_.boxId).toSet shouldBe boxes.map(_.boxId).toSet
    tx.outputs.size shouldBe 2
    tx.outputs.head.ergoTree shouldBe f.wallet.contract.ergoTreeHex
    tx.outputs.head.value shouldBe result.nanoErgs - Fee
    tx.outputs.head.assets shouldBe Seq(NodeAsset(tokenId, 3L))
    tx.outputs(1).ergoTree shouldBe Contract.FEE.ergoTreeHex
    tx.outputs(1).value shouldBe Fee
    // No wallet box funds it: the fee comes out of the boxes reclaimed
    f.selections.get shouldBe 0
  }

  it should "leave a running stake running when the address holds nothing to send" in {
    val f = fixture(spendable = 0L)
    f.execution.reclaim(() => true) shouldBe None
    f.stakes.paused shouldBe false
    verify(f.api, never()).sendTransaction(anyString())
  }

  it should "leave arb stopped when the node refuses it, so another reclaim finds the stake untouched" in {
    val f = fixture(spendable = 0L, respond = _ => Failure(NodeError.Rejected("Double spending attempt")))
    f.atAddress.set(Seq(stakeBox(f, Stake, 1)))
    val result = f.execution.reclaim(() => true).get
    result.outcome shouldBe EngineBroadcast.Rejected
    result.reason.get should include("Double spending")
    f.stakes.paused shouldBe true
  }

  it should "spend the largest boxes first up to the wallet's input cap, and say how many are left" in {
    val f = fixture(spendable = 0L, limits = configs.WalletConfig.Default.copy(maxInputs = 2))
    f.atAddress.set(Seq(stakeBox(f, Erg, 1), stakeBox(f, Stake, 2), stakeBox(f, 3 * Erg, 3)))
    val result = f.execution.reclaim(() => true).get
    result.boxes shouldBe 2
    result.left shouldBe 1
    result.nanoErgs shouldBe Stake + 3 * Erg
  }

  it should "refuse to build when the boxes cannot pay the fee" in {
    val f = fixture(spendable = 0L)
    f.atAddress.set(Seq(stakeBox(f, Fee + 1000L, 1)))
    an[IllegalArgumentException] should be thrownBy f.execution.reclaim(() => true)
    // Nothing was sent, so the stake that was running is not left stopped
    f.stakes.paused shouldBe false
    verify(f.api, never()).sendTransaction(anyString())
  }
}
