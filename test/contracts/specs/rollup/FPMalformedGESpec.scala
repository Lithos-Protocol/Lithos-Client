package contracts.specs.rollup

import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.Contract

/**
 * `FP_MalformedGE.ergo` — the second gate, and the reason the proofs are ordered.
 *
 * `deserializeTo[Header]` decompresses the header's miner key, and a key that is not a point on
 * secp256k1 makes it throw. A throw is not a verdict: `Evaluator` cannot tell it from a proof that was
 * never reached, so a miner whose NISP throws in every proof is a miner nobody can slash. This proof
 * is what closes that, and it works on raw bytes for exactly that reason — it is the last proof that
 * may not parse a header.
 *
 * What it actually checks is the pair `(compressed key, stored y)`: prefix in {2, 3}, both coordinates
 * below the field prime, the prefix matching y's parity, and `y² == x³ + 7`. Together those say the
 * stored y *is* the decompression of x, so declining implies the key decodes.
 *
 * Each fraud property is paired with what a header-parsing proof does on the same NISP, which is what
 * says whether the case is a parse hole this proof has to hold, or a rule it enforces on its own.
 */
class FPMalformedGESpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val score = 100000L

  /** secp256k1's field prime, the bound both coordinates have to sit under. */
  private val P: BigInt =
    BigInt("115792089237316195423570985008687907853269984665640564039457584007908834671663")

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  private def honest(ctx: BlockchainContext): Seq[SuperShare] =
    NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))

  /** Every share's miner key replaced, with the share's own stored y left where it was. */
  private def nispWithKey(ctx: BlockchainContext, ge: Array[Byte]): Array[Byte] =
    NispFixtures.rawNisp(score, honest(ctx).map(s =>
      NispFixtures.shareWithHeader(s, NispFixtures.withGroupElement(s.headerBytes, ge))))

  /** The real key from an honest share, so a negative can perturb one byte of a valid encoding. */
  private def realKey(ctx: BlockchainContext): Array[Byte] =
    NispFixtures.groupElementOf(honest(ctx).head.headerBytes)

  private def unsigned32(v: BigInt): Array[Byte] = {
    val raw = v.toByteArray.dropWhile(_ == 0.toByte)
    Array.fill(32 - raw.length)(0.toByte) ++ raw
  }

  private def ge(ctx: BlockchainContext, script: Contract, nisp: Array[Byte]): Fraud =
    fraud(ctx, script, nisp, score)

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: a real miner key on every share, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), NispFixtures.nisp(score, honest(ctx)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── keys that do not decode: the parse holes this proof exists to hold ───

  property("prefix 0x04: caught") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, 4.toByte +: realKey(ctx).tail))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("prefix 0x04: a header-parsing proof cannot evaluate it") {
    withCtx { ctx =>
      val f = ge(ctx, fpNotInWindow(ctx), nispWithKey(ctx, 4.toByte +: realKey(ctx).tail))
      val ex = failsToEvaluate(f.prover, fraudTx(f)())
      println(s"[hole] prefix 0x04: ${ex.getMessage}")
    }
  }

  /** A well-formed prefix over an x that no y satisfies. The curve equation is what finds it. */
  property("x off the curve: caught") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, 2.toByte +: unsigned32(BigInt(7))))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("x off the curve: a header-parsing proof cannot evaluate it") {
    withCtx { ctx =>
      val f = ge(ctx, fpNotInWindow(ctx), nispWithKey(ctx, 2.toByte +: unsigned32(BigInt(7))))
      val ex = failsToEvaluate(f.prover, fraudTx(f)())
      println(s"[hole] x off the curve: ${ex.getMessage}")
    }
  }

  /** x at or above the field prime is not a coordinate at all. `coordsInRange` is what refuses it. */
  property("x at the field prime: caught") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, 2.toByte +: unsigned32(P)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("x above the field prime: caught") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, 2.toByte +: Array.fill(32)(0xFF.toByte)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── keys that do decode, and are still fraud ─────────────────────────────

  /**
   * The infinity point encodes as 33 zero bytes and `deserializeTo[Header]` reads it happily, so this
   * is a rule rather than a parse hole. It has to be one: infinity is not a key any coinbase can pay,
   * and the header would otherwise carry a miner nobody can be.
   */
  property("infinity: caught even though the header parses") {
    withCtx { ctx =>
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, Array.fill(33)(0.toByte)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("infinity: a header-parsing proof reads it without trouble") {
    withCtx { ctx =>
      val f = ge(ctx, fpNotInWindow(ctx), nispWithKey(ctx, Array.fill(33)(0.toByte)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The prefix flipped over a real x. The key still decodes — to the other root — but it no longer
   * agrees with the y the share stores, and every later proof reads that y as the miner's. This is the
   * half of the check that binds the two together.
   */
  property("parity flipped against the stored y: caught") {
    withCtx { ctx =>
      val key = realKey(ctx)
      val flipped = (if (key(0) == 2.toByte) 3.toByte else 2.toByte) +: key.tail
      val f = ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, flipped))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("parity flipped against the stored y: a header-parsing proof reads it anyway") {
    withCtx { ctx =>
      val key = realKey(ctx)
      val flipped = (if (key(0) == 2.toByte) 3.toByte else 2.toByte) +: key.tail
      val f = ge(ctx, fpNotInWindow(ctx), nispWithKey(ctx, flipped))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The other side of the same binding: a real key with a y that is not its decompression. */
  property("stored y replaced with a value off the curve: caught") {
    withCtx { ctx =>
      val broken = honest(ctx).map(s =>
        NispFixtures.withYCoord(s.serialize, unsigned32(BigInt(12345))))
      val f = ge(ctx, fpMalformedGE(ctx), NispFixtures.rawNisp(score, broken))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("stored y at the field prime: caught") {
    withCtx { ctx =>
      val broken = honest(ctx).map(s => NispFixtures.withYCoord(s.serialize, unsigned32(P)))
      val f = ge(ctx, fpMalformedGE(ctx), NispFixtures.rawNisp(score, broken))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the predicate is an exists ───────────────────────────────────────────

  property("stray: one bad key among nine real ones is caught") {
    withCtx { ctx =>
      val shares = honest(ctx)
      val bad = NispFixtures.shareWithHeader(shares.last,
        NispFixtures.withGroupElement(shares.last.headerBytes, 4.toByte +: realKey(ctx).tail))
      val f = ge(ctx, fpMalformedGE(ctx),
        NispFixtures.rawNisp(score, shares.dropRight(1).map(_.serialize) :+ bad))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  transitionChecks("malformedGE") { ctx =>
    ge(ctx, fpMalformedGE(ctx), nispWithKey(ctx, 4.toByte +: realKey(ctx).tail))
  }
}
