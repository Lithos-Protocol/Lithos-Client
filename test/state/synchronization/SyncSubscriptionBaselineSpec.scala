package state.synchronization

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.Subscribable.{Subscribe, SubscribeAck, SubscribeRejected}

import scala.concurrent.duration._

object SyncSubscriptionBaselineSpec {
  private case class SetHeight(height: Int)
  private case object HeightSet
  private case class Publish(value: String)

  private class Publisher(initialHeight: Int) extends Actor with Subscribable {
    override protected var currentHeight: Int = initialHeight
    override protected var subscribers = Set.empty[akka.actor.ActorRef]
    override protected val logger: Logger = LoggerFactory.getLogger("SyncSubscriptionBaseline")

    override def receive: Receive = handleSubscriptions orElse {
      case SetHeight(height) =>
        currentHeight = height
        sender() ! HeightSet
      case Publish(value) =>
        subscribers.foreach(_ ! value)
    }
  }
}

/**
 * Covers height-gated subscription handoff.
 * Ordering between different message senders is outside this contract.
 */
class SyncSubscriptionBaselineSpec extends TestKit(ActorSystem("sync-subscription-baseline"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  import SyncSubscriptionBaselineSpec._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "A synchronization publisher" should "accept only a subscriber at its current height" in {
    val publisher = system.actorOf(Props(new Publisher(100)))
    val requester = TestProbe()
    val subscriber = TestProbe()

    requester.send(publisher, Subscribe(99, subscriber.ref))
    requester.expectMsgType[SubscribeRejected]
    publisher ! Publish("rejected")
    subscriber.expectNoMessage(200.millis)

    requester.send(publisher, Subscribe(100, subscriber.ref))
    requester.expectMsg(SubscribeAck)
    publisher ! Publish("accepted")
    subscriber.expectMsg("accepted")
  }

  it should "deliver once when the same actor subscribes repeatedly" in {
    val publisher = system.actorOf(Props(new Publisher(200)))
    val requester = TestProbe()
    val subscriber = TestProbe()

    requester.send(publisher, Subscribe(200, subscriber.ref))
    requester.expectMsg(SubscribeAck)
    requester.send(publisher, Subscribe(200, subscriber.ref))
    requester.expectMsg(SubscribeAck)

    publisher ! Publish("one-delivery")
    subscriber.expectMsg("one-delivery")
    subscriber.expectNoMessage(200.millis)
  }

  it should "reject the former height after the publisher advances" in {
    val publisher = system.actorOf(Props(new Publisher(300)))
    val requester = TestProbe()
    val oldSubscriber = TestProbe()
    val currentSubscriber = TestProbe()

    requester.send(publisher, SetHeight(301))
    requester.expectMsg(HeightSet)
    requester.send(publisher, Subscribe(300, oldSubscriber.ref))
    requester.expectMsgType[SubscribeRejected]
    requester.send(publisher, Subscribe(301, currentSubscriber.ref))
    requester.expectMsg(SubscribeAck)

    publisher ! Publish("height-301")
    oldSubscriber.expectNoMessage(200.millis)
    currentSubscriber.expectMsg("height-301")
  }
}
