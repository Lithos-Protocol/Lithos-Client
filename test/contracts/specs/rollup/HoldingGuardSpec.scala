package contracts.specs.rollup

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.{Coll, Colls}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * `Holding_Guard` + `Holding_Logic` — the split of the old `Holding.ergo` into a
 * small on-box guard and the rules it runs out of context var 64.
 *
 * Why the shape is what it is: `executeFromVar` cannot nest, and the holding logic used to run the
 * miner's signing script out of var 0. So the guard runs it instead, binds the tree key to it, and
 * the logic takes `CTX_NISP_DATA._1` as already authenticated. That in turn is why an op byte
 * replaces the `if (HEIGHT < ...)` — the guard must know before it executes anything whether a
 * signer script is involved. The op byte is the spender's choice and proves nothing, so the logic
 * re-checks the height condition as a named conjunct.
 *
 * The three things worth proving here: that nesting really does fail, that the guard is materially
 * smaller than what it replaces, and that both paths still sign and still reject what they used to.
 */
object HoldingGuardSpec {
  /**
   * The compiled size of the single-script holding contract the guard replaced, recorded because
   * that contract no longer exists to measure. A script this large no longer fits TX_PROOF_MAX once
   * the rollup NFT is on the holding box.
   */
  val HoldingErgoBytes: Int = 403
}

class HoldingGuardSpec extends AnyPropSpec with RollupSpecBase {

  private val period = LFSMHelpers.HOLDING_PERIOD

  // ─── the compiled pair ────────────────────────────────────────────────────
  //
  // Both come from `RollupContracts` now rather than being compiled here: `mkHoldingContract`
  // returns the guard and injects the logic's hash itself, so a spec that built its own pair could
  // drift from what the client ships. `RollupSpecBase` caches them.

  private def bytesVar(id: Byte, bytes: Array[Byte]): ContextVar =
    ContextVar.of(id, ErgoValue.of(Colls.fromArray(bytes), scalaByteType))

  // ─── 1. the constraint the whole design is built around ──────────────────

  /**
   * A script executed from a variable cannot itself execute from a variable. If this ever stops
   * being true the guard can keep the original `if (HEIGHT < ...)` and lose the op byte entirely,
   * so it is worth having the failure pinned rather than assumed.
   */
  property("nesting: executeFromVar inside a script that is itself executed from a var fails") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val inner = Contract.SIGMA_TRUE
      val outer = Contract.fromErgoScript(ctx, ConstantsBuilder.empty(),
        "{ executeFromVar[SigmaProp](0) }")
      val host = Contract.fromErgoScript(ctx, ConstantsBuilder.empty(),
        "{ executeFromVar[SigmaProp](64) }")

      val box = UTXO(host, boxValue).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(bytesVar(0.toByte, inner.valueBytes), bytesVar(64.toByte, outer.valueBytes))

