package support

import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar

import akka.testkit.TestProbe
import state.messages.SyncMessages.{GetCommittedState, Starting, SyncUnavailable}

import scala.util.{Success, Try}

/** Parent-linked node fixtures using the node's exclusive `chainSlice.fromHeight` convention. */
object ChainFixtures extends MockitoSugar {

  def headerId(height: Int): String = SyncFixtures.id(900000 + height)

  def header(height: Int): NodeHeader =
    NodeHeader(headerId(height), headerId(height - 1), height, 0L, 1, 0L, BigInt(0),
      "", "", "", "", NodePowSolution("", "", "", ""), "")

  /** A block with one ordinary transaction, so it decodes into the shape the reducer receives. */
  def block(height: Int): IndexedBlock = {
    val head = header(height)
    val spent = IndexedBox(NodeBox(SyncFixtures.id(910000 + height), "", 1000000L, 0, height,
      "spent-tree"), "", height, 0L, Some(SyncFixtures.id(920000 + height)), Some(height), None)
    val created = IndexedBox(NodeBox(SyncFixtures.id(930000 + height), SyncFixtures.id(920000 + height),
      1000000L, 0, height, "created-tree"), "", height, 0L, None, None, None)
    val tx = IndexedTransaction(SyncFixtures.id(920000 + height), Seq(spent), Seq.empty, Seq(created),
      height, 1, head.id, 0L, 0, 0L, 0)
    IndexedBlock(head, Seq(tx), NodeExtension(head.id, "", Seq.empty), None, 0)
  }

  /** Returns a node with canonical data through `tip` and an exclusive slice lower bound. */
  def nodeAt(tip: Int, firstHeight: Int = 1): NodeApi = {
    val api = mock[NodeApi]
    when(api.info()).thenReturn(Success(infoAt(tip)))
    // The blocks and boxes this client reads come from the extra index, so catch-up follows it.
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(tip, tip)))

    when(api.chainSlice(any[Option[Int]], any[Option[Int]])).thenAnswer { invocation =>
      val from = invocation.getArgument[Option[Int]](0).getOrElse(0)
      val to = invocation.getArgument[Option[Int]](1).getOrElse(tip)
      val lowest = math.max(from + 1, firstHeight)
      val highest = math.min(to, tip)
      Success(if (highest < lowest) Seq.empty else (lowest to highest).map(header)): Try[Seq[NodeHeader]]
    }

    when(api.indexedBlocksByHeaderIds(any[Seq[String]])).thenAnswer { invocation =>
      val ids = invocation.getArgument[Seq[String]](0)
      val byId = (firstHeight to tip).map(h => headerId(h) -> block(h)).toMap
      Success(ids.flatMap(byId.get)): Try[Seq[IndexedBlock]]
    }
    api
  }

  /** Answers the producer's first startup question: this owner holds no state of its own yet. */
  def unseeded(sync: TestProbe): Unit = {
    sync.expectMsg(GetCommittedState)
    sync.reply(SyncUnavailable(Starting))
  }

  def infoAt(tip: Int): NodeInfo =
    NodeInfo("test", "6.0.3", Some(tip), Some(tip), Some(tip), None, None, None, None, "utxo",
      None, isMining = false, 0, 0, None, 0L, 0L, None, None, None,
      NodeParameters(tip, 0, 0, 0, 0, 3, 0, 0, 0, 0), None, None, None)
}
