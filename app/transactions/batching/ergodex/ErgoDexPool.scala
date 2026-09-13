package transactions.batching.ergodex

import node.model.NodeBox

import scala.util.Try

/** Native pool balances: X is ERG, Y is a token, and lpSupply is the LP balance still held by the pool. */
final case class ErgoDexPool(nft: String,
                             lpId: String,
                             yId: String,
                             reservesX: Long,
                             reservesY: Long,
                             lpSupply: Long,
                             feeNum: Int,
                             boxId: String) {

  /** Constant-product output after the pool fee, using BigInt to avoid reserve-product overflow. */
  def outputAmount(baseAmount: Long, baseIsErg: Boolean): Long = {
    if (baseAmount <= 0) 0L
    else {
      val (reservesIn, reservesOut) =
        if (baseIsErg) (BigInt(reservesX), BigInt(reservesY)) else (BigInt(reservesY), BigInt(reservesX))
      val baseWithFee = BigInt(baseAmount) * feeNum
      val quote = reservesOut * baseWithFee /
        (reservesIn * ErgoDexContracts.FeeDenominator + baseWithFee)
      if (quote > Long.MaxValue) 0L else quote.toLong
    }
  }

  /** The pool as it stands after this swap, which is what the execution's pool output must hold. */
  def afterSwap(baseAmount: Long, quoteAmount: Long, baseIsErg: Boolean): ErgoDexPool =
    if (baseIsErg) copy(reservesX = Math.addExact(reservesX, baseAmount), reservesY = reservesY - quoteAmount)
    else copy(reservesX = reservesX - quoteAmount, reservesY = Math.addExact(reservesY, baseAmount))

  /** LP issued for a deposit, with excess ERG or tokens returned to the order owner. */
  def deposit(amountX: Long, amountY: Long): Option[(Long, ErgoDexPool)] = {
    val circulating = BigInt(Long.MaxValue) - lpSupply
    if (circulating <= 0 || amountX <= 0 || amountY <= 0) None
    else {
      val byX = BigInt(amountX) * circulating / reservesX
      val byY = BigInt(amountY) * circulating / reservesY
      val issued = byX.min(byY)
      val changeX = (byX - byY).max(BigInt(0)) * reservesX / circulating
      // The token-excess branch requires a second, nonempty reward token entry.
      val changeY = if (byX < byY) ((byY - byX) * reservesY / circulating).max(BigInt(1)) else BigInt(0)
      if (issued <= 0 || issued >= lpSupply ||
        (BigInt(amountY) - changeY) * circulating / reservesY < issued) None
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

  private def balances(x: BigInt, y: BigInt, lp: BigInt): Option[ErgoDexPool] =
    if (!x.isValidLong || !y.isValidLong || !lp.isValidLong ||
      x <= ErgoDexPool.MinStorageRent || y <= 0 || lp <= 0) None
    else Some(copy(reservesX = x.toLong, reservesY = y.toLong, lpSupply = lp.toLong))
}

object ErgoDexPool {

  final val MinStorageRent = 10000000L

  /** Checks the native script, token layout and fee register before reading pool balances. */
  def native(box: NodeBox): Option[ErgoDexPool] = {
    import ErgoDexContracts.NativePoolTokens._
    if (box.ergoTree != ErgoDexContracts.NativePoolErgoTree) None
    else if (box.assets.size != Count) None
    else {
      val nft = box.assets(Nft)
      val lp = box.assets(Lp)
      val y = box.assets(Y)
      // R4 stores the pool fee numerator over the shared denominator.
      val feeNum = box.additionalRegisters.get(4).flatMap(hex =>
        Try(org.ergoplatform.appkit.ErgoValue.fromHex(hex).getValue).toOption.collect {
          case i: java.lang.Integer => i.intValue()
        })
      feeNum.filter(fee => fee > 0 && fee <= ErgoDexContracts.FeeDenominator).flatMap { fee =>
        // The NFT identifies the pool and must be a singleton; the other two are balances.
        if (nft.amount != 1L || box.value <= 0 || lp.amount <= 0 || y.amount <= 0 ||
          box.assets.map(_.tokenId).distinct.size != Count) None
        else Some(ErgoDexPool(nft.tokenId, lp.tokenId, y.tokenId,
          reservesX = box.value, reservesY = y.amount, lpSupply = lp.amount,
          feeNum = fee, boxId = box.boxId))
      }
    }
  }
}
