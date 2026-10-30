package transactions.batching.ergodex

import node.model.NodeBox

import scala.util.Try

/**
 * Pool balances: lpSupply is the LP balance still held by the pool. A native pool's X is its ERG, and `xId`
 * is None; a token pool's X is the token `xId`, and `ergValue` is its ERG, which no order moves.
 */
final case class ErgoDexPool(nft: String,
                             lpId: String,
                             yId: String,
                             reservesX: Long,
                             reservesY: Long,
                             lpSupply: Long,
                             feeNum: Int,
                             boxId: String,
                             xId: Option[String] = None,
                             ergValue: Long = 0L) {

  def shape: PoolShape = if (xId.isDefined) PoolShape.Token else PoolShape.Native

  /** Constant-product output after the pool fee, using BigInt to avoid reserve-product overflow. */
  def outputAmount(baseAmount: Long, baseIsX: Boolean): Long = {
    if (baseAmount <= 0) 0L
    else {
      val (reservesIn, reservesOut) =
        if (baseIsX) (BigInt(reservesX), BigInt(reservesY)) else (BigInt(reservesY), BigInt(reservesX))
      val baseWithFee = BigInt(baseAmount) * feeNum
      val quote = reservesOut * baseWithFee /
        (reservesIn * ErgoDexContracts.FeeDenominator + baseWithFee)
      if (quote > Long.MaxValue) 0L else quote.toLong
    }
  }

  /** The pool as it stands after this swap, which is what the execution's pool output must hold. */
  def afterSwap(baseAmount: Long, quoteAmount: Long, baseIsX: Boolean): ErgoDexPool =
    if (baseIsX) copy(reservesX = Math.addExact(reservesX, baseAmount), reservesY = reservesY - quoteAmount)
    else copy(reservesX = reservesX - quoteAmount, reservesY = Math.addExact(reservesY, baseAmount))

  /**
   * LP issued for a deposit, with the excess side returned to the order owner. A token change entry cannot hold
   * zero, so on a token pool either side in excess returns at least one unit; a native pool returns ERG change
   * in the reward's value, which may be zero.
   */
  def deposit(amountX: Long, amountY: Long): Option[(Long, ErgoDexPool)] = {
    val circulating = BigInt(Long.MaxValue) - lpSupply
    if (circulating <= 0 || amountX <= 0 || amountY <= 0) None
    else {
      val byX = BigInt(amountX) * circulating / reservesX
      val byY = BigInt(amountY) * circulating / reservesY
      val issued = byX.min(byY)
      val changeX = if (byX > byY) ((byX - byY) * reservesX / circulating).max(BigInt(if (xId.isDefined) 1 else 0))
        else BigInt(0)
      // The token-excess branch requires a second, nonempty reward token entry.
      val changeY = if (byX < byY) ((byY - byX) * reservesY / circulating).max(BigInt(1)) else BigInt(0)
      if (issued <= 0 || issued >= lpSupply ||
        (BigInt(amountY) - changeY) * circulating / reservesY < issued ||
        (xId.isDefined && (BigInt(amountX) - changeX) * circulating / reservesX < issued)) None
      else balances(BigInt(reservesX) + amountX - changeX,
        BigInt(reservesY) + amountY - changeY, BigInt(lpSupply) - issued).map(issued.toLong -> _)
    }
  }

  /** Both reserves are returned in proportion to the LP tokens redeemed, rounded down. */
  def redeem(amountLP: Long): Option[(Long, ErgoDexPool)] = {
    val circulating = BigInt(Long.MaxValue) - lpSupply
    if (amountLP <= 0 || amountLP >= circulating) None
    else {
      val outX = BigInt(amountLP) * reservesX / circulating
      val outY = BigInt(amountLP) * reservesY / circulating
      if (outX <= 0 || outY <= 0) None
      else balances(BigInt(reservesX) - outX, BigInt(reservesY) - outY,
        BigInt(lpSupply) + amountLP).map(outX.toLong -> _)
    }
  }

  /** The least X a pool may keep: a native pool's X is its ERG, which must cover storage rent. */
  def minReservesX: Long = if (xId.isDefined) 0L else ErgoDexPool.MinStorageRent

  private def balances(x: BigInt, y: BigInt, lp: BigInt): Option[ErgoDexPool] =
    if (!x.isValidLong || !y.isValidLong || !lp.isValidLong ||
      x <= minReservesX || y <= 0 || lp <= 0) None
    else Some(copy(reservesX = x.toLong, reservesY = y.toLong, lpSupply = lp.toLong))
}

object ErgoDexPool {

  final val MinStorageRent = 10000000L

  /** Either shape, read from the box's script. */
  def parse(box: NodeBox): Option[ErgoDexPool] = native(box).orElse(token(box))

  /** Checks the native script, token layout and fee register before reading pool balances. */
  def native(box: NodeBox): Option[ErgoDexPool] = {
    import ErgoDexContracts.NativePoolTokens._
    if (box.ergoTree != ErgoDexContracts.NativePoolErgoTree) None
    else if (box.assets.size != Count) None
    else {
      val nft = box.assets(Nft)
      val lp = box.assets(Lp)
      val y = box.assets(Y)
      feeNumOf(box).flatMap { fee =>
        // The NFT identifies the pool and must be a singleton; the other two are balances.
        if (nft.amount != 1L || box.value <= 0 || lp.amount <= 0 || y.amount <= 0 ||
          box.assets.map(_.tokenId).distinct.size != Count) None
        else Some(ErgoDexPool(nft.tokenId, lp.tokenId, y.tokenId,
          reservesX = box.value, reservesY = y.amount, lpSupply = lp.amount,
          feeNum = fee, boxId = box.boxId))
      }
    }
  }

  /** The same checks for a token pool, whose X is its third token rather than its ERG. */
  def token(box: NodeBox): Option[ErgoDexPool] = {
    import ErgoDexContracts.TokenPoolTokens._
    if (box.ergoTree != ErgoDexContracts.TokenPoolErgoTree) None
    else if (box.assets.size != Count) None
    else {
      val nft = box.assets(Nft)
      val lp = box.assets(Lp)
      val x = box.assets(X)
      val y = box.assets(Y)
      feeNumOf(box).flatMap { fee =>
        if (nft.amount != 1L || box.value <= 0 || lp.amount <= 0 || x.amount <= 0 || y.amount <= 0 ||
          box.assets.map(_.tokenId).distinct.size != Count) None
        else Some(ErgoDexPool(nft.tokenId, lp.tokenId, y.tokenId,
          reservesX = x.amount, reservesY = y.amount, lpSupply = lp.amount,
          feeNum = fee, boxId = box.boxId, xId = Some(x.tokenId), ergValue = box.value))
      }
    }
  }

  /** R4 stores the pool fee numerator over the shared denominator, as an Int on both shapes. */
  private def feeNumOf(box: NodeBox): Option[Int] =
    box.additionalRegisters.get(4).flatMap(hex =>
      Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption.collect {
        case i: java.lang.Integer => i.intValue()
      }).filter(fee => fee > 0 && fee <= ErgoDexContracts.FeeDenominator)
}
