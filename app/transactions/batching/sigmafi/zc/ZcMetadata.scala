package transactions.batching.sigmafi.zc

import sigmafi.zc.ZcMarket

/**
 * zcs1 token metadata: the EIP-4 name, description and decimals of bond units, shares and wrap receipts, built only
 * from chain data so every client writes the same bytes. Descriptions are compact JSON in a fixed key order. Display
 * only: nothing is ever verified from them.
 */
object ZcMetadata {

  final val Version = "1"
  final val ShareDecimals = "6"
  final val BondDecimals = "0"
  final val Verify = "Verify on chain."

  type Field = (String, String)

  /** What a bond's metadata is built from. `rule` is from [[ruleFields]]. */
  final case class BondFacts(market: ZcMarket, asset: String, principal: Long, repayment: Long, start: Long,
                             maturity: Long, rule: Seq[Field])

  /** A share auction's terms: opening and floor bps, length and expiry blocks, and the in-kind threshold. */
  final case class Auction(openingBps: Long, floorBps: Long, length: Long, expiry: Long, threshold: Long) {
    def selective: Boolean = threshold != Long.MaxValue
  }

  def decimals(m: ZcMarket): Int = if (m.tokenId.isEmpty) 9 else 2

  /** A raw amount in the market's unit: no trailing zeros, no trailing point, no separators. */
  def number(raw: Long, m: ZcMarket): String = plain(BigDecimal(raw) / BigDecimal(10).pow(decimals(m)))

  /** Basis points as a percentage: 9000 is "90", 9050 is "90.5". */
  def percent(bps: Long): String = plain(BigDecimal(bps) / 100)

  private def plain(d: BigDecimal): String = d.bigDecimal.stripTrailingZeros.toPlainString

  def id6(id: String): String = id.take(6).toLowerCase

  def str(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def json(fields: Seq[Field]): String = fields.map { case (k, v) => s"${str(k)}:$v" }.mkString("{", ",", "}")

  /** The fields naming a liquidation rule. */
  def ruleFields(rule: ZcRule, payload: Seq[Long], liqHash: String): Seq[Field] = rule match {
    case ZcRule.Maturity => Seq("rule" -> str("maturity"))
    case ZcRule.Oracle if payload.nonEmpty => Seq("rule" -> str("oracle"), "ratio" -> percent(payload.head))
    case _ => Seq("rule" -> str("other"), "ruleHash" -> str(liqHash.take(8)))
  }

  private def terms(f: BondFacts): Seq[Field] = Seq(
    "principal" -> number(f.principal, f.market), "repayment" -> number(f.repayment, f.market),
    "start" -> f.start.toString, "maturity" -> f.maturity.toString) ++ f.rule

  /** A bond unit's (name, description, decimals). */
  def bond(f: BondFacts): Seq[String] = Seq(
    s"ZCS-Bond-Unit-${id6(f.asset)}",
    json(Seq("zcs" -> Version, "type" -> str("bond unit"), "market" -> str(f.market.name)) ++ terms(f) ++
      Seq("note" -> str(Verify))),
    BondDecimals)

  /** Shares' (name, description, decimals), naming the bond only when the writer knows it. */
  def shares(m: ZcMarket, auction: Option[Auction], bond: Option[BondFacts]): Seq[String] = {
    val whole = plain(BigDecimal(ZcPricing.ShareCount) / BigDecimal(10).pow(ShareDecimals.toInt))
    val kind = auction.fold("shares")(a => if (a.selective) "selective shares" else "auction shares")
    val head = Seq("zcs" -> Version, "type" -> str(kind), "shares" -> whole)
    val what = bond.fold(Seq("market" -> str(m.name), "via" -> str("lend offer")))(b =>
      Seq("bond" -> str(id6(b.asset)), "market" -> str(m.name)) ++ terms(b))
    val sale = auction.toSeq.map(a => "auction" ->
      json(Seq("from" -> percent(a.openingBps), "to" -> percent(a.floorBps), "blocks" -> a.length.toString,
        "expiry" -> a.expiry.toString) ++ (if (a.selective) Seq("threshold" -> a.threshold.toString) else Seq.empty)))
    Seq("ZCS-Bond-Shares" + bond.fold("")(b => s"-${id6(b.asset)}"), json(head ++ what ++ sale ++ Seq("note" -> str(Verify))),
      ShareDecimals)
  }

  /** A wrap receipt's (name, description, decimals). */
  def receipt(wrapped: String, amount: Long): Seq[String] = Seq(
    s"ZCS-Wrap-${id6(wrapped)}",
    json(Seq("zcs" -> Version, "type" -> str("wrap receipt"), "token" -> str(wrapped), "amount" -> amount.toString,
      "note" -> str(Verify))),
    "0")
}
