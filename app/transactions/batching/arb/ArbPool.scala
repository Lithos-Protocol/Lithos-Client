package transactions.batching.arb

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.lithosdex.{LDT2TExecution, LithosDexTransactions}
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import pricing.SigmaUsdBank
import sigmausd.SigmaUsdContracts
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

/** The pool kinds a leg can swap through: four AMM kinds and the SigmaUSD bank. */
sealed abstract class ArbPoolKind(val label: String)

object ArbPoolKind {
  case object ErgoDexNative extends ArbPoolKind("ergodex-n2t")
  case object ErgoDexToken extends ArbPoolKind("ergodex-t2t")
  case object LithosDexNative extends ArbPoolKind("lithosdex-n2t")
  case object LithosDexToken extends ArbPoolKind("lithosdex-t2t")
  case object SigmaUsdBank extends ArbPoolKind("sigmausd-bank")
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
   * Units out per unit in for a vanishing input, fees included: the slope of `quote` at zero. No input returns more
   * than this rate times itself, except where a truncated side fee lets one more unit reach the curve.
   */
  def marginal(xIn: Boolean): Double

  /** The swap and the state it leaves, or None when the pool cannot fill it. */
  def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap]

  /**
   * The least input that takes exactly `amountOut` out, and the state that leaves, or None when the pool cannot
   * pay that much. Every pool's contract checks its curve with an inequality, so a swap may take less than the
   * input buys; the rest stays in the pool.
   */
  def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap]

  /** The same state, held by the box `boxId`. */
  def withBox(boxId: String): ArbPool

  /** The pool input as this kind's contract needs it spent: LithosDex pools read their op from var 0. */
  def input(poolBox: InputUTXO): InputUTXO

  /** The pool's successor at output 0, holding `after`, built from `poolBox`, the box being spent. */
  def successor(poolBox: InputUTXO, after: ArbPool): UTXO

  /** Whether `asset` is one of this pool's two sides. */
  def trades(asset: ArbAsset): Boolean = asset == x || asset == y

  /** The NFT of the box this state is held by; several states held by one box share it, and a route takes one. */
  def holder: String = nft

  /** The other states the same box holds, in the same state as this one; a leg through this one moves them too. */
  def sharing: Seq[ArbPool] = Seq.empty

  /** Boxes a leg through this pool reads as data inputs, in order. */
  def dataInputs(ctx: BlockchainContext): Seq[InputUTXO] = Seq.empty

  /** Registers the leg's carry output (output 1) must hold for this pool's contract. */
  def carryRegisters(swap: ArbSwap): Seq[ErgoValue[_]] = Seq.empty
}

object ArbPool {
  /** `states` with `after` in place of its pool and of every state its box also holds. */
  def moved(states: Map[String, ArbPool], after: ArbPool): Map[String, ArbPool] =
    states ++ (after +: after.sharing).map(p => p.nft -> p)

  /** The NFT keys of `pool` and of every state its box also holds. */
  def keys(pool: ArbPool): Seq[String] = (pool +: pool.sharing).map(_.nft)
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

  override def marginal(xIn: Boolean): Double =
    if (xIn) state.curve.marginal(state.reservesX, state.reservesY) else state.curve.marginal(state.reservesY, state.reservesX)

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    afterSwap(amountIn, xIn).map { case (out, next) => ArbSwap(this, amountIn, xIn, out, copy(state = next)) }

