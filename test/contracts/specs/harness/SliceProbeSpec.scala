package contracts.specs.harness

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, UTXO}

/**
 * Language behaviour rather than a contract: what `slice` and `zip` do when asked for more elements
 * than exist.
 */
class SliceProbeSpec extends AnyPropSpec with ContractSpecBase {

  private def spend(ctx: BlockchainContext, src: String, outs: Int) = {
    val prover = miner(ctx)
    val box = UTXO(Contract.fromErgoScript(ctx, ConstantsBuilder.empty(), src), boxValue)
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
    val outputs = (1 to outs).map(_ => UTXO(contractOf(prover), boxValue / (outs + 1)))
    (prover, build(ctx, Seq(box, fundingInput(ctx, prover)), outputs, prover.getAddress))
  }

  property("slice past the end clamps to what exists rather than throwing") {
    withCtx { ctx =>
      // One output plus the fee box appkit appends, so OUTPUTS.size is 2 against a slice of 9.
      val (p, tx) = spend(ctx, "{ sigmaProp(OUTPUTS.slice(0, 9).size == OUTPUTS.size) }", 1)
      accepts(p, tx)
    }
  }

  property("apply past the end throws, which is what makes the two differ") {
    withCtx { ctx =>
      val (p, tx) = spend(ctx, "{ sigmaProp(OUTPUTS(9).value > 0L) }", 1)
      rejects(p, tx)
    }
  }

  /**
   * Zip stops at the shorter side, so a predicate that
   * no element could satisfy still holds for every element that was dropped.
   */
  property("zip truncates to the shorter side, so dropped elements go unchecked") {
    withCtx { ctx =>
      val unsatisfiable = "(x: (Box, Int)) => x._1.value == 999999999999L"
      // Two outputs exist, so two pairs are formed and both fail the predicate.
      val (p, checked) = spend(ctx,
        s"{ sigmaProp(OUTPUTS.slice(0, 9).zip(Coll(1,2,3,4,5,6,7,8,9)).forall{ $unsatisfiable }) }", 1)
      rejects(p, checked)

      // Slicing to nothing forms no pairs at all, and the same predicate now holds vacuously.
      val (q, vacuous) = spend(ctx,
        s"{ sigmaProp(OUTPUTS.slice(0, 0).zip(Coll(1,2,3)).forall{ $unsatisfiable }) }", 1)
      accepts(q, vacuous)
    }
  }
}
