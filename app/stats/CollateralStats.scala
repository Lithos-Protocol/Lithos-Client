package stats

final case class CollateralStats(status: String = "loading", observedAt: Option[Long] = None,
                                   source: Option[MiningCursor] = None, boxes: Int = 0,
                                   nanoErg: String = "0", partial: Boolean = true,
                                   error: Option[String] = None)
