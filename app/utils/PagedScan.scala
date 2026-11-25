package utils

import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}

import scala.util.Try

/**
 * One loop for reading the node's index a page at a time. Each page is handed to `keep` and dropped, so a scan
 * holds only what `keep` retains; the scan stops at a short page or at `maxBoxes`, and says which. A failed read
 * throws, so a caller keeps whatever it held before.
 */
object PagedScan {

  /** Boxes per index page, for every scan. The node serves up to 16,384. */
  final val PageSize = 200

  /**
   * Feeds every item `read` returns to `keep`, `PageSize` at a time, until a page comes back short or `maxBoxes`
   * have been read. True when it reached the last item, false when the cap stopped it.
   */
  def apply[A](maxBoxes: Int)(read: Paging => Try[Seq[A]])(keep: A => Unit): Boolean = {
    var offset = 0
    var exhausted = false
    while (!exhausted && offset < maxBoxes) {
      val page = read(Paging(offset, PageSize)).get
      require(page.size <= PageSize, "the node returned more boxes than the page asked for")
      page.foreach(keep)
      exhausted = page.size < PageSize
      offset += page.size
    }
    exhausted
  }

  /** What `parse` makes of every box `read` returns, to `maxBoxes`, and whether the scan reached the last box. */
  def collect[A](maxBoxes: Int)(read: Paging => Try[Seq[IndexedBox]])(parse: IndexedBox => Option[A]): (Vector[A], Boolean) = {
    val found = Vector.newBuilder[A]
    val exhausted = apply(maxBoxes)(read)((indexed: IndexedBox) => parse(indexed).foreach(found += _))
    found.result() -> exhausted
  }

  /** Unspent boxes at `ergoTree`, a page at a time. */
  def byTree(api: NodeApi, ergoTree: String, sort: SortDirection = SortDirection.Desc,
             mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Paging => Try[Seq[IndexedBox]] =
    paging => api.unspentBoxesByErgoTree(ergoTree, paging, sort, mempool)

  /** Unspent boxes whose tree has the template `templateHash`, a page at a time. */
  def byTemplate(api: NodeApi, templateHash: String, sort: SortDirection = SortDirection.Desc,
                 mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Paging => Try[Seq[IndexedBox]] =
    paging => api.unspentBoxesByTemplateHash(templateHash, paging, sort, mempool)
}
