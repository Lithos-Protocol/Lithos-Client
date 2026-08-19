package lithosdex

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.{ErgoClient, NetworkType}
import org.ergoplatform.sdk.ErgoId

/**
 * Constants shared between the LithosDex contracts and the off-chain code that builds against them.
 *
 * Everything under "In-script constants" is declared inside the `.ergo` sources rather than injected, so
 * these are copies. If one changes on either side it must change on both — the contract is the authority.
 * Everything under "Injected constants" is compiled into a contract by [[lithosdex.contracts.LithosDexContracts]].
 */
object LDHelpers {

  // ---------------------------------------------------------------------------------------------
  // In-script constants — mirrors of values declared in the .ergo sources
  // ---------------------------------------------------------------------------------------------

  /** LD_LiquidityPool CONST_LOCKED_LP. Supply is this less R4. */
  final val LOCKED_LP: Long = 0x7fffffffffffffffL

  /** LD_LiquidityPool CONST_FEE_DENOM. Denominator of every numerator in feeParams. */
  final val FEE_DENOM: Long = 10000L

  /**
   * LD_LiquidityPool CONST_MIN_RENT. Floor on the pool box's value, deliberately not sized against
   * storage rent: it also bounds a redemption and bounds what a flush may leave behind, so it is paid
   * for continuously, while a pool idle for a storage period has no fees left to protect.
   */
  final val MIN_RENT: Long = 10000000L

  /** LD_LiquidityPool CONST_MIN_SUPPLY. Supply may never be redeemed below this. */
  final val MIN_SUPPLY: Long = 1000000L

  /** LD_LiquidityPool / LD_FeeVault CONST_SCALE. Fixed-point scale of both accumulators, 1e27. */
  final val SCALE: BigInt = BigInt(1000000000L) * BigInt(1000000000L) * BigInt(1000000000L)

  /**
   * LD_LiquidityPool / LD_Provision CONST_PROVISION_MIN. Floor value of a provision box, covering two
   * storage-fee collections.
   */
  final val PROVISION_MIN: Long = 600000000L

  /**
   * LD_FeeVault CONST_VAULT_MIN. Held back from every payout so the vault stays spendable between
   * flushes. Not sized against storage rent, for the same reason as [[MIN_RENT]].
   */
  final val VAULT_MIN: Long = 100000000L

  /** LD_Provision CONST_REFRESH_AGE. Blocks a provision box must reach before anyone may refresh it. */
  final val REFRESH_AGE: Int = 525600

  /** LD_Provision CONST_HEIGHT_SLACK. How stale a refreshed box's declared creation height may be. */
  final val HEIGHT_SLACK: Int = 1000

  // ---------------------------------------------------------------------------------------------
  // Pool operations — LD_LiquidityPool CTX_OP(0)
  // ---------------------------------------------------------------------------------------------

  final val POOL_SWAP: Byte    = 0.toByte
  final val POOL_DEPOSIT: Byte = 1.toByte
  final val POOL_REDEEM: Byte  = 2.toByte
  final val POOL_FLUSH: Byte   = 3.toByte
  final val POOL_RESIZE: Byte  = 4.toByte

  // ---------------------------------------------------------------------------------------------
  // Vault operations — LD_FeeVault CTX_OP(0)
  // ---------------------------------------------------------------------------------------------

  final val VAULT_CLAIM: Byte = 0.toByte
  final val VAULT_FLUSH: Byte = 1.toByte

  // ---------------------------------------------------------------------------------------------
  // Provision operations — LD_Provision CTX_OP(0)
  // ---------------------------------------------------------------------------------------------

  final val PROV_CLAIM: Byte   = 0.toByte
  final val PROV_REDEEM: Byte  = 1.toByte
  final val PROV_REFRESH: Byte = 2.toByte
  final val PROV_RESIZE: Byte  = 3.toByte

  /** Context var LD_Provision_Guard reads the provision logic from, and executes. */
  final val PROVISION_LOGIC_VAR: Byte = 64.toByte

  // ---------------------------------------------------------------------------------------------
  // Injected constants — filler values until genesis is minted
  // ---------------------------------------------------------------------------------------------

