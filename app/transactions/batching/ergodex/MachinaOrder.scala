package transactions.batching.ergodex

import node.model.NodeBox
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** A Machina Finance order contract. */
sealed abstract class MachinaKind(val name: String)
object MachinaKind {
  /** ERG against a token, one way: R6 says whether the box sells its tokens or buys them. */
  case object Limit extends MachinaKind("e2t-limit")
  /** ERG against a token, both ways at once: R5 holds the ask and the bid. */
  case object Grid extends MachinaKind("e2t-grid")
  /** Token against token. Recognised only: a fill through a token pool leaves its gap in tokens. */
  case object TokenGrid extends MachinaKind("t2t-grid")
}

/**
 * The current Machina Finance templates, and only those: older versions had bugs since patched, so their
 * orders are never filled. Each carries the order's token id as constant 0; prices and the owner are registers.
 */
object MachinaContracts {

  /**
   * @param headerHex    the tree's header, size and constant count, then the token constant's type and length
   * @param constantsHex every constant after the token id, as every mainnet order of the kind carries them
   */
  final case class Template(kind: MachinaKind, templateHex: String, executable: Boolean,
                            headerHex: String = "", constantsHex: String = "") {
    lazy val templateHash: String = Hex.toHexString(Blake2b256(Hex.decode(templateHex)))

    /** The whole tree of an order for `tokenId`, so a box differing anywhere but its token is not taken for one. */
    def treeHex(tokenId: String): String = headerHex + tokenId + constantsHex + templateHex
  }

  val templates: Vector[Template] = Vector(
    Template(MachinaKind.Limit,
      "d804d601e30004d6027300d603860272027301d604e4c6a7040895e67201d807d605e4c6a70601d606b2a5e4720100d6" +
      "07b2db630872067302017203d6088c720702d609e4c6a70505d60a8c720701d60b8cb2db6308a7730301720302d1ed95" +
      "9572059172087304d801d60cc17206ed91720c730592720c7209ededededed93c27206c2a793720a720293e4c6720604" +
      "08720493e4c672060505720993e4c672060601720593e4c67206070ec5a7eded93c27206d0720493720a720293e4c672" +
      "06040ec5a7957205d801d60c99c17206c1a7ed91720c730692720c9c99720b72087209d801d60c997208720bed91720c" +
      "7307929c720c720999c1a7c172067204",
      executable = true, headerHex = "19b302080e20", constantsHex = "05000400040005000580897a05000500"),

    Template(MachinaKind.Grid,
      "d804d601e30104d6027300d603860272027301d604e4c6a7040895e67201d805d605b2a5e4720100d606b2db63087205" +
      "7302017203d607e4c6a70511d6088cb2db6308a7730301720302d6098c720602d1ededededed93c27205c2a7938c7206" +
      "01720293e4c672050408720493e4c672050511720793e4c67205060ec5a795e4e30001d801d60a99c17205c1a7ed9172" +
      "0a730492720a9c9972087209b27207730500d801d60a9972097208ed91720a7306929c720ab2720773070099c1a7c172" +
      "057204",
      executable = true, headerHex = "19f401080e20", constantsHex = "0500040004000500040005000402"),

    Template(MachinaKind.TokenGrid,
      "d804d601e30104d6027300d603860272027301d604e4c6a7040895e67201d80bd605b2a5e4720100d606db6308a7d607" +
      "b27206730200d608db63087205d609b27208730300d60ab272087304017203d60be4c6a70511d60c8c720902d60d8c72" +
      "0702d60e8cb27206730501720302d60f8c720a02d1ededededededed93c27205c2a793c17205c1a7938c7207018c7209" +
      "01938c720a01720293e4c672050408720493e4c672050511720b93e4c67205060ec5a795e4e30001d801d61099720c72" +
      "0ded91721073069272109c99720e720fb2720b730700d801d61099720f720eed9172107308929c7210b2720b73090099" +
      "720d720c7204",
      executable = false))

