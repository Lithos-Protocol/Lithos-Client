package lfsm.contracts

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, Constants, ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigma.data.ProveDlog
import work.lithos.mutations.{Contract, Mutator}

object RollupContracts {

  /**
   * The drain path refuses any transaction carrying a miner-fee output, which is what leaves a stuck
   * payout box claimable only by a block producer or someone paying one.
   *
   * `feeProposition` reads the miner key out of the context rather than baking it in, and takes no
   * network prefix, so the fee ergo tree — and therefore this hash — is a single global constant.
   * It is derived here rather than hard-coded so it cannot drift from the fee output the client's own
   * builders emit.
   */
  private def payoutConstants: Constants = ConstantsBuilder.create()
    .item("CONST_FEE_HASH", Colls.fromArray(Contract.FEE_720.hashedPropBytes))
    .build()

  def mkPayoutContract(ctx: BlockchainContext): Contract = {
    Contract.fromErgoScript(ctx, payoutConstants, ScriptGenerator.mkRollupScript("Payout"))
  }

  def mkPayoutContract(networkType: NetworkType): Contract = {
    Contract.fromErgoScript(networkType, payoutConstants, ScriptGenerator.mkRollupScript("Payout"), Seq.empty[Mutator])
  }

  def mkEvalContract(ctx: BlockchainContext, periodLength: Long, payoutBytes: Array[Byte], fpToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Evaluation"))
  }

  def mkEvalContract(networkType: NetworkType, periodLength: Long, payoutBytes: Array[Byte], fpToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkRollupScript("Evaluation"), Seq.empty[Mutator])
  }

  def mkHoldingContract(ctx: BlockchainContext, periodLength: Long, evalBytes: Array[Byte]): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", Colls.fromArray(evalBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Holding"))
  }

  def mkHoldingContract(networkType: NetworkType, periodLength: Long, evalBytes: Array[Byte]): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", Colls.fromArray(evalBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkRollupScript("Holding"), Seq.empty[Mutator])
  }

  def mkFPControlTestnetContract(ctx: BlockchainContext, proveDlog: ProveDlog): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_TESTNET_PK", proveDlog)
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("FP_Control_Testnet"))
  }

}
