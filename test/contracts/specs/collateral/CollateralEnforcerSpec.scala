package contracts.specs.collateral

import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import sigma.crypto.CryptoConstants
import sigma.data.{CGroupElement, ProveDlog}
import work.lithos.mutations.{Contract, Token}

/**
 * Collateral_Enforcer.ergo — one property per named condition in the contract.
 *
 * The enforcer never sits on a box. It rides in context var 65 of every emission spend and is executed
 * by `Emission_Guard`, which authenticates it against config R5 — that indirection is what makes the
 * collateral scheme replaceable while the emission schedule stays fixed. So these properties run the
 * full production stack: the real emission logic in var 64 and the real enforcer in var 65.
 *
 * The two contracts check disjoint things on Join, which is what makes the negatives here isolated.
 * Emission pins the tokens, the gate hash and the successor's registers, and checks only that the
 * queue box's R4/R5/R6 exist; the enforcer owns everything about their *values* — the principal floor,
 * the fee channel, the permit, the extension flag, the queue position — plus head and tail, which
 * emission deliberately leaves alone ("queue state is the job of the collateral enforcer").
 */
class CollateralEnforcerSpec extends AnyPropSpec with EmissionSpecBase {

  private def enforced(ctx: BlockchainContext) = enforcerScript(ctx)

  // ─── Join: the permit thermostat ──────────────────────────────────────────

