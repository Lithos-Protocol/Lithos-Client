package plasmadex

import com.google.common.base.Charsets
import org.ergoplatform.appkit.{ContextVar, ErgoType, ErgoValue, Parameters}
import plasmadex.states.LiquidityState
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations.{InputUTXO, Mutator, Token, TxContext, UTXO}
import work.lithos.plasma.collections.PlasmaMap

// Deposit Mutator, input 1 is assumed to be funding the transaction. An NFT will be outputted as a box
// under the same contract as input 1. Any additional change will NOT go to this box.
// NOTE: amount is total amount of liquidity to add, relative to total liquidity in R5 of lpBox
class DepositMutator(lp: LiquidityPool, lqState: LiquidityState, amount: Long) extends Mutator{
  private val depositOutput = lp.simDeposit(amount)
  private val amntX = depositOutput._1.toLong
  private val amntY = depositOutput._2.toLong
  private var ctxLP: Option[InputUTXO] = None
  def getAmountX: Long = amntX
  def getAmountY: Long = amntY

  override val preReqs: Seq[TxContext => Boolean] = Seq(
    {(t: TxContext) => t.inputs.head.tokens.head.id == PDHelpers.getLPToken(t.ctx.getNetworkType) },
    {(t: TxContext) => t.inputs.head.id == lp.lpBox.id },
    {(t: TxContext) => t.inputs.size >= 2 },
    {(t: TxContext) => t.inputs(1).value >= amntX + Parameters.MinFee },
    {(t: TxContext) => t.inputs(1).tokens.nonEmpty },
    {(t: TxContext) => t.inputs(1).tokens.head.id == lp.tokenY.id },
    {(t: TxContext) => t.inputs(1).tokens.head.amount >= amntY },
  )

  override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
    val insertion = lqState.dictionary.insert((lp.lpBox.id.getBytes, Longs.toByteArray(amount)))

    ctxLP = Some(lp.lpBox
      .withCtxVar(ContextVar.of(0.toByte, 1.toByte))
      .withCtxVar(ContextVar.of(1.toByte,
        ErgoValue.pairOf(ErgoValue.of(Colls.fromArray(lp.lpBox.id.getBytes),
          ErgoType.byteType()), ErgoValue.of(Colls.fromArray(Longs.toByteArray(amount)),
          ErgoType.byteType()))))
      .withCtxVar(ContextVar.of(2.toByte, insertion.proof.ergoValue)))
    val nextLP = lp.lpBox.toUTXO
      .addValue(amntX)
      .addToken(Token(lp.tokenY.id, amntY.toLong))
      .withReg(0, lqState.dictionary.ergoValue)
      .withReg(1, ErgoValue.of(lp.liquidity - amount))
    val nftOutput = UTXO(tCtx.inputs(1).contract, Parameters.MinFee, Seq(Token(lp.lpBox.id, 1)))
      .withReg(0, ErgoValue.of("Provision NFT".getBytes(Charsets.UTF_8)))
      .withReg(1, ErgoValue.of(s"{LP: ${lp.lpToken.id} (ERG/LIT), initERG: $amntX, initLIT: $amntY, liqDeposited:$amount, totalLiq:${lp.supplyLP0 + amount} height: ${tCtx.ctx.getHeight}}".getBytes(Charsets.UTF_8)))
      .withReg(2, ErgoValue.of("0".getBytes(Charsets.UTF_8)))
    Seq(nextLP, nftOutput)
  }
  // Get LP Box with context vars associated with the given mutation
  // NOTE: Returns None until outputs for the given tx have been mutated
  def getCtxLP: Option[InputUTXO] = ctxLP

}

