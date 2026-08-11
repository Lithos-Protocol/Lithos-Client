package mining

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import mining.MiningMessages._
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import stratum.CollateralData
import stratum.data.{Data, MiningCandidate, Options}

import java.math.BigInteger

/**
 * `LithosJobManager` owns every job and validates every share, on its own thread.
 *
 * Two properties decide whether a miner keeps mining, and both are asserted here rather than
 * assumed: no message can make `receive` throw — an exception restarts the actor and empties
 * `validJobs`, which tells every connected miner "old block" until the next poll — and a solo job
 * never produces a super share, because a candidate fetched for a lender's key keeps that key even
 * when the genesis transaction is absent.
 */
object LithosJobManagerSpec {
  /**
   * A share is answered by one Autolykos verification, and the first few in a JVM are far slower
   * than the rest. Three seconds is the testkit default and it is not a statement about the client.
   */
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 30s")
}

class LithosJobManagerSpec extends TestKit(ActorSystem("job-manager-spec", LithosJobManagerSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    // Both for the same reason `LithosPool.preStart` forces the table: they are only reachable from
    // share validation, so otherwise the first share in this suite pays for them and answers late.
    // `NTable.N_Table` is a lazy val built by scanning every height to ~4.2M; the hit computation
    // is the real per-share cost and wants its JIT warmup outside a timed expectation.
    evaluation.NTable.lookUp(1)
    sigma.pow.Autolykos2PowValidation.hitForVersion2ForMessageWithChecks(
      32, Array.fill[Byte](32)(0), Array.fill[Byte](8)(0), Array[Byte](0, 7, 79, 27),
      evaluation.NTable.lookUp(479451))
  }

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val maxTarget = new BigInteger("f" * 64, 16)

  private def options(extraNonce1Size: Int = 2): Options = {
    val data = new Data
    data.protocolVersion = 3
    new Options(extraNonce1Size, 1L, 60000L, 1000L, "http://127.0.0.1:9052/",
      BigInteger.valueOf(4000000000L), data)
  }

  private def candidate(height: Long = 479451L,
                        b: BigInteger = BigInteger.ONE,
                        msgByte: Byte = 7,
                        collateral: CollateralData = null): MiningCandidate =
    new MiningCandidate(Array.fill[Byte](32)(msgByte), height, 3, b, "02" * 33, null, collateral)

  private def collateralData(txId: String = "ab" * 32): CollateralData =
    new CollateralData(txId, "{}", "02" * 33, Array.emptyByteArray, Array.emptyByteArray,
      "cd" * 32, "9address")

  /** A job manager whose parent is a probe, so NewJobAvailable can be observed. */
  private def managerWithParent(opts: Options = options()): (TestProbe, akka.actor.ActorRef) = {
    val parent = TestProbe()
    val ref = parent.childActorOf(akka.actor.Props(new LithosJobManager(opts)))
    (parent, ref)
  }

  private def share(jobId: String,
                    en1: Array[Byte],
                    en2: Array[Byte],
                    nTime: String = "00000000"): ProcessShare =
    ProcessShare(jobId, BigInteger.ONE, en1, en2, nTime, "127.0.0.1", 4444, "worker")

  // ─── template handling ────────────────────────────────────────────────────

  "A solo template carrying collateral data" should "be refused outright" in {
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()

    probe.send(mgr, ProcessTemplate(candidate(collateral = collateralData()), BigInteger.TEN,
      usesCollateral = false, reducedShareMessages = false, mustPublish = true))

    probe.expectMsg(false)
    parent.expectNoMessage()
  }

  "A template at a height already passed" should "never be published" in {
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()

    probe.send(mgr, ProcessTemplate(candidate(height = 100L), BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    parent.expectMsgType[NewJobAvailable]

    probe.send(mgr, ProcessTemplate(candidate(height = 99L), BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(false)
    parent.expectNoMessage()
  }

  "mustPublish" should "override the identity test, because the candidate is already taken" in {
    // The node keeps one candidate per key and validates solutions against it. A template that has
    // been fetched and then dropped leaves miners hashing a header no solution can be submitted
    // against, so once it is in hand it goes out even if nothing about it changed.
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()
    val same = candidate()

    probe.send(mgr, ProcessTemplate(same, BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    parent.expectMsgType[NewJobAvailable]

    probe.send(mgr, ProcessTemplate(same, BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    parent.expectMsgType[NewJobAvailable]
  }

  it should "still be dropped when left false and nothing changed" in {
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()
    val same = candidate()

    probe.send(mgr, ProcessTemplate(same, BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = false))
    probe.expectMsg(true)
    parent.expectMsgType[NewJobAvailable]

    probe.send(mgr, ProcessTemplate(same, BigInteger.TEN, usesCollateral = false,
      reducedShareMessages = false, mustPublish = false))
    probe.expectMsg(false)
    parent.expectNoMessage()
  }

  // ─── share validation ─────────────────────────────────────────────────────

  /**
   * A subscription and a published job.
   *
   * `tau` defaults wide open and `b` stays at 1, which makes every share an ordinary ACCEPTED one:
   * the hit clears the miner's difficulty so it is not rejected as low, and cannot clear a block
   * target of 1. The shipped 4G difficulty would reject every share offline, since nothing here
   * searches for a favourable nonce.
   */
  private def subscribeAndJob(mgr: akka.actor.ActorRef,
                              parent: TestProbe,
                              usesCollateral: Boolean = true,
                              b: BigInteger = BigInteger.ONE,
                              tau: BigInteger = maxTarget): (String, String) = {
    val probe = TestProbe()
    probe.send(mgr, RequestSubscription)
    val sub = probe.expectMsgType[SubscriptionData]

    val collateral = if (usesCollateral) collateralData() else null
    probe.send(mgr, ProcessTemplate(candidate(b = b, collateral = collateral), tau, usesCollateral,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    val job = parent.expectMsgType[NewJobAvailable].template.jobId
    (sub.extraNonce1, job)
  }

  "A duplicate nonce" should "be rejected on the second submission" in {
    val (parent, mgr) = managerWithParent()
    val (en1Hex, jobId) = subscribeAndJob(mgr, parent)
    val probe = TestProbe()
    val en1 = Hex.decode(en1Hex)
    val en2 = Array[Byte](1, 2, 3, 4, 5, 6)

    probe.send(mgr, share(jobId, en1, en2))
    probe.expectMsgType[ShareResult] shouldBe a[ShareAccepted]

    probe.send(mgr, share(jobId, en1, en2))
    val second = probe.expectMsgType[ShareResult]
    second shouldBe a[ShareRejected]
    second.asInstanceOf[ShareRejected].id shouldEqual 22
  }

  it should "not reject a different nonce on the same job" in {
    val (parent, mgr) = managerWithParent()
    val (en1Hex, jobId) = subscribeAndJob(mgr, parent)
    val probe = TestProbe()
    val en1 = Hex.decode(en1Hex)

    probe.send(mgr, share(jobId, en1, Array[Byte](1, 1, 1, 1, 1, 1)))
    probe.expectMsgType[ShareResult] shouldBe a[ShareAccepted]
    probe.send(mgr, share(jobId, en1, Array[Byte](2, 2, 2, 2, 2, 2)))
    probe.expectMsgType[ShareResult] shouldBe a[ShareAccepted]
  }

  "Concurrent submissions of one nonce" should "yield exactly one acceptance" in {
    // Validation moved onto the actor thread precisely so this holds: `validJobs` is a mutable map
    // and the submission set is a plain HashSet, and running them in Futures both lost duplicates
    // and corrupted the table — worst exactly when share volume is highest.
    val (parent, mgr) = managerWithParent()
    val (en1Hex, jobId) = subscribeAndJob(mgr, parent)
    val en1 = Hex.decode(en1Hex)
    val en2 = Array[Byte](9, 9, 9, 9, 9, 9)

    val probes = (1 to 16).map(_ => TestProbe())
    probes.foreach(p => p.send(mgr, share(jobId, en1, en2)))
    val results = probes.map(_.expectMsgType[ShareResult])

    val duplicates = results.count {
      case r: ShareRejected => r.id == 22
      case _ => false
    }
    duplicates shouldEqual results.size - 1
  }

  "A solo job" should "never produce a super share, whatever the hash" in {
    // A candidate requested for a lender's key keeps that key even when the genesis transaction is
    // not in it, so mining it pays the lender for a block that spends no collateral. Asserted over
    // many nonces and with the block target wide open, so the guard cannot be passing by luck.
    val (parent, mgr) = managerWithParent()
    val (en1Hex, jobId) = subscribeAndJob(mgr, parent, usesCollateral = false, b = maxTarget,
      tau = maxTarget)
    val probe = TestProbe()
    val en1 = Hex.decode(en1Hex)

    val accepted = (0 until 64).map { i =>
      probe.send(mgr, share(jobId, en1, Array[Byte](0, 0, 0, 0, 0, i.toByte)))
      probe.expectMsgType[ShareResult]
    }.collect { case a: ShareAccepted => a }

    accepted should not be empty            // the target is wide open, so these are all blocks
    all(accepted.map(_.isBlock)) shouldBe true
    all(accepted.map(_.isSuperShare)) shouldBe false
  }

  "An accepted ordinary share" should "not allocate a candidate copy" in {
    // One in tens of thousands is ever read downstream, and MiningCandidate's fields are assigned
    // only in its constructors, so sharing the job's own is safe.
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()
    probe.send(mgr, RequestSubscription)
    val sub = probe.expectMsgType[SubscriptionData]
    val cand = candidate(b = BigInteger.ONE, collateral = collateralData())

    probe.send(mgr, ProcessTemplate(cand, maxTarget, usesCollateral = true,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    val job = parent.expectMsgType[NewJobAvailable].template.jobId

    val results = (0 until 32).map { i =>
      probe.send(mgr, share(job, Hex.decode(sub.extraNonce1), Array[Byte](0, 0, 0, 0, 1, i.toByte)))
      probe.expectMsgType[ShareResult]
    }.collect { case a: ShareAccepted if !a.isBlock && !a.isSuperShare => a }

    results should not be empty
    all(results.map(_.candidate eq cand)) shouldBe true
  }

  "A block" should "carry a defensive copy of the candidate" in {
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()
    probe.send(mgr, RequestSubscription)
    val sub = probe.expectMsgType[SubscriptionData]
    val cand = candidate(b = maxTarget, collateral = collateralData())

    probe.send(mgr, ProcessTemplate(cand, maxTarget, usesCollateral = true,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    val job = parent.expectMsgType[NewJobAvailable].template.jobId

    probe.send(mgr, share(job, Hex.decode(sub.extraNonce1), Array[Byte](3, 3, 3, 3, 3, 3)))
    val accepted = probe.expectMsgType[ShareAccepted]
    accepted.isBlock shouldBe true
    // The solved job's own key is what the solution is submitted under, so the copy has to carry it.
    accepted.candidate.pk shouldEqual cand.pk
    accepted.candidate should not be theSameInstanceAs(cand)
  }

  // ─── adversarial input ────────────────────────────────────────────────────

  "A share for an unknown job" should "be rejected as a stale block" in {
    val (_, mgr) = managerWithParent()
    val probe = TestProbe()
    probe.send(mgr, share("ffff", Array[Byte](1, 2), Array[Byte](1, 2, 3, 4, 5, 6)))
    probe.expectMsgType[ShareRejected].id shouldEqual 21
  }

  "A share with the wrong extraNonce2 size" should "be rejected, not throw" in {
    val (_, mgr) = managerWithParent()
    val probe = TestProbe()
    probe.send(mgr, share("ffff", Array[Byte](1, 2), Array[Byte](1)))
    probe.expectMsgType[ShareRejected].id shouldEqual 20
  }

  "Adversarial share input" should "never restart the actor" in {
    // The inputs are miner-supplied and validation now runs where a throw is fatal: it empties
    // validJobs, and every connected miner is told "old block" until the next poll builds one.
    val (parent, mgr) = managerWithParent()
    val (en1Hex, jobId) = subscribeAndJob(mgr, parent)
    val probe = TestProbe()
    val en1 = Hex.decode(en1Hex)

    val hostile = Seq(
      share(jobId, Array.emptyByteArray, Array[Byte](1, 2, 3, 4, 5, 6)),
      share(jobId, en1, Array.emptyByteArray),
      share(jobId, en1, Array.fill[Byte](64)(0)),
      share("", en1, Array[Byte](1, 2, 3, 4, 5, 6)),
      share("not-hex", en1, Array[Byte](1, 2, 3, 4, 5, 6)),
      share(jobId, Array.fill[Byte](8)(0), Array[Byte](1, 2, 3, 4, 5, 6)),
      share(jobId, en1, Array[Byte](1, 2, 3, 4, 5, 6), nTime = "")
    )

    hostile.foreach { s =>
      probe.send(mgr, s)
      probe.expectMsgType[ShareResult] // an answer of some kind, never a dead letter
    }

    // Still alive and still serving: the job survived every one of those.
    probe.send(mgr, RequestSubscription)
    probe.expectMsgType[SubscriptionData].currentJob.map(_.jobId) shouldEqual Some(jobId)
  }

  "The job window" should "stay bounded as jobs roll over" in {
    // pruneOldJobs removes exactly one entry per addition, keyed by the same unpadded hex the
    // counter emits. A format mismatch here would grow validJobs without bound; nothing else caps it.
    val (parent, mgr) = managerWithParent()
    val probe = TestProbe()
    val jobIds = (1 to 40).map { i =>
      probe.send(mgr, ProcessTemplate(candidate(height = 1000L + i), maxTarget,
        usesCollateral = false, reducedShareMessages = false, mustPublish = true))
      probe.expectMsg(true)
      parent.expectMsgType[NewJobAvailable].template.jobId
    }

    // Anything older than the rolling window is gone; the newest few are still served.
    val stale = jobIds.head
    probe.send(mgr, share(stale, Array[Byte](1, 2), Array[Byte](1, 2, 3, 4, 5, 6)))
    probe.expectMsgType[ShareRejected].id shouldEqual 21

    probe.send(mgr, share(jobIds.last, Array[Byte](1, 2), Array[Byte](1, 2, 3, 4, 5, 6)))
    probe.expectMsgType[ShareResult] shouldBe a[ShareAccepted]
  }

  // ─── subscription ─────────────────────────────────────────────────────────

  "Subscription" should "issue an extraNonce1 of the configured size and a matching remainder" in {
    // The two halves have to add up to Ergo's 8-byte nonce. A mismatch rejects every share the
    // miner sends, which is what a hardcoded truncation did before the size became configurable.
    Seq(2, 3, 4).foreach { size =>
      val (_, mgr) = managerWithParent(options(extraNonce1Size = size))
      val probe = TestProbe()
      probe.send(mgr, RequestSubscription)
      val sub = probe.expectMsgType[SubscriptionData]

      withClue(s"extraNonce1Size=$size: ") {
        Hex.decode(sub.extraNonce1).length shouldEqual size
        sub.extraNonce2Size shouldEqual (8 - size)
        Hex.decode(sub.extraNonce1).length + sub.extraNonce2Size shouldEqual 8
      }
    }
  }

  it should "hand out a distinct extraNonce1 per subscriber" in {
    val (_, mgr) = managerWithParent()
    val probe = TestProbe()
    val issued = (1 to 25).map { _ =>
      probe.send(mgr, RequestSubscription)
      probe.expectMsgType[SubscriptionData].extraNonce1
    }
    issued.distinct.size shouldEqual issued.size
  }
}
