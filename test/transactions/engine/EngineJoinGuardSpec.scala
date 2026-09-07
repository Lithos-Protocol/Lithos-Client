package transactions.engine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import work.lithos.mutations.UTXO
import org.ergoplatform.sdk.ErgoId

class EngineJoinGuardSpec extends TestKit(ActorSystem("engine-join-guard-spec", ConfigFactory.load()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  import EngineWalletMessages._
  import EngineJoinGuard._
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  "Lender key ownership" should "survive uncertain send cleanup until every funding input is reconciled" in {
    val (ctx, _, wallet) = FakeNodeContext()
    val inputs = ctx.getClient.execute(c => (1 to 2).map(i =>
      UTXO(wallet.contract, 2000000L).toInput(c, ErgoId.create(f"$i%064x"), 0.toShort)))
    val engine = system.actorOf(Props(new EngineWalletState(ctx)))
    val probe = TestProbe()
    val key = "ab" * 32
    val txId = "cd" * 32
    val ids = inputs.map(_.id.toString).toSet
    try {
      probe.send(engine, ReserveKnownInputs(inputs, "funding", Long.MaxValue))
      probe.expectMsgType[WalletInputs].inputs.size shouldBe 2
      probe.send(engine, Acquire(key, "first")); probe.expectMsg(true)
      probe.send(engine, Acquire(key, "second")); probe.expectMsg(false)
      probe.send(engine, Pin(key, "first", txId, Set("funding"))); probe.expectMsg(true)
      probe.send(engine, PinEngineInputs(EngineHold("join", "funding", txId, ids, ids)))
      probe.expectMsg(true)
      probe.send(engine, EngineSendFinished("funding", txId, accepted = false))
      probe.send(engine, Cancel(key, "first"))
      probe.send(engine, Keys); probe.expectMsg(Set(key))
      probe.send(engine, ResolveEngineInputs("funding", txId, Set(inputs.head.id.toString), Set.empty))
      probe.expectMsg(true)
      probe.send(engine, Keys); probe.expectMsg(Set(key))
      probe.send(engine, ResolveEngineInputs("funding", txId, Set.empty, Set(inputs.last.id.toString)))
      probe.expectMsg(true)
      probe.send(engine, Keys); probe.expectMsg(Set.empty[String])
    } finally system.stop(engine)
  }
  it should "release a build-only key by exact lease identity" in {
    val engine = system.actorOf(Props(new EngineWalletState(FakeNodeContext()._1)))
    val probe = TestProbe()
    val key = "ab" * 32
    try {
      probe.send(engine, Acquire(key, "current")); probe.expectMsg(true)
      probe.send(engine, Cancel(key, "obsolete"))
      probe.send(engine, Keys); probe.expectMsg(Set(key))
      probe.send(engine, Cancel(key, "current"))
      probe.send(engine, Keys); probe.expectMsg(Set.empty[String])
    } finally system.stop(engine)
  }
}
