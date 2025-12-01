package lfsm.fraudproofs

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId}
import work.lithos.mutations.Contract

object FraudProofContracts {

  def mkInvalidDiffContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidDiff"))
  }
  def mkInvalidSizeContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidSize"))
  }
  def mkNonUniqueHeadersContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NonUniqueHeaders"))
  }
  def mkNotInWindowContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NotInWindow"))
  }

  // Order is important here, as `Evaluator` uses ordering of this sequence to create fraud proof transactions
  def getFraudProofContracts(ctx: BlockchainContext): Seq[Contract] = {
    Seq(
      mkInvalidSizeContract(ctx),
      mkNotInWindowContract(ctx),
      mkNonUniqueHeadersContract(ctx),
      mkInvalidDiffContract(ctx)
    )
  }

}
