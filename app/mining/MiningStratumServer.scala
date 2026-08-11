package mining

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import com.redbottledesign.bitcoin.rpc.stratum.MalformedStratumMessageException
import configs.CandidateConfig
import com.redbottledesign.bitcoin.rpc.stratum.transport.{AbstractConnectionState, ConnectionState, StatefulMessageTransport}
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.{StratumTcpServer, StratumTcpServerConnection}
import mining.StratumConnection.ConnectionEstablished
import mining.MiningMessages._
import mutations.NodeWallet
import nisp.NISPDatabase
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import stratum.counter.SubscriptionIdCounter
import stratum.data.Options
import stratum.message.{Announcement, Requests, Response}

import java.io.IOException
import java.math.BigDecimal
import java.net.{InetSocketAddress, Socket}
import java.util.Arrays
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/**
 * Actor-based Stratum TCP server.
 *
 * This class replaces stratum.ErgoStratumServer with an implementation that
 * uses Akka actors for all stateful logic.  It extends JStratum's
 * StratumTcpServer purely to inherit the socket-accept loop; every protocol
 * concern is delegated to the actor hierarchy rooted at LithosPool.
 *
 * Actor hierarchy
 * ───────────────
 *  system
 *   └─ mining-pool  (LithosPool)
 *       ├─ job-manager  (LithosJobManager)
 *       ├─ connection-<id>  (StratumConnection)  ← one per connected miner
 *       └─ ...
 *
 * Chicken-and-egg bridge
 * ──────────────────────
 * JStratum's connection-state object needs an ActorRef to forward messages to,
 * but the StratumConnection needs the connection reference to send responses.
 * This is solved with an AtomicReference that is populated immediately after
 * both objects are constructed, before JStratum starts any I/O threads for
 * that connection.
 *
 * Startup
 * ───────
 * Construct this class, then call startListening(port).  The LithosPool is
 * created and the initial block template is fetched inside LithosPool.preStart,
 * so no additional initialisation is required.
 *
 * Shutdown
 * ────────
 * Call stopListening() — this closes all miner sockets and stops all
 * ConnectionActors before shutting down the TCP accept loop.
 *
 * @param system              Akka ActorSystem — used to create LithosPool and ConnectionActors
 * @param options             Stratum options (nodeApiUrl, tau, polling interval, etc.)
 * @param useCollateral       Whether to fetch candidates with collateral transactions
 * @param client              Ergo client for collateral retrieval
 * @param prover              Node wallet used to sign collateral transactions
 * @param apiKey              Node API key for /mining/candidateWithTxs
 * @param reducedShareMessages Whether to divide the displayed tau by 1000
 * @param nispDB              NISP database for super-share persistence
 * @param candidateConfig     Tuning for CandidateBuilder, which builds the transactions inserted
 *                            into every Lithos block
 */
