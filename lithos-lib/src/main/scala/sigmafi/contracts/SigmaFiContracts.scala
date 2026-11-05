package sigmafi.contracts

import lfsm.ScriptGenerator
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.ast.{Constant, ErgoTree, SType}
import sigma.data.RType
import sigma.{Coll, Colls, VersionContext}
import work.lithos.mutations.{Contract, Mutator}

import scala.util.Try

/** When a bond matures: at a fixed height, or a number of blocks after its order is filled. */
sealed trait ZCSchedule
final case class FixedMaturity(height: Int) extends ZCSchedule
final case class OnClose(duration: Int) extends ZCSchedule

/**
 * A lender's offer terms, the constants of one `ZC_Offer`.
 *
 * @param lenderHash       blake2b256 of the lender's ErgoTree: receives the bond asset, authorizes a cancel
 * @param assetName        EIP-4 name of the bond asset, UTF-8
 * @param assetDescription EIP-4 description of the bond asset, UTF-8
 * @param bondHash         blake2b256 of the bond tree a close must create
 * @param collateralErg    least nanoERG the bond holds
 * @param collateralTokens the bond's tokens, exactly
 */
final case class ZCOfferTerms(lenderHash: Array[Byte], assetName: Array[Byte], assetDescription: Array[Byte],
                              bondHash: Array[Byte], repayment: Long, batchingFee: Long, maturity: Int,
                              liqScriptHash: Array[Byte], collateralErg: Long, collateralTokens: Seq[(ErgoId, Long)])

/**
 * A borrower's order terms, the constants of one `ZC_Order` or `ZC_OrderOnClose`.
 *
 * @param principalErg    least nanoERG of the principal box
 * @param principalTokens the principal box's tokens, exactly: none for an ERG loan
 */
final case class ZCOrderTerms(bondHash: Array[Byte], borrowerHash: Array[Byte], principalErg: Long,
                              principalTokens: Seq[(ErgoId, Long)], repayment: Long, batchingFee: Long,
                              schedule: ZCSchedule, liqScriptHash: Array[Byte])

/** A liquidation script: `bytes` is the expression a bond runs from context var 0, `hash` what it keeps in R8. */
final case class LiquidationScript(name: String, bytes: Array[Byte]) {
  val hash: Array[Byte] = Blake2b256.hash(bytes)
  def hashHex: String = Hex.toHexString(hash)
  def bytesHex: String = Hex.toHexString(bytes)
}

/** A SigmaFi contract built per instance from a pinned template, and the names of its terms. */
sealed abstract class SigmaFiTemplateKind(val scriptName: String, val termNames: Seq[String])

object SigmaFiTemplateKind {
  import SigmaFiContracts._

  case object Offer extends SigmaFiTemplateKind("ZC_Offer", Seq(LENDER_HASH, ASSET_NAME, ASSET_DESCRIPTION,
    BOND_HASH, REPAYMENT, BATCHING_FEE, MATURITY, LIQ_SCRIPT_HASH, COLLATERAL_ERG, COLLATERAL_TOKENS))
  case object Order extends SigmaFiTemplateKind("ZC_Order", Seq(BOND_HASH, BORROWER_HASH, PRINCIPAL_ERG,
    PRINCIPAL_TOKENS, REPAYMENT, BATCHING_FEE, MATURITY, LIQ_SCRIPT_HASH))
  case object OrderOnClose extends SigmaFiTemplateKind("ZC_OrderOnClose", Seq(BOND_HASH, BORROWER_HASH,
    PRINCIPAL_ERG, PRINCIPAL_TOKENS, REPAYMENT, BATCHING_FEE, DURATION, LIQ_SCRIPT_HASH))
  /** One token bond per repayment token, CONST_TOKEN_ID its only term. */
  case object TokenBond extends SigmaFiTemplateKind("ZC_Token_Bond", Seq(TOKEN_ID))

  val all: Seq[SigmaFiTemplateKind] = Seq(Offer, Order, OrderOnClose, TokenBond)
}

/**
 * One template, read from a tree compiled with sentinel terms.
 *
 * A template hash leaves out every constant, so a forged tree can share it while changing a literal such as
 * an order's mint amount. [[matches]] holds every constant that is not a term to the pinned tree's own value.
 */
