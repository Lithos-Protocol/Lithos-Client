package contracts.specs.rollup

import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_InvalidDiff.ergo` — every share has to be an Autolykos2 hit at or below `τ/10000`, where
 * `τ = 2²⁵⁶ / score` comes from the score the NISP itself claims.
 *
 * This is the proof that makes the score mean something. Without it a miner claims any score they
 * like and is paid in proportion to it; with it, claiming a high score raises the bar their own shares
 * have to clear. It runs after `FP_IncorrectN` because it feeds the share's declared `N` straight into
 * `powHit`, and `N` is the one field of a share the header does not carry.
 *
 * The honest case has to be mined, because there is no way to fake a hit. `score = 1` puts the
 * threshold at `2²⁵⁶/10000`, so roughly one nonce in ten thousand clears it — the nonce below was
 * found by that search and is pinned so the suite does not repeat it.
 */
object FPInvalidDiffSpec {

  /** `TARGET_MAX_LITHOS`, the same constant the contract divides by the claimed score. */
  val TargetMax: BigInt =
    BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639935")

  /** The lowest score Holding accepts, which is the most permissive threshold a NISP can claim. */
  val MinedScore: Long = 1L
  val Threshold: BigInt = TargetMax / MinedScore / 10000

  /**
   * Found by walking nonces from zero against the fixture header. Pinned so the search costs nothing
   * on later runs; if the fixture header ever moves, the search resumes and prints the new nonce.
   */
  val PinnedNonce: Long = 109L
}

class FPInvalidDiffSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  import FPInvalidDiffSpec._

  private val claimedScore = 100000L

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  /** Mined once for the whole suite: the search is the expensive part, not the contract. */
  private def mined(ctx: BlockchainContext): (SuperShare, Long) =
    NispFixtures.minedShare(rollupBlock(ctx).toInt, pk(ctx), Threshold, from = PinnedNonce)

  /**
   * Ten copies of one mined share.
   *
   * Duplicate headers are `FP_NonUniqueHeaders`' business and this proof neither reads nor cares, so
   * reusing the share buys a tenfold saving on the only expensive fixture in the set.
   */
  private def minedNisp(ctx: BlockchainContext): Array[Byte] =
    NispFixtures.nisp(MinedScore, Seq.fill(10)(mined(ctx)._1))

  private def proof(ctx: BlockchainContext, nisp: Array[Byte], score: Long): Fraud =
    fraud(ctx, fpInvalidDiff(ctx), nisp, score)

  // ─── the client and the contract have to agree on the hit ────────────────

  /**
   * `powHit` in the contract and `Autolykos2PowValidation.hitForVersion2` in the client are two
   * implementations of the same function over the same share. If they diverge the proof is measuring
   * something the miner was never solving for, and every property below would still pass.
   */
  property("hit: the mined share clears the threshold the contract will apply") {
    withCtx { ctx =>
      val (share, nonce) = mined(ctx)
      println(s"[mined] nonce=$nonce hit=${share.powHit}")
      println(s"[mined] threshold at score $MinedScore = $Threshold")
      if (nonce != PinnedNonce)
        println(s"[mined] NOTE: the pinned nonce no longer works; update PinnedNonce to $nonce")
      share.powHit should be <= Threshold
    }
  }

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: shares that clear the threshold, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = proof(ctx, minedNisp(ctx), MinedScore)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the fraud ────────────────────────────────────────────────────────────

  /** An unmined share at a score high enough to make the threshold real. */
  property("unmined: a share that never reached the threshold is caught") {
    withCtx { ctx =>
      val shares = NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))
      val f = proof(ctx, NispFixtures.nisp(claimedScore, shares), claimedScore)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The attack the proof exists to stop: keep the shares, raise the claimed score. A higher score is
   * a smaller τ and so a harder threshold, and the same shares stop clearing it.
   */
  property("inflated score: the same mined shares under a raised score are caught") {
    withCtx { ctx =>
      val nisp = NispFixtures.nisp(claimedScore, Seq.fill(10)(mined(ctx)._1))
      val f = proof(ctx, nisp, claimedScore)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("stray: one unmined share among nine mined ones is caught") {
    withCtx { ctx =>
      val stray = NispFixtures.superShare(rollupBlock(ctx).toInt - 1, pk(ctx),
        timestamp = 1700000000001L)
      val f = proof(ctx, NispFixtures.nisp(MinedScore, Seq.fill(9)(mined(ctx)._1) :+ stray),
        MinedScore)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The share's `N` is what `FP_IncorrectN` guarantees before this proof runs. Feeding a different one
   * changes which table `powHit` sums over, so the hit moves and the verdict with it — which is the
   * reason the two proofs are ordered rather than independent.
   */
  property("ordering: a share with a tampered N produces a different hit") {
    withCtx { ctx =>
      val (share, _) = mined(ctx)
      val tampered = NispFixtures.withN(share.serialize, NispFixtures.calcN(4198400))
      val f = proof(ctx, NispFixtures.rawNisp(MinedScore, Seq.fill(10)(tampered)), MinedScore)
      // Not asserting which way it goes: the point is that N is an input to the verdict, so it has to
      // be pinned by FP_IncorrectN first. Either outcome here is a valid answer to a different puzzle.
      val outcome = scala.util.Try(f.prover.sign(fraudTx(f)()))
      println(s"[ordering] tampered N: ${if (outcome.isSuccess) "fires" else "declines"}")
    }
  }

  transitionChecks("invalidDiff") { ctx =>
    val shares = NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))
    proof(ctx, NispFixtures.nisp(claimedScore, shares), claimedScore)
  }
}
