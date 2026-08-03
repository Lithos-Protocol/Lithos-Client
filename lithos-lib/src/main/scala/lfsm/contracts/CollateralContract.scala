package lfsm.contracts

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Mutator, Token, TxContext, UTXO}

object CollateralContract {
  // Emission Parameters used by Ergo
  // TODO: Change to use correct params
  final val FIXED_RATE_PERIOD = 525600L
  final val FIXED_RATE        = 75000000000L
  final val FOUND_INIT_RWRD   = 7500000000L
  final val EPOCH_LENGTH      = 64800L
  final val ONE_EPOCH_RED     = 3000000000L

  // Emission Parameters for Lithos
  final val LIT_MAX_EPOCHS = 8
  final val LIT_EPOCH_LENGTH = 250000
  final val LIT_EPOCH_REDUCTION = 50 * Parameters.OneErg
  final val LIT_TAIL_EMISSION_RATE = 100 * Parameters.OneErg
  final val LIT_INITIAL_RATE = 500 * Parameters.OneErg
  // TODO: Add collateral control contract to enforce 1 per block
  def mkMainnetCollatContract(ctx: BlockchainContext, emissionId: ErgoId): Contract = {

    val constants = ConstantsBuilder
      .create()
      .item("_EMISSION_ID", Colls.fromArray(emissionId.getBytes))
      .item("_ROLLUP_HOLDING_HASH", Colls.fromArray(ScriptGenerator.mkSigTrue(ctx).hashedPropBytes))
      .item("_FIXED_RATE", FIXED_RATE)
      .item("_FIXED_RATE_PERIOD", FIXED_RATE_PERIOD)
      .item("_FOUNDER_INIT_REWARD", FOUND_INIT_RWRD)
      .item("_EPOCH_LENGTH", EPOCH_LENGTH)
      .item("_ONE_EPOCH_REDUCTION", ONE_EPOCH_RED)
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Collateral_Mainnet"))
  }

  def mkOfflineTestCollatContract(ctx: BlockchainContext, emissionId: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("_EMISSION_ID", Colls.fromArray(emissionId.getBytes))
      .item("_ROLLUP_HOLDING_HASH", Colls.fromArray(ScriptGenerator.mkSigTrue(ctx).hashedPropBytes))
      .item("_FIXED_RATE", FIXED_RATE)
      .item("_FIXED_RATE_PERIOD", FIXED_RATE_PERIOD)
      .item("_FOUNDER_INIT_REWARD", FOUND_INIT_RWRD)
      .item("_EPOCH_LENGTH", EPOCH_LENGTH)
      .item("_ONE_EPOCH_REDUCTION", ONE_EPOCH_RED)
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Collateral_Test"))
  }

  def mkTestnetCollatContract(ctx: BlockchainContext, holdingPropBytes: Array[Byte]): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("_ROLLUP_HOLDING_HASH", Colls.fromArray(holdingPropBytes))
      .item("_FIXED_RATE", FIXED_RATE)
      .item("_FIXED_RATE_PERIOD", FIXED_RATE_PERIOD)
      .item("_FOUNDER_INIT_REWARD", FOUND_INIT_RWRD)
      .item("_EPOCH_LENGTH", EPOCH_LENGTH)
      .item("_ONE_EPOCH_REDUCTION", ONE_EPOCH_RED)
      .build()

    //Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Collateral_Testnet"))
    Contract.SIGMA_TRUE
  }

  def mkEmissionsContract(ctx: BlockchainContext, collatPropBytes: Array[Byte]): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_COLLATERAL_HASH", Colls.fromArray(collatPropBytes))
      .item("CONST_MAX_EPOCHS", LIT_MAX_EPOCHS)
      .item("CONST_EPOCH_LENGTH", LIT_EPOCH_LENGTH)
      .item("CONST_EPOCH_REDUCTION", LIT_EPOCH_REDUCTION)
      .item("CONST_TAIL_EMISSIONS_RATE", LIT_TAIL_EMISSION_RATE)
      .item("CONST_INITIAL_RATE", LIT_INITIAL_RATE)
      .item("CONST_ERG_FIXED_RATE", FIXED_RATE)
      .item("CONST_ERG_FIXED_RATE_PERIOD", FIXED_RATE_PERIOD)
      .item("CONST_ERG_FOUNDER_INIT_REWARD", FOUND_INIT_RWRD)
      .item("CONST_ERG_EPOCH_LENGTH", EPOCH_LENGTH)
      .item("CONST_ERG_ONE_EPOCH_REDUCTION", ONE_EPOCH_RED)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("LIT_Emissions"))
  }

  def coinsToIssue(height: Long): Long = {
    val minersReward = FIXED_RATE - FOUND_INIT_RWRD
    val minersFixedRatePeriod = FIXED_RATE_PERIOD + 2 * EPOCH_LENGTH
    val epoch = 1 + (height - FIXED_RATE_PERIOD) / EPOCH_LENGTH

    if(height < minersFixedRatePeriod){
      minersReward
    }else{
      FIXED_RATE - (ONE_EPOCH_RED * epoch)
    }
  }

  def tokensToIssue(blocks: Int) = {
    val numReductions = blocks / LIT_EPOCH_LENGTH
    val amountReduced = numReductions * LIT_EPOCH_REDUCTION
    val emissionRate = {
      if(numReductions < LIT_MAX_EPOCHS)
        LIT_INITIAL_RATE - amountReduced
      else
        LIT_TAIL_EMISSION_RATE
    }
    emissionRate
  }

  def emissionMutator(collatContract: Contract, lenderPK: Contract, fee: Long): Mutator = new Mutator {
    override val preReqs: Seq[TxContext => Boolean] = Seq.empty[TxContext => Boolean]

    override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
      val emissionBox = tCtx.inputs.head
      val numBlocks = emissionBox.parseReg[Int](0)
      val emissionRate = tokensToIssue(numBlocks)
      val ergRate = coinsToIssue(tCtx.ctx.getHeight)
      val nextEmissionBox = emissionBox.toUTXO
        .removeToken(emissionBox.tokens(1).id, emissionRate)
        .withReg(0, ErgoValue.of(numBlocks+1))
      val nextCollatBox = UTXO(collatContract, ergRate, Seq(Token(emissionBox.tokens(1).id, emissionRate)),
        registers = Seq(ErgoValue.of(fee), ErgoValue.of(lenderPK.sigmaBoolean.get)))
      Seq(nextEmissionBox, nextCollatBox)
    }
  }
}
