package mining

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import evaluation.NTable
import lfsm.LFSMHelpers
import mining.MiningMessages._
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import sigma.pow.Autolykos2PowValidation
import stats.LocalMiningObservation
import stratum.{BlockTemplate, CollateralData}
import stratum.data.{Data, MiningCandidate, Options}

import java.math.BigInteger

/**
 * A real super share, captured from testnet on 2026-08-11 at height 480,273.
 *
 * This is the one branch of share validation that cannot be driven synthetically: it needs a nonce
 * whose Autolykos hit clears `realTau / NISP_COEFFICIENT`, roughly one in ten thousand, so a test
 * cannot search for one. Everything below is replayed from the capture rather than constructed, so
 * it pins the classification against numbers the chain actually produced — including the N lookup at
 * a real mining height, which is where the old table bug lived.
 *
 * The share is deliberately NOT a block: `b` is below `fH`, so it exercises the super-share branch
 * on its own rather than the block branch that also happens to set the flag.
 */
object SuperShareFixtureSpec {
  val msg: Array[Byte] = Hex.decode("598e93f4cc23c30e5eabf5ea6a082381c1db7aabb8a964015b9d2c9b0dc2138f")
  val nonce: Array[Byte] = Hex.decode("0000fcbbf0eebbe1")
  val height: Long = 480273L
  val n: Int = 67108864
  val tau = new BigInteger("827086351695115681596935607204913627523357033326004028853268457199379497")
  val b = new BigInteger("22982479575150034204626778802035933762856547847377518257648858953144")
  val fH = new BigInteger("47390704087967412719297748562161005380568271415804206279386393657692")

  /** The capture used the shipped two-byte extraNonce1, so the miner searched the last six. */
  val extraNonce1: Array[Byte] = nonce.take(2)
  val extraNonce2: Array[Byte] = nonce.drop(2)

  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 30s")
}

