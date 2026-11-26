package transactions.rent

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import configs.{CandidateConfig, CandidateSourceConfig, RentConfig}
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.Parameters
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import transactions.candidate.BlockTxMessages.{BlockTxsReady, RequestBlockTxs}
import work.lithos.mutations.Contract

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * The rent source's sweep read, driven through the actor: what a failed read does to the boxes it
 * was holding, asserted on the next build after the read recovers.
 */
class StorageRentSweepSpec extends TestKit(ActorSystem("storage-rent-sweep-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with ImplicitSender with AnyFlatSpecLike with Matchers with MockitoSugar with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val dueAt = support.RentRule.dueHeight

  /** Created at zero, so due at `dueAt`; the walk's timer is pushed out of the test's reach. */
  private def sourceWith(api: NodeApi, box: NodeBox) = {
    val (nodeContext, _, _) = support.FakeNodeContext(api, numAddresses = 1)
    val source = system.actorOf(Props(new StorageRentSource(nodeContext,
      RentConfig(startBehindBlocks = 0, scanIntervalMs = 3600000, blocksPerScan = 1, lookaheadBlocks = 0),
      CandidateConfig.Default.limitsFor(CandidateSourceConfig.Rent), useTrueProp = false)))
    source ! StorageRentSource.Scanned(Success(
      StorageRentSource.Pass(1, 2, Map(box.boxId -> box.creationHeight), Set.empty)))
    source
  }

  private val box = NodeBox("ab" * 32, "cd" * 32, 100L * Parameters.OneErg, 0, 0, Contract.SIGMA_FALSE.ergoTreeHex)

  "A sweep whose read fails" should "keep its due boxes and offer them once the read recovers" in {
    val api = mock[NodeApi]
    when(api.boxesWithPoolByIds(any())).thenReturn(Failure(new RuntimeException("node busy")), Success(Seq(box)))
    val source = sourceWith(api, box)

    source ! RequestBlockTxs(dueAt, 8)
    expectMsgType[BlockTxsReady](30.seconds).bundles shouldBe empty

    source ! RequestBlockTxs(dueAt, 8, refresh = true)
    withClue("the box held before the failed read must still be swept: ") {
      expectMsgType[BlockTxsReady](30.seconds).bundles should have size 1
    }
  }

  /** The other side of the same rule: a read that succeeds without the box does forget it. */
  "A sweep whose read leaves a box out" should "stop offering that box" in {
    val api = mock[NodeApi]
    when(api.boxesWithPoolByIds(any())).thenReturn(Success(Seq.empty[NodeBox]), Success(Seq(box)))
    val source = sourceWith(api, box)

    source ! RequestBlockTxs(dueAt, 8)
    expectMsgType[BlockTxsReady](30.seconds).bundles shouldBe empty

    source ! RequestBlockTxs(dueAt, 8, refresh = true)
    expectMsgType[BlockTxsReady](30.seconds).bundles shouldBe empty
  }
}
