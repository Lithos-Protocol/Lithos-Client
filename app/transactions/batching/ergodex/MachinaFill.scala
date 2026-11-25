package transactions.batching.ergodex

import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import transactions.batching.Batcher
import transactions.batching.arb.{ArbAsset, ArbLeg, ArbPool, ArbSwap}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.util.Try

/**
 * One fill of `order` priced against `swap.pool`: `tokens` of the order's token bought from the box and sold into the
 * pool when `boxSells`, else bought from the pool and sold to the box. `gap` is what is left between the two in the
 * order's base asset, ERG or a token/token grid's base token, which the filler keeps. The child holds `childValue`
 * nanoERG, `childBase` of a grid's base token and `childTokens` of the order's token, at the owner's script when
 * `toOwner`.
 */
final case class MachinaFill(order: MachinaOrder, swap: ArbSwap, tokens: Long, boxSells: Boolean, gap: Long,
                             childValue: Long, childTokens: Long, toOwner: Boolean, childBase: Long = 0L)

/**
 * Machina Finance fills through any pool kind, priced and built the same way for the ErgoDEX batcher and the arb
 * stage.
 *
 * An ERG/token fill is `[pool, order] -> [successor, child, takings]`: the pool at input 0 and its successor at output
 * 0, the order at input 1 and its child at output 1, the gap in a box of its own at output 2. The order's contract
 * reads only itself and the output its context variable names, and every pool reads only itself at input 0 and its
 * successor at output 0, so a fill through a LithosDex pool is laid out as one through ErgoDEX.
 *
 * A token/token grid's gap is in its base token, so it goes into a carry instead: `[pool, carry, order] ->
 * [successor, carry, child]`, the child at output 2.
 */
object MachinaFill {

  /** Where an ERG/token fill's child goes: after the pool's successor. */
  final val ChildIndex: Int = 1

  /** Where a token/token grid's child goes: after the pool's successor and the carry. */
  final val CarryChildIndex: Int = 2

  /** Where a fill's gap goes. */
  sealed trait Takings

  object Takings {

    /**
     * A box of its own at `to`, output 2, holding `carried`'s ERG as well when a chain passes its takings on at input
     * 2, less `minerFee`, which a fee box after it pays.
     */
    final case class ToBox(to: Contract, carried: Option[InputUTXO] = None, minerFee: Long = 0L) extends Takings

    /** Into `carry`, passed through at input 1 and output 1 with the gap added in the order's base token. */
    final case class IntoCarry(carry: InputUTXO) extends Takings
  }

  /** What an order trades its token against: ERG for an ERG/token order, else the grid's base token. */
  def baseOf(order: MachinaOrder): ArbAsset = order.base.fold(ArbAsset.Erg)(ArbAsset.token)

  /** The least of its base asset a child may keep: a token/token grid's must hold one unit, being its first token. */
  private def floorOf(order: MachinaOrder, price: Long): Long =
    if (order.base.isEmpty) MachinaContracts.childFloor(order, price) else 1L

