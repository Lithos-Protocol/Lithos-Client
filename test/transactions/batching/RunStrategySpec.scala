package transactions.batching

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * The run strategies against a fee-less constant-product pool, where every case can be worked out by hand.
 * Each order is what it fills, and a fill must return at least the order's minimum.
 */
class RunStrategySpec extends AnyFlatSpec with Matchers {

  /** ERG and token reserves. */
  private final case class Pool(x: Long, y: Long)

  /** Sells `amount` ERG for tokens, or `amount` tokens for ERG, for at least `minOut`. */
  private final case class Order(id: String, sellsErg: Boolean, amount: Long, minOut: Long, fee: Long,
                                 placements: Seq[String] = Seq.empty)

  /** Least a run's first fill must earn to fund the takings box on its own. */
  private val Opening = 3L

  private def fill(order: Order, pool: Pool, first: Boolean): Option[Priced[Order, Pool]] = {
    val (in, out) = if (order.sellsErg) (pool.x, pool.y) else (pool.y, pool.x)
    val quote = out * order.amount / (in + order.amount)
    val after =
      if (order.sellsErg) Pool(pool.x + order.amount, pool.y - quote) else Pool(pool.x - quote, pool.y + order.amount)
    if (quote < order.minOut || (first && order.fee < Opening)) None else Some(Priced(order, order.fee, after))
  }

  private def problem(orders: Seq[Order], limit: Int = 20, carried: Set[String] = Set.empty,
                      until: Deadline = 10.seconds.fromNow): RunProblem[Order, Order, Pool] =
    RunProblem[Order, Order, Pool](orders, Pool(1000, 1000), limit, _.id, fill, _.placements, carried,
      pool => pool.y.toDouble / pool.x, until)

  private def planned(orders: Seq[Order], limit: Int = 20, carried: Set[String] = Set.empty,
                      until: Deadline = 10.seconds.fromNow): Seq[String] =
    MaxFees.plan(problem(orders, limit, carried, until)).map(_.order.id)

  // At the opening pool a 10 ERG sell returns 9 tokens; after the big sell below, 4
  private val big = Order("big", sellsErg = true, 500, 1, fee = 10)
  private val small = (1 to 3).map(i => Order(s"small$i", sellsErg = true, 10, 9, fee = 4))

  "maxFees" should "execute a big order after the small ones it would push under their minimums" in {
    // Big first earns 10 and prices the rest out; the small ones first still leave the big one within its own
    planned(big +: small) shouldBe Seq("small1", "small2", "small3", "big")
  }

  it should "take orders worth more together over one worth more alone" in {
    // 333 tokens at the opening pool, under 330 after any small fill, so it cannot share a run with them
    val exclusive = big.copy(minOut = 330)
    planned(exclusive +: small) shouldBe Seq("small1", "small2", "small3")
  }

  it should "come back to an order another fill brings within its minimum" in {
    // 9 tokens at the opening pool, under its 11; after the buy sells 100 tokens in, 11
    val buy = Order("buy", sellsErg = false, 100, 1, fee = 10)
    val sell = Order("sell", sellsErg = true, 10, 11, fee = 5)
    planned(Seq(sell, buy)) shouldBe Seq("buy", "sell")
  }

  it should "open with an order that can fund the takings box, and plan nothing when none can" in {
    val low = Order("low", sellsErg = true, 10, 1, fee = 2)
    val high = Order("high", sellsErg = true, 10, 1, fee = 10)
    planned(Seq(low, high)) shouldBe Seq("high", "low")
    planned(Seq(low)) shouldBe empty
  }

  it should "count a placement's slot once, and not at all when the block already carries it" in {
    val placedA = Order("placedA", sellsErg = true, 10, 1, fee = 10, placements = Seq("t1"))
    val placedB = Order("placedB", sellsErg = true, 10, 1, fee = 10, placements = Seq("t1"))
    val own = Order("own", sellsErg = true, 10, 1, fee = 11)
    // Three slots: both placed orders take 3 for 20, the own order and one placed order 3 for 21
    planned(Seq(placedA, placedB, own), limit = 3) shouldBe Seq("own", "placedA")
    planned(Seq(placedA, placedB, own), limit = 3, carried = Set("t1")) shouldBe Seq("own", "placedA", "placedB")
  }

  it should "stop at the slot limit" in {
    planned(small, limit = 2) should have size 2
    planned(small, limit = 0) shouldBe empty
  }

  it should "return the greedy plan when it has no time to search" in {
    planned(big +: small, until = Deadline.now - 1.second) shouldBe Seq("big")
  }

  "RunStrategy.searchUntil" should "stop at the configured budget when the build has time to spare" in {
    val until = RunStrategy.searchUntil(10.seconds.fromNow, 40.millis)
    until.timeLeft should be <= 40.millis
    until.timeLeft should be > 20.millis
  }

  it should "stop at a quarter of the build's time left when that is shorter than the budget" in {
    // The budget is a ceiling, never a reason to overrun the build: 400 ms left allows about 100 ms.
    RunStrategy.searchUntil(400.millis.fromNow, 10.seconds).timeLeft should be <= 100.millis
  }

  it should "not search at all with a budget of zero" in {
    RunStrategy.searchUntil(10.seconds.fromNow, Duration.Zero).isOverdue() shouldBe true
  }

  "RunStrategy.named" should "find maxFees, and nothing for an unknown name" in {
    RunStrategy.named(" maxFees ") shouldBe Some(MaxFees)
    RunStrategy.named("greedy") shouldBe None
    RunStrategy.named(configs.BatchingConfig.Default.strategy) shouldBe Some(RunStrategy.Default)
  }
}
