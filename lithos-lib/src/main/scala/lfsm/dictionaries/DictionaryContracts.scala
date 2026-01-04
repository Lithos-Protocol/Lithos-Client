package lfsm.dictionaries

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ErgoId, NetworkType}
import sigma.Colls
import sigma.data.ProveDlog
import work.lithos.mutations.{Contract, Mutator}

object DictionaryContracts {

  def mkMinerDictionaryContract(networkType: NetworkType, mdToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MIN_DATA_BOX_AMNT", LFSMHelpers.MIN_DATA_BOX_AMNT)
      .item("CONST_MINERDATA_PROPBYTES", Colls.fromArray(mkMinerDataContract(networkType, mdToken).hashedPropBytes))
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkDictionaryScript("MinerDictionary"), Seq())
  }
  def mkMinerDataContract(networkType: NetworkType, mdToken: ErgoId): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_MIN_DATA_BOX_AMNT", LFSMHelpers.MIN_DATA_BOX_AMNT)
      .item("CONST_NISP_WINDOW", LFSMHelpers.NISP_WINDOW)
      .item("CONST_MD_ID", Colls.fromArray(mdToken.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkDictionaryScript("MinerData"), Seq())
  }



}