  /** `tokens` units of `order`'s token moved through `pool` on the given side, if the order and the pool allow it. */
  def at(order: MachinaOrder, pool: ArbPool, tokens: Long, boxSells: Boolean): Option[MachinaFill] = {
    val (base, quote) = (baseOf(order), ArbAsset.token(order.tokenId))
    if (tokens <= 0L || base == quote || !pool.trades(base) || !pool.trades(quote)) None
    else Try {
      val quoteIn = pool.x == quote
      val held = BigInt(order.baseHeld)
      def fill(swap: ArbSwap, gap: BigInt, kept: BigInt, childTokens: Long, toOwner: Boolean): Option[MachinaFill] =
        if (!gap.isValidLong || !kept.isValidLong) None
        else if (order.base.isEmpty)
          Some(MachinaFill(order, swap, tokens, boxSells, gap.toLong, kept.toLong, childTokens, toOwner))
        else Some(MachinaFill(order, swap, tokens, boxSells, gap.toLong, order.box.value, childTokens, toOwner, kept.toLong))
      if (boxSells) order.ask.flatMap { price =>
        if (tokens > order.tokens) None
        else pool.swap(tokens, xIn = quoteIn).flatMap { swap =>
          val paid = BigInt(tokens) * price
          // A limit box emptied of its tokens pays out to its owner; a grid box is recreated, empty or not
          fill(swap, BigInt(swap.amountOut) - paid, held + paid, order.tokens - tokens,
            toOwner = order.kind == MachinaKind.Limit && tokens == order.tokens)
        }
      } else order.bid.flatMap { price =>
        pool.swapFor(tokens, xIn = !quoteIn).flatMap { swap =>
          val received = BigInt(tokens) * price
          if (held - received < floorOf(order, price)) None
          else fill(swap, received - swap.amountIn, held - received, Math.addExact(order.tokens, tokens), toOwner = false)
        }
      }
    }.toOption.flatten
  }

  /**
   * The fill of `order` through `pool` that leaves the most of its base asset, if any leaves some. A side is skipped
   * when the pool's rate for a vanishing trade does not beat the order's price, since no size can then leave a gap.
   * Each side's gap is concave in the size, so it is sized by ternary search and the last few sizes tried one by one.
   */
  def best(order: MachinaOrder, pool: ArbPool): Option[MachinaFill] = {
    val (base, quote) = (baseOf(order), ArbAsset.token(order.tokenId))
    if (base == quote || !pool.trades(base) || !pool.trades(quote)) None
    else {
      val quoteIn = pool.x == quote
      val sells = order.ask.filter(price => order.tokens > 0L && pool.marginal(quoteIn) > price.toDouble)
        .flatMap(_ => sized(order.tokens)(at(order, pool, _, boxSells = true)))
      val buys = order.bid.filter(price => pool.marginal(!quoteIn) * price > 1.0)
        .flatMap(price => sized((order.baseHeld - floorOf(order, price)) / price)(at(order, pool, _, boxSells = false)))
      (sells.toSeq ++ buys).filter(_.gap > 0L).sortBy(f => (-f.gap, f.tokens)).headOption
    }
  }

  /** The best of `fill` over sizes 1 to `most`, by gap, ties to the smaller. */
  private def sized(most: Long)(fill: Long => Option[MachinaFill]): Option[MachinaFill] =
    if (most < 1L) None
    else {
      def gap(tokens: Long): Long = fill(tokens).fold(Long.MinValue)(_.gap)
      var (lo, hi) = (1L, most)
      while (hi - lo > 2L) {
        val third = (hi - lo) / 3L
        val (m1, m2) = (lo + third, hi - third)
        if (gap(m1) < gap(m2)) lo = m1 + 1L else hi = m2
      }
      (lo to hi).iterator.flatMap(fill(_)).foldLeft(Option.empty[MachinaFill]) { (kept, f) =>
        if (kept.forall(k => f.gap > k.gap)) Some(f) else kept
      }
    }

  /**
   * The order input with its child at output `childIndex` and the context vars its contract reads: the child's index,
   * and for a grid the side, true when the filler buys the box's tokens.
   */
  def input(orderInput: InputUTXO, order: MachinaOrder, boxSells: Boolean, childIndex: Int): InputUTXO =
    order.kind match {
      case MachinaKind.Limit => orderInput.withCtxVar(0.toByte, ErgoValue.of(childIndex))
      case _ => orderInput.withCtxVar(0.toByte, ErgoValue.of(boxSells)).withCtxVar(1.toByte, ErgoValue.of(childIndex))
    }