  /** The least input the pool's curve accepts for exactly `amountOut`; the rest of what it would buy stays in the pool. */
  override def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap] = {
    val (in, out) = if (xIn) (state.reservesX, state.reservesY) else (state.reservesY, state.reservesX)
    Try {
      state.curve.in(in, out, amountOut)
        .map(cost => cost -> state.afterSwap(cost, amountOut, baseIsX = xIn))
        .filter { case (_, next) => next.reservesX > next.minReservesX && next.reservesY > 0 }
        .map { case (cost, next) => ArbSwap(this, cost, xIn, amountOut, copy(state = next)) }
    }.toOption.flatten
  }

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

  override def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap] =
    if (amountOut <= 0L) None
    else Try {
      state.simSwapForOutput(amountOut, xIn).filter(_.isExecutable).map(_.copy(amountOut = amountOut))
        .map(q => q -> state.afterSwap(q))
        .filter { case (_, next) => next.boxValue > LDHelpers.MIN_RENT && next.reservesX > 0L && next.reservesY > 0L }
        .map { case (q, next) => ArbSwap(this, q.amountIn, xIn, amountOut, copy(state = next)) }
    }.toOption.flatten

  override def marginal(xIn: Boolean): Double =
    state.curve(xIn).marginal(if (xIn) state.reservesX else state.reservesY, if (xIn) state.reservesY else state.reservesX)

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

  override def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap] =
    if (amountOut <= 0L) None
    else Try {
      state.simSwapForOutput(amountOut, xIn).filter(_.isExecutable).map(_.copy(amountOut = amountOut))
        .map(q => q -> state.afterSwap(q))
        .filter { case (_, next) => next.reservesX > 0L && next.reservesY > 0L }
        .map { case (q, next) => ArbSwap(this, q.amountIn, xIn, amountOut, copy(state = next)) }
    }.toOption.flatten

  override def marginal(xIn: Boolean): Double =
    state.curve(xIn).marginal(if (xIn) state.reservesX else state.reservesY, if (xIn) state.reservesY else state.reservesX)

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

/**
 * One side of the SigmaUSD bank: ERG against SigUSD when `sc`, else against SigRSV. X is ERG, so ERG in mints and the
 * coin in redeems, each at exactly what the bank's rule fixes; a mint takes only what its whole coins cost. Both sides
 * are one box, so a leg through one moves the other and a route takes at most one. A leg reads `oracle` as data
 * input 0, and its carry output is the contract's receipt: the change in circulation in R4, the reserve's in R5.
 */
