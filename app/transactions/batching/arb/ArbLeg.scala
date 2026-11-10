package transactions.batching.arb

import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import transactions.batching.Batcher
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

/**
 * What a leg leaves in its carry box: everything the carry inputs held, less what went into the pool and the
 * miner fee, plus what came out. Tokens keep the inputs' order, a bought token joins at the end, and a token
 * spent to nothing leaves the list.
 */
final case class ArbCarry(value: Long, tokens: Seq[Token]) {
  def amountOf(asset: ArbAsset): Long = asset.tokenId match {
    case None => value
    case Some(id) => tokens.filter(_.id.toString == id).map(_.amount).sum
  }
}

/**
 * The kind every transaction of an arb-stage bundle carries in the block package, so the package says what each
 * bundle is and a refused candidate which builder made it. All four come from the `arb` candidate source.
 */
object ArbKind {
  /** A pool cycle whose every leg swaps through an ErgoDEX pool. */
  final val ErgoDexCycle = "arb"
  /** A pool cycle with at least one leg through a LithosDex pool. */
  final val LithosDexCycle = "arb-lithosdex"
  /** A Machina order filled through a LithosDex ERG pool, with no stake. */
  final val MachinaFill = "arb-machina"
  /** A Machina token/token grid's fill and the sale of its gap, both transactions. */
  final val Grid = "arb-grid"

  /** A pool cycle's kind, from the pools its legs swap through. */
  def cycle(pools: Seq[ArbPool]): String =
    if (pools.exists(p => p.kind == ArbPoolKind.LithosDexNative || p.kind == ArbPoolKind.LithosDexToken)) LithosDexCycle
    else ErgoDexCycle
}

/**
 * One direct swap: the pool at input 0 and its successor at output 0, as every pool kind requires, then the
 * carry box. The carry inputs follow the pool, and output 1 takes everything they held after the swap, less
 * any profit split off to output 2, so a chain of legs spends one box per leg and each leg balances to zero
 * change. With no miner fee the leg suits this miner's own block; a broadcast pays its fee from the carry.
 */
object ArbLeg {

  /**
   * The least ERG a carry box keeps. A leg that buys tokens holds them in the carry box, so its ERG funds that
   * box; an ERG input larger than the carry's value less this cannot be built.
   */
  final val MinCarryValue: Long = UTXO.MIN_CHANGE

  /** What `carry` holds after `swap` and `minerFee`, or None when it lacks the input or falls below the floor. */
  def carried(carry: ArbCarry, swap: ArbSwap, minerFee: Long): Option[ArbCarry] =
    if (swap.amountIn <= 0L || swap.amountOut <= 0L || minerFee < 0L || carry.amountOf(swap.assetIn) < swap.amountIn) None
    else {
      val ergIn = if (swap.assetIn.isErg) swap.amountIn else 0L
      val ergOut = if (swap.assetOut.isErg) swap.amountOut else 0L
      val value = BigInt(carry.value) - ergIn + ergOut - minerFee
      // Amounts as plain pairs first: a Token cannot hold zero, and a fully spent input reaches zero here
      val spent = carry.tokens.map { t =>
        t.id.toString -> (if (swap.assetIn.tokenId.contains(t.id.toString)) t.amount - swap.amountIn else t.amount)
      }
      val bought = swap.assetOut.tokenId.map { id =>
        if (spent.exists(_._1 == id)) spent.map { case (k, n) => k -> (if (k == id) Math.addExact(n, swap.amountOut) else n) }
        else spent :+ (id -> swap.amountOut)
      }.getOrElse(spent)
      if (!value.isValidLong || value < MinCarryValue) None
      else Some(ArbCarry(value.toLong, bought.collect { case (id, n) if n > 0L => Token(ErgoId.create(id), n) }))
    }

  /** The carry inputs' combined contents, in input order. */
  def holding(carry: Seq[InputUTXO]): ArbCarry =
    ArbCarry(carry.foldLeft(0L)((sum, box) => Math.addExact(sum, box.value)),
      carry.flatMap(_.tokens).foldLeft(Vector.empty[Token]) { (acc, t) =>
        val at = acc.indexWhere(_.id.toString == t.id.toString)
        if (at < 0) acc :+ t else acc.updated(at, acc(at).copy(amount = Math.addExact(acc(at).amount, t.amount)))
      })

  /**
   * Signs `swap` against `poolBox` with `carry` paying for it, the rest returned to `carryTo`. With `profit`,
   * that many nanoERG of the rest go to their own output 2 instead, at the contract given. Built for
   * `blockHeight`, or for the newest input's height when that is later. Throws when the swap is not priced
   * against `poolBox`, the carry cannot pay for it and keep its floor, or the pool's contract refuses the successor.
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, swap: ArbSwap, poolBox: InputUTXO, carry: Seq[InputUTXO],
            carryTo: Contract, blockHeight: Int, minerFee: Long = 0L,
            profit: Option[(Contract, Long)] = None): SignedTransaction = {
    require(poolBox.id.toString == swap.pool.boxId,
      s"swap priced against pool box ${swap.pool.boxId}, not ${poolBox.id}")
    require(carry.nonEmpty, "a leg needs a carry input to pay for its swap")
    val left = carried(holding(carry), swap, minerFee).getOrElse(throw new IllegalArgumentException(
      s"the carry cannot pay ${swap.amountIn} of ${swap.assetIn} and a $minerFee fee and keep $MinCarryValue"))
    val split = profit.fold(0L)(_._2)
    require(split >= 0L && left.value - split >= MinCarryValue,
      s"the carry holds ${left.value} nanoERG, too little to split off $split and keep $MinCarryValue")

    val spent = swap.pool.input(poolBox) +: carry
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))
    val poolOut = swap.pool.successor(poolBox, swap.after).setCreationHeight(height)
    require(poolOut.value - poolBox.value == swap.poolErgDelta,
      s"the successor moves ${poolOut.value - poolBox.value} nanoERG where the swap moves ${swap.poolErgDelta}")
    val carryOut = UTXO(carryTo, left.value - split, left.tokens).setCreationHeight(height)
    val profitOut = profit.filter(_._2 > 0L).map { case (to, amount) => UTXO(to, amount).setCreationHeight(height) }
    val feeOut = if (minerFee > 0L) Seq(UTXO.feeBox(minerFee).setCreationHeight(height)) else Seq.empty

    val unsigned = TxBuilder(ctx)
      .setInputs(spent: _*)
      .setOutputs((Seq(poolOut, carryOut) ++ profitOut ++ feeOut): _*)
      .buildTx(0L, wallet.p2pk)
    // Signing runs the pool's script, so a successor the contract refuses fails here.
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }
}
