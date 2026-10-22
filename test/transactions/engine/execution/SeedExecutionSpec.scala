package transactions.engine.execution

import configs.WalletConfig
import lfsm.LFSMHelpers
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.Parameters
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import transactions.rollups.{CommitmentSchedule, DataBoxSource}
import work.lithos.mutations.UTXO

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SeedExecutionSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import SeedExecution._

  private val Erg = Parameters.OneErg
  private val Seed = MinBoxValue
  private val Height = 1000

  /** A wallet box as the node reports it, under the signable tree unless another is named. */
  private def entry(i: Int, value: Long, tokens: Boolean = false, onchain: Boolean = true,
                    tree: String = "wallet"): WalletBox = {
    val b = NodeBox(f"$i%064x", "ab" * 32, value, 0, 10, tree,
      if (tokens) Seq(NodeAsset("cd" * 32, 1)) else Seq.empty)
    WalletBox(b, "", if (onchain) Some(5) else None, b.transactionId, 0, if (onchain) Some(10) else None,
      None, None, false, onchain, Seq.empty)
  }

  private def serving(entries: Seq[WalletBox]): NodeApi = {
    val api = mock[NodeApi]
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { invocation =>
      val p = invocation.getArgument[Paging](1)
      Success(entries.slice(p.offset, p.offset + p.limit))
    }
    api
  }

  private def scanOf(entries: Seq[WalletBox], excluded: Set[String] = Set.empty): Scan =
    scan(serving(entries), Set("wallet"), Set("master-reward"), excluded, Height, Seed, WalletConfig.Default)

  private def plain(values: Long*): Seq[WalletBox] =
    values.zipWithIndex.map { case (value, i) => entry(i + 1, value) }

  /** The wallet after `count` seed boxes are split from the largest box, as the next attempt reads it. */
  private def afterSeed(values: Seq[Long], count: Int): Seq[Long] = {
    val largest = values.indexOf(values.max)
    values.patch(largest, Nil, 1) ++ Seq.fill(count)(Seed) :+ (values.max - Fee - count * Seed)
  }

  // ─── what the seed is aiming for ──────────────────────────────────────────

  "A seed box" should "hold the floor value while two bonds, fees and change come to less" in {
    boxValue(1L) shouldBe MinBoxValue
  }
  it should "cover two submissions' bond, fee and change once that is more than the floor" in {
    val score = 100000000L * LFSMHelpers.BOND_DIVISOR
    boxValue(score) shouldBe 2 * (100000000L + Parameters.MinFee + UTXO.MIN_CHANGE)
  }

  "A registration" should "count as initial from its declaration until the window has passed" in {
    initial(CommitmentSchedule(Height, Vector((Height + 80, 5L)))) shouldBe true
    initial(CommitmentSchedule(Height, Vector((Height - InitialWindow, 5L)))) shouldBe true
    initial(CommitmentSchedule(Height, Vector((Height - InitialWindow - 1, 5L)))) shouldBe false
  }
  it should "not count once the commitment has been changed" in {
    initial(CommitmentSchedule(Height, Vector((Height, 6L), (Height - 10, 5L)))) shouldBe false
  }

  // ─── how much is split ────────────────────────────────────────────────────

  "A seed plan" should "split a single 5 ERG box to the target and keep the rest in one box" in {
    val wallet = Seq(5 * Erg)
    val count = plan(scanOf(plain(wallet: _*)), 32, Seed, Fee)
    count shouldBe 31
    afterSeed(wallet, count).max shouldBe 5 * Erg - Fee - 31 * Seed
  }

  it should "put at most half a small wallet into seed boxes" in {
    plan(scanOf(plain(Erg)), 32, Seed, Fee) shouldBe 10
    plan(scanOf(plain(Erg / 10)), 32, Seed, Fee) shouldBe 0
  }

  /** Nothing records that a seed ran, so a restart inside the window has to find nothing to do. */
  it should "find nothing to do after a partial seed" in {
    val wallet = Seq(Erg)
    val count = plan(scanOf(plain(wallet: _*)), 32, Seed, Fee)
    count should be > 0
    plan(scanOf(plain(afterSeed(wallet, count): _*)), 32, Seed, Fee) shouldBe 0
  }

  it should "split one of several equal boxes rather than refuse" in {
    val wallet = Seq(2 * Erg, 2 * Erg, 2 * Erg)
    val count = plan(scanOf(plain(wallet: _*)), 32, Seed, Fee)
    count shouldBe 29
    afterSeed(wallet, count).min shouldBe Seed
    plan(scanOf(plain(afterSeed(wallet, count): _*)), 32, Seed, Fee) shouldBe 0
  }

  it should "do nothing for a wallet already at the target" in {
    plan(scanOf(plain(Seq.fill(32)(Erg): _*)), 32, Seed, Fee) shouldBe 0
  }

  // ─── what a short seed tells the miner ────────────────────────────────────

  /** The figure the warning names has to be the one that works, not an estimate either side of it. */
  "The value a full seed needs" should "be exactly what lets one box reach the target" in {
    val needed = fullSeedValue(scanOf(plain(Erg)), 32, Seed)
    needed shouldBe BigInt(2 * 31 * Seed)
    plan(scanOf(plain(needed.toLong)), 32, Seed, Fee) shouldBe 31
    plan(scanOf(plain(needed.toLong - 1)), 32, Seed, Fee) shouldBe 30
  }

  it should "grow with dust the wallet already holds" in {
    val needed = fullSeedValue(scanOf(plain(Parameters.MinFee, Erg)), 32, Seed)
    needed shouldBe BigInt(2 * (Parameters.MinFee + 31 * Seed))
    plan(scanOf(plain(Parameters.MinFee, needed.toLong)), 32, Seed, Fee) shouldBe 31
  }

  private val ShortWallet = Status(OutcomeNoWork, 1, 32, 0, Seed, None, BigInt(Erg / 20), BigInt(2 * 31 * Seed))

  "A seed short of its target" should "warn with the ERG a full seed needs and what the wallet held" in {
    val warning = shortfall(ShortWallet).get
    warning should include("1 of 32")
    warning should include("3.100 ERG")
    warning should include("0.050 ERG")
    warning should not include "large enough"
  }

  it should "warn after a partial seed, counting the boxes it added" in {
    val partial = ShortWallet.copy(outcome = transactions.engine.EngineBroadcast.Accepted, created = 10,
      txId = Some("ab" * 32), plainValue = BigInt(Erg))
    shortfall(partial).get should include("11 of 32")
  }

  it should "say so when the wallet held enough but no box could be split" in {
    shortfall(ShortWallet.copy(plainValue = BigInt(5 * Erg))).get should include("no confirmed, unreserved box")
  }

  it should "stay quiet for a full seed, an attempt still due, or one that never read the wallet" in {
    shortfall(ShortWallet.copy(outcome = transactions.engine.EngineBroadcast.Accepted, created = 31,
      txId = Some("ab" * 32))) shouldBe None
    shortfall(ShortWallet.copy(outcome = transactions.engine.EngineBroadcast.Rejected, created = 10,
      txId = Some("ab" * 32))) shouldBe None
    shortfall(ShortWallet.copy(outcome = OutcomeWaiting)) shouldBe None
    shortfall(ShortWallet.copy(outcome = OutcomeNotInitial)) shouldBe None
    shortfall(ShortWallet.copy(usable = 32)) shouldBe None
  }

  // ─── what the scan sees ───────────────────────────────────────────────────

  "A seed scan" should "count unconfirmed and owned boxes but split only a confirmed unowned one" in {
    val pending = entry(1, 9 * Erg, onchain = false)
    val owned = entry(2, 8 * Erg)
    val free = entry(3, 3 * Erg)
    val seen = scanOf(Seq(pending, owned, free), excluded = Set(owned.box.boxId))
    seen.usable shouldBe 3
    seen.total shouldBe BigInt(20 * Erg)
    seen.largest.map(_.value) shouldBe Some(3 * Erg)
  }

  it should "leave token boxes and rewards out of the count, the total and the split" in {
    val tokens = entry(1, 9 * Erg, tokens = true)
    val reward = entry(2, 8 * Erg, tree = "master-reward")
    val seen = scanOf(Seq(tokens, reward, entry(3, Erg)))
    seen.usable shouldBe 1
    seen.total shouldBe BigInt(Erg)
    seen.largest.map(_.value) shouldBe Some(Erg)
  }

  it should "hold dust against the half-value bound without counting it as usable" in {
    val seen = scanOf(plain(Parameters.MinFee, Erg))
    seen.usable shouldBe 1
    seen.small shouldBe BigInt(Parameters.MinFee)
  }

  // ─── the transaction ──────────────────────────────────────────────────────

  "The seed transaction" should "pay every seed box and the remainder to the first address, balanced" in {
    val (ctx, _, wallet) = support.FakeNodeContext(numAddresses = 1)
    ctx.getClient.execute { context =>
      val input = UTXO(wallet.contract, 5 * Erg).setCreationHeight(context.getHeight - 10)
        .toInput(context, ErgoId.create("01" * 32), 0)
      val outputs = wallet.sign(build(context, wallet, input, 31, Seed, Fee)).getOutputsToSpend.asScala.toSeq
      outputs should have size 33
      outputs.take(32).foreach(_.getErgoTree shouldBe wallet.contract.ergoTree)
      outputs.take(31).map(_.getValue).distinct shouldBe Seq(Seed)
      outputs(31).getValue shouldBe 5 * Erg - Fee - 31 * Seed
      outputs(32).getValue shouldBe Fee
      outputs.map(_.getValue).sum shouldBe 5 * Erg
      outputs.foreach(_.getTokens.asScala shouldBe empty)
    }
  }

  it should "refuse a split that leaves the remainder smaller than a seed box" in {
    val (ctx, _, wallet) = support.FakeNodeContext(numAddresses = 1)
    ctx.getClient.execute { context =>
      val input = UTXO(wallet.contract, 2 * Seed).setCreationHeight(context.getHeight - 10)
        .toInput(context, ErgoId.create("01" * 32), 0)
      an[IllegalArgumentException] should be thrownBy build(context, wallet, input, 2, Seed, Fee)
    }
  }

  // ─── when a seed runs at all ──────────────────────────────────────────────

  /**
   * One attempt against an engine that never answers, so an attempt that went on to read the mempool
   * or the wallet would time out and throw rather than report an outcome. Returns the data box reads.
   */
  private def gated(token: Boolean, commitments: Try[CommitmentSchedule]): (Status, NodeApi, Int) = {
    val system = akka.actor.ActorSystem("seed-gating")
    try {
      val silent = akka.testkit.TestProbe()(system)
      val api = mock[NodeApi]
      val (ctx, _, _) = support.FakeNodeContext(api, numAddresses = 1)
      val source = new DataBoxSource {
        override def getDataBoxToken: Option[ErgoId] = if (token) Some(ErgoId.create("02" * 32)) else None
      }
      var reads = 0
      val seed = new SeedExecution(ctx, api, silent.ref, source, requestTimeout = 300.millis)(system.dispatcher) {
        override protected def schedule(): Try[CommitmentSchedule] = { reads += 1; commitments }
      }
      (seed.execute(32, () => true), api, reads)
    } finally akka.testkit.TestKit.shutdownActorSystem(system)
  }

  /** The engine asks once a minute until the registration confirms, so the wait has to stay local. */
  "A seed attempt" should "wait without reading the data box while this miner has no token" in {
    val (status, _, reads) = gated(token = false, Failure(new IllegalStateException("never read")))
    status.outcome shouldBe OutcomeWaiting
    reads shouldBe 0
  }

  it should "wait while the data box cannot be read" in {
    gated(token = true, Failure(new IllegalStateException("not confirmed")))._1.outcome shouldBe OutcomeWaiting
  }

  it should "leave a miner registered before the window alone without reading the wallet" in {
    val old = CommitmentSchedule(5000, Vector((5000 - InitialWindow - 1, 5L)))
    val (status, api, _) = gated(token = true, Success(old))
    status.outcome shouldBe OutcomeNotInitial
    settles(status.outcome) shouldBe true
    verify(api, never()).walletUnspentBoxes(any[ConfirmationRange], any[Paging])
  }

  "A seed outcome" should "keep the attempt due while waiting or after a refused send" in {
    settles(OutcomeWaiting) shouldBe false
    settles(transactions.engine.EngineBroadcast.Rejected) shouldBe false
    settles(OutcomeNoWork) shouldBe true
    settles(transactions.engine.EngineBroadcast.Accepted) shouldBe true
    settles(transactions.engine.EngineBroadcast.Uncertain) shouldBe true
  }
}
