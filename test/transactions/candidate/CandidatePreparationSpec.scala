package transactions.candidate

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import configs.BoundedWorker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import transactions.candidate.BlockTxMessages.BlockTxsReady

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._

class CandidatePreparationSpec extends TestKit(ActorSystem("candidate-preparation-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "A build with no free worker slot" should "answer its requesters with nothing and never run" in {
    val worker = new BoundedWorker(system.dispatcher, 1)
    val gate = new CountDownLatch(1)
    worker.submit(gate.await(10, TimeUnit.SECONDS))
    val owner = TestProbe()
    val requester = TestProbe()
    var started = Vector.empty[Int]
    val preparation = new CandidatePreparation(worker, owner.ref, height => started :+= height)
    val ran = new AtomicBoolean(false)

    preparation.start(100, Some(requester.ref)) { ran.set(true); Seq.empty } shouldBe false
    requester.expectMsg(BlockTxsReady(100, Seq.empty))
    started shouldBe empty
    gate.countDown()
    awaitCond(worker.running == 0, 10.seconds)
    ran.get shouldBe false

    // A free slot again, and the result comes back from the build's own task: one slot is enough.
    preparation.start(100, Some(requester.ref))(Seq.empty) shouldBe true
    started shouldBe Vector(100)
    preparation.receive(owner.expectMsgType[CandidatePreparation.Prepared])
    requester.expectMsg(BlockTxsReady(100, Seq.empty))
  }
}
