package transactions.batching.arb

import scala.concurrent.duration.Deadline

/** A route priced at one input: each leg's swap in order, the first taking ERG in, the last paying ERG out. */
final case class ArbCycle(route: ArbRoute, swaps: Vector[ArbSwap]) {
  def amountIn: Long = swaps.head.amountIn
  def amountOut: Long = swaps.last.amountOut
  def profit: Long = amountOut - amountIn
  def legs: Int = swaps.size
}

/**
 * Prices routes in exact integers through each pool's own formula, sizes each by ternary search, and takes
 * cycles best first. Pricing reads pool states only; nothing here touches a box or the node.
 */
object ArbSearch {

  /** The cycle `amountIn` of ERG makes along `route` through `states`, or None when a pool cannot fill its leg. */
  def run(route: ArbRoute, states: Map[String, ArbPool], amountIn: Long): Option[ArbCycle] =
    if (amountIn <= 0L) None
    else {
      var amount = amountIn
      val swaps = Vector.newBuilder[ArbSwap]
      val filled = route.hops.forall { hop =>
        states.get(hop.nft).flatMap(_.swap(amount, hop.xIn)) match {
          case Some(swap) => swaps += swap; amount = swap.amountOut; true
          case None => false
        }
      }
      if (filled) Some(ArbCycle(route, swaps.result())) else None
    }

  /**
   * ERG out per ERG in for a vanishing input: the product of each leg's slope at zero. At or under 1 no input
   * profits, since every pool's curve is concave and rounding only lowers what it returns. Zero when a pool
   * is missing.
   */
  def marginal(route: ArbRoute, states: Map[String, ArbPool]): Double =
    route.hops.foldLeft(1.0)((rate, hop) => states.get(hop.nft).fold(0.0)(p => rate * p.marginal(hop.xIn)))

  /**
   * The most profitable input between 1 and `maxIn`, with the cycle it makes, or None when no input profits.
   * Ternary search over profit, which is concave but for integer rounding, then the last few inputs tried
   * one by one. Skipped when even the slope at zero could not earn `minProfit` on `maxIn`, since no input
   * profits more than that.
   */
  def best(route: ArbRoute, states: Map[String, ArbPool], maxIn: Long, minProfit: Long = 1L): Option[ArbCycle] =
    if (maxIn < 1L || (marginal(route, states) - 1.0) * maxIn < minProfit) None
    else {
      def profitAt(amount: Long): Long = run(route, states, amount).fold(Long.MinValue)(_.profit)
      var lo = 1L
      var hi = maxIn
      while (hi - lo > 2L) {
        val third = (hi - lo) / 3L
        val (m1, m2) = (lo + third, hi - third)
        if (profitAt(m1) < profitAt(m2)) lo = m1 + 1L else hi = m2
      }
      (lo to hi).iterator.flatMap(run(route, states, _)).filter(_.profit > 0L)
        .foldLeft(Option.empty[ArbCycle]) { (kept, c) =>
          if (kept.forall(k => c.profit > k.profit)) Some(c) else kept
        }
    }

  /**
   * Cycles taken best first: the most profitable route of at most the legs left, offered to `take`; once taken,
   * the pools it moved change state and every route through one of them is priced again. `take` builds the
   * cycle and says whether it did; a route it refuses is dropped for the rest of the selection and the states
   * stay as they were. Stops below `minProfit`, when no route fits in `slots`, or at `until`. Ties go to fewer
   * legs, then the route key.
   *
   * @param maxIn the most ERG one cycle may put in
   */
  def select(routes: Seq[ArbRoute], initial: Map[String, ArbPool], maxIn: Long, minProfit: Long, slots: Int,
             until: Deadline)(take: ArbCycle => Boolean): Vector[ArbCycle] = {
    var states = initial
    var left = slots
    var taken = Vector.empty[ArbCycle]
    var refused = Set.empty[ArbRoute]
    val eligible = routes.filter(r => r.hops.size <= slots && r.pools.forall(initial.contains))
    var priced: Map[ArbRoute, ArbCycle] = Map.empty
    def price(route: ArbRoute): Unit =
      if (!until.isOverdue()) best(route, states, maxIn, minProfit).filter(_.profit >= minProfit)
        .foreach(c => priced += route -> c)
    eligible.foreach(price)
    val order = Ordering.by[ArbCycle, (Long, Int, String)](c => (-c.profit, c.legs, c.route.key))
    var going = true
    while (going && !until.isOverdue()) {
      val fitting = priced.valuesIterator.filter(_.legs <= left)
      if (!fitting.hasNext) going = false
      else {
        val top = fitting.min(order)
        if (take(top)) {
          taken :+= top
          left -= top.legs
          states ++= top.swaps.iterator.map(s => s.after.nft -> s.after)
          val moved = top.route.pools
          priced = priced.filterNot { case (route, _) => route.pools.exists(moved.contains) }
          eligible.iterator
            .filter(r => r.hops.size <= left && r.pools.exists(moved.contains) && !refused.contains(r))
            .foreach(price)
        } else {
          priced -= top.route
          refused += top.route
        }
      }
    }
    taken
  }
}