  lazy val executableByTemplateHash: Map[String, Template] =
    templates.filter(_.executable).map(t => t.templateHash -> t).toMap

  lazy val byTemplateHash: Map[String, Template] = templates.map(t => t.templateHash -> t).toMap

  /** The limit contract's SAFE_MIN_BOX_VALUE, which a buying box must keep more than to be recreated. */
  final val SafeMinBoxValue: Long = 1000000L

  /**
   * Least ERG a buying order's recreated box may keep. The limit contract recreates it only above
   * SAFE_MIN_BOX_VALUE and with enough to pay for one more unit, and otherwise pays out in full. The grid contract
   * sets no floor, so a grid box keeps SAFE_MIN_BOX_VALUE, well above its minimum value.
   */
  def childFloor(order: MachinaOrder, price: Long): Long = order.kind match {
    case MachinaKind.Limit => math.max(SafeMinBoxValue + 1, price)
    case _ => SafeMinBoxValue
  }
}

/**
 * An E2T Machina Finance order: a box trading `tokenId` against ERG at its own prices. `ask` is the nanoERG it
 * takes for a unit of its tokens and `bid` what it pays for one; a limit order has one of the two, a grid both.
 * `poolNft` is the ErgoDEX native pool a fill is routed through, chosen per discovery pass.
 */
final case class MachinaOrder(box: NodeBox,
                              kind: MachinaKind,
                              tokenId: String,
                              ownerPropBytes: Array[Byte],
                              ask: Option[Long],
                              bid: Option[Long],
                              poolNft: String = "") extends PoolOrder {

  /** The contracts bound no miner fee, so a broadcast fill pays up to the configured ceiling. */
  override def maxMinerFee: Long = Long.MaxValue

  /** Units of `tokenId` the box holds. */
  def tokens: Long = box.assets.headOption.map(_.amount).getOrElse(0L)

  def routedThrough(nft: String): MachinaOrder = copy(poolNft = nft)
}

object MachinaOrder {

  /**
   * An executable Machina order, or None for any other box, an older contract's, a malformed one, one whose tree
   * differs from its kind's anywhere but the token id, or one holding anything but its own token, which no honest
   * fill puts there.
   */
  def parse(box: NodeBox): Option[MachinaOrder] =
    Try(sigma.ast.ErgoTree.fromHex(box.ergoTree)).toOption.flatMap { tree =>
      val hash = Hex.toHexString(Blake2b256(tree.template))
      MachinaContracts.executableByTemplateHash.get(hash).flatMap { template =>
        for {
          token <- Try(tree.constants.head.value).toOption.collect { case c: sigma.Coll[_] =>
            Try(c.asInstanceOf[sigma.Coll[Byte]].toArray).toOption }.flatten
          if token.length == 32
          tokenId = Hex.toHexString(token)
          // Pricing assumes the contract's own constants, so a box that changed one would only fail at signing
          if box.ergoTree.equalsIgnoreCase(template.treeHex(tokenId))
          if box.value > 0 && box.assets.size <= 1 && box.assets.forall(a => a.tokenId == tokenId && a.amount > 0)
          owner <- register(box, 4).collect { case p: sigma.SigmaProp => p.propBytes.toArray }
          (ask, bid) <- prices(template.kind, box)
        } yield MachinaOrder(box, template.kind, tokenId, owner, ask, bid)
      }
    }

  /** The limit's one price, on the side R6 names; the grid's ask and bid, the first two entries of R5. */
  private def prices(kind: MachinaKind, box: NodeBox): Option[(Option[Long], Option[Long])] = kind match {
    case MachinaKind.Limit =>
      for {
        price <- register(box, 5).collect { case p: java.lang.Long => p.longValue() } if price > 0
        sells <- register(box, 6).collect { case s: java.lang.Boolean => s.booleanValue() }
      } yield if (sells) (Some(price), None) else (None, Some(price))
    case MachinaKind.Grid =>
      register(box, 5).collect { case c: sigma.Coll[_] => Try(c.toArray.toSeq.map(_.asInstanceOf[Long])).toOption }
        .flatten.filter(p => p.size >= 2 && p.head > 0 && p(1) > 0).map(p => (Some(p.head), Some(p(1))))
    case MachinaKind.TokenGrid => None
  }

