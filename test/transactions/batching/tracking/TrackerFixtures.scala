package transactions.batching.tracking

import node.NodeApi
import node.model._
import org.ergoplatform.appkit.NetworkType
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, when}
import transactions.batching.ergodex.ErgoDexContracts

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A mocked node whose chain, block transactions, index height and pool scans a spec scripts and changes as it
 * goes. Headers are parent-linked; `chainSlice` keeps the node's exclusive lower bound. Counters record every
 * scan page and block read, so a spec can say what an advance cost.
 */
final class ScriptedChain {

  val api: NodeApi = mock(classOf[NodeApi])

  @volatile private var chain: Map[Int, NodeHeader] = Map.empty
  private val txs = TrieMap.empty[String, Seq[NodeTransaction]]
  @volatile private var scanned: Seq[IndexedBox] = Seq.empty
  @volatile var indexed: Int = 0
  /** When set, every read fails with this message. */
  @volatile var failing: Option[String] = None
  /** When set, only the pool scans fail. */
  @volatile var scansFailing: Boolean = false

  val scanPages = new AtomicInteger(0)
  val blockReads = new AtomicInteger(0)
  val sliceReads = new AtomicInteger(0)

  def headerId(height: Int, branch: Int = 0): String = f"$branch%02x" + f"$height%062x"

  def header(height: Int): NodeHeader = chain(height)

  /** Makes `branch` canonical from `from` to `to`, linked to whatever is canonical below `from`. */
  def extend(from: Int, to: Int, branch: Int = 0): Unit = synchronized {
    chain = (from to to).foldLeft(chain) { (c: Map[Int, NodeHeader], h: Int) =>
      val parent = c.get(h - 1).map(_.id).getOrElse(headerId(h - 1, branch))
      c.updated(h, NodeHeader(headerId(h, branch), parent, h, 0L, 1, 0L, BigInt(0), "", "", "", "",
        NodePowSolution("", "", "", ""), ""))
    }.filter { case (h, _) => h <= to }
  }

  def block(height: Int, transactions: NodeTransaction*): Unit = txs.update(header(height).id, transactions)

  /** What the index scans return, every box at whichever script or template it sits at. */
  def scans(boxes: IndexedBox*): Unit = scanned = boxes

  def tip: Int = chain.keys.max

  private def fail[A](): Option[Failure[A]] = failing.map(m => Failure(new RuntimeException(m)))

  when(api.indexedHeight()).thenAnswer(_ => fail().getOrElse(Success(BlockchainIndexHeight(indexed, tip))))
  when(api.chainSlice(any[Option[Int]], any[Option[Int]])).thenAnswer { inv =>
    sliceReads.incrementAndGet()
    val from = inv.getArgument[Option[Int]](0).getOrElse(0)
    val to = inv.getArgument[Option[Int]](1).getOrElse(tip)
    fail().getOrElse(Success(chain.values.filter(h => h.height > from && h.height <= to).toSeq.sortBy(_.height)))
  }
  when(api.blockTransactions(anyString())).thenAnswer { inv =>
    blockReads.incrementAndGet()
    val id = inv.getArgument[String](0)
    fail().getOrElse(Success(Some(NodeBlockTransactions(id, txs.getOrElse(id, Seq.empty), 0))))
  }
  private def page(select: IndexedBox => Boolean, paging: Paging) = {
    scanPages.incrementAndGet()
    fail().orElse(if (scansFailing) Some(Failure(new RuntimeException("scan refused"))) else None)
      .getOrElse(Success(scanned.filter(select).slice(paging.offset, paging.offset + paging.limit)))
  }
  private val shapes = new PoolShapes(NetworkType.MAINNET)
  when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
    val tree = inv.getArgument[String](0)
    page(_.box.ergoTree == tree, inv.getArgument[Paging](1))
  }
  when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
    val hash = inv.getArgument[String](0)
    val kind: IndexedBox => Boolean = b => shapes.read(b.box, 0).exists(t => (t.deployment, hash) match {
      case (_: PoolDeployment.LithosDexNative, h) => h == shapes.n2tTemplateHash
      case (_: PoolDeployment.LithosDexToken, h) => h == shapes.t2tTemplateHash
      case _ => false
    })
    page(kind, inv.getArgument[Paging](1))
  }
}

object TrackerFixtures {

  val shapes = new PoolShapes(NetworkType.MAINNET)

  def tx(id: String, spends: Seq[String], creates: Seq[NodeBox]): NodeTransaction =
    NodeTransaction(id, spends.map(NodeInput(_, NodeSpendingProof.empty)), Seq.empty, creates)

  /** A box at no pool contract. */
  def plain(id: String): NodeBox = NodeBox(id, "ab" * 32, 1000000L, 0, 1, "0008cd" + "02" * 33)

  /** A tracker over `chain` that reads on the calling test's pool, never waiting on a dispatcher. */
  def tracker(chain: ScriptedChain, reseedBlocks: Int = PoolTracker.ReseedBlocks, retryMs: Long = 50L): PoolTracker =
    new PoolTracker(() => chain.api, NetworkType.MAINNET, ExecutionContext.global, reseedBlocks, retryMs)

  /** `tracker` followed to the tip of `chain` and waited for. */
  def warmed(chain: ScriptedChain, t: PoolTracker): TrackedPools = {
    t.follow(chain.tip, chain.header(chain.tip).id)
    t.at(chain.tip, 10.seconds).getOrElse(throw new IllegalStateException("the tracker did not reach the tip"))
  }

  /** The ErgoDEX native pool script, for specs that check a box shares it. */
  val NativeTree: String = ErgoDexContracts.NativePoolErgoTree
}
