package plasmadex

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Mutator, TxContext, UTXO}

// Swap Mutator, input 1 is assumed to be funding the transaction
// NOTE: amount is total amount of ERG or token to swap, including the lsFee which will be removed
class SwapMutator(lp: LiquidityPool, amount: Long, isERG: Boolean) extends Mutator{

  override val preReqs: Seq[TxContext => Boolean] = Seq(
    {(t: TxContext) => t.inputs.head.tokens.head.id == PDHelpers.getLPToken(t.ctx.getNetworkType) },
    {(t: TxContext) => t.inputs.head.id == lp.lpBox.id }
  )
  private val liqStateFee = lp.calcLSFee(amount, isERG)
  private val amntToSwap = amount - liqStateFee
  private val swapOutput = {
    if(isERG)
      lp.simSwapX(amntToSwap)
    else
      lp.simSwapY(amntToSwap)
  }
  private val nextLPBox = {
    if(isERG) {
      lp.lpBox.toUTXO.copy(
        value = lp.lpBox.value + amount,
        tokens = Seq(lp.lpToken, lp.tokenY - swapOutput),
        registers = lp.lpBox.registers.patch(3, Seq(
          ErgoValue.ofColl(Colls.fromItems(lp.lsFeesX + liqStateFee, lp.lsFeesY), ErgoType.longType())
        ), 1)
      )

    }else{
      lp.lpBox.toUTXO.copy(
        value = lp.lpBox.value - swapOutput,
        tokens = Seq(lp.lpToken, lp.tokenY + amount),
        registers = lp.lpBox.registers.patch(3, Seq(
          ErgoValue.ofColl(Colls.fromItems(lp.lsFeesX, lp.lsFeesY + liqStateFee), ErgoType.longType())
        ), 1)
      )

    }
  }

  def getSwapOutput: Long = swapOutput
  def getAmountSwapped: Long = amntToSwap
  def getCtxLP: InputUTXO = lp.lpBox.withCtxVar(0.toByte, ErgoValue.of(0.toByte))
  override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
    Seq(nextLPBox)
  }

}

