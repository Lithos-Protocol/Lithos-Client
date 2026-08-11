package transactions

import lfsm.{CollateralParams, EmissionSchedule}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `EmissionSchedule` and `CollateralParams` mirror `LIT_Emissions.ergo` and
 * `Collateral_Enforcer.ergo`. They are kept in one file so a divergence shows up by diffing two
 * files rather than by a rejected transaction — and these are what say so out loud.
 *
 * What the contract specs cannot cover: they exercise the CONTRACT's arithmetic. These exercise the
 * builder's copy of it, which is what decides the numbers an Activate actually writes.
 */
class EmissionScheduleSpec extends AnyFlatSpec with Matchers {

  import EmissionSchedule._

  private val plenty = Long.MaxValue / 4 // never the binding constraint

  private def blockIn(epoch: Int): Int = epoch * EPOCH_LENGTH

  // ─── the two behaviours §26 called out ────────────────────────────────────

  "emissionAt" should "cap the total by the LIT actually held, not the nominal rate" in {
    // The contract emits min(held, rate). A builder that wrote the nominal rate would produce a
    // token count the emission box cannot cover, and every Activate would be rejected.
    val (total, _, _) = emissionAt(blockIn(0), litHeld = 1L)
    total shouldEqual 1L

    val nominal = pubRateAt(blockIn(0)) + privRateAt(blockIn(0))
    emissionAt(blockIn(0), plenty)._1 shouldEqual nominal
    emissionAt(blockIn(0), nominal)._1 shouldEqual nominal
    emissionAt(blockIn(0), nominal - 1)._1 shouldEqual (nominal - 1)
  }

  it should "pay founders in full or not at all" in {
    // `emittedPriv` is all-or-nothing: if the held balance cannot cover the private rate, the
    // founders map is empty rather than partial. A partial split is a rejected transaction.
    val priv = privRateAt(blockIn(0))
    priv should be > 0L

    val (_, _, paidSplit) = emissionAt(blockIn(0), plenty)
    paidSplit.map(_._2).sum shouldEqual priv
    paidSplit should have size 3

    val (total, pub, emptySplit) = emissionAt(blockIn(0), priv - 1)
    total shouldEqual (priv - 1)
    pub shouldEqual total       // nothing went to founders, so all of it is public
    emptySplit shouldBe empty
  }

  it should "order the founders exactly as the contract's literal Coll does" in {
    // R6 is compared structurally, so the ORDER is load bearing, not just the amounts.
    val (_, _, split) = emissionAt(blockIn(0), plenty)
    split.map(_._2) shouldEqual Seq(F1_RATE, F2_RATE, F3_RATE)
    // By value: these are Arrays, which compare by reference, and the keys are what R6 is compared on.
    split.map(_._1.toSeq) shouldEqual founders.map(_._1.toSeq)
  }

  it should "split the total into public and founders' shares that add up" in {
    Seq(0, 1, 7, 8, 13, 14, 15, 16, 21).foreach { epoch =>
      val (total, pub, split) = emissionAt(blockIn(epoch), plenty)
      withClue(s"epoch $epoch: ") {
        pub + split.map(_._2).sum shouldEqual total
      }
    }
  }

  // ─── the schedule's own boundaries ────────────────────────────────────────

  "privRateAt" should "stop exactly at the end of the private period" in {
    privRateAt(blockIn(PRIV_LENGTH - 1)) shouldEqual INIT_PRIV_RATE
    privRateAt(blockIn(PRIV_LENGTH) - 1) shouldEqual INIT_PRIV_RATE
    privRateAt(blockIn(PRIV_LENGTH)) shouldEqual 0L
    privRateAt(blockIn(PRIV_LENGTH + 1)) shouldEqual 0L
  }

