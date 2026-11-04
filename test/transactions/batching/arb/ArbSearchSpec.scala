package transactions.batching.arb

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import transactions.batching.ergodex.ErgoDexPool

import scala.concurrent.duration._

/**
 * Routes, pricing and best-first selection, on pool states alone. These pin which routes discovery offers,
 * that a cycle is priced exactly as its legs' pools price it, that each is sized at its most profitable input,
 * and that a cycle taken through a pool re-prices every other route through it.
 */
class ArbSearchSpec extends AnyFlatSpec with Matchers {
  import ArbFixtures.id

  private val LpHeld = Long.MaxValue - 1000000000000L

  private def native(nft: Int, token: Int, erg: Long, tokens: Long, fee: Int = 997): ArbPool =
    ErgoDexArbPool(ErgoDexPool(id(nft), id(nft + 1000), id(token), erg, tokens, LpHeld, fee, id(nft + 2000)))

  private def tokenPool(nft: Int, x: Int, y: Int, xAmount: Long, yAmount: Long, fee: Int = 997): ArbPool =
    ErgoDexArbPool(ErgoDexPool(id(nft), id(nft + 1000), id(y), xAmount, yAmount, LpHeld, fee, id(nft + 2000),
      xId = Some(id(x)), ergValue = 10000000L))

  private def states(pools: ArbPool*): Map[String, ArbPool] = pools.map(p => p.nft -> p).toMap

  private def route(hops: (ArbPool, Boolean)*): ArbRoute = ArbRoute(hops.map { case (p, xIn) => ArbHop(p.nft, xIn) }.toVector)

  private val erg = ArbFixtures.Erg

  // Token 1 is cheap on pool 1 and dear on pool 2; token 2 has one ERG pool and a token pool with token 1
  private val cheap = native(1, 101, 1000 * erg, 1000000000000L)
  private val dear = native(2, 101, 1100 * erg, 1000000000000L)
  private val other = native(3, 102, 500 * erg, 500000000000L)
  private val bridge = tokenPool(4, 101, 102, 1000000000000L, 1000000000000L)

  "Route discovery" should "offer every two- to four-leg route from ERG back to ERG through distinct pools, shortest first" in {
    val (routes, cut) = ArbRoutes.enumerate(Seq(cheap, dear, other, bridge), cap = 100)
    cut shouldBe false
    routes.map(_.hops.size) shouldBe Vector(2, 2, 3, 3, 3, 3)
    routes.take(2).map(_.pools).toSet shouldBe Set(Set(cheap.nft, dear.nft))
    routes.foreach { r =>
      r.pools.size shouldBe r.hops.size
      val assets = r.hops.scanLeft(ArbAsset.Erg) { (in, hop) =>
        val p = Seq(cheap, dear, other, bridge).find(_.nft == hop.nft).get
        if (hop.xIn) { in shouldBe p.x; p.y } else { in shouldBe p.y; p.x }
      }
      assets.last shouldBe ArbAsset.Erg
      assets.init.tail.distinct.size shouldBe assets.size - 2
    }
  }

  it should "find four-leg routes, and keep the shortest when the cap cuts it short" in {
    val third = native(5, 103, 500 * erg, 500000000000L)
    val bridge2 = tokenPool(6, 102, 103, 1000000000000L, 1000000000000L)
    val pools = Seq(cheap, other, bridge, bridge2, third)
    val (all, cut) = ArbRoutes.enumerate(pools, cap = 1000)
    cut shouldBe false
    all.count(_.hops.size == 4) shouldBe 2
    val (capped, cutNow) = ArbRoutes.enumerate(pools, cap = all.size - 1)
    cutNow shouldBe true
    capped shouldBe all.init
  }

  "Pricing" should "follow each leg's own pool exactly, every output feeding the next leg" in {
    val r = route(cheap -> true, bridge -> true, other -> false)
    val cycle = ArbSearch.run(r, states(cheap, dear, other, bridge), 5 * erg).get
    val t1 = cheap.quote(5 * erg, xIn = true)
    val t2 = bridge.quote(t1, xIn = true)
    cycle.swaps.map(_.amountOut) shouldBe Vector(t1, t2, other.quote(t2, xIn = false))
    cycle.profit shouldBe other.quote(t2, xIn = false) - 5 * erg
    ArbSearch.run(r, states(cheap, other), 5 * erg) shouldBe None
  }

  it should "never return more than the route's slope at zero times what went in" in {
    val r = route(cheap -> true, dear -> false)
    val rate = ArbSearch.marginal(r, states(cheap, dear))
    rate should be > 1.0
    Seq(1L, 1000L, erg, 10 * erg, 100 * erg).foreach { in =>
      ArbSearch.run(r, states(cheap, dear), in).fold(0L)(_.amountOut).toDouble should be <= in * rate
    }
    ArbSearch.marginal(route(dear -> true, cheap -> false), states(cheap, dear)) should be < 1.0
    ArbSearch.best(route(dear -> true, cheap -> false), states(cheap, dear), 10 * erg) shouldBe None
  }

