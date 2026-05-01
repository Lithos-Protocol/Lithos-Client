package plasmadex.contracts

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, NetworkType}
import sigma.Colls
import work.lithos.mutations.{Contract, Mutator}

object PlasmaDexContracts {

  def mkLPContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_FEE_REWARD_HASH", Colls.fromArray(ScriptGenerator.mkSigTrue(ctx).hashedPropBytes))
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkPlasmaDexScript("LiquidityPool"))
  }

  def mkLPContract(networkType: NetworkType): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_FEE_REWARD_HASH", Colls.fromArray(Contract.SIGMA_TRUE.hashedPropBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkPlasmaDexScript("LiquidityPool"), Seq.empty[Mutator])
  }

  def mkFeeRewardContract(ctx: BlockchainContext): Contract = {
//    val constants = ConstantsBuilder
//      .create()
//      .item("CONST_FEE_REWARD_HASH", Colls.fromArray(ScriptGenerator.mkSigTrue(ctx).hashedPropBytes))
//      .build()
//    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkPlasmaDexScript("LiquidityPool"))
    ScriptGenerator.mkSigTrue(ctx)
  }

  def mkFeeRewardContract(networkType: NetworkType): Contract = {
//    val constants = ConstantsBuilder
//      .create()
//      .item("CONST_FEE_REWARD_HASH", Colls.fromArray(Contract.SIGMA_TRUE.hashedPropBytes))
//      .build()
//    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkPlasmaDexScript("LiquidityPool"), Seq.empty[Mutator])
    ScriptGenerator.mkSigTrue(networkType)
  }


}
