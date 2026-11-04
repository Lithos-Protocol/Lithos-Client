package transactions.engine.wallet

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.engine.wallet.EngineWalletMessages._

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.util.Success

/**
 * The arb stake: one wallet box held apart from the fraud-proof pins, handed on to the cycle output that
 * returns it once a block spends it. These pin which box is chosen, that the stake never takes or keeps a box a
 * fraud-proof pin needs, and how a pending successor survives a found block, no block and a reorg.
 */
class ArbStakeSpec extends TestKit(ActorSystem("arb-stake-spec", PinnedInputsSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L
  private val stakeTarget = 10 * erg

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = support.CanonicalNodeBox(boxId = "00" * 32, transactionId = f"$value%064x", value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex, assets = Seq.empty),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = f"$value%064x",
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /**
   * @param reported  what the wallet reports unspent, mempool included
   * @param confirmed ids the confirmed UTXO set holds, which the stake is checked against
   */
  private case class Fixture(engine: ActorRef, wallet: NodeWallet, reported: AtomicReference[Seq[WalletBox]],
                             confirmed: AtomicReference[Set[String]],
                             readDelayMs: java.util.concurrent.atomic.AtomicLong) {
    def idOf(value: Long): String = reported.get.find(_.box.value == value).get.box.boxId

    /** A wallet box of `value` that a block has just created: reported and confirmed from now on. */
    def arrive(value: Long): String = {
      val box = walletBox(wallet, value)
      reported.set(reported.get :+ box)
      confirmed.set(confirmed.get + box.box.boxId)
      box.box.boxId
    }

    /** A block spent `boxId`: neither reported nor confirmed any more. */
    def spend(boxId: String): Unit = {
      reported.set(reported.get.filterNot(_.box.boxId == boxId))
      confirmed.set(confirmed.get - boxId)
    }
  }

  private def fixture(values: Seq[Long], pins: Int = 1, stake: Long = stakeTarget): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val reported = new AtomicReference(values.map(walletBox(wallet, _)))
    val confirmed = new AtomicReference(reported.get.map(_.box.boxId).toSet)

    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(reported.get) else Success(Seq.empty[WalletBox])
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    // How long each confirmed-set read takes, so a test can hold a stake check out
    val readDelayMs = new java.util.concurrent.atomic.AtomicLong(0L)
    when(api.boxById(anyString())).thenAnswer { inv =>
      Thread.sleep(readDelayMs.get)
      val id = inv.getArgument[String](0)
      Success(reported.get.map(_.box).find(box => box.boxId == id && confirmed.get.contains(id)))
    }

    val engine = system.actorOf(Props(new EngineWalletState(ctx, configs.WalletConfig.Default, pins, stake)))
    engine ! RefreshBoxes
    Fixture(engine, wallet, reported, confirmed, readDelayMs)
  }

  private def stakeAt(f: Fixture, height: Int): Option[String] = {
    val probe = TestProbe()
    probe.send(f.engine, GetArbStake(height))
    probe.expectMsgType[ArbStake].box.map(_.boxId)
  }

  /** The stake handed out at `height`, once the check that height starts has come back. */
  private def awaitStake(f: Fixture, height: Int, expected: Option[String]): Unit =
    awaitAssert(stakeAt(f, height) shouldEqual expected, 15.seconds, 200.millis)

  private def pinned(f: Fixture): Seq[String] = {
    val probe = TestProbe()
    probe.send(f.engine, GetPinnedInputs)
    probe.expectMsgType[PinnedInputs].boxIds
  }

  private def owned(f: Fixture): Set[String] = {
    val probe = TestProbe()
    probe.send(f.engine, GetOwnedInputIds)
    probe.expectMsgType[Set[String]]
  }

  private def reserveSuccessor(f: Fixture, replaces: String, boxId: String, height: Int): Boolean = {
    val probe = TestProbe()
    probe.send(f.engine, ReserveStakeSuccessor(replaces, boxId, height))
    probe.expectMsgType[Boolean]
  }

  private def select(f: Fixture, value: Long): Seq[Long] = {
    val probe = TestProbe()
    probe.send(f.engine, SelectInputs(value))
    probe.expectMsgType[WalletInputs].inputs.map(_.value)
  }

  "The arb stake" should "take the smallest plain box of at least the target, apart from the fraud-proof pin" in {
    val f = fixture(Seq(erg / 50, erg / 20, 15 * erg, 12 * erg, erg))
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(erg / 50)), 15.seconds, 200.millis)
    awaitStake(f, 101, Some(f.idOf(12 * erg)))
    pinned(f) shouldEqual Seq(f.idOf(erg / 50))
  }

  it should "never be handed to a selection" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    awaitStake(f, 101, Some(f.idOf(12 * erg)))
    withClue("covering this needs the stake as well: ") {
      select(f, 16 * erg) shouldBe empty
    }
    select(f, 13 * erg) shouldEqual Seq(15 * erg)
  }

  it should "be handed out only once the confirmed UTXO set has been read for the height asked" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    awaitStake(f, 101, Some(f.idOf(12 * erg)))
    // The first ask at a new height starts the check and is answered with nothing
    stakeAt(f, 102) shouldBe None
    awaitStake(f, 102, Some(f.idOf(12 * erg)))
  }

  it should "not be held when no box is large enough, and be taken once one arrives, without a restart" in {
    val f = fixture(Seq(erg / 50, erg / 20, erg))
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(erg / 50)), 15.seconds, 200.millis)
    Thread.sleep(1500)
    stakeAt(f, 101) shouldBe None
    val arrived = f.arrive(12 * erg)
    f.engine ! RefreshBoxes
    awaitStake(f, 101, Some(arrived))
  }

  it should "leave the last plain box to the mempool rather than hold it" in {
    // The pin takes the small box; the large one is then the only plain box left
    val f = fixture(Seq(erg / 50, 12 * erg))
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(erg / 50)), 15.seconds, 200.millis)
    Thread.sleep(1500)
    stakeAt(f, 101) shouldBe None
    select(f, erg) shouldEqual Seq(12 * erg)
  }

  it should "not be held while arb is off" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg), stake = 0L)
    Thread.sleep(2000)
    stakeAt(f, 101) shouldBe None
    owned(f) shouldEqual pinned(f).toSet
  }

  "A fraud-proof pin" should "take the stake's box when no other box is free for it" in {
    val f = fixture(Seq(erg / 50, erg / 20, 12 * erg))
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(erg / 50)), 15.seconds, 200.millis)
    awaitStake(f, 101, Some(f.idOf(12 * erg)))
    // A block spends the pin. Its replacement needs a box besides the one left to the mempool
    val lost = f.idOf(erg / 50)
    f.spend(lost)
    f.engine ! UnpinInput(lost)
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(erg / 20)), 15.seconds, 200.millis)
    awaitStake(f, 102, None)
    select(f, erg) shouldEqual Seq(12 * erg)
  }

  it should "never be taken by the stake while pins are short" in {
    // Every plain box is large enough to be the stake; the pin still gets the smallest
    val f = fixture(Seq(11 * erg, 12 * erg, 13 * erg))
    awaitAssert(pinned(f) shouldEqual Seq(f.idOf(11 * erg)), 15.seconds, 200.millis)
    awaitStake(f, 101, Some(f.idOf(12 * erg)))
  }

  "A pending successor" should "become the stake once a block spends the stake" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    val successor = "cd" * 32
    reserveSuccessor(f, stake, successor, 101) shouldBe true
    // The block lands: the stake is spent and the successor appears, reported before anything confirms it
    f.spend(stake)
    f.reported.set(f.reported.get :+ walletBoxWithId(f, successor, 12 * erg))
    withClue("the successor is kept out of selection from the moment it appears: ") {
      f.engine ! RefreshBoxes
      Thread.sleep(1000)
      select(f, 13 * erg) shouldEqual Seq(15 * erg)
    }
    f.confirmed.set(f.confirmed.get + successor)
    awaitStake(f, 102, Some(successor))
    owned(f) should not contain stake
  }

  it should "be dropped at the next height when no block spends the stake" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    val successor = "ce" * 32
    reserveSuccessor(f, stake, successor, 101) shouldBe true
    owned(f) should contain(successor)
    awaitStake(f, 102, Some(stake))
    owned(f) should not contain successor
  }

  it should "give way to the old stake when a reorg undoes the block that spent it" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    val successorBox = walletBoxWithId(f, "cf" * 32, 12 * erg)
    reserveSuccessor(f, stake, successorBox.box.boxId, 101) shouldBe true
    f.spend(stake)
    f.reported.set(f.reported.get :+ successorBox)
    f.confirmed.set(f.confirmed.get + successorBox.box.boxId)
    awaitStake(f, 102, Some(successorBox.box.boxId))
    // The block is orphaned: its output is gone and the old stake is unspent again
    f.spend(successorBox.box.boxId)
    f.reported.set(f.reported.get :+ walletBox(f.wallet, 12 * erg))
    f.confirmed.set(f.confirmed.get + stake)
    awaitStake(f, 103, Some(stake))
  }

  it should "be refused when it does not replace the stake, or the stake was not checked for its height" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    reserveSuccessor(f, f.idOf(15 * erg), "d0" * 32, 101) shouldBe false
    reserveSuccessor(f, stake, "d1" * 32, 102) shouldBe false
    reserveSuccessor(f, stake, "not a box id", 101) shouldBe false
    reserveSuccessor(f, stake, f.idOf(15 * erg), 101) shouldBe true
    awaitAssert(pinned(f) should not be empty, 15.seconds, 200.millis)
    reserveSuccessor(f, stake, pinned(f).head, 101) shouldBe false
  }

  it should "be taken again when a refresh builds the same cycle and names the same output" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    val successor = "d2" * 32
    reserveSuccessor(f, stake, successor, 101) shouldBe true
    reserveSuccessor(f, stake, successor, 101) shouldBe true
    owned(f) should contain(successor)
  }

  it should "be refused past 256 held at once, while each already held is still taken again" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    (1 to 256).foreach(i => reserveSuccessor(f, stake, f"$i%064x", 101) shouldBe true)
    reserveSuccessor(f, stake, f"${257}%064x", 101) shouldBe false
    reserveSuccessor(f, stake, f"${256}%064x", 101) shouldBe true
    owned(f) should not contain f"${257}%064x"
  }

  "A stake check" should "never answer for a height with the stake checked for an earlier one, even while it is out" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    f.readDelayMs.set(2000L)
    stakeAt(f, 102) shouldBe None
    withClue("asked for the next height while the check for 102 is out: ") {
      stakeAt(f, 103) shouldBe None
    }
    f.readDelayMs.set(0L)
    awaitStake(f, 102, Some(stake))
    withClue("the check for 102 has come back, and none for 103 was started while it was out: ") {
      stakeAt(f, 103) shouldBe None
    }
    awaitStake(f, 103, Some(stake))
  }

  "A stake a block spent with no successor" should "be replaced by another box" in {
    val f = fixture(Seq(erg / 50, 12 * erg, 15 * erg, 20 * erg))
    val stake = f.idOf(12 * erg)
    awaitStake(f, 101, Some(stake))
    f.spend(stake)
    stakeAt(f, 102) shouldBe None
    awaitAssert(stakeAt(f, 103) shouldEqual Some(f.idOf(15 * erg)), 15.seconds, 200.millis)
  }

  /** A wallet box with the given id, as the node reports one a block created. */
  private def walletBoxWithId(f: Fixture, id: String, value: Long): WalletBox = {
    val base = walletBox(f.wallet, value)
    base.copy(box = base.box.copy(boxId = id))
  }
}