final case class SigmaUsdArbPool(bank: SigmaUsdBank, sc: Boolean, boxId: String, oracle: NodeBox) extends ArbPool {

  override def kind: ArbPoolKind = ArbPoolKind.SigmaUsdBank
  override def nft: String = SigmaUsdArbPool.key(sc)
  override def holder: String = SigmaUsdContracts.BankNft
  override val x: ArbAsset = ArbAsset.Erg
  override val y: ArbAsset = ArbAsset.token(if (sc) SigmaUsdContracts.SigUsd else SigmaUsdContracts.SigRsv)

  override def quote(amountIn: Long, xIn: Boolean): Long = swap(amountIn, xIn).fold(0L)(_.amountOut)

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    if (xIn) bank.mintFor(sc, amountIn).map(ex => ArbSwap(this, ex.reserveDelta, xIn = true, ex.circDelta, moved(ex)))
    else bank.redeem(sc, amountIn).map(ex => ArbSwap(this, amountIn, xIn = false, -ex.reserveDelta, moved(ex)))

  /** The mint of exactly `amountOut` coins. A redeem pays what its coins fix, never a chosen amount, so it gives None. */
  override def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap] =
    if (!xIn) None
    else bank.mintOf(sc, amountOut).map(ex => ArbSwap(this, ex.reserveDelta, xIn = true, amountOut, moved(ex)))

  /** The fee-inclusive rate for one coin, a millionth over so no amount beats it; 0 while the operation is closed. */
  override def marginal(xIn: Boolean): Double = {
    val price = (if (sc) bank.scPrice else bank.rcPrice).toDouble
    val pct = SigmaUsdBank.FeePercent.toDouble
    if (xIn) bank.mintOf(sc, 1L).fold(0.0)(_ => 100.0 / ((100.0 + pct) * price) * 1.000001)
    else bank.redeem(sc, 1L).fold(0.0)(_ => price * (100.0 - pct) / 100.0 * 1.000001)
  }

  override def withBox(boxId: String): ArbPool = copy(boxId = boxId)

  override def sharing: Seq[ArbPool] = Seq(copy(sc = !sc))

  override def input(poolBox: InputUTXO): InputUTXO = poolBox

  override def dataInputs(ctx: BlockchainContext): Seq[InputUTXO] = {
    val input = oracle.toInputUTXO(ctx)
    require(input.id.toString == oracle.boxId, s"oracle box ${oracle.boxId} reads back as ${input.id}")
    Seq(input)
  }

  override def carryRegisters(swap: ArbSwap): Seq[ErgoValue[_]] = {
    val circDelta = if (swap.xIn) swap.amountOut else -swap.amountIn
    Seq(ErgoValue.of(circDelta), ErgoValue.of(swap.poolErgDelta))
  }

  /** The bank box with `after`'s reserve, coins and circulation, its script and token order kept. */
  override def successor(poolBox: InputUTXO, after: ArbPool): UTXO = after match {
    case SigmaUsdArbPool(next, _, _, _) =>
      val ids = poolBox.tokens.map(_.id)
      require(ids.size == 3 && ids(2).toString == SigmaUsdContracts.BankNft, s"box ${poolBox.id} is not the bank")
      UTXO(poolBox.contract, next.reserve,
        Seq(Token(ids.head, next.scTokens), Token(ids(1), next.rcTokens), Token(ids(2), 1L)),
        Seq(ErgoValue.of(next.scCirc), ErgoValue.of(next.rcCirc)))
    case other => throw new IllegalArgumentException(s"the SigmaUSD bank cannot be succeeded by a ${other.kind.label} pool")
  }

  private def moved(ex: SigmaUsdBank.Exchange): SigmaUsdArbPool = copy(bank = ex.after)
}

object SigmaUsdArbPool {

  /** The key a side goes by in routes and pool maps: the bank NFT and the coin, so the two sides differ. */
  def key(sc: Boolean): String = SigmaUsdContracts.BankNft + (if (sc) ":sigusd" else ":sigrsv")

  /**
   * Both sides of the bank `bankBox` holds, priced at the rate `oracleBox` holds, or nothing when either is not what
   * the contract reads: the bank at its pinned script with SigUSD, SigRSV and its NFT in that order and its two
   * circulation registers, and the oracle box carrying the oracle NFT first with a positive rate in R4.
   */
  def read(bankBox: NodeBox, oracleBox: NodeBox): Seq[SigmaUsdArbPool] = Try {
    require(bankBox.ergoTree.equalsIgnoreCase(SigmaUsdContracts.BankErgoTree))
    val ids = bankBox.assets.map(_.tokenId)
    require(ids == Seq(SigmaUsdContracts.SigUsd, SigmaUsdContracts.SigRsv, SigmaUsdContracts.BankNft) &&
      bankBox.assets(2).amount == 1L)
    require(oracleBox.assets.headOption.exists(a => a.tokenId == SigmaUsdContracts.OracleNft && a.amount == 1L))
    def long(box: NodeBox, at: Int): Long = {
      val values = box.registerValues
      require(values.size > at)
      values(at).getValue match {
        case v: java.lang.Long => v.longValue
        case other => throw new IllegalArgumentException(s"register R${at + 4} holds $other")
      }
    }
    val rate = SigmaUsdBank.rateOf(long(oracleBox, 0))
    require(rate > 0L)
    val bank = SigmaUsdBank(bankBox.value, long(bankBox, 0), long(bankBox, 1), bankBox.assets.head.amount,
      bankBox.assets(1).amount, rate)
    Seq(true, false).map(sc => SigmaUsdArbPool(bank, sc, bankBox.boxId, oracleBox))
  }.getOrElse(Seq.empty)
}
