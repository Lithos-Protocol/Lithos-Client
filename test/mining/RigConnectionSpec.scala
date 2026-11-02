package mining

import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.redbottledesign.bitcoin.rpc.stratum.transport.{AbstractConnectionState, ConnectionState}
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.TcpSocketInputThread
import org.json.JSONObject
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, ServerSocket, Socket}
import scala.collection.JavaConverters._

class RigConnectionSpec extends AnyFlatSpec with Matchers {

  "A rig that disconnects" should "end its connection without logging an error" in {
    // JStratum's reader threw "No line found" at end of stream and logged every rig disconnect at
    // error with a stack trace, which read like the stratum failing.
    val events = new ListAppender[ILoggingEvent]()
    events.start()
    val loggers = Seq("MiningStratumServer", classOf[TcpSocketInputThread].getName)
      .map(name => LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger])
    loggers.foreach(_.addAppender(events))
    val listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    try {
      val rig = new Socket(InetAddress.getLoopbackAddress, listening.getLocalPort)
      val connection = new RigConnection(null, listening.accept()) {
        override protected def createPostConnectState(): ConnectionState = new AbstractConnectionState(this) {}
      }
      connection.open()
      // A blank line is skipped, as JStratum skips it; then the rig hangs up.
      rig.getOutputStream.write("\n".getBytes("UTF-8"))
      rig.getOutputStream.flush()
      rig.close()

      val deadline = System.currentTimeMillis() + 5000
      while (connection.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(20)
      connection.isOpen shouldBe false
      events.list.asScala.filter(_.getLevel == Level.ERROR).map(_.getFormattedMessage) shouldBe empty
    } finally {
      loggers.foreach(_.detachAppender(events))
      listening.close()
    }
  }

  "A method the stratum does not implement" should "be refused under the id the rig sent, and keep the connection" in {
    // JStratum's own refusal sent the id back as the string "5", which a client pairing replies by
    // numeric id never matches to its request.
    val listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    try {
      val rig = new Socket(InetAddress.getLoopbackAddress, listening.getLocalPort)
      rig.setSoTimeout(5000)
      val connection = new RigConnection(null, listening.accept()) {
        override protected def createPostConnectState(): ConnectionState = new AbstractConnectionState(this) {}
      }
      connection.open()
      rig.getOutputStream.write("""{"id":5,"method":"mining.configure","params":[[],{}]}""".concat("\n").getBytes("UTF-8"))
      rig.getOutputStream.flush()

      val reply = new JSONObject(new BufferedReader(new InputStreamReader(rig.getInputStream, "UTF-8")).readLine())
      reply.get("id") shouldBe 5
      reply.getString("error") shouldBe "20: Method not supported: mining.configure"
      connection.isOpen shouldBe true
      rig.close()
    } finally listening.close()
  }
}