  property("join: accepts a well-formed queue box under the production enforcer") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      j.permit shouldBe LFSMHelpers.PERMIT_FLOOR
      accepts(j.prover, joinTx(j)())
    }
  }

  /**
   * Linear rather than stepped, so adjacent joiners pay nearly the same and there is no cliff to race
   * around in the mempool (analysis §26.1).
   */
  property("thermostat: accepts the sloped permit at a backlog inside the sloped region") {
    withCtx { ctx =>
      permitAt(100L) shouldBe 2000L * LIT
      val j = joinScenario(ctx, head = 0L, tail = 100L, enfScript = enforced(ctx))
      j.permit shouldBe 2000L * LIT
      accepts(j.prover, joinTx(j)())
    }
  }

  /** `1000 + 10Q >= 30000` at `Q = 2,900`; above that the thermostat is flat (analysis §26.2). */
  property("thermostat: clamps to the ceiling once the backlog saturates it") {
    withCtx { ctx =>
      permitAt(2900L) shouldBe LFSMHelpers.PERMIT_CEIL
      permitAt(50000L) shouldBe LFSMHelpers.PERMIT_CEIL
      val j = joinScenario(ctx, head = 0L, tail = 50000L, enfScript = enforced(ctx))
      j.permit shouldBe LFSMHelpers.PERMIT_CEIL
      accepts(j.prover, joinTx(j)())
    }
  }

  /**
   * `==`, not `>=`. Over-posting is harmless to the pool but makes boxes heterogeneous, and
   * permit-as-bid is the door §21 says not to open by accident.
   */
  property("thermostat: rejects an under-posted permit (permitPosted)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val short = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit - 1L)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = short))
    }
  }

  property("thermostat: rejects an over-posted permit (permitPosted)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val over = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit + LIT)
      val in = inputAt(work.lithos.mutations.UTXO(contractOf(j.lenderProver), 10L * Parameters.OneErg,
        Seq(Token(LFSMHelpers.LIT_ID, j.permit + LIT))), ctx, 1)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = over, inputs = Seq(j.emissionIn, in)))
    }
  }

  /** `permitFloor = 0, permitSlope = 0` disables the permit outright — a launch-day escape hatch. */
  property("thermostat: accepts a zero permit when the parameters disable it") {
    withCtx { ctx =>
      val j = joinScenario(ctx, params = Array(0L, 0L, 0L), enfScript = enforced(ctx))
      j.permit shouldBe 0L
      accepts(j.prover, joinTx(j)())
    }
  }

  /**
   * `slope × backlog` in raw `Long` would overflow on hostile parameters, and an overflow here would
   * not fail one transaction — it would make every Join unsatisfiable until a config fix. Computing
   * wide and clamping first makes the function total: no parameter choice can throw.
   */
  property("thermostat: stays total under parameters that would overflow a raw Long (BigInt clamp)") {
    withCtx { ctx =>
      val hostile = Array(0L, 1000L, Long.MaxValue)
      permitAt(1000L, hostile) shouldBe 1000L
      val j = joinScenario(ctx, head = 0L, tail = 1000L, params = hostile, enfScript = enforced(ctx))
      j.permit shouldBe 1000L
      accepts(j.prover, joinTx(j)())
    }
  }

  // ─── Join: the queue box's terms ──────────────────────────────────────────

  property("join: rejects a queue box that is not at the tail of the queue (R7)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, head = 3L, tail = 11L, enfScript = enforced(ctx))
      val misplaced = queueUTXO(ctx, j.lenderProver, position = 12L, permit = j.permit)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = misplaced))
    }
  }

  /**
   * The floor adds the dust budget back, so the pool's worst case is exactly `3 - 0.09` ERG. Without
   * that term the effective cap is `FEE_CAP + DUST` and the 3% figure does not mean what it says.
   */
  property("join: rejects a principal below the floor (CONST_BLOCK_REWARD - CONST_FEE_CAP + CONST_DUST_BUDGET)") {
    withCtx { ctx =>
      principalFloor shouldBe 2915000000L
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = j.queueOut.subValue(1L)))
    }
  }

  property("join: accepts a principal above the floor") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      accepts(j.prover, joinTx(j)(queueOut = j.queueOut.addValue(Parameters.OneErg)))
    }
  }

  property("join: rejects a fee channel that is not the dust budget (CONST_DUST_BUDGET)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val greedy = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit,
        feeValue = DUST_BUDGET + 1L)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = greedy))
    }
  }

  /**
   * The last unguarded dead-slot vector (analysis §26.3.1). A nonzero flag routes the eventual
   * collateral spend through `extensionActivated`, which demands a script matching config R6 *at spend
   * time* — so a flag no extension script handles makes the box unspendable forever, and the slot with
   * it. Pinned at 0x00 until the live product flags ship.
   */
  property("join: rejects a nonzero extension flag (R6 == 0x00)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val flagged = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit, extensionFlag = 5.toByte)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = flagged))
    }
  }

  /**
   * A `lenderPk` that is not a plain dlog can never be paid by a coinbase, so the collateral box it
   * becomes is unspendable and its slot is dead. The group element is supplied separately and the two
   * are required to agree.
   */
  property("join: rejects a lender key that is not the dlog companion of CTX_GE (proveDlog)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val mismatched = queueUTXO(ctx, otherLender(ctx), position = 0L, permit = j.permit)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = mismatched))
    }
  }

  /** The identity point cannot receive a block reward, so it is excluded explicitly. */
  property("join: rejects the identity group element (CTX_GE.getEncoded(0) != 0)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val identity = CryptoConstants.dlogGroup.identity
      val in = emissionInput(ctx, emissionUTXO(ctx), 0.toByte,
        lenderGE = Some(ErgoValue.of(CGroupElement(identity))), enfScript = enforced(ctx))
      val queueOut = j.queueOut.withReg(1, ErgoValue.of(ProveDlog(identity)))
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = queueOut, inputs = Seq(in, j.funding)))
    }
  }

  // ─── Join: queue state ────────────────────────────────────────────────────

  property("join: rejects a successor that does not advance the tail (queueGrown nextTail)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.withReg(3, ErgoValue.of(0L))))
    }
  }

  property("join: rejects a successor that moves the head (queueGrown nextHead)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, head = 3L, tail = 11L, enfScript = enforced(ctx))
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.withReg(2, ErgoValue.of(4L))))
    }
  }

  // ─── Activate: FIFO ───────────────────────────────────────────────────────

  /**
   * Strict first-come-first-served, and it lives here rather than in the emission contract precisely so
   * a later enforcer can choose differently without reissuing the emission box (analysis §16.7, §22).
   */
  property("activate: accepts the box at the head of the queue (queueAdvanced)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      accepts(a.prover, activateTx(a)())
    }
  }

  property("activate: rejects a queue box that is not at the head (queueIn R7)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      val jumper = inputAt(queueUTXO(ctx, a.lenderProver, position = 7L, permit = a.permit), ctx, 1)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, jumper, a.funding)))
    }
  }

  property("activate: rejects a successor that does not advance the head (nextHead)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = a.emissionOut.withReg(2, ErgoValue.of(4L))))
    }
  }

  property("activate: rejects a successor that moves the tail (nextTail)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = a.emissionOut.withReg(3, ErgoValue.of(10L))))
    }
  }

  // ─── Clear: FIFO ──────────────────────────────────────────────────────────

  property("clear: accepts clearing the box at the head of the queue (queueCleaned)") {
    withCtx { ctx =>
      val c = clearScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      accepts(c.prover, clearTx(c)())
    }
  }

  property("clear: rejects clearing a queue box that is not at the head (queueIn R7)") {
    withCtx { ctx =>
      val c = clearScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      val jumper = inputAt(queueUTXO(ctx, c.lenderProver, position = 7L, permit = c.permit), ctx, 1)
      rejectsAtSigning(c.prover, clearTx(c)(inputs = Seq(c.emissionIn, jumper, c.funding)))
    }
  }

  property("clear: rejects a successor that does not advance the head (nextHead)") {
    withCtx { ctx =>
      val c = clearScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = c.emissionOut.withReg(2, ErgoValue.of(4L))))
    }
  }

  property("clear: rejects a successor that moves the tail (nextTail)") {
    withCtx { ctx =>
      val c = clearScenario(ctx, head = 4L, tail = 9L, enfScript = enforced(ctx))
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = c.emissionOut.withReg(3, ErgoValue.of(10L))))
    }
  }

  // ─── delegation ───────────────────────────────────────────────────────────

  /**
   * The enforcer reads the emission box through `getVarFromInput(0, 0)` and `INPUTS(0)`, both anchored
   * by the emission contract's own `onlyOne`. Pointing them at anything else means the emission logic
   * has already rejected the spend.
   */
  property("delegation: rejects a spend where the emission box is not INPUTS(0)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(j.funding, j.emissionIn)))
    }
  }

  property("delegation: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforced(ctx))
      val in = emissionInput(ctx, emissionUTXO(ctx), 9.toByte,
        lenderGE = Some(lenderGEValue(ctx, j.lenderProver)), enfScript = enforced(ctx))
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(in, j.funding)))
    }
  }

  property("delegation: the enforcer is not a stub") {
    withCtx { ctx =>
      enforced(ctx).ergoTreeHex should not be Contract.SIGMA_TRUE.ergoTreeHex
      enforced(ctx).ergoTree.version shouldBe 3
    }
  }
}
