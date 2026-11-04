package transactions.engine.execution

import node.MutationConversions._
import org.ergoplatform.appkit.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.LDNodeFixtures
import work.lithos.mutations.{Contract, UTXO}

/**
 * The arb reserve's plan, against the wallet's own stake rule: whatever it sends must leave a wallet that rule takes
 * a stake from, with the pins served first. And the merge it signs keeps every output at the first address.
 */
class ArbReserveExecutionSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbReserveExecution._

  private val Erg = Parameters.OneErg
  private val Stake = 10 * Erg

  private def boxes(values: Long*): Seq[Plain] =
    values.zipWithIndex.map { case (value, i) => Plain(f"${i + 1}%064x", value, onchain = true) }

  private def planned(missingPins: Int, values: Long*): Plan =
    plan(boxes(values: _*), missingPins, Stake, Fee, maxInputs = 50)

  /** The wallet's boxes after `merge` confirms. */
  private def after(wallet: Seq[Plain], merge: Merge): Seq[Long] =
    wallet.filterNot(b => merge.inputs.exists(_.id == b.id)).map(_.value) ++ merge.outputs

  "The stake rule" should "leave the pins their smallest boxes and need a second free box beside the stake" in {
    stakeable(Seq(Erg, 12 * Erg), missingPins = 0, Stake) shouldBe true
    stakeable(Seq(Erg, 12 * Erg), missingPins = 1, Stake) shouldBe false
    stakeable(Seq(12 * Erg), missingPins = 0, Stake) shouldBe false
    stakeable(Seq(Erg, 2 * Erg, 9 * Erg), missingPins = 0, Stake) shouldBe false
  }

  "A wallet that can already hold a stake" should "send nothing, counting a box still waiting to confirm" in {
    planned(1, Erg, 2 * Erg, 11 * Erg) shouldBe Held
    val waiting = boxes(Erg, 2 * Erg) :+ Plain("ff" * 32, Stake, onchain = false)
    plan(waiting, 0, Stake, Fee, 50) shouldBe Held
  }

  "A wallet under twice the stake" should "send nothing and say how much it holds" in {
    planned(0, 5 * Erg, 5 * Erg, 9 * Erg) shouldBe Short(BigInt(19 * Erg))
    // An unconfirmed box adds nothing it could merge
    plan(boxes(5 * Erg, 9 * Erg) :+ Plain("ff" * 32, 9 * Erg, onchain = false), 0, Stake, Fee, 50) shouldBe
      Short(BigInt(14 * Erg))
  }

  "Many small boxes" should "be merged largest first into one box of the stake and change" in {
    val wallet = boxes(Seq.tabulate(30)(i => Erg + i * 1000L): _*)
    val merge = plan(wallet, 1, Stake, Fee, 50).asInstanceOf[Merge]
    // Ten of the largest come to 245,000 nanoERG over the stake, short of the fee, so an eleventh is taken
    merge.inputs.size shouldBe 11
    merge.inputs.map(_.value) shouldBe wallet.map(_.value).sorted.reverse.take(11)
    merge.outputs.head shouldBe Stake
    merge.outputs.sum + Fee shouldBe merge.inputs.map(_.value).sum
    stakeable(after(wallet, merge), 1, Stake) shouldBe true
  }

  "One large box the pins and the free box rule keep from the stake" should "be split, with a spare for the pin" in {
    val wallet = boxes(50 * Erg)
    planned(1, 50 * Erg) shouldBe a[Merge]
    val merge = planned(1, 50 * Erg).asInstanceOf[Merge]
    merge.outputs shouldBe Vector(Stake, SpareValue, 50 * Erg - Stake - Fee - SpareValue)
    stakeable(after(wallet, merge), 1, Stake) shouldBe true
    // With no pin to serve, the stake and its change are enough
    planned(0, 50 * Erg).asInstanceOf[Merge].outputs shouldBe Vector(Stake, 50 * Erg - Stake - Fee)
  }

  "Change below a box's minimum" should "join the stake box" in {
    // The two largest come to the stake, the fee and half a box's minimum
    val wallet = boxes((Seq(9 * Erg, Erg + Fee + UTXO.MIN_CHANGE / 2) ++ Seq.fill(10)(Erg)): _*)
    val merge = plan(wallet, 0, Stake, Fee, 50).asInstanceOf[Merge]
    merge.inputs.size shouldBe 2
    merge.outputs shouldBe Vector(Stake + UTXO.MIN_CHANGE / 2)
    stakeable(after(wallet, merge), 0, Stake) shouldBe true
  }

  "A spare the change cannot fund" should "take one more input rather than give up" in {
    // The box just over the stake funds no spare, and the pin would take the stake box without one
    val wallet = boxes(Stake + Fee + UTXO.MIN_CHANGE / 2, 9 * Erg + 999000000L)
    planned(1, wallet.map(_.value): _*) match {
      case merge: Merge =>
        merge.inputs.size shouldBe 2
        merge.outputs.take(2) shouldBe Vector(Stake, SpareValue)
        stakeable(after(wallet, merge), 1, Stake) shouldBe true
      case other => fail(s"expected a merge, got $other")
    }
  }

  "A wallet too scattered for the input cap" should "send nothing" in {
    planned(0, Seq.fill(300)(Erg / 10): _*) shouldBe Unreachable(BigInt(30 * Erg))
  }

  "The merge" should "spend plain boxes to its outputs at the first address and the fee, and sign" in {
    val (nodeContext, _, wallet) = support.FakeNodeContext(mock[_root_.node.NodeApi], numAddresses = 1)
    nodeContext.getClient.execute { ctx =>
      val inputs = Seq(30 * Erg, 2 * Erg).zipWithIndex.map { case (v, i) =>
        LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, v), 0, f"${i + 1}%064x")
      }
      val outputs = Seq(Stake, SpareValue, 32 * Erg - Stake - SpareValue - Fee)
      val signed = wallet.sign(build(ctx, wallet, inputs, outputs, Fee))
      val outs = signed.getOutputsToSpend
      outs.size shouldBe 4
      (0 until 3).foreach(i => Contract(outs.get(i).getErgoTree).ergoTreeHex shouldBe wallet.contract.ergoTreeHex)
      (0 until 4).map(outs.get(_).getValue.longValue) shouldBe (outputs :+ Fee)
      an[IllegalArgumentException] should be thrownBy build(ctx, wallet, inputs, outputs.updated(0, Stake + 1), Fee)
    }
  }
}