  private def register(box: NodeBox, index: Int): Option[Any] =
    box.additionalRegisters.get(index).flatMap(hex =>
      Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption)
}

/**
 * One Machina fill against one pool state: `tokens` units bought from the box and sold into the pool when
 * `boxSells`, or bought from the pool and sold to the box otherwise. `gap` is the ERG left between the two legs,
 * which is the executor's. The order's child holds `childValue` and `childTokens`, at the owner's script when
 * `toOwner`.
 */
final case class MachinaTrade(tokens: Long, boxSells: Boolean, gap: Long, poolAfter: ErgoDexPool,
                              childValue: Long, childTokens: Long, toOwner: Boolean)

object MachinaPricing {

  /**
   * The fill of `order` through `pool` that leaves the most ERG, if any leaves some. Each side's gap is concave in
   * the size, so the real optimum of the constant-product formulas is solved for and its integer neighbours
   * compared, with the size bounds as candidates too.
   */
  def best(order: MachinaOrder, pool: ErgoDexPool): Option[MachinaTrade] =
    if (pool.xId.isDefined || pool.yId != order.tokenId) None
    else (sells(order, pool) ++ buys(order, pool)).filter(_.gap > 0).sortBy(t => (-t.gap, t.tokens)).headOption

  /** `tokens` units moved through `pool` on the given side, if the order and the pool both allow it. */
  def at(order: MachinaOrder, pool: ErgoDexPool, tokens: Long, boxSells: Boolean): Option[MachinaTrade] =
    Try {
      if (tokens <= 0) None
      else if (boxSells) order.ask.flatMap { price =>
        if (tokens > order.tokens) None
        else {
          val ergOut = pool.outputAmount(tokens, baseIsX = false)
          val paid = BigInt(tokens) * price
          val child = BigInt(order.box.value) + paid
          val after = pool.afterSwap(tokens, ergOut, baseIsX = false)
          if (!child.isValidLong || after.reservesX <= after.minReservesX) None
          // A limit box emptied of its tokens pays out to its owner; a grid box is recreated, empty or not
          else Some(MachinaTrade(tokens, boxSells = true, (BigInt(ergOut) - paid).toLong, after, child.toLong,
            order.tokens - tokens, toOwner = order.kind == MachinaKind.Limit && tokens == order.tokens))
        }
      } else order.bid.flatMap { price =>
        if (tokens >= pool.reservesY) None
        else {
          val (x, y, f) = (BigInt(pool.reservesX), BigInt(pool.reservesY), BigInt(pool.feeNum))
          // The least ERG the pool accepts for these tokens, rounded up
          val cost = (BigInt(tokens) * x * ErgoDexContracts.FeeDenominator + f * (y - tokens) - 1) /
            (f * (y - tokens))
          val received = BigInt(tokens) * price
          val child = BigInt(order.box.value) - received
          if (child < MachinaContracts.childFloor(order, price) || !(x + cost).isValidLong) None
          else Some(MachinaTrade(tokens, boxSells = false, (received - cost).toLong,
            pool.copy(reservesX = (x + cost).toLong, reservesY = pool.reservesY - tokens), child.toLong,
            Math.addExact(order.tokens, tokens), toOwner = false))
        }
      }
    }.toOption.flatten

