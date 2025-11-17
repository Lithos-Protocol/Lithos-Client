package lfsm.rollup

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId}
import sigma.Colls
import work.lithos.mutations.Contract

object RollupContracts {


  def mkPayoutContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Payout"))
  }

  def mkEvalContract(ctx: BlockchainContext, periodLength: Long, payoutBytes: Array[Byte], fpToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", payoutBytes)
      .item("CONST_FP_ID", fpToken.getBytes)
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Evaluation"))
  }

  def mkHoldingContract(ctx: BlockchainContext, periodLength: Long, evalBytes: Array[Byte]): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", evalBytes)
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Holding"))
  }

}
