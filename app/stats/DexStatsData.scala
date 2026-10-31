package stats

import api.DexHistory
import api.LithosApiErrors.LithosBadRequest
import api.models._
import play.api.libs.json.{Json, OWrites}
import transactions.batching.lithosdex.LDBoxes.PoolSnapshot

final case class DexStatsView(status: String = "loading", updatedAt: Option[Long] = None,
                              sourceHeight: Option[Int] = None, sourceBlockId: Option[String] = None,
                              windowStartHeight: Option[Int] = None, coverageStartHeight: Option[Int] = None,
                              historyComplete: Option[Boolean] = None,
                              refreshing: Boolean = false, restored: Boolean = false,
                              error: Option[String] = None, collectedFrom: Option[Int] = None)
object DexStatsView {
  implicit val writes: OWrites[DexStatsView] = Json.writes[DexStatsView]
}

/**
 * A bounded, freshly read chain suffix. It is replaced as a whole, including after a reorg.
 *
 * @param decimalsX     X's display decimals: 9 for ERG, a token/token pool's X otherwise
 * @param collectedFrom the height a verified pool joined the list; nothing earlier was read, so graphs report the
 *                      range before it as not collected. None when the pool's whole life is read
 */
final case class DexStatsData(height: Int, blockId: String, fromHeight: Int,
                              history: Vector[PoolSnapshot], complete: Boolean,
                              current: LDPricePoint, decimals: Int, timestamps: Map[Int, Long],
                              prices: Map[String, LDPriceHistory], defaultFees: LDFeeHistory,
                              recent: LDRecentActivity, decimalsX: Int = 9, collectedFrom: Option[Int] = None) {
  def price(range: Option[String], bucket: Option[Int]): LDPriceHistory = {
    val (name, blocks, _) = DexHistory.priceRange(range, bucket)
    if (bucket.isEmpty) prices(name)
    else DexHistory.price(history, complete || history.headOption.exists(_.height < height - blocks),
      current, decimals, Some(name), bucket, _ => timestamps, decimalsX)
  }

  def fees(from: Option[Int], to: Option[Int], bucket: Option[Int]): LDFeeHistory = {
    val lo = from.getOrElse(fromHeight)
    val hi = to.getOrElse(height)
    if (lo < fromHeight || hi > height)
      throw LithosBadRequest(s"cached fee history supports heights $fromHeight through $height")
    if (from.isEmpty && to.isEmpty && bucket.isEmpty) defaultFees
    else DexHistory.fees(history, complete || history.headOption.exists(_.height < lo),
      lo, hi, bucket.getOrElse(720), _ => timestamps)
  }

  /** One provision's fees over the collected window, from its size and entry as they stand now. */
  def provisionFees(boxId: String, shares: Long, entryX: BigInt, entryY: BigInt, from: Option[Int], to: Option[Int],
                    bucket: Option[Int]): LDProvisionFeeHistory = {
    val lo = from.getOrElse(fromHeight)
    val hi = to.getOrElse(height)
    if (lo < fromHeight || hi > height)
      throw LithosBadRequest(s"cached fee history supports heights $fromHeight through $height")
    LDProvisionFeeHistory(boxId, DexHistory.provisionFees(history, lo, hi, bucket.getOrElse(720), shares, entryX, entryY,
      timestamps), partial = !(complete || history.headOption.exists(_.height < lo)))
  }

  def activity(limit: Option[Int]): LDRecentActivity = {
    val count = limit.getOrElse(LDRecentActivity.DefaultLimit)
    if (count <= 0) throw LithosBadRequest("'limit' must be positive")
    LDRecentActivity(recent.activity.take(math.min(count, LDRecentActivity.MaxLimit)))
  }
}

final case class DexStatsSnapshot(view: DexStatsView, data: Option[DexStatsData])
