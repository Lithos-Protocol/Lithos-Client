package transactions.batching.ergodex

import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import sigma.ast.{Constant, SType}

import scala.util.Try

/**
 * Native v1 order terms. Base amount is swap input, deposit ERG, or redeemed LP; liquidity orders pay a
 * fixed fee.
 */
final case class ErgoDexOrder(box: NodeBox,
                              contract: OrderContract,
                              poolNft: String,
                              redeemerPropBytes: Array[Byte],
                              baseAmount: Long,
                              minQuoteAmount: Long,
                              dexFeePerTokenNum: Long,
                              dexFeePerTokenDenom: Long,
                              maxMinerFee: Long,
                              quoteId: Option[String] = None,
                              fixedDexFee: Long = 0L) {

  /** Whether this swap spends ERG to receive the pool's Y token. */
  def baseIsErg: Boolean = contract.kind == OrderKind.SwapSell

  def boxId: String = box.boxId

  /** Reconstructs the order input from the reported box fields. */
  def boxAsInput(ctx: org.ergoplatform.appkit.BlockchainContext): work.lithos.mutations.InputUTXO = {
    import node.MutationConversions._
    box.toInputUTXO(ctx)
  }

  /** Executor revenue in nanoERG: a quote-based rate for swaps and a fixed fee for liquidity orders. */
  def dexFee(quoteAmount: Long): Long =
    if (contract.kind == OrderKind.Deposit || contract.kind == OrderKind.Redeem) fixedDexFee
    else if (dexFeePerTokenDenom <= 0) 0L
    else (BigInt(quoteAmount) * dexFeePerTokenNum / dexFeePerTokenDenom).min(BigInt(Long.MaxValue)).toLong
}

object ErgoDexOrder {

  /** Constant positions in the native v1 sell template. */
  private object SwapSellV1 {
    /** Also the redeemer: the reward box pays the key that could otherwise refund the order. */
    val RefundProp = 0
    val BaseAmount = 2
    val PoolNft = 8
    /** The token the reward must carry, which has to be the pool's Y token. */
    val QuoteId = 9
    val MinQuoteAmount = 10
    val DexFeePerTokenNum = 11
    val DexFeePerTokenDenom = 12
    /** The script embeds the base amount twice; both copies must agree. */
    val BaseAmountRepeat = 17
    val MaxMinerFee = 22
    val Size = 24
  }

  private object SwapBuyV1 {
    /** Also the redeemer: a buy order's reward box pays the key that could otherwise refund it. */
    val RefundProp = 0
    val DexFeePerTokenDenom = 5
    /** The script stores denominator minus numerator, so the numerator is the difference. */
    val DexFeeDenomLessNum = 6
    val PoolNft = 9
    val MinQuoteAmount = 10
    val MaxMinerFee = 19
    val Size = 21
  }

  private object DepositV1 {
    val RefundProp = 0
    val BaseAmount = 2
    val InitiallyLockedLP = 7
    val PoolNft = 12
    val DexFee = 15
    val BaseAmountRepeat = 16
    val DexFeeRepeat = 17
    val MaxMinerFee = 22
    val Size = 24
  }

  private object RedeemV1 {
    val RefundProp = 0
    val InitiallyLockedLP = 8
    val PoolNft = 11
    val DexFee = 12
    val MaxMinerFee = 16
    val Size = 18
  }

  /**
   * Identifies executable templates and reads their typed constants, returning None for malformed
   * orders.
   */
  def parse(box: NodeBox): Option[ErgoDexOrder] =
    Try(sigma.ast.ErgoTree.fromHex(box.ergoTree)).toOption.flatMap { tree =>
      val hash = Hex.toHexString(scorex.crypto.hash.Blake2b256(tree.template))
      ErgoDexContracts.executableByTemplateHash.get(hash).flatMap { contract =>
        val constants = Try(tree.constants).getOrElse(IndexedSeq.empty)
        contract.kind match {
          case OrderKind.SwapSell => swapSell(box, contract, constants)
          case OrderKind.SwapBuy => swapBuy(box, contract, constants)
          case OrderKind.Deposit => deposit(box, contract, constants)
          case OrderKind.Redeem => redeem(box, contract, constants)
        }
      }
    }

  private def swapSell(box: NodeBox, contract: OrderContract,
                       constants: Seq[Constant[SType]]): Option[ErgoDexOrder] = {
    import SwapSellV1._
    if (constants.size != Size) None
    else for {
      baseAmount <- longAt(constants, BaseAmount)
      repeated <- longAt(constants, BaseAmountRepeat)
      // Both embedded copies of the base amount must agree.
      if repeated == baseAmount
      poolNft <- bytesAt(constants, PoolNft) if poolNft.length == 32
      quoteId <- bytesAt(constants, QuoteId) if quoteId.length == 32
      redeemer <- propBytesAt(constants, RefundProp)
      minQuote <- longAt(constants, MinQuoteAmount)
      feeNum <- longAt(constants, DexFeePerTokenNum)
      feeDenom <- longAt(constants, DexFeePerTokenDenom)
      maxMinerFee <- longAt(constants, MaxMinerFee)
      order <- build(box, contract, poolNft, redeemer, baseAmount, minQuote, feeNum, feeDenom, maxMinerFee)
    } yield order.copy(quoteId = Some(Hex.toHexString(quoteId)))
  }

