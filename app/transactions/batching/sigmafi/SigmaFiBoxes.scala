package transactions.batching.sigmafi

import node.MutationConversions._
import node.model.{IndexedBox, NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.ErgoTree
import sigmafi.contracts.{FixedMaturity, LiquidationScript, OnClose, SigmaFiContracts, SigmaFiTemplateKind, ZCOrderTerms}
import work.lithos.mutations.InputUTXO

import scala.util.Try

/**
 * A live zero-coupon bond.
 *
 * @param repayToken   None for an ERG bond, the token a token bond is repaid in otherwise
 * @param asset        first token, quantity one; R4 must agree with this originating order id
 * @param borrowerHash R5: blake2b256 of the tree the collateral returns to on repayment
 * @param repayment    R6(0): nanoERG, or token units for a token bond
 * @param batchingFee  R6(1): the most nanoERG a liquidator may keep
 * @param maturity     R7: repayment is open strictly below it
 */
final case class SigmaFiBond(box: NodeBox, repayToken: Option[ErgoId], asset: ErgoId, borrowerHash: Array[Byte],
                             repayment: Long, batchingFee: Long, maturity: Int, liqScriptHash: Array[Byte]) {
  def boxId: String = box.boxId

  def collateral: Seq[NodeAsset] = box.assets.drop(1)

  /** The liquidation script R8 names, when this client knows it. */
  def liquidation: Option[LiquidationScript] = SigmaFiContracts.liquidationOf(liqScriptHash)
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
          singleton <- box.assets.headOption.filter(a => a.amount == 1L && a.tokenId.matches("[0-9a-fA-F]{64}"))
          asset <- bytes(box, 4).filter(b => b.length == 32 && Hex.toHexString(b).equalsIgnoreCase(singleton.tokenId))
          borrower <- bytes(box, 5).filter(_.length == 32)
          amounts <- register(box, 6, LongsType).collect { case c: sigma.Coll[Long @unchecked] => c.toArray }
          if amounts.length == 2
          maturity <- register(box, 7, IntType).collect { case v: Integer => v.intValue }
          liq <- bytes(box, 8).filter(_.length == 32)
        } yield SigmaFiBond(box, token, new ErgoId(asset), borrower, amounts(0), amounts(1), maturity, liq)
      }.toOption.flatten
    }
  }

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

  /** The ErgoTrees R4 lists as order hints. Bad entries are dropped. */
  def hintTrees(box: NodeBox): Seq[ErgoTree] =
    register(box, 4, TreesType).toSeq.flatMap {
      case c: sigma.Coll[sigma.Coll[Byte] @unchecked] => c.toArray.toSeq.flatMap(b => parse(Hex.toHexString(b.toArray)))
      case _ => Seq.empty
    }

  /** R4 order hint: `Coll[Coll[Byte]]`, each entry an ErgoTree's bytes. No contract reads it. */
  def hintValue(trees: ErgoTree*): ErgoValue[_] =
    ErgoValue.of(sigma.Colls.fromArray(trees.map(t => sigma.Colls.fromArray(t.bytes)).toArray)(
      sigma.collRType(sigma.ByteType)), ErgoType.collType(org.ergoplatform.appkit.scalaapi.scalaByteType))

  /**
   * The repayment token a bond hash names for a loan of `principalTokens`: Some(None) for an ERG loan paired
   * with the ERG bond, Some(Some(id)) for a loan of one token paired with that token's bond, otherwise None.
   * Orders do not check the pairing, so a mismatched one is never built on.
   */
  def pairedBond(bondHash: Array[Byte], principalTokens: Seq[(ErgoId, Long)]): Option[Option[ErgoId]] =
    principalTokens match {
      case Seq() if SigmaFiContracts.ergBond.hashedPropBytes.sameElements(bondHash) => Some(None)
      case Seq((id, _)) if SigmaFiContracts.tokenBond(id).hashedPropBytes.sameElements(bondHash) => Some(Some(id))
      case _ => None
    }

  /**
   * Whether `bond` is the one its bond asset's mint created: the asset's genesis box `genesis` is a genuine
   * borrower order, spent by the transaction that created the bond at output 1, with matching terms and
   * collateral. This is indexed historical evidence, not an on-chain proof or a storage-rent guarantee.
   */
  def verify(bond: SigmaFiBond, genesis: Option[IndexedBox]): Either[String, Unit] =
    for {
      g <- genesis.toRight("the node's index has no genesis box for its bond asset")
      _ <- Either.cond(g.boxId == bond.asset.toString, (), "the box found is not its bond asset's genesis box")
      originOrder <- order(g.box).toRight("its bond asset was not minted by a SigmaFi borrower order")
      _ <- Either.cond(g.spentTransactionId.contains(bond.box.transactionId), (),
        "it is not the bond its asset's mint created")
      _ <- Either.cond(bond.box.index == 1, (), "it is not its mint's second output")
      _ <- Either.cond(pairedBond(originOrder.terms.bondHash, originOrder.terms.principalTokens).contains(bond.repayToken), (),
        "its order's bond does not match its loan")
      _ <- Either.cond(matchesOrder(bond, originOrder, g.spendingHeight), (), "its terms or collateral do not match its originating order")
    } yield ()

  private def matchesOrder(b: SigmaFiBond, o: SigmaFiOrder, spendingHeight: Option[Int]): Boolean = {
    val t = o.terms
    val scheduleMatches = t.schedule match {
      case FixedMaturity(h) => b.maturity == h
      case OnClose(d) =>
        val latest = spendingHeight.getOrElse(b.box.creationHeight).toLong + d
        d >= SigmaFiContracts.MinOnCloseDuration && b.maturity.toLong <= latest &&
          b.maturity.toLong >= latest - SigmaFiContracts.OnCloseAllowance
    }
    hashOf(parse(b.box.ergoTree).get).sameElements(t.bondHash) &&
      b.borrowerHash.sameElements(t.borrowerHash) && b.repayment == t.repayment &&
      b.batchingFee == t.batchingFee && b.liqScriptHash.sameElements(t.liqScriptHash) &&
      b.box.value >= o.box.value && b.collateral == o.box.assets && scheduleMatches
  }

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