  "pubRateAt" should "decay by one step every P1_DECAY_LENGTH epochs" in {
    pubRateAt(blockIn(0)) shouldEqual INIT_PUB_RATE
    pubRateAt(blockIn(1)) shouldEqual INIT_PUB_RATE
    pubRateAt(blockIn(2)) shouldEqual (INIT_PUB_RATE - PUB_DECAY)
    pubRateAt(blockIn(3)) shouldEqual (INIT_PUB_RATE - PUB_DECAY)
    pubRateAt(blockIn(4)) shouldEqual (INIT_PUB_RATE - 2 * PUB_DECAY)
  }

  it should "step to the P2 rates at their epochs" in {
    pubRateAt(blockIn(P2_START - 1)) shouldEqual (INIT_PUB_RATE - ((P2_START - 1) / P1_DECAY_LENGTH) * PUB_DECAY)
    pubRateAt(blockIn(P2_START)) shouldEqual P2_RATE_1
    pubRateAt(blockIn(P2_START + 1)) shouldEqual P2_RATE_2
    pubRateAt(blockIn(P3_START - 1)) shouldEqual P2_RATE_2
  }

  it should "settle at the P3 rate and then stop at the final block" in {
    pubRateAt(blockIn(P3_START)) shouldEqual P3_RATE
    pubRateAt(blockIn(P3_START + 1)) shouldEqual P3_RATE
    pubRateAt(FINAL_BLOCK - 1) shouldEqual P3_RATE
    pubRateAt(FINAL_BLOCK) shouldEqual 0L
    pubRateAt(FINAL_BLOCK + 1) shouldEqual 0L
  }

  it should "never go negative anywhere in the schedule" in {
    // The P1 branch subtracts a multiple of PUB_DECAY, so an epoch boundary moving could take it
    // below zero and mint a negative token amount into the collateral box.
    (0 to FINAL_BLOCK by EPOCH_LENGTH / 4).foreach { h =>
      withClue(s"height $h: ") { pubRateAt(h) should be >= 0L }
    }
    pubRateAt(FINAL_BLOCK) shouldEqual 0L
  }

  "emissionAt past the final block" should "emit nothing at all" in {
    val (total, pub, split) = emissionAt(FINAL_BLOCK, plenty)
    total shouldEqual 0L
    pub shouldEqual 0L
    split shouldBe empty
  }

  // ─── enforcer constants ───────────────────────────────────────────────────

  "The principal floor" should "be the block reward less the fee cap plus the dust budget" in {
    // 2.915 ERG. Every fee-less Activate and Clear balances against this exact number.
    CollateralParams.PRINCIPAL_FLOOR shouldEqual 2915000000L
    CollateralParams.PRINCIPAL_FLOOR shouldEqual
      (CollateralParams.BLOCK_REWARD - CollateralParams.FEE_CAP + CollateralParams.DUST_BUDGET)
  }

  it should "leave a real change box after a fee-less Clear" in {
    // The forfeit is the whole principal, and it has to clear the change minimum or TxBuilder folds
    // it into a fee output that a fee-less transaction does not have.
    CollateralParams.PRINCIPAL_FLOOR should be > work.lithos.mutations.UTXO.MIN_CHANGE
  }

  "The fee cap" should "be exactly 3% of the block reward" in {
    CollateralParams.FEE_CAP shouldEqual (CollateralParams.BLOCK_REWARD * 3 / 100)
  }

  "The dust budget" should "cover the genesis transaction's worst case with margin" in {
    // Founders 3x1e6 + permit return 1e6 + finder 1e5 + proof-of-spend 1.5e5 = 4.25M during the
    // founder period, 1.25M after. The audit asserts these numbers; here they are checked.
    val duringFounderPeriod = 3 * 1000000L + 1000000L + 100000L + 150000L
    val afterFounderPeriod = 1000000L + 100000L + 150000L
    duringFounderPeriod shouldEqual 4250000L
    afterFounderPeriod shouldEqual 1250000L
    CollateralParams.DUST_BUDGET should be > duringFounderPeriod
  }
}
