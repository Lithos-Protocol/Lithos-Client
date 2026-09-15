package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderKind, LDOrderContracts}
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SBigInt, SType, SigmaPropConstant}
import sigma.data.CBigInt

import scala.util.Try

/**
 * Terms every LithosDex order carries.
 *
 * @param redeemerTree the owner's ErgoTree as hex: the reward output's script, and the refund key
 * @param poolNft      the pool the order names, as hex
 * @param executorFee  nanoERG the executor keeps, miner fee included
 * @param maxMinerFee  most nanoERG an execution may pay to the miner fee script
 */
final case class LithosDexTerms(redeemerTree: String, poolNft: String, executorFee: Long, maxMinerFee: Long)

/** A LithosDex order box and the terms read from its constants. */
sealed trait LithosDexOrder {
  def box: NodeBox
  def terms: LithosDexTerms
  def kind: LDOrderKind

  def boxId: String = box.boxId
  def poolNft: String = terms.poolNft
}

object LithosDexOrder {

  /** Sell `baseAmount` nanoERG for at least `minQuote` tokens. The box holds no tokens. */
  final case class SwapSell(box: NodeBox, terms: LithosDexTerms, baseAmount: Long, minQuote: Long)
    extends LithosDexOrder {
    override def kind: LDOrderKind = LDOrderKind.SwapSell
  }

  /** Sell every token the box holds, a single entry, for at least `minQuote` nanoERG after the fee. */
  final case class SwapBuy(box: NodeBox, terms: LithosDexTerms, minQuote: Long) extends LithosDexOrder {
    override def kind: LDOrderKind = LDOrderKind.SwapBuy
    def tokenId: String = box.assets.head.tokenId
    def amount: Long = box.assets.head.amount
  }

  /** Deposit up to `depositX` nanoERG and up to every token the box holds, a single entry, for at least `minShares`. */
  final case class Deposit(box: NodeBox, terms: LithosDexTerms, depositX: Long, minShares: Long) extends LithosDexOrder {
    override def kind: LDOrderKind = LDOrderKind.Deposit
    def tokenId: String = box.assets.head.tokenId
    def amount: Long = box.assets.head.amount
  }

  /** Close the provision owned by the one NFT the box holds. */
  final case class Redeem(box: NodeBox, terms: LithosDexTerms) extends LithosDexOrder {
    override def kind: LDOrderKind = LDOrderKind.Redeem
    def ownerNft: String = box.assets.head.tokenId
  }

  private lazy val kindsByTemplateHash: Map[String, LDOrderKind] =
    LDOrderKind.all.map(kind => LDOrderContracts.template(kind).templateHash -> kind).toMap

  /**
   * The order a box holds, or None for anything else: another script, a malformed tree, a term out of
   * range, a box shape its kind cannot execute, or a tree sharing an order's template while differing
   * anywhere outside the terms.
   */
  def parse(box: NodeBox): Option[LithosDexOrder] =
    Try {
      VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
        val tree = ErgoTree.fromHex(box.ergoTree)
        kindsByTemplateHash.get(Hex.toHexString(Blake2b256.hash(tree.template))).flatMap(read(box, _, tree))
      }
    }.toOption.flatten

  private def read(box: NodeBox, kind: LDOrderKind, tree: ErgoTree): Option[LithosDexOrder] = {
    import LDOrderContracts._
    val template = LDOrderContracts.template(kind)
    val constants = tree.constants
    val values = template.termNames.flatMap(name => constants.lift(template.indexOf(name)).map(name -> _)).toMap
    def amountOf(name: String): Option[Long] = values.get(name).flatMap(amountTerm)

    val terms = for {
      redeemer <- values.get(REDEEMER).collect { case SigmaPropConstant(prop) => prop.propBytes.toArray }
      nft <- values.get(POOL_NFT).flatMap(bytesTerm) if nft.length == 32
      fee <- amountOf(EXECUTOR_FEE)
      maxMinerFee <- amountOf(MAX_MINER_FEE)
    } yield LithosDexTerms(Hex.toHexString(redeemer), Hex.toHexString(nft), fee, maxMinerFee)

    val order = terms.flatMap { t =>
      val assets = box.assets
      kind match {
        case LDOrderKind.SwapSell if assets.isEmpty =>
          for {
            base <- amountOf(BASE_AMOUNT) if base > 0
            minQuote <- amountOf(MIN_QUOTE)
          } yield SwapSell(box, t, base, minQuote)
        case LDOrderKind.SwapBuy if assets.size == 1 && assets.head.amount > 0 =>
          amountOf(MIN_QUOTE).map(SwapBuy(box, t, _))
        case LDOrderKind.Deposit if assets.size == 1 && assets.head.amount > 0 =>
          for {
            depositX <- amountOf(DEPOSIT_X) if depositX > 0
            minShares <- amountOf(MIN_SHARES)
          } yield Deposit(box, t, depositX, minShares)
        case LDOrderKind.Redeem if assets.size == 1 && assets.head.amount == 1L =>
          Some(Redeem(box, t))
        case _ => None
      }
    }

    // Terms reused as found, so the rebuild differs from the box only if something outside them does:
    // a fixed constant, a constant's type, or the header. Such a box is not the contract this build signs.
    order.filter(_ => template.withValues(values).ergoTreeHex.equalsIgnoreCase(box.ergoTree))
  }

  /** A BigInt term as a non-negative Long. Anything outside that range is not an amount this client spends. */
  private def amountTerm(constant: Constant[SType]): Option[Long] =
    if (constant.tpe != SBigInt) None
    else constant.value match {
      case v: CBigInt =>
        val n = BigInt(v.wrappedValue)
        if (n >= 0 && n.isValidLong) Some(n.toLong) else None
      case _ => None
    }

  private def bytesTerm(constant: Constant[SType]): Option[Array[Byte]] =
    if (constant.tpe != SByteArray) None
    else constant.value match {
      case coll: sigma.Coll[_] => Try(coll.asInstanceOf[sigma.Coll[Byte]].toArray).toOption
      case _ => None
    }
}
