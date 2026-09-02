package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, Constants, ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, Mutator}

object DictionaryContracts {

  /**
   * The MinerData rules. Never sits on a box: rides in context var 64 and is executed by the guard,
   * which checks its hash first.
   */
  private def minerDataLogicConstants(mdToken: ErgoId): Constants =
    ConstantsBuilder.create()
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .item("CONST_ROLLUP_LIFETIME", LFSMHelpers.ROLLUP_LIFETIME)
      .item("CONST_EVICT_DELAY", LFSMHelpers.EVICT_DELAY)
      .build()

  def mkMinerDataLogicContract(networkType: NetworkType, mdToken: ErgoId): Contract =
    Contract.fromErgoScript(networkType, minerDataLogicConstants(mdToken),
      ScriptGenerator.mkDictionaryScript("MinerData_Logic"), Seq.empty[Mutator])

  def mkMinerDataLogicContract(ctx: BlockchainContext, mdToken: ErgoId): Contract =
    Contract.fromErgoScript(ctx, minerDataLogicConstants(mdToken),
      ScriptGenerator.mkDictionaryScript("MinerData_Logic"))

  /**
   * The script a MinerData box carries. Compiles the logic and injects its hash, so a guard can
   * never be built against logic this build does not also produce. Value bytes, not prop bytes: the
   * guard hashes what it executes. Compiles two scripts, so callers should cache.
   */
  def mkMinerDataContract(networkType: NetworkType, mdToken: ErgoId): Contract = {
    val logic = mkMinerDataLogicContract(networkType, mdToken)
    val constants = ConstantsBuilder.create()
      .item("CONST_MINERDATA_LOGIC_HASH", Colls.fromArray(logic.hashedValueBytes))
      .build()
    Contract.fromErgoScript(networkType, constants,
      ScriptGenerator.mkDictionaryScript("MinerData_Guard"), Seq.empty[Mutator])
  }

  def mkMinerDataContract(ctx: BlockchainContext, mdToken: ErgoId): Contract = {
    val logic = mkMinerDataLogicContract(ctx, mdToken)
    val constants = ConstantsBuilder.create()
      .item("CONST_MINERDATA_LOGIC_HASH", Colls.fromArray(logic.hashedValueBytes))
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkDictionaryScript("MinerData_Guard"))
  }

  private def dictionaryConstants(networkType: NetworkType, mdToken: ErgoId): Constants =
    ConstantsBuilder.create()
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_MINERDATA_PROPBYTES",
        Colls.fromArray(mkMinerDataContract(networkType, mdToken).hashedPropBytes))
      .item("CONST_MIN_DATA_BOX_AMNT", LFSMHelpers.MIN_DATA_BOX_AMNT)
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .item("CONST_DATA_LIFETIME", LFSMHelpers.DATA_LIFETIME)
      .item("CONST_EVICT_DELAY", LFSMHelpers.EVICT_DELAY)
      .item("CONST_REGISTER_SLACK", LFSMHelpers.REGISTER_SLACK)
      .build()

  def mkMinerDictionaryContract(networkType: NetworkType, mdToken: ErgoId): Contract =
    Contract.fromErgoScript(networkType, dictionaryConstants(networkType, mdToken),
      ScriptGenerator.mkDictionaryScript("MinerDictionary"), Seq.empty[Mutator])

  def mkMinerDictionaryContract(ctx: BlockchainContext, mdToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .item("CONST_MINERDATA_PROPBYTES",
        Colls.fromArray(mkMinerDataContract(ctx, mdToken).hashedPropBytes))
      .item("CONST_MIN_DATA_BOX_AMNT", LFSMHelpers.MIN_DATA_BOX_AMNT)
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .item("CONST_DATA_LIFETIME", LFSMHelpers.DATA_LIFETIME)
      .item("CONST_EVICT_DELAY", LFSMHelpers.EVICT_DELAY)
      .item("CONST_REGISTER_SLACK", LFSMHelpers.REGISTER_SLACK)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkDictionaryScript("MinerDictionary"))
  }
}
