package transactions.batching

import scala.concurrent.duration._

/**
 * One order priced against one pool state.
 *
 * @param fill  what building the order there would produce
 * @param value what this miner keeps: the executor fee, less any miner fee a broadcast pays out of it
 * @param after the pool the fill leaves
 */
final case class Priced[F, S](fill: F, value: Long, after: S)

/** One step of a plan: the order, its fill, and the placements it adds to the run. */
final case class Planned[O, F, S](order: O, priced: Priced[F, S], placements: Seq[String])

/**
 * What a strategy plans one run against one pool from.
 *
 * @param orders         what the run may execute
 * @param opening        the pool the run's first fill spends
 * @param limit          transactions the run may add, placements included
 * @param id             an order's box id
 * @param price          `order` against `state`, or None when it cannot fill there. The flag is true for a
 *                       run's first fill, which has to fund the takings box on its own
 * @param placements     ids of the unconfirmed transactions an order needs carried ahead of it
 * @param alreadyCarried placements the block already carries, which cost the run no slot
 * @param spot           a pool state's price, used to measure how far a fill moves it
 * @param until          when planning stops and the best plan found so far is returned
 */
final case class RunProblem[O, F, S](orders: Seq[O], opening: S, limit: Int, id: O => String,
                                     price: (O, S, Boolean) => Option[Priced[F, S]],
                                     placements: O => Seq[String], alreadyCarried: Set[String],
                                     spot: S => Double, until: Deadline)

/** Chooses which of a pool's orders one run executes, and in what order. Plans only; nothing is signed. */
trait RunStrategy {

  /** The value of `batching.<adapter>.strategy` that selects this strategy. */
  def name: String

  /** The steps in execution order, each priced against the pool the one before it leaves. */
  def plan[O, F, S](problem: RunProblem[O, F, S]): Vector[Planned[O, F, S]]
}

object RunStrategy {

  val Default: RunStrategy = MaxFees

  val all: Seq[RunStrategy] = Seq(MaxFees)

  /** The strategy `name` selects, if any does. */
  def named(name: String): Option[RunStrategy] = all.find(_.name == name.trim)

  /** Most time one plan may search, so a deep order book cannot hold up a build. */
  final val SearchBudget: FiniteDuration = 250.millis

  /** When a plan made now stops searching: after [[SearchBudget]], or a quarter of what `deadline` leaves. */
  def searchUntil(deadline: Deadline): Deadline =
    Deadline.now + (deadline.timeLeft / 4).max(Duration.Zero).min(SearchBudget)
}

/**
 * The run earning the most this miner keeps, found by local search before the problem's deadline.
 *
 * Each fill moves the pool, so one order can put another under its own minimum or bring it back within it.
 * The search starts from a greedy plan that re-prices every remaining order after each fill, then tries
 * dropping each planned order and inserting each unplanned one at every position, keeping any change that
 * earns strictly more. Among fills worth the same it takes the one leaving the price nearest where the run
 * opened, which keeps more of the other orders within their minimums.
 */
object MaxFees extends RunStrategy {

  override val name: String = "maxFees"

  /** Most orders one plan considers, those worth most at the opening pool first. Bounds the search. */
  final val MaxConsidered = 64

  override def plan[O, F, S](problem: RunProblem[O, F, S]): Vector[Planned[O, F, S]] =
    if (problem.limit <= 0 || problem.orders.isEmpty) Vector.empty
    else new Search(problem).result

  private final class Search[O, F, S](p: RunProblem[O, F, S]) {

    private val openingSpot = p.spot(p.opening)

    /** How far `state` has moved the price from the opening pool, as a log ratio. Unmeasurable counts as far. */
    private def distance(state: S): Double = {
      val ratio = p.spot(state) / openingSpot
      if (ratio > 0 && !ratio.isInfinite) math.abs(math.log(ratio)) else Double.MaxValue
    }

    // An order that does not price at the opening pool may still price after another fill moves it
    private val considered: Vector[O] =
      p.orders.map(order => order -> p.price(order, p.opening, false).map(_.value))
        .sortBy { case (order, value) => (value.isEmpty, -value.getOrElse(0L), p.id(order)) }
        .take(MaxConsidered).map(_._1).toVector

    /** A plan so far: its steps, the pool they leave, what they carry, and the slots they use. */
    private final case class Plan(steps: Vector[Planned[O, F, S]], state: S, carried: Set[String], slots: Int) {
      val value: Long = steps.map(_.priced.value).sum
      lazy val ids: Set[String] = steps.map(step => p.id(step.order)).toSet

      /** This plan with `order` last, when it prices against this plan's pool and its placements fit. */
      def append(order: O): Option[Plan] = {
        val added = p.placements(order).distinct.filterNot(carried)
        if (slots + 1 + added.size > p.limit) None
        else p.price(order, state, steps.isEmpty).map(priced =>
          Plan(steps :+ Planned(order, priced, added), priced.after, carried ++ added, slots + 1 + added.size))
      }
    }

    private val empty = Plan(Vector.empty, p.opening, p.alreadyCarried, 0)

    /** `plan` extended with the best remaining order, one fill at a time, until none fits. */
    private def extend(plan: Plan, excluded: Set[String]): Plan = {
      var current = plan
      var open = considered.filterNot(order => excluded(p.id(order)) || plan.ids(p.id(order)))
      var extending = true
      while (extending) {
        val options = open.flatMap(order => current.append(order).map(order -> _))
        if (options.isEmpty) extending = false
        else {
          val (chosen, next) = options.minBy { case (order, next) =>
            val step = next.steps.last
            (-step.priced.value, step.placements.size, distance(next.state), p.id(order))
          }
          current = next
          open = open.filterNot(order => p.id(order) == p.id(chosen))
        }
      }
      current
    }

    /** `orders` in this order, each priced against the pool the one before leaves, if every one fills. */
    private def sequence(orders: Seq[O]): Option[Plan] =
      orders.foldLeft(Option(empty))((plan, order) => plan.flatMap(_.append(order)))

    val result: Vector[Planned[O, F, S]] = {
      var best = extend(empty, Set.empty)
      var excluded = Set.empty[String]
      var improved = true
      def timeLeft: Boolean = !p.until.isOverdue()

      // Every accepted change earns strictly more, so the search ends even with time to spare
      while (improved && timeLeft) {
        improved = false

        val drops = best.steps.map(step => p.id(step.order)).iterator
        while (!improved && drops.hasNext && timeLeft) {
          val without = excluded + drops.next()
          val candidate = extend(empty, without)
          if (candidate.value > best.value) {
            best = candidate
            excluded = without
            improved = true
          }
        }

        val unplanned = considered.filterNot(order => best.ids(p.id(order))).iterator
        while (!improved && unplanned.hasNext && timeLeft) {
          val order = unplanned.next()
          val planned = best.steps.map(_.order)
          val positions = (0 to planned.size).iterator
          while (!improved && positions.hasNext && timeLeft) {
            sequence(planned.patch(positions.next(), Seq(order), 0)).map(extend(_, excluded)).foreach { candidate =>
              if (candidate.value > best.value) {
                best = candidate
                improved = true
              }
            }
          }
        }
      }
      best.steps
    }
  }
}