  /**
   * The order's child holding `value`, `base` of a token/token grid's base token first, and `tokens` of the order's
   * token, every register bound to the spent box's id. Recreated, it keeps the order's script, owner and prices (and
   * a limit's side); paid out to the owner when `toOwner`, it carries the id alone, in R4.
   */
  def child(orderInput: InputUTXO, order: MachinaOrder, value: Long, tokens: Long, toOwner: Boolean,
            base: Long = 0L): UTXO = {
    val binding = ErgoValue.of(orderInput.id.getBytes)
    val held = order.base.map(id => Token(ErgoId.create(id), base)).toSeq ++
      (if (tokens > 0) Seq(Token(ErgoId.create(order.tokenId), tokens)) else Seq.empty)
    if (toOwner) UTXO(Contract(sigma.ast.ErgoTree.fromBytes(order.ownerPropBytes)), value, held, Seq(binding))
    else {
      val kept = if (order.kind == MachinaKind.Limit) 3 else 2
      UTXO(orderInput.contract, value, held, orderInput.registers.take(kept) :+ binding)
    }
  }

  /**
   * Signs `fill` against `poolBox`, its gap going where `takings` says: an ERG/token order's to a box of its own, a
   * token/token grid's into a carry. Built for `blockHeight`, or for the newest input's height when that is later.
   * Throws when the fill is not priced against `poolBox`, the order box does not read back as its id, the takings do
   * not suit the order, or a contract refuses it.
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, fill: MachinaFill, poolBox: InputUTXO, takings: Takings,
            blockHeight: Int): SignedTransaction = {
    val swap = fill.swap
    val order = fill.order
    require(poolBox.id.toString == swap.pool.boxId, s"fill priced against pool box ${swap.pool.boxId}, not ${poolBox.id}")
    val boxInput = order.boxAsInput(ctx)
    require(boxInput.id.toString == order.boxId, s"the order box ${order.boxId} reads back as ${boxInput.id}")

    val spent = takings match {
      case Takings.ToBox(_, carried, minerFee) =>
        require(order.base.isEmpty, s"order ${order.boxId} trades a token against a token, which needs a carry")
        require(minerFee >= 0L, "a miner fee cannot be negative")
        Seq(swap.pool.input(poolBox), input(boxInput, order, fill.boxSells, ChildIndex)) ++ carried
      case Takings.IntoCarry(carry) =>
        require(order.base.nonEmpty, s"order ${order.boxId} trades a token against ERG, which needs no carry")
        Seq(swap.pool.input(poolBox), carry, input(boxInput, order, fill.boxSells, CarryChildIndex))
    }
    // Consensus refuses an output created below the newest input
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))
    val poolOut = swap.pool.successor(poolBox, swap.after).setCreationHeight(height)
    require(poolOut.value - poolBox.value == swap.poolErgDelta,
      s"the successor moves ${poolOut.value - poolBox.value} nanoERG where the fill moves ${swap.poolErgDelta}")
    val childOut = child(boxInput, order, fill.childValue, fill.childTokens, fill.toOwner, fill.childBase)
      .setCreationHeight(height)

    val outputs = takings match {
      case Takings.ToBox(to, carried, minerFee) =>
        val kept = Math.addExact(carried.map(_.value).getOrElse(0L), fill.gap - minerFee)
        Seq(poolOut, childOut, UTXO(to, kept).setCreationHeight(height)) ++
          (if (minerFee > 0L) Seq(UTXO.feeBox(minerFee).setCreationHeight(height)) else Seq.empty)
      case Takings.IntoCarry(carry) =>
        val base = order.base.get
        val held = ArbLeg.holding(Seq(carry))
        val tokens = if (held.tokens.exists(_.id.toString == base))
          held.tokens.map(t => if (t.id.toString == base) t.copy(amount = Math.addExact(t.amount, fill.gap)) else t)
        else held.tokens :+ Token(ErgoId.create(base), fill.gap)
        Seq(poolOut, UTXO(carry.contract, held.value, tokens).setCreationHeight(height), childOut)
    }

    // Signing runs every input's script, so a fill the pool's or the order's contract refuses fails here
    val unsigned = TxBuilder(ctx).setInputs(spent: _*).setOutputs(outputs: _*).buildTx(0L, wallet.p2pk)
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }
}
