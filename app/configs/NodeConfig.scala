package configs



import mutations.NodeWallet
import node.NodeApi
import node.rest.RestNodeApi
import org.ergoplatform.appkit._
import org.ergoplatform.restapi.client.ApiClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration

class NodeConfig(config: Configuration) extends NodeContext {

  private val logger: Logger = LoggerFactory.getLogger("NodeConfig")
  private val nodeURL: String = config.get[String]("node.url")
  private val nodeKey: String = config.get[String]("node.key")

  private val storagePath: String = config.get[String]("node.storagePath")
  private val password:    String = config.get[String]("node.pass")
  private val networkType: NetworkType = NetworkType.valueOf(config.get[String]("node.networkType"))
  //private val scriptBase: String = config.get[String]("params.scriptBasePath")
  private val secretStorage: SecretStorage = SecretStorage.loadFrom(storagePath)
  private var explorerURL: String = config.get[String]("node.explorerURL")
  secretStorage.unlock(password)



  private val ergoClient: ErgoClient = RestApiErgoClient.create(getNodeUrl, networkType, nodeKey, "https://api-testnet.ergoplatform.com")
  val apiClient = new ApiClient(nodeURL, "ApiKeyAuth", nodeKey)

  private val nodeApi: NodeApi = RestNodeApi(getNodeUrl, Some(nodeKey))

  /**
   * How many EIP-3 addresses the prover holds secrets for. Must be at least
   * `emission.maxLenderKeys`: lender keys are derived EIP-3 addresses, and boxes paid to them — permit
   * returns today, swept coinbases later — cannot be spent by a prover missing that index.
   */
  private val numAddresses: Int =
    math.max(1, config.getOptional[Int]("node.numAddresses").getOrElse(1))

  private val prover: ErgoProver = ergoClient.execute { ctx =>
    val builder = ctx.newProverBuilder().withSecretStorage(secretStorage)
    (0 until numAddresses).foldLeft(builder)((b, i) => b.withEip3Secret(i)).build()
  }
  private val nodeWallet: NodeWallet = NodeWallet(prover)

  {
    val lenderKeys = config.getOptional[Int]("emission.maxLenderKeys").getOrElse(0)
    if (numAddresses < lenderKeys)
      logger.error(s"node.numAddresses ($numAddresses) is below emission.maxLenderKeys " +
        s"($lenderKeys). Lender keys past index ${numAddresses - 1} will receive funds this client " +
        "cannot spend. Raise node.numAddresses and restart")
    else
      logger.info(s"Prover holds $numAddresses EIP-3 address(es), primary ${nodeWallet.p2pk}")
  }


  override def getNetwork: NetworkType   = networkType
  override def getExplorerURL: String    = {
    if(explorerURL == "default")
      explorerURL = RestApiErgoClient.getDefaultExplorerUrl(networkType)
    explorerURL
  }
  override def getClient: ErgoClient     = ergoClient
  override def getNodeWallet: NodeWallet = nodeWallet
  override def getNodeKey: String        = nodeKey
  override def getNodeApi: NodeApi       = nodeApi
  override def getNodeUrl: String = {
    val port = networkType match {
      case NetworkType.MAINNET => ":9053/"
      case NetworkType.TESTNET => ":9052/"
    }
    nodeURL + port
  }

}