class MiningStratumServer(system: ActorSystem,
                          options: Options,
                          useCollateral: Boolean,
                          client: ErgoClient,
                          prover: NodeWallet,
                          apiKey: String,
                          reducedShareMessages: Boolean,
                          nispDB: NISPDatabase,
                          stateFrame: ActorRef,
                          forceConfigDiff: Boolean,
                          diffRefreshInterval: Int,
                          candidateConfig: CandidateConfig = CandidateConfig.Default,
                          txSources: Seq[ActorRef] = Seq.empty[ActorRef],
                          rotateExtraNonceInterval: Int = 0)
  extends StratumTcpServer {

  private val logger: Logger = LoggerFactory.getLogger("MiningStratumServer")

  // ─── actor tree root ──────────────────────────────────────────────────────

  val poolActor: ActorRef = system.actorOf(
    Props(new LithosPool(options, useCollateral, client, prover, apiKey,
      reducedShareMessages, nispDB, stateFrame, forceConfigDiff, diffRefreshInterval,
      candidateConfig, txSources)),
    "mining-pool"
  )

  /**
   * Resolved once at startup so every connection can reach it without going through the pool. The
   * Await is on the startup path only; nothing on the share path blocks.
   */
  private val jobManager: ActorRef = {
    implicit val timeout: akka.util.Timeout = akka.util.Timeout(30.seconds)
    Await.result((poolActor ? GetJobManager).mapTo[ActorRef], 30.seconds)
  }

  // ─── per-connection tracking (for clean shutdown) ─────────────────────────

  private val subscriptionIdCounter = new SubscriptionIdCounter()

  /**
   * Live connection actors, so `stopListening` can stop them promptly rather than waiting out each
   * one's liveness tick. Concurrent because entries are added on JStratum's accept thread and
   * removed on each connection actor's own thread as it stops; a plain map only ever grew, holding a
   * dead ActorRef for every connection the process had ever accepted.
   */
  private val connectionActors: TrieMap[String, ActorRef] = TrieMap.empty

  // ─── StratumTcpServer overrides ───────────────────────────────────────────

  /**
   * Called by JStratum's accept loop for every incoming TCP connection.
   *
   * We create the StratumConnection and the JStratum connection object in
   * the same thread-safe sequence:
   *   1. Allocate an AtomicReference for the actor ref (starts empty).
   *   2. Create the StratumTcpServerConnection whose state lambdas close over
   *      the AtomicReference — they will block on get() if invoked before step 4.
   *   3. Create the StratumConnection (which will receive ConnectionEstablished
   *      before any miner messages can arrive, because JStratum doesn't start
   *      I/O threads until after createConnection returns).
   *   4. Populate the AtomicReference so the state lambdas can forward messages.
   *   5. Send ConnectionEstablished to the actor so it transitions to ready state.
   */
  override protected def createConnection(connectionSocket: Socket): StratumTcpServerConnection = {
    val subscriptionId = subscriptionIdCounter.next()
    val socketAddress  = connectionSocket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress]

    // Step 1 — mutable bridge between JStratum callbacks and the actor
    val actorRefBridge = new AtomicReference[ActorRef]()

    // Step 2 — JStratum connection object; its state will forward to the bridge
    val connection = new StratumTcpServerConnection(this, connectionSocket) {
      override protected def createPostConnectState(): ConnectionState =
        new MiningConnectionState(actorRefBridge, socketAddress, this)

      override def close(): Unit =
        if (isOpen) {
          try {
            getSocket.close()
            getOutputThread.interrupt()
            getInputThread.interrupt()
          } catch {
            case ex: IOException => logger.warn(s"Error closing connection $getConnectionId: ${ex.getMessage}")
          }
        }
    }

    val connectionId = connection.getConnectionId

    // Step 3 — create the actor BEFORE populating the bridge
    val connectionActor = system.actorOf(
      Props(new StratumConnection(subscriptionId, socketAddress, poolActor, jobManager, connectionId,
        rotateExtraNonceInterval, () => connectionActors.remove(connectionId))),
      s"connection-$connectionId"
    )

    // Step 4 — expose the actor ref to the JStratum callbacks
    actorRefBridge.set(connectionActor)

    // Step 5 — give the actor the connection reference (processed before I/O threads start)
    connectionActor ! ConnectionEstablished(connection)

    // Track the actor for broadcasting and clean shutdown
    connectionActors.put(connectionId, connectionActor)
    poolActor ! MinerConnected(connectionId, connectionActor)

    logger.info(s"Accepted connection $connectionId from ${socketAddress.getHostString}:${socketAddress.getPort}")

    connection
  }

  override protected def acceptConnection(connection: StratumTcpServerConnection): Unit =
    getConnections.put(connection.getConnectionId, connection)

  /** Closes all miner connections and shuts down all ConnectionActors. */
  override def stopListening(): Unit = {
    logger.info("Stopping MiningStratumServer")
    try {
      Option(getServerSocket).foreach(_.close())
      val iter = getConnections.asMap().values().iterator()
      while (iter.hasNext) {
        val conn = iter.next()
        try {
          logger.info(s"Closing connection ${conn.getConnectionId}")
          conn.close()
        } catch {
          case ex: Exception => logger.warn(s"Error closing connection: ${ex.getMessage}")
        }
      }
    } catch {
      case ex: IOException => logger.error("Error stopping server", ex)
    }
    connectionActors.values.foreach(system.stop)
    connectionActors.clear()
  }

  // ─── inner class: JStratum ↔ Akka bridge ─────────────────────────────────

  /**
   * Per-connection Stratum protocol state.
   *
   * Each handler lambda runs on JStratum's input I/O thread.  It does nothing
   * more than forward the parsed message to the StratumConnection, keeping all
   * business logic inside the actor system and off the I/O thread.
   *
   * The AtomicReference ensures that even if JStratum starts the input thread
   * before step 4 of createConnection completes (which should never happen in
   * practice), the handlers will spin-wait until the actor ref is available.
   */
  private class MiningConnectionState(actorRefBridge: AtomicReference[ActorRef],
                                      socketAddress: InetSocketAddress,
                                      transport: StatefulMessageTransport)
    extends AbstractConnectionState(transport) {

    private def actor: ActorRef = actorRefBridge.get()

    registerRequestHandler(Requests.Subscribe.NAME, classOf[Requests.Subscribe], (m: Requests.Subscribe) => {
      logger.info(s"mining.subscribe from ${socketAddress.getHostString}")
      actor ! MinerSubscribe(m.getId)
    })

    registerRequestHandler(Requests.Authorize.NAME, classOf[Requests.Authorize], (m: Requests.Authorize) => {
      logger.info(s"mining.authorize worker='${m.workerName}' from ${socketAddress.getHostString}")
      actor ! MinerAuthorize(m.getId, m.workerName, m.password)
    })

    registerRequestHandler(Requests.Submit.NAME, classOf[Requests.Submit], (m: Requests.Submit) => {
      try {
        val params       = m.getParams
        val workerName   = params.get(0).asInstanceOf[String]
        val jobId        = params.get(1).asInstanceOf[String]
        val extraNonce2  = params.get(2).asInstanceOf[String]
        val nTime        = params.get(3).asInstanceOf[String]
        val extraNonce1H = params.get(4).asInstanceOf[String]
        actor ! MinerSubmit(m.getId, workerName, jobId, extraNonce2, nTime, extraNonce1H)
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to parse mining.submit from ${socketAddress.getHostString}: ${ex.getMessage}")
      }
    })
  }
}
