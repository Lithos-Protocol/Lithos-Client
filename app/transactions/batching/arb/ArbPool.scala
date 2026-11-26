package transactions.batching.arb

import ergodexcl.ErgoDexClContracts
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.lithosdex.{DexContracts, LDT2TExecution, LithosDexTransactions}
import node.MutationConversions._
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaIntType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import pricing.{ClPosition, ClProtocol, ClSwap, SigmaUsdBank}
import sigma.ast.ErgoTree
import sigma.{Coll, Colls}
import sigmausd.SigmaUsdContracts
import utils.BoxMinimum
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

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

/** The pool kinds a leg can swap through: four AMM kinds, ErgoDEX CL positions and the SigmaUSD bank. */
sealed abstract class ArbPoolKind(val label: String)

object ArbPoolKind {
  case object ErgoDexNative extends ArbPoolKind("ergodex-n2t")
  case object ErgoDexToken extends ArbPoolKind("ergodex-t2t")
  case object LithosDexNative extends ArbPoolKind("lithosdex-n2t")
  case object LithosDexToken extends ArbPoolKind("lithosdex-t2t")
  case object ErgoDexCl extends ArbPoolKind("ergodex-cl")
  case object SigmaUsdBank extends ArbPoolKind("sigmausd-bank")
}

/**
 * One swap priced against `pool`: `amountIn` of the pool's X when `xIn`, else of Y, for `amountOut` of the
 * other side, leaving the pool in state `after`. Every amount is what the pool's own contract checks.
 * `protocolFee` is the part of `amountIn` paid to an output of its own rather than into the pool, and `feeBoxErg`
 * the ERG the carry pays beside `amountIn` so that output meets the box minimum; both are 0 but for CL positions.
 */
