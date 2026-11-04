package transactions.batching.arb

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{ArbConfig, BatchingConfig}
import mutations.NodeWallet
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.tracking.{ScriptedChain, TrackerFixtures}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, PrepareBlockTxs, RequestArbTxs}
import transactions.engine.wallet.EngineWalletMessages.RefreshBoxes
import transactions.engine.wallet.EngineWalletState

import scala.concurrent.duration._
import scala.util.Success

object ArbStageSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The arb stage on a scripted chain, a real pool tracker and a real wallet actor holding the stake. These pin
 * that a round is answered with a signed cycle once the tracker has read the block being built on, that a
 * refresh with nothing changed answers with the same cycles without building them again, that a block moving a
 * pool is built on its new box, that a new parent at the same height is built on rather than answered from the
 * last build, and that a round on a block the tracker was never told of gets nothing.
 */
class ArbStageSpec extends TestKit(ActorSystem("arb-stage-spec", ArbStageSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  import ArbFixtures._
  import TrackerFixtures.tx

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val tip = 1908200

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = support.CanonicalNodeBox(boxId = "00" * 32, transactionId = f"$value%064x", value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex, assets = Seq.empty),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = f"$value%064x",
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

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
    chain.scans(LDNodeFixtures.indexed(cheap, tip - 5), LDNodeFixtures.indexed(dear, tip - 5))
    val boxes = Seq(walletBox(wallet, 12 * Erg), walletBox(wallet, 15 * Erg))
    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(boxes) else Success(Seq.empty[WalletBox])
    }
    when(api.boxById(anyString())).thenAnswer { inv =>
      Success(boxes.map(_.box).find(_.boxId == inv.getArgument[String](0)))
    }

    val engine = system.actorOf(Props(new EngineWalletState(nodeContext, configs.WalletConfig.Default, 0, 10 * Erg)))
    engine ! RefreshBoxes
    val tracker = TrackerFixtures.tracker(chain)
    val stage = system.actorOf(Props(new ArbStage(nodeContext, ArbConfig.Default.copy(enabled = true),
      BatchingConfig.Default, Set.empty, tracker, engine, useTrueProp = false, buildBudgetMs = 10000L)))
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
  }
}
