package mining

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.CandidateConfig
import mining.MiningMessages._
import mutations.NodeWallet
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import stratum.{CollateralData, CollateralNotFoundException}
import transactions.BlockTxMessages.{CandidateTxsDropped, RequestBlockTxs}
import support.FakeNodeContext
import work.lithos.mutations.InputUTXO

import scala.concurrent.duration._

/**
 * `CandidateBuilder`'s selection rules — which collateral box a block is built on, and when a box
 * stops being offered.
 *
 * Every defect this covers cost real blocks and none of them produced an error a user could act on.
 * A sticky choice with no release path ended collateral mining permanently and silently; a box the
 * block just spent was handed straight back to the next build; and a spent box produces a genesis
 * transaction the node drops before validation, with a 200 and nothing in the log.
 *
 * The transaction builder is stubbed. What is under test is the actor's bookkeeping, not signing —
 * and stubbing it is also what lets a build "fail" on demand.
 */
object CandidateBuilderSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 15s")
}

class CandidateBuilderSpec extends TestKit(ActorSystem("candidate-builder-spec", CandidateBuilderSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val cfg = CandidateConfig.Default.copy(blockTransactions = false, collateralRefreshInterval = 600000)

  /** A collateral box stand-in. Only its id, age and bid are ever read by the code under test. */
  private def box(ctx: BlockchainContext,
                  wallet: NodeWallet,
                  value: Long,
                  inclusionHeight: Int = 100,
                  finderFee: Long = 0L): CollateralCandidate = {
    import node.MutationConversions._
    val input = NodeBox(f"$value%064x", "aa" * 32, value, 0, 100, wallet.contract.ergoTreeHex)
      .toInputUTXO(ctx)
    CollateralCandidate(input, inclusionHeight, finderFee)
  }

  private def collateralData(boxId: String): CollateralData =
    new CollateralData("tx-" + boxId.take(8), "{}", "pk-" + boxId.take(8), Array.emptyByteArray,
      Array.emptyByteArray, boxId, "9address")

  /**
   * A builder whose collateral set and build outcome the test controls.
   *
   * `loadCollateral` answers from `available`, so a refresh can be made to put a box back — which is
   * how a lagging indexer looks from here. `buildGenesis` fails for any id in `failing`.
   */
  private class StubTxBuilder(prover: NodeWallet, api: NodeApi, c: CandidateConfig,
                              @volatile var available: Seq[CollateralCandidate],
                              @volatile var failing: Set[String])
    extends CandidateTxBuilder(prover, api, c) {

    @volatile var builtFor: Seq[String] = Seq.empty
    @volatile var failNextBuilds: Int = 0

    /**
     * Heights whose build blocks until [[release]]. A completion ordering has to be forced rather
     * than waited for: what is under test is a result arriving after its own block is over.
     */
    @volatile var holdAt: Set[Int] = Set.empty
    private val gate = new java.util.concurrent.CountDownLatch(1)

    def release(): Unit = gate.countDown()

    override def loadCollateral(ctx: BlockchainContext): Seq[CollateralCandidate] = available

    override def buildGenesis(ctx: BlockchainContext, collat: InputUTXO, blockHeight: Int): CollateralData = {
      val id = collat.id.toString
      builtFor = builtFor :+ id
      if (holdAt.contains(blockHeight))
        gate.await(20, java.util.concurrent.TimeUnit.SECONDS)
      if (failNextBuilds > 0) {
        failNextBuilds -= 1
        throw new CollateralNotFoundException(s"stub refuses first build on $id")
      }
      if (failing.contains(id)) throw new CollateralNotFoundException(s"stub refuses $id")
      collateralData(id)
    }
  }

  private class TestableBuilder(client: org.ergoplatform.appkit.ErgoClient, prover: NodeWallet,
                                api: NodeApi, c: CandidateConfig, stub: StubTxBuilder,
                                sources: Seq[ActorRef] = Seq.empty)
    extends CandidateBuilder(client, prover, api, c, sources) {
    override protected val txBuilder: CandidateTxBuilder = stub
  }

  private case class Fixture(builder: ActorRef, parent: TestProbe, stub: StubTxBuilder, ids: Seq[String])



  private def fixture(boxCount: Int = 4,
                      failing: Set[String] = Set.empty,
                      boxes: Option[Seq[CollateralCandidate]] = None,
                      sources: Seq[ActorRef] = Seq.empty,
                      config: CandidateConfig = cfg): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val set = boxes.getOrElse(
      ctx.getClient.execute(c => (1 to boxCount).map(i => box(c, wallet, i * 1000000L))))
    val stub = new StubTxBuilder(wallet, api, config, set, failing)
    val parent = TestProbe()
    val builder = parent.childActorOf(Props(
      new TestableBuilder(ctx.getClient, wallet, api, config, stub, sources)))
    // preStart runs a refresh; let it land so the set is warm before the first block.
    Thread.sleep(600)
    Fixture(builder, parent, stub, set.map(_.id))
  }

  /** A set built against the offline context, so a test can choose each box age and bid. */
  private def boxesWith(specs: Seq[(Long, Int, Long)]): Seq[CollateralCandidate] = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    ctx.getClient.execute(c => specs.map { case (value, height, fee) =>
      box(c, wallet, value, height, fee)
    })
  }

  private def advanceTo(f: Fixture, height: Int): BlockPackage = {
    f.builder ! ChainAdvanced(height)
    f.parent.expectMsgType[BlockPackageReady].pkg
  }

  // ─── stickiness ───────────────────────────────────────────────────────────

  "The chosen box" should "stay the same across rebuilds at one height" in {
    // The node caches one candidate per miner key and the collateral box decides that key, so
    // changing box between polls evicts that cache and forces a full regeneration.
    val f = fixture()
    val first = advanceTo(f, 100)

    f.builder ! RebuildCandidate
    val second = f.parent.expectMsgType[BlockPackageReady].pkg
    // A rebuild is asked for BECAUSE the current box did not work, so it must move...
    second.collateral.collateralId should not equal first.collateral.collateralId
  }

  it should "be re-ranked when a new block arrives, so no box can hold the set" in {
    // Selection is sticky within a height and only within one. Carrying it across blocks froze the
    // set on whichever box was picked first, and nothing would ever become overdue.
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 100, 500000L)))
    val f = fixture(boxes = Some(boxes))
    advanceTo(f, 101).collateral.collateralId shouldEqual boxes(1).id
    // The higher bid is still the higher bid, so a re-rank must land on it again.
    advanceTo(f, 102).collateral.collateralId shouldEqual boxes(1).id
  }

  // ─── generations ──────────────────────────────────────────────────────────
  //
  // A build runs off the actor thread, so its result can arrive after the block it was started for
  // is over. Everything it writes — the chosen box, the skip set, the retry budget, the collection
  // flag — belongs to that block, and a late result that writes them decides the next block's
  // economics on the previous block's information. The gate forces that ordering rather than racing.

  /** Two boxes whose ranking flips between one height and the next, purely on age. */
  private def flipAt101: Seq[CollateralCandidate] =
    boxesWith(Seq((1000000L, 1, 0L), (2000000L, 100, 900000L)))

  "A genesis build that lands after its block" should "not become the next block's choice" in {
    // At 100 nothing is overdue and the high bid wins. At 101 the older box crosses the overdue line
    // and must win instead — which only happens if the late result's choice was not made sticky.
    val boxes = flipAt101
    val f = fixture(boxes = Some(boxes))
    f.stub.holdAt = Set(100)

    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(1.second) // held, so nothing is published for 100
    f.builder ! ChainAdvanced(101)
    f.stub.release()

    val pkg = f.parent.expectMsgType[BlockPackageReady].pkg
    pkg.blockHeight shouldEqual 101
    withClue("101 must re-rank rather than inherit the box 100 chose: ") {
      pkg.collateral.collateralId shouldEqual boxes.head.id
    }
    f.parent.expectNoMessage(1.second)
  }

  "A build failure that lands after its block" should "not skip a box the next block wants" in {
    // Both boxes are the same age at both heights, so the high bidder wins twice. The failure at 100
    // is about that block's attempt; applying its skip to 101 hands 101 the box nobody bid for.
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 100, 900000L)))
    val f = fixture(boxes = Some(boxes))
    f.stub.holdAt = Set(100)
    f.stub.failNextBuilds = 1

    f.builder ! ChainAdvanced(100)
    f.builder ! ChainAdvanced(101)
    f.stub.release()

    val pkg = f.parent.expectMsgType[BlockPackageReady].pkg
    pkg.blockHeight shouldEqual 101
    withClue("100's failure must not exclude the highest bid from 101: ") {
      pkg.collateral.collateralId shouldEqual boxes(1).id
    }
  }

  "A collection round left running by a block" should "not suppress the next block's request" in {
    // The source never answers, so the flag for 100 is still set when 101's genesis is ready. Left
    // there, 101's request is a no-op that nothing restarts and the block mines on genesis alone.
    val source = TestProbe()
    val withTxs = cfg.copy(blockTransactions = true, maxBlockTxs = 4, blockTxTimeout = 30000)
    val f = fixture(sources = Seq(source.ref), config = withTxs)

    f.builder ! ChainAdvanced(100)
    f.parent.expectMsgType[BlockPackageReady]
    source.expectMsgType[RequestBlockTxs].blockHeight shouldEqual 100

    f.builder ! ChainAdvanced(101)
    // Every source is told the previous height is over before anything is asked of it again.
    source.expectMsgType[CandidateTxsDropped].blockHeight shouldEqual 100
    f.parent.expectMsgType[BlockPackageReady]
    withClue("101 has its own package, so it must get its own collection round: ") {
      source.expectMsgType[RequestBlockTxs](10.seconds).blockHeight shouldEqual 101
    }
  }

  // ─── ranking ──────────────────────────────────────────────────────────────

  "Ranking" should "prefer the highest bid while every box is young" in {
    val boxes = boxesWith(Seq((1000000L, 100, 10L), (2000000L, 100, 900L), (3000000L, 100, 400L)))
    val f = fixture(boxes = Some(boxes))
    advanceTo(f, 150).collateral.collateralId shouldEqual boxes(1).id
  }

  it should "take the oldest overdue box even when a younger one bids more" in {
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 180, 900000L)))
    val f = fixture(boxes = Some(boxes))
    // The first box is 100 blocks old at height 200, the second is 20.
    advanceTo(f, 200).collateral.collateralId shouldEqual boxes.head.id
  }

  it should "not treat a box one block short of the threshold as overdue" in {
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 180, 900000L)))
    val f = fixture(boxes = Some(boxes))
    // One block earlier the same box is only 99 old, so the bid still decides.
    advanceTo(f, 199).collateral.collateralId shouldEqual boxes(1).id
  }

  // ─── the §3.25 / §3.2 regression: a spent box must not come back ──────────

  "A box the block just spent" should "not be offered again on the next block" in {
    // Mining a Lithos block consumes the collateral box. The set is refreshed two seconds later
    // against an indexer that has not necessarily applied the block yet, so without a memory that
    // survives the block boundary the next genesis transaction spends an already-spent box — which
    // the node drops before validation, silently, with a 200.
    val f = fixture()
    val first = advanceTo(f, 100)
    val spent = first.collateral.collateralId

    // The block is found: the pool reports the spend, then the chain advances.
    f.builder ! CollateralSpent(spent)
    val second = advanceTo(f, 101)
    second.collateral.collateralId should not equal spent
  }

  it should "stay out even when a lagging refresh reports it as unspent" in {
    // The exact shape of the defect: `skipped` was cleared on every ChainAdvanced, so a refresh that
    // still listed the box put it straight back into the draw.
    // One box, so the draw cannot accidentally avoid it: either the memory holds it out and there
    // is no package at all, or the refresh puts it back and the block is built on a spent box.
    val f = fixture(boxCount = 1)
    val spent = f.ids.head
    advanceTo(f, 100).collateral.collateralId shouldEqual spent

    f.builder ! CollateralSpent(spent)
    // The indexer is behind and still reports the box as unspent.
    f.builder ! CandidateBuilder.RefreshCollateralSet
    Thread.sleep(500)

    f.builder ! ChainAdvanced(101)
    withClue("a box known to be spent must not be built on, even when the node still lists it: ") {
      f.parent.expectNoMessage(3.seconds)
    }
  }

  it should "be forgiven after the memory expires, so a good box is not lost forever" in {
    // CollateralSpent is also sent when the node merely declines a transaction, where the box may be
    // perfectly good — so the exclusion has a ceiling rather than being permanent.
    val f = fixture(boxCount = 1)
    val only = f.ids.head
    val first = advanceTo(f, 100)
    first.collateral.collateralId shouldEqual only

    f.builder ! CollateralSpent(only)
    // With the only box excluded there is nothing to build on for the next few blocks...
    f.builder ! ChainAdvanced(101)
    f.parent.expectNoMessage(2.seconds)

    // ...and past the ceiling it returns.
    val recovered = advanceTo(f, 100 + CandidateBuilder.SpentMemoryBlocks + 1)
    recovered.collateral.collateralId shouldEqual only
  }

  // ─── failures ─────────────────────────────────────────────────────────────

  "A box that cannot build" should "be skipped so the next attempt draws another" in {
    // Sticky selection had no release path: a box that stayed in the set but could not produce a
    // valid genesis transaction re-picked itself on every retry and every later block, forever. One
    // such box would have ended collateral mining permanently and silently.
    // Fail whichever box the random draw chooses first. The retry must select the other box; naming
    // one fixed box as bad made this test pass without exercising the failure path whenever the
    // random draw happened to choose the good box first.
    val f = fixture(boxCount = 2)
    f.stub.failNextBuilds = 1

    val recovered = advanceTo(f, 100)
    f.stub.builtFor should have size 2
    f.stub.builtFor.distinct should have size 2
    recovered.collateral.collateralId shouldEqual f.stub.builtFor.last
  }

  "A set where every box fails" should "produce no package rather than a bad one" in {
    val f = fixture(boxCount = 2)
    f.stub.failing = f.ids.toSet

    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(3.seconds)
  }

  // ─── height discipline ────────────────────────────────────────────────────

  "A package" should "name the height it was built for" in {
    val f = fixture()
    advanceTo(f, 4242).blockHeight shouldEqual 4242
  }

  "A ChainAdvanced that does not move forward" should "be ignored" in {
    val f = fixture()
    advanceTo(f, 100)
    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(1.second)
    f.builder ! ChainAdvanced(99)
    f.parent.expectNoMessage(1.second)
  }

  // ─── the property the whole subsystem rests on ────────────────────────────

  "No message" should "be able to restart the actor" in {
    // Every failure path here is supposed to degrade rather than stop. An exception in `receive`
    // restarts the actor and empties the collateral set, which is a mining outage.
    val f = fixture()
    advanceTo(f, 100)

    val hostile: Seq[Any] = Seq(
      ChainAdvanced(0), ChainAdvanced(-1), RebuildCandidate,
      CollateralSpent(""), CollateralSpent("not-a-box-id"), BlockTxsRejected(0),
      BlockTxsRejected(Int.MaxValue), CandidateBuilder.RefreshCollateralSet, "garbage", 42
    )
    hostile.foreach(m => f.builder ! m)
    Thread.sleep(1500)
    f.parent.receiveWhile(2.seconds) { case _: BlockPackageReady => () }

    // Still alive and still building.
    advanceTo(f, 200).blockHeight shouldEqual 200
  }
}
