package contracts.specs.rollup

import lfsm.LFSMHelpers
import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_NotInWindow.ergo` — a share is honest work for this rollup only if it was mined inside
 * `[currentBlock - NISP_WINDOW, currentBlock]`.
 *
 * `currentBlock` is the rollup's own block height out of the evaluation box's R7, not the height the
 * NISP was submitted at. That is deliberate: submission height is nowhere on chain and is the miner's
 * to choose, so anchoring there would let them widen their own window.
 *
 * The proof reads every header through `deserializeTo[Header]`, so it is only safe to run once
 * `FP_InvalidFormat` and `FP_MalformedGE` have declined. The boundary properties below are the ones
 * that matter: an off-by-one either slashes honest miners or admits stale work.
 */
class FPNotInWindowSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val score = 100000L
  private val window = LFSMHelpers.NISP_WINDOW

  /** Ten shares descending from `top`, so `top` is the newest height in the NISP. */
  private def sharesFrom(ctx: BlockchainContext, top: Long): Seq[SuperShare] =
    NispFixtures.cleanShares(top.toInt, NispFixtures.pkOf(miner(ctx)))

  private def nispFrom(ctx: BlockchainContext, top: Long): Array[Byte] =
    NispFixtures.nisp(score, sharesFrom(ctx, top))

  /** Nine honest shares plus one at `stray`, which is what `exists` has to find. */
  private def nispWithStray(ctx: BlockchainContext, stray: Long): Array[Byte] = {
    val pk = NispFixtures.pkOf(miner(ctx))
    val block = rollupBlock(ctx)
    val honest = (0 until 9).map(i => NispFixtures.superShare(block.toInt - i, pk,
      timestamp = 1700000000000L + i))
    NispFixtures.nisp(score, honest :+ NispFixtures.superShare(stray.toInt, pk,
      timestamp = 1700000000000L + 9))
  }

  private def proof(ctx: BlockchainContext, nisp: Array[Byte]): Fraud =
    fraud(ctx, fpNotInWindow(ctx), nisp, score)

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: every share inside the window, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = proof(ctx, nispFrom(ctx, rollupBlock(ctx)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the two boundaries ───────────────────────────────────────────────────
  //
  // Both are inclusive: `h < currentBlock - WINDOW || h > currentBlock` is the fraud condition, so
  // the endpoints themselves are honest. Ten shares descend from the newest, so the top boundary is
  // posed by the newest share and the bottom one by the oldest.

  property("boundary: the newest share exactly at currentBlock is honest") {
    withCtx { ctx =>
      val f = proof(ctx, nispFrom(ctx, rollupBlock(ctx)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("boundary: a share one block above currentBlock is fraud") {
    withCtx { ctx =>
      val f = proof(ctx, nispWithStray(ctx, rollupBlock(ctx) + 1L))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("boundary: the oldest share exactly at currentBlock - NISP_WINDOW is honest") {
    withCtx { ctx =>
      // Ten shares descend one block each, so the oldest sits nine below the top.
      val f = proof(ctx, nispFrom(ctx, rollupBlock(ctx) - window + 9L))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("boundary: a share one block below currentBlock - NISP_WINDOW is fraud") {
    withCtx { ctx =>
      val f = proof(ctx, nispWithStray(ctx, rollupBlock(ctx) - window - 1L))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the predicate is an exists, not a forall ────────────────────────────

  property("stray: one share far in the future among nine honest ones is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispWithStray(ctx, rollupBlock(ctx) + 5000L))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("stray: one share far in the past among nine honest ones is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispWithStray(ctx, rollupBlock(ctx) - 5000L))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The whole NISP shifted forward. Anchoring on submission height rather than on the rollup's block
   * would make this honest, which is the attack the anchor choice exists to stop.
   */
  property("anchor: a NISP mined entirely after the rollup's block is fraud") {
    withCtx { ctx =>
      val f = proof(ctx, nispFrom(ctx, rollupBlock(ctx) + 300L))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  transitionChecks("notInWindow") { ctx =>
    proof(ctx, nispWithStray(ctx, rollupBlock(ctx) + 5000L))
  }
}
