package stats

import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PaymentLedgerSpec extends AnyFlatSpec with Matchers with LoneElement {
  private def activity(kind: String, nft: String = "nft", miners: Int = 1, claimed: Long = 0, bond: Long = 0,
                       value: Long = 0, local: Boolean = false, localTarget: Boolean = false,
                       lit: Option[Long] = None, tx: String = "") =
    RollupActivity(if (tx.nonEmpty) tx else s"$kind-$nft-$claimed", nft, s"mined-$nft", 40, kind, miners,
      claimed.toString, bond.toString, value.toString, local = local, localTarget = localTarget,
      rewardLit = lit.map(_.toString))
  private def at(height: Int, a: RollupActivity) = LedgerEvent(height, height * 1000L, a)
  private val submitted = at(50, activity("submission", claimed = 100, bond = 2000000, local = true))

  "Open claims" should "stay in holding without a projection until evaluation fixes the entrants" in {
    val claim = PaymentLedger.claims(Vector(submitted)).loneElement
    claim.phase shouldBe "holding"
    claim.score shouldBe "100"
    claim.bondNanoErg shouldBe "2000000"
    claim.minedHeight shouldBe 40
    claim.rewardNanoErg shouldBe None
    claim.rewardBasis shouldBe None
    // The earliest it could be ready: holding runs from the mined block, evaluation straight after.
    claim.payoutReadyFrom shouldBe Some(40 + PaymentLedger.HoldingBlocks + PaymentLedger.EvaluationBlocks)
    PaymentLedger.claims(Vector(submitted), 10, 20).loneElement.payoutReadyFrom shouldBe Some(70)
  }

  it should "project the evaluation share and raise it when a fraud proof removes another miner" in {
    // 4 ERG pool after 6 ERG of bonds; this client holds 100 of 400.
    val evaluation = at(60, activity("evaluation", miners = 3, claimed = 400, bond = 6000000, value = 10000000000L))
    val projected = PaymentLedger.claims(Vector(submitted, evaluation)).loneElement
    projected.phase shouldBe "evaluation"
    projected.rewardBasis shouldBe Some("projected")
    // Evaluation's period starts where the transition landed, not where holding could have ended.
    projected.payoutReadyFrom shouldBe Some(60 + PaymentLedger.EvaluationBlocks)
    projected.rewardNanoErg shouldBe Some((BigInt(9994000000L) * 100 / 400).toString)
    val fraud = at(61, activity("fraudProof", claimed = 150, bond = 3000000, value = 3000000))
    val raised = PaymentLedger.claims(Vector(submitted, evaluation, fraud)).loneElement
    raised.rollupScore shouldBe Some("250")
    raised.rollupMiners shouldBe Some(2)
    raised.rewardNanoErg shouldBe Some((BigInt(9994000000L) * 100 / 250).toString)
  }

  it should "report the exact ERG and LIT share once the payout is ready" in {
    val ready = at(70, activity("payoutReady", miners = 2, claimed = 300, bond = 4000000, value = 10000001, lit = Some(101)))
    val claim = PaymentLedger.claims(Vector(submitted, ready)).loneElement
    claim.phase shouldBe "payout"
    claim.rewardBasis shouldBe Some("exact")
    // Matches the payout accounting's floor division for the same share.
    claim.rewardNanoErg shouldBe Some("3333333")
    claim.rewardLit shouldBe Some("33")
    claim.phaseHeight shouldBe 70
    claim.payoutReadyFrom shouldBe None
  }

  it should "mark a claim slashed when a fraud proof removes this client, even after the payout was projected" in {
    val evaluation = at(60, activity("evaluation", miners = 2, claimed = 300, bond = 4000000, value = 9000000))
    val slash = at(62, activity("fraudProof", claimed = 100, bond = 2000000, localTarget = true, tx = "slash"))
    val claim = PaymentLedger.claims(Vector(submitted, evaluation, slash)).loneElement
    claim.phase shouldBe "slashed"
    claim.slashedTransactionId shouldBe Some("slash")
    claim.rewardNanoErg shouldBe None
  }

  it should "drop a rollup whose submission has been pruned instead of inventing its share" in {
    val orphan = at(60, activity("evaluation", nft = "old", miners = 2, claimed = 300, value = 9000000))
    PaymentLedger.claims(Vector(orphan)) shouldBe empty
    PaymentLedger.claims(Vector(orphan, submitted)).map(_.rollupNft) shouldBe Vector("nft")
  }

  it should "list newer submissions first" in {
    val later = at(80, activity("submission", nft = "b", claimed = 5, bond = 1, local = true))
    PaymentLedger.claims(Vector(submitted, later)).map(_.rollupNft) shouldBe Vector("b", "nft")
  }

  /** Keys as the store writes them, in its byte order. */
  private def keys(payouts: (Int, Int, String)*) = payouts.map { case (paid, mined, out) =>
    PaymentLedger.paymentKey(MiningPaymentRecord("tx", out, "box", s"nft-$out", "mined", mined, 0L, "block", paid, 0L, "1"))
  }.toVector.sorted
  private def paidOf(page: Vector[String]) = page.map(PaymentLedger.paymentKeyParts(_).paidHeight)
  private def minedOf(page: Vector[String]) = page.map(PaymentLedger.paymentKeyParts(_).minedHeight)

  "Payout pages" should "run newest first and cover every payout exactly once across consecutive offsets" in {
    val all = keys((10, 5, "a"), (20, 8, "a2"), (20, 9, "b"), (30, 7, "c"))
    val first = PaymentLedger.page(all, 0, 2)
    paidOf(first) shouldBe Vector(30, 20)
    val second = PaymentLedger.page(all, 2, 2)
    paidOf(second) shouldBe Vector(20, 10)
    (first ++ second).sorted shouldBe all
    PaymentLedger.page(all, 4, 2) shouldBe empty
    intercept[IllegalArgumentException](PaymentLedger.page(all, 0, 0))
    intercept[IllegalArgumentException](PaymentLedger.page(all, -1, 1))
  }

  it should "order by the rollup's own block, either way, without losing a payout" in {
    // Paid order and mined order disagree: the rollup mined at 9 paid before the one mined at 7.
    val all = keys((10, 5, "a"), (20, 9, "b"), (30, 7, "c"))
    minedOf(PaymentLedger.page(all, 0, 3, "mined")) shouldBe Vector(9, 7, 5)
    minedOf(PaymentLedger.page(all, 0, 3, "mined", ascending = true)) shouldBe Vector(5, 7, 9)
    paidOf(PaymentLedger.page(all, 0, 3, "paid", ascending = true)) shouldBe Vector(10, 20, 30)
    PaymentLedger.page(all, 0, 2, "mined").map(PaymentLedger.paymentKeyParts(_).nft) shouldBe Vector("nft-b", "nft-c")
    intercept[IllegalArgumentException](PaymentLedger.page(all, 0, 1, "reward"))
  }
}
