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
 * Pools are distinct within a route, counted by the box that holds them so the two sides of the SigmaUSD bank are one,
 * and so are the assets it passes through, since a route that returns to an asset holds a shorter cycle. Shorter routes come first, so a cap keeps the cheapest to price and sign.
 */
object ArbRoutes {

  /** Fewest and most legs a route has. */
  final val MinLegs = 2
  final val MaxLegs = 4

  /** Hard bound on DFS edge inspections, so adversarial dense dead-end graphs cannot hang discovery. */
  final val MaxSteps = 200000

  /** One way through a pool: what goes in, what comes out, and the hop that does it. */
  private final case class Edge(in: ArbAsset, out: ArbAsset, hop: ArbHop)

  /**
   * The routes through `pools`, shortest first, at most `cap` of them, and whether the cap or step budget cut the
   * list short. Within a length, routes follow the order of `pools`, so the same pools give the same routes.
   */
  def enumerate(pools: Seq[ArbPool], cap: Int, maxSteps: Int = MaxSteps): (Vector[ArbRoute], Boolean) = {
    val edges: Map[ArbAsset, Vector[Edge]] = pools.iterator
      .flatMap(p => Iterator(Edge(p.x, p.y, ArbHop(p.nft, xIn = true)), Edge(p.y, p.x, ArbHop(p.nft, xIn = false))))
      .toVector.groupBy(_.in)
    // Backward BFS from ERG up to depth MaxLegs - 1 so DFS never enters edges that cannot return to ERG in time
    val distToErg = scala.collection.mutable.HashMap[ArbAsset, Int](ArbAsset.Erg -> 0)
    val queue = scala.collection.mutable.Queue[ArbAsset](ArbAsset.Erg)
    while (queue.nonEmpty) {
      val cur = queue.dequeue()
      val d = distToErg(cur)
      if (d < MaxLegs - 1) {
        edges.getOrElse(cur, Vector.empty).foreach { edge =>
          if (!distToErg.contains(edge.out)) {
            distToErg.put(edge.out, d + 1)
            queue.enqueue(edge.out)
          }
        }
      }
    }
    // Pre-filtered adjacency per remaining-hop budget (0..MaxLegs - 1), preserving pool order
    val edgesWithin: Array[Map[ArbAsset, Vector[Edge]]] = Array.tabulate(MaxLegs) { rem =>
      edges.map { case (asset, es) =>
        asset -> es.filter(e => distToErg.getOrElse(e.out, Int.MaxValue) <= rem)
      }.filter(_._2.nonEmpty)
    }
    val holderOf: Map[String, String] = pools.iterator.map(p => p.nft -> p.holder).toMap
    val found = Vector.newBuilder[ArbRoute]
    var count = 0
    var steps = 0
    var cut = false

    // Routes of exactly `legs` legs, depth first from ERG; stops once the cap or step budget is reached
    def extend(legs: Int, at: ArbAsset, path: Vector[ArbHop], usedPools: Set[String], seen: Set[ArbAsset]): Unit = {
      val rem = legs - (path.size + 1)
      edgesWithin(rem).getOrElse(at, Vector.empty).foreach { edge =>
        if (!cut) {
          steps += 1
          if (steps > maxSteps) cut = true
          else if (!usedPools.contains(holderOf(edge.hop.nft))) {
            val next = path :+ edge.hop
            if (rem == 0) {
              if (edge.out.isErg) {
                if (count >= cap) cut = true
                else { found += ArbRoute(next); count += 1 }
              }
            } else if (!edge.out.isErg && !seen.contains(edge.out))
              extend(legs, edge.out, next, usedPools + holderOf(edge.hop.nft), seen + edge.out)
          }
        }
      }
    }

    (MinLegs to MaxLegs).foreach(legs => if (!cut) extend(legs, ArbAsset.Erg, Vector.empty, Set.empty, Set(ArbAsset.Erg)))
    found.result() -> cut
  }
}
