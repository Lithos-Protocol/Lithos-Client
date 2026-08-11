package support

import configs.NodeContext
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.{ErgoClient, ErgoProver, FileMockedErgoClient, NetworkType}
import org.ergoplatform.sdk.SecretString
import org.scalatestplus.mockito.MockitoSugar

import java.util.logging.{Level, Logger => JLogger}
import java.util.{List => JList}
import scala.collection.JavaConverters._
import scala.io.Source

/**
 * A [[NodeContext]] with no node behind it.
 *
 * This is what `NodeContext` was extracted for. In production `Module` binds the trait to the single
 * `NodeConfig` instance `Globals` holds; here it is backed by an offline client, a mnemonic-derived
 * prover and a mocked `NodeApi`, so the actors that used to reach into the singleton can be built and
 * driven.
 *
 * The prover comes from a mnemonic rather than `withDLogSecret`, because `NodeWallet` reads
 * `getEip3Addresses` — a DLog-only prover has none, and every one of its members would throw.
 * `numAddresses` mirrors `node.numAddresses`, since the reward-box lookups are per derived address.
 */
object FakeNodeContext extends MockitoSugar {

  /** A fixed test mnemonic. Never used anywhere but here, and holds nothing. */
  private val mnemonic =
    "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"

  private lazy val quieted: Unit =
    Seq("okhttp3.mockwebserver.MockWebServer", "okhttp3.mockwebserver")
      .foreach(name => JLogger.getLogger(name).setLevel(Level.OFF))

  private def loadResponse(name: String): String = {
    val src = Source.fromResource(s"mockwebserver/node_responses/$name")
    try src.mkString finally src.close()
  }

  /**
   * A fresh offline client. Each `execute` stands up its own MockWebServer replaying the same two
   * fixtures, so an actor may call it as many times as it likes.
   */
  def offlineClient(): ErgoClient = {
    quieted
    val responses: JList[String] = Seq(
      loadResponse("response_NodeInfo_v6.json"),
      loadResponse("response_LastHeaders_v6.json")
    ).asJava
    new FileMockedErgoClient(responses, Seq.empty[String].asJava, true)
  }

  def proverWith(client: ErgoClient, numAddresses: Int): ErgoProver =
    client.execute { ctx =>
      val base = ctx.newProverBuilder()
        .withMnemonic(SecretString.create(mnemonic), SecretString.empty(), false)
      (0 until numAddresses).foldLeft(base)((b, i) => b.withEip3Secret(i)).build()
    }

  /**
   * @param api          a mock, so a test says what the node reports
   * @param numAddresses how many EIP-3 indices the prover holds, as `node.numAddresses` decides
   */
  def apply(api: NodeApi = mock[NodeApi], numAddresses: Int = 2): (NodeContext, NodeApi, NodeWallet) = {
    val client = offlineClient()
    val wallet = NodeWallet(proverWith(client, numAddresses))
    val ctx = new NodeContext {
      override def getNetwork: NetworkType = NetworkType.MAINNET
      override def getExplorerURL: String = ""
      override def getClient: ErgoClient = client
      override def getNodeWallet: NodeWallet = wallet
      override def getNodeKey: String = "test-api-key"
      override def getNodeApi: NodeApi = api
      override def getNodeUrl: String = "http://127.0.0.1:9052/"
    }
    (ctx, api, wallet)
  }
}
