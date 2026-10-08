package mining

import com.redbottledesign.bitcoin.rpc.stratum.transport.{AbstractConnectionState, ConnectionState}
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServerConnection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{BindException, InetAddress, Socket}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/** Binds real loopback sockets on ephemeral ports, so it needs no node and no actor system. */
class BoundStratumTcpServerSpec extends AnyFlatSpec with Matchers {

  /** Counts accepted rigs; each connection ignores whatever it is sent. */
  private class CountingServer extends BoundStratumTcpServer {
    val accepted = new CountDownLatch(1)

    def localAddress: InetAddress = getServerSocket.getInetAddress
    def localPort: Int            = getServerSocket.getLocalPort

    override protected def createConnection(socket: Socket): StratumTcpServerConnection = {
      accepted.countDown()
      new StratumTcpServerConnection(this, socket) {
        override protected def createPostConnectState(): ConnectionState = new AbstractConnectionState(this) {}
      }
    }
  }

  private def awaitListening(server: CountingServer): Unit = {
    val deadline = System.currentTimeMillis() + 5000
    while (!server.isListening && System.currentTimeMillis() < deadline) Thread.sleep(10)
    server.isListening shouldBe true
  }

  "BoundStratumTcpServer" should "listen only on the address it is given, and accept rigs there" in {
    val server = new CountingServer
    Future(server.startListening(0, "127.0.0.1"))
    awaitListening(server)

    server.localAddress shouldBe InetAddress.getByName("127.0.0.1")
    val rig = new Socket("127.0.0.1", server.localPort)
    try server.accepted.await(5, TimeUnit.SECONDS) shouldBe true
    finally {
      rig.close()
      server.stopListening()
    }
  }

  it should "return normally when stopListening closes the socket" in {
    val server = new CountingServer
    val run    = Future(server.startListening(0, "127.0.0.1"))
    awaitListening(server)

    server.stopListening()
    noException should be thrownBy Await.result(run, 5.seconds)
  }

  it should "throw when the address is not one this machine holds, and not be listening" in {
    val server = new CountingServer
    // 192.0.2.0/24 is reserved for documentation and never assigned to a host. The timeout turns a
    // bind that unexpectedly succeeds into a failure rather than a hang.
    try {
      a[BindException] should be thrownBy Await.result(Future(server.startListening(0, "192.0.2.1")), 5.seconds)
      server.isListening shouldBe false
    } finally server.stopListening()
  }
}