      val tx = build(ctx, Seq(box, fundingInput(ctx, prover)),
        Seq(UTXO(contractOf(prover), boxValue)), prover.getAddress)
      val ex = rejects(prover, tx)
      println(s"[nesting] nested executeFromVar rejected: ${ex.getMessage}")
    }
  }

  /** The same outer script, run directly rather than from a variable, is fine — so the failure is
   * the nesting and not the script. */
  property("nesting: the same script executed directly from the box does work") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val host = Contract.fromErgoScript(ctx, ConstantsBuilder.empty(),
        "{ executeFromVar[SigmaProp](0) }")
      val box = UTXO(host, boxValue).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(bytesVar(0.toByte, Contract.SIGMA_TRUE.valueBytes))
      accepts(prover, build(ctx, Seq(box, fundingInput(ctx, prover)),
        Seq(UTXO(contractOf(prover), boxValue)), prover.getAddress))
    }
  }

  // ─── 2. the size, and what it is worth downstream ────────────────────────

  property("size: the guard against the Holding contract it replaces") {
    withCtx { ctx =>
      val guard = holdingContract(ctx).ergoTree.bytes.length
      val logic = holdingLogic(ctx).ergoTree.bytes.length
      // Holding.ergo was deleted once the split landed, so its size is recorded rather than
      // measured. 403 bytes, from the last run before it was removed.
      val replaced = HoldingGuardSpec.HoldingErgoBytes
      val saved = replaced - guard

      println(s"[size] Holding.ergo (deleted, recorded) = $replaced bytes")
      println(s"[size] Holding_Guard (on box)           = $guard bytes")
      println(s"[size] Holding_Logic (in var 64)        = $logic bytes")
      println(s"[size] saved from the genesis tx        = $saved bytes")

      // One byte off the genesis tx is ten off the NISP ceiling and thirty off a fraud proof.
      println(s"[size] -> NISP ceiling falls by ${saved * 10}, fraud-proof worst case by ${saved * 30}")
      println(s"[size] submission tx pays $logic extra bytes in its context extension instead")

      guard should be < replaced
      guard shouldBe 137
    }
  }

  /**
   * What the saving is worth against the 90 KB fraud-proof budget, in the units the constants are
   * actually set in. `CONST_NISP_MAX` is derived from `CONST_TX_PROOF_MAX`, and a fraud proof
   * carries three NISPs (lookup 1x, removal 2x) plus about 520 bytes of path labels.
   */
  property("size: the guard against the 90 KB fraud-proof budget") {
    withCtx { ctx =>
      val saved = HoldingGuardSpec.HoldingErgoBytes - holdingContract(ctx).ergoTree.bytes.length

      def ceilingFor(txProofMax: Int) = 8 + 10 * (4 + 221 + 3 + txProofMax + 16 * 33 + 32) + 1
      def fraudProof(nispMax: Int) = 3 * nispMax + 520

      val deployedTxProofMax = 2174 // the value the constants were just sized to
      val deployed = ceilingFor(deployedTxProofMax)
      val withGuard = ceilingFor(deployedTxProofMax - saved)

      deployed shouldBe 29629 // matches CONST_NISP_MAX in Holding.ergo, so the model is the real one

      println(s"[budget] today:      TX_PROOF_MAX=$deployedTxProofMax NISP_MAX=$deployed " +
        s"fraudProof=${fraudProof(deployed)} margin=${90000 - fraudProof(deployed)}")
      println(s"[budget] with guard: TX_PROOF_MAX=${deployedTxProofMax - saved} NISP_MAX=$withGuard " +
        s"fraudProof=${fraudProof(withGuard)} margin=${90000 - fraudProof(withGuard)}")

      fraudProof(deployed) should be < 90000
      fraudProof(withGuard) should be < fraudProof(deployed)
    }
  }

  // ─── 3. both paths, through the guard ────────────────────────────────────

  private case class Submission(ctx: BlockchainContext, in: InputUTXO, out: UTXO,
                                funding: InputUTXO, prover: ErgoProver)

  private def submission(ctx: BlockchainContext,
                         score: Long = 5000L,
                         nispSize: Int = 8000,
                         op: Byte = 0.toByte,
                         periodStart: Long = -1L,
                         key: Array[Byte] = null): Submission = {
    val guard = holdingContract(ctx)
    val prover = miner(ctx)
    val identity = contractOf(prover)
    val treeKey = if (key == null) identity.hashedPropBytes else key
    val nisp = nispBytes(score, nispSize)

    val tree = treeWith(Seq.empty)
    val inTree = tree.ergoValue
    val insertion = tree.insert(treeKey -> nisp)
    val outTree = tree.ergoValue
    val start = if (periodStart < 0L) ctx.getHeight.toLong - 100L else periodStart

    val in = UTXO(guard, boxValue, Seq.empty[Token], Seq(
      inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger), stateReg(start, start, 0L)))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        bytesVar(0.toByte, identity.valueBytes),
        ContextVar.of(1.toByte, ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(treeKey), scalaByteType),
          ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
        ContextVar.of(2.toByte, insertion.proof.ergoValue),
        ContextVar.of(3.toByte, ErgoValue.of(op)),
        bytesVar(64.toByte, holdingLogic(ctx).valueBytes))

    // The submission posts a bond priced from its own score, so the successor holds more than SELF.
    val bond = bondFor(score)
    val out = UTXO(guard, boxValue + bond, Seq.empty[Token], Seq(
      outTree, ErgoValue.of(1), ErgoValue.of(BigInt(score).bigInteger), stateReg(start, start, bond)))

    Submission(ctx, in, out, fundingInput(ctx, prover), prover)
  }

  private def submit(s: Submission, out: UTXO): UnsignedTransaction =
    build(s.ctx, Seq(s.in, s.funding), Seq(out), s.prover.getAddress)

  property("submit: accepts a well-formed NISP through the guard") {
    withCtx { ctx =>
      val s = submission(ctx)
      accepts(s.prover, submit(s, s.out))
    }
  }

  /** The condition the old `if` supplied, now a named conjunct. Without it the op byte would let a
   * submission land after the period closed. */
  property("submit: rejects a submission after the period has closed (inSubmissionPhase)") {
    withCtx { ctx =>
      val s = submission(ctx, periodStart = ctx.getHeight.toLong - period - 1L)
      rejectsAtSigning(s.prover, submit(s, s.out))
    }
  }

  property("submit: accepts one block before the period closes") {
    withCtx { ctx =>
      val s = submission(ctx, periodStart = ctx.getHeight.toLong - period + 1L)
      accepts(s.prover, submit(s, s.out))
    }
  }

  /** The binding the guard took over from the logic. */
  property("submit: rejects a tree key that is not the signer's hash (authenticMiner)") {
    withCtx { ctx =>
      val s = submission(ctx, key = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes)
      rejectsAtSigning(s.prover, submit(s, s.out))
    }
  }

  property("submit: rejects a NISP below CONST_NISP_MIN (validNISP)") {
    withCtx { ctx =>
      val s = submission(ctx, nispSize = 7947)
      rejectsAtSigning(s.prover, submit(s, s.out))
    }
  }

  property("submit: rejects a substituted holding logic (authenticLogic)") {
    withCtx { ctx =>
      val s = submission(ctx)
      val swapped = s.in.setCtxVars(
        (s.in.ctxVars.filterNot(_.getId == 64.toByte) :+
          bytesVar(64.toByte, Contract.SIGMA_TRUE.valueBytes)): _*)
      rejectsAtSigning(s.prover, build(ctx, Seq(swapped, s.funding), Seq(s.out), s.prover.getAddress))
    }
  }

  property("submit: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val s = submission(ctx, op = 9.toByte)
      rejectsAtSigning(s.prover, submit(s, s.out))
    }
  }

  /** Declaring the transform op does not skip the signer — it takes the logic's transform path,
   * which conserves the tree and so cannot insert anything. */
  property("submit: declaring op 1 cannot smuggle an insertion past the guard") {
    withCtx { ctx =>
      val s = submission(ctx, op = 1.toByte)
      rejectsAtSigning(s.prover, submit(s, s.out))
    }
  }

  // ─── transform ───────────────────────────────────────────────────────────

  private def transform(ctx: BlockchainContext, periodStart: Long, op: Byte = 1.toByte) = {
    val guard = holdingContract(ctx)
    val prover = miner(ctx)
    val tree = treeWith(Seq(contractOf(prover).hashedPropBytes -> nispBytes(5000L, 8000)))
    val t = tree.ergoValue

    val in = UTXO(guard, boxValue, Seq.empty[Token], Seq(
      t, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger),
      stateReg(periodStart, periodStart, bondFor(5000L))))
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        ContextVar.of(3.toByte, ErgoValue.of(op)),
        bytesVar(64.toByte, holdingLogic(ctx).valueBytes))

    // The period start moves on; the block height and the bonds carry over untouched.
    val out = UTXO(evalContract(ctx), boxValue, Seq.empty[Token], Seq(
      t, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger),
      stateReg(ctx.getHeight.toLong, periodStart, bondFor(5000L))))

    (in, out, prover, fundingInput(ctx, prover))
  }

  property("transform: accepts the handoff to Evaluation after the period") {
    withCtx { ctx =>
      val (in, out, prover, funding) = transform(ctx, ctx.getHeight.toLong - period)
      accepts(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  /**
   * The half of the design that needs no extra conjunct. `nextPeriod <= HEIGHT` together with
   * `nextPeriod >= currentPeriod + CONST_PERIOD_LENGTH` already forces
   * `HEIGHT >= currentPeriod + CONST_PERIOD_LENGTH`, so the transform path guards its own timing
   * and the op byte buys an attacker nothing here.
   */
  property("transform: rejects a transform before the period has elapsed, with no height conjunct") {
    withCtx { ctx =>
      val (in, out, prover, funding) = transform(ctx, ctx.getHeight.toLong - period + 10L)
      rejectsAtSigning(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val (in, out, prover, funding) = transform(ctx, ctx.getHeight.toLong - period, op = 7.toByte)
      rejectsAtSigning(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects a successor that is not the Evaluation contract") {
    withCtx { ctx =>
      val (in, out, prover, funding) = transform(ctx, ctx.getHeight.toLong - period)
      rejectsAtSigning(prover, build(ctx, Seq(in, funding),
        Seq(out.setContract(holdingContract(ctx))), prover.getAddress))
    }
  }
}
