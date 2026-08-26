package transactions.wallet

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import api.CollateralMarketApiImpl
import api.models.CollateralJoinExecuteRequest
import lfsm.{CollateralParams, LFSMHelpers}
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{Address, BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{CollateralNodeFixtures => Fx, FakeNodeContext}
import transactions.wallet.WalletMessages._
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.concurrent.duration._
import scala.util.{Success, Try}

/**
 * What a join does with the wallet inputs it reserved when the broadcast fails.
 *
 * In this package rather than beside the other API specs because the lifecycle messages it asserts
 * on are `private[transactions]` — and because the property is a wallet one: which ENDING an
 * acknowledged lease is given.
 *
 * The defect this pins was silent and permanent. `CollateralMarketApiImpl.join` called
 * `WalletReservation.beginAll` — Selected to Submitting — then `ctx.sendTransaction` with no
 * `catch`. On a throw, `finally` ran `funding.finish`, whose `release()` is a NO-OP past Selected,
 * and `ResetUsedInputs` collects Selected and Known by age but never Submitting, which has no TTL.
 * So ~2.9 ERG of capacity was withheld until the process restarted, from a wallet the rollup and
 * emission paths also draw from — presenting as "the wallet seems short".
 *
 * The offline context provokes it honestly: `FileMockedErgoClient` answers only what opening a
 * context needs, so the send fails at a real send boundary rather than an injected one.
 */
object CollateralJoinReservationSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 25s")
}

class CollateralJoinReservationSpec
  extends TestKit(ActorSystem("collateral-join-reservation-spec", CollateralJoinReservationSpec.config))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /**
   * A wallet that grants everything and reports what it was told.
   *
   * Every lifecycle message is forwarded to a probe, so the assertion is on which ending the join
   * chose for its lease — the distinction the manager's own state machine turns on.
   */
  private class GrantingWallet(funding: Seq[InputUTXO], watcher: ActorRef) extends Actor {
    override def receive: Receive = {
      case GetSpendableBalance => sender() ! SpendableBalance(1000L * Parameters.OneErg)
      case GetUnlockedRewards => sender() ! RewardSummary(0, 0, 0L, 0L, None)

      case m @ RetrieveInputs(_, _, _, id, _) =>
        watcher ! m
        sender() ! WalletInputs(funding, id)

      case m @ ReserveKnownInputs(inputs, id, _) =>
        watcher ! m
        sender() ! WalletInputs(inputs, id)

      case m @ BeginReservationSubmission(id, _, _) =>
        watcher ! m
        sender() ! ReservationSubmissionStarted(id, accepted = true)

      case m @ CancelReservationSubmission(id, _) =>
        watcher ! m
        sender() ! ReservationSubmissionCancelled(id, accepted = true)

      case m: MarkReservationUncertain => watcher ! m
      case m: CommitReservation => watcher ! m
      case m: ReleaseInputs => watcher ! m
      case m: ReturnInputs => watcher ! m
    }
  }

  /** One box at this wallet's own address, carrying the principal, the fees and the permit. */
  private def fundingBox(ctx: BlockchainContext, own: Contract, permit: Long): InputUTXO =
    UTXO(own,
      CollateralParams.PRINCIPAL_FLOOR + Parameters.MinFee * 4,
      Seq(Token(LFSMHelpers.LIT_ID, permit * 2)))
      .toInput(ctx, ErgoId.create("ef" * 32), 0.toShort)

  private case class Fixture(api: CollateralMarketApiImpl, watcher: TestProbe)

  private def fixture(): Fixture = {
    val nodeApi = mock[NodeApi]
    val (nodeCtx, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 2)
    val addresses: Seq[Address] = wallet.addresses

    // Backlog zero, so the permit is the floor and the funding box can carry a known amount.
    val permit = LFSMHelpers.PERMIT_PARAMS(0)

    val (byToken, funding) = nodeCtx.getClient.execute { ctx =>
      val boxes = Map(
        LFSMHelpers.EMISSION_NFT.toString ->
          Seq(Fx.indexed(Fx.emissionBox(ctx, head = 0L, tail = 0L))),
        LFSMHelpers.EMCONFIG_NFT.toString -> Seq(Fx.indexed(Fx.configBox(ctx))))
      (boxes, fundingBox(ctx, wallet.contract, permit))
    }

    when(nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer((inv: InvocationOnMock) => {
        val tokenId = inv.getArgument[String](0)
        val paging = inv.getArgument[Paging](1)
        (Success(if (paging.offset == 0) byToken.getOrElse(tokenId, Seq.empty)
                 else Seq.empty[IndexedBox]): Try[Seq[IndexedBox]])
      })
    when(nodeApi.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(nodeApi.walletAddresses()).thenReturn(Success(addresses.map(_.toString)))
    when(nodeApi.indexerEnabled).thenReturn(true)

    val watcher = TestProbe()
    val walletRef = system.actorOf(Props(new GrantingWallet(Seq(funding), watcher.ref)))
    val api = new CollateralMarketApiImpl(nodeCtx, Configuration.from(Map.empty[String, Any]),
      system, walletRef)
    Fixture(api, watcher)
  }

  "A join whose broadcast fails" should "mark its lease uncertain rather than leaving it Submitting" in {
    val f = fixture()

    // The send is a third node call on an offline client, so it throws. Nothing was created, so the
    // original failure is rethrown rather than becoming a partial result.
    a[Exception] should be thrownBy
      f.api.join(CollateralJoinExecuteRequest(1, Some(true), None, None))

    // First: prove the run reached the send boundary at all. Without this the test would pass for
    // the wrong reason if the build failed earlier, where no lease is acknowledged and nothing is
    // owed. This is the assertion that makes the one below mean something.
    f.watcher.fishForMessage(20.seconds, "the lease was acknowledged") {
      case _: BeginReservationSubmission => true
      case _ => false
    }

    // Then the ending it chose. `release()` is what the defective version effectively did, and the
    // manager ignores it past Selected — so the boxes stayed held with no TTL to free them.
    f.watcher.fishForMessage(20.seconds, "the acknowledged lease was ended as uncertain") {
      case _: MarkReservationUncertain => true
      case _: CommitReservation => fail("a broadcast that threw must not be committed")
      case _ => false
    }
  }
}
