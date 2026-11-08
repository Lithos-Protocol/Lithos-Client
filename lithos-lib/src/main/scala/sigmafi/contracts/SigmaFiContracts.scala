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
final case class FixedMaturity(height: Long) extends ZCSchedule
final case class OnClose(duration: Long) extends ZCSchedule

/**
 * A borrower's order terms, the constants of one `ZC_Order` or `ZC_OrderOnClose`.
 *
 * @param principalErg    least nanoERG of the principal box
 * @param principalTokens the principal box's tokens, exactly: none for an ERG loan
 */
final case class ZCOrderTerms(bondHash: Array[Byte], borrowerHash: Array[Byte], principalErg: Long,
                              principalTokens: Seq[(ErgoId, Long)], repayment: Long,
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

  case object Order extends SigmaFiTemplateKind("ZC_Order", Seq(BOND_HASH, BORROWER_HASH, PRINCIPAL_ERG,
    PRINCIPAL_TOKENS, REPAYMENT, MATURITY, LIQ_SCRIPT_HASH))
  case object OrderOnClose extends SigmaFiTemplateKind("ZC_OrderOnClose", Seq(BOND_HASH, BORROWER_HASH,
    PRINCIPAL_ERG, PRINCIPAL_TOKENS, REPAYMENT, DURATION, LIQ_SCRIPT_HASH))
  /** One token bond per repayment token, CONST_TOKEN_ID its only term. */
  case object TokenBond extends SigmaFiTemplateKind("ZC_Token_Bond", Seq(TOKEN_ID))

  val all: Seq[SigmaFiTemplateKind] = Seq(Order, OrderOnClose, TokenBond)
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
 * The vault and the ERG bond are single trees. The token bond and both orders are templates
 * compiled with sentinel terms, and instances are built by substituting constants into them. Every term
 * is one constant at a fixed position. `SigmaFiContractsSpec` compiles the sources and fails on any
 * difference from these pins or from the addresses and hashes SigmaFi's own client reports.
 */
object SigmaFiContracts {

  final val BOND_HASH = "CONST_BOND_HASH"
  final val BORROWER_HASH = "CONST_BORROWER_HASH"
  final val REPAYMENT = "CONST_REPAYMENT"
  final val MATURITY = "CONST_MATURITY"
  final val DURATION = "CONST_DURATION"
  final val LIQ_SCRIPT_HASH = "CONST_LIQ_SCRIPT_HASH"
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
    "1ba80106050005000502050005000500d801d601e4c6a7050ed1ed92dad901020eb0a47300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201730293dad901020eb0a57303d9010441639a8c720401b0db63088c7204027304d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c72080272090172017305"

  final val ErgBondTreeHex: String =
    "1bbd031204020400040805000580897a040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b0500050005000201040001000406020004000402d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd802d60ec17208d60fb2a5720d00d196830a018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e7202b2720173100093cbc2720fe4c6a7050e93e4c6720f040e720a93db6308720fb472057311b1720592c1720f9972037202720c"

  /** Exact prototypes exported by the current SigmaFi V2 contract reference. */
  private val pinnedTrees: Map[SigmaFiTemplateKind, String] = Map(
    SigmaFiTemplateKind.TokenBond -> "1b8e041704020400040805000580897a040001000e20ed132c371e35881b1b4fde03f092bc2eb8d9deb96df89cd9a5a32ab4f94b3c6b050005000500020104000100040602000580897a040204000e207e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e040004000402d806d601e4c6a70611d602b27201730000d603c1a7d604e4e30104d605db6308a7d6068cb272057301000195ecececec94b1720173028f7202730391720299720373048f72047305927204b1a5d17306d806d607e3000ed608b2a5720400d60993cbc272087307d60ac5a7d60bed93e4c67208040e720a93e4c67208050e7206d60c93dad9010c0eb0a57308d9010e41639a8c720e01b0db63088c720e027309d90110414d0ed802d6128c721002d6138c72100195938c721201720c9a72138c7212027213017206730a95e67207ea02d19683050193cbe47207e4c6a7080e7209720b93e4c672080602730b720cd40800d801d60de4e3020495ecec8f720d730c92720db1a593720d7204d1730dd803d60ec17208d60fdb63087208d610b2a5720d00d196830b018f7ea305b27201730e007209720b93e4c672080602730fed92720e72029299720e72027310eded93b1720f7311938cb2720f731200017313928cb2720f73140002b2720173150093cbc27210e4c6a7050e93e4c67210040e720a93db63087210b472057316b1720592c172109972037202720c",
    SigmaFiTemplateKind.Order -> "1b9e0524050005000e206262626262626262626262626262626262626262626262626262626262626262050004000402040204040400050404000400040005020e2061616161616161616161616161616161616161616161616161616161616161610400040004000502040205000408040005e4b88d9d420402040405e2b88d9d420c4d0e01206464646464646464646464646464646464646464646464646464646464646464eab88d9d42040605e8c06c0e20636363636363636363636363636363636363636363636363636363636363636305000580897a040605020404d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d806d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d609b2a5730700d1ededededededededed937201c5b2a4730800937202730991b1db63087204730a938cb2db63087204730b00017201938cb2db63087204730c0002730dededededededed93cbc27205730e91b1db63087205730f938cb2db63087205731000017201938cb2db63087205731100027312d801d60adb6308720593b4720a7313b1720adb6308a792c17205c1a792720673149299c17205c1a77206edededededededed93e4c67205040e720193e4c67205050e720393b17207731593b27207731600731793b27207731800720693b27207731900731a93e4c67205070c4d0e731b93b27207731c00731d93e4c67205080e731eed927208731f9299c172057208732091b272077321009a7ea3057322eded93cbc27209720392c17209b2720773230093db63087209e4c67205070c4d0ed1aea4d901046393cbc272047203",
    SigmaFiTemplateKind.OrderOnClose -> "1bbd0525050005000e207272727272727272727272727272727272727272727272727272727272727272050004000402040205f4887a040604040400050404000400040005020e2071717171717171717171717171717171717171717171717171717171717171710400040004000502040205000408040005f08fdfc04a0402040405ee8fdfc04a0c4d0e01207474747474747474747474747474747474747474747474747474747474747474f68fdfc04a0e20737373737373737373737373737373737373737373737373737373737373737305000580897a053c053c05100404d803d601c5a7d602dad901020eb0a57300d9010441639a8c720401b0db63088c7204027301d90106414d0ed802d6088c720602d6098c72060195938c72080172029a72098c7208027209017201d6037302959172027303d809d604b2a5730400d605b2a5730500d606e4e30005d607e4c672050611d608b27207730600d6097ea305d60a9a72097307d60bb27207730800d60cb2a5730900d1ededededededededed937201c5b2a4730a00937202730b91b1db63087204730c938cb2db63087204730d00017201938cb2db63087204730e0002730fededededededed93cbc27205731091b1db630872057311938cb2db63087205731200017201938cb2db63087205731300027314d801d60ddb6308720593b4720d7315b1720ddb6308a792c17205c1a792720673169299c17205c1a77206ededededededed93e4c67205040e720193e4c67205050e720393b17207731793b27207731800731993b27207731a00720693b27207731b00731c93e4c67205070c4d0e731d93e4c67205080e731eed927208731f9299c1720572087320ededed92720a9a720973219299720a7209732290720b720a92720b99720a7323eded93cbc2720c720392c1720cb2720773240093db6308720ce4c67205070c4d0ed1aea4d901046393cbc272047203")

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
    SigmaFiTemplateKind.Order -> Map(
      BOND_HASH -> bytes(0x61), BORROWER_HASH -> bytes(0x62), PRINCIPAL_ERG -> 8888888881L,
      PRINCIPAL_TOKENS -> tokenColl(Seq(new ErgoId(Array.fill(32)(0x64.toByte)) -> 8888888885L)),
      REPAYMENT -> 8888888882L, MATURITY -> 888884L, LIQ_SCRIPT_HASH -> bytes(0x63)),
    SigmaFiTemplateKind.OrderOnClose -> Map(
      BOND_HASH -> bytes(0x71), BORROWER_HASH -> bytes(0x72), PRINCIPAL_ERG -> 9999999991L,
      PRINCIPAL_TOKENS -> tokenColl(Seq(new ErgoId(Array.fill(32)(0x74.toByte)) -> 9999999995L)),
      REPAYMENT -> 9999999992L, DURATION -> 999994L, LIQ_SCRIPT_HASH -> bytes(0x73)))

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

  /** Maturity plus exact collateral tokens and the fee bound; the bond pins vault, tags and burn. */
  final val MaturityLiquidation = LiquidationScript("MaturityLiquidation", Hex.decode("d801d601e4e3010495ec8f72010400927201b1a5d10100d803d602e4c6a70611d603b2a5720100d604db6308a7d1eded927ea305b2720204060093db63087203b472040402b1720492c1720399c1a7b27202040200"))

  /** Every liquidation script this client will build for or liquidate under. */
  val liquidations: Seq[LiquidationScript] = Seq(MaturityLiquidation)

  def liquidationOf(hash: Array[Byte]): Option[LiquidationScript] = liquidations.find(_.hash.sameElements(hash))

  def liquidation(name: String): Option[LiquidationScript] = liquidations.find(_.name == name)

  // ─── instances ───────────────────────────────────────────────────────────

  def order(t: ZCOrderTerms): Contract = {
    val common: Map[String, Any] = Map(
      BOND_HASH -> Colls.fromArray(t.bondHash), BORROWER_HASH -> Colls.fromArray(t.borrowerHash),
      PRINCIPAL_ERG -> t.principalErg, PRINCIPAL_TOKENS -> tokenColl(t.principalTokens),
      REPAYMENT -> t.repayment, LIQ_SCRIPT_HASH -> Colls.fromArray(t.liqScriptHash))
    t.schedule match {
      case FixedMaturity(h) => template(SigmaFiTemplateKind.Order).withTerms(common + (MATURITY -> h))
      case OnClose(d) => template(SigmaFiTemplateKind.OrderOnClose).withTerms(common + (DURATION -> d))
    }
  }

  // ─── reading terms back ──────────────────────────────────────────────────

  /** `tree`'s terms when it is a genuine order of either style. */
  def orderTerms(tree: ErgoTree): Option[ZCOrderTerms] =
    Seq(SigmaFiTemplateKind.Order, SigmaFiTemplateKind.OrderOnClose).iterator
      .flatMap(kind => template(kind).termsOf(tree).map(kind -> _)).toSeq.headOption.flatMap { case (kind, v) =>
        Try {
          val schedule =
            if (kind == SigmaFiTemplateKind.Order) FixedMaturity(v(MATURITY).asInstanceOf[Long])
            else OnClose(v(DURATION).asInstanceOf[Long])
          for {
            bond <- hash32(v(BOND_HASH))
            borrower <- hash32(v(BORROWER_HASH))
            liq <- hash32(v(LIQ_SCRIPT_HASH))
            tokens <- tokenList(v(PRINCIPAL_TOKENS))
          } yield ZCOrderTerms(bond, borrower, v(PRINCIPAL_ERG).asInstanceOf[Long], tokens,
            v(REPAYMENT).asInstanceOf[Long], schedule, liq)
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

  /** Whether `tree` is a genuine borrower order, the only contracts that mint bond assets. */
  def isMinter(tree: ErgoTree): Boolean =
    Seq(SigmaFiTemplateKind.Order, SigmaFiTemplateKind.OrderOnClose)
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
