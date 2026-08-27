package state.synchronization

import node.NodeApi
import node.model.{IndexedBlock, IndexedBox, IndexedTransaction, NodeBox, NodeExtension, NodeHeader, NodePowSolution}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.messages.SyncMessages.SyncCursor
import support.SyncFixtures

import scala.util.Success

class CanonicalBlockSourceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  "CanonicalBlockSource" should "load the indexed block named by the canonical header" in {
    val nodeApi = mock[NodeApi]
    val previous = header("previous", 99, "ancestor")
    val canonical = header("canonical", 100, previous.id)
    val indexed = blockOf(canonical)
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(canonical)))
    when(nodeApi.indexedBlocksByHeaderIds(Seq(canonical.id))).thenReturn(Success(Seq(indexed)))

    val result = new CanonicalBlockSource(nodeApi).blockAt(100).get

    result.id shouldEqual canonical.id
    result.parentId shouldEqual previous.id
    result.height shouldEqual 100
    verify(nodeApi).indexedBlocksByHeaderIds(Seq(canonical.id))
  }

  it should "reject an ambiguous canonical header response before loading a block" in {
    val nodeApi = mock[NodeApi]
    val first = header("first", 100, "previous")
    val second = header("second", 100, "previous")
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(first, second)))

    new CanonicalBlockSource(nodeApi).blockAt(100).isFailure shouldBe true
    verify(nodeApi, never()).indexedBlocksByHeaderIds(Seq(first.id))
    verify(nodeApi, never()).indexedBlocksByHeaderIds(Seq(second.id))
  }

  it should "reject an indexed block whose height contradicts its header" in {
    val nodeApi = mock[NodeApi]
    val canonical = header("canonical", 100, "previous")
    val wrongHeight = header("canonical", 101, "previous")
    val indexed = blockOf(wrongHeight)
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(canonical)))
    when(nodeApi.indexedBlocksByHeaderIds(Seq(canonical.id))).thenReturn(Success(Seq(indexed)))

    new CanonicalBlockSource(nodeApi).blockAt(100).failed.get.getMessage should include("does not match")
  }

  /** Empty transaction data is reported but not treated as a permanent synchronization failure. */
  it should "still return an indexed block that decoded with no transactions" in {
    val nodeApi = mock[NodeApi]
    val canonical = header("canonical", 100, "previous")
    val withoutTxs = IndexedBlock(canonical, Seq.empty, NodeExtension(canonical.id, "", Seq.empty), None, 0)
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(canonical)))
    when(nodeApi.indexedBlocksByHeaderIds(Seq(canonical.id))).thenReturn(Success(Seq(withoutTxs)))

    val block = new CanonicalBlockSource(nodeApi).blockAt(100).get

    block.id shouldEqual canonical.id
    block.txs shouldBe empty
  }

  /**
   * The degraded shape the index can actually return is an input box stripped to its id. A block whose
   * transaction inputs are absent entirely resolves no inputs either, so the two are indistinguishable
   * from the outside; this fixture uses the one the reducer can tell apart.
   */
  it should "still return a block whose input boxes carry no script, so one bad shape cannot stop the cursor" in {
    val nodeApi = mock[NodeApi]
    val canonical = header("canonical", 100, "previous")
    val stripped = blockOf(canonical).copy(blockTransactions = blockOf(canonical).blockTransactions.map(
      tx => tx.copy(inputs = tx.inputs.map(in => in.copy(box = in.box.copy(ergoTree = ""))))))
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(canonical)))
    when(nodeApi.indexedBlocksByHeaderIds(Seq(canonical.id))).thenReturn(Success(Seq(stripped)))

    val block = new CanonicalBlockSource(nodeApi).blockAt(100).get

    block.id shouldEqual canonical.id
    // The inputs resolve, so the reducer sees them; none authenticates a genesis.
    block.resolvedInputs should not be empty
    block.resolvedInputs.values.foreach(_.ergoTree shouldBe empty)
  }

  it should "reject a batch that omits a block the canonical headers named" in {
    val nodeApi = mock[NodeApi]
    val canonical = header("canonical", 100, "previous")
    val unrelated = header("unrelated", 100, "previous")
    val indexed = blockOf(unrelated)
    when(nodeApi.chainSlice(Some(99), Some(100))).thenReturn(Success(Seq(canonical)))
    when(nodeApi.indexedBlocksByHeaderIds(Seq(canonical.id))).thenReturn(Success(Seq(indexed)))

    new CanonicalBlockSource(nodeApi).blockAt(100).failed.get.getMessage should include("has no block")
  }

  /** Verifies the node's exclusive `fromHeight` convention for multi-header ranges. */
  it should "ask one height lower, because the node's fromHeight is exclusive" in {
    val nodeApi = mock[NodeApi]
    val first = header("h-100", 100, "h-99")
    val second = header("h-101", 101, first.id)
    val third = header("h-102", 102, second.id)
    when(nodeApi.chainSlice(Some(99), Some(102)))
      .thenReturn(Success(Seq(third, first, second)))

    new CanonicalBlockSource(nodeApi).headerSlice(100, 102).get.map(_.id) shouldEqual
      Seq(first.id, second.id, third.id)
    verify(nodeApi).chainSlice(Some(99), Some(102))
  }

  it should "keep a single-height read on the same convention" in {
    val nodeApi = mock[NodeApi]
    val only = header("h-500", 500, "h-499")
    when(nodeApi.chainSlice(Some(499), Some(500))).thenReturn(Success(Seq(only)))

    new CanonicalBlockSource(nodeApi).headerAt(500).get.id shouldEqual only.id
  }

  // Ancestor search must include the oldest retained cursor.
  it should "include the oldest retained height in the ancestor search" in {
    val nodeApi = mock[NodeApi]
    val oldest = SyncCursor(100, "canonical-100", "parent-99")
    val ancestor = SyncCursor(101, "canonical-101", oldest.blockId)
    when(nodeApi.chainSlice(Some(99), Some(101))).thenReturn(Success(Seq(
      header(oldest.blockId, 100, oldest.parentId),
      header(ancestor.blockId, 101, oldest.blockId))))

    new CanonicalBlockSource(nodeApi).commonAncestor(Seq(oldest, ancestor), chainHeight = 101).get shouldEqual Some(ancestor)
  }

  // Reject discontinuous batches before any child block is applied.
  it should "reject a header batch that is not parent-linked" in {
    val nodeApi = mock[NodeApi]
    val first = header("h-100", 100, "h-99")
    val orphaned = header("h-101", 101, "some-other-parent")
    when(nodeApi.chainSlice(Some(99), Some(101))).thenReturn(Success(Seq(first, orphaned)))

    new CanonicalBlockSource(nodeApi).headerSlice(100, 101)
      .failed.get.getMessage should include("not parent-linked")
  }

  // A gap is also a short answer, so the count check reports it first and names both figures.
  it should "reject a header batch with a gap" in {
    val nodeApi = mock[NodeApi]
    val first = header("h-100", 100, "h-99")
    val skipped = header("h-102", 102, first.id)
    when(nodeApi.chainSlice(Some(99), Some(102))).thenReturn(Success(Seq(first, skipped)))

    new CanonicalBlockSource(nodeApi).headerSlice(100, 102)
      .failed.get.getMessage should include("returned 2 of 3 header(s)")
  }

  /** The one shape with the right count and the wrong positions, so the position check earns its place. */
  it should "reject a header batch that repeats a height" in {
    val nodeApi = mock[NodeApi]
    val first = header("h-100", 100, "h-99")
    val repeated = header("h-100-again", 100, "h-99")
    val last = header("h-102", 102, repeated.id)
    when(nodeApi.chainSlice(Some(99), Some(102))).thenReturn(Success(Seq(first, repeated, last)))

    new CanonicalBlockSource(nodeApi).headerSlice(100, 102)
      .failed.get.getMessage should include("not contiguous")
  }

  it should "select the newest retained cursor still on the canonical chain" in {
    val nodeApi = mock[NodeApi]
    val oldest = SyncCursor(100, "canonical-100", "parent-99")
    val ancestor = SyncCursor(101, "canonical-101", oldest.blockId)
    val orphan = SyncCursor(102, "orphan-102", ancestor.blockId)
    when(nodeApi.chainSlice(Some(99), Some(102))).thenReturn(Success(Seq(
      header(oldest.blockId, 100, oldest.parentId),
      header(ancestor.blockId, 101, oldest.blockId),
      header("canonical-102", 102, ancestor.blockId))))

    val result = new CanonicalBlockSource(nodeApi).commonAncestor(Seq(oldest, ancestor, orphan), chainHeight = 102).get

    result shouldEqual Some(ancestor)
    verify(nodeApi).chainSlice(Some(99), Some(102))
  }

  /** A block with one ordinary transaction whose input resolves, as the indexer reports one. */
  private def blockOf(header: NodeHeader): IndexedBlock = {
    val spent = IndexedBox(NodeBox(SyncFixtures.id(1), "", 1000000L, 0, header.height, "spent-tree"),
      "", header.height, 0L, Some(SyncFixtures.id(2)), Some(header.height), None)
    val created = IndexedBox(NodeBox(SyncFixtures.id(3), SyncFixtures.id(2), 1000000L, 0,
      header.height, "created-tree"), "", header.height, 0L, None, None, None)
    val tx = IndexedTransaction(SyncFixtures.id(2), Seq(spent), Seq.empty, Seq(created),
      header.height, 1, header.id, 0L, 0, 0L, 0)
    IndexedBlock(header, Seq(tx), NodeExtension(header.id, "", Seq.empty), None, 0)
  }

  private def header(id: String, height: Int, parentId: String): NodeHeader =
    NodeHeader(id, parentId, height, 0L, 1, 0L, BigInt(0), "", "", "", "",
      NodePowSolution("", "", "", ""), "")
}
