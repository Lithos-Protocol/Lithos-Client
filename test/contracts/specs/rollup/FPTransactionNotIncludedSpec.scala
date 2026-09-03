package contracts.specs.rollup

import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_TransactionNotIncluded.ergo` — the share has to prove the collateral transaction was in the
 * block it claims to come from.
 *
 * This is what ties a NISP to real work on *this* pool rather than to any Autolykos hit. The genesis
 * transaction is inserted before the header is hashed, so a header committing to a transactions root
 * that contains it could only have been mined with it. Drop that check and a miner submits shares from
 * blocks they mined for somebody else.
 *
 * The proof walks the levels the share carries, rebuilding the root from
 * `blake2b256(0x00 ++ blake2b256(tx))` upward, and compares against `header.transactionsRoot`. Every
 * fixture below therefore has to be built root-first, which is what `NispFixtures.includedShare` does.
 */
class FPTransactionNotIncludedSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val score = 100000L

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  /** Ten shares each committing to their own transaction proof. */
  private def included(ctx: BlockchainContext, levels: Int = 2): Seq[SuperShare] =
    (0 until 10).map(i => NispFixtures.includedShare(rollupBlock(ctx).toInt - i, pk(ctx),
      numLevels = levels, timestamp = 1700000000000L + i, seed = (1 + i).toByte))

  private def proof(ctx: BlockchainContext, shares: Seq[SuperShare]): Fraud =
    fraud(ctx, fpTransactionNotIncluded(ctx), NispFixtures.nisp(score, shares), score)

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: every share proves its own transaction, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = proof(ctx, included(ctx))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("honest: a single-level proof verifies") {
    withCtx { ctx =>
      val f = proof(ctx, included(ctx, levels = 1))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The depth ceiling `FP_InvalidFormat` admits, so the fold has to reach it. */
  property("honest: a sixteen-level proof verifies") {
    withCtx { ctx =>
      val f = proof(ctx, included(ctx, levels = 16))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the fraud ────────────────────────────────────────────────────────────

  /**
   * A header committing to a root unrelated to the proof it carries — the shape of a share lifted
   * from a block that never held the collateral transaction.
   */
  property("unrelated root: a header that does not commit to its own proof is caught") {
    withCtx { ctx =>
      val f = proof(ctx, NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("stray: one unproven share among nine proven ones is caught") {
    withCtx { ctx =>
      val shares = included(ctx)
      val stray = NispFixtures.superShare(rollupBlock(ctx).toInt - 9, pk(ctx))
      val f = proof(ctx, shares.dropRight(1) :+ stray)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /** The side byte decides which way a sibling concatenates, so flipping one moves the root. */
  property("level side flipped: caught") {
    withCtx { ctx =>
      val shares = included(ctx).map { s =>
        val flipped = s.levels.head.clone()
        flipped(0) = if (flipped(0) == 0.toByte) 1.toByte else 0.toByte
        SuperShare(s.headerBytes, s.txProof, flipped +: s.levels.tail)
      }
      val f = proof(ctx, shares)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("sibling replaced: caught") {
    withCtx { ctx =>
      val shares = included(ctx).map { s =>
        val other = s.levels.head(0) +: Array.fill(32)(0x5A.toByte)
        SuperShare(s.headerBytes, s.txProof, other +: s.levels.tail)
      }
      val f = proof(ctx, shares)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /** Blake2b is not commutative in the order the levels are applied, so swapping them breaks it. */
  property("levels reordered: caught") {
    withCtx { ctx =>
      val shares = included(ctx).map(s => SuperShare(s.headerBytes, s.txProof, s.levels.reverse))
      val f = proof(ctx, shares)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * A proof of a different transaction against the same header. The levels still verify to the
   * header's root — for the transaction the header actually carried, not for the one submitted.
   */
  property("transaction swapped under a valid proof: caught") {
    withCtx { ctx =>
      val shares = included(ctx).map(s =>
        SuperShare(s.headerBytes, NispFixtures.txProof(0x7F.toByte), s.levels))
      val f = proof(ctx, shares)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  transitionChecks("transactionNotIncluded") { ctx =>
    proof(ctx, NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx)))
  }
}
