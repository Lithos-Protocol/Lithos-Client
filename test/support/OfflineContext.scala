package support

import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, FileMockedErgoClient}
import work.lithos.mutations.Contract

import java.math.BigInteger
import java.util.logging.{Level, Logger => JLogger}
import java.util.{List => JList}
import scala.collection.JavaConverters._
import scala.io.Source

/**
 * A real [[BlockchainContext]] with no node, for the `app/mining` and `app/transactions` tests.
 *
 * appkit's `FileMockedErgoClient` answers the two calls a context needs from fixtures that ship in
 * the appkit test jar, so anything that only wants a context to build boxes and sign — which is most
 * of the transaction builders — runs offline. Anything that would make a THIRD node call does not,
 * and that boundary is what decides which tests exist.
 *
 * The `_v6` fixtures are required rather than preferred: the plain ones report blockVersion 3, and
 * contracts here compile at ErgoTree v3.
 */
object OfflineContext {

  /** MockWebServer logs two request lines per `execute`, which buries the actual test output. */
  private lazy val quieted: Unit =
    Seq("okhttp3.mockwebserver.MockWebServer", "okhttp3.mockwebserver")
      .foreach(name => JLogger.getLogger(name).setLevel(Level.OFF))

  private def loadResponse(name: String): String = {
    val src = Source.fromResource(s"mockwebserver/node_responses/$name")
    try src.mkString finally src.close()
  }

  def withCtx[A](f: BlockchainContext => A): A = {
    quieted
    val responses: JList[String] = Seq(
      loadResponse("response_NodeInfo_v6.json"),
      loadResponse("response_LastHeaders_v6.json")
    ).asJava
    new FileMockedErgoClient(responses, Seq.empty[String].asJava, true).execute(ctx => f(ctx))
  }

  def proverWith(ctx: BlockchainContext, secret: BigInteger): ErgoProver =
    ctx.newProverBuilder().withDLogSecret(secret).build()

  def contractOf(prover: ErgoProver): Contract = Contract.fromAddress(prover.getAddress)
}
