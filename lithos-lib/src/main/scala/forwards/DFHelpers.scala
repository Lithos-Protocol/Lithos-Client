package forwards

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId

/**
 * Constants shared between the difficulty forward contracts and the off-chain code that builds against them.
 *
 * Everything here is declared inside the `.ergo` sources rather than injected, so these are copies. If one
 * changes on either side it must change on both — the contract is the authority. The ids a deployment is
 * built from are passed to [[forwards.contracts.DFContracts]] instead.
 */
object DFHelpers {

  /** DF_Accumulator / DF_Offer CONST_EPOCH_LENGTH. Ergo retargets only at heights ≡ 1 mod 128. */
  final val EPOCH_LENGTH: Int = 128

  /** DF_Accumulator / DF_Position CONST_RING_SIZE. Epochs of difficulty the accumulator holds. */
  final val RING_SIZE: Int = 128

  /** DF_Offer CONST_MIN_LEAD. A window starts at least this many epochs after the fill's last block. */
  final val MIN_LEAD: Int = 2

  /** DF_Offer CONST_MAX_LEAD. A window starts at most this many epochs after the fill's last block. */
  final val MAX_LEAD: Int = 120

  /** DF_Offer CONST_MAX_WINDOW. Longest window, in epochs. */
  final val MAX_WINDOW: Int = 120

  /** DF_Offer / DF_Position. Each side's margin is the notional divided by this. */
  final val MARGIN_DIVISOR: Long = 5L

  /** DF_Offer CONST_SIDE_REWARD. LIT each side adds on top of its margin. */
  final val SIDE_REWARD: Long = 7500000000L

  /** DF_Position CONST_ACC_DEPOSIT. LIT a settlement or refund adds to the accumulator's balance. */
  final val ACC_DEPOSIT: Long = 10000000000L

  /** DF_Accumulator / DF_Position CONST_RECORD_REWARD. LIT a builder may take for recording a new epoch. */
  final val RECORD_REWARD: Long = 5000000000L

  /** LIT left to the builder of a settlement or refund: both sides' rewards less the accumulator's deposit. */
  final val SETTLE_REWARD: Long = 2 * SIDE_REWARD - ACC_DEPOSIT

  /** DF_Offer CONST_POSITION_ERG. Least nanoERG a position holds, 0.002 ERG from each side. */
  final val POSITION_ERG: Long = 4000000L

  /** The LIT token. Not a register value: each contract takes it as CONST_LIT_ID. */
  def getLitId(networkType: NetworkType): ErgoId = networkType match {
    case NetworkType.MAINNET => LFSMHelpers.LIT_ID_MAINNET
    case _                   => LFSMHelpers.LIT_ID_TESTNET
  }

  // ---------------------------------------------------------------------------------------------
  // Deployment ids
  // ---------------------------------------------------------------------------------------------

  /**
   * PLACEHOLDER, not a minted token: no accumulator exists on either network yet. Genesis prints the real
   * ids to paste over these. A token id is the hash of the box that minted it, so all zeroes is never one.
   */
  private final val ACC_NFT_PLACEHOLDER = ErgoId.create("00" * 32)

  /** DF_Position CONST_ACC_NFT on testnet. PLACEHOLDER until genesis mints it. */
  private final val ACC_NFT_TESTNET = ErgoId.create("1b45c0957d32a54fe946ed4408245f45cd17c0a530dc986f1689eceb5b7e6d50")
  /** DF_Position CONST_ACC_NFT on mainnet. PLACEHOLDER until genesis mints it. */
  private final val ACC_NFT_MAINNET = ACC_NFT_PLACEHOLDER

  /** DF_Position CONST_ACC_NFT, the accumulator's singleton NFT, or None while the slot holds the placeholder. */
  def getAccNFT(networkType: NetworkType): Option[ErgoId] = {
    val nft = networkType match {
      case NetworkType.MAINNET => ACC_NFT_MAINNET
      case _                   => ACC_NFT_TESTNET
    }
    if (java.util.Arrays.equals(nft.getBytes, ACC_NFT_PLACEHOLDER.getBytes)) None else Some(nft)
  }
}
