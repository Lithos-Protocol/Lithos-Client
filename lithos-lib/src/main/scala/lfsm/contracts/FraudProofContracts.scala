package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.Contract

object FraudProofContracts {

  def mkInvalidDiffContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidDiff"))
  }
  def mkInvalidFormatContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_TX_PROOF_MIN", LFSMHelpers.TX_PROOF_MIN)
      .item("CONST_TX_PROOF_MAX", LFSMHelpers.TX_PROOF_MAX)
      .item("CONST_TX_SIZE_MIN", LFSMHelpers.TX_SIZE_MIN)
      .item("CONST_COLLAT_BOX_MIN", LFSMHelpers.COLLAT_BOX_MIN)
      .item("CONST_NUM_LVLS_MAX", LFSMHelpers.NUM_LVLS_MAX)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_InvalidFormat"))
  }
  def mkNonUniqueHeadersContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NonUniqueHeaders"))
  }
  def mkNotInWindowContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_NotInWindow"))
  }

  def mkIncorrectNContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_IncorrectN"))
  }

  def mkMalformedGEContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_MalformedGE"))
  }

  def mkTransactionNotIncludedContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder.empty()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkFraudProofScript("FP_TransactionNotIncluded"))
  }

  /**
   * The only proof that reads the Miner Dictionary, so the only one needing its NFT and the MinerData
   * guard's hash. Compiling it compiles the guard and its logic underneath, so callers should cache.
   */
  def mkNonMatchingCommitmentContract(ctx: BlockchainContext, mdToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MINERDATA_PROPBYTES",
        Colls.fromArray(DictionaryContracts.mkMinerDataContract(ctx, mdToken).hashedPropBytes))
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .build()
    Contract.fromErgoScript(ctx, constants,
      ScriptGenerator.mkFraudProofScript("FP_NonMatchingCommitment"))
  }

  /**
   * Order is important here, as `Evaluator` uses ordering of this sequence to create fraud proof
   * transactions. Anything new is APPENDED, never inserted.
   */
  def getFraudProofContracts(ctx: BlockchainContext): Seq[Contract] = {
    Seq(
      mkInvalidFormatContract(ctx),
      mkMalformedGEContract(ctx),
      mkNotInWindowContract(ctx),
      mkNonUniqueHeadersContract(ctx),
      mkIncorrectNContract(ctx),
      mkInvalidDiffContract(ctx),
      mkTransactionNotIncludedContract(ctx),
      mkNonMatchingCommitmentContract(ctx, LFSMHelpers.getMDToken(ctx.getNetworkType))
    )
  }

}
