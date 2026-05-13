package mining

import org.json.JSONObject
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * Scala port of stratum.NodeInterface.
 *
 * Handles all HTTP communication with the Ergo node: fetching mining candidates
 * (with or without collateral transactions) and submitting solved blocks.
 * The API surface is identical to the Java original so the rest of the mining
 * system can be swapped between the two implementations transparently.
 */
class MiningNodeInterface(nodeApiUrl: String) {

  require(nodeApiUrl.endsWith("/"), "nodeApiUrl must end with a trailing slash")

  private val logger: Logger     = LoggerFactory.getLogger("MiningNodeInterface")
  private val http: HttpClient   = HttpClient.newHttpClient()
  private val baseURI: URI       = URI.create(nodeApiUrl)

  // ─── internal helpers ─────────────────────────────────────────────────────

  private def req(path: String): HttpRequest.Builder =
    HttpRequest.newBuilder(baseURI.resolve(path))
      .header("User-Agent", "lithos-stratum 1.0.0")

  // ─── public API ───────────────────────────────────────────────────────────

  /** Returns true when the node responds 200 to /info. */
  def isOnline: Boolean =
    try
      http.send(req("/info").build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    catch {
      case _: IOException | _: InterruptedException => false
    }

  /** Fetches /info JSON (protocol version, chain difficulty, etc.). */
  def info(): JSONObject =
    try
      new JSONObject(http.send(req("/info").build(), HttpResponse.BodyHandlers.ofString()).body())
    catch {
      case e: InterruptedException => throw new RuntimeException(e)
    }

  /**
   * Fetches a mining candidate from the node.
   *
   * @param useCollateral  when true, POSTs the collateral transaction to
   *                       /mining/candidateWithTxs; otherwise GETs /mining/candidate
   * @param postBody       the collateral transaction JSON (required when useCollateral = true)
   * @param apiKey         the node's API key (required when useCollateral = true)
   */
  def miningCandidate(useCollateral: Boolean,
                      postBody: Option[String],
                      apiKey: Option[String]): JSONObject =
    try {
      if (!useCollateral) {
        val json = new JSONObject(
          http.send(req("/mining/candidate").build(), HttpResponse.BodyHandlers.ofString()).body()
        )
        if (json.has("error")) {
          logger.error(s"Error while requesting mining candidate: errorCode=${json.getInt("error")}, reason=${json.getString("reason")}")
          throw new RuntimeException("HTTP request to /mining/candidate failed")
        }
        json
      } else {
        val httpReq = req("/mining/candidateWithTxs")
          .header("Content-Type", "application/json")
          .header("api_key", apiKey.getOrElse(""))
          .POST(HttpRequest.BodyPublishers.ofString("[" + postBody.getOrElse("") + "]"))
          .build()
        val json = new JSONObject(http.send(httpReq, HttpResponse.BodyHandlers.ofString()).body())
        if (json.has("error")) {
          if (json.getInt("error") == 403)
            logger.error("API key does not match the node's configured API key")
          else
            logger.error(s"Error while requesting candidateWithTxs: errorCode=${json.getInt("error")}, reason=${json.optString("reason", "unknown")}")
          throw new RuntimeException("HTTP request to /mining/candidateWithTxs failed")
        }
        json
      }
    } catch {
      case e: InterruptedException => throw new RuntimeException(e)
    }

  /**
   * Submits a solved block to the node.
   *
   * @param nonce  hex-encoded 8-byte nonce
   * @param pk     miner public key (from the mining candidate)
   * @return true on HTTP 200, false otherwise
   */
  def sendSolution(nonce: String, pk: String): Boolean =
    try {
      val body = new JSONObject()
      body.put("pk", pk)
      body.put("w", "02a7955281885bf0f0ca4a48678848cad8dc5b328ce8bc1d4481d041c98e891ff3")
      body.put("n", nonce)
      body.put("d", 0)
      val response = http.send(
        req("/mining/solution")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString))
          .header("Content-Type", "application/json")
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      if (response.statusCode() == 500)
        logger.error(s"Block submission returned HTTP 500: ${response.body()}")
      response.statusCode() == 200
    } catch {
      case e: InterruptedException => throw new RuntimeException(e)
    }
}
