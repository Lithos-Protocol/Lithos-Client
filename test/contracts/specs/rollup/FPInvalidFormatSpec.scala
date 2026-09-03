package contracts.specs.rollup

import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_InvalidFormat.ergo` — the gatekeeper. Every other proof reads a NISP with no bounds checks of
 * its own, on the strength of this one having declined first, and four of them hand the header
 * straight to `deserializeTo[Header]`.
 *
 * So this proof carries an obligation the others do not: a NISP it calls well-formed has to be a NISP
 * they can all parse. Where that fails the miner is unslashable, because a proof that throws is
 * indistinguishable to `Evaluator` from one that has not been reached.
 *
 * Each of the three sections below poses a header that parses far enough for the size arithmetic to
 * work out, and then breaks `deserializeTo[Header]`. They are paired: the proof must catch it, and
 * the proof that would otherwise read it still cannot.
 */
class FPInvalidFormatSpec extends AnyPropSpec with FraudProofSpecBase {

  private val score = 100000L

  /** Ten well-formed shares inside the window, which is what an honest NISP looks like. */
  private def honestShares(ctx: BlockchainContext, at: Long): Seq[SuperShare] =
    NispFixtures.cleanShares(at.toInt, NispFixtures.pkOf(miner(ctx)))

  private def honestNisp(ctx: BlockchainContext, at: Long): Array[Byte] =
    NispFixtures.nisp(score, honestShares(ctx, at))

  /** The accused's NISP, with every share's header replaced by `mutate`. */
  private def nispWithHeaders(ctx: BlockchainContext,
                              at: Long,
                              mutate: Array[Byte] => Array[Byte]): Array[Byte] =
    NispFixtures.rawNisp(score,
      honestShares(ctx, at).map(s => NispFixtures.shareWithHeader(s, mutate(s.headerBytes))))

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: an unmutated NISP is well formed, so the proof declines cleanly") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script, honestNisp(ctx, ctx.getHeight.toLong - 460L), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The other half of the control: an honest NISP is one the header-parsing proofs can read. Without
   * this the tests below could pass on a fixture that was broken all along.
   */
  property("honest: a header-parsing proof also declines cleanly on the same NISP") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script, honestNisp(ctx, ctx.getHeight.toLong - 460L), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("malformed: a share whose txProof size is out of range is caught") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      // The size field says 2 bytes, far below CONST_TX_PROOF_MIN.
      val broken = honestShares(ctx, ctx.getHeight.toLong - 460L).map { s =>
        val bytes = NispFixtures.shareWithHeader(s, s.headerBytes)
        val at = 4 + s.headerBytes.length
        bytes.slice(0, at) ++ Array(0.toByte, 2.toByte) ++ bytes.drop(at + 2)
      }
      val f = fraud(ctx, script, NispFixtures.rawNisp(score, broken), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── hole 1: a height VLQ past Int.MaxValue ───────────────────────────────
  //
  // `HeaderWithoutPowSerializer.parse` reads the height as `r.getUInt().toIntExact`, which throws
  // once the decoded value passes Int.MaxValue. `validVLQUInt` bounds the value only when the first
  // four bytes are all 0xFF, and a five-byte VLQ does not have to look like that.
  //
  // [0x80, 0x80, 0x80, 0x80, 0x08] carries payload bits 0,0,0,0,8, so it decodes to 8 << 28 =
  // 2,147,483,648 — one past Int.MaxValue. Its first four bytes are -128, not -1, so the bound is
  // skipped entirely.

  private val heightPastIntMax: Array[Byte] =
    Array(0x80.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte, 0x08.toByte)

  private def overflowNisp(ctx: BlockchainContext, at: Long): Array[Byte] =
    nispWithHeaders(ctx, at, NispFixtures.withHeightVlq(_, heightPastIntMax))

  property("height overflow: InvalidFormat catches it") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script, overflowNisp(ctx, ctx.getHeight.toLong - 460L), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * Why the catch above has to exist. The same NISP hands a header-parsing proof a height it cannot
   * narrow to an Int, so that proof throws rather than returning a verdict. Reorder the set and this
   * miner becomes unslashable, because one throw skips every proof after it.
   */
  property("height overflow: a header-parsing proof still cannot evaluate it") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script, overflowNisp(ctx, ctx.getHeight.toLong - 460L), score)
      val ex = failsToEvaluate(f.prover, fraudTx(f)())
      println(s"[hole] height past Int.MaxValue: ${ex.getMessage}")
    }
  }

  // ─── the version byte ─────────────────────────────────────────────────────
  //
  // The proof accepts version 4 and up, and only with the unparsed-bytes count at zero. Two things
  // are being separated here. A version below 4 is readable and rejected anyway, because nothing is
  // mining it. A nonzero count is rejected because the bytes it announces sit between the votes and
  // the miner key, so CONST_INVARIANT_SUFFIX stops describing the header — which is why version 1,
  // with no count byte at all and a solution carrying a second group element, cannot be read either.

  property("version 1: InvalidFormat catches it") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 1.toByte)), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("version 1: a header-parsing proof still cannot evaluate it") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 1.toByte)), score)
      val ex = failsToEvaluate(f.prover, fraudTx(f)())
      println(s"[hole] version 1: ${ex.getMessage}")
    }
  }

  /** From version 5 the size byte is honoured, so a nonzero one shifts everything after it. */
  property("version 5 with unparsed bytes: InvalidFormat catches it") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script, nispWithHeaders(ctx, ctx.getHeight.toLong - 460L,
        h => NispFixtures.withUnparsedSize(NispFixtures.withVersion(h, 5.toByte), 10.toByte)), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("version 5 with unparsed bytes: a header-parsing proof still cannot evaluate it") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script, nispWithHeaders(ctx, ctx.getHeight.toLong - 460L,
        h => NispFixtures.withUnparsedSize(NispFixtures.withVersion(h, 5.toByte), 10.toByte)), score)
      val ex = failsToEvaluate(f.prover, fraudTx(f)())
      println(s"[hole] version 5, 10 unparsed bytes: ${ex.getMessage}")
    }
  }

  /**
   * The version bound is open above, so a future version is honest work as long as it announces no
   * unparsed bytes: with the count at zero the header is laid out exactly like a version 4 one, and
   * the proof has nothing to object to.
   */
  property("version 5 with no unparsed bytes: still well formed") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 5.toByte)), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("version 5 with no unparsed bytes: a header-parsing proof reads it without trouble") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 5.toByte)), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** Same story one version down: a readable header, rejected for being the wrong version. */
  property("version 3: InvalidFormat catches it") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 3.toByte)), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("version 3: a header-parsing proof reads it without trouble") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script,
        nispWithHeaders(ctx, ctx.getHeight.toLong - 460L, NispFixtures.withVersion(_, 3.toByte)), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The count is refused at every version, including the one being mined. At version 4 the parser
   * reads the byte and discards what it announces, so this header does parse — but no node builds
   * one, and accepting it would make the proof depend on which side of the version boundary that
   * discarding happens.
   */
  property("version 4 with a nonzero size byte: InvalidFormat catches it") {
    withCtx { ctx =>
      val script = fpInvalidFormat(ctx)
      val f = fraud(ctx, script, nispWithHeaders(ctx, ctx.getHeight.toLong - 460L,
        NispFixtures.withUnparsedSize(_, 10.toByte)), score)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("version 4 with a nonzero size byte: a header-parsing proof reads it anyway") {
    withCtx { ctx =>
      val script = fpNotInWindow(ctx)
      val f = fraud(ctx, script, nispWithHeaders(ctx, ctx.getHeight.toLong - 460L,
        NispFixtures.withUnparsedSize(_, 10.toByte)), score)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }
}
