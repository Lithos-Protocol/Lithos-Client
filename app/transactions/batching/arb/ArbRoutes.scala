package transactions.batching.arb

/** One leg of a route: through the pool `nft`, putting in its X when `xIn`, else its Y. */
final case class ArbHop(nft: String, xIn: Boolean)

/** Legs from ERG back to ERG through distinct pools, each leg's output the next one's input. */
final case class ArbRoute(hops: Vector[ArbHop]) {
  /** The pools this route spends, which a cycle taken through any of them re-prices. */
  val pools: Set[String] = hops.iterator.map(_.nft).toSet

  /** Identifies the route in logs and breaks ties between equal profits. */
  def key: String = hops.map(h => s"${h.nft.take(8)}${if (h.xIn) ">" else "<"}").mkString
}

/**
 * Every route of two to four legs from ERG back to ERG, enumerated when the set of pools changes so a build only
 * prices them.
 * Pools are distinct within a route, and so are the assets it passes through, since a route that returns to an
 * asset holds a shorter cycle. Shorter routes come first, so a cap keeps the cheapest to price and sign.
 */
object ArbRoutes {

  /** Fewest and most legs a route has. */
  final val MinLegs = 2
  final val MaxLegs = 4

  /** One way through a pool: what goes in, what comes out, and the hop that does it. */
  private final case class Edge(in: ArbAsset, out: ArbAsset, hop: ArbHop)

  /**
   * The routes through `pools`, shortest first, at most `cap` of them, and whether the cap cut the list short.
   * Within a length, routes follow the order of `pools`, so the same pools give the same routes.
   */
  def enumerate(pools: Seq[ArbPool], cap: Int): (Vector[ArbRoute], Boolean) = {
    val edges: Map[ArbAsset, Vector[Edge]] = pools.iterator
      .flatMap(p => Iterator(Edge(p.x, p.y, ArbHop(p.nft, xIn = true)), Edge(p.y, p.x, ArbHop(p.nft, xIn = false))))
      .toVector.groupBy(_.in)
    val found = Vector.newBuilder[ArbRoute]
    var count = 0
    var cut = false

    // Routes of exactly `legs` legs, depth first from ERG; stops once the cap is reached
    def extend(legs: Int, at: ArbAsset, path: Vector[ArbHop], usedPools: Set[String], seen: Set[ArbAsset]): Unit =
      edges.getOrElse(at, Vector.empty).foreach { edge =>
        if (!cut && !usedPools.contains(edge.hop.nft)) {
          val next = path :+ edge.hop
          if (next.size == legs) {
            if (edge.out.isErg) {
              if (count >= cap) cut = true
              else { found += ArbRoute(next); count += 1 }
            }
          } else if (!edge.out.isErg && !seen.contains(edge.out))
            extend(legs, edge.out, next, usedPools + edge.hop.nft, seen + edge.out)
        }
      }

    (MinLegs to MaxLegs).foreach(legs => if (!cut) extend(legs, ArbAsset.Erg, Vector.empty, Set.empty, Set(ArbAsset.Erg)))
    found.result() -> cut
  }
}
