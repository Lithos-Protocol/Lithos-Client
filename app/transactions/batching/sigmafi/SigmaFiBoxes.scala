package transactions.batching.sigmafi

import node.MutationConversions._
import node.model.{IndexedBox, NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.ErgoTree
import sigmafi.contracts.{LiquidationScript, SigmaFiContracts, SigmaFiTemplateKind, ZCOfferTerms, ZCOrderTerms}
import work.lithos.mutations.InputUTXO

import scala.util.Try

/**
 * A live zero-coupon bond.
 *
 * @param repayToken   None for an ERG bond, the token a token bond is repaid in otherwise
 * @param asset        R4: the bond asset that claims the payout, the id of the order or offer that minted it
 * @param borrowerHash R5: blake2b256 of the tree the collateral returns to on repayment
 * @param repayment    R6(0): nanoERG, or token units for a token bond
 * @param batchingFee  R6(1): the most nanoERG a liquidator may keep
 * @param maturity     R7: repayment is open strictly below it
 */
final case class SigmaFiBond(box: NodeBox, repayToken: Option[ErgoId], asset: ErgoId, borrowerHash: Array[Byte],
                             repayment: Long, batchingFee: Long, maturity: Int, liqScriptHash: Array[Byte]) {
  def boxId: String = box.boxId

  def collateral: Seq[NodeAsset] = box.assets

  /** The liquidation script R8 names, when this client knows it. */
  def liquidation: Option[LiquidationScript] = SigmaFiContracts.liquidationOf(liqScriptHash)
}

/** A lender's offer: the principal plus 0.001 ERG for the lender's unit box. Its id becomes the bond asset's. */
final case class SigmaFiOffer(box: NodeBox, terms: ZCOfferTerms) {
  def boxId: String = box.boxId

  def principalErg: Long = box.value - SigmaFiContracts.MinBox

  def principalTokens: Seq[NodeAsset] = box.assets

  def liquidation: Option[LiquidationScript] = SigmaFiContracts.liquidationOf(terms.liqScriptHash)
}

/** A borrower's order: the box's whole contents are the collateral. Its id becomes the bond asset's. */
final case class SigmaFiOrder(box: NodeBox, terms: ZCOrderTerms) {
  def boxId: String = box.boxId

  def liquidation: Option[LiquidationScript] = SigmaFiContracts.liquidationOf(terms.liqScriptHash)
}

/**
 * A vault box: a settled bond's repayment or collateral.
 *
 * @param bondId R4, the bond it settles, when R4 reads as bytes
 * @param asset  R5, the bond asset that claims it
 */
final case class SigmaFiVaultBox(box: NodeBox, bondId: Option[String], asset: ErgoId) {
  def boxId: String = box.boxId
}

/**
 * Reads SigmaFi V2 boxes the node reports. Anyone can create a box at any of these scripts with any registers,
 * so each read first matches the script's template cheaply, then holds the whole tree to the pinned one with
 * only the terms free, and reads every register at the exact type the contracts write.
 */
object SigmaFiBoxes {

  private val BytesType = ErgoType.collType(ErgoType.byteType())
  private val LongsType = ErgoType.collType(ErgoType.longType())
  private val IntType = ErgoType.integerType()
  private val TreesType = ErgoType.collType(ErgoType.collType(ErgoType.byteType()))

  /** Each template's root expression as hex, which every instance's tree ends with. */
  private lazy val templateSuffix: Map[SigmaFiTemplateKind, String] =
    SigmaFiTemplateKind.all.map(k => k -> Hex.toHexString(SigmaFiContracts.template(k).tree.template)).toMap

  /** A cheap test that `ergoTree` could be an instance of `kind`, before any parse. */
  def shaped(ergoTree: String, kind: SigmaFiTemplateKind): Boolean =
    ergoTree.length > templateSuffix(kind).length && ergoTree.toLowerCase.endsWith(templateSuffix(kind))

  /** `box` as an ERG or token bond, or None. */
  def bond(box: NodeBox): Option[SigmaFiBond] = {
    val tree = box.ergoTree.toLowerCase
    val repayToken: Option[Option[ErgoId]] =
      if (tree == SigmaFiContracts.ergBond.ergoTreeHex) Some(None)
      else if (shaped(tree, SigmaFiTemplateKind.TokenBond)) parse(tree).flatMap(SigmaFiContracts.bondRepayToken)
      else None
    repayToken.flatMap { token =>
      Try {
        for {
          asset <- bytes(box, 4).filter(_.length == 32)
          borrower <- bytes(box, 5)
          amounts <- register(box, 6, LongsType).collect { case c: sigma.Coll[Long @unchecked] => c.toArray }
          if amounts.length == 2
          maturity <- register(box, 7, IntType).collect { case v: Integer => v.intValue }
          liq <- bytes(box, 8)
        } yield SigmaFiBond(box, token, new ErgoId(asset), borrower, amounts(0), amounts(1), maturity, liq)
      }.toOption.flatten
    }
  }

  /**
   * `box` as an offer, or None. Refused when it holds less than the lender unit's box plus a principal box,
   * since no close could then pay both.
   */
  def offer(box: NodeBox): Option[SigmaFiOffer] =
    if (box.value < 2 * SigmaFiContracts.MinBox || !shaped(box.ergoTree, SigmaFiTemplateKind.Offer)) None
    else parse(box.ergoTree).flatMap(SigmaFiContracts.offerTerms).map(SigmaFiOffer(box, _))

  /** `box` as an order of either style, or None. */
  def order(box: NodeBox): Option[SigmaFiOrder] =
    if (!shaped(box.ergoTree, SigmaFiTemplateKind.Order) && !shaped(box.ergoTree, SigmaFiTemplateKind.OrderOnClose)) None
    else parse(box.ergoTree).flatMap(SigmaFiContracts.orderTerms).map(SigmaFiOrder(box, _))

  /** `box` as a vault box naming a 32-byte bond asset, or None. A vault box without one is locked for good. */
  def vault(box: NodeBox): Option[SigmaFiVaultBox] =
    if (!box.ergoTree.equalsIgnoreCase(SigmaFiContracts.vault.ergoTreeHex)) None
    else Try {
      bytes(box, 5).filter(_.length == 32).map { asset =>
        SigmaFiVaultBox(box, bytes(box, 4).map(Hex.toHexString), new ErgoId(asset))
      }
    }.toOption.flatten

  /** The ErgoTrees R4 lists as hints, as this client writes them on orders and offers. Bad entries are dropped. */
  def hintTrees(box: NodeBox): Seq[ErgoTree] =
    register(box, 4, TreesType).toSeq.flatMap {
      case c: sigma.Coll[sigma.Coll[Byte] @unchecked] => c.toArray.toSeq.flatMap(b => parse(Hex.toHexString(b.toArray)))
      case _ => Seq.empty
    }

  /** R4 hint for an order or offer: `Coll[Coll[Byte]]`, each entry an ErgoTree's bytes. No contract reads it. */
  def hintValue(trees: ErgoTree*): ErgoValue[_] =
    ErgoValue.of(sigma.Colls.fromArray(trees.map(t => sigma.Colls.fromArray(t.bytes)).toArray)(
      sigma.collRType(sigma.ByteType)), ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))

  /**
   * The repayment token a bond hash names for a loan of `principalTokens`: Some(None) for an ERG loan paired
   * with the ERG bond, Some(Some(id)) for a loan of one token paired with that token's bond, otherwise None.
   * Neither orders nor offers check the pairing, so a mismatched one is never built on.
   */
  def pairedBond(bondHash: Array[Byte], principalTokens: Seq[(ErgoId, Long)]): Option[Option[ErgoId]] =
    principalTokens match {
      case Seq() if SigmaFiContracts.ergBond.hashedPropBytes.sameElements(bondHash) => Some(None)
      case Seq((id, _)) if SigmaFiContracts.tokenBond(id).hashedPropBytes.sameElements(bondHash) => Some(Some(id))
      case _ => None
    }

  /**
   * Whether `bond` is the one its bond asset's mint created: the asset's genesis box `genesis` is a genuine
   * order or offer, spent by the transaction that created the bond, with the bond at output 1. For display
   * and pricing only; nothing liquidated or repaid depends on it. Left is the reason it is not.
   */
  def verify(bond: SigmaFiBond, genesis: Option[IndexedBox]): Either[String, Unit] =
    for {
      g <- genesis.toRight("the node's index has no genesis box for its bond asset")
      _ <- Either.cond(g.boxId == bond.asset.toString, (), "the box found is not its bond asset's genesis box")
      tree <- parse(g.ergoTree).toRight("its bond asset's genesis box has an unreadable script")
      _ <- Either.cond(SigmaFiContracts.isMinter(tree), (), "its bond asset was not minted by a SigmaFi order or offer")
      _ <- Either.cond(g.spentTransactionId.contains(bond.box.transactionId), (),
        "it is not the bond its asset's mint created")
      _ <- Either.cond(bond.box.index == 1, (), "it is not its mint's second output")
    } yield ()

  /** `box` rebuilt for spending, or None when its id does not come back the same. */
  def input(box: NodeBox, ctx: BlockchainContext): Option[InputUTXO] =
    Try(box.toInputUTXO(ctx)).toOption.filter(_.id.toString == box.boxId)

  /** blake2b256 of a tree's bytes, the form every SigmaFi contract commits to. */
  def hashOf(tree: ErgoTree): Array[Byte] = Blake2b256.hash(tree.bytes)

  def parse(hex: String): Option[ErgoTree] =
    Try(VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      ErgoTree.fromHex(hex)
    }).toOption

  private def bytes(box: NodeBox, index: Int): Option[Array[Byte]] =
    register(box, index, BytesType).collect { case c: sigma.Coll[Byte @unchecked] => c.toArray }

  /** The value of register `index`, when it decodes as `tpe`. */
  private def register(box: NodeBox, index: Int, tpe: ErgoType[_]): Option[Any] =
    box.additionalRegisters.get(index)
      .flatMap[ErgoValue[_]](hex => Try(ErgoValue.fromHex(hex)).toOption)
      .filter(_.getType == tpe)
      .map(_.getValue)
}
