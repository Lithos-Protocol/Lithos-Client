package contracts.specs.rollup

import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_NonUniqueHeaders.ergo` — ten super-shares have to be ten distinct headers.
 *
 * Without it a miner mines one super-share and submits it ten times, paying a tenth of the work for a
 * full NISP. Nothing else in the set catches that: each copy is individually in window, individually
 * clears the threshold, and individually proves its own transaction.
 *
 * The comparison is `Header == Header` over deserialized headers, which compares their ids, so two
 * shares differing anywhere in the header are distinct — including in fields no other proof reads.
 */
class FPNonUniqueHeadersSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val score = 100000L

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  /** Ten distinct headers, one block apart, which is what honest mining produces. */
  private def distinct(ctx: BlockchainContext): Seq[SuperShare] =
    NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))

  private def proof(ctx: BlockchainContext, shares: Seq[SuperShare]): Fraud =
    fraud(ctx, fpNonUniqueHeaders(ctx), NispFixtures.nisp(score, shares), score)

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: ten distinct headers, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = proof(ctx, distinct(ctx))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the fraud ────────────────────────────────────────────────────────────

  property("duplicate: the same share submitted ten times is caught") {
    withCtx { ctx =>
      val one = NispFixtures.superShare(rollupBlock(ctx).toInt, pk(ctx))
      val f = proof(ctx, Seq.fill(10)(one))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /** One repeat among nine distinct headers — the cheapest fraud this proof has to find. */
  property("duplicate: a single repeated header among nine distinct ones is caught") {
    withCtx { ctx =>
      val shares = distinct(ctx)
      val f = proof(ctx, shares.dropRight(1) :+ shares.head)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("duplicate: a repeat in the first and last positions is caught") {
    withCtx { ctx =>
      val shares = distinct(ctx)
      val f = proof(ctx, shares.head +: shares.slice(1, 9) :+ shares.head)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * Two headers at the same height but different timestamps are two different blocks, so this is not
   * a repeat. Worth pinning: a proof that compared heights rather than headers would fire here and
   * slash a miner who legitimately built twice on the same parent.
   */
  property("distinct: two shares at the same height with different timestamps are honest") {
    withCtx { ctx =>
      val h = rollupBlock(ctx).toInt
      val shares = (0 until 10).map(i => NispFixtures.superShare(h, pk(ctx),
        timestamp = 1700000000000L + i))
      val f = proof(ctx, shares)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** Headers differing only in a field no other proof looks at are still distinct headers. */
  property("distinct: shares differing only in their transactions root are honest") {
    withCtx { ctx =>
      val h = rollupBlock(ctx).toInt
      val shares = (0 until 10).map(i => NispFixtures.superShare(h, pk(ctx),
        txRoot = scorex.crypto.hash.Blake2b256.hash(s"txRoot-$i")))
      val f = proof(ctx, shares)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  transitionChecks("nonUniqueHeaders") { ctx =>
    val one = NispFixtures.superShare(rollupBlock(ctx).toInt, pk(ctx))
    proof(ctx, Seq.fill(10)(one))
  }
}
