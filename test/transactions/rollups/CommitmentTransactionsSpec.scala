package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.NodeContext
import lfsm.LFSMHelpers
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.scalaapi.{scalaIntType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
import support.FakeNodeContext
import transactions.wallet.WalletMessages._
import transactions.wallet.{WalletManager, WalletSelector}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * The difficulty commitment: which entry is in force, and what happens to the wallet input that pays
 * to move it.
 *
 * The reservation half is why this class exists. `beginSubmission` is an obligation with no expiry —
 * the manager refuses to age a Submitting lease out, because releasing one while a node call may
 * still be in flight is a double spend — so a caller that acquires the permit and then throws holds
 * those boxes until the process restarts. When this lived in `LFSMTransformer` the permit was taken
 * inside a callback the caller supplied, which put the obligation on the caller and left one path
 * where nothing discharged it. It is owned here now, and these are what hold that.
 *
 * The reading half matters for a different reason: a NISP judged against the wrong committed score
 * is the shape a fraud proof is written for, so `commitmentForNISP` must fail rather than default.
 */
object CommitmentTransactionsSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class CommitmentTransactionsSpec
  extends TestKit(ActorSystem("commitment-transactions-spec", CommitmentTransactionsSpec.config))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  /** 10G, so the configured score differs from every commitment these fixtures declare. */
  private val diff = "10G"

  private def configuredScore: Long =
    LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum(diff).get)).toLong

  private class TestableWalletManager(ctx: NodeContext, clock: () => Long) extends WalletManager(ctx) {
    override protected def now(): Long = clock()
  }

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "aa" * 32, value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /**
   * A data box carrying `commits` in R4.
   *
   * Locked under the prover's own script rather than the real data-box contract, so signing succeeds
   * and the send is the only thing that can fail. What the real script accepts is the contract
   * suites' question; this one is about what happens to the wallet input either side of the node
   * call.
   */
  private def dataBoxWith(ctx: BlockchainContext,
                          wallet: NodeWallet,
                          commits: Seq[(Int, Long)],
                          contract: Contract = null): InputUTXO =
    UTXO(if (contract == null) wallet.contract else contract, Parameters.MinFee,
      Seq(work.lithos.mutations.Token(ErgoId.create("dc" * 32), 1L)),
      Seq(ErgoValue.of(Colls.fromArray(commits.toArray), ErgoType.pairType(scalaIntType, scalaLongType))))
      .toInput(ctx, ErgoId.create("ab" * 32), 0.toShort)

  private case class Fixture(commitments: CommitmentTransactions,
                             selector: WalletSelector,
                             mgr: ActorRef,
                             probe: TestProbe,
                             clock: AtomicLong,
                             readable: java.util.concurrent.atomic.AtomicBoolean)

  /**
   * @param commits what R4 holds, or None for "this miner has no data box"
   * @param unsignable lock the data box under a script the prover has no secret for, so the build
   *                   fails before anything reaches the node
   */
  private def fixture(commits: Option[Seq[(Int, Long)]],
                      unsignable: Boolean = false): Fixture = {
    val api = mock[NodeApi]
    val (nodeCtx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val clock = new AtomicLong(1000000L)
    val readable = new java.util.concurrent.atomic.AtomicBoolean(true)

    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Seq(walletBox(wallet, 5 * erg)))
      else Success(Seq.empty[WalletBox])
    }
    // `readable` fails the REWARD lookup, not the wallet one. Both feed the same completeness flag,
    // so either stops the refresh resolving anything — but only this one leaves the wallet box in
    // the manager's map. Failing the wallet read instead removes the box from the map as well, and
    // then it is absent whether it is reserved or not, which is a test that cannot fail.
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { _ =>
        if (readable.get()) Success(Seq.empty[IndexedBox])
        else Failure(new RuntimeException("reward lookup refused"))
      }

    val mgr = system.actorOf(Props(new TestableWalletManager(nodeCtx, () => clock.get())))
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    val source = new DataBoxSource {
      override def getDataBoxToken: Option[ErgoId] =
        if (commits.isDefined) Some(ErgoId.create("dc" * 32)) else None
    }
    val commitments = new CommitmentTransactions(nodeCtx, source) {
      override protected def dataBox(ctx: BlockchainContext): Try[InputUTXO] = commits match {
        case None => Failure(new state.DataBoxRetrievalException("no stored data box"))
        case Some(cs) =>
          val foreign = if (unsignable) Contract(sigma.ast.ErgoTree.fromHex("10010101d17300")) else null
          Success(dataBoxWith(ctx, wallet, cs, foreign))
      }
    }
    Fixture(commitments, WalletSelector(mgr, 5.seconds, ec), mgr, TestProbe(), clock, readable)
  }

  private def offered(f: Fixture, need: Long): Seq[InputUTXO] = {
    f.probe.send(f.mgr, RetrieveInputs(need, Seq.empty, trackUsed = false))
    f.probe.expectMsgType[WalletInputs].inputs
  }

  /** The current height, which every fixture's commitment heights are chosen relative to. */
  private lazy val height: Int = FakeNodeContext()._1.getClient.execute(_.getHeight)

  /**
   * A commitment old enough both to be in force and to be replaceable. The contract spaces changes by
   * a full rollup lifetime past the window, so anything younger is readable but cannot be moved.
   */
  private def inForce(score: Long): Seq[(Int, Long)] =
    Seq((height - (LFSMHelpers.NISP_WINDOW + LFSMHelpers.ROLLUP_LIFETIME).toInt - 1) -> score)

  private def pending(nextScore: Long, oldScore: Long): Seq[(Int, Long)] =
    Seq(height -> nextScore, (height - 1000) -> oldScore)

  // ─── the obligation ───────────────────────────────────────────────────────

  "An ambiguous commitment send" should "hold its wallet input until a COMPLETE read resolves it" in {
    // The regression for the leak this class was extracted to remove. The send throws — there is no
    // node behind the offline client — which is indistinguishable from a node that accepted the
    // transaction and then dropped the connection. Releasing on that would be a double spend.
    //
    // The reward lookup is made to fail from here on, so no refresh can be COMPLETE and nothing can
    // resolve the reservation in either direction — while the wallet box itself stays in the
    // manager's map, which is what makes the assertion mean something. Under a release the box is
    // handed straight back; under `uncertain()` it is not. The TTL sweep must not end it either,
    // which is the second half.
    val f = fixture(Some(inForce(configuredScore + 1)))
    f.readable.set(false)

    f.commitments.commitScore(diff, f.selector).isFailure shouldBe true

    offered(f, erg) shouldBe empty
    f.clock.addAndGet(WalletManager.ReservationTtlMs + 1L)
    f.probe.send(f.mgr, ResetUsedInputs)
    offered(f, erg) shouldBe empty
  }

  it should "hand the input back once a complete read proves the send never landed" in {
    // The other half, and what makes the hold above safe to have. Uncertain is resolved by a
    // complete mempool-aware read; Submitting is resolved by nothing at all. A lease left Submitting
    // would pass the test above and fail this one, which is the distinction that matters.
    val f = fixture(Some(inForce(configuredScore + 1)))
    f.commitments.commitScore(diff, f.selector).isFailure shouldBe true

    f.mgr ! RefreshBoxes
    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(5 * erg), 10.seconds, 200.millis)
  }

  "A commitment that fails before the node call" should "release its selection at once" in {
    // The other direction. Nothing left this process, so holding the input for fifteen minutes would
    // be contention against SubmissionHandler and LithosDex for no reason.
    val f = fixture(Some(inForce(configuredScore + 1)), unsignable = true)

    f.commitments.commitScore(diff, f.selector).isFailure shouldBe true
    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(5 * erg), 5.seconds, 100.millis)
  }

  // ─── when not to move at all ──────────────────────────────────────────────

  "commitScore" should "do nothing while the current commitment has not taken effect" in {
    // Replacing an entry that is not yet in force would leave the window with no agreed score in it.
    val f = fixture(Some(pending(configuredScore + 1, configuredScore + 2)))
    f.commitments.commitScore(diff, f.selector) shouldEqual Success(None)
    offered(f, erg).map(_.value) shouldEqual Seq(5 * erg)
  }

  it should "do nothing when the commitment already matches the configured score" in {
    val f = fixture(Some(inForce(configuredScore)))
    f.commitments.commitScore(diff, f.selector) shouldEqual Success(None)
    offered(f, erg).map(_.value) shouldEqual Seq(5 * erg)
  }

  it should "do nothing, and reserve nothing, when this miner has no data box" in {
    val f = fixture(None)
    f.commitments.commitScore(diff, f.selector) shouldEqual Success(None)
    offered(f, erg).map(_.value) shouldEqual Seq(5 * erg)
  }

  // ─── which entry is in force ──────────────────────────────────────────────

  "commitmentForNISP" should "read the aged entry once it has taken effect" in {
    val f = fixture(Some(inForce(4242L)))
    f.commitments.commitmentForNISP(height) shouldEqual Success(4242L)
  }

  it should "read the previous entry while the newest one is still pending" in {
    // The one that costs a rollup. Entry 0 is declared ahead of time; everyone judging this miner's
    // NISPs is still using entry 1 until it ages, so building against entry 0 is building against a
    // score nobody has agreed to.
    val f = fixture(Some(pending(nextScore = 999L, oldScore = 4242L)))
    f.commitments.commitmentForNISP(height) shouldEqual Success(4242L)
  }

  it should "fail rather than default when nothing has ever been in force" in {
    // A single entry that has not aged means no score has ever taken effect. Defaulting to it would
    // submit NISPs against a commitment the chain does not yet recognise — which is the shape a
    // fraud proof is written for, so this is the one reader that must not fall back.
    val f = fixture(Some(Seq(height -> 999L)))
    f.commitments.commitmentForNISP(height).isFailure shouldBe true
  }

  it should "fail rather than default when there is no data box" in {
    fixture(None).commitments.commitmentForNISP(height).isFailure shouldBe true
  }

  // ─── the readers that keep the rigs mining ────────────────────────────────

  "committedTau" should "fall back to the configured tau rather than stop mining" in {
    // Priority 1 outranks priority 2: mining on a stale tau still finds blocks, and the NISPs built
    // from those shares are checked separately by commitmentForNISP, which does not fall back.
    val configured = BigInt(12345)
    fixture(None).commitments.committedTau(configured) shouldEqual Success(configured)
  }

  it should "use the committed score when one is in force" in {
    val f = fixture(Some(inForce(4242L)))
    f.commitments.committedTau(BigInt(12345)) shouldEqual
      Success(LFSMHelpers.convertTauOrScore(BigInt(4242L)))
  }

  "committedScore" should "fall back to the configured diff rather than refuse to start" in {
    val f = fixture(None)
    val ctx = FakeNodeContext()._1
    ctx.getClient.execute { c =>
      f.commitments.committedScore(c, diff, "stratum mining") shouldEqual Success(configuredScore)
    }
  }
}
