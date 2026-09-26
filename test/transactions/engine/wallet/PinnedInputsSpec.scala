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
 * Pinned inputs: wallet boxes held to fund transactions in this miner's own blocks.
 *
 * A pin is never broadcast and never offered to a selection, so the only things that end one are a
 * refresh no longer reporting it and the candidate builder finding it gone. These pin which box is
 * chosen, that the last usable box is never taken, and that a lost pin is replaced.
 */
object PinnedInputsSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
      .withFallback(com.typesafe.config.ConfigFactory.load())
}

class PinnedInputsSpec extends TestKit(ActorSystem("pinned-inputs-spec", PinnedInputsSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  private def walletBox(wallet: NodeWallet, value: Long, tokens: Seq[NodeAsset] = Seq.empty): WalletBox =
    WalletBox(
      box = support.CanonicalNodeBox(boxId = "00" * 32, transactionId = f"$value%064x", value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex, assets = tokens),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = f"$value%064x",
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /**
   * @param reported what the wallet reports unspent, mempool included
   * @param confirmed ids the confirmed UTXO set holds, which is what a pin is checked against
   */
  private case class Fixture(wallet: ActorRef, reported: AtomicReference[Seq[WalletBox]],
                             confirmed: AtomicReference[Set[String]]) {
    def idOf(value: Long): String = reported.get.find(_.box.value == value).get.box.boxId
  }

  private def fixture(values: Seq[Long], pins: Int = 1, tokenValues: Seq[Long] = Seq.empty,
                      unconfirmed: Seq[Long] = Seq.empty): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val token = NodeAsset("ab" * 32, 5L)
    val reported = new AtomicReference(values.map(walletBox(wallet, _)) ++
      tokenValues.map(walletBox(wallet, _, Seq(token))))
    val confirmed = new AtomicReference(reported.get
      .filterNot(box => unconfirmed.contains(box.box.value)).map(_.box.boxId).toSet)

    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(reported.get) else Success(Seq.empty[WalletBox])
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.boxById(anyString())).thenAnswer { inv =>
      val id = inv.getArgument[String](0)
      Success(reported.get.map(_.box).find(box => box.boxId == id && confirmed.get.contains(id)))
    }

    val manager = system.actorOf(Props(new EngineWalletState(ctx, configs.WalletConfig.Default, pins)))
    manager ! RefreshBoxes
    Fixture(manager, reported, confirmed)
  }

  private def pinned(f: Fixture): Seq[String] = {
    val probe = TestProbe()
    probe.send(f.wallet, GetPinnedInputs)
    probe.expectMsgType[PinnedInputs].boxIds
  }

  private def awaitPins(f: Fixture, expected: Seq[String]): Unit =
    awaitAssert(pinned(f) shouldEqual expected, 15.seconds, 200.millis)

  private def select(f: Fixture, value: Long): Seq[Long] = {
    val probe = TestProbe()
    probe.send(f.wallet, SelectInputs(value))
    probe.expectMsgType[WalletInputs].inputs.map(_.value)
  }

  "A pin" should "take the smallest plain box, never one carrying tokens" in {
    // Tokens would have to leave in an output of their own, which a fee-less proof has no room for
    val f = fixture(Seq(erg, erg / 50, erg / 20), tokenValues = Seq(erg / 100))
    awaitPins(f, Seq(f.idOf(erg / 50)))
  }

  it should "never take the last box that could be pinned" in {
    // The funded fraud proof needs a plain box too; pinning the only one would leave it none
    val f = fixture(Seq(erg))
    Thread.sleep(2000)
    pinned(f) shouldBe empty
    select(f, erg / 2) shouldEqual Seq(erg)
  }

  it should "never be handed to a selection" in {
    val f = fixture(Seq(erg / 50, erg))
    awaitPins(f, Seq(f.idOf(erg / 50)))
    withClue("covering this needs the pinned box as well: ") {
      select(f, erg + erg / 100) shouldBe empty
    }
    select(f, erg / 2) shouldEqual Seq(erg)
  }

  it should "pass over a box the confirmed UTXO set does not hold yet" in {
    // A candidate spending it would need its unconfirmed parent carried as well
    val f = fixture(Seq(erg / 50, erg / 20, erg), unconfirmed = Seq(erg / 50))
    awaitPins(f, Seq(f.idOf(erg / 20)))
  }

  it should "not be taken when the setting is zero" in {
    val f = fixture(Seq(erg / 50, erg), pins = 0)
    Thread.sleep(2000)
    pinned(f) shouldBe empty
  }

  "A pin a block spent" should "be replaced once a refresh stops reporting it" in {
    val f = fixture(Seq(erg / 50, erg / 20, erg))
    val first = f.idOf(erg / 50)
    awaitPins(f, Seq(first))
    f.reported.set(f.reported.get.filterNot(_.box.boxId == first))
    f.wallet ! RefreshBoxes
    awaitPins(f, Seq(f.idOf(erg / 20)))
  }

  "A pin the builder found gone" should "be dropped and replaced without waiting for a refresh" in {
    val f = fixture(Seq(erg / 50, erg / 20, erg))
    val first = f.idOf(erg / 50)
    awaitPins(f, Seq(first))
    f.wallet ! UnpinInput(first)
    awaitPins(f, Seq(f.idOf(erg / 20)))
  }
}
