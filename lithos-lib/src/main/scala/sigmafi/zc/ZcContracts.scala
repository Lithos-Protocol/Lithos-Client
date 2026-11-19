package sigmafi.zc

import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.ErgoTree
import work.lithos.mutations.Contract

import java.nio.charset.StandardCharsets.UTF_8

/** A repayment asset. Every per-market contract compiles it in, so each market has its own tree of each one. */
sealed abstract class ZcMarket(val name: String, val tokenId: Option[String]) {
  override def toString: String = name
}

object ZcMarket {
  case object Erg extends ZcMarket("ERG", None)
  case object SigUsd extends ZcMarket("SigUSD", Some(ZcContracts.SigUsdId))

  /** The markets the pinned set compiles. The oracle rule and collateral policy support only these. */
  val all: Seq[ZcMarket] = Seq(Erg, SigUsd)

  def named(name: String): Option[ZcMarket] = all.find(_.name.equalsIgnoreCase(name))
}

/** A ZC box contract. `perMarket` ones have one tree per market; the rest have one tree for every market. */
sealed abstract class ZcKind(val name: String, val perMarket: Boolean) {
  override def toString: String = name
}

object ZcKind {
  case object BondVault extends ZcKind("BondVault", false)
  case object FractionalVault extends ZcKind("FractionalVault", false)
  case object FractionalMint extends ZcKind("FractionalMint", false)
  case object DutchAuctionVault extends ZcKind("DutchAuctionVault", false)
  case object WrapVault extends ZcKind("WrapVault", false)
  case object WrapMint extends ZcKind("WrapMint", false)
  case object Bond extends ZcKind("Bond", true)
  case object Order extends ZcKind("Order", true)
  case object PricedOrder extends ZcKind("PricedOrder", true)
  case object LendOffer extends ZcKind("LendOffer", true)
  case object SaleOrder extends ZcKind("SaleOrder", true)
  case object PricedAsk extends ZcKind("PricedAsk", true)
  case object DutchAuctionMint extends ZcKind("DutchAuctionMint", true)
  case object LotAuction extends ZcKind("LotAuction", true)

  val all: Seq[ZcKind] = Seq(BondVault, FractionalVault, FractionalMint, DutchAuctionVault, WrapVault, WrapMint,
    Bond, Order, PricedOrder, LendOffer, SaleOrder, PricedAsk, DutchAuctionMint, LotAuction)

  def named(name: String): Option[ZcKind] = all.find(_.name == name)
}

/**
 * An expression a host box runs from a context variable. A box commits to blake2b256 of its bytes, never to a
 * tree, so nothing is ever locked under one.
 */
sealed abstract class ZcExpression(val name: String, val perMarket: Boolean) {
  override def toString: String = name
}

object ZcExpression {
  case object MaturityLiquidation extends ZcExpression("MaturityLiquidation", false)
  case object OracleLiquidation extends ZcExpression("ERG_SigUSD_Liquidation", true)
  case object CollateralPolicy extends ZcExpression("ERG_SigUSD_CollateralPolicy", true)
  case object FillGuard extends ZcExpression("ERG_SigUSD_FillGuard", true)
  case object YieldOrderPricing extends ZcExpression("YieldOrder_Pricing", true)
  case object YieldAskPricing extends ZcExpression("YieldAsk_Pricing", true)
  case object GuardedYieldPricing extends ZcExpression("ERG_SigUSD_GuardedYield_Pricing", true)
  case object CurveOrderPricing extends ZcExpression("CurveOrder_Pricing", true)

  val all: Seq[ZcExpression] = Seq(MaturityLiquidation, OracleLiquidation, CollateralPolicy, FillGuard,
    YieldOrderPricing, YieldAskPricing, GuardedYieldPricing, CurveOrderPricing)

  def named(name: String): Option[ZcExpression] = all.find(_.name == name)
}

/** One pinned box contract: its kind, its market (None when shared) and its tree. */
final case class ZcScript(kind: ZcKind, market: Option[ZcMarket], contract: Contract) {
  val ergoTreeHex: String = contract.ergoTreeHex
  /** What another contract pins, and what a box's script-hash register holds for a script. */
  val treeHash: String = Hex.toHexString(Blake2b256.hash(contract.propBytes))
  /** The node index's template hash: every market of one kind shares it. */
  val templateHash: String = Hex.toHexString(Blake2b256.hash(contract.ergoTree.template))
  def label: String = market.fold(kind.name)(m => s"${kind.name} $m")
}

