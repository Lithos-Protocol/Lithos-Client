package support

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
 * Proves the two pieces of test infrastructure work before anything is built on them: an offline
 * BlockchainContext from the appkit fixtures, and an Akka testkit system.
 *
 * Worth its own file. When a test that uses both fails, this says which half is broken.
 */
class HarnessProbeSpec extends TestKit(ActorSystem("harness-probe"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "The offline context" should "open without a node and report a height" in {
    OfflineContext.withCtx { ctx =>
      ctx.getHeight should be > 0
    }
  }

  it should "build a prover whose address round-trips through Contract" in {
    OfflineContext.withCtx { ctx =>
      val prover = OfflineContext.proverWith(ctx, java.math.BigInteger.valueOf(1001L))
      OfflineContext.contractOf(prover).ergoTreeHex shouldEqual
        work.lithos.mutations.Contract.fromAddress(prover.getAddress).ergoTreeHex
    }
  }

  "The testkit" should "deliver a message to a probe" in {
    val probe = TestProbe()
    probe.ref ! "ping"
    probe.expectMsg("ping")
  }
}
