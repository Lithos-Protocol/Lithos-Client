package transactions.emissions

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.EmissionConfig
import lfsm.{CollateralParams, LFSMHelpers}
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeBox, NodeInput, NodeSpendingProof, NodeTransaction, Paging, SortDirection}
import org.ergoplatform.appkit.{Address, BlockchainContext}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{FakeNodeContext, CollateralNodeFixtures => Fx}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, RequestBlockTxs, Supersede}
import transactions.engine.wallet.EngineWalletMessages.{SelectInputs, WalletInputs}
import transactions.engine.wallet.{FundingAllocation, FundingSource}
import work.lithos.mutations.{InputUTXO, Token}

import scala.concurrent.duration._
import scala.util.Success

/**
 * What this miner's own block carries from the collateral queue.
 *
 * Queue spends are planned from CONFIRMED state. With nothing pending, Activates stop at
 * `candidateActivates`; a pending spend leaves the queue to the mempool unless the walk reaches a
 * Clear, which takes the whole walk and supersedes the pending chain. Every spend here is signed
 * against the production emission stack, so a planned step the contracts reject fails the build.
 */
object EmissionCandidateSpec {
  // Contract compilation and signing on the candidate worker can exceed Akka's 3s default.
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
      .withFallback(com.typesafe.config.ConfigFactory.load())
}