/** One pinned expression: `bytes` go in the spender's context variable, `hash` is what a host box commits to. */
final case class ZcExpr(expression: ZcExpression, market: Option[ZcMarket], bytes: Array[Byte]) {
  val hash: String = Hex.toHexString(Blake2b256.hash(bytes))
  def label: String = market.fold(expression.name)(m => s"${expression.name} $m")
}

/**
 * The SigmaFi ZC Standard, from trees pinned at Sigma-Finance-V2 [[PinnedCommit]] and never compiled here.
 * Terms are registers and markets are constants, so every box contract of one market is one exact tree, and a box
 * is recognised by an exact match of its whole tree. `ZcContractsSpec` holds every hash to the published reference.
 */
object ZcContracts {

  final val Resource = "sigmafi/zc/contracts.tsv"
  final val PinnedCommit = "cb8e8ad"

  /** The SigUSD token: the SigUSD market's repayment asset. */
  final val SigUsdId = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"

  /** The mainnet USD oracle pool v2 NFT, compiled into every oracle rule, policy and guard. */
  final val OracleNft = "6a2b821b5727e85beb5e78b4efb9f0250d59cd48481d2ded2c23e91ba1d07c66"

  private final case class Line(name: String, market: Option[ZcMarket], tree: ErgoTree)

  private def tree(hex: String): ErgoTree =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      ErgoTree.fromHex(hex)
    }

  private lazy val lines: Seq[Line] = {
    val in = Option(getClass.getClassLoader.getResourceAsStream(Resource))
      .getOrElse(throw new IllegalStateException(s"missing classpath resource $Resource"))
    val text = try new String(in.readAllBytes(), UTF_8) finally in.close()
    text.replace("\r\n", "\n").split("\n").toSeq.filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
      line.split("\t") match {
        case Array(name, market, hex) =>
          val m = if (market == "-") None
            else Some(ZcMarket.named(market).getOrElse(throw new IllegalStateException(s"unknown ZC market $market")))
          Line(name, m, tree(hex))
        case _ => throw new IllegalStateException(s"malformed ZC pin line: ${line.take(80)}")
      }
    }
  }

  /** Every pinned box contract: six shared, then eight per market. */
  lazy val scripts: Seq[ZcScript] = lines.flatMap(l => ZcKind.named(l.name).map(k => ZcScript(k, l.market, Contract(l.tree))))

  /** Every pinned expression: one shared, then seven per market. */
  lazy val expressions: Seq[ZcExpr] = lines.flatMap { l =>
    ZcExpression.named(l.name).map(e => ZcExpr(e, l.market, Contract(l.tree).valueBytes))
  }

  private lazy val byTree: Map[String, ZcScript] = {
    require(lines.forall(l => ZcKind.named(l.name).isDefined || ZcExpression.named(l.name).isDefined),
      "the ZC pins name a script this client does not know")
    val all = scripts.map(s => s.ergoTreeHex -> s)
    require(all.map(_._1).distinct.size == all.size, "two ZC pins share a tree")
    all.toMap
  }

  /** The tree lengths of every box contract, so most boxes are turned away before any hashing. */
  private lazy val lengths: Set[Int] = byTree.keySet.map(_.length)

  private lazy val byExpressionHash: Map[String, ZcExpr] = expressions.map(e => e.hash -> e).toMap

  /** The box contract `ergoTreeHex` is, when it is exactly one of the pinned trees. */
  def classify(ergoTreeHex: String): Option[ZcScript] =
    if (!lengths.contains(ergoTreeHex.length)) None
    else byTree.get(ergoTreeHex).orElse(byTree.get(ergoTreeHex.toLowerCase))

  def script(kind: ZcKind, market: Option[ZcMarket]): ZcScript =
    scripts.find(s => s.kind == kind && s.market == (if (kind.perMarket) market else None))
      .getOrElse(throw new NoSuchElementException(s"no pinned ZC $kind for ${market.getOrElse("all markets")}"))

  def expression(e: ZcExpression, market: Option[ZcMarket]): ZcExpr =
    expressions.find(x => x.expression == e && x.market == (if (e.perMarket) market else None))
      .getOrElse(throw new NoSuchElementException(s"no pinned ZC $e for ${market.getOrElse("all markets")}"))

  /** The pinned expression whose bytes hash to `hashHex`. */
  def expressionByHash(hashHex: String): Option[ZcExpr] = byExpressionHash.get(hashHex.toLowerCase)

  /** Each box contract's tree, for scans. */
  def trees: Seq[String] = scripts.map(_.ergoTreeHex)
}
