package transactions.engine.execution

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import configs.{ForwardsOffersConfig, NodeContext}
import forwards.contracts.{DFContracts, DFTerms, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import node.NodeApi
import node.model._
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import stats.NextDifficultyEstimate
import support.{ChainFixtures, FakeNodeContext, LDNodeFixtures}
import transactions.batching.Batcher
import transactions.batching.forwards.{DFBoxes, ForwardsFixtures, ForwardsUserTransactions}
import transactions.engine.wallet.EngineFunding
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

/**
 * The difficulty forward endpoints above the builders: what a caller is shown, what a request is refused for
 * before anything is reserved, and, for a fill and a settlement, the whole path through wallet funding and
 * the send boundary with the probe standing in for the engine.
 *
 * Every fixture sits at the offline chain: tip 123,413, epoch 964, where a recording of 964 can sign.
 */
class ForwardsAPIExecutionSpec extends TestKit(ActorSystem("forwards-api-execution-spec"))
  with AnyFlatSpecLike with BeforeAndAfterAll with MockitoSugar with ForwardsFixtures {

  import _root_.contracts.specs.forwards.DFSpecBase._

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Tip = 123413
  private val Epoch = DFMath.epochOf(Tip)
  private val anchor = "cc" * 32
  private val BALANCE = 100L * LIT
  /** The shipped notional limit on windows under 8 epochs, in LIT base units. */
  private val ShortCap = ForwardsOffersConfig.Default.shortWindowMaxNotionalLit * LIT
  private def lit(amount: Long): Token = Token(litId, amount)

  private def snapshot(spending: (String, String)*): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot(anchor, spending.map(_._2).toSet, spending.map(_._1).toSet, System.nanoTime(),
      transactions = spending.toVector.map { case (boxId, txId) =>
        CompleteMempool.MempoolTx(txId, NodeTransaction(txId, Seq(NodeInput(boxId, NodeSpendingProof.empty)),
          Seq.empty, Seq.empty), 100)
      })

  private case class Fixture(api: ForwardsAPIExecution, nodeApi: NodeApi, node: NodeContext, engine: TestProbe) {
    def onChain[A](f: BlockchainContext => A): A = node.getClient.execute(ctx => f(ctx))
  }

  /**
   * @param offers    boxes the index lists at the offer contract
   * @param positions boxes it lists at the position contract
   * @param acc       the accumulator, confirmed and unspent in the mempool
   */
  private def fixture(offers: BlockchainContext => Seq[NodeBox] = _ => Seq.empty,
                      positions: BlockchainContext => Seq[NodeBox] = _ => Seq.empty,
                      acc: Option[BlockchainContext => NodeBox] = None,
                      deployment: Option[DFContracts] = Some(contracts),
                      limits: ForwardsOffersConfig = ForwardsOffersConfig.Default,
                      next: () => Either[String, NextDifficultyEstimate] = ForwardsAPIExecution.NotComputed,
                      spent: BlockchainContext => Seq[IndexedBox] = _ => Seq.empty,
                      txs: BlockchainContext => Seq[IndexedTransaction] = _ => Seq.empty,
                      alive: () => Boolean = () => true): Fixture = {
    val nodeApi = mock[NodeApi]
    val (node, _, _) = FakeNodeContext(nodeApi, numAddresses = 1)
    val engine = TestProbe()
    val (offerBoxes, positionBoxes, accBox, spentBoxes, transactions) = node.getClient.execute { ctx =>
      (offers(ctx), positions(ctx), acc.map(_(ctx)), spent(ctx), txs(ctx))
    }
    // Every box the tree ever held: the spent history and the unspent ones
    when(nodeApi.boxesByErgoTree(anyString(), any[Paging])).thenAnswer { inv =>
      val tree = inv.getArgument[String](0)
      val paging = inv.getArgument[Paging](1)
      val all = spentBoxes.filter(_.box.ergoTree == tree) ++
        (offerBoxes ++ positionBoxes).filter(_.ergoTree == tree).map(LDNodeFixtures.indexed(_))
      Success(Paged(all.slice(paging.offset, paging.offset + paging.limit), all.size))
    }
    when(nodeApi.indexedTransactionById(anyString())).thenAnswer { inv =>
      Success(transactions.find(_.id == inv.getArgument[String](0)))
    }
    when(nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val paging = inv.getArgument[Paging](1)
        val listed = inv.getArgument[String](0) match {
          case tree if tree == contracts.offer.ergoTreeHex => offerBoxes
          case tree if tree == contracts.position.ergoTreeHex => positionBoxes
          case _ => Seq.empty
        }
        Success(listed.slice(paging.offset, paging.offset + paging.limit).map(LDNodeFixtures.indexed(_)))
      }
    when(nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer(_ => Success(accBox.toSeq.map(LDNodeFixtures.indexed(_))))
    when(nodeApi.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val ids = inv.getArgument[Seq[String]](0).toSet
      Success((offerBoxes ++ positionBoxes ++ accBox).filter(b => ids(b.boxId)))
    }
    when(nodeApi.info()).thenReturn(Success(ChainFixtures.infoAt(Tip).copy(bestFullHeaderId = Some(anchor))))
    // The node accepts whatever is sent, answering with the transaction's own id
    when(nodeApi.sendTransaction(anyString())).thenAnswer { inv =>
      Success(NodeCodecs.transaction(new com.google.gson.JsonParser().parse(inv.getArgument[String](0))
        .getAsJsonObject).id)
    }
    val api = new ForwardsAPIExecution(node, EngineFunding(engine.ref, 3.seconds, ec), deployment, limits, alive,
      next)
    Fixture(api, nodeApi, node, engine)
  }

  private def ring(lastEpoch: Int, inside: DFTerms*)(percent: Long): IndexedSeq[Long] =
    (0 until RING_SIZE).map { i =>
      val e = lastEpoch - (lastEpoch - i) % RING_SIZE
      if (inside.exists(t => e >= t.windowStart && e < t.windowStart + t.windowLength)) STRIKE + STRIKE * percent / 100
      else STRIKE
    }

  private def accBox(lastEpoch: Int, inside: DFTerms*)(ctx: BlockchainContext): NodeBox =
    nodeBox(ctx, accUTXO(AccState(lastEpoch, ring(lastEpoch, inside: _*)(8), BALANCE)), 0, "ac" * 32)

  private def offerBox(ctx: BlockchainContext, t: DFTerms, expiry: Int = Tip + 50, makerLong: Boolean = true,
                       litHeld: Long = -1L, index: Int = 0): NodeBox =
    nodeBox(ctx, offerUTXO(ctx, makerLong, t, expiry, lit = litHeld), index, "aa" * 32)

  /** An offer whose maker is the fixture's own wallet. */
  private def ownOfferBox(ctx: BlockchainContext, node: NodeContext, t: DFTerms, index: Int): NodeBox =
    nodeBox(ctx, UTXO(contracts.offer, DFHelpers.POSITION_ERG / 2, Seq(lit(DFMath.sideLit(t.notional))),
      DifficultyForwardContracts.offerRegisters(node.getNodeWallet.p2pk.getPublicKey, true, t, Tip + 50)), index,
      "aa" * 32)

  private val open: DFTerms = terms(Epoch + 2, 8)

  /** Answers the engine's side of one funded mutation: a fresh mempool, funding, a fresh mempool again, a pin. */
  private def engineServes(f: Fixture, funding: Option[InputUTXO], first: CompleteMempool.Snapshot = snapshot()): Unit = {
    f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
    f.engine.reply(CompleteMempool.Observation(1L, Some(first), None))
    funding.foreach { box =>
      val request = f.engine.expectMsgType[SelectInputs](10.seconds)
      f.engine.reply(WalletInputs(Seq(box), request.reservationId))
    }
    f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
    f.engine.reply(CompleteMempool.Observation(2L, Some(snapshot()), None))
    if (funding.isDefined) {
      f.engine.expectMsgType[PinEngineInputs](10.seconds)
      f.engine.reply(true)
    }
  }

  private def sentTransaction(f: Fixture): NodeTransaction = {
    val captor = org.mockito.ArgumentCaptor.forClass(classOf[String])
    verify(f.nodeApi).sendTransaction(captor.capture())
    NodeCodecs.transaction(new com.google.gson.JsonParser().parse(captor.getValue).getAsJsonObject)
  }

  // ─── the accumulator ──────────────────────────────────────────────────────

  "The accumulator read" should "report the chain and the limits, and say there is no accumulator, before genesis" in {
    val f = fixture(deployment = None)
    val info = f.api.getAccumulator
    info.deployed shouldBe false
    info.height shouldBe Tip
    info.currentEpoch shouldBe Epoch
    info.blocksLeftInEpoch shouldBe (Epoch + 1) * 128 - Tip
    BigInt(info.currentDifficulty) should be > BigInt(0)
    info.boxId shouldBe None
    info.recent shouldBe empty
    info.nextDifficulty shouldBe None
    info.limits shouldBe DFOfferLimits(Epoch + 2, Epoch + 120, 120, (Epoch + 1) * 128, 8, (10000L * LIT).toString)
  }

  it should "list the 128 epochs the accumulator holds, oldest first, with its balance" in {
    val f = fixture(acc = Some(accBox(Epoch - 1, open)))
    val info = f.api.getAccumulator
    info.deployed shouldBe true
    info.lastEpoch shouldBe Some(Epoch - 1)
    info.balance shouldBe Some(BALANCE.toString)
    info.recent.map(_.epoch) shouldBe ((Epoch - 128) to (Epoch - 1))
    info.accumulatorUnavailable shouldBe None
  }

  it should "report the next epoch's difficulty only when it is for the epoch after the tip's" in {
    val current = fixture().api.getAccumulator.currentDifficulty
    val next = BigInt(current) * 11 / 10
    val ahead = fixture(next = () => Right(NextDifficultyEstimate(Epoch + 1, next, exact = false, Tip))).api.getAccumulator
    ahead.nextDifficulty.map(n => (n.epoch, n.difficulty)) shouldBe Some((Epoch + 1, next.toString))
    ahead.nextDifficulty.get.change shouldBe 0.1 +- 0.0001
    val behind = fixture(next = () => Right(NextDifficultyEstimate(Epoch, next, exact = true, Tip - 1))).api.getAccumulator
    behind.nextDifficulty shouldBe None
    behind.nextDifficultyUnavailable.get should include(s"epoch $Epoch")
  }

  // ─── the order book ───────────────────────────────────────────────────────

  "The order book" should "list only offers some block could still fill, by window start, length and strike" in {
    val f = fixture(offers = ctx => Seq(
      offerBox(ctx, terms(Epoch + 5, 4), index = 0),
      offerBox(ctx, terms(Epoch + 2, 8, strike = STRIKE + 1), index = 1),
      offerBox(ctx, terms(Epoch + 2, 8), index = 2),
      offerBox(ctx, terms(Epoch + 2, 4), litHeld = DFMath.sideLit(NOTIONAL) - 1, index = 3), // unfunded
      offerBox(ctx, terms(Epoch + 2, 4), expiry = Tip, index = 4), // expired
      offerBox(ctx, terms(Epoch + 1, 4), index = 5))) // starts too soon
    val book = f.api.listOffers(None, None)
    book.total shouldBe 3
    book.offers.map(o => (o.windowStart, o.windowLength, o.strike)) shouldBe Seq(
      (Epoch + 2, 8, STRIKE.toString), (Epoch + 2, 8, (STRIKE + 1).toString), (Epoch + 5, 4, STRIKE.toString))
    book.truncated shouldBe false
    f.api.listOffers(Some(1), Some(1)).offers.map(_.strike) shouldBe Seq((STRIKE + 1).toString)
  }

  it should "say what a fill costs, the move the strike implies, and mark this wallet's own offer unfillable" in {
    val f = fixture(offers = ctx => Seq(offerBox(ctx, open, makerLong = false)))
    val view = f.api.listOffers(None, None).offers.head
    view.makerSide shouldBe "short"
    view.takerLit shouldBe DFMath.sideLit(NOTIONAL).toString
    view.takerNanoErgs shouldBe (DFHelpers.POSITION_ERG / 2 + Parameters.MinFee).toString
    view.lastFillHeight shouldBe Tip + 50
    view.fillable shouldBe true
    view.impliedMove.get shouldBe (BigDecimal(STRIKE) / BigDecimal(f.api.getAccumulator.currentDifficulty) - 1)
      .toDouble +- 1e-9

    val mine = fixture()
    val own = fixture(offers = ctx => Seq(ownOfferBox(ctx, mine.node, open, 0))).api.listOffers(None, None).offers.head
    own.mine shouldBe true
    own.fillable shouldBe false
  }

  it should "mark an offer over the short-window size limit as one this client will not fill" in {
    val big = terms(Epoch + 2, 4, notional = 2 * ShortCap)
    val view = fixture(offers = ctx => Seq(offerBox(ctx, big))).api.listOffers(None, None).offers.head
    view.fillable shouldBe false
    view.refusal.get should include("shortWindowMaxNotionalLit")
  }

  it should "refuse a page outside its bounds" in {
    an[LithosBadRequest] should be thrownBy fixture().api.listOffers(Some(-1), None)
    an[LithosBadRequest] should be thrownBy fixture().api.listOffers(None, Some(ForwardsAPIExecution.MaxPage + 1))
  }

  // ─── creating an offer ────────────────────────────────────────────────────

  private def request(side: String = "long", strike: Long = STRIKE, notional: Long = NOTIONAL,
                      start: Int = Epoch + 2, length: Int = 8, expiry: Option[Int] = None) =
    DFOfferRequest(side, strike.toString, notional.toString, start, length, expiry)

  "An offer quote" should "lock the margin and rewards, and expire at the end of the epoch by default" in {
    val quote = fixture().api.checkOffer(request())
    quote.lit shouldBe DFMath.sideLit(NOTIONAL).toString
    quote.margin shouldBe (NOTIONAL / 5).toString
    quote.maxLoss shouldBe DFMath.sideLit(NOTIONAL).toString
    quote.nanoErgs shouldBe (DFHelpers.POSITION_ERG / 2).toString
    quote.expiry shouldBe (Epoch + 1) * 128
    quote.expiresInBlocks shouldBe (Epoch + 1) * 128 - (Tip + 1)
    quote.lastFillHeight shouldBe quote.expiry
    quote.warnings shouldBe empty
  }

  it should "warn when an explicit expiry outlives the epoch, or outlasts the window's lead" in {
    val late = fixture().api.checkOffer(request(expiry = Some((Epoch + 2) * 128)))
    late.warnings.exists(_.contains("stays open past the end of epoch")) shouldBe true
    late.warnings.exists(_.contains("Fills stop at height")) shouldBe true
    late.lastFillHeight shouldBe ForwardsUserTransactions.lastFillHeight(Epoch + 2)
  }

  it should "refuse terms no fill accepts, a window no fill could reach, and a short window over the size limit" in {
    val f = fixture()
    an[LithosBadRequest] should be thrownBy f.api.checkOffer(request(side = "up"))
    an[LithosBadRequest] should be thrownBy f.api.checkOffer(request(length = 0))
    an[LithosBadRequest] should be thrownBy f.api.checkOffer(request(strike = 0L))
    an[LithosBadRequest] should be thrownBy f.api.checkOffer(request().copy(notional = "ten"))
    an[LithosBadRequest] should be thrownBy f.api.checkOffer(request(expiry = Some(Tip + 1)))
    an[LithosUnprocessable] should be thrownBy f.api.checkOffer(request(start = Epoch + 1))
    an[LithosUnprocessable] should be thrownBy f.api.checkOffer(request(start = Epoch + 121))
    an[LithosUnprocessable] should be thrownBy f.api.checkOffer(request(length = 7, notional = ShortCap + LIT))
    noException should be thrownBy f.api.checkOffer(request(length = 7, notional = ShortCap))
    noException should be thrownBy f.api.checkOffer(request(length = 8, notional = ShortCap + LIT))
  }

  it should "refuse every mutation on a network with no accumulator" in {
    an[LithosUnprocessable] should be thrownBy fixture(deployment = None).api.checkOffer(request())
    an[LithosUnprocessable] should be thrownBy fixture(deployment = None).api.fill("ab" * 32)
  }

  "Creating an offer" should "ask the wallet for the offer's ERG, the fee and the maker's side in LIT" in {
    val f = fixture()
    Future(f.api.createOffer(request()))
    val selection = f.engine.expectMsgType[SelectInputs](10.seconds)
    selection.erg shouldBe DFHelpers.POSITION_ERG / 2 + Parameters.MinFee + UTXO.MIN_CHANGE
    selection.tokens shouldBe Seq(lit(DFMath.sideLit(NOTIONAL)))
  }

  // ─── filling ──────────────────────────────────────────────────────────────

  "Filling an offer" should "fund, sign and send a position with the maker and this wallet on opposite sides" in {
    val f = fixture(offers = ctx => Seq(offerBox(ctx, open)))
    val offerId = f.onChain(ctx => offerBox(ctx, open).boxId)
    val funding = f.onChain(ctx => inputAt(UTXO(f.node.getNodeWallet.contract, 10L * Parameters.OneErg,
      Seq(lit(DFMath.sideLit(NOTIONAL)))), ctx, 3, "ef" * 32))
    val result = Future(f.api.fill(offerId))
    engineServes(f, Some(funding))
    val filled = Await.result(result, 10.seconds)
    filled.outcome shouldBe "accepted"

    val tx = sentTransaction(f)
    tx.id shouldBe filled.txId
    tx.inputs.head.boxId shouldBe offerId
    val position = DFBoxes.position(tx.outputs.head, contracts).get
    filled.positionBoxId shouldBe tx.outputs.head.boxId
    position.terms shouldBe open
    position.long shouldBe f.onChain(ctx => contractOf(maker(ctx)))
    position.short shouldBe f.node.getNodeWallet.contract
    position.lit shouldBe DFMath.positionLit(NOTIONAL)
  }

  it should "refuse, before reserving anything, an offer this wallet made, an unfunded one, and a short window over the limit" in {
    val mineFixture = fixture()
    val cases = Seq(
      "own" -> ((ctx: BlockchainContext) => ownOfferBox(ctx, mineFixture.node, open, 0)),
      "unfunded" -> ((ctx: BlockchainContext) => offerBox(ctx, open, litHeld = DFMath.sideLit(NOTIONAL) - 1)),
      "oversized" -> ((ctx: BlockchainContext) => offerBox(ctx, terms(Epoch + 2, 4, notional = 2 * ShortCap))),
      "expired" -> ((ctx: BlockchainContext) => offerBox(ctx, open, expiry = Tip)))
    cases.foreach { case (name, box) =>
      val f = fixture(offers = ctx => Seq(box(ctx)))
      val id = f.onChain(box(_).boxId)
      val result = Future(f.api.fill(id))
      f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
      f.engine.reply(CompleteMempool.Observation(1L, Some(snapshot()), None))
      withClue(s"$name: ") {
        an[LithosUnprocessable] should be thrownBy Await.result(result, 10.seconds)
        f.engine.expectNoMessage(200.millis)
        verify(f.nodeApi, never()).sendTransaction(anyString())
      }
    }
  }

  it should "be a conflict when an unconfirmed transaction already spends the offer" in {
    val f = fixture(offers = ctx => Seq(offerBox(ctx, open)))
    val id = f.onChain(offerBox(_, open).boxId)
    val result = Future(f.api.fill(id))
    f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
    f.engine.reply(CompleteMempool.Observation(1L, Some(snapshot(id -> ("f1" * 32))), None))
    the[LithosStateChanged].thrownBy(Await.result(result, 10.seconds)).getMessage should include("f1" * 32)
  }

  it should "send nothing once the engine stops it, after funding or at the send, and say nothing was sent" in {
    // Stopped once the wallet has answered, then once the broadcast is under way
    Seq(1, 2).foreach { stopAt =>
      val running = new java.util.concurrent.atomic.AtomicBoolean(true)
      val f = fixture(offers = ctx => Seq(offerBox(ctx, open)), alive = () => running.get)
      val offerId = f.onChain(ctx => offerBox(ctx, open).boxId)
      val funding = f.onChain(ctx => inputAt(UTXO(f.node.getNodeWallet.contract, 10L * Parameters.OneErg,
        Seq(lit(DFMath.sideLit(NOTIONAL)))), ctx, 3, "ef" * 32))
      val result = Future(f.api.fill(offerId))
      f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
      f.engine.reply(CompleteMempool.Observation(1L, Some(snapshot()), None))
      val selection = f.engine.expectMsgType[SelectInputs](10.seconds)
      if (stopAt == 1) running.set(false)
      f.engine.reply(WalletInputs(Seq(funding), selection.reservationId))
      if (stopAt == 2) {
        f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
        running.set(false)
        f.engine.reply(CompleteMempool.Observation(2L, Some(snapshot()), None))
      }
      withClue(s"stopped at step $stopAt: ") {
        the[LithosUnavailable].thrownBy(Await.result(result, 10.seconds)).getMessage should include("nothing was sent")
        f.engine.fishForMessage(10.seconds) {
          case ReleaseInputs(id) => id == selection.reservationId
          case _ => false
        }
        verify(f.nodeApi, never()).sendTransaction(anyString())
      }
    }
  }

  it should "be not found for a box that is not an offer" in {
    val f = fixture(positions = ctx => Seq(positionAt(ctx, maker(ctx), taker(ctx), open, 1)._1.box))
    val id = f.onChain(ctx => positionAt(ctx, maker(ctx), taker(ctx), open, 1)._1.boxId)
    an[LithosNotFound] should be thrownBy f.api.checkFill(id)
    an[LithosNotFound] should be thrownBy f.api.checkFill("ab" * 32)
    an[LithosBadRequest] should be thrownBy f.api.checkFill("not-a-box")
  }

  // ─── cancelling ───────────────────────────────────────────────────────────

  "Cancelling" should "quote the whole offer back less the fee, and refuse an offer another wallet made" in {
    val shell = fixture()
    val f = fixture(offers = ctx => Seq(ownOfferBox(ctx, shell.node, open, 0), offerBox(ctx, open, index = 1)))
    val (own, theirs) = f.onChain(ctx => (ownOfferBox(ctx, shell.node, open, 0).boxId, offerBox(ctx, open, index = 1).boxId))
    val quote = f.api.checkCancel(own)
    quote.feeFromOffer shouldBe true
    quote.returnedLit shouldBe DFMath.sideLit(NOTIONAL).toString
    quote.returnedNanoErgs shouldBe (DFHelpers.POSITION_ERG / 2 - Parameters.MinFee).toString
    an[LithosNotFound] should be thrownBy f.api.checkCancel(theirs)
  }

  // ─── positions ────────────────────────────────────────────────────────────

  "This wallet's positions" should "carry each status, with the mark and what a settlement now pays" in {
    val shell = fixture()
    val me = shell.node.getNodeWallet.prover
    val waiting = terms(Epoch + 3, 8)
    val inWindow = terms(Epoch - 2, 8)
    val ended = terms(Epoch - 14, 8)
    val gone = terms(Epoch - 1 - RING_SIZE - 1, 8)
    val f = fixture(
      positions = ctx => Seq(
        positionAt(ctx, me, taker(ctx), waiting, 1)._1.box,
        positionAt(ctx, taker(ctx), me, inWindow, 2)._1.box,
        positionAt(ctx, me, taker(ctx), ended, 3)._1.box,
        positionAt(ctx, me, taker(ctx), gone, 4)._1.box,
        positionAt(ctx, maker(ctx), taker(ctx), ended, 5)._1.box), // neither side is this wallet's
      offers = ctx => Seq(ownOfferBox(ctx, shell.node, open, 6), offerBox(ctx, open, index = 7)),
      acc = Some(accBox(Epoch - 1, inWindow, ended)))
    val list = f.api.listPositions
    list.positions.map(p => (p.windowStart, p.side, p.status)) shouldBe Seq(
      (gone.windowStart, "long", "refundable"),
      (ended.windowStart, "long", "settleable"),
      (inWindow.windowStart, "short", "in_window"),
      (waiting.windowStart, "long", "waiting"))
    list.offers.map(_.mine) shouldBe Seq(true)

    val settled = list.positions(1)
    val payout = NOTIONAL * 8 / 100
    settled.owedLong shouldBe Some((NOTIONAL / 5 + payout).toString)
    settled.pnl shouldBe Some(payout.toString)
    settled.epochsKnown shouldBe 8

    val refund = list.positions.head
    refund.owedLong shouldBe Some((NOTIONAL / 5).toString)
    refund.markDifficulty shouldBe None
    refund.pnl shouldBe Some("0")

    // Epochs Epoch-2 and Epoch-1 are recorded at +8%, the current one can be; the rest are marked at today's
    val partial = list.positions(2)
    partial.epochsKnown shouldBe 3
    partial.owedLong shouldBe None
    BigInt(partial.markPayout.get) should be > BigInt(0)
    partial.pnl shouldBe Some((-BigInt(partial.markPayout.get)).toString)

    list.positions(3).epochsKnown shouldBe 0
  }

  // ─── settling by hand ─────────────────────────────────────────────────────

  "Settling by hand" should "be refused with the epoch it waits for while the window is unrecorded" in {
    val running = terms(Epoch - 2, 8)
    val f = fixture(positions = ctx => Seq(positionAt(ctx, maker(ctx), taker(ctx), running, 1)._1.box),
      acc = Some(accBox(Epoch - 1, running)))
    val id = f.onChain(ctx => positionAt(ctx, maker(ctx), taker(ctx), running, 1)._1.boxId)
    the[LithosUnprocessable].thrownBy(f.api.checkSettle(id)).getMessage should include(s"epoch ${running.windowStart + 7}")
  }

  it should "settle on the accumulator, recording the epoch due, paying the fee from the position, without funding" in {
    val ended = terms(Epoch - 14, 8)
    val f = fixture(positions = ctx => Seq(positionAt(ctx, maker(ctx), taker(ctx), ended, 1)._1.box),
      acc = Some(accBox(Epoch - 1, ended)))
    val id = f.onChain(ctx => positionAt(ctx, maker(ctx), taker(ctx), ended, 1)._1.boxId)
    val quote = f.api.checkSettle(id)
    quote.records shouldBe true
    quote.rewardLit shouldBe (DFHelpers.SETTLE_REWARD + DFHelpers.RECORD_REWARD).toString

    val result = Future(f.api.settle(id))
    engineServes(f, funding = None)
    val settled = Await.result(result, 10.seconds)
    settled.outcome shouldBe "accepted"
    settled.owedLong shouldBe quote.owedLong
    val tx = sentTransaction(f)
    tx.inputs.map(_.boxId).tail shouldBe Seq(id)
    tx.outputs.map(_.value) should contain(Parameters.MinFee)
    withClue("no wallet input was reserved or pinned: ") { f.engine.expectMsg(RefreshBoxes) }
  }

  it should "be a conflict while the accumulator's unconfirmed spend records an epoch that is over" in {
    val ended = terms(Epoch - 14, 8)
    val f = fixture(positions = ctx => Seq(positionAt(ctx, maker(ctx), taker(ctx), ended, 1)._1.box),
      acc = Some(accBox(Epoch - 2, ended)))
    val (id, accId, staleSuccessor) = f.onChain { ctx =>
      val stale = nodeBox(ctx, accUTXO(AccState(Epoch - 1, ring(Epoch - 1, ended)(8), BALANCE)), 0, "5e" * 32)
      (positionAt(ctx, maker(ctx), taker(ctx), ended, 1)._1.boxId, accBox(Epoch - 2, ended)(ctx).boxId, stale)
    }
    val stale = CompleteMempool.MempoolTx("5e" * 32, NodeTransaction("5e" * 32,
      Seq(NodeInput(accId, NodeSpendingProof.empty)), Seq.empty, Seq(staleSuccessor)), 100)
    val result = Future(f.api.settle(id))
    f.engine.expectMsg(10.seconds, CompleteMempool.Refresh)
    f.engine.reply(CompleteMempool.Observation(1L, Some(snapshot().copy(transactions = Vector(stale),
      spent = Set(accId))), None))
    the[LithosStateChanged].thrownBy(Await.result(result, 10.seconds)).getMessage should include("which is over")
  }


  // ─── the market and this wallet's history ─────────────────────────────────

  "The market" should "sum each side of the book and every unsettled position by status" in {
    val f = fixture(
      offers = ctx => Seq(
        offerBox(ctx, open, index = 0),
        offerBox(ctx, terms(Epoch + 3, 8), index = 1),
        offerBox(ctx, terms(Epoch + 2, 16, notional = 2 * NOTIONAL), makerLong = false, index = 2),
        offerBox(ctx, open, expiry = Tip, index = 3)), // expired, so not in the book
      positions = ctx => Seq(
        positionAt(ctx, maker(ctx), taker(ctx), terms(Epoch + 3, 8), 4)._1.box,
        positionAt(ctx, maker(ctx), taker(ctx), terms(Epoch - 14, 8), 5)._1.box,
        positionAt(ctx, maker(ctx), taker(ctx), terms(Epoch - 140, 8), 6)._1.box),
      acc = Some(accBox(Epoch - 1)))
    val market = f.api.getMarket
    market.longs shouldBe DFBookSide(2, (2 * NOTIONAL).toString)
    market.shorts shouldBe DFBookSide(1, (2 * NOTIONAL).toString)
    market.positions shouldBe 3
    market.openInterest shouldBe (3 * NOTIONAL).toString
    (market.waiting, market.inWindow, market.settleable, market.refundable) shouldBe ((1, 0, 1, 1))
    market.epochsFunded shouldBe Some(BALANCE / DFHelpers.RECORD_REWARD)
  }

  /** `box` as the index lists it once `txId` spent it at `height`. */
  private def spentBox(box: NodeBox, txId: String, height: Int): IndexedBox =
    IndexedBox(box, "df", math.max(1, height - 50), 1L, spentTransactionId = Some(txId), spendingHeight = Some(height))

  /** A confirmed transaction whose first output is `out`. */
  private def tx(id: String, height: Int, out: NodeBox): IndexedTransaction =
    IndexedTransaction(id, Seq.empty, Seq.empty, Seq(LDNodeFixtures.indexed(out)), height, Tip - height, "bb" * 32,
      1792000000000L + height, 0, 1L, 100)

  "This wallet's history" should "price each settlement from the accumulator it left, net of the rewards paid in" in {
    val shell = fixture()
    val me = shell.node.getNodeWallet.prover
    val won = terms(Epoch - 30, 8)
    val refunded = terms(Epoch - 300, 8)
    val f = fixture(
      spent = ctx => Seq(
        spentBox(positionAt(ctx, me, taker(ctx), won, 1)._1.box, "51" * 32, Tip - 100),
        spentBox(positionAt(ctx, taker(ctx), me, refunded, 2)._1.box, "52" * 32, Tip - 200),
        spentBox(positionAt(ctx, maker(ctx), taker(ctx), won, 3)._1.box, "55" * 32, Tip - 100), // not this wallet's
        spentBox(ownOfferBox(ctx, shell.node, won, 4), "53" * 32, Tip - 150),
        spentBox(ownOfferBox(ctx, shell.node, refunded, 5), "54" * 32, Tip - 250)),
      txs = ctx => Seq(
        tx("51" * 32, Tip - 100, accBox(Epoch - 20, won)(ctx)),
        tx("52" * 32, Tip - 200, accBox(Epoch - 100)(ctx)),
        tx("53" * 32, Tip - 150, positionAt(ctx, me, taker(ctx), won, 9)._1.box),
        tx("54" * 32, Tip - 250, nodeBox(ctx, UTXO(shell.node.getNodeWallet.contract, 1000000L), 0, "54" * 32))))
    val history = f.api.getHistory
    val payout = NOTIONAL * 8 / 100
    history.settled.map(s => (s.windowStart, s.side, s.refund)) shouldBe
      Seq((won.windowStart, "long", false), (refunded.windowStart, "short", true))

    val gain = history.settled.head
    gain.payout shouldBe payout.toString
    gain.received shouldBe (NOTIONAL / 5 + payout).toString
    gain.net shouldBe (payout - DFHelpers.SIDE_REWARD).toString
    gain.windowMean shouldBe Some((STRIKE + STRIKE * 8 / 100).toString)
    gain.settledHeight shouldBe Tip - 100

    val refund = history.settled(1)
    refund.received shouldBe (NOTIONAL / 5).toString
    refund.net shouldBe (-DFHelpers.SIDE_REWARD).toString
    refund.windowMean shouldBe None

    history.realizedNet shouldBe (payout - 2 * DFHelpers.SIDE_REWARD).toString
    (history.wins, history.losses) shouldBe ((1, 1))
    history.offers.map(o => (o.outcome, o.positionBoxId.isDefined)) shouldBe Seq(("filled", true), ("cancelled", false))
    history.truncated shouldBe false
  }

  it should "leave out a settlement the index does not hold, rather than count it as a loss" in {
    val me = fixture().node.getNodeWallet.prover
    val won = terms(Epoch - 30, 8)
    val f = fixture(spent = ctx => Seq(spentBox(positionAt(ctx, me, taker(ctx), won, 1)._1.box, "51" * 32, Tip - 100)))
    val history = f.api.getHistory
    history.settled shouldBe empty
    (history.realizedNet, history.wins, history.losses) shouldBe (("0", 0, 0))
  }

  it should "count both payouts, and both sides' rewards, for a position this wallet holds on both sides" in {
    val me = fixture().node.getNodeWallet.prover
    val won = terms(Epoch - 30, 8)
    val f = fixture(
      spent = ctx => Seq(spentBox(positionAt(ctx, me, me, won, 1)._1.box, "51" * 32, Tip - 100)),
      txs = ctx => Seq(tx("51" * 32, Tip - 100, accBox(Epoch - 20, won)(ctx))))
    val settled = f.api.getHistory.settled
    settled should have size 1
    val both = settled.head
    both.side shouldBe "both"
    // The long side's gain is the short side's loss, so only the margins come back
    both.received shouldBe (2 * (NOTIONAL / 5)).toString
    both.net shouldBe (-2 * DFHelpers.SIDE_REWARD).toString
  }

  it should "keep the most recently spent of what it scanned, and say the scan was cut short" in {
    val me = fixture().node.getNodeWallet.prover
    val won = terms(Epoch - 30, 8)
    val f = fixture()
    val (position, accLeft) = f.onChain(ctx => (positionAt(ctx, me, taker(ctx), won, 1)._1.box, accBox(Epoch - 20, won)(ctx)))
    val cap = Batcher.MaxScannedPerTemplate
    // The index holds three caps' worth, each box spent one block after the one before it
    // Replaces the fixture's stubs without calling them, which `when` would do with null arguments
    doAnswer { (inv: InvocationOnMock) =>
      val paging = inv.getArgument[Paging](1)
      if (inv.getArgument[String](0) != contracts.position.ergoTreeHex) Success(Paged(Seq.empty[IndexedBox], 0))
      else Success(Paged((paging.offset until paging.offset + paging.limit)
        .map(i => spentBox(position, f"${i + 1}%064x", i + 1)), 3 * cap))
    }.when(f.nodeApi).boxesByErgoTree(anyString(), any[Paging])
    doAnswer { (inv: InvocationOnMock) =>
      val id = inv.getArgument[String](0)
      Success(Some(tx(id, BigInt(id, 16).toInt, accLeft)))
    }.when(f.nodeApi).indexedTransactionById(anyString())
    val history = f.api.getHistory
    history.truncated shouldBe true
    history.settled.map(_.settledHeight) shouldBe (cap until cap - ForwardsAPIExecution.MaxHistory by -1)
  }
}