  it should "size a cycle at its most profitable input" in {
    // Small pools, so a search over inputs is cheap; integer rounding leaves profit concave only to within a unit
    val small1 = native(11, 111, 100000000L, 1000000000000L)
    val small2 = native(12, 111, 150000000L, 1000000000000L)
    val r = route(small1 -> true, small2 -> false)
    val s = states(small1, small2)
    def profit(in: Long): Long = ArbSearch.run(r, s, in).fold(Long.MinValue)(_.profit)
    /** The most profit within `window` of the best input on a coarse grid up to `maxIn`. */
    def searched(maxIn: Long, step: Long, window: Long): Long = {
      val coarse = (1L to maxIn by step).maxBy(profit)
      (math.max(1L, coarse - window) to math.min(maxIn, coarse + window)).map(profit).max
    }
    val brute = searched(50000000L, 10000L, 20000L)
    brute should be > 0L
    ArbSearch.best(r, s, 50000000L).get.profit should be >= brute - 1
    // A cap below the optimum takes the most profitable input under it
    val capped = ArbSearch.best(r, s, 1000000L).get
    capped.amountIn should be <= 1000000L
    capped.profit should be >= searched(1000000L, 1000L, 20000L) - 1
  }

  "Selection" should "price every route through a pool again once a cycle has moved it" in {
    // Three pools of one token: any second pair shares a pool with the first, so it must be priced on what the first left
    val dear2 = native(7, 101, 1080 * erg, 1000000000000L)
    val all = states(cheap, dear, dear2)
    val routes = ArbRoutes.enumerate(all.values.toSeq, 100)._1
    val taken = ArbSearch.select(routes, all, 100 * erg, 1000000L, 4, Deadline.now + 1.minute)(_ => true)
    taken.size shouldBe 2
    taken.head.route.pools shouldBe Set(cheap.nft, dear.nft)
    taken(1).route.pools.intersect(taken.head.route.pools) should not be empty
    val left = all ++ taken.head.swaps.map(s => s.after.nft -> s.after)
    taken(1) shouldBe ArbSearch.best(taken(1).route, left, 100 * erg).get
    taken(1).profit should not be ArbSearch.best(taken(1).route, all, 100 * erg).get.profit
  }

  it should "take nothing under the least profit, and nothing once its deadline has passed" in {
    val all = states(cheap, dear)
    val routes = ArbRoutes.enumerate(all.values.toSeq, 100)._1
    val profit = ArbSearch.best(routes.find(_.hops.head.nft == cheap.nft).get, all, 10 * erg).get.profit
    ArbSearch.select(routes, all, 10 * erg, profit, 8, Deadline.now + 1.minute)(_ => true).size shouldBe 1
    ArbSearch.select(routes, all, 10 * erg, profit + 1, 8, Deadline.now + 1.minute)(_ => true) shouldBe empty
    ArbSearch.select(routes, all, 10 * erg, 1L, 8, Deadline.now - 1.second)(_ => true) shouldBe empty
  }

  it should "take only routes that fit the slots left" in {
    // The only paying route here has three legs
    val r = route(cheap -> true, bridge -> true, other -> false)
    val pricier = native(3, 102, 700 * erg, 500000000000L)
    val all = states(cheap, bridge, pricier)
    ArbSearch.best(r, all, 10 * erg).map(_.profit).getOrElse(0L) should be > 0L
    val routes = ArbRoutes.enumerate(all.values.toSeq, 100)._1
    ArbSearch.select(routes, all, 10 * erg, 1L, 2, Deadline.now + 1.minute)(_ => true) shouldBe empty
    ArbSearch.select(routes, all, 10 * erg, 1L, 3, Deadline.now + 1.minute)(_ => true).map(_.legs) shouldBe Vector(3)
  }

  it should "drop a cycle the builder refuses for the rest of the selection, and go on to the next" in {
    val dear2 = native(7, 101, 1080 * erg, 1000000000000L)
    val all = states(cheap, dear, dear2)
    val routes = ArbRoutes.enumerate(all.values.toSeq, 100)._1
    var offered = Vector.empty[ArbCycle]
    val taken = ArbSearch.select(routes, all, 10 * erg, 1000000L, 8, Deadline.now + 1.minute) { c =>
      offered :+= c
      c.route != offered.head.route
    }
    offered.head.route.pools shouldBe Set(cheap.nft, dear.nft)
    taken should not be empty
    // Refused, so the states stayed as they were: the next pair is priced on the untouched cheap pool
    taken.head shouldBe ArbSearch.best(taken.head.route, all, 10 * erg).get
    withClue("the refused route is not priced again after other cycles move its pools: ") {
      offered.count(_.route == offered.head.route) shouldBe 1
    }
  }
}
