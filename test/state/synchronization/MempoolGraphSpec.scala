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

    val graph = MempoolView.buildGraph(Seq(child, parent), Set(root))

    graph.blockedRoots shouldBe empty
    graph.chains(root).transforms.map(_.tx.id) shouldEqual Seq(parent.id, child.id)
  }

  it should "retain a chain beyond the former 100-transaction cap" in {
    val ids = (0 to 150).map(index => SyncFixtures.id(1000 + index))
    val transactions = (1 to 150).map { index =>
      SyncFixtures.rollupTransform(index, 100, ids(index - 1), ids(index)).tx
    }.reverse

    val graph = MempoolView.buildGraph(transactions, Set(ids.head))

    graph.chains(ids.head).transforms should have size 150
    graph.chains(ids.head).endId shouldEqual ids.last
  }

  it should "block a root with conflicting spends" in {
    val root = SyncFixtures.id(2000)
    val first = SyncFixtures.rollupTransform(1, 100, root, SyncFixtures.id(2001)).tx
    val replacement = SyncFixtures.rollupTransform(2, 100, root, SyncFixtures.id(2002)).tx

    val graph = MempoolView.buildGraph(Seq(first, replacement), Set(root))

    graph.chains shouldBe empty
    graph.blockedRoots should contain(root)
  }

  it should "block a malformed spend instead of throwing" in {
    val root = SyncFixtures.id(3000)
    val malformed = BlockTx(SyncFixtures.id(3001), Seq(TxInput(root, None)), Seq.empty, Seq.empty)

    val graph = MempoolView.buildGraph(Seq(malformed), Set(root))

    graph.chains shouldBe empty
    graph.blockedRoots should contain(root)
  }

  /** Anyone can pay a rollup script, so an untracked root must cost nothing to see. */
  it should "ignore a chain rooted anywhere this client does not track" in {
    val tracked = SyncFixtures.id(5000)
    val untracked = SyncFixtures.id(5100)
    val mine = SyncFixtures.rollupTransform(1, 100, tracked, SyncFixtures.id(5001)).tx
    val theirs = SyncFixtures.rollupTransform(2, 100, untracked, SyncFixtures.id(5101)).tx

    val graph = MempoolView.buildGraph(Seq(mine, theirs), Set(tracked))

    graph.chains.keySet shouldEqual Set(tracked)
    graph.blockedRoots shouldBe empty
  }

  it should "publish the first healthy result and recovery even when the fingerprint is unchanged" in {
    val fingerprint = Set(SyncFixtures.id(4000))

    MempoolView.shouldPublish(Set.empty, healthy = false, Set.empty) shouldBe true
    MempoolView.shouldPublish(fingerprint, healthy = false, fingerprint) shouldBe true
    MempoolView.shouldPublish(fingerprint, healthy = true, fingerprint) shouldBe false
  }
}
