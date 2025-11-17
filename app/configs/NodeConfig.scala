package configs



import org.ergoplatform.appkit.impl.{ErgoTreeContract, NodeAndExplorerDataSourceImpl}
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoProver, InputBox, NetworkType, RestApiErgoClient, SecretStorage}
import org.ergoplatform.restapi.client.ApiClient
import play.api.Configuration
import work.lithos.mutations.Contract

import java.util.concurrent.TimeUnit

class NodeConfig(config: Configuration) {
  private val nodeURL: String = config.get[String]("node.url")
  private val nodeKey: String = config.get[String]("node.key")

  private val storagePath: String = config.get[String]("node.storagePath")
  private val password:    String = config.get[String]("node.pass")
  private val networkType: NetworkType = NetworkType.valueOf(config.get[String]("node.networkType"))
  //private val scriptBase: String = config.get[String]("params.scriptBasePath")
  private val secretStorage: SecretStorage = SecretStorage.loadFrom(storagePath)
  private var explorerURL: String = config.get[String]("node.explorerURL")

  secretStorage.unlock(password)



  private val ergoClient: ErgoClient = RestApiErgoClient.create(getNodeApi, networkType, nodeKey, "https://api-testnet.ergoplatform.com")
  val apiClient = new ApiClient(nodeURL, "ApiKeyAuth", nodeKey)
  val prover: ErgoProver = ergoClient.execute{
    ctx =>
      ctx.newProverBuilder().withSecretStorage(secretStorage).withEip3Secret(0).build()
  }
  private val nodeWallet: NodeWallet = NodeWallet(PK(prover.getEip3Addresses.get(0)), prover)


  def getNetwork: NetworkType   = networkType
  def getExplorerURL: String    = {
    if(explorerURL == "default")
      explorerURL = RestApiErgoClient.getDefaultExplorerUrl(networkType)
    explorerURL
  }
  def getClient: ErgoClient     = ergoClient
  def getNodeWallet: NodeWallet = nodeWallet
  def getNodeKey: String        = nodeKey
  def getNodeApi: String = {
    val port = networkType match {
      case NetworkType.MAINNET => ":9053/"
      case NetworkType.TESTNET => ":9052/"
    }
    nodeURL + port
  }
  case class NodeWallet(pk: PK, prover: ErgoProver) {
    val p2pk: Address = pk.p2pk
    val contract: Contract = pk.contract
  }

  case class PK(p2pk: Address) {
    val contract: Contract = Contract(new ErgoTreeContract(p2pk.getErgoAddress.script, networkType).getErgoTree)
  }

}
