package plasmadex

import org.ergoplatform.appkit.{ErgoId, NetworkType}

object PDHelpers {
  final val MAX_LIQ: Long = 0x7fffffffffffffffL
  final val FEE_DENOM: Long = 1000

  private final val LP_TOKEN_TESTNET: ErgoId = ErgoId.create("3e80f3d0b85a3b66558bd14b1db36b38320948b8b4de8c27cdf3f38a86f53980")
  private final val LP_TOKEN_MAINNET: ErgoId = ErgoId.create("3e80f3d0b85a3b66558bd14b1db36b38320948b8b4de8c27cdf3f38a86f53980")

  def getLPToken(networkType: NetworkType): ErgoId = {
    networkType match {
      case NetworkType.MAINNET =>
        LP_TOKEN_MAINNET
      case _ =>
        LP_TOKEN_TESTNET
    }
  }
}
