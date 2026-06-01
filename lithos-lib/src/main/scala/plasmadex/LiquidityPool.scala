package plasmadex

import sigma.{AvlTree, Coll}
import work.lithos.mutations.{InputUTXO, UTXO}
import work.lithos.plasma.collections.PlasmaMap

case class LiquidityPool(lpBox: InputUTXO) {
  val lpToken = lpBox.tokens.head
  val tokenY  = lpBox.tokens(1)

  val lqAVL: Array[Byte] = lpBox.parseReg[AvlTree](0).digest.toArray
  val liquidity: Long = lpBox.parseReg[Long](1)
  val feeParams: Array[Long] = lpBox.parseCollReg[Long](2)
  val feeInfo: Array[Long] = lpBox.parseCollReg[Long](3)

  val lsFeesX: Long = feeInfo.head
  val lsFeesY: Long = feeInfo(1)

  val reservesX: Long = lpBox.value - lsFeesX
  val reservesY: Long = tokenY.amount - lsFeesY
  val supplyLP0 = PDHelpers.MAX_LIQ - liquidity

  /**
   * Simulate the best deposit.
   * @param amounts Amount of X and Y to check the best deposit for
   * @return Tuple of minimum liquidity, amount X to deposit, and amount Y to deposit
   */
  def simBestDeposit(amounts: (BigInt, BigInt)): (BigInt, BigInt, BigInt) = {
    val deltaReservesX = amounts._1
    val deltaReservesY = amounts._2

    val sharesX = deltaReservesX * supplyLP0 / reservesX
    val sharesY = deltaReservesY * supplyLP0 / reservesY
    val minXY = minBigInt(sharesX, sharesY)
    if(minXY == sharesX)
      (minXY, deltaReservesX, (minXY * reservesY) / supplyLP0)
    else
      (minXY, (minXY * reservesX) / supplyLP0, deltaReservesY)
  }

  def simDeposit(amntLiq: Long): (BigInt, BigInt) = {
    val xNum = BigInt(amntLiq) * reservesX
    val yNum = BigInt(amntLiq) * reservesY
    val deltaReservesX = xNum / supplyLP0
    val deltaReservesY = yNum / supplyLP0
    val xRem = xNum % supplyLP0
    val yRem = yNum % supplyLP0
    (deltaReservesX + xRem) -> (deltaReservesY + yRem)
  }

  def simRedemption(liqRedeemed: Long): (BigInt, BigInt) = {
    val deltaSupplyLP = -liqRedeemed
    val xNum = BigInt(liqRedeemed) * reservesX
    val yNum = BigInt(liqRedeemed) * reservesY
    val deltaReservesX = (BigInt(deltaSupplyLP) * reservesX) / supplyLP0
    val deltaReservesY = (BigInt(deltaSupplyLP) * reservesY) / supplyLP0
    val xRem = xNum % supplyLP0
    val yRem = yNum % supplyLP0
    (deltaReservesX - xRem) -> (deltaReservesY - yRem)
  }
  // NOTE: amntX is amount of ERG to swap after lsFee is removed
  def simSwapX(amntX: Long): Long = {
    ((BigInt(reservesY) * amntX * feeParams.head) /
      (BigInt(reservesX) * PDHelpers.FEE_DENOM + BigInt(amntX) * feeParams.head)).toLong
  }
  // NOTE: amntY is amount of token to swap after lsFee is removed
  def simSwapY(amntY: Long): Long = {
    ((BigInt(reservesX) * amntY * feeParams.head) /
      (BigInt(reservesY) * PDHelpers.FEE_DENOM + BigInt(amntY) * feeParams.head)).toLong
  }

  def calcLSFee(amount: Long, isERG: Boolean): Long = {
    if (isERG) {
      ((BigInt(amount) * feeParams(1) ) / PDHelpers.FEE_DENOM).toLong
    }else {
      ((BigInt(amount) * feeParams(2) ) / PDHelpers.FEE_DENOM).toLong
    }
  }

  private def minBigInt(x: BigInt, y: BigInt) = {
    if(x <= y)
      x
    else
      y
  }

}
