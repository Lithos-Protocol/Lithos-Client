package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderContracts, LDT2TOrderKind}
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.{ErgoTree, SigmaPropConstant}

import scala.util.Try

/** A token/token LithosDex order box and the terms read from its constants. */
sealed trait LDT2TOrder {
  def box: NodeBox
  def terms: LithosDexTerms
  def kind: LDT2TOrderKind

  def boxId: String = box.boxId
  def poolNft: String = terms.poolNft
}

object LDT2TOrder {

  /** Sell every unit of the one token the box holds for at least `minQuote` of the pool's other token. */
  final case class Swap(box: NodeBox, terms: LithosDexTerms, minQuote: Long) extends LDT2TOrder {
    override def kind: LDT2TOrderKind = LDT2TOrderKind.Swap
    def tokenId: String = box.assets.head.tokenId
    def amount: Long = box.assets.head.amount
  }

  /** Deposit every unit of token 0 as X and token 1 as Y, for at least `minShares`. */
  final case class Deposit(box: NodeBox, terms: LithosDexTerms, minShares: Long) extends LDT2TOrder {
    override def kind: LDT2TOrderKind = LDT2TOrderKind.Deposit
    def tokenX: String = box.assets.head.tokenId
    def amountX: Long = box.assets.head.amount
    def tokenY: String = box.assets(1).tokenId
    def amountY: Long = box.assets(1).amount
  }

  /** Close the provision owned by the one NFT the box holds. */
  final case class Redeem(box: NodeBox, terms: LithosDexTerms) extends LDT2TOrder {
    override def kind: LDT2TOrderKind = LDT2TOrderKind.Redeem
    def ownerNft: String = box.assets.head.tokenId
  }

  private lazy val kindsByTemplateHash: Map[String, LDT2TOrderKind] =
    LDT2TOrderKind.all.map(kind => LDOrderContracts.template(kind).templateHash -> kind).toMap

  /** Every token/token order template's bytes as hex. A tree ends with its template, so this finds orders cheaply. */
  lazy val templatesHex: Seq[String] =
    LDT2TOrderKind.all.map(kind => Hex.toHexString(LDOrderContracts.template(kind).templateBytes))

  /**
   * The order a box holds, or None for anything else: another script, a malformed tree, a term out of range,
   * a box shape its kind cannot execute, or a tree sharing an order's template while differing anywhere
   * outside the terms. The template hash is matched before anything else is parsed.
   */
  def parse(box: NodeBox): Option[LDT2TOrder] =
    Try {
      VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
        val tree = ErgoTree.fromHex(box.ergoTree)
        kindsByTemplateHash.get(Hex.toHexString(Blake2b256.hash(tree.template))).flatMap(read(box, _, tree))
      }
    }.toOption.flatten

  private def read(box: NodeBox, kind: LDT2TOrderKind, tree: ErgoTree): Option[LDT2TOrder] = {
    import LDOrderContracts._
    val template = LDOrderContracts.template(kind)
    val constants = tree.constants
    val values = template.termNames.flatMap(name => constants.lift(template.indexOf(name)).map(name -> _)).toMap
    def amountOf(name: String): Option[Long] = values.get(name).flatMap(LithosDexOrder.amountTerm)

    val terms = for {
      redeemer <- values.get(REDEEMER).collect { case SigmaPropConstant(prop) => prop.propBytes.toArray }
      nft <- values.get(POOL_NFT).flatMap(LithosDexOrder.bytesTerm) if nft.length == 32
      fee <- amountOf(EXECUTOR_FEE)
      maxMinerFee <- amountOf(MAX_MINER_FEE)
    } yield LithosDexTerms(Hex.toHexString(redeemer), Hex.toHexString(nft), fee, maxMinerFee)

    val order = terms.flatMap { t =>
      val assets = box.assets
      kind match {
        case LDT2TOrderKind.Swap if assets.size == 1 && assets.head.amount > 0 =>
          amountOf(MIN_QUOTE).map(Swap(box, t, _))
        case LDT2TOrderKind.Deposit if assets.size == 2 && assets.forall(_.amount > 0) &&
          assets.head.tokenId != assets(1).tokenId =>
          amountOf(MIN_SHARES).map(Deposit(box, t, _))
        case LDT2TOrderKind.Redeem if assets.size == 1 && assets.head.amount == 1L =>
          Some(Redeem(box, t))
        case _ => None
      }
    }

    // Terms reused as found, so the rebuild differs from the box only if something outside them does
    order.filter(_ => template.withValues(values).ergoTreeHex.equalsIgnoreCase(box.ergoTree))
  }
}