  private def swapBuy(box: NodeBox, contract: OrderContract,
                      constants: Seq[Constant[SType]]): Option[ErgoDexOrder] = {
    import SwapBuyV1._
    if (constants.size != Size) None
    else for {
      // A buy spends a token, and which token and how much is on the box rather than in the script.
      baseToken <- box.assets.headOption if box.assets.size == 1
      poolNft <- bytesAt(constants, PoolNft) if poolNft.length == 32
      redeemer <- propBytesAt(constants, RefundProp)
      minQuote <- longAt(constants, MinQuoteAmount)
      feeDenom <- longAt(constants, DexFeePerTokenDenom)
      denomLessNum <- longAt(constants, DexFeeDenomLessNum)
      feeNum = feeDenom - denomLessNum
      maxMinerFee <- longAt(constants, MaxMinerFee)
      order <- build(box, contract, poolNft, redeemer, baseToken.amount, minQuote, feeNum, feeDenom, maxMinerFee)
    } yield order
  }

  private def deposit(box: NodeBox, contract: OrderContract,
                      constants: Seq[Constant[SType]]): Option[ErgoDexOrder] = {
    import DepositV1._
    if (constants.size != Size || box.assets.size != 1 || box.assets.head.amount <= 0) None
    else for {
      baseAmount <- longAt(constants, BaseAmount) if baseAmount > 0
      repeated <- longAt(constants, BaseAmountRepeat) if repeated == baseAmount
      supply <- longAt(constants, InitiallyLockedLP) if supply == Long.MaxValue
      poolNft <- bytesAt(constants, PoolNft) if poolNft.length == 32
      redeemer <- propBytesAt(constants, RefundProp)
      fee <- longAt(constants, DexFee) if fee >= 0
      repeatedFee <- longAt(constants, DexFeeRepeat) if repeatedFee == fee
      maxMinerFee <- longAt(constants, MaxMinerFee) if maxMinerFee >= 0
    } yield ErgoDexOrder(box, contract, Hex.toHexString(poolNft), redeemer,
      baseAmount, 1L, 0L, 1L, maxMinerFee, fixedDexFee = fee)
  }

  private def redeem(box: NodeBox, contract: OrderContract,
                     constants: Seq[Constant[SType]]): Option[ErgoDexOrder] = {
    import RedeemV1._
    if (constants.size != Size || box.assets.size != 1 || box.assets.head.amount <= 0) None
    else for {
      supply <- longAt(constants, InitiallyLockedLP) if supply == Long.MaxValue
      poolNft <- bytesAt(constants, PoolNft) if poolNft.length == 32
      redeemer <- propBytesAt(constants, RefundProp)
      fee <- longAt(constants, DexFee) if fee >= 0
      maxMinerFee <- longAt(constants, MaxMinerFee) if maxMinerFee >= 0
    } yield ErgoDexOrder(box, contract, Hex.toHexString(poolNft), redeemer,
      box.assets.head.amount, 1L, 0L, 1L, maxMinerFee, fixedDexFee = fee)
  }

  /** Validates the amount, fee rate and miner fee cap shared by both swap directions. */
  private def build(box: NodeBox, contract: OrderContract, poolNft: Array[Byte],
                    redeemer: Array[Byte], baseAmount: Long, minQuote: Long,
                    feeNum: Long, feeDenom: Long, maxMinerFee: Long): Option[ErgoDexOrder] =
    if (baseAmount <= 0 || minQuote <= 0 || maxMinerFee < 0) None
    else if (feeDenom <= 0 || feeNum < 0) None
    // Buys pay a fraction of ERG output; sells pay ERG per token and may have a rate above one.
    else if (contract.kind == OrderKind.SwapBuy && feeNum > feeDenom) None
    else Some(ErgoDexOrder(box, contract, Hex.toHexString(poolNft), redeemer,
      baseAmount, minQuote, feeNum, feeDenom, maxMinerFee))

  private def longAt(constants: Seq[Constant[SType]], index: Int): Option[Long] =
    at(constants, index).collect { case value: java.lang.Long => value.longValue() }

  private def bytesAt(constants: Seq[Constant[SType]], index: Int): Option[Array[Byte]] =
    at(constants, index).collect { case coll: sigma.Coll[_] =>
      Try(coll.asInstanceOf[sigma.Coll[Byte]].toArray).toOption
    }.flatten

  private def propBytesAt(constants: Seq[Constant[SType]], index: Int): Option[Array[Byte]] =
    at(constants, index).collect { case prop: sigma.SigmaProp => Try(prop.propBytes.toArray).toOption }.flatten

  private def at(constants: Seq[Constant[SType]], index: Int): Option[Any] =
    if (index < 0 || index >= constants.size) None else Option(constants(index).value)
}