final case class SigmaFiTemplate(kind: SigmaFiTemplateKind, tree: ErgoTree, positions: Map[String, Int]) {

  /** The node indexer's hash: Blake2b256 over the template bytes. */
  val templateHash: Array[Byte] = Blake2b256.hash(tree.template)
  val templateHashHex: String = Hex.toHexString(templateHash)

  def indexOf(name: String): Int =
    positions.getOrElse(name, throw new NoSuchElementException(s"${kind.scriptName} has no term $name"))

  /** Whether `candidate` is this template with only its terms changed, each keeping its type. */
  def matches(candidate: ErgoTree): Boolean = {
    val terms = positions.values.toSet
    candidate.header == tree.header &&
      candidate.constants.size == tree.constants.size &&
      java.util.Arrays.equals(candidate.template, tree.template) &&
      tree.constants.indices.forall { i =>
        if (terms(i)) candidate.constants(i).tpe == tree.constants(i).tpe
        else candidate.constants(i) == tree.constants(i)
      }
  }

  /** `candidate`'s term values, by name, when it [[matches]]. */
  def termsOf(candidate: ErgoTree): Option[Map[String, Any]] =
    if (!Try(matches(candidate)).getOrElse(false)) None
    else Some(positions.map { case (name, i) => name -> candidate.constants(i).value })

  /** An instance with every term replaced, so its template is this one byte for byte. */
  def withTerms(values: Map[String, Any]): Contract = {
    require(values.keySet == kind.termNames.toSet,
      s"${kind.scriptName} takes ${kind.termNames.mkString(", ")}, got ${values.keySet.mkString(", ")}")
    val replaced = values.foldLeft(tree.constants) { case (consts, (name, value)) =>
      val i = indexOf(name)
      consts.updated(i, Constant[SType](value.asInstanceOf[SType#WrappedType], consts(i).tpe))
    }
    val built = VersionContext.withVersions(tree.version, tree.version) {
      ErgoTree(tree.header, replaced, tree.root.right.get)
    }
    // Serializing checks each value against its declared type, and a wrong one throws here, not at a spend
    require(Try(ErgoTree.fromBytes(built.bytes)).toOption.exists(matches),
      s"${kind.scriptName}: a term's value does not fit its type")
    Contract(built)
  }
}

/**
 * The SigmaFi V2 zero-coupon bond contracts, pinned rather than compiled at runtime.
 *
 * The vault and the ERG bond are single trees. The token bond, both orders and the offer are templates
 * compiled with sentinel terms, and instances are built by substituting constants into them. Every term
 * is one constant at a fixed position. `SigmaFiContractsSpec` compiles the sources and fails on any
 * difference from these pins or from the addresses and hashes SigmaFi's own client reports.
 */
object SigmaFiContracts {

  final val LENDER_HASH = "CONST_LENDER_HASH"
  final val ASSET_NAME = "CONST_ASSET_NAME"
  final val ASSET_DESCRIPTION = "CONST_ASSET_DESCRIPTION"
  final val BOND_HASH = "CONST_BOND_HASH"
  final val BORROWER_HASH = "CONST_BORROWER_HASH"
  final val REPAYMENT = "CONST_REPAYMENT"
  final val BATCHING_FEE = "CONST_BATCHING_FEE"
  final val MATURITY = "CONST_MATURITY"
  final val DURATION = "CONST_DURATION"
  final val LIQ_SCRIPT_HASH = "CONST_LIQ_SCRIPT_HASH"
  final val COLLATERAL_ERG = "CONST_COLLATERAL_ERG"
  final val COLLATERAL_TOKENS = "CONST_COLLATERAL_TOKENS"
  final val PRINCIPAL_ERG = "CONST_PRINCIPAL_ERG"
  final val PRINCIPAL_TOKENS = "CONST_PRINCIPAL_TOKENS"
  final val TOKEN_ID = "CONST_TOKEN_ID"
  final val VAULT_HASH = "CONST_VAULT_HASH"

  /** Every protocol output holding a token or registers carries at least this: 0.001 ERG. */
  final val MinBox: Long = 1000000L

  /** The fewest blocks an on-close order's bond may run, and how far its maturity may trail the fill. */
  final val MinOnCloseDuration: Int = 30
  final val OnCloseAllowance: Int = 8

  // ─── pinned trees ────────────────────────────────────────────────────────

  final val VaultTreeHex: String =
    "1ba80106050005000502050005000500d801d601e4c6a7050ed1ed92dad901020eb0a47300d9010441639a8c720401b0db63088c" +
      "7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201730293dad90102" +
      "0eb0a57303d9010441639a8c720401b0db63088c7204027304d90106414d0ed802d6088c720602d6098c72060195938c72080172" +
      "029a72098c72080272090172017305"

  final val ErgBondTreeHex: String =
    "1beb01040e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b040204020400d809d601e3000ed6" +
      "02db6508fed603b2a5720200d604db6308a7d605c1a7d606e4c6a70611d60793cbc272037300d608c5a7d609ed93e4c67203040e" +
      "720893e4c67203050ee4c6a7040e95e67201ea02d19683050193cbe47201e4c6a7080e7207720993db63087203720492c1720399" +
      "7205b27206730100d40800d801d60ab2a59a7202730200d1968308018fa3e4c6a707047207720992c17203b2720673030093cbc2" +
      "720ae4c6a7050e93e4c6720a040e720893db6308720a720492c1720a7205"

  /** Each template's tree with sentinel terms, and the sentinel values that find each term's position. */
  private val pinnedTrees: Map[SigmaFiTemplateKind, String] = Map(
    SigmaFiTemplateKind.TokenBond ->
      ("1bbc02090e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b040204020580897a040204000e20" +
        "7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e04000400d809d601e3000ed602db6508fed603b2" +
        "a5720200d604db6308a7d605c1a7d606e4c6a70611d60793cbc272037300d608c5a7d609ed93e4c67203040e720893e4c6720305" +
        "0ee4c6a7040e95e67201ea02d19683050193cbe47201e4c6a7080e7207720993db63087203720492c17203997205b27206730100" +
        "d40800d802d60adb63087203d60bb2a59a7202730200d1968309018fa3e4c6a707047207720992c172037303eded93b1720a7304" +
        "938cb2720a730500017306928cb2720a73070002b2720673080093cbc2720be4c6a7050e93e4c6720b040e720893db6308720b72" +
        "0492c1720b7205"),

    SigmaFiTemplateKind.Offer ->
      ("1bc8041b050005000e205252525252525252525252525252525252525252525252525252525252525252050004000402040204" +
        "000502040004000580897a0e1455555555555555555555555555555555555555550e15565656565656565656565656565656565656" +
        "56565602300e2051515151515151515151515151515151515151515151515151515151515151510c4d0e012054545454545454545454" +
        "54545454545454545454545454545454545454545454dee1bbf93905dce1bbf9390404040005d6e1bbf939040205d8e1bbf93904" +
        "daf85e0e20535353535353535353535353535353535353535353535353535353535353535305000580897ad803d601c5a7d602da" +
        "d901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72" +
        "080172029a72098c7208027209017201d6037302959172027303d804d604b2a5730400d605b2a5730500d606e4c672050611d607" +
        "b27206730600d1ededededededededed937201c5b2a4730700937202730891b1db630872047309938cb2db63087204730a000172" +
        "0193cbc27204720392c17204730bededed93e4c67204040e730c93e4c67204050e730d93e4c67204060e830102730eefe6c67204" +
        "070eeded93cbc27205730f93db63087205731092c172057311edededededed93e4c67205040e7201e6c67205050e93b172067312" +
        "93b27206731300731493b27206731500731693e4c672050704731793e4c67205080e7318ed92720773199299c172057207731ad1" +
        "aea4d901046393cbc272047203"),

    SigmaFiTemplateKind.Order ->
      ("1b910419050005000e206262626262626262626262626262626262626262626262626262626262626262050004000402040204" +
        "0404000502040004000e2061616161616161616161616161616161616161616161616161616161616161610404040005e4b88d9d42" +
        "040205e6b88d9d4204e8c06c0e20636363636363636363636363636363636363636363636363636363636363636305000580897a04" +
        "0205e2b88d9d420c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42d803d6" +
        "01c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72" +
        "060195938c72080172029a72098c7208027209017201d6037302959172027303d805d604b2a5730400d605b2a5730500d606e4c6" +
        "72050611d607b27206730600d608b2a5730700d1edededededededed937201c5b2a4730800937202730991b1db63087204730a93" +
        "8cb2db63087204730b00017201eded93cbc27205730c93db63087205db6308a792c17205c1a7edededededed93e4c67205040e72" +
        "0193e4c67205050e720393b17206730d93b27206730e00730f93b27206731000731193e4c672050704731293e4c67205080e7313" +
        "ed92720773149299c172057207731591e4c6720507049aa37316eded93cbc27208720392c17208731793db630872087318d1aea4" +
        "d901046393cbc272047203"),

    SigmaFiTemplateKind.OrderOnClose ->
      ("1ba2041a050005000e207272727272727272727272727272727272727272727272727272727272727272050004000402040204" +
        "f4887a040404000502040004000e2071717171717171717171717171717171717171717171717171717171717171710404040005" +
        "f08fdfc04a040205f28fdfc04a0e20737373737373737373737373737373737373737373737373737373737373737305000580897a" +
        "043c041005ee8fdfc04a0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04ad8" +
        "03d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d609" +
        "8c72060195938c72080172029a72098c7208027209017201d6037302959172027303d807d604b2a5730400d605b2a5730500d606" +
        "e4c672050611d607b27206730600d6089aa37307d609e4c672050704d60ab2a5730800d1edededededededed937201c5b2a47309" +
        "00937202730a91b1db63087204730b938cb2db63087204730c00017201eded93cbc27205730d93db63087205db6308a792c17205" +
        "c1a7ededededed93e4c67205040e720193e4c67205050e720393b17206730e93b27206730f00731093b27206731100731293e4c6" +
        "7205080e7313ed92720773149299c1720572077315eded92997208a3731690720972089272099972087317eded93cbc2720a7203" +
        "92c1720a731893db6308720a7319d1aea4d901046393cbc272047203"))

  private def bytes(fill: Int, n: Int = 32): Coll[Byte] = Colls.fromArray(Array.fill(n)(fill.toByte))

  private def tokenColl(tokens: Seq[(ErgoId, Long)]): Coll[(Coll[Byte], Long)] =
    Colls.fromArray(tokens.map { case (id, n) => (Colls.fromArray(id.getBytes), n) }.toArray)(
      RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType))

  /**
   * The values each template was compiled with, matching SigmaFi's own client, so the pinned trees and the
   * positions they give are the ones it reports. Each appears nowhere else in its script.
   */
  private[contracts] val sentinels: Map[SigmaFiTemplateKind, Map[String, Any]] = Map(
    SigmaFiTemplateKind.TokenBond -> Map(TOKEN_ID -> bytes(0x7e)),
    SigmaFiTemplateKind.Offer -> Map(
      LENDER_HASH -> bytes(0x52), ASSET_NAME -> bytes(0x55, 20), ASSET_DESCRIPTION -> bytes(0x56, 21),
      BOND_HASH -> bytes(0x51), REPAYMENT -> 7777777771L, BATCHING_FEE -> 7777777772L, MATURITY -> 777773,
      LIQ_SCRIPT_HASH -> bytes(0x53), COLLATERAL_ERG -> 7777777774L,
      COLLATERAL_TOKENS -> tokenColl(Seq(new ErgoId(Array.fill(32)(0x54.toByte)) -> 7777777775L))),
    SigmaFiTemplateKind.Order -> Map(
      BOND_HASH -> bytes(0x61), BORROWER_HASH -> bytes(0x62), PRINCIPAL_ERG -> 8888888881L,
      PRINCIPAL_TOKENS -> tokenColl(Seq(new ErgoId(Array.fill(32)(0x64.toByte)) -> 8888888885L)),
      REPAYMENT -> 8888888882L, BATCHING_FEE -> 8888888883L, MATURITY -> 888884, LIQ_SCRIPT_HASH -> bytes(0x63)),
    SigmaFiTemplateKind.OrderOnClose -> Map(
      BOND_HASH -> bytes(0x71), BORROWER_HASH -> bytes(0x72), PRINCIPAL_ERG -> 9999999991L,
      PRINCIPAL_TOKENS -> tokenColl(Seq(new ErgoId(Array.fill(32)(0x74.toByte)) -> 9999999995L)),
      REPAYMENT -> 9999999992L, BATCHING_FEE -> 9999999993L, DURATION -> 999994, LIQ_SCRIPT_HASH -> bytes(0x73)))

  private def tree(hex: String): ErgoTree =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      ErgoTree.fromHex(hex)
    }

  private lazy val templates: Map[SigmaFiTemplateKind, SigmaFiTemplate] =
    SigmaFiTemplateKind.all.map(kind => kind -> fromTree(kind, tree(pinnedTrees(kind)))).toMap

  /** Each term found at the one constant holding its sentinel; anything else is a broken pin. */
  private def fromTree(kind: SigmaFiTemplateKind, t: ErgoTree): SigmaFiTemplate = {
    val positions = kind.termNames.map { name =>
      val value = sentinels(kind)(name)
      val found = t.constants.indices.filter(i => t.constants(i).value == value)
      require(found.size == 1, s"${kind.scriptName}: $name must be exactly one constant, found ${found.size}")
      name -> found.head
    }.toMap
    SigmaFiTemplate(kind, t, positions)
  }

  def template(kind: SigmaFiTemplateKind): SigmaFiTemplate = templates(kind)

  def pinnedTreeHex(kind: SigmaFiTemplateKind): String = pinnedTrees(kind)

  /** BondAssetVault, where every settlement goes and which the bond asset opens. */
  lazy val vault: Contract = Contract(tree(VaultTreeHex))

  /** ZC_ERG_Bond, repaid in nanoERG. */
  lazy val ergBond: Contract = Contract(tree(ErgBondTreeHex))

  /** ZC_Token_Bond for `tokenId`, repaid in that token. */
  def tokenBond(tokenId: ErgoId): Contract =
    template(SigmaFiTemplateKind.TokenBond).withTerms(Map(TOKEN_ID -> Colls.fromArray(tokenId.getBytes)))

  /** The bond repaid in `repayToken`, or in nanoERG for None. */
  def bond(repayToken: Option[ErgoId]): Contract = repayToken.fold(ergBond)(tokenBond)

  // ─── liquidation scripts ─────────────────────────────────────────────────

  /** HEIGHT >= SELF.R7: anyone may liquidate once the bond matures. */
  final val MaturityLiquidation = LiquidationScript("MaturityLiquidation", Hex.decode("d192a3e4c6a70704"))

  /** Every liquidation script this client will build for or liquidate under. */
  val liquidations: Seq[LiquidationScript] = Seq(MaturityLiquidation)

  def liquidationOf(hash: Array[Byte]): Option[LiquidationScript] = liquidations.find(_.hash.sameElements(hash))

  def liquidation(name: String): Option[LiquidationScript] = liquidations.find(_.name == name)

  // ─── instances ───────────────────────────────────────────────────────────

  def offer(t: ZCOfferTerms): Contract = template(SigmaFiTemplateKind.Offer).withTerms(Map(
    LENDER_HASH -> Colls.fromArray(t.lenderHash), ASSET_NAME -> Colls.fromArray(t.assetName),
    ASSET_DESCRIPTION -> Colls.fromArray(t.assetDescription), BOND_HASH -> Colls.fromArray(t.bondHash),
    REPAYMENT -> t.repayment, BATCHING_FEE -> t.batchingFee, MATURITY -> t.maturity,
    LIQ_SCRIPT_HASH -> Colls.fromArray(t.liqScriptHash), COLLATERAL_ERG -> t.collateralErg,
    COLLATERAL_TOKENS -> tokenColl(t.collateralTokens)))

  def order(t: ZCOrderTerms): Contract = {
    val common: Map[String, Any] = Map(
      BOND_HASH -> Colls.fromArray(t.bondHash), BORROWER_HASH -> Colls.fromArray(t.borrowerHash),
      PRINCIPAL_ERG -> t.principalErg, PRINCIPAL_TOKENS -> tokenColl(t.principalTokens),
      REPAYMENT -> t.repayment, BATCHING_FEE -> t.batchingFee, LIQ_SCRIPT_HASH -> Colls.fromArray(t.liqScriptHash))
    t.schedule match {
      case FixedMaturity(h) => template(SigmaFiTemplateKind.Order).withTerms(common + (MATURITY -> h))
      case OnClose(d) => template(SigmaFiTemplateKind.OrderOnClose).withTerms(common + (DURATION -> d))
    }
  }

  // ─── reading terms back ──────────────────────────────────────────────────

  /** `tree`'s terms when it is a genuine offer, with every hash and token id 32 bytes. */
  def offerTerms(tree: ErgoTree): Option[ZCOfferTerms] =
    template(SigmaFiTemplateKind.Offer).termsOf(tree).flatMap { v =>
      Try {
        for {
          lender <- hash32(v(LENDER_HASH))
          bond <- hash32(v(BOND_HASH))
          liq <- hash32(v(LIQ_SCRIPT_HASH))
          tokens <- tokenList(v(COLLATERAL_TOKENS))
        } yield ZCOfferTerms(lender, collBytes(v(ASSET_NAME)), collBytes(v(ASSET_DESCRIPTION)), bond,
          v(REPAYMENT).asInstanceOf[Long], v(BATCHING_FEE).asInstanceOf[Long], v(MATURITY).asInstanceOf[Int], liq,
          v(COLLATERAL_ERG).asInstanceOf[Long], tokens)
      }.toOption.flatten
    }

  /** `tree`'s terms when it is a genuine order of either style. */
  def orderTerms(tree: ErgoTree): Option[ZCOrderTerms] =
    Seq(SigmaFiTemplateKind.Order, SigmaFiTemplateKind.OrderOnClose).iterator
      .flatMap(kind => template(kind).termsOf(tree).map(kind -> _)).toSeq.headOption.flatMap { case (kind, v) =>
        Try {
          val schedule =
            if (kind == SigmaFiTemplateKind.Order) FixedMaturity(v(MATURITY).asInstanceOf[Int])
            else OnClose(v(DURATION).asInstanceOf[Int])
          for {
            bond <- hash32(v(BOND_HASH))
            borrower <- hash32(v(BORROWER_HASH))
            liq <- hash32(v(LIQ_SCRIPT_HASH))
            tokens <- tokenList(v(PRINCIPAL_TOKENS))
          } yield ZCOrderTerms(bond, borrower, v(PRINCIPAL_ERG).asInstanceOf[Long], tokens,
            v(REPAYMENT).asInstanceOf[Long], v(BATCHING_FEE).asInstanceOf[Long], schedule, liq)
        }.toOption.flatten
      }

  /**
   * The token a bond tree repays in: Some(None) for the ERG bond, Some(Some(id)) for the token bond built for
   * `id`, None for anything else.
   */
  def bondRepayToken(tree: ErgoTree): Option[Option[ErgoId]] =
    if (java.util.Arrays.equals(tree.bytes, ergBond.propBytes)) Some(None)
    else template(SigmaFiTemplateKind.TokenBond).termsOf(tree)
      .flatMap(v => hash32(v(TOKEN_ID))).map(id => Some(new ErgoId(id)))

  /** Whether `tree` is a genuine order or offer, the only contracts that mint bond assets. */
  def isMinter(tree: ErgoTree): Boolean =
    Seq(SigmaFiTemplateKind.Offer, SigmaFiTemplateKind.Order, SigmaFiTemplateKind.OrderOnClose)
      .exists(k => Try(template(k).matches(tree)).getOrElse(false))

  /** A short name for a SigmaFi tree, or None. */
  def nameOf(tree: ErgoTree): Option[String] =
    if (java.util.Arrays.equals(tree.bytes, vault.propBytes)) Some("BondAssetVault")
    else if (java.util.Arrays.equals(tree.bytes, ergBond.propBytes)) Some("ZC_ERG_Bond")
    else SigmaFiTemplateKind.all.find(k => Try(template(k).matches(tree)).getOrElse(false)).map(_.scriptName)

  private def collBytes(v: Any): Array[Byte] = v.asInstanceOf[Coll[Byte]].toArray

  private def hash32(v: Any): Option[Array[Byte]] = Some(collBytes(v)).filter(_.length == 32)

  private def tokenList(v: Any): Option[Seq[(ErgoId, Long)]] = {
    val pairs = v.asInstanceOf[Coll[(Coll[Byte], Long)]].toArray.toSeq.map { case (id, n) => id.toArray -> n }
    if (pairs.forall(_._1.length == 32)) Some(pairs.map { case (id, n) => new ErgoId(id) -> n }) else None
  }

  // ─── sources, for the drift spec ─────────────────────────────────────────

  /**
   * `.ergo` sources compiled as SigmaFi's own client compiles them. For the drift spec and re-pinning only:
   * compiling a script twice in one JVM can throw.
   */
  def compileSource(name: String, terms: Map[String, Any]): ErgoTree = {
    val builder = ConstantsBuilder.create()
    terms.foreach { case (term, value) => builder.item(term, value.asInstanceOf[AnyRef]) }
    // The scripts hold no address literal, so the network prefix never reaches the tree
    Contract.fromErgoScript(NetworkType.MAINNET, builder.build(), ScriptGenerator.mkSigmaFiScript(name),
      Seq.empty[Mutator]).ergoTree
  }

  /** Each template kind's sentinel terms, as compiled into its pinned tree. */
  def sentinelTerms(kind: SigmaFiTemplateKind): Map[String, Any] =
    if (kind == SigmaFiTemplateKind.TokenBond) sentinels(kind) + (VAULT_HASH -> Colls.fromArray(vault.hashedPropBytes))
    else sentinels(kind)
}
