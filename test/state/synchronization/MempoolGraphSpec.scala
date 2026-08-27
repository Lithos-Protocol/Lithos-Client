package state.synchronization

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.{BlockTx, TxInput}
import support.SyncFixtures

class MempoolGraphSpec extends AnyFlatSpec with Matchers {

  "MempoolView.buildGraph" should "order a child-before-parent response by dependency" in {
    val root = SyncFixtures.id(1)
    val middle = SyncFixtures.id(2)
    val end = SyncFixtures.id(3)
    val parent = SyncFixtures.rollupTransform(1, 100, root, middle).tx
    val child = SyncFixtures.rollupTransform(2, 100, middle, end).tx

    val graph = MempoolView.buildGraph(Seq(child, parent))

    graph.blockedRoots shouldBe empty
    graph.chains(root).transforms.map(_.tx.id) shouldEqual Seq(parent.id, child.id)
  }

  it should "retain a chain beyond the former 100-transaction cap" in {
    val ids = (0 to 150).map(index => SyncFixtures.id(1000 + index))
    val transactions = (1 to 150).map { index =>
      SyncFixtures.rollupTransform(index, 100, ids(index - 1), ids(index)).tx
    }.reverse

    val graph = MempoolView.buildGraph(transactions)

    graph.chains(ids.head).transforms should have size 150
    graph.chains(ids.head).endId shouldEqual ids.last
  }

  it should "block a root with conflicting spends" in {
    val root = SyncFixtures.id(2000)
    val first = SyncFixtures.rollupTransform(1, 100, root, SyncFixtures.id(2001)).tx
    val replacement = SyncFixtures.rollupTransform(2, 100, root, SyncFixtures.id(2002)).tx

    val graph = MempoolView.buildGraph(Seq(first, replacement))

    graph.chains shouldBe empty
    graph.blockedRoots should contain(root)
  }

  it should "block a malformed spend instead of throwing" in {
    val root = SyncFixtures.id(3000)
    val malformed = BlockTx(SyncFixtures.id(3001), Seq(TxInput(root, None)), Seq.empty, Seq.empty)

    val graph = MempoolView.buildGraph(Seq(malformed))

    graph.chains shouldBe empty
    graph.blockedRoots should contain(root)
  }

  it should "publish the first healthy result and recovery even when the fingerprint is unchanged" in {
    val fingerprint = Set(SyncFixtures.id(4000))

    MempoolView.shouldPublish(Set.empty, healthy = false, Set.empty) shouldBe true
    MempoolView.shouldPublish(fingerprint, healthy = false, fingerprint) shouldBe true
    MempoolView.shouldPublish(fingerprint, healthy = true, fingerprint) shouldBe false
  }
}