  // TODO: replace with the real ids once the genesis transaction is built. Nothing derived from these
  // is meaningful until then — every contract address below changes when they do.
  private final val POOL_NFT_TESTNET  = ErgoId.create("feecf867f715dc6539401736a02bd7145648b4ebe70d23205165a38e792b32a1")
  private final val POOL_NFT_MAINNET  = ErgoId.create("1111111111111111111111111111111111111111111111111111111111111111")
  private final val VAULT_NFT_TESTNET = ErgoId.create("b4c6fa23e9e14c577fe4f776e769d737857bd0794b960e90cff36b3573fff026")
  private final val VAULT_NFT_MAINNET = ErgoId.create("2222222222222222222222222222222222222222222222222222222222222222")
  private final val PROV_TOKEN_TESTNET = ErgoId.create("a4fea435f759fcb5fecb052dba466c63553411640265ba0076b861be1337e43b")
  private final val PROV_TOKEN_MAINNET = ErgoId.create("3333333333333333333333333333333333333333333333333333333333333333")
  // LIT token ids
  private final val TOKEN_Y_TESTNET   = LFSMHelpers.LIT_ID
  private final val TOKEN_Y_MAINNET   = LFSMHelpers.LIT_ID_MAINNET

  /**
   * Whole supply of the provision token, minted into the pool box at genesis.
   *
   * One deposit takes one token, so this is the ceiling on live provisions for the life of the pool,
   * and it cannot be raised afterwards: the entire supply goes into the pool box at genesis, and any
   * left in a wallet would be a forgeable provision. 1e15 is chosen to put that ceiling out of reach
   * rather than to be sized against a forecast.
   *
   * This is a launch decision, not a contract constant — no `.ergo` source mentions it. Everything
   * that mints or models a genesis pool reads it from here; `LithosDexConstantsSpec` is what keeps
   * that true.
   */
  final val PROV_TOKEN_SUPPLY: Long = 1000000000000000L

  /** Supply issued at genesis. The remainder up to [[LOCKED_LP]] is the locked share. Filler. */
  final val GENESIS_SUPPLY: Long = 1000000000000L

  /** feeParams as minted at genesis: [dexFee, ergFee, tokenFee] over [[FEE_DENOM]]. Filler. */
  final val GENESIS_FEE_PARAMS: Array[Long] = Array(9985L, 15L, 15L)

  /** Height of the genesis transaction. */
  final val GENESIS_HEIGHT: Int = 0

  /** Box id of the pool at genesis. */
  final val POOL_GENESIS_ID: String = "0000000000000000000000000000000000000000000000000000000000000000"

  /** Box id of the fee vault at genesis. */
  final val VAULT_GENESIS_ID: String = "0000000000000000000000000000000000000000000000000000000000000000"

  /** LD_LiquidityPool / LD_FeeVault / LD_Provision CONST_POOL_NFT. */
  def getPoolNFT(networkType: NetworkType): ErgoId = mainnetOr(networkType, POOL_NFT_MAINNET, POOL_NFT_TESTNET)

  /** LD_LiquidityPool / LD_Provision CONST_VAULT_NFT. */
  def getVaultNFT(networkType: NetworkType): ErgoId = mainnetOr(networkType, VAULT_NFT_MAINNET, VAULT_NFT_TESTNET)

  /** LD_FeeVault CONST_PROV_TOKEN. */
  def getProvToken(networkType: NetworkType): ErgoId = mainnetOr(networkType, PROV_TOKEN_MAINNET, PROV_TOKEN_TESTNET)

  /** The pool's token side. Not a contract constant — the pool reads it from its own tokens(1). */
  def getTokenY(networkType: NetworkType): ErgoId = mainnetOr(networkType, TOKEN_Y_MAINNET, TOKEN_Y_TESTNET)

  def getPoolNFT(client: ErgoClient): ErgoId   = client.execute(ctx => getPoolNFT(ctx.getNetworkType))
  def getVaultNFT(client: ErgoClient): ErgoId  = client.execute(ctx => getVaultNFT(ctx.getNetworkType))
  def getProvToken(client: ErgoClient): ErgoId = client.execute(ctx => getProvToken(ctx.getNetworkType))
  def getTokenY(client: ErgoClient): ErgoId    = client.execute(ctx => getTokenY(ctx.getNetworkType))

  private def mainnetOr(networkType: NetworkType, mainnet: ErgoId, testnet: ErgoId): ErgoId = {
    networkType match {
      case NetworkType.MAINNET => mainnet
      case _                   => testnet
    }
  }
}