class SuperShareFixtureSpec extends TestKit(ActorSystem("super-share-fixture", SuperShareFixtureSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  import SuperShareFixtureSpec._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def intBytes(v: Int): Array[Byte] =
    Array((v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte)

  private def collateralData: CollateralData =
    new CollateralData("ab" * 32, "{}", "02" * 33, Array.emptyByteArray, Array.emptyByteArray,
      "cd" * 32, "9address")

  private def candidate(withCollateral: Boolean): MiningCandidate =
    new MiningCandidate(msg, height, 3, b, "02" * 33, null,
      if (withCollateral) collateralData else null)

  // ─── the numbers themselves ───────────────────────────────────────────────

  "The captured share" should "reproduce its hit from the message and nonce" in {
    // If this fails, the client is computing a different hit from the one it recorded — which is a
    // PoW bug, and every share it has ever classified was classified on the wrong number.
    val computed = Autolykos2PowValidation
      .hitForVersion2ForMessageWithChecks(32, msg, nonce, intBytes(height.toInt), n)
      .bigInteger
    computed shouldEqual fH
  }

  it should "get that same N from the table at its own height" in {
    // The captured N came from `NTable.lookUp` at mining time. This is the boundary test made
    // concrete: a lookup that returned the genesis N here would compute a different hit entirely.
    NTable.lookUp(height.toInt) shouldEqual n
    n shouldEqual Autolykos2PowValidation.calcN(height.toInt)
  }

  it should "clear the super-share threshold but not the block target" in {
    val template = new BlockTemplate("1a", candidate(withCollateral = true), tau,
      true, false)

    withClue("it must NOT be a block, or it would exercise the wrong branch: ") {
      b.compareTo(fH) should be < 0
    }
    withClue("it must clear the super-share threshold: ") {
      template.superShareThreshold.compareTo(fH) should be >= 0
    }
    withClue("and it must clear the miner's own difficulty, or it is a low-difficulty reject: ") {
      tau.compareTo(fH) should be >= 0
    }
  }

  it should "split into the two nonce halves the shipped extraNonce1Size produces" in {
    extraNonce1.length shouldEqual 2
    extraNonce2.length shouldEqual 6
    (extraNonce1 ++ extraNonce2) shouldEqual nonce
  }

  // ─── the classification, end to end through the actor ─────────────────────

  private def submit(usesCollateral: Boolean): ShareResult = {
    val data = new Data
    data.protocolVersion = 3
    val opts = new Options(2, 1L, 60000L, 1000L, "http://127.0.0.1:9052/", tau, data)

    val parent = TestProbe()
    val mgr = parent.childActorOf(Props(new LithosJobManager(opts)))
    val probe = TestProbe()

    probe.send(mgr, ProcessTemplate(candidate(usesCollateral), tau, usesCollateral,
      reducedShareMessages = false, mustPublish = true))
    probe.expectMsg(true)
    val jobId = parent.expectMsgType[NewJobAvailable].template.jobId

    probe.send(mgr, ProcessShare(jobId, BigInteger.ONE, extraNonce1, extraNonce2, "00000000",
      "127.0.0.1", 4444, "worker"))
    probe.expectMsgType[ShareResult]
  }

  "A collateral-backed job" should "classify the captured share as a super share" in {
    val result = submit(usesCollateral = true)
    result shouldBe a[ShareAccepted]
    val accepted = result.asInstanceOf[ShareAccepted]

    accepted.isSuperShare shouldBe true
    accepted.isBlock shouldBe false
    accepted.shareDiff shouldEqual fH
    accepted.height shouldEqual height
    // A super share is persisted to the NISP database, so the candidate has to come through.
    accepted.candidate should not be null
    accepted.blockHash shouldEqual fH.toByteArray
  }

  "A solo job" should "classify the very same share as ordinary" in {
    // The regression test for the guard, now with a share that genuinely clears the threshold — so
    // it fails if the `usedCollateral` conjunct is dropped, which no synthetic share could show.
    val result = submit(usesCollateral = false)
    result shouldBe a[ShareAccepted]
    val accepted = result.asInstanceOf[ShareAccepted]

    accepted.isSuperShare shouldBe false
    accepted.isBlock shouldBe false
    accepted.shareDiff shouldEqual fH
  }

  // ─── work accounting ──────────────────────────────────────────────────────

  /** One job on a manager that reports statistics; submits the shares and returns its counters. */
  private def countedWork(jobTau: BigInteger, reduced: Boolean, jobB: BigInteger,
                          shares: Seq[(Array[Byte], Array[Byte])]): Map[String, String] = {
    val data = new Data
    data.protocolVersion = 3
    val opts = new Options(2, 1L, 60000L, 1000L, "http://127.0.0.1:9052/", jobTau, data)
    val parent = TestProbe()
    val collector = TestProbe()
    val mgr = parent.childActorOf(Props(new LithosJobManager(opts, Some(collector.ref))))
    val probe = TestProbe()

    probe.send(mgr, ProcessTemplate(new MiningCandidate(msg, height, 3, jobB, "02" * 33, null, collateralData),
      jobTau, true, reducedShareMessages = reduced, mustPublish = true))
    probe.expectMsg(true)
    val jobId = parent.expectMsgType[NewJobAvailable].template.jobId
    shares.foreach { case (en1, en2) =>
      probe.send(mgr, ProcessShare(jobId, BigInteger.ONE, en1, en2, "00000000", "127.0.0.1", 4444, "worker"))
      probe.expectMsgType[ShareAccepted]
    }
    collector.fishForMessage() {
      case o: LocalMiningObservation => o.counters.get("accepted").contains(shares.size.toString)
      case _ => false
    }.asInstanceOf[LocalMiningObservation].counters
  }

  "Reduced reporting" should "credit a share with the super-share threshold's work, not tau's" in {
    // The miner is only ever sent tau / NISP_COEFFICIENT, so each share it returns stands for that
    // many more hashes. Crediting TARGET_MAX / tau instead read hashrate low by the whole coefficient.
    val advertised = BigInt(tau) / LFSMHelpers.NISP_COEFFICIENT
    withClue("the captured share must clear what a reduced job advertises: ") {
      advertised should be >= BigInt(fH)
    }
    val counters = countedWork(tau, reduced = true, b, Seq(extraNonce1 -> extraNonce2))
    val credited = BigInt(counters("acceptedAssignedWork"))
    credited shouldEqual LFSMHelpers.TARGET_MAX_LITHOS / advertised
    credited should be >= (LFSMHelpers.TARGET_MAX_LITHOS / BigInt(tau)) * (LFSMHelpers.NISP_COEFFICIENT - 1)
    counters.get("acceptedBelowAdvertised") shouldBe None
    counters("acceptedWithReducedReporting") shouldEqual "1"
  }

  it should "leave the same share at tau's work when the job advertises tau" in {
    val counters = countedWork(tau, reduced = false, b, Seq(extraNonce1 -> extraNonce2))
    BigInt(counters("acceptedAssignedWork")) shouldEqual LFSMHelpers.TARGET_MAX_LITHOS / BigInt(tau)
  }

  it should "credit nothing for an accepted share that missed the advertised threshold" in {
    // Acceptance only needs tau. A miner ignoring its notify difficulty sends shares like these, and
    // crediting each at the advertised threshold's work would report it thousands of times faster.
    val wide = new BigInteger("f" * 64, 16)
    val advertised = wide.divide(BigInteger.valueOf(LFSMHelpers.NISP_COEFFICIENT))
    val shares = (1 to 4).map(i => extraNonce1 -> Array[Byte](0, 0, 0, 0, 0, i.toByte))
    shares.foreach { case (en1, en2) =>
      val hit = Autolykos2PowValidation
        .hitForVersion2ForMessageWithChecks(32, msg, en1 ++ en2, intBytes(height.toInt), n).bigInteger
      withClue(s"nonce ${Hex.toHexString(en1 ++ en2)} must miss the advertised threshold: ") {
        hit.compareTo(advertised) should be > 0
      }
    }
    val counters = countedWork(wide, reduced = true, BigInteger.ONE, shares)
    counters("acceptedBelowAdvertised") shouldEqual "4"
    counters.get("acceptedAssignedWork") shouldBe None
  }
}
