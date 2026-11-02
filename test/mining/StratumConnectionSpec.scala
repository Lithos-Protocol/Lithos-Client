package mining

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.redbottledesign.bitcoin.rpc.stratum.message.ResponseMessage
import com.redbottledesign.bitcoin.rpc.stratum.transport.tcp.StratumTcpServerConnection
import com.redbottledesign.bitcoin.rpc.stratum.transport.{AbstractConnectionState, ConnectionState}
import lfsm.LFSMHelpers
import mining.MiningMessages._
import mining.StratumConnection.{ConnectionEstablished, NiceHashMinDifficulty, niceHashDifficulty, parseSubmit}
import org.bouncycastle.util.encoders.Hex
import org.json.{JSONArray, JSONObject}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import stratum.BlockTemplate
import stratum.data.MiningCandidate
import stratum.message.{Requests, Response}

import java.math.{BigDecimal, BigInteger}
import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt

/**
 * What one miner connection puts on the wire. NiceHash refused the stratum for announcing a share
 * difficulty of 1, and its submit layout could not be parsed; these pin the announcement under
 * `niceHashMode`, the unchanged one without it, and every submit layout mining software sends.
 */
class StratumConnectionSpec extends TestKit(ActorSystem("stratum-connection-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** A connection that records each message as the JSON a miner would read, and never touches a socket. */
  private class Wire extends StratumTcpServerConnection(null, new Socket()) {
    val sent = new ConcurrentLinkedQueue[JSONObject]()
    override def isOpen: Boolean = true
    override def sendResponse(message: ResponseMessage): Unit = sent.add(new JSONObject(message.toJson.toString))
    override protected def createPostConnectState(): ConnectionState = new AbstractConnectionState(this) {}
    def messages: List[JSONObject] = sent.asScala.toList
  }

  private case class Rig(connection: ActorRef, wire: Wire, jobManager: TestProbe)

  private def connect(niceHashMode: Boolean): Rig = {
    val wire       = new Wire
    val jobManager = TestProbe()
    val connection = system.actorOf(Props(new StratumConnection("5ub1d", new InetSocketAddress("127.0.0.1", 4444),
      TestProbe().ref, jobManager.ref, "c", niceHashMode = niceHashMode)))
    connection ! ConnectionEstablished(wire)
    Rig(connection, wire, jobManager)
  }

  /** Waits for the reply to request `id`, which the connection sends after an ask to the job manager. */
  private def awaitReply(rig: Rig, id: Long): Unit =
    awaitCond(rig.wire.messages.exists(_.opt("id") == id), 5.seconds)

  private def subscribe(rig: Rig, job: Option[BlockTemplate], extraNonce1: String = "ab12"): Unit = {
    rig.connection ! MinerSubscribe("1")
    rig.jobManager.expectMsg(RequestSubscription)
    rig.jobManager.reply(SubscriptionData(extraNonce1, 6, job))
    awaitReply(rig, 1L)
  }

  /**
   * Everything sent before the reply to a marker request, so whatever the connection was told before
   * it has been handled. The marker is an empty submit, which is answered and changes nothing.
   */
  private def settled(rig: Rig): List[JSONObject] = {
    rig.connection ! MinerSubmit("999", Nil)
    awaitReply(rig, 999L)
    val all = rig.wire.messages
    rig.wire.sent.clear()
    all.filterNot(_.opt("id") == 999L)
  }

  private def method(m: JSONObject): String = m.optString("method", "response")
  private def params(m: JSONObject): JSONArray = m.getJSONArray("params")
  private def number(value: Any): BigInteger = new BigDecimal(value.toString).toBigIntegerExact

  private val candidate =
    new MiningCandidate(Array.fill[Byte](32)(7), 479451L, 3, BigInteger.ONE, "02" * 33, null, null)

  /** A job for a committed score, advertised `multiplier` times harder as reduced reporting does. */
  private def job(jobId: String, score: Long, multiplier: Int = 100): BlockTemplate =
    new BlockTemplate(jobId, candidate, LFSMHelpers.convertTauOrScore(BigInt(score)).bigInteger,
      true, true, multiplier)

  // ─── mining.set_difficulty ──────────────────────────────────────────────

  "In NiceHash mode the stratum" should "announce the job's share difficulty before the job" in {
    val rig = connect(niceHashMode = true)
    val first = job("1", 40000000L)
    subscribe(rig, Some(first))

    val sent = settled(rig)
    sent.map(method) shouldBe List("response", "mining.set_difficulty", "mining.notify")
    val announced = number(params(sent(1)).get(0))
    announced shouldEqual niceHashDifficulty(first.assignedThreshold)
    // A 40M commitment sent at 100 times is NiceHash's minimum, the reported refusal's own case.
    announced.compareTo(NiceHashMinDifficulty) should be >= 0
    params(sent(2)).getString(0) shouldBe "1"
  }

  it should "announce it again only when a job's difficulty changes" in {
    val rig = connect(niceHashMode = true)
    subscribe(rig, Some(job("1", 40000000L)))
    settled(rig)

    rig.connection ! BroadcastJob(job("2", 40000000L))
    settled(rig).map(method) shouldBe List("mining.notify")

    val harder = job("3", 70000000L)
    rig.connection ! BroadcastJob(harder)
    val sent = settled(rig)
    sent.map(method) shouldBe List("mining.set_difficulty", "mining.notify")
    number(params(sent.head).get(0)) shouldEqual niceHashDifficulty(harder.assignedThreshold)
  }

  "Outside NiceHash mode the stratum" should "announce a difficulty of 1 once, whatever the job's threshold" in {
    // Mining software other than NiceHash reads the target from the job; this is the wire it always had.
    val rig = connect(niceHashMode = false)
    subscribe(rig, Some(job("1", 40000000L)))
    val sent = settled(rig)
    sent.map(method) shouldBe List("response", "mining.set_difficulty", "mining.notify")
    number(params(sent(1)).get(0)) shouldEqual BigInteger.ONE

    rig.connection ! BroadcastJob(job("2", 70000000L))
    settled(rig).map(method) shouldBe List("mining.notify")
  }

  it should "announce 1 even when there is no job to send yet" in {
    val rig = connect(niceHashMode = false)
    subscribe(rig, None)
    settled(rig).map(method) shouldBe List("response", "mining.set_difficulty")
  }

  "A job broadcast before the miner subscribes" should "be held, then sent after the subscribe response" in {
    val rig = connect(niceHashMode = true)
    rig.connection ! BroadcastJob(job("7", 40000000L))
    settled(rig) shouldBe empty

    subscribe(rig, Some(job("6", 40000000L)))
    val sent = settled(rig)
    sent.map(method) shouldBe List("response", "mining.set_difficulty", "mining.notify")
    params(sent(2)).getString(0) shouldBe "7"
  }

  // ─── mining.subscribe ───────────────────────────────────────────────────

  "The subscribe response" should "list the subscriptions, then extraNonce1 and the extraNonce2 size" in {
    val rig = connect(niceHashMode = false)
    subscribe(rig, None)
    val response = settled(rig).head
    response.get("id") shouldBe 1L
    val result = response.getJSONArray("result")
    result.getJSONArray(0).toString shouldBe """[["mining.set_difficulty","5ub1d"],["mining.notify","5ub1d"]]"""
    result.getString(1) shouldBe "ab12"
    result.getInt(2) shouldBe 6
  }

  // ─── mining.submit ──────────────────────────────────────────────────────

  "A three-param submit" should "be validated under the extraNonce1 this connection issued" in {
    val rig = connect(niceHashMode = true)
    subscribe(rig, Some(job("1", 40000000L)))
    settled(rig)

    rig.connection ! MinerSubmit("5", Seq("worker", "1", "00112233aabb"))
    val share = rig.jobManager.expectMsgType[ProcessShare]
    (share.jobId, share.workerName, share.nTime) shouldBe (("1", "worker", ""))
    Hex.toHexString(share.extraNonce1) shouldBe "ab12"
    Hex.toHexString(share.extraNonce2) shouldBe "00112233aabb"

    rig.jobManager.reply(ShareRejected(21, "solution was for old block", share.extraNonce1))
    awaitReply(rig, 5L)
    val response = settled(rig).head
    response.get("id") shouldBe 5L
    response.getString("error") shouldBe "21: solution was for old block"
  }

  "A malformed submit" should "be answered with an error, not left unanswered" in {
    val rig = connect(niceHashMode = true)
    subscribe(rig, Some(job("1", 40000000L)))
    settled(rig)

    rig.connection ! MinerSubmit("8", Seq("worker", "1"))
    val response = settled(rig).head
    response.get("id") shouldBe 8L
    response.getString("error") should startWith("20: ")
    rig.jobManager.expectNoMessage(200.millis)
  }

  // ─── extraNonce1 changes ────────────────────────────────────────────────

  "mining.extranonce.subscribe" should "be acknowledged with true" in {
    val rig = connect(niceHashMode = true)
    rig.connection ! MinerExtranonceSubscribe("4")
    awaitCond(rig.wire.messages.nonEmpty, 5.seconds)
    val response = rig.wire.messages.head
    response.get("id") shouldBe 4L
    response.get("result") shouldBe true
    response.isNull("error") shouldBe true
  }

  "A duplicate share in NiceHash mode" should "be rejected, not rotated, until the miner subscribes to extranonce" in {
    val rig = connect(niceHashMode = true)
    subscribe(rig, Some(job("1", 40000000L)))
    settled(rig)
    val en1 = Hex.decode("ab12")

    rig.connection ! MinerSubmit("10", Seq("worker", "1", "00112233aabb"))
    rig.jobManager.expectMsgType[ProcessShare]
    rig.jobManager.reply(ShareRejected(22, "duplicate share", en1))
    awaitReply(rig, 10L)
    val rejected = settled(rig)
    rejected.map(method) shouldBe List("response")
    rejected.head.getString("error") shouldBe "22: duplicate share"
    rig.jobManager.expectNoMessage(200.millis)

    rig.connection ! MinerExtranonceSubscribe("11")
    rig.connection ! MinerSubmit("12", Seq("worker", "1", "00112233aabb"))
    rig.jobManager.expectMsgType[ProcessShare]
    rig.jobManager.reply(ShareRejected(22, "duplicate share", en1))
    rig.jobManager.expectMsg(RequestSubscription)
    rig.jobManager.reply(SubscriptionData("cd34", 6, None))
    awaitReply(rig, 12L)
    val rotated = settled(rig).filterNot(_.opt("id") == 11L)
    rotated.map(method) shouldBe List("mining.set_extranonce", "mining.notify", "response")
    params(rotated.head).getString(0) shouldBe "cd34"
    rotated(2).get("result") shouldBe true
  }

  // ─── parseSubmit ────────────────────────────────────────────────────────

  private def parsed(params: String*): (String, String, String) = parseSubmit(params, "ab12") match {
    case Right(s) => (Hex.toHexString(s.extraNonce1), Hex.toHexString(s.extraNonce2), s.nTime)
    case Left(reason) => fail(reason)
  }

  "parseSubmit" should "read the five-param layout's extraNonce1 from the head of its nonce" in {
    parsed("w", "1", "00112233aabb", "", "cd3400112233aabb") shouldBe (("cd34", "00112233aabb", ""))
  }

  it should "fall back to the issued extraNonce1 when the fifth param cannot hold one" in {
    parsed("w", "1", "00112233aabb", "", "") shouldBe (("ab12", "00112233aabb", ""))
  }

  it should "read a four-param submit under the issued extraNonce1" in {
    parsed("w", "1", "00112233aabb", "6543") shouldBe (("ab12", "00112233aabb", "6543"))
  }

  it should "split a whole nonce sent as the third param" in {
    parsed("w", "1", "cd3400112233aabb") shouldBe (("cd34", "00112233aabb", ""))
  }

  it should "accept hex with a 0x prefix" in {
    parsed("w", "1", "0x00112233aabb") shouldBe (("ab12", "00112233aabb", ""))
  }

  it should "refuse too few params, and fields that are not hex" in {
    parseSubmit(Seq("w", "1"), "ab12").isLeft shouldBe true
    parseSubmit(Seq("w", "1", "zz"), "ab12").isLeft shouldBe true
    parseSubmit(Seq("w", "1", "00112233aabb", "", "not hex"), "ab12").isLeft shouldBe true
  }

  // ─── niceHashDifficulty ─────────────────────────────────────────────────

  "niceHashDifficulty" should "be 2^256 over the threshold, rounded up" in {
    val two256 = BigInteger.ONE.shiftLeft(256)
    niceHashDifficulty(BigInteger.valueOf(1L << 40)) shouldEqual two256.shiftRight(40)
    niceHashDifficulty(BigInteger.valueOf(3)) shouldEqual two256.divide(BigInteger.valueOf(3)).add(BigInteger.ONE)
  }

  it should "give back a target no easier than the job's, under either diff1 NiceHash may use" in {
    val two256    = BigInteger.ONE.shiftLeft(256)
    val miningcore = BigInteger.valueOf(0xffff).shiftLeft(240)
    Seq(job("1", 40000000L), job("2", 7000000L, 10000), job("3", 123456789L, 10)).foreach { j =>
      val threshold  = j.assignedThreshold
      val difficulty = niceHashDifficulty(threshold)
      two256.divide(difficulty).compareTo(threshold) should be <= 0
      miningcore.divide(difficulty).compareTo(threshold) should be <= 0
    }
  }

  it should "be the committed diff times the reduction multiplier, as application.conf tells miners" in {
    Seq((40000000L, 100), (7000000L, 100), (4000000L, 10000)).foreach { case (score, multiplier) =>
      val expected = BigInteger.valueOf(score * multiplier)
      val actual   = niceHashDifficulty(job("1", score, multiplier).assignedThreshold)
      actual.subtract(expected).abs.compareTo(expected.divide(BigInteger.valueOf(1000000))) should be <= 0
    }
    // The reported setup: a 7M commitment at 100 times is below NiceHash's minimum.
    niceHashDifficulty(job("1", 7000000L).assignedThreshold).compareTo(NiceHashMinDifficulty) should be < 0
  }

  it should "not throw on a job with no threshold" in {
    niceHashDifficulty(BigInteger.ZERO) shouldEqual BigInteger.ONE
  }

  // ─── message encoding ───────────────────────────────────────────────────

  "A response" should "echo an integral id as a number and any other id as the string it was" in {
    Response.acknowledge("7").toJson.get("id") shouldBe 7L
    Response.acknowledge("4294967296").toJson.get("id") shouldBe 4294967296L
    Response.acknowledge("job-7").toJson.get("id") shouldBe "job-7"
  }

  "mining.authorize" should "accept a worker name sent without a password" in {
    val request = new Requests.Authorize(new JSONObject("""{"id":2,"method":"mining.authorize","params":["worker"]}"""))
    request.workerName shouldBe "worker"
    request.password shouldBe ""
  }
}
