package transactions.batching.arb

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.lithosdex.{LDT2TExecution, LithosDexTransactions}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.util.Try

/** What a leg trades on one side: ERG when `tokenId` is None, else that token. */
final case class ArbAsset(tokenId: Option[String]) {
  def isErg: Boolean = tokenId.isEmpty
  override def toString: String = tokenId.getOrElse("ERG")
}

object ArbAsset {
  final val Erg: ArbAsset = ArbAsset(None)
  def token(id: String): ArbAsset = ArbAsset(Some(id.toLowerCase))
}

/** The four pool kinds a leg can swap through. */
sealed abstract class ArbPoolKind(val label: String)

object ArbPoolKind {
  case object ErgoDexNative extends ArbPoolKind("ergodex-n2t")
  case object ErgoDexToken extends ArbPoolKind("ergodex-t2t")
  case object LithosDexNative extends ArbPoolKind("lithosdex-n2t")
  case object LithosDexToken extends ArbPoolKind("lithosdex-t2t")
}

/**
 * One swap priced against `pool`: `amountIn` of the pool's X when `xIn`, else of Y, for `amountOut` of the
 * other side, leaving the pool in state `after`. Every amount is what the pool's own contract checks.
 */
final case class ArbSwap(pool: ArbPool, amountIn: Long, xIn: Boolean, amountOut: Long, after: ArbPool) {
  def assetIn: ArbAsset = if (xIn) pool.x else pool.y
  def assetOut: ArbAsset = if (xIn) pool.y else pool.x

  /** How much the pool box's ERG changes: only a pool whose X is ERG takes or pays it. */
  def poolErgDelta: Long =
    if (assetIn.isErg) amountIn else if (assetOut.isErg) -amountOut else 0L
}

/**
 * A pool's state as a leg sees it, for every pool kind alike, so a search prices and builds any leg the same
 * way. Pricing never touches a box; building takes the box the state was read from, and `after` states carry
 * the id of the box they were read from until `withBox` names the successor that holds them.
 */
sealed trait ArbPool {
  def kind: ArbPoolKind

  /** The pool's identifying NFT. */
  def nft: String

  /** The box this state was read from. Empty for a state no box holds yet. */
  def boxId: String

  def x: ArbAsset
  def y: ArbAsset

  /** What `amountIn` of X (when `xIn`) or Y returns, or 0 when the pool cannot fill it. */
  def quote(amountIn: Long, xIn: Boolean): Long

  /**
   * Units out per unit in for a vanishing input, fees included: the slope of `quote` at zero. Rounding only
   * lowers what a swap returns, so no input returns more than this rate times itself.
   */
  def marginal(xIn: Boolean): Double

  /** The swap and the state it leaves, or None when the pool cannot fill it. */
  def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap]

  /** The same state, held by the box `boxId`. */
  def withBox(boxId: String): ArbPool

  /** The pool input as this kind's contract needs it spent: LithosDex pools read their op from var 0. */
  def input(poolBox: InputUTXO): InputUTXO

  /** The pool's successor at output 0, holding `after`, built from `poolBox`, the box being spent. */
  def successor(poolBox: InputUTXO, after: ArbPool): UTXO

  /** Whether `asset` is one of this pool's two sides. */
  def trades(asset: ArbAsset): Boolean = asset == x || asset == y
}

object ArbPool {
  /** A LithosDex pool's slope at zero: the side fee leaves the input first, then the curve keeps its own fee. */
  private[arb] def sideThenCurve(sideFeeNum: Long, dexFeeNum: Long, reserveIn: Long, reserveOut: Long): Double = {
    val d = LDHelpers.FEE_DENOM.toDouble
    if (reserveIn <= 0L) 0.0 else (d - sideFeeNum) / d * (dexFeeNum / d) * (reserveOut.toDouble / reserveIn)
  }
}

/**
 * An ErgoDEX pool of either shape: a native pool's X is its ERG, a token pool's X its third token. Prices with
 * the pool's own fee over 1,000, rounding down, exactly as order executions do.
 */
final case class ErgoDexArbPool(state: ErgoDexPool) extends ArbPool {

  override def kind: ArbPoolKind = if (state.xId.isDefined) ArbPoolKind.ErgoDexToken else ArbPoolKind.ErgoDexNative
  override def nft: String = state.nft
  override def boxId: String = state.boxId
  override val x: ArbAsset = state.xId.fold(ArbAsset.Erg)(ArbAsset.token)
  override val y: ArbAsset = ArbAsset.token(state.yId)

  override def quote(amountIn: Long, xIn: Boolean): Long = afterSwap(amountIn, xIn).fold(0L)(_._1)

  override def marginal(xIn: Boolean): Double = {
    val (in, out) = if (xIn) (state.reservesX, state.reservesY) else (state.reservesY, state.reservesX)
    if (in <= 0L) 0.0
    else out.toDouble * state.feeNum / (in.toDouble * transactions.batching.ergodex.ErgoDexContracts.FeeDenominator)
  }

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    afterSwap(amountIn, xIn).map { case (out, next) => ArbSwap(this, amountIn, xIn, out, copy(state = next)) }

  /** Refuses a swap that would take a native pool's ERG below the storage rent it must keep. */
  private def afterSwap(amountIn: Long, xIn: Boolean): Option[(Long, ErgoDexPool)] = {
    val out = state.outputAmount(amountIn, baseIsX = xIn)
    if (out <= 0L) None
    else Try(state.afterSwap(amountIn, out, baseIsX = xIn)).toOption
      .filter(next => next.reservesX > next.minReservesX && next.reservesY > 0)
      .map(out -> _)
  }

  override def withBox(boxId: String): ArbPool = copy(state = state.copy(boxId = boxId))

  override def input(poolBox: InputUTXO): InputUTXO = poolBox

  /** Script, registers and token order kept; a token pool's ERG never moves. */
  override def successor(poolBox: InputUTXO, after: ArbPool): UTXO = after match {
    case ErgoDexArbPool(next) =>
      require(next.nft == state.nft, s"the successor of pool ${state.nft} cannot hold pool ${next.nft}")
      UTXO(poolBox.contract, if (state.xId.isDefined) poolBox.value else next.reservesX,
        Seq(Token(ErgoId.create(state.nft), 1L),
          Token(ErgoId.create(state.lpId), next.lpSupply)) ++
          state.xId.map(id => Token(ErgoId.create(id), next.reservesX)).toSeq :+
          Token(ErgoId.create(state.yId), next.reservesY),
        poolBox.registers)
    case other => throw new IllegalArgumentException(s"an ErgoDEX pool cannot be succeeded by a ${other.kind.label} pool")
  }
}

