package mining

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import evaluation.NTable
import mining.MiningMessages._
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import sigma.pow.Autolykos2PowValidation
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
}