  /** The box sells: k units at the ask, sold on into the pool. Optimum where the pool's marginal price is the ask. */
  private def sells(order: MachinaOrder, pool: ErgoDexPool): Seq[MachinaTrade] =
    order.ask.filter(_ > 0 && order.tokens > 0).toSeq.flatMap { price =>
      val (x, y, f) = (BigInt(pool.reservesX), BigInt(pool.reservesY), BigInt(pool.feeNum))
      val root = sqrt(x * y * ErgoDexContracts.FeeDenominator * f / price)
      sized(order, pool, (root - y * ErgoDexContracts.FeeDenominator) / f, 1L, order.tokens, boxSells = true)
    }

  /** The box buys: k units bought from the pool at the bid. Optimum where the pool's marginal cost is the bid. */
  private def buys(order: MachinaOrder, pool: ErgoDexPool): Seq[MachinaTrade] =
    order.bid.filter(_ > 0).toSeq.flatMap { price =>
      val (x, y, f) = (BigInt(pool.reservesX), BigInt(pool.reservesY), BigInt(pool.feeNum))
      val affordable = (order.box.value - MachinaContracts.childFloor(order, price)) / price
      val guess = y - sqrt(x * y * ErgoDexContracts.FeeDenominator / (f * price))
      sized(order, pool, guess, 1L, math.min(affordable, pool.reservesY - 1), boxSells = false)
    }

  /** Every candidate size near `guess`, and the bounds, priced exactly. */
  private def sized(order: MachinaOrder, pool: ErgoDexPool, guess: BigInt, lo: Long, hi: Long,
                    boxSells: Boolean): Seq[MachinaTrade] =
    if (hi < lo) Seq.empty
    else {
      val near = (-2 to 2).map(guess + _).filter(k => k >= lo && k <= hi).map(_.toLong)
      (near :+ lo :+ hi).distinct.flatMap(k => at(order, pool, k, boxSells))
    }

  private def sqrt(n: BigInt): BigInt = if (n <= 0) BigInt(0) else BigInt(n.bigInteger.sqrt())
}

/** How a priced Machina trade is laid out: the order's context vars and its child at output 1. */
object MachinaFill {

  /** The pool's successor is output 0, so the child goes next. */
  final val ChildIndex: Int = 1

  /**
   * The order input with the context vars its contract reads: the child's index, and for a grid the side, true
   * when the filler buys the box's tokens.
   */
  def input(orderInput: InputUTXO, order: MachinaOrder, trade: MachinaTrade): InputUTXO =
    input(orderInput, order, trade.boxSells, ChildIndex)

  /** The order input with its child at output `childIndex`; `boxSells` is the grid's side. */
  def input(orderInput: InputUTXO, order: MachinaOrder, boxSells: Boolean, childIndex: Int): InputUTXO =
    order.kind match {
      case MachinaKind.Limit => orderInput.withCtxVar(0.toByte, ErgoValue.of(childIndex))
      case _ => orderInput.withCtxVar(0.toByte, ErgoValue.of(boxSells)).withCtxVar(1.toByte, ErgoValue.of(childIndex))
    }

  def child(orderInput: InputUTXO, order: MachinaOrder, trade: MachinaTrade): UTXO =
    child(orderInput, order, trade.childValue, trade.childTokens, trade.toOwner)

  /**
   * The order's child holding `value` and `tokens` of the order's token, every register bound to the spent box's
   * id. Recreated, it keeps the order's script, owner and prices (and a limit's side); paid out to the owner when
   * `toOwner`, it carries the id alone, in R4.
   */
  def child(orderInput: InputUTXO, order: MachinaOrder, value: Long, tokens: Long, toOwner: Boolean): UTXO = {
    val binding = ErgoValue.of(orderInput.id.getBytes)
    val held = if (tokens > 0) Seq(Token(ErgoId.create(order.tokenId), tokens)) else Seq.empty
    if (toOwner) UTXO(Contract(sigma.ast.ErgoTree.fromBytes(order.ownerPropBytes)), value, held, Seq(binding))
    else {
      val kept = if (order.kind == MachinaKind.Limit) 3 else 2
      UTXO(orderInput.contract, value, held, orderInput.registers.take(kept) :+ binding)
    }
  }
}
