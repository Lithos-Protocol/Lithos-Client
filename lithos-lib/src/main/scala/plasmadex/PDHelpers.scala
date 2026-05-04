package plasmadex

import org.ergoplatform.appkit.{ErgoClient, ErgoId, NetworkType}

object PDHelpers {
  final val MAX_LIQ: Long = 0x7fffffffffffffffL
  final val FEE_DENOM: Long = 10000

  private final val LP_TOKEN_TESTNET: ErgoId = ErgoId.create("85a1d609456685a51c117357444b2c69a309ebc93bf427e5166592dbb176f994")
  private final val LP_TOKEN_MAINNET: ErgoId = ErgoId.create("3e80f3d0b85a3b66558bd14b1db36b38320948b8b4de8c27cdf3f38a86f53980")

  final val LP_GENESIS_HEIGHT = 323206
  // Genesis Tx: db97b6b413d9e5e9668805db44b4f9e5304e7e1e3daf020af816530c9258af97
  final val LP_GENESIS_ID = "33c10e99c41aa717e1454549b201c149a584719f1bcebcdd68be64b191a0e318"
  def getLPToken(networkType: NetworkType): ErgoId = {
    networkType match {
      case NetworkType.MAINNET =>
        LP_TOKEN_MAINNET
      case _ =>
        LP_TOKEN_TESTNET
    }
  }

  def getLPToken(client: ErgoClient): ErgoId = {
    client.execute{
      ctx =>
        getLPToken(ctx.getNetworkType)
    }
  }
}
