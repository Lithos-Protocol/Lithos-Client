package api

import api.LDErrors.{LDBadRequest, LDStateChanged, LDUnavailable, LDUnprocessable}
import api.models._
import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import cache.LDCache
import configs.NodeContext
import lithosdex.LDHelpers
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{FakeCache, FakeNodeContext, LDNodeFixtures}
import transactions.dex.{DexContracts, LDBoxes}
import transactions.wallet.WalletMessages._
import transactions.wallet.WalletSelector

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * The LithosDex endpoints above the builders: what a request is allowed to execute against, and what
 * a caller is told when it cannot.
 *
 * None of this could be reached before `LithosDexApiImpl` took `NodeContext` by injection — it read
 * `Globals.getNodeConfig` directly, so nothing here could be constructed at all. Four properties,
 * each of which was previously read-and-reasoned only:
 *
 *  - one mutation at a time, because two requests reading the same singleton both sign and one is
 *    rejected by the node after reserving wallet inputs;
 *  - a quote pinned to a box that has since moved is a 409 carrying the new id, not a silent
 *    re-quote at whatever price the pool became;
 *  - a bound the current pool cannot meet is refused BEFORE anything is reserved or signed;
 *  - the node being unreachable is a 503, not a 404 and not a 500.
 */
object LithosDexApiImplSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class LithosDexApiImplSpec
  extends TestKit(ActorSystem("lithosdex-api-spec", LithosDexApiImplSpec.config))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  /** A pool with real depth, so quotes are ordinary numbers rather than edge cases. */
  private val ReservesX = 10L * erg
  private val ReservesY = 10000L * 1000000L

  private case class Fixture(api: LithosDexApiImpl,
                             nodeApi: NodeApi,
                             ctxOf: NodeContext,
                             selector: WalletSelector,
                             wallet: TestProbe,
                             cache: LDCache)

  /**
   * @param mutationWait how long a mutation waits for the DEX lock; short so the refusal can be
   *                     asserted without a fifteen-second test
   */
  private def fixture(mutationWait: Long = 15000L): Fixture = {
    val nodeApi = mock[NodeApi]
    val (nodeCtx, _, _) = FakeNodeContext(nodeApi, numAddresses = 1)
    val impl = new LithosDexApiImpl(nodeCtx) {
      override protected def mutationWaitMs: Long = mutationWait
    }
    val wallet = TestProbe()
    Fixture(impl, nodeApi, nodeCtx, WalletSelector(wallet.ref, 2.seconds, ec), wallet,
      new LDCache(new FakeCache))
  }

  // `ctx => body(ctx)` rather than `body`: appkit's `execute` takes a java.util.function.Function,
  // and scalac SAM-converts a lambda literal where IntelliJ will not convert a Function1 value.
  private def withCtx[A](f: Fixture)(body: BlockchainContext => A): A =
    f.ctxOf.getClient.execute(ctx => body(ctx))

  /** Point the mocked index at one pool box and one vault box, both mempool-aware lookups. */
  private def serve(f: Fixture, pool: Option[NodeBox], vault: Option[NodeBox]): Unit =
    withCtx(f) { ctx =>
      val n = ctx.getNetworkType
      when(f.nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenAnswer { inv =>
          inv.getArgument[String](0) match {
            case id if id == LDHelpers.getPoolNFT(n).toString =>
              Success(pool.map(b => LDNodeFixtures.indexed(b)).toSeq)
            case id if id == LDHelpers.getVaultNFT(n).toString =>
              Success(vault.map(b => LDNodeFixtures.indexed(b)).toSeq)
            case _ => Success(Seq.empty[IndexedBox])
          }
        }
    }

  private def poolBox(f: Fixture, pendingX: Long = 0L, accX: BigInt = BigInt(0)): NodeBox =
    withCtx(f)(ctx => LDNodeFixtures.poolBox(ctx, ReservesX, ReservesY, pendingX = pendingX, accX = accX))

  /**
   * The request got as far as asking the wallet for funding, which is where these fixtures stop.
   *
   * Matched by simple name because `WalletMessages` is `private[transactions]` and this spec is in
   * `api` — the same package boundary that keeps production callers off those messages.
   */
  private def expectsSelection(f: Fixture): Unit =
    f.wallet.expectMsgPF(10.seconds, "a wallet selection request") {
      case msg if msg.getClass.getSimpleName == "RetrieveInputs" => ()
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  ONE MUTATION AT A TIME
  // ══════════════════════════════════════════════════════════════════════════

  "Two concurrent mutations" should "not both execute against the same pool box" in {
    // The race the expected...BoxId fields cannot close on their own: both requests see the SAME id,
    // because the loser reads its box before the winner has sent anything. One of them has to be
    // told to re-quote, and the only thing that can tell it is the lock.
    val f = fixture(mutationWait = 200L)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    withCtx(f) { ctx =>
      val n = ctx.getNetworkType
      val pool = LDNodeFixtures.poolBox(ctx, ReservesX, ReservesY, pendingX = erg)
      val vault = LDNodeFixtures.vaultBox(ctx, LDHelpers.VAULT_MIN)
      when(f.nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenAnswer { inv =>
          // The first request to reach the index blocks inside the lock; the second must not wait
          // it out, since by then its quote is against a box the first has spent.
          if (inv.getArgument[String](0) == LDHelpers.getPoolNFT(n).toString) {
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            Success(Seq(LDNodeFixtures.indexed(pool)))
          } else Success(Seq(LDNodeFixtures.indexed(vault)))
        }
    }

    val first = Future(Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector)))
    entered.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

    val second = Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector))
    second.failed.get shouldBe a[LDStateChanged]
    second.failed.get.getMessage should include("re-read the pool and vault")

    // Asserted while the first request is still blocked inside the lock, so the only thing that
    // could have reached the wallet is the second — and it must not have: a refused mutation has to
    // stop before it reserves anything, or a caller told to re-quote has still consumed inputs.
    f.wallet.expectNoMessage(200.millis)

    release.countDown()
    Await.result(first, 20.seconds)
    // The first request, which held the lock, does reach the wallet.
    expectsSelection(f)
  }

  it should "release the lock again once the first has finished" in {
    val f = fixture(mutationWait = 200L)
    serve(f, Some(poolBox(f)), Some(withCtx(f)(ctx => LDNodeFixtures.vaultBox(ctx, LDHelpers.VAULT_MIN))))

    // Nothing is pending, so this fails on its own terms rather than on the lock — and must still
    // hand the lock back, or every later mutation in the process is refused.
    Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector)).failed.get shouldBe a[LDBadRequest]
    Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector)).failed.get shouldBe a[LDBadRequest]
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PINNING A QUOTE TO THE BOX IT WAS MADE AGAINST
  // ══════════════════════════════════════════════════════════════════════════

  "An execute request naming a pool box that has moved" should "be refused with the current id" in {
    val f = fixture()
    val live = poolBox(f)
    serve(f, Some(live), None)

    val thrown = intercept[LDStateChanged](f.api.swap(
      LDSwapExecuteRequest("1000000", ergIn = true, "0", expectedPoolBoxId = Some("de" * 32)),
      f.cache, f.selector))

    // The id it moved TO, so the caller can re-quote against it rather than read the pool again and
    // race a third request in the process.
    thrown.poolBoxId shouldEqual Some(live.boxId)
    f.wallet.expectNoMessage(200.millis)
  }

  it should "proceed past the check when the id still matches" in {
    // The other direction, so the test above is not passing because the check rejects everything.
    val f = fixture()
    val live = poolBox(f)
    serve(f, Some(live), None)

    // Reaches the builder, which then asks the wallet — which is where this fixture stops.
    Future(Try(f.api.swap(
      LDSwapExecuteRequest("1000000", ergIn = true, "0", expectedPoolBoxId = Some(live.boxId)),
      f.cache, f.selector)))
    expectsSelection(f)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  EXECUTE-SIDE BOUNDS
  // ══════════════════════════════════════════════════════════════════════════

  "A deposit priced above its ceiling" should "be refused before anything is reserved" in {
    // A deposit's cost comes from the reserves, so a pool that traded since the quote charges a
    // different price and there was nothing to refuse it. Checked against the box that will actually
    // be spent, and before the builder is called at all.
    val f = fixture()
    val live = poolBox(f)
    serve(f, Some(live), None)

    val shares = 1000000L
    val quoted = withCtx(f)(ctx => lithosdex.LDLiquidityPool(live.toInputUTXO(ctx))
      .simDepositForShares(shares))

    val thrown = intercept[LDUnprocessable](f.api.deposit(
      LDDepositExecuteRequest(shares.toString, maxAmountX = Some((quoted.amountX - 1).toString)),
      f.cache, f.selector))
    thrown.getMessage should include("maxAmountX")
    f.wallet.expectNoMessage(200.millis)
  }

  it should "proceed when the ceiling covers the quote" in {
    val f = fixture()
    serve(f, Some(poolBox(f)), None)
    Future(Try(f.api.deposit(
      LDDepositExecuteRequest("1000000", maxAmountX = Some(Long.MaxValue.toString)),
      f.cache, f.selector)))
    expectsSelection(f)
  }

  "A swap with a negative floor" should "be refused outright" in {
    // `amountOut >= minOutput` is vacuously true for any negative floor, so this disabled slippage
    // protection entirely — on the one field that exists to provide it.
    val f = fixture()
    serve(f, Some(poolBox(f)), None)

    intercept[LDBadRequest](f.api.swap(
      LDSwapExecuteRequest("1000000", ergIn = true, "-1"), f.cache, f.selector))
      .getMessage should include("minOutput")
    f.wallet.expectNoMessage(200.millis)
  }

  "A redemption forfeiting settled fees" should "require the caller to say so by name" in {
    // Spending the provision box forfeits whatever the vault had already settled to it, to nobody.
    // The contract permits that deliberately and this client must not refuse it — but a caller does
    // not consent to it by leaving a field out.
    val f = fixture()
    val owner = ErgoId.create("f0" * 32)
    val provision = withCtx(f)(ctx => LDNodeFixtures.provisionBox(ctx, shares = 1000000L, ownerNFT = owner))
    serve(f, Some(poolBox(f)),
      Some(withCtx(f)(ctx => LDNodeFixtures.vaultBox(ctx, 5 * erg, accX = LDHelpers.SCALE))))
    when(f.nodeApi.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(f.nodeApi.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
    when(f.nodeApi.indexedBoxById(provision.boxId))
      .thenReturn(Success(Some(LDNodeFixtures.indexed(provision))))

    val thrown = intercept[LDBadRequest](f.api.redeem(
      LDRedeemRequest(provision.boxId), f.cache, f.selector))
    thrown.getMessage should include("acknowledgeUnclaimedFees")
    // Both amounts named, since "some fees" is not something a user can weigh.
    thrown.getMessage should include("nanoERG")
    f.wallet.expectNoMessage(200.millis)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  WHAT AN UNREACHABLE NODE MEANS
  // ══════════════════════════════════════════════════════════════════════════

  "A lock held longer than a node round trip" should "be a 503, not a 409" in {
    // The two want opposite things from the caller. A mutation merely ahead in the queue finishes in
    // well under a second, so "re-quote and resend" is right. One wedged on the node is not going to
    // finish, and telling a caller to retry immediately puts a queue of them onto a node that is
    // already not answering.
    val f = fixture(mutationWait = 100L)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    withCtx(f) { ctx =>
      val pool = LDNodeFixtures.poolBox(ctx, ReservesX, ReservesY, pendingX = erg)
      val vault = LDNodeFixtures.vaultBox(ctx, LDHelpers.VAULT_MIN)
      val n = ctx.getNetworkType
      when(f.nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenAnswer { inv =>
          if (inv.getArgument[String](0) == LDHelpers.getPoolNFT(n).toString) {
            entered.countDown()
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
            Success(Seq(LDNodeFixtures.indexed(pool)))
          } else Success(Seq(LDNodeFixtures.indexed(vault)))
        }
    }

    val first = Future(Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector)))
    entered.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    // Past the stuck-holder threshold, so the next caller must be told the node is the problem.
    Thread.sleep(10500L)

    val second = Try(f.api.flush(LDFlushRequest.Empty, f.cache, f.selector))
    second.failed.get shouldBe a[LDUnavailable]
    second.failed.get.getMessage should include("may not be answering")

    release.countDown()
    Await.result(first, 30.seconds)
    expectsSelection(f)
  }

  "An unreachable index" should "not be reported as a missing pool" in {
    val f = fixture()
    when(f.nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(scala.util.Failure(new RuntimeException("index off")))

    intercept[LDBoxes.IndexUnavailableException](f.api.getPool(f.cache))
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FEE HISTORY
  // ══════════════════════════════════════════════════════════════════════════

  "Fee history" should "credit every transition when several land in one block" in {
    // Several pool transitions land in one block routinely — a swap and the flush behind it. Two
    // separate places used to break on that, and both present the same way: fees missing from the
    // series with nothing logged.
    //
    //  - the lineage was ordered by inclusion height alone, so same-height boxes kept whatever order
    //    the index returned them in. `feeFromAccumulator` floors a negative step to zero, so a dip
    //    that is later recovered is counted twice on the way back up.
    //  - the bucket took `maxBy(height)` of its readings, which on a tie returns the FIRST, not the
    //    latest — dropping every transition after the first one in that block.
    //
    // The fixture is built so neither can be right by accident: the accumulator climbs 0 → 1 → 2 → 3
    // in globalIndex order, and the index hands them back out of order. Read correctly that is three
    // steps; ordered by height alone the sequence becomes 0 → 2 → 1 → 3 and totals four.
    val f = fixture()
    val supply = LDHelpers.GENESIS_SUPPLY
    val step = LDHelpers.SCALE / BigInt(supply)
    val perStep = (step * BigInt(supply) / LDHelpers.SCALE).toLong
    perStep shouldEqual 1L

    val boxes = withCtx(f) { ctx =>
      def at(acc: Int, height: Int, gi: Long, index: Int) =
        LDNodeFixtures.indexed(
          LDNodeFixtures.poolBox(ctx, ReservesX, ReservesY, accX = step * acc, index = index),
          height = height, globalIndex = gi)
      // Deliberately not in globalIndex order, which is what an index page looks like.
      Seq(at(0, 500, 10L, 0), at(2, 501, 30L, 1), at(1, 501, 20L, 2), at(3, 501, 40L, 3))
    }
    when(f.nodeApi.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Paged(boxes, boxes.size))
      else Success(Paged(Seq.empty[IndexedBox], boxes.size))
    }
    when(f.nodeApi.chainSlice(any[Option[Int]], any[Option[Int]])).thenReturn(Success(Seq.empty[NodeHeader]))

    val history = f.api.getFeeHistory(None, None, Some(1000), f.cache)
    history.history should not be empty
    // Three steps of one nanoERG. Four means the lineage was mis-ordered; one means the bucket took
    // the first reading of the block instead of the last.
    history.history.map(_.cumulativeX.toLong).max shouldEqual perStep * 3
  }
}
