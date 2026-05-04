package plasmadex

import com.google.common.base.Charsets
import org.ergoplatform.appkit.{ContextVar, ErgoType, ErgoValue, Parameters}
import plasmadex.contracts.PlasmaDexContracts
import plasmadex.states.LiquidityState
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations._

// Withdraw Mutator, OUTPUTS(1) is feeReward box with correct registers and identity token
class WithdrawMutator(lp: LiquidityPool) extends Mutator{

  override val preReqs: Seq[TxContext => Boolean] = Seq(
    {(t: TxContext) => t.inputs.head.tokens.head.id == PDHelpers.getLPToken(t.ctx.getNetworkType) },
    {(t: TxContext) => t.inputs.head.id == lp.lpBox.id }
  )

  override protected def mutation(tCtx: TxContext): Seq[UTXO] = {

    val nextLP = lp.lpBox.toUTXO
      .subValue(lp.lsFeesX)
      .removeToken(lp.tokenY.id, lp.lsFeesY)
      .withReg(3, ErgoValue.of(Colls.fromItems(0L, 0L), ErgoType.longType()))
    val outputAmount = if(lp.lsFeesX > Parameters.MinFee) lp.lsFeesX else Parameters.MinFee
    val outputTokens = {
      if(lp.lsFeesY > 0)
        Seq(Token(lp.lpBox.id, 1), Token(lp.tokenY.id, lp.lsFeesY))
      else
        Seq(Token(lp.lpBox.id, 1))
    }
    val feeRewardOutput = UTXO(PlasmaDexContracts.mkFeeRewardContract(tCtx.ctx), outputAmount, outputTokens)
      .withReg(0, lp.lpBox.registers.head)
      .withReg(1, lp.lpBox.registers(1))
      .withReg(2, lp.lpBox.registers(3))
    Seq(nextLP, feeRewardOutput)
  }

  def getCtxLP: InputUTXO = lp.lpBox.withCtxVar(0.toByte, ErgoValue.of(3.toByte))


}