class EmissionCandidateSpec extends TestKit(ActorSystem("emission-candidate-spec", EmissionCandidateSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val permit = 2000L * 1000000000L

  /** The block the offline context's funded pass executes in: its chain height is 123,413. */
  private val nextBlock = 123414

  /** Fee-less builds take no wallet input, so this is never asked for anything. */
  private object UnusedWallet extends FundingSource {
    def reserve(value: Long, tokens: Seq[Token]): FundingAllocation =
      throw new IllegalStateException("a fee-less build asked the wallet for funds")
    def reserveCovering(value: Long): FundingAllocation =
      throw new IllegalStateException("a fee-less build asked the wallet for one input")
    def reserveCoveringP2PK(value: Long): FundingAllocation =
      throw new IllegalStateException("a fee-less build asked the wallet for one P2PK input")
    def reserveKnown(inputs: Seq[InputUTXO]): FundingAllocation =
      throw new IllegalStateException("a fee-less build asked the wallet to hold known change")
    def giveBack(boxes: Seq[InputUTXO]): Unit = ()
  }

  // ─── the chain as the node reports it ────────────────────────────────────

  /**
   * Confirmed boxes, and the unconfirmed spends chained onto the emission box, oldest first. Index
   * reads honour `includeUnconfirmed` and `excludeMempoolSpent` as the node does, because which
   * boxes each path is allowed to see is part of what is under test.
   */
  private case class Chain(confirmed: Seq[NodeBox], pending: Seq[NodeTransaction] = Seq.empty,
                           listed: Option[Seq[NodeTransaction]] = None) {
    private val spent = pending.flatMap(_.inputs.map(_.boxId)).toSet

    def unspentByToken(tokenId: String, mempool: MempoolOptions): Seq[IndexedBox] = {
      def carries(b: NodeBox): Boolean = b.assets.headOption.exists(_.tokenId == tokenId)
      val kept = confirmed.filter(carries).filterNot(b => mempool.excludeMempoolSpent && spent.contains(b.boxId))
      val created =
        if (mempool.includeUnconfirmed) pending.flatMap(_.outputs).filter(carries).filterNot(b => spent.contains(b.boxId))
        else Seq.empty[NodeBox]
      (kept ++ created).map(b => Fx.indexed(b))
    }

    /** Unconfirmed transactions with an output under `ergoTree`, or spending a confirmed box under it. */
    def pendingUnder(ergoTree: String): Seq[NodeTransaction] = {
      val confirmedUnder = confirmed.filter(_.ergoTree == ergoTree).map(_.boxId).toSet
      pending.filter(tx => tx.outputs.exists(_.ergoTree == ergoTree) || tx.inputs.exists(i => confirmedUnder(i.boxId)))
    }

    def emission: NodeBox = confirmed.head
  }

  private def stub(api: NodeApi, chain: Chain): Unit = {
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer((inv: InvocationOnMock) =>
        Success(if (inv.getArgument[Paging](1).offset > 0) Seq.empty[IndexedBox]
        else chain.unspentByToken(inv.getArgument[String](0), inv.getArgument[MempoolOptions](3))))
    // `listed` stands in for one page of the node's unordered set, returned exactly as given.
    when(api.unconfirmedTransactionsByErgoTree(anyString(), any[Paging]))
      .thenAnswer((inv: InvocationOnMock) => {
        val paging = inv.getArgument[Paging](1)
        Success(chain.listed.getOrElse(
          chain.pendingUnder(inv.getArgument[String](0)).slice(paging.offset, paging.offset + paging.limit)))
      })
  }

  /** Joins chained one after another off the confirmed emission box, one per lender. */
  private def joinChain(ctx: BlockchainContext, state: Seq[NodeBox], lenders: Seq[Address]): Vector[NodeTransaction] = {
    val tail = state.size - 2L
    lenders.zipWithIndex.foldLeft((state.head, Vector.empty[NodeTransaction])) { case ((spent, txs), (lender, i)) =>
      val id = s"b$i" * 32
      val next = emissionAt(ctx, Seq.empty, 0L, tail + i + 1, txId = id)
      (next, txs :+ NodeTransaction(id, Seq(input(spent)), Seq.empty,
        Seq(next, Fx.queueBox(ctx, lender, tail + i, permit, 1, id))))
    }._2
  }

  /** The emission box, holding `supply - backlog` queue tokens as a real Join leaves it. */
  private def emissionAt(ctx: BlockchainContext, lenderSet: Seq[Address], head: Long, tail: Long,
                         txId: String = "cd" * 32): NodeBox =
    Fx.emissionBox(ctx, lenderSet = lenderSet.map(Fx.entryOf), head = head, tail = tail,
      queueTokens = LFSMHelpers.PROP_TOKEN_AMNT - (tail - head),
      collatTokens = LFSMHelpers.PROP_TOKEN_AMNT - lenderSet.size, txId = txId)

  /** Emission box first, then the config box, then one queue box per lender from position 0. */
  private def confirmedState(ctx: BlockchainContext, lenderSet: Seq[Address], queued: Seq[Address]): Seq[NodeBox] =
    Seq(emissionAt(ctx, lenderSet, 0L, queued.size.toLong), Fx.configBox(ctx)) ++
      queued.zipWithIndex.map { case (lender, i) => Fx.queueBox(ctx, lender, i.toLong, permit) }

  private def queueBoxAt(state: Seq[NodeBox], position: Int): NodeBox = state(2 + position)

  private def input(box: NodeBox): NodeInput = NodeInput(box.boxId, NodeSpendingProof.empty)

  /** A stranger's Join: the tail moves on and a queue box appears at the old tail. */
  private def pendingJoin(ctx: BlockchainContext, state: Seq[NodeBox], lenderSet: Seq[Address],
                          joining: Address): NodeTransaction = {
    val id = "a1" * 32
    val tail = state.size - 2L
    NodeTransaction(id, Seq(input(state.head)), Seq.empty,
      Seq(emissionAt(ctx, lenderSet, 0L, tail + 1, txId = id), Fx.queueBox(ctx, joining, tail, permit, 1, id)))
  }

  /** A funded Activate or Clear at the head: the head moves on and the head queue box is spent. */
  private def pendingHeadSpend(ctx: BlockchainContext, state: Seq[NodeBox], nextSet: Seq[Address],
                               id: String): NodeTransaction =
    NodeTransaction(id, Seq(input(state.head), input(queueBoxAt(state, 0))), Seq.empty,
      Seq(emissionAt(ctx, nextSet, 1L, state.size - 2L, txId = id)))

  /**
   * A builder over a mocked node serving `build`'s chain, with eight wallet addresses. Address 0 is
   * this client's own key, where a Clear's forfeit lands, so lenders are drawn from the rest.
   */
  private def withChain[A](activateCap: Int = 5)(build: (BlockchainContext, Seq[Address]) => Chain)
                          (test: (BlockchainContext, EmissionTransactions, NodeWallet, Chain) => A): A = {
    val api = mock[NodeApi]
    val (nodeCtx, _, wallet) = FakeNodeContext(api, numAddresses = 8)
    val chain = nodeCtx.getClient.execute(ctx => build(ctx, wallet.addresses))
    stub(api, chain)
    val txs = new EmissionTransactions(wallet, api,
      EmissionConfig.Default.copy(candidateActivates = activateCap), UnusedWallet)
    nodeCtx.getClient.execute(ctx => test(ctx, txs, wallet, chain))
  }

  private def firstInput(spend: EmissionSpend): String = spend.tx.getSignedInputs.get(0).getId.toString

  // ─── reading the mempool chain ───────────────────────────────────────────

  "The emission chain" should "be walked in spend order whatever order the mempool lists it in" in {
    // Regression: the walk asked the node for an input and decoded it as a transaction, so a spent
    // emission box read as spent without being recreated and no chain was ever walked.
    withChain() { (ctx, a) =>
      val state = confirmedState(ctx, Seq.empty, Seq(a(1)))
      Chain(state, joinChain(ctx, state, Seq(a(2), a(3))).reverse)
    } { (ctx, txs, _, chain) =>
      val walked = txs.emissionChain(ctx, 16)
      walked.spends.map(_.id) shouldEqual Seq("b0" * 32, "b1" * 32)
      walked.complete shouldBe true
      walked.tip.id.toString shouldEqual chain.pending.head.outputs.head.boxId
    }
  }

  it should "not count as finished when a full page leaves out the next spend" in {
    // Depth 2 reads a page of 3. The node pages an unordered set, so the second spend can be the one
    // left out, and a chain read as ending there would look safe to build on.
    withChain() { (ctx, a) =>
      val state = confirmedState(ctx, Seq.empty, Seq(a(1)))
      val joins = joinChain(ctx, state, Seq(a(2), a(3), a(4), a(5)))
      Chain(state, joins, listed = Some(Seq(joins(0), joins(2), joins(3))))
    } { (ctx, txs, _, _) =>
      val walked = txs.emissionChain(ctx, 2)
      walked.spends.map(_.id) shouldEqual Seq("b0" * 32)
      walked.complete shouldBe false
      walked.pending shouldBe true
    }
  }

  // ─── what the candidate carries ──────────────────────────────────────────

  "A candidate" should "carry Activates from confirmed state, stopping at candidateActivates, when nothing is pending" in {
    withChain(activateCap = 3) { (ctx, a) => Chain(confirmedState(ctx, Seq.empty, a.slice(1, 8))) } {
      (ctx, txs, _, chain) =>
        val (choice, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
        spends.map(_.kind) shouldEqual Seq.fill(3)(EmissionSpend.Activate)
        choice.supersedes shouldBe empty
        choice.driveFunded shouldBe false
        firstInput(spends.head) shouldEqual chain.emission.boxId
    }
  }

  it should "leave the queue to the mempool, and ask for the funded pass, while only joins are pending" in {
    withChain() { (ctx, a) =>
      val state = confirmedState(ctx, Seq.empty, a.slice(1, 3))
      Chain(state, Seq(pendingJoin(ctx, state, Seq.empty, a(3))))
    } { (ctx, txs, _, _) =>
      val (choice, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
      spends shouldBe empty
      choice.supersedes shouldBe empty
      withClue("nothing pending advances the head, so the Activates would otherwise wait for a tick: ") {
        choice.driveFunded shouldBe true
      }
    }
  }

  it should "ask for nothing while the pending chain already advances the head" in {
    withChain() { (ctx, a) =>
      val state = confirmedState(ctx, Seq.empty, a.slice(1, 4))
      Chain(state, Seq(pendingHeadSpend(ctx, state, Seq(a(1)), "a2" * 32)))
    } { (ctx, txs, _, _) =>
      val (choice, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
      spends shouldBe empty
      choice.driveFunded shouldBe false
    }
  }

  it should "supersede the pending chain when the walk reaches a Clear, lifting the Activate cap" in {
    // Regression for Activates built on an unconfirmed emission box without its parent, which the
    // node skipped as spending a missing input, taking every chained spend after it.
    withChain(activateCap = 1) { (ctx, a) =>
      // a(1) already holds a slot, so its queue box behind two Activates can only be Cleared.
      val state = confirmedState(ctx, Seq(a(1)), Seq(a(2), a(3), a(1), a(4)))
      Chain(state, Seq(pendingJoin(ctx, state, Seq(a(1)), a(5))))
    } { (ctx, txs, wallet, chain) =>
      val (choice, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
      spends.map(_.kind) shouldEqual Seq(EmissionSpend.Activate, EmissionSpend.Activate,
        EmissionSpend.Clear, EmissionSpend.Activate)
      choice.supersedes shouldEqual Set("a1" * 32)
      withClue("the first spend builds on the CONFIRMED emission box, not the pending join's output: ") {
        firstInput(spends.head) shouldEqual chain.emission.boxId
      }
      withClue("the Clear pays the forfeited principal and permit to this client: ") {
        val change = spends(2).tx.getOutputsToSpend.get(1)
        change.getValue shouldEqual CollateralParams.PRINCIPAL_FLOOR
        change.getErgoTree.bytesHex shouldEqual wallet.contract.ergoTreeHex
        change.getTokens.get(0).getValue shouldEqual permit
      }
    }
  }

  it should "take a competitor's pending Clear, whose queue box a mempool-aware read would hide" in {
    withChain() { (ctx, a) =>
      val state = confirmedState(ctx, Seq(a(1)), Seq(a(1), a(2)))
      Chain(state, Seq(pendingHeadSpend(ctx, state, Seq(a(1)), "a3" * 32)))
    } { (ctx, txs, _, _) =>
      val (choice, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
      spends.map(_.kind) shouldEqual Seq(EmissionSpend.Clear, EmissionSpend.Activate)
      choice.supersedes shouldEqual Set("a3" * 32)
    }
  }

  it should "hold the head's Activates for the first block it carries them in, and hand them over at the next" in {
    withChain() { (ctx, a) => Chain(confirmedState(ctx, Seq.empty, a.slice(1, 3))) } { (ctx, txs, _, _) =>
      val (first, carried) = txs.candidateQueueSpends(ctx, nextBlock, 20)
      carried.map(_.kind) shouldEqual Seq(EmissionSpend.Activate, EmissionSpend.Activate)
      first.hold shouldEqual Some(QueueHold(0L, nextBlock))

      withClue("a refresh in the same block keeps carrying them under the same hold: ") {
        val (refresh, again) = txs.candidateQueueSpends(ctx, nextBlock, 20, first.hold)
        again should have size 2
        refresh.hold shouldEqual first.hold
      }
      withClue("a block later the same head goes to the funded pass instead: ") {
        val (next, none) = txs.candidateQueueSpends(ctx, nextBlock + 1, 20, first.hold)
        none shouldBe empty
        next.driveFunded shouldBe true
      }
    }
  }

  it should "hold the Activates in a walk with a Clear every block, leaving the funded pass only Clears" in {
    // Otherwise a queue tick sends funded Activates behind this client's own funded Clear, and the
    // candidate's supersede evicts them along with the Clear.
    withChain() { (ctx, a) =>
      Chain(confirmedState(ctx, Seq(a(1)), Seq(a(1), a(2))))
    } { (ctx, txs, _, _) =>
      val (first, _) = txs.candidateQueueSpends(ctx, nextBlock, 20)
      first.hold shouldEqual Some(QueueHold(0L, nextBlock))
      val (next, spends) = txs.candidateQueueSpends(ctx, nextBlock + 1, 20, first.hold)
      spends.map(_.kind) shouldEqual Seq(EmissionSpend.Clear, EmissionSpend.Activate)
      next.hold shouldEqual Some(QueueHold(0L, nextBlock + 1))
      next.driveFunded shouldBe false
    }
  }

  it should "stand aside while the funded pass owns the block's Activates" in {
    withChain() { (ctx, a) => Chain(confirmedState(ctx, Seq.empty, a.slice(1, 3))) } { (ctx, txs, _, _) =>
      val (choice, spends) = txs.candidateQueueSpends(ctx, nextBlock, 20, None, fundedOwns = true)
      spends shouldBe empty
      choice.driveFunded shouldBe false
      choice.hold shouldBe empty
    }
  }

  it should "Clear a duplicate that an earlier Activate in the same walk created" in {
    withChain() { (ctx, a) => Chain(confirmedState(ctx, Seq.empty, Seq(a(2), a(2)))) } { (ctx, txs, _, _) =>
      val (_, spends) = txs.candidateQueueSpends(ctx, ctx.getHeight + 1, 20)
      spends.map(_.kind) shouldEqual Seq(EmissionSpend.Activate, EmissionSpend.Clear)
    }
  }

  // ─── through the actor ───────────────────────────────────────────────────

  private def harnessConfig(extra: Map[String, Any]): Configuration = Configuration.from(Map[String, Any](
    "emission.enabled" -> true,
    "emission.autoCollateralize" -> false,
    // Long intervals so no timer fires; every funded pass in these tests is driven by a candidate.
    "emission.queueInterval" -> 3600000,
    "emission.collateralizeInterval" -> 3600000
  ) ++ extra)

  private def harness(build: (BlockchainContext, Seq[Address]) => Chain,
                      extra: Map[String, Any] = Map.empty): (akka.actor.ActorRef, TestProbe) = {
    val api = mock[NodeApi]
    val (nodeCtx, _, wallet) = FakeNodeContext(api, numAddresses = 8)
    stub(api, nodeCtx.getClient.execute(ctx => build(ctx, wallet.addresses)))
    val walletProbe = TestProbe()
    (system.actorOf(Props(new EmissionsCoreHaress(harnessConfig(extra), nodeCtx, walletProbe.ref))), walletProbe)
  }

  "RequestBlockTxs" should "answer with one superseding bundle and no mempool ancestor when a Clear is in reach" in {
    val (handler, _) = harness { (ctx, a) =>
      val state = confirmedState(ctx, Seq(a(1)), Seq(a(1), a(2)))
      Chain(state, Seq(pendingJoin(ctx, state, Seq(a(1)), a(3))))
    }
    val probe = TestProbe()
    probe.send(handler, RequestBlockTxs(500, 20))
    val bundles = probe.expectMsgType[BlockTxsReady].bundles
    bundles should have size 1
    bundles.head.members.map(_.kind) shouldEqual Vector(CandidateTx.Clear, CandidateTx.Activate)
    bundles.head.interactions shouldEqual Seq(Supersede(Set("a1" * 32)))
  }

  it should "trim a walk that overruns the source's byte allowance to the prefix that fits" in {
    // A bundle over the allowance is refused whole, which would drop the Clear at its head too.
    // The same chain builds byte-identical spends, so the allowance is set from an untrimmed build.
    val probe = TestProbe()
    def members(extra: Map[String, Any]): Seq[CandidateTx] = {
      val (handler, _) = harness({ (ctx, a) =>
        Chain(confirmedState(ctx, Seq(a(1)), Seq(a(1), a(2), a(3))))
      }, extra)
      probe.send(handler, RequestBlockTxs(500, 20))
      probe.expectMsgType[BlockTxsReady].bundles.flatMap(_.members)
    }

    val whole = members(Map.empty)
    whole.map(_.kind) shouldEqual Seq(CandidateTx.Clear, CandidateTx.Activate, CandidateTx.Activate)
    val allowance = whole(0).sizeBytes.toLong + whole(1).sizeBytes - 1
    members(Map("stratum.candidate.sources.emissions.maxBytes" -> allowance)) shouldEqual whole.take(1)
  }

  it should "drive the funded pass at most once per block height while joins hold the queue" in {
    val (handler, walletProbe) = harness { (ctx, a) =>
      val state = confirmedState(ctx, Seq.empty, a.slice(1, 3))
      Chain(state, Seq(pendingJoin(ctx, state, Seq.empty, a(3))))
    }
    val probe = TestProbe()

    def fundedPassAsked(): Unit = {
      val request = walletProbe.fishForMessage() { case _: SelectInputs => true; case _ => false }
      // No inputs, so the funded Activate fails and the pass releases the emission lock.
      walletProbe.reply(WalletInputs(Seq.empty, request.asInstanceOf[SelectInputs].reservationId))
    }

    probe.send(handler, RequestBlockTxs(nextBlock, 20))
    probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    fundedPassAsked()
    // Settles the failed pass, so a second one could start and only the height gate can refuse it.
    Thread.sleep(2000)

    probe.send(handler, RequestBlockTxs(nextBlock, 20, refresh = true))
    probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    withClue("a refresh at the same height must not start another funded pass: ") {
      walletProbe.receiveWhile(3.seconds) { case m => m }.collect { case s: SelectInputs => s } shouldBe empty
    }

    probe.send(handler, RequestBlockTxs(nextBlock + 1, 20))
    probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    fundedPassAsked()
  }

  it should "keep a queue tick off the Activates its candidate holds, then broadcast them a block later" in {
    val (handler, walletProbe) = harness { (ctx, a) => Chain(confirmedState(ctx, Seq.empty, a.slice(1, 3))) }
    val probe = TestProbe()

    probe.send(handler, RequestBlockTxs(nextBlock, 20))
    probe.expectMsgType[BlockTxsReady].bundles.flatMap(_.members.map(_.kind)) shouldEqual
      Seq(CandidateTx.Activate, CandidateTx.Activate)

    handler ! EmissionsCore.DriveQueue
    withClue("the funded copies would be evicted by the next request carrying the fee-less ones: ") {
      walletProbe.receiveWhile(3.seconds) { case m => m }.collect { case s: SelectInputs => s } shouldBe empty
    }

    probe.send(handler, RequestBlockTxs(nextBlock + 1, 20))
    probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    val request = walletProbe.fishForMessage() { case _: SelectInputs => true; case _ => false }
    walletProbe.reply(WalletInputs(Seq.empty, request.asInstanceOf[SelectInputs].reservationId))
  }
}
