package transactions.batching.arb

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{ArbConfig, BatchingConfig}
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.tracking.{ScriptedChain, TrackerFixtures}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, PrepareBlockTxs, RequestArbTxs}

import scala.concurrent.duration._
import scala.util.Success

object ArbStageSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The arb stage on a scripted chain, a real pool tracker and the stake at its arb address. These pin that a
 * round is answered with a signed cycle once the tracker has read the block being built on, that a refresh with
 * nothing changed answers with the same cycles without building them again, that a block moving a pool is built
 * on its new box, that a new parent at the same height is built on rather than answered from the last build,
 * and that a round on a block the tracker was never told of, or with the stake paused, gets nothing.
 */
class ArbStageSpec extends TestKit(ActorSystem("arb-stage-spec", ArbStageSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  import ArbFixtures._
  import TrackerFixtures.tx

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val tip = 1908200

  "The arb stage" should "offer a signed cycle on the tracked pools, and the same cycles on a refresh without rebuilding" in {
    val chain = new ScriptedChain
    val api = chain.api
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val (cheap, dear, dearer, dearest) = nodeContext.getClient.execute { ctx =>
      (nativePool(ctx, id(1), id(101), 1000 * Erg, 1000000000000L),
        nativePool(ctx, id(2), id(101), 1100 * Erg, 1000000000000L),
        nativePool(ctx, id(2), id(101), 1200 * Erg, 1000000000000L),
        nativePool(ctx, id(2), id(101), 1300 * Erg, 1000000000000L))
    }
    chain.extend(tip - 1, tip)
    chain.indexed = tip
    // The stake sits at the arb address, where the index lists it and the UTXO set holds it; no wallet is asked
    val stakeBox = nodeContext.getClient.execute(ctx => arbStakeBox(ctx, wallet, 12 * Erg))
    chain.scans(LDNodeFixtures.indexed(cheap, tip - 5), LDNodeFixtures.indexed(dear, tip - 5),
      LDNodeFixtures.indexed(stakeBox, tip - 5))
    when(api.indexerEnabled).thenReturn(true)
    when(api.boxById(anyString())).thenAnswer { inv =>
      Success(Some(stakeBox).filter(_.boxId == inv.getArgument[String](0)))
    }

    val stakes = new ArbStake(() => api, stakeContract(wallet), nodeContext.getNetwork, 10 * Erg)
    val tracker = TrackerFixtures.tracker(chain)
    val stage = system.actorOf(Props(new ArbStage(nodeContext, ArbConfig.Default.copy(enabled = true),
      BatchingConfig.Default, Set.empty, tracker, stakes, useTrueProp = false, buildBudgetMs = 10000L)))
    val probe = TestProbe()

    stage ! PrepareBlockTxs(tip + 1, 8, chain.header(tip).id)
    var first: BlockTxsReady = null
    awaitAssert({
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = false, Seq.empty))
      first = probe.expectMsgType[BlockTxsReady]
      first.blockHeight shouldBe tip + 1
      first.bundles should not be empty
    }, 30.seconds, 300.millis)
    // A ten percent gap: the stake caps each pass at 10 ERG, so the pair is taken again while it still pays
    first.bundles.foreach(_.members.size shouldBe 2)
    first.bundles.size should be <= 4
    withClue("the first cycle starts from the box at the arb address and no cycle spends a wallet box: ") {
      first.bundles.head.members.head.inputIds should contain(stakeBox.boxId)
      stakes.current.map(_.boxId) shouldBe Some(stakeBox.boxId)
    }
    chain.scanPages.get should be > 0
    val scanned = chain.scanPages.get

    withClue("a refresh with the same stake, basis and parent answers with the build it already made: ") {
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = true, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles should be theSameInstanceAs first.bundles
    }

    withClue("the next block moves a pool: its new box is read from the block, with no scan: ") {
      chain.extend(tip + 1, tip + 1)
      chain.block(tip + 1, tx("t", Seq(dear.boxId), Seq(dearer)))
      stage ! PrepareBlockTxs(tip + 2, 8, chain.header(tip + 1).id)
      awaitAssert({
        probe.send(stage, RequestArbTxs(tip + 2, 8, refresh = false, Seq.empty))
        val next = probe.expectMsgType[BlockTxsReady]
        next.bundles should not be empty
        val spent = next.bundles.flatMap(_.members).flatMap(_.inputIds).toSet
        spent should contain(dearer.boxId)
        spent should not contain dear.boxId
      }, 30.seconds, 300.millis)
      chain.scanPages.get shouldBe scanned
    }

    withClue("a new parent at the same height that moves the pool elsewhere is built on, not answered from the " +
      "last build, and undone without a scan: ") {
      chain.extend(tip + 1, tip + 1, branch = 1)
      chain.block(tip + 1, tx("u", Seq(dear.boxId), Seq(dearest)))
      stage ! PrepareBlockTxs(tip + 2, 8, chain.header(tip + 1).id)
      awaitAssert({
        probe.send(stage, RequestArbTxs(tip + 2, 8, refresh = true, Seq.empty))
        val spent = probe.expectMsgType[BlockTxsReady].bundles.flatMap(_.members).flatMap(_.inputIds).toSet
        spent should contain(dearest.boxId)
        spent should not contain dearer.boxId
      }, 30.seconds, 300.millis)
      chain.scanPages.get shouldBe scanned
    }

    withClue("a block the tracker was never told of gets no cycles: ") {
      probe.send(stage, RequestArbTxs(tip + 5, 8, refresh = false, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    }

    withClue("a paused stake gets no cycles, on a block that had them: ") {
      stakes.pause()
      probe.send(stage, RequestArbTxs(tip + 2, 8, refresh = true, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    }
  }

  it should "fill the Machina orders handed over for the block, with no stake at the arb address" in {
    import node.model.{IndexedBox, MempoolOptions, NodeAsset, NodeBox, Paging, SortDirection}
    import org.mockito.ArgumentMatchers.{any, eq => is}
    val chain = new ScriptedChain
    val api = chain.api
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val gridSells = support.MachinaFixtures.gridSells
    val live = transactions.batching.ergodex.MachinaOrder.parse(gridSells.order).get
    val ld = nodeContext.getClient.execute(ctx => support.MachinaFixtures.lithosDexFakuPoolAt(ctx, live.ask.get * 1.5))
    chain.extend(tip - 1, tip)
    chain.indexed = tip
    chain.scans(LDNodeFixtures.indexed(ld, tip - 5))
    when(api.indexerEnabled).thenReturn(true)
    when(api.boxById(anyString())).thenReturn(Success(None))
    // The pool's fee vault stands, as its deployment's own script holding its NFT
    val contracts = transactions.batching.lithosdex.LDDeployments.verify(org.ergoplatform.appkit.NetworkType.MAINNET,
      ld.ergoTree, ld.assets.map(a => a.tokenId -> a.amount)).get
    val vault = NodeBox(id(61), id(62), 1000000L, 0, tip - 5, contracts.feeVault.ergoTreeHex,
      Seq(NodeAsset(contracts.vaultNFT.toString, 1L)))
    when(api.unspentBoxesByTokenId(is(contracts.vaultNFT.toString), any[Paging], any[SortDirection],
      any[MempoolOptions])).thenReturn(Success(Seq(IndexedBox(vault, "", tip - 5, 9L))))

    val handoff = new MachinaHandoff(active = true, Set.empty)
    val order = live.routedThrough(ld.assets.head.tokenId)
    handoff.offer(tip + 1, Seq(order))
    val stakes = new ArbStake(() => api, stakeContract(wallet), nodeContext.getNetwork, 10 * Erg)
    val stage = system.actorOf(Props(new ArbStage(nodeContext, ArbConfig.Default.copy(enabled = true),
      BatchingConfig.Default, Set.empty, TrackerFixtures.tracker(chain), stakes, useTrueProp = false,
      buildBudgetMs = 10000L, machina = handoff)))
    val probe = TestProbe()
    stage ! PrepareBlockTxs(tip + 1, 8, chain.header(tip).id)
    awaitAssert({
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = false, Seq.empty))
      val bundles = probe.expectMsgType[BlockTxsReady].bundles
      bundles.map(_.members.map(_.inputIds)) shouldBe Seq(Vector(Set(ld.boxId, order.boxId)))
    }, 30.seconds, 300.millis)
    stakes.current shouldBe None
    withClue("orders offered for another block are not filled in this one: ") {
      handoff.offer(tip + 2, Seq(order))
      probe.send(stage, RequestArbTxs(tip + 1, 8, refresh = true, Seq.empty))
      probe.expectMsgType[BlockTxsReady].bundles shouldBe empty
    }
  }
}
