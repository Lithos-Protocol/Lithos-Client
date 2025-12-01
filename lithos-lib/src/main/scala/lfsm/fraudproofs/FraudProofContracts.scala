package lfsm.fraudproofs

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId}
import work.lithos.mutations.Contract

object FraudProofContracts {

  def mkInvalidDiffContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidDiff"))
  }

  // Order is important here, as `Evaluator` uses ordering of this sequence to create fraud proof transactions
  def getFraudProofContracts(ctx: BlockchainContext): Seq[Contract] = {
    Seq(
      mkInvalidDiffContract(ctx)
    )
  }

}
