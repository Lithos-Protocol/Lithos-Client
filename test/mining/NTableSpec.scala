package mining

import evaluation.NTable
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.pow.Autolykos2PowValidation
import sigma.pow.Autolykos2PowValidation.NIncreasementHeightMax

/**
 * `NTable.lookUp` supplies N to the Autolykos2 hit computation for every share, and the audit calls
 * this the highest-value test in the whole document.
 *
 * The defect it pins: the lookup searched an ASCENDING table with `find`, and `height >= 0` holds
 * for every height, so it always returned the genesis N of 2^26. Correct below 614,400 — the first
 * increase — and wrong above it, where shares that are really blocks go unrecognised and shares that
 * are not are accepted. On testnet nothing looks wrong. On mainnet, far past that height, PoW
 * validation is simply incorrect and blocks are missed silently.
 *
 * `Autolykos2PowValidation.calcN` is the oracle: it is what the table is generated from, and what
 * the consensus rules use. Comparing the lookup against a hand-written expectation would only pin
 * the table's own arithmetic, not its agreement with the chain.
 */
class NTableSpec extends AnyFlatSpec with Matchers {

  /** Every height at which N changes, taken from the table itself rather than hardcoded. */
  private val increaseHeights: Seq[Int] = NTable.N_Table.map(_._1).toSeq

  "NTable.lookUp" should "agree with calcN at every boundary and on both sides of it" in {
    val probes = increaseHeights.flatMap(h => Seq(h - 1, h, h + 1)).filter(_ >= 0).distinct.sorted
    probes.foreach { h =>
      withClue(s"at height $h: ") {
        NTable.lookUp(h) shouldEqual Autolykos2PowValidation.calcN(h)
      }
    }
  }

  it should "return the genesis N below the first increase" in {
    val firstIncrease = increaseHeights.filter(_ > 0).min
    firstIncrease shouldEqual 614400 // the value the old bug was accidentally correct below

    Seq(0, 1, 1000, firstIncrease - 1).foreach { h =>
      NTable.lookUp(h) shouldEqual Autolykos2PowValidation.calcN(h)
      NTable.lookUp(h) shouldEqual (1 << 26)
    }
  }

  it should "NOT return the genesis N above the first increase" in {
    // The whole shape of the old defect in one assertion: it returned 2^26 for every height.
    val firstIncrease = increaseHeights.filter(_ > 0).min
    NTable.lookUp(firstIncrease) should not equal (1 << 26)
  }

  it should "agree with calcN at the heights this client actually runs at" in {
    // Testnet around the height the audit's live runs were at, and mainnet where the previous
    // behaviour would have been silently wrong on every share.
    Seq(479451, 1848582).foreach { h =>
      withClue(s"at height $h: ") {
        NTable.lookUp(h) shouldEqual Autolykos2PowValidation.calcN(h)
      }
    }
  }

  it should "be non-decreasing across the whole table" in {
    // The lookup takes the last entry at or below the height, which is only the right answer if N
    // never decreases. Stated as an assertion rather than left as an assumption.
    val values = NTable.N_Table.map(_._2).toSeq
    values shouldEqual values.sorted
  }

  it should "hold past the last increase, including beyond the generator's ceiling" in {
    val last = increaseHeights.max
    Seq(last, last + 1, NIncreasementHeightMax, NIncreasementHeightMax + 1).foreach { h =>
      withClue(s"at height $h: ") {
        NTable.lookUp(h) shouldEqual Autolykos2PowValidation.calcN(h)
      }
    }
  }

  it should "answer a height of zero rather than throwing" in {
    // Reachable from a malformed job. `validateShare` runs on the actor thread, where a throw
    // restarts the job manager and empties every job.
    noException should be thrownBy NTable.lookUp(0)
    NTable.lookUp(0) shouldEqual Autolykos2PowValidation.calcN(0)
  }
}
