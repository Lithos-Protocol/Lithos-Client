package lfsm.collateral

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId}
import sigma.Colls
import sigma.pow.Autolykos2PowValidation
import sigma.util.NBitsUtils
import work.lithos.mutations.Contract

object CollateralContract {
  // Emission Parameters used by Ergo
  final val FIXED_RATE_PERIOD = 525600L
  final val FIXED_RATE        = 75000000000L
  final val FOUND_INIT_RWRD   = 7500000000L
  final val EPOCH_LENGTH      = 64800L
  final val ONE_EPOCH_RED     = 3000000000L

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
}
