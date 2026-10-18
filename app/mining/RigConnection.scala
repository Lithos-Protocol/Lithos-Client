package mining

import com.redbottledesign.bitcoin.rpc.stratum.UnhandledStratumMessageException
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.{StratumTcpServer, StratumTcpServerConnection, TcpSocketInputThread}
import org.slf4j.{Logger, LoggerFactory}

import java.net.{Socket, SocketException}
import java.util.Scanner
import scala.util.control.NonFatal

/**
 * One rig's JStratum connection, whose reader treats the rig disconnecting as the end of the
 * connection rather than an error. JStratum's own reader calls `Scanner.nextLine`, which throws at end
 * of stream, so every disconnect was logged at error with a stack trace.
 */
abstract class RigConnection(server: StratumTcpServer, socket: Socket)
  extends StratumTcpServerConnection(server, socket) {

  private val readerLogger: Logger = LoggerFactory.getLogger("MiningStratumServer")

  private val reader: TcpSocketInputThread = new TcpSocketInputThread(this) {
    override def run(): Unit = readUntilClosed()
  }

  /** `open()` starts whatever this returns, and `close()` interrupts it. */
  override protected def getInputThread: TcpSocketInputThread = reader

  /**
   * Hands each line to the connection state as JStratum does, under the same lock, until the rig
   * disconnects. Anything else that ends the read is still an error. The connection closes either way.
   */
  private def readUntilClosed(): Unit =
    try {
      val lines = new Scanner(getSocket.getInputStream)
      try {
        while (isOpen && lines.hasNextLine) {
          val line = lines.nextLine().trim
          if (line.nonEmpty) this.synchronized {
            try receiveMessages(getConnectionState.getMarshaller.marshalMessages(line))
            catch { case unhandled: UnhandledStratumMessageException => handleUnsupportedRequest(unhandled.getUnhandledRequest) }
          }
        }
      } finally lines.close()
    } catch {
      case closed: SocketException => readerLogger.debug(s"Connection $getConnectionId closed: ${closed.getMessage}")
      case NonFatal(ex) => readerLogger.error(s"Error on connection $getConnectionId: ${ex.getMessage}", ex)
    } finally close()
}
