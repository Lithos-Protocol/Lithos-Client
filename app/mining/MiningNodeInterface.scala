package mining

import node.NodeApi
import node.model.{MiningSolution, NodeInfo}
import node.rest.RestNodeApi
import org.json.{JSONArray, JSONObject}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.{Failure, Success}

/**
 * Scala port of stratum.NodeInterface.
 *
 * Handles all HTTP communication with the Ergo node: fetching mining candidates
 * (with or without collateral transactions) and submitting solved blocks.
 * The API surface is identical to the Java original so the rest of the mining
 * system can be swapped between the two implementations transparently.
 */
object MiningNodeInterface {
  private final val PlaceholderW = "02a7955281885bf0f0ca4a48678848cad8dc5b328ce8bc1d4481d041c98e891ff3"

  /**
   * Both candidate calls run inside LithosPool's `receive`, so an unanswered one holds that mailbox
   * for as long as it takes — and a found block waits in it. A node that accepts the connection and
   * then goes quiet would stall mining outright with no error and nothing in the log.
   */
  private final val ConnectTimeout: Duration = Duration.ofSeconds(3)
  private final val RequestTimeout: Duration = Duration.ofSeconds(10)
}

class MiningNodeInterface(nodeApiUrl: String) {

  require(nodeApiUrl.endsWith("/"), "nodeApiUrl must end with a trailing slash")

  private val logger: Logger     = LoggerFactory.getLogger("MiningNodeInterface")
  private val http: HttpClient   = HttpClient.newBuilder()
    .connectTimeout(MiningNodeInterface.ConnectTimeout)
    .build()
  private val baseURI: URI       = URI.create(nodeApiUrl)
  private val nodeApi: NodeApi   = RestNodeApi(nodeApiUrl)

  // ─── internal helpers ─────────────────────────────────────────────────────

  private def req(path: String): HttpRequest.Builder =
    HttpRequest.newBuilder(baseURI.resolve(path))
      .timeout(MiningNodeInterface.RequestTimeout)
      .header("User-Agent", "lithos-stratum 1.0.0")

  // ─── public API ───────────────────────────────────────────────────────────

  /** Returns true when the node responds 200 to /info. */
  def isOnline: Boolean = nodeApi.isOnline

  /** Fetches /info (protocol version, chain difficulty, etc.). */
  def info(): NodeInfo = nodeApi.info() match {
    case Success(nodeInfo) => nodeInfo
    case Failure(ex)       => throw new RuntimeException("Failed to read node info", ex)
  }

  /**
   * Fetches a mining candidate from the node.
   *
   * @param useCollateral  when true, POSTs the collateral transaction to
   *                       /mining/candidateWithTxsAndPk; otherwise GETs /mining/candidate
   * @param postBody       the collateral transaction JSON (required when useCollateral = true)
   * @param apiKey         the node's API key (required when useCollateral = true)
   */
  def miningCandidate(useCollateral: Boolean,
                      postBody: Option[String],
                      apiKey: Option[String],
                      minerPk: Option[String] = None): JSONObject =
    if (!useCollateral) soloCandidate()
    else candidateWithTxs(postBody.toSeq, apiKey, minerPk)

  /** GET /mining/candidate — the node's own reward key, no inserted transactions. */
  def soloCandidate(): JSONObject =
    try {
      val json = new JSONObject(
        http.send(req("/mining/candidate").build(), HttpResponse.BodyHandlers.ofString()).body()
      )
      if (json.has("error")) {
        logger.error(s"Error while requesting mining candidate: errorCode=${json.getInt("error")}, reason=${json.getString("reason")}")
        throw new RuntimeException("HTTP request to /mining/candidate failed")
      }
      json
    } catch {
      case e: InterruptedException => throw new RuntimeException(e)
    }

  /**
   * POST /mining/candidateWithTxsAndPk — a candidate carrying `txsJson` and paying its coinbase to
   * `minerPk`, which is what the collateral contract's `lenderIsBlockMiner` needs (analysis §24.4).
   *
   * Transaction ORDER is preserved end to end: the node applies these in the order it is given them,
   * so a transaction chaining off another must come after it.
   */
  def candidateWithTxs(txsJson: Seq[String],
                       apiKey: Option[String],
                       minerPk: Option[String]): JSONObject =
    try {
      val txs = new JSONArray()
      txsJson.foreach(tx => txs.put(new JSONObject(tx)))
      val body = new JSONObject()
        .put("txs", txs)
        .put("pk", minerPk.getOrElse(""))
      val httpReq = req("/mining/candidateWithTxsAndPk")
        .header("Content-Type", "application/json")
        .header("api_key", apiKey.getOrElse(""))
        .POST(HttpRequest.BodyPublishers.ofString(body.toString))
        .build()
      val json = new JSONObject(http.send(httpReq, HttpResponse.BodyHandlers.ofString()).body())
      if (json.has("error")) {
        if (json.getInt("error") == 403)
          logger.error("API key does not match the node's configured API key")
        else {
          logger.error(s"Error while requesting candidateWithTxsAndPk: errorCode=${json.getInt("error")}, " +
            s"reason=${json.optString("reason", "unknown")}")
          //logger.error(json.toString)
        }
        throw new RuntimeException("HTTP request to /mining/candidateWithTxsAndPk failed")
      }
      json
    } catch {
      case e: InterruptedException => throw new RuntimeException(e)
    }

  /** The height of the block a candidate would be built for — one past the confirmed chain tip. */
  def nextBlockHeight(): Option[Int] =
    nodeApi.info().toOption.flatMap(_.fullHeight).map(_ + 1)

  /**
   * Submits a solved block to the node.
   *
   * @param nonce  hex-encoded 8-byte nonce
   * @param pk     miner public key (from the mining candidate)
   * @return true on HTTP 200, false otherwise
   */
  def sendSolution(nonce: String, pk: String): Boolean =
    nodeApi.submitSolution(MiningSolution(pk, MiningNodeInterface.PlaceholderW, nonce, "0")) match {
      case Success(_) => true
      case Failure(ex) =>
        logger.error(s"Block submission failed: ${ex.getMessage}", ex)
        false
    }
}
