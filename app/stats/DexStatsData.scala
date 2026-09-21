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
                              error: Option[String] = None)
object DexStatsView {
  implicit val writes: OWrites[DexStatsView] = Json.writes[DexStatsView]
}

/** A bounded, freshly read chain suffix. It is replaced as a whole, including after a reorg. */
final case class DexStatsData(height: Int, blockId: String, fromHeight: Int,
                              history: Vector[PoolSnapshot], complete: Boolean,
                              current: LDPricePoint, decimals: Int, timestamps: Map[Int, Long],
                              prices: Map[String, LDPriceHistory], defaultFees: LDFeeHistory,
                              recent: LDRecentActivity) {
  def price(range: Option[String], bucket: Option[Int]): LDPriceHistory = {
    val (name, blocks, _) = DexHistory.priceRange(range, bucket)
    if (bucket.isEmpty) prices(name)
    else DexHistory.price(history, complete || history.headOption.exists(_.height < height - blocks),
      current, decimals, Some(name), bucket, _ => timestamps)
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

  def activity(limit: Option[Int]): LDRecentActivity = {
    val count = limit.getOrElse(LDRecentActivity.DefaultLimit)
    if (count <= 0) throw LithosBadRequest("'limit' must be positive")
    LDRecentActivity(recent.activity.take(math.min(count, LDRecentActivity.MaxLimit)))
  }
}

final case class DexStatsSnapshot(view: DexStatsView, data: Option[DexStatsData])
