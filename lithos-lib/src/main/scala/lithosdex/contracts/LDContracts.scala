package lithosdex.contracts

import lfsm.ScriptGenerator
import lithosdex.LDHelpers
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, ContextVar, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, Mutator}

/**
 * The four LithosDex contracts, wired to each other.
 *
 * Everything derives from three ids — the pool NFT, the vault NFT and the provision token — so there is
 * no circularity to break: the provision logic is named by the guard, the guard by the pool, and the
 * vault and pool only ever name each other by NFT.
 *
 * {{{
 *   LD_Provision       (poolNFT, vaultNFT)          - logic, never a box script
 *     -> LD_Provision_Guard (hashedValueBytes)      - what a provision box actually carries
 *          -> LD_LiquidityPool (guard hash, vaultNFT)
 *   LD_FeeVault        (poolNFT, provToken)
 * }}}
 *
 * Use [[LDContracts]] rather than calling these one at a time unless you specifically want one script.
 */
object LithosDexContracts {

  private final val LIQUIDITY_POOL  = "LD_LiquidityPool"
  private final val FEE_VAULT       = "LD_FeeVault"
  private final val PROVISION       = "LD_Provision"
  private final val PROVISION_GUARD = "LD_Provision_Guard"

  /**
   * LD_Provision — the provision logic.
   *
   * NOTE: this contract is never used as a box's script. Its serialized value bytes are handed to
   * the provision guard at spend time through context var 64, the same trade Collateral_Enforcer makes.
   * Use the provision contract below to build that context var.
   */
  def mkProvisionLogic(networkType: NetworkType, poolNFT: ErgoId, vaultNFT: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_POOL_NFT", Colls.fromArray(poolNFT.getBytes))
      .item("CONST_VAULT_NFT", Colls.fromArray(vaultNFT.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkLithosDexScript(PROVISION), Seq.empty[Mutator])
  }

  def mkProvisionLogic(ctx: BlockchainContext, poolNFT: ErgoId, vaultNFT: ErgoId): Contract =
    mkProvisionLogic(ctx.getNetworkType, poolNFT, vaultNFT)

  /**
   * LD_Provision_Guard — the script every provision box carries.
   *
   * @param provisionLogicHash `hashedValueBytes` of provision contract, not its prop bytes: the guard
   *                           hashes the bytes it is handed in a context var and then executes them.
   */
  def mkProvisionGuard(networkType: NetworkType, provisionLogicHash: Array[Byte]): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_PROVISION_LOGIC_HASH", Colls.fromArray(provisionLogicHash))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkLithosDexScript(PROVISION_GUARD), Seq.empty[Mutator])
  }

  def mkProvisionGuard(ctx: BlockchainContext, provisionLogicHash: Array[Byte]): Contract =
    mkProvisionGuard(ctx.getNetworkType, provisionLogicHash)

  /** LD_FeeVault — the singleton holding flushed fees and the accumulators they paid for. */
  def mkFeeVault(networkType: NetworkType, poolNFT: ErgoId, provToken: ErgoId): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_POOL_NFT", Colls.fromArray(poolNFT.getBytes))
      .item("CONST_PROV_TOKEN", Colls.fromArray(provToken.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkLithosDexScript(FEE_VAULT), Seq.empty[Mutator])
  }

  def mkFeeVault(ctx: BlockchainContext, poolNFT: ErgoId, provToken: ErgoId): Contract =
    mkFeeVault(ctx.getNetworkType, poolNFT, provToken)

  /**
   * LD_LiquidityPool — the AMM.
   *
   * @param provisionGuardHash `hashedPropBytes` of provision guard. This is what a provision box is
   *                           actually locked under, so it is the prop bytes here rather than the value
   *                           bytes used for the logic.
   */
  def mkLPContract(networkType: NetworkType, vaultNFT: ErgoId, provisionGuardHash: Array[Byte]): Contract = {
    val constants = ConstantsBuilder
      .create()
      .item("CONST_VAULT_NFT", Colls.fromArray(vaultNFT.getBytes))
      .item("CONST_PROVISION_GUARD_HASH", Colls.fromArray(provisionGuardHash))
      .build()
    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkLithosDexScript(LIQUIDITY_POOL), Seq.empty[Mutator])
  }

  def mkLPContract(ctx: BlockchainContext, vaultNFT: ErgoId, provisionGuardHash: Array[Byte]): Contract =
    mkLPContract(ctx.getNetworkType, vaultNFT, provisionGuardHash)

  /**
   * The context var carrying the provision logic into a guard-locked box's spend. Every provision input
   * needs this attached alongside its own CTX_OP.
   */
  def provisionLogicVar(provisionLogic: Contract): ContextVar = {
    ContextVar.of(LDHelpers.PROVISION_LOGIC_VAR,
      ErgoValue.of(Colls.fromArray(provisionLogic.valueBytes), scalaByteType))
  }
}

/**
 * Every LithosDex contract for one deployment, with the hashes already threaded between them.
 *
 * @param poolNFT   singleton NFT of the pool
 * @param vaultNFT  singleton NFT of the fee vault
 * @param provToken id of the provision token, whose whole supply is minted into the pool at genesis
 */
case class LDContracts(poolNFT: ErgoId, vaultNFT: ErgoId, provToken: ErgoId, networkType: NetworkType) {

  /** LD_Provision. Never a box script — see [[LithosDexContracts.mkProvisionLogic]]. */
  val provisionLogic: Contract = LithosDexContracts.mkProvisionLogic(networkType, poolNFT, vaultNFT)

  /** LD_Provision_Guard. This is the address a provision box actually sits at. */
  val provisionGuard: Contract = LithosDexContracts.mkProvisionGuard(networkType, provisionLogic.hashedValueBytes)

  /** LD_FeeVault. */
  val feeVault: Contract = LithosDexContracts.mkFeeVault(networkType, poolNFT, provToken)

  /** LD_LiquidityPool. */
  val liquidityPool: Contract = LithosDexContracts.mkLPContract(networkType, vaultNFT, provisionGuard.hashedPropBytes)

  /** Attach to every provision input being spent, alongside its CTX_OP. */
  val provisionLogicVar: ContextVar = LithosDexContracts.provisionLogicVar(provisionLogic)
}

object LDContracts {

  /** Build from whatever [[LDHelpers]] holds for this network. */
  def apply(networkType: NetworkType): LDContracts = {
    LDContracts(
      LDHelpers.getPoolNFT(networkType),
      LDHelpers.getVaultNFT(networkType),
      LDHelpers.getProvToken(networkType),
      networkType
    )
  }

  def apply(ctx: BlockchainContext): LDContracts = apply(ctx.getNetworkType)

  def apply(ctx: BlockchainContext, poolNFT: ErgoId, vaultNFT: ErgoId, provToken: ErgoId): LDContracts =
    LDContracts(poolNFT, vaultNFT, provToken, ctx.getNetworkType)
}