/**
 * A LithosDex ERG/token pool of the deployment `contracts`. The side fee comes out of the input first, over
 * 10,000 and rounded down; the AMM fee then prices the rest, as `LDLiquidityPool.simSwap` does. A swap is
 * refused when it would leave the box at or under the contract's `MIN_RENT`, or either reserve at or under zero.
 */
final case class LithosDexArbPool(state: LDLiquidityPool, contracts: LDContracts, boxId: String) extends ArbPool {

  override def kind: ArbPoolKind = ArbPoolKind.LithosDexNative
  override def nft: String = state.poolNFT.toString
  override val x: ArbAsset = ArbAsset.Erg
  override val y: ArbAsset = ArbAsset.token(state.tokenY.toString)

  override def quote(amountIn: Long, xIn: Boolean): Long = swap(amountIn, xIn).fold(0L)(_.amountOut)

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    if (amountIn <= 0L) None
    else Try {
      val q = state.simSwap(amountIn, xIn)
      if (!q.isExecutable) None
      else Some(state.afterSwap(q))
        .filter(next => next.boxValue > LDHelpers.MIN_RENT && next.reservesX > 0L && next.reservesY > 0L)
        .map(next => ArbSwap(this, amountIn, xIn, q.amountOut, copy(state = next)))
    }.toOption.flatten

  override def marginal(xIn: Boolean): Double =
    ArbPool.sideThenCurve(if (xIn) state.ergFeeNum else state.tokenFeeNum, state.dexFeeNum,
      if (xIn) state.reservesX else state.reservesY, if (xIn) state.reservesY else state.reservesX)

  override def withBox(boxId: String): ArbPool = copy(boxId = boxId)

  override def input(poolBox: InputUTXO): InputUTXO = LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_SWAP)

  override def successor(poolBox: InputUTXO, after: ArbPool): UTXO = after match {
    case LithosDexArbPool(next, _, _) => LithosDexTransactions.poolUTXO(contracts, state, next)
    case other => throw new IllegalArgumentException(s"a LithosDex pool cannot be succeeded by a ${other.kind.label} pool")
  }
}

object LithosDexArbPool {
  /** The pool `box` holds, under the deployment `contracts` it was verified against. */
  def apply(box: InputUTXO, contracts: LDContracts): LithosDexArbPool =
    LithosDexArbPool(LDLiquidityPool(box), contracts, box.id.toString)
}

/**
 * A LithosDex token/token pool of the deployment `contracts`. Its ERG is storage and never moves. A swap is
 * refused when it would leave either reserve at or under zero, as the contract refuses it.
 */
final case class LithosDexT2TArbPool(state: LDT2TLiquidityPool, contracts: LDT2TContracts, boxId: String)
  extends ArbPool {

  override def kind: ArbPoolKind = ArbPoolKind.LithosDexToken
  override def nft: String = state.poolNFT.toString
  override val x: ArbAsset = ArbAsset.token(state.tokenX.toString)
  override val y: ArbAsset = ArbAsset.token(state.tokenY.toString)

  override def quote(amountIn: Long, xIn: Boolean): Long = swap(amountIn, xIn).fold(0L)(_.amountOut)

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    if (amountIn <= 0L) None
    else Try {
      val q = state.simSwap(amountIn, xIn)
      if (!q.isExecutable) None
      else Some(state.afterSwap(q))
        .filter(next => next.reservesX > 0L && next.reservesY > 0L)
        .map(next => ArbSwap(this, amountIn, xIn, q.amountOut, copy(state = next)))
    }.toOption.flatten

  override def marginal(xIn: Boolean): Double =
    ArbPool.sideThenCurve(if (xIn) state.xFeeNum else state.yFeeNum, state.dexFeeNum,
      if (xIn) state.reservesX else state.reservesY, if (xIn) state.reservesY else state.reservesX)

  override def withBox(boxId: String): ArbPool = copy(boxId = boxId)

  override def input(poolBox: InputUTXO): InputUTXO = LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_SWAP)

  override def successor(poolBox: InputUTXO, after: ArbPool): UTXO = after match {
    case LithosDexT2TArbPool(next, _, _) => LDT2TExecution.poolUTXO(contracts, poolBox, next)
    case other => throw new IllegalArgumentException(s"a LithosDex token pool cannot be succeeded by a ${other.kind.label} pool")
  }
}

object LithosDexT2TArbPool {
  /** The pool `box` holds, under the token/token deployment `contracts` it was verified against. */
  def apply(box: InputUTXO, contracts: LDT2TContracts): LithosDexT2TArbPool =
    LithosDexT2TArbPool(LDT2TLiquidityPool(box), contracts, box.id.toString)
}
