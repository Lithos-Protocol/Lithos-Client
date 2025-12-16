package lfsm.rollup

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId, NetworkType}
import sigma.Colls
import sigma.data.ProveDlog
import work.lithos.mutations.{Contract, Mutator}

object RollupContracts {


  def mkPayoutContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Payout"))
  }

  def mkPayoutContract(networkType: NetworkType): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkRollupScript("Payout"), Seq.empty[Mutator])
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
