package configs

import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.{ErgoClient, NetworkType}

/**
 * Everything the actors need from [[NodeConfig]], as an interface rather than a class.
 *
 * There is still exactly ONE of these at runtime: `Module` binds it to the single instance `Globals`
 * holds, so nothing derives a second prover, unlocks secret storage twice, or hands out lender keys
 * from a different sequence. What the trait buys is that an actor names what it needs instead of
 * reaching into a singleton — which is also the only way any of them can be built in a test, since
 * `NodeConfig`'s constructor opens a `BlockchainContext` and so requires a live node.
 */
trait NodeContext {

  def getNetwork: NetworkType

  def getExplorerURL: String

  def getClient: ErgoClient

  def getNodeWallet: NodeWallet

  def getNodeKey: String

  def getNodeApi: NodeApi

  def getNodeUrl: String
}
