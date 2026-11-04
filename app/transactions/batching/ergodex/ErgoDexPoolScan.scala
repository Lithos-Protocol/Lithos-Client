package transactions.batching.ergodex

import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import transactions.batching.Batcher

/**
 * The confirmed boxes at one ErgoDEX pool script, read a page at a time. Each page is parsed and dropped, so a
 * scan holds only what `keep` returns. Machina routing reads the native pools this way, and the arb stage both
 * shapes.
 */
object ErgoDexPoolScan {

  /** Most pages one scan reads, 2,000 pools; each script holds a few hundred. */
  final val MaxPages = 20

  /**
   * What `keep` makes of every confirmed box at `ergoTree`, newest first, to `maxPages` pages, and whether the
   * scan reached the last box. A failed read throws, so a caller keeps whatever it held before.
   */
  def confirmed[A](nodeApi: NodeApi, ergoTree: String, maxPages: Int = MaxPages)
                  (keep: IndexedBox => Option[A]): (Vector[A], Boolean) = {
    val found = Vector.newBuilder[A]
    var offset = 0
    var exhausted = false
    while (!exhausted && offset < maxPages * Batcher.PageSize) {
      val page = nodeApi.unspentBoxesByErgoTree(ergoTree, Paging(offset, Batcher.PageSize), SortDirection.Desc,
        MempoolOptions.ConfirmedOnly).get
      page.foreach(indexed => keep(indexed).foreach(found += _))
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    found.result() -> exhausted
  }
}