final case class ArbSwap(pool: ArbPool, amountIn: Long, xIn: Boolean, amountOut: Long, after: ArbPool,
                         protocolFee: Long = 0L, feeBoxErg: Long = 0L) {
  def assetIn: ArbAsset = if (xIn) pool.x else pool.y
  def assetOut: ArbAsset = if (xIn) pool.y else pool.x

  /** How much the pool box's ERG changes: only a pool whose X is ERG takes or pays it, less an ERG protocol fee. */
  def poolErgDelta: Long =
    if (assetIn.isErg) amountIn - protocolFee else if (assetOut.isErg) -amountOut else 0L
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

  /** Outputs a leg through this pool pays besides the successor, the carry and the profit, from the carry. */
  def feeOutputs(swap: ArbSwap): Seq[UTXO] = Seq.empty

  /** The pool input for a leg of `swap` whose [[feeOutputs]] sit at the output indices `feesAt`. */
  def legInput(poolBox: InputUTXO, swap: ArbSwap, feesAt: Seq[Int]): InputUTXO = input(poolBox)
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
 * The ErgoDEX CL config box as a leg reads it: the box, read by every leg as data input 0; the key protocol fees go to
 * (R4); the protocol fee's parameters (R5). `tokenFeeBox` is the ERG a fee output holding one token needs, and
 * `ergFeeBox` the least ERG a fee output of ERG alone may hold, both at the node's per-byte rate for the largest such box.
 */
final case class ClConfig(box: NodeBox, feeTo: Contract, protocol: ClProtocol, tokenFeeBox: Long, ergFeeBox: Long)

object ClConfig {

  /**
   * The config `box` holds, or None when it is not the config box or does not list the pinned swap script in R8, so a
   * delisting stops every CL leg. The fee boxes are sized at `perByte` nanoERG per byte.
   */
  def read(ctx: BlockchainContext, box: NodeBox, perByte: Long): Option[ClConfig] = Try {
    val feeTree = feeTreeOf(box).get
    val registers = box.registerValues
    require(registers.size >= 5 && registers(4).getType == ErgoType.collType(ErgoType.collType(ErgoType.byteType())))
    val listed = registers(4).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq.map(h => Hex.toHexString(h.toArray))
    require(listed.exists(_.equalsIgnoreCase(ErgoDexClContracts.SwapScriptHash)))
    val feeTo = Contract(ErgoTree.fromBytes(feeTree))
    val protocol = ClProtocol.of(ErgoDexClArbPool.longs(registers(1))).get
    // Sized at the largest value, token amount and height, so the box is never short whatever it ends up holding
    def least(tokens: Seq[Token]): Long = Math.multiplyExact(
      BoxMinimum.bytes(ctx, UTXO(feeTo, Long.MaxValue, tokens).setCreationHeight(Int.MaxValue)).toLong, perByte)
    ClConfig(box, feeTo, protocol,
      least(Seq(Token(ErgoId.create(ErgoDexClContracts.ConfigToken), Long.MaxValue))), least(Seq.empty))
  }.toOption

  /** The script protocol fees go to, in lower-case hex, from the config box's R4; None when it is not the config box. */
  def feeScript(box: NodeBox): Option[String] = feeTreeOf(box).map(Hex.toHexString)

  private def feeTreeOf(box: NodeBox): Option[Array[Byte]] = Try {
    require(box.assets.headOption.exists(a => a.tokenId == ErgoDexClContracts.ConfigToken && a.amount == 1L))
    val registers = box.registerValues
    require(registers.nonEmpty && registers.head.getType == ErgoType.collType(ErgoType.byteType()))
    registers.head.getValue.asInstanceOf[Coll[Byte]].toArray
  }.toOption
}

/**
 * One ErgoDEX CL position of the pinned deployment, priced by `position`'s rule with the protocol fee `config` names.
 * An ERG position's X is its ERG; a token/token position's X is its second token, and its ERG never moves. A leg
 * spends the position at input 0 as its own leader with the swap script in context variable 1, its successor at
 * output 0, the config box as data input 0, and the protocol fee to an output of its own at the config's key: ERG
 * when it at least meets the box minimum, else padded to it from the carry; a token in a box whose ERG the carry pays.
 * `amountIn` is everything the carry pays in the input asset, protocol fee included.
 */
final case class ErgoDexClArbPool(position: ClPosition, nft: String, boxId: String, x: ArbAsset, y: ArbAsset,
                                  config: ClConfig) extends ArbPool {

  override def kind: ArbPoolKind = ArbPoolKind.ErgoDexCl

  private def protocolRate: Long = config.protocol.rate(position.feePpm)

  override def quote(amountIn: Long, xIn: Boolean): Long = swap(amountIn, xIn).fold(0L)(_.amountOut)

  override def swap(amountIn: Long, xIn: Boolean): Option[ArbSwap] =
    position.swap(amountIn, xIn, protocolRate).map(arbSwap(_, xIn))

  override def swapFor(amountOut: Long, xIn: Boolean): Option[ArbSwap] =
    position.swapFor(amountOut, xIn, protocolRate).map(arbSwap(_, xIn))

  override def marginal(xIn: Boolean): Double = position.marginal(xIn)

  override def withBox(boxId: String): ArbPool = copy(boxId = boxId)

  override def input(poolBox: InputUTXO): InputUTXO = poolBox

  override def dataInputs(ctx: BlockchainContext): Seq[InputUTXO] = {
    val input = config.box.toInputUTXO(ctx)
    require(input.id.toString == config.box.boxId, s"CL config box ${config.box.boxId} reads back as ${input.id}")
    Seq(input)
  }

  override def feeOutputs(swap: ArbSwap): Seq[UTXO] =
    if (swap.protocolFee <= 0L) Seq.empty
    else swap.assetIn.tokenId match {
      case None => Seq(UTXO(config.feeTo, Math.addExact(swap.protocolFee, swap.feeBoxErg)))
      case Some(id) => Seq(UTXO(config.feeTo, swap.feeBoxErg, Seq(Token(ErgoId.create(id), swap.protocolFee))))
    }

  /** Its own leader at input 0, successor at output 0 (offset 0), the config at data input 0. */
  override def legInput(poolBox: InputUTXO, swap: ArbSwap, feesAt: Seq[Int]): InputUTXO =
    DexContracts.attachCtxVars(poolBox, Seq[(Byte, ErgoValue[_])](
      0.toByte -> ErgoValue.of(Colls.fromArray(Array(0, 0)), scalaIntType),
      1.toByte -> ErgoValue.of(Colls.fromArray(ErgoDexClArbPool.SwapScriptBytes), scalaByteType),
      2.toByte -> ErgoValue.of(0),
      3.toByte -> ErgoValue.of(Colls.fromArray(feesAt.toArray), scalaIntType),
      4.toByte -> ErgoValue.of(0)))

  /** Script, R4, token order and a token/token position's ERG kept; R5 holds the fees `after` keeps. */
  override def successor(poolBox: InputUTXO, after: ArbPool): UTXO = after match {
    case ErgoDexClArbPool(next, nextNft, _, _, _, _) =>
      require(nextNft == nft, s"the successor of CL position $nft cannot hold position $nextNft")
      val ids = poolBox.tokens.map(_.id)
      require(ids.headOption.exists(_.toString == nft) && poolBox.registers.nonEmpty,
        s"box ${poolBox.id} is not CL position $nft")
      val ergSide = ids.size == 2
      val value = if (ergSide) Math.addExact(Math.addExact(ErgoDexClArbPool.LockedErg, next.x), next.feesX) else poolBox.value
      val sides = (if (ergSide) Seq.empty else Seq(Token(ids(1), Math.addExact(Math.addExact(next.x, 1L), next.feesX)))) :+
        Token(ids.last, Math.addExact(Math.addExact(next.y, 1L), next.feesY))
      UTXO(poolBox.contract, value, Token(ids.head, 1L) +: sides,
        Seq(poolBox.registers.head, ErgoValue.of(Colls.fromArray(Array(next.feesX, next.feesY)), scalaLongType)))
    case other => throw new IllegalArgumentException(s"a CL position cannot be succeeded by a ${other.kind.label} pool")
  }

  private def arbSwap(s: ClSwap, xIn: Boolean): ArbSwap = {
    val feeBoxErg =
      if (s.protocolFee <= 0L) 0L
      else if ((if (xIn) x else y).isErg) math.max(0L, config.ergFeeBox - s.protocolFee)
      else config.tokenFeeBox
    ArbSwap(this, s.paid, xIn, s.out, copy(position = s.after), s.protocolFee, feeBoxErg)
  }
}

object ErgoDexClArbPool {

  /** nanoERG an ERG position locks outside its curve. */
  final val LockedErg: Long = 1000000L

  private val SwapScriptBytes: Array[Byte] = Hex.decode(ErgoDexClContracts.SwapScript)

  /**
   * The position `box` holds under `config`, or None when it is not one the pinned swap script prices: at the pinned
   * position script, its NFT first at one unit, two tokens (ERG/token) or three (token/token), R4 holding the curve's
   * offsets and LP fee and R5 its two fee balances, in a state the contract accepts.
   */
  def read(box: NodeBox, config: ClConfig): Option[ErgoDexClArbPool] = Try {
    require(box.ergoTree.equalsIgnoreCase(ErgoDexClContracts.PositionErgoTree))
    val assets = box.assets
    require((assets.size == 2 || assets.size == 3) && assets.head.amount == 1L)
    val registers = box.registerValues
    require(registers.size >= 2)
    val curve = longs(registers.head)
    val fees = longs(registers(1))
    require(curve.size >= 3 && fees.size >= 2)
    val ergSide = assets.size == 2
    val x = if (ergSide) box.value - LockedErg - fees.head else assets(1).amount - 1L - fees.head
    val position = ClPosition(x, assets.last.amount - 1L - fees(1), curve.head, curve(1), curve(2), fees.head, fees(1))
    require(position.valid)
    ErgoDexClArbPool(position, assets.head.tokenId, box.boxId,
      if (ergSide) ArbAsset.Erg else ArbAsset.token(assets(1).tokenId), ArbAsset.token(assets.last.tokenId), config)
  }.toOption

  /** The longs a Coll[Long] register holds. */
  private[arb] def longs(register: ErgoValue[_]): Seq[Long] = {
    require(register.getType == ErgoType.collType(ErgoType.longType()), s"a register holds ${register.getType}, not longs")
    register.getValue.asInstanceOf[Coll[Long]].toArray.toSeq
  }
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
