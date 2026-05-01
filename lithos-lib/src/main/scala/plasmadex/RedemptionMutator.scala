package plasmadex

import org.ergoplatform.appkit.impl.Eip4TokenBuilder
import org.ergoplatform.appkit.{ContextVar, ErgoType, ErgoValue, Parameters}
import plasmadex.states.LiquidityState
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations._

// Redemption Mutator, input 1 is assumed to be funding the transaction. An NFT will be outputted as a box
// under the same contract as input 1. Any additional change will NOT go to this box.
// NOTE: amount is total amount of liquidity to add, relative to total liquidity in R5 of lpBox
class RedemptionMutator(lp: LiquidityPool, lqState: LiquidityState, lqNFT: Array[Byte]) extends Mutator{
  private val lookUp = lqState.dictionary.lookUp(lqNFT)
  private val liquidity = Longs.fromByteArray(lookUp.response.head.get)
  private val redemption = lp.simRedemption(liquidity)
  private val amntX = redemption._1.toLong
  private val amntY = redemption._2.toLong

  private var ctxLP: Option[InputUTXO] = None


  override val preReqs: Seq[TxContext => Boolean] = Seq(
    {(t: TxContext) => t.inputs.head.tokens.head.id == PDHelpers.getLPToken(t.ctx.getNetworkType) },
    {(t: TxContext) => t.inputs.head.id == lp.lpBox.id },
  )

  override protected def mutation(tCtx: TxContext): Seq[UTXO] = {

    val removal = lqState.dictionary.delete(lqNFT)

    ctxLP = Some(lp.lpBox
      .withCtxVar(ContextVar.of(0.toByte, 2.toByte))
      .withCtxVar(ContextVar.of(1.toByte,
          ErgoValue.of(Colls.fromArray(lqNFT),
            ErgoType.byteType())))
      .withCtxVar(ContextVar.of(2.toByte, lookUp.proof.ergoValue))
      .withCtxVar(ContextVar.of(3.toByte, removal.proof.ergoValue)))
    require(amntX < 0 && amntY < 0, "Redemption must produce positive ERG and tokens")
    val nextLP = lp.lpBox.toUTXO
      .subValue(-amntX)
      .removeToken(lp.tokenY.id, -amntY)
      .withReg(0, lqState.dictionary.ergoValue)
      .withReg(1, ErgoValue.of(lp.liquidity + liquidity))

    Seq(nextLP)
  }
  // Get LP Box with context vars associated with the given mutation
  // NOTE: Returns None until outputs for the given tx have been mutated
  def getCtxLP: Option[InputUTXO] = ctxLP
  def getAmountX: Long = amntX
  def getAmountY: Long = amntY
  def getLiqRemoved: Long = liquidity

}

