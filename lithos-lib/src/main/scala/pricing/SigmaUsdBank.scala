package pricing

import scala.util.Try

/**
 * The SigmaUSD bank's state and its exchange rule, in the contract's own integer maths: Long arithmetic that fails on
 * overflow, division truncating toward zero.
 *
 * A change of `delta` coins in circulation (positive mints, negative redeems) moves the reserve by the coin's nominal
 * price times `delta`, plus 2% of that taken as a positive amount, so the fee raises what a mint pays and lowers what
 * a redeem returns. SigUSD's price is the oracle rate unless the reserve cannot cover every SigUSD at it; SigRSV's is
 * the equity left over per SigRSV. The reserve ratio after the exchange decides what is open: a SigUSD mint and a
 * SigRSV redeem need at least 400%, a SigRSV mint at most 800%, a SigUSD redeem nothing.
 *
 * @param reserve  nanoERG in the bank box
 * @param scCirc   SigUSD in circulation (R4), in cents
 * @param rcCirc   SigRSV in circulation (R5)
 * @param scTokens SigUSD the box holds
 * @param rcTokens SigRSV the box holds
 * @param rate     nanoERG per SigUSD cent: the oracle's nanoERG per USD over 100
 */
final case class SigmaUsdBank(reserve: Long, scCirc: Long, rcCirc: Long, scTokens: Long, rcTokens: Long, rate: Long) {
  import SigmaUsdBank._

  /** What the bank owes SigUSD holders, at most its whole reserve. */
  def liabilities: Long = math.max(math.min(reserve, Math.multiplyExact(scCirc, rate)), 0L)

  /** nanoERG per SigUSD cent the bank mints and redeems at, before its fee. */
  def scPrice: Long = math.min(rate, if (scCirc == 0L) Long.MaxValue else liabilities / scCirc)

  /** nanoERG per SigRSV the bank mints and redeems at, before its fee. */
  def rcPrice: Long = {
    val equity = reserve - liabilities
    if (equity == 0L || rcCirc == 0L) RcDefaultPrice else equity / rcCirc
  }

  /** The reserve ratio in percent, as the contract computes it on a bank's state. */
  def ratioPercent: Long = {
    val needed = Math.multiplyExact(scCirc, rate)
    if (needed == 0L) MaxRatioPercent else Math.multiplyExact(reserve, 100L) / needed
  }

  /**
   * The exchange of `delta` coins (SigUSD when `sc`, else SigRSV): the reserve's change and the state it leaves, or
   * None when the contract refuses it. Refused: no change, more coins out than the box holds less one (a box cannot
   * hold none of a token it keeps), more redeemed than circulate, a reserve under the storage floor, a ratio the
   * operation may not leave, or any Long overflow.
   */
  def exchange(sc: Boolean, delta: Long): Option[Exchange] = Try {
    require(delta != 0L)
    val held = if (sc) scTokens else rcTokens
    val circ = if (sc) scCirc else rcCirc
    val tokensOut = Math.subtractExact(held, delta)
    val circOut = Math.addExact(circ, delta)
    require(tokensOut >= 1L && circOut >= 0L)
    val expected = Math.multiplyExact(if (sc) scPrice else rcPrice, delta)
    val fee = Math.multiplyExact(expected, FeePercent) / 100L
    val reserveDelta = Math.addExact(expected, math.abs(fee))
    val after = if (sc) copy(reserve = Math.addExact(reserve, reserveDelta), scCirc = circOut, scTokens = tokensOut)
      else copy(reserve = Math.addExact(reserve, reserveDelta), rcCirc = circOut, rcTokens = tokensOut)
    require(after.reserve >= MinStorageRent)
    val ratio = after.ratioPercent
    val open =
      if (sc) delta < 0L || ratio >= MinRatioPercent
      else if (delta > 0L) ratio <= MaxRatioPercent
      else ratio >= MinRatioPercent
    require(open)
    Exchange(delta, reserveDelta, after)
  }.toOption

  /**
   * The mint of the most coins `ergIn` pays for, and what it costs, which may be less than `ergIn`. None when not
   * even one coin can be minted for it.
   */
  def mintFor(sc: Boolean, ergIn: Long): Option[Exchange] =
    if (ergIn <= 0L) None
    else Try {
      val price = if (sc) scPrice else rcPrice
      require(price > 0L)
      // Cost rises with the coins minted, and every refusal (tokens held, the ratio) holds from some count on, so the
      // counts that pass form a prefix; n coins cost at least p·n, which bounds the search
      def ok(k: Long): Boolean = exchange(sc, k).exists(_.reserveDelta <= ergIn)
      var lo = 0L
      var hi = math.min(ergIn / price, (if (sc) scTokens else rcTokens) - 1L) + 1L
      while (hi - lo > 1L) {
        val mid = lo + (hi - lo) / 2L
        if (ok(mid)) lo = mid else hi = mid
      }
      lo
    }.toOption.filter(_ > 0L).flatMap(exchange(sc, _)).filter(_.reserveDelta <= ergIn)

  /** The redeem of `coins` and the nanoERG it pays, or None when the contract refuses it. */
  def redeem(sc: Boolean, coins: Long): Option[Exchange] =
    if (coins <= 0L) None else exchange(sc, -coins).filter(_.reserveDelta < 0L)

  /**
   * The least ERG that mints exactly `coins`, or None when that mint is refused. A mint costs exactly what this
   * returns, since the contract fixes the reserve's change.
   */
  def mintOf(sc: Boolean, coins: Long): Option[Exchange] =
    if (coins <= 0L) None else exchange(sc, coins).filter(_.reserveDelta > 0L)
}

object SigmaUsdBank {

  /** The bank's fee, in percent of the nominal amount. */
  final val FeePercent: Long = 2L

  /** The least nanoERG the bank box keeps. */
  final val MinStorageRent: Long = 10000000L

  /** nanoERG per SigRSV when there is no equity or no SigRSV in circulation. */
  final val RcDefaultPrice: Long = 1000000L

  final val MinRatioPercent: Long = 400L

  /** The ratio ceiling a SigRSV mint must stay at or under; it has applied since the bank's cooling-off height. */
  final val MaxRatioPercent: Long = 800L

  /**
   * One exchange: the coins' change in circulation (positive mints), the reserve's change, which a receipt output's
   * R4 and R5 carry, and the bank after it.
   */
  final case class Exchange(circDelta: Long, reserveDelta: Long, after: SigmaUsdBank)

  /** nanoERG per SigUSD cent from an oracle datapoint in nanoERG per USD, as the contract divides it. */
  def rateOf(datapoint: Long): Long = datapoint / 100L
}
