package configs

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

class BoundedWorkerSpec extends TestKit(ActorSystem("bounded-worker-spec", ConfigFactory.load()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "The engine candidate worker" should "refuse work past its pool and queue rather than run it on the caller" in {
    // Akka runs a task its full queue rejects on the submitting thread. For the engine that thread
    // is its own mailbox, and the build then waits on asks only that mailbox can answer.
    val worker = BoundedWorkers(system)(Contexts.EngineCandidate)
    worker.capacity shouldBe 6
    val gate = new CountDownLatch(1)
    val held = (1 to worker.capacity).map(_ => worker.submit(gate.await(10, TimeUnit.SECONDS)))
    val ranOn = new AtomicReference[Thread]()
    try {
      held.forall(_.isDefined) shouldBe true
      worker.submit(ranOn.set(Thread.currentThread())) shouldBe None
      ranOn.get shouldBe null
    } finally gate.countDown()
    awaitCond(worker.running == 0, 10.seconds)
    worker.submit(()) should not be empty
  }

  it should "be one instance per dispatcher, so every submitter counts against the same slots" in {
    BoundedWorkers(system)(Contexts.EngineCandidate) should be theSameInstanceAs
      BoundedWorkers(system)(Contexts.EngineCandidate)
  }
}
