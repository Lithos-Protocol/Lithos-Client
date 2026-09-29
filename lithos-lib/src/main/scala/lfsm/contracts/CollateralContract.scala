package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.appkit.scalaapi._
import sigma.Colls
import work.lithos.mutations.Contract

object CollateralContract {

  def mkMainnetCollatContract(ctx: BlockchainContext, emConfigId: ErgoId,
                              emissionGateHash: Array[Byte], litID: ErgoId): Contract = {

    val constants = ConstantsBuilder
      .create()
      .item("CONST_EMCONFIG_NFT", Colls.fromArray(emConfigId.getBytes))
      .item("CONST_GATE_HASH", Colls.fromArray(emissionGateHash))
      .item("CONST_LIT_ID", Colls.fromArray(litID.getBytes))
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Collateral_Mainnet"))
  }
  def mkMainnetCollatContract(networkType: NetworkType, emConfigId: ErgoId,
                              emissionGateHash: Array[Byte], litID: ErgoId): Contract = {

    val constants = ConstantsBuilder
      .create()
      .item("CONST_EMCONFIG_NFT", Colls.fromArray(emConfigId.getBytes))
      .item("CONST_GATE_HASH", Colls.fromArray(emissionGateHash))
      .item("CONST_LIT_ID", Colls.fromArray(litID.getBytes))
      .build()

    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("Collateral_Mainnet"), Seq.empty)
  }

  // Essentially a contract which allows execution only when emission box exists
  // Used for queue and proof-of-spend boxes
  def mkEmissionGateContract(ctx: BlockchainContext): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_EMISSION_NFT", Colls.fromArray(LFSMHelpers.getEmissionNft(ctx.getNetworkType).getBytes))
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Emission_Gate"))
  }
  def mkEmissionGateContract(networkType: NetworkType): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_EMISSION_NFT", Colls.fromArray(LFSMHelpers.getEmissionNft(networkType).getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("Emission_Gate"), Seq.empty)
  }

  def mkEmissionsContract(ctx: BlockchainContext, collatPropBytesHash: Array[Byte],
                          emissionGateHash: Array[Byte], litID: ErgoId): Contract =
    mkEmissionsContract(ctx.getNetworkType, collatPropBytesHash, emissionGateHash, litID)

  def mkEmissionsContract(networkType: NetworkType, collatPropBytesHash: Array[Byte],
                          emissionGateHash: Array[Byte], litID: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()

      .item("CONST_COLLATERAL_HASH", Colls.fromArray(collatPropBytesHash))
      .item("CONST_GATE_HASH", Colls.fromArray(emissionGateHash))
      .item("CONST_LIT_ID", Colls.fromArray(litID.getBytes))
      .item("CONST_F1", Colls.fromArray(LFSMHelpers.FOUNDER_1.hashedPropBytes))
      .item("CONST_F2", Colls.fromArray(LFSMHelpers.FOUNDER_2.hashedPropBytes))
      .item("CONST_F3", Colls.fromArray(LFSMHelpers.FOUNDER_3.hashedPropBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("LIT_Emissions"), Seq.empty)

  }

  def mkEmissionsGuardContract(ctx: BlockchainContext, emissionId: ErgoId, emConfigId: ErgoId, collatPropBytesHash: Array[Byte],
                       emissionGateHash: Array[Byte], litID: ErgoId): Contract =
    mkEmissionsGuardContract(ctx.getNetworkType, emissionId, emConfigId, collatPropBytesHash,
      emissionGateHash, litID)

  def mkEmissionsGuardContract(networkType: NetworkType, emissionId: ErgoId, emConfigId: ErgoId, collatPropBytesHash: Array[Byte],
                       emissionGateHash: Array[Byte], litID: ErgoId): Contract = {
    val emissionsContract = mkEmissionsContract(networkType, collatPropBytesHash, emissionGateHash, litID)
    val constants = ConstantsBuilder
      .create()
      .item("CONST_EMCONFIG_NFT", Colls.fromArray(emConfigId.getBytes))
      .item("CONST_EMISSION_NFT", Colls.fromArray(emissionId.getBytes))
      .item("CONST_EM_SCRIPT_HASH", Colls.fromArray(emissionsContract.hashedValueBytes))
      .build()

    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("Emission_Guard"), Seq.empty)
  }
  def mkEmConfigContract(ctx: BlockchainContext, ownerPK: Contract): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_TESTNET_PK", ownerPK.address(ctx).getPublicKey)
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkCollatScript("Emission_Config"))
  }
  // Governed by voting tokens rather than a key: any 3 of the 5 in a transaction's inputs may spend it
  def mkMainnetEmConfigContract(networkType: NetworkType, voteTokenId: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_VOTE_TOKEN_ID", Colls.fromArray(voteTokenId.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("Emission_Config_Mainnet"), Seq.empty)
  }
  // NOTE: The contract is never actually used in a box, its serialized value bytes are instead attached
  // as a context var to emissions.
  def mkCollateralEnforcerContract(ctx: BlockchainContext, litID: ErgoId): Contract =
    mkCollateralEnforcerContract(ctx.getNetworkType, litID)

  def mkCollateralEnforcerContract(networkType: NetworkType, litID: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_LIT_ID", Colls.fromArray(litID.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkCollatScript("Collateral_Enforcer"), Seq.empty)
  }
}
