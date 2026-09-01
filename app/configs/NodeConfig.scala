package configs



import mutations.NodeWallet
import node.NodeApi
import node.rest.RestNodeApi
import org.ergoplatform.appkit._
import org.ergoplatform.restapi.client.ApiClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration

import java.net.URI

import scala.util.{Failure, Success, Try}

class NodeConfig(config: Configuration) extends NodeContext {

  private val logger: Logger = LoggerFactory.getLogger("NodeConfig")
  private val nodeURL: String = config.get[String]("node.url")
  private val nodeKey: String = config.get[String]("node.key")

  private val storagePath: String = config.get[String]("node.storagePath")
  private val password:    String = config.get[String]("node.pass")

  // valueOf throws a bare IllegalArgumentException naming nothing the user can act on
  private val networkType: NetworkType =
    Try(NetworkType.valueOf(config.get[String]("node.networkType").trim)) match {
      case Success(nt) => nt
      case Failure(_)  =>
        Configs.fail("node.networkType",
          s""""${config.get[String]("node.networkType")}" is not a network type - use MAINNET or TESTNET""")
    }

  private val secretStorage: SecretStorage =
    Try(SecretStorage.loadFrom(storagePath)) match {
      case Success(s) => s
      case Failure(_) =>
        Configs.fail("node.storagePath",
          s"no secret storage could be loaded from '$storagePath' - point node.storagePath at your " +
            "node's wallet keystore file (the JSON secret storage the node writes)")
    }
  private var explorerURL: String = config.get[String]("node.explorerURL")

  Try(secretStorage.unlock(password)) match {
    case Success(_) => ()
    case Failure(_) =>
      Configs.fail("node.pass",
        "the secret storage rejected this password - set node.pass to the wallet password your node uses")
  }


  private val ergoClient: ErgoClient = RestApiErgoClient.create(getNodeUrl, networkType, nodeKey, "https://api-testnet.ergoplatform.com")


  private val nodeApi: NodeApi = RestNodeApi(getNodeUrl, Some(nodeKey))

  /**
   * How many EIP-3 addresses the prover holds secrets for. Must be at least
   * `emission.maxLenderKeys`: lender keys are derived EIP-3 addresses, and boxes paid to them - permit
   * returns today, swept coinbases later - cannot be spent by a prover missing that index.
   */
  private val numAddresses: Int =
    math.max(1, config.getOptional[Int]("node.numAddresses").getOrElse(1))

  // Building the prover opens a connection to the node; a bad URL or a down node fails here first,
  // so name what to check rather than letting appkit's stack trace speak for itself.
  private val prover: ErgoProver =
    Try(ergoClient.execute { ctx =>
      val builder = ctx.newProverBuilder().withSecretStorage(secretStorage)
      (0 until numAddresses).foldLeft(builder)((b, i) => b.withEip3Secret(i)).build()
    }) match {
      case Success(p) => p
      case Failure(t) =>
        Configs.fail("node.url",
          s"could not build the signing prover against ${getNodeUrl}: check that the node is running, " +
            s"node.url is right and node.key matches its apiKey. (${t.getClass.getSimpleName}: ${t.getMessage})")
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
  /**
   * `node.url` may carry its own port for a node on a custom one; when it does not, the network's
   * default port is appended as before.
   */
  override def getNodeUrl: String = {
    val trimmed = nodeURL.trim.stripSuffix("/")
    val hasPort = Try(new URI(trimmed).getPort).getOrElse(-1) != -1
    if (hasPort) trimmed + "/"
    else {
      val port = networkType match {
        case NetworkType.MAINNET => ":9053/"
        case NetworkType.TESTNET => ":9052/"
      }
      trimmed + port
    }
  }

}
