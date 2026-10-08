package mining

import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServer
import org.slf4j.{Logger, LoggerFactory}

import java.net.{InetAddress, ServerSocket, SocketException}

/**
 * A JStratum server that listens on one chosen local address. JStratum's own `startListening(port)`
 * always binds every interface; this runs the same accept loop on a socket bound to `bindAddress`.
 */
abstract class BoundStratumTcpServer extends StratumTcpServer {

  private val bindLogger: Logger = LoggerFactory.getLogger("MiningStratumServer")

  /**
   * Binds `bindAddress`:`port` and accepts rigs until [[stopListening]] closes the socket, then
   * returns normally. Throws if the bind fails, or if accepting fails while still listening.
   */
  def startListening(port: Int, bindAddress: String): Unit = {
    if (isListening) throw new IllegalStateException("The server is already listening for connections.")
    // 50 is the JDK's default backlog, which JStratum's own loop uses.
    setServerSocket(new ServerSocket(port, 50, InetAddress.getByName(bindAddress)))
    bindLogger.info(s"Mining server listening on $bindAddress, port ${getServerSocket.getLocalPort}")
    try {
      while (isListening) {
        val connection = createConnection(getServerSocket.accept())
        acceptConnection(connection)
        connection.open()
      }
    } catch {
      case _: SocketException if !isListening => ()
    }
  }
}
