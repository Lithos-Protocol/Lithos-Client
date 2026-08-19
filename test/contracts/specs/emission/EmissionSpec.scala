package contracts.specs.emission

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * LIT_Emissions.ergo — one property per named condition in the contract.
 *
 * The emission box is scripted with `Emission_Guard`, so these run the production stack: the guard
 * authenticates, then executes the emission logic from context var 64. `sigmaProp(true)` stands in for
 * the enforcer in var 65 (with config R5 naming it) so that every rejection here comes from the
 * emission logic alone — the enforcer's own conditions are in [[CollateralEnforcerSpec]] and the
 * guard's in [[EmissionGuardSpec]].
 *
 * Three paths, dispatched on the op byte in context var 0:
 *   0 Join     — a lender's principal and permit go into a queue box at the tail
 *   1 Activate — the head queue box becomes a live collateral box carrying this block's LIT
 *   2 Clear    — a queue box whose lender already holds a slot is spent without activating
 * Anything else ends at the contract's fail-closed `sigmaProp(false)`.
 *
 * Negatives use `rejectsAtSigning`, so a perturbation that failed to balance would be reported as a
 * broken test rather than passing as a contract rejection. Where the token accounting forces a
 * perturbation to trip more than one conjunction the property says which.
 */
class EmissionSpec extends AnyPropSpec with EmissionSpecBase {

  private def otherToken(amount: Long = 1L): Token = Token(unrelatedTokenId, amount)

  /**
   * The proposition tokens are minted at `Long.MaxValue`, so a transaction bringing an extra one in
   * cannot even be *built* — appkit's change calculation overflows before the contract runs. The
   * `totalInInputs` properties therefore run against a supply a little below the ceiling, which is
   * also the only state the box is ever in after its first Join.
   */
  private val belowCeiling: Long = queueSupply - 16L

  private def hashOf(s: String): Array[Byte] = Blake2b256.hash(s.getBytes("UTF-8"))

  private def funding(ctx: BlockchainContext, prover: ErgoProver, tokens: Seq[Token], index: Int = 1) =
    inputAt(UTXO(contractOf(prover), 10L * Parameters.OneErg, tokens), ctx, index)

  /** A full active set, with the retiring lender parked at `slot`. */
  private def fullSet(retiring: Array[Byte], slot: Int): Seq[Array[Byte]] =
    (0 until MAX_ACTIVE).map(i => if (i == slot) retiring else hashOf(s"lender-$i"))

  // ─── dispatch ─────────────────────────────────────────────────────────────

  property("dispatch: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val in = emissionInput(ctx, emissionUTXO(ctx), 7.toByte,
        lenderGE = Some(lenderGEValue(ctx, j.lenderProver)))
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(in, j.funding)))
    }
  }

  // ─── shared: emissionBoxPreserved ─────────────────────────────────────────

  property("preserved: rejects when the emission box is not INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(j.funding, j.emissionIn)))
    }
  }

  property("preserved: rejects a successor under a different script (propositionBytes)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("preserved: rejects a successor whose value changed (value)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.addValue(Parameters.OneErg)))
    }
  }

  property("preserved: rejects a successor holding more than one emission NFT (nextNFT)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val extraNft = funding(ctx, j.lenderProver,
        Seq(Token(LFSMHelpers.LIT_ID, j.permit), Token(LFSMHelpers.EMISSION_NFT, 1L)))
      val out = j.emissionOut.setTokens(
        Token(LFSMHelpers.EMISSION_NFT, 2L) +: j.emissionOut.tokens.tail: _*)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out, inputs = Seq(j.emissionIn, extraNft)))
    }
  }

  /**
   * The proposition tokens are pinned by id and by slot, so swapping their positions leaves the totals
   * conserved and still has to fail. Caught by the id clauses and the amount clauses together.
   */
  property("preserved: rejects a successor with the proposition tokens reordered (nextQueueTok/nextCollatTok id)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val t = j.emissionOut.tokens
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.setTokens(t(0), t(2), t(1), t(3))))
    }
  }

  // ─── path 1: Join / validQueueBox ─────────────────────────────────────────

  property("join: accepts a well-formed queue box at the tail of the queue") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      accepts(j.prover, joinTx(j)())
    }
  }

  property("join: accepts a join onto a queue that already has boxes in it") {
    withCtx { ctx =>
      val j = joinScenario(ctx, head = 3L, tail = 11L)
      accepts(j.prover, joinTx(j)())
    }
  }

  property("join: rejects a queue box that is not under the emission gate (CONST_GATE_HASH)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = j.queueOut.setContract(Contract.SIGMA_TRUE)))
    }
  }

  /** Also trips `nextCollatTok`, since a collateral token has to come out of the box to balance. */
  property("join: rejects a queue box whose first token is not the queue token") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val queueOut = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit,
        queueTokenId = LFSMHelpers.COLLAT_TOKEN)
      val out = emissionUTXO(ctx, tail = 1L, queueTokens = queueSupply - 1L,
        collatTokens = collatSupply - 1L)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out, queueOut = queueOut,
        burn = Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L))))
    }
  }

  /** Also trips `nextQueueTok`, since two tokens cannot leave a box that only gave up one. */
  property("join: rejects a queue box carrying more than one queue token") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val queueOut = j.queueOut.setTokens(
        Token(LFSMHelpers.QUEUE_TOKEN, 2L) +: j.queueOut.tokens.tail: _*)
      val out = emissionUTXO(ctx, tail = 1L, queueTokens = queueSupply - 2L)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out, queueOut = queueOut))
    }
  }

  property("join: rejects a queue box carrying a third token (tokens.size)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val in = funding(ctx, j.lenderProver, Seq(Token(LFSMHelpers.LIT_ID, j.permit), otherToken()))
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = j.queueOut.addToken(otherToken()),
        inputs = Seq(j.emissionIn, in)))
    }
  }

  property("join: rejects a permit posted in a token that is not LIT") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val in = funding(ctx, j.lenderProver, Seq(otherToken(j.permit)))
      val queueOut = queueUTXO(ctx, j.lenderProver, position = 0L, permit = j.permit,
        permitTokenId = unrelatedTokenId)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = queueOut, inputs = Seq(j.emissionIn, in)))
    }
  }

  /** The permit is LIT or it is nothing — a zero permit is the thermostat's disable hatch (§26.1). */
  property("join: accepts a queue box carrying no permit at all") {
    withCtx { ctx =>
      val j = joinScenario(ctx, params = Array(0L, 0L, 0L))
      accepts(j.prover, joinTx(j)())
    }
  }

  property("join: rejects a queue box with no lender key (R5 isDefined)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val queueOut = j.queueOut.setRegs(
        ErgoValue.of(DUST_BUDGET), ErgoValue.of(0L), ErgoValue.of(0.toByte), ErgoValue.of(0L))
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = queueOut))
    }
  }

  property("join: rejects a queue box with no fee value (R4 isDefined)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = j.queueOut.withReg(0, ErgoValue.of(5))))
    }
  }

  property("join: rejects a queue box with no extension flag (R6 isDefined)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(queueOut = j.queueOut.setRegs(j.queueOut.registers.take(2): _*)))
    }
  }

  // ─── path 1: Join / queueGrown ────────────────────────────────────────────

  /**
   * The emission box is the only source of either proposition token. A single one escaping its
   * accounting is enough to forge a queue box or a proof of spend, so the input total is pinned
   * alongside the exact output amount.
   */
  property("join: rejects a queue token entering from anywhere but the emission box (totalInInputs)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, propSupply = belowCeiling)
      val in = funding(ctx, j.lenderProver,
        Seq(Token(LFSMHelpers.LIT_ID, j.permit), Token(LFSMHelpers.QUEUE_TOKEN, 1L)))
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(j.emissionIn, in),
        burn = Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L))))
    }
  }

  property("join: rejects a collateral token entering from anywhere but the emission box (totalInInputs)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, propSupply = belowCeiling)
      val in = funding(ctx, j.lenderProver,
        Seq(Token(LFSMHelpers.LIT_ID, j.permit), Token(LFSMHelpers.COLLAT_TOKEN, 1L)))
      rejectsAtSigning(j.prover, joinTx(j)(inputs = Seq(j.emissionIn, in),
        burn = Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L))))
    }
  }

  property("join: rejects a successor that gives up more queue tokens than it hands out (nextQueueTok amount)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val out = emissionUTXO(ctx, tail = 1L, queueTokens = queueSupply - 2L)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out,
        burn = Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L))))
    }
  }

  property("join: rejects a successor that also gives up a collateral token (nextCollatTok amount)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val out = emissionUTXO(ctx, tail = 1L, queueTokens = queueSupply - 1L,
        collatTokens = collatSupply - 1L)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out,
        burn = Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L))))
    }
  }

  property("join: rejects a successor that advances the emission schedule (nextBlock)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.withReg(0, ErgoValue.of(1))))
    }
  }

  property("join: rejects a successor that changes the active set (nextSet)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val out = j.emissionOut.withReg(1, lenderSetValue(Seq(setEntry(j.lenderProver))))
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out))
    }
  }

  property("join: rejects a successor that emits LIT (nextTokenLIT amount)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val out = emissionUTXO(ctx, tail = 1L, queueTokens = queueSupply - 1L, lit = litSupply - LIT)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out))
    }
  }

  /**
   * Slot 3 is read by constant, never by position, so swapping the LIT entry for another token of the
   * same size cannot quietly redefine what a permit is (analysis §26.4, token-conservation traces).
   */
  property("join: rejects a successor whose LIT slot holds a different token (nextTokenLIT id)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, params = Array(0L, 0L, 0L))
      val in = funding(ctx, j.lenderProver, Seq(otherToken(litSupply)))
      val out = j.emissionOut.setTokens(
        j.emissionOut.tokens.take(3) :+ otherToken(litSupply): _*)
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = out, inputs = Seq(j.emissionIn, in),
        burn = Seq(Token(LFSMHelpers.LIT_ID, litSupply))))
    }
  }

  property("join: rejects a successor carrying an extra token (tokens.size)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val in = funding(ctx, j.lenderProver, Seq(Token(LFSMHelpers.LIT_ID, j.permit), otherToken()))
      rejectsAtSigning(j.prover, joinTx(j)(emissionOut = j.emissionOut.addToken(otherToken()),
        inputs = Seq(j.emissionIn, in)))
    }
  }

  // ─── path 2: Activate / the emission schedule ─────────────────────────────

  property("activate: accepts the head queue box becoming a collateral box") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      a.emitted shouldBe 640L * LIT
      accepts(a.prover, activateTx(a)())
    }
  }

  property("schedule: epoch 0 emits 500 LIT to the pool and 140 to the founders") {
    withCtx { ctx =>
      val (total, pub, split) = emissionAt(0, litSupply)
      total shouldBe 640L * LIT
      pub shouldBe 500L * LIT
      split.map(_._2) shouldBe Seq(100L * LIT, 25L * LIT, 15L * LIT)
      val a = activateScenario(ctx)
      accepts(a.prover, activateTx(a)())
    }
  }

  property("schedule: the public rate steps down only every second epoch (CONST_P1_DECAY_LENGTH)") {
    withCtx { ctx =>
      pubRateAt(EPOCH_LENGTH) shouldBe 500L * LIT
      pubRateAt(2 * EPOCH_LENGTH) shouldBe 450L * LIT
      val flat = activateScenario(ctx, currentBlock = EPOCH_LENGTH)
      flat.emitted shouldBe 640L * LIT
      accepts(flat.prover, activateTx(flat)())
      val stepped = activateScenario(ctx, currentBlock = 2 * EPOCH_LENGTH)
      stepped.emitted shouldBe 590L * LIT
      accepts(stepped.prover, activateTx(stepped)())
    }
  }

  property("schedule: the founders' allocation stops after CONST_PRIV_LENGTH epochs") {
    withCtx { ctx =>
      val block = PRIV_LENGTH * EPOCH_LENGTH
      privRateAt(block - 1) shouldBe INIT_PRIV_RATE
      privRateAt(block) shouldBe 0L
      emissionAt(block, litSupply)._3 shouldBe empty
      val a = activateScenario(ctx, currentBlock = block)
      a.emitted shouldBe 300L * LIT
      accepts(a.prover, activateTx(a)())
    }
  }

  property("schedule: phase 2 pays CONST_P2_RATE_1 then CONST_P2_RATE_2") {
    withCtx { ctx =>
      val first = activateScenario(ctx, currentBlock = P2_START * EPOCH_LENGTH)
      first.emitted shouldBe 150L * LIT
      accepts(first.prover, activateTx(first)())
      val second = activateScenario(ctx, currentBlock = (P2_START + 1) * EPOCH_LENGTH)
      second.emitted shouldBe 100L * LIT
      accepts(second.prover, activateTx(second)())
    }
  }

  property("schedule: phase 3 is flat at CONST_P3_RATE up to the final block") {
    withCtx { ctx =>
      val start = activateScenario(ctx, currentBlock = P3_START * EPOCH_LENGTH)
      start.emitted shouldBe 50L * LIT
      accepts(start.prover, activateTx(start)())
      val last = activateScenario(ctx, currentBlock = FINAL_BLOCK - 1)
      last.emitted shouldBe 50L * LIT
      accepts(last.prover, activateTx(last)())
    }
  }

  /**
   * The schedule ends on an explicit block bound rather than an epoch one, because integer division
   * over epochs cannot express the truncated final epoch. Past it the box carries on running the queue
   * with nothing left to emit, and permits still work.
   */
  property("schedule: emits nothing from CONST_FINAL_BLOCK onward (CONST_FINAL_BLOCK)") {
    withCtx { ctx =>
      FINAL_BLOCK shouldBe 2825000
      val a = activateScenario(ctx, currentBlock = FINAL_BLOCK)
      a.emitted shouldBe 0L
      accepts(a.prover, activateTx(a)())
    }
  }

  /**
   * The post-exhaustion state: no LIT left, nothing emitted, no permit posted. The collateral box
   * carries no LIT entry at all and the emission box is down to its three proposition tokens, which is
   * exactly what the contracts' `getOrElse` defaults are written to make pass vacuously.
   */
  property("schedule: accepts an activation once both the schedule and the supply are done") {
    withCtx { ctx =>
      val a = activateScenario(ctx, currentBlock = FINAL_BLOCK, lit = 0L, permit = 0L)
      a.emitted shouldBe 0L
      a.emissionOut.tokens.size shouldBe 3
      a.collatOut.tokens.size shouldBe 1
      accepts(a.prover, activateTx(a)())
    }
  }

  /** Epoch 2 emits 590, not the 640 of epoch 0 — the extra 50 has to be funded, and is still refused. */
  property("schedule: a collateral box carrying the wrong epoch's emission is rejected") {
    withCtx { ctx =>
      val a = activateScenario(ctx, currentBlock = 2 * EPOCH_LENGTH)
      a.emitted shouldBe 590L * LIT
      val in = funding(ctx, a.lenderProver, Seq(Token(LFSMHelpers.LIT_ID, 50L * LIT)), 3)
      val wrong = collateralUTXO(ctx, a.lenderProver, lit = 640L * LIT + a.permit,
        pub = 500L * LIT, founderSplit = founders)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = wrong,
        inputs = Seq(a.emissionIn, a.queueIn, in)))
    }
  }

  // ─── path 2: Activate / short supply ──────────────────────────────────────

  property("activate: accepts the last emitting activation, which drops the LIT entry (litEmitted)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lit = 640L * LIT)
      a.emitted shouldBe 640L * LIT
      a.emissionOut.tokens.size shouldBe 3
      accepts(a.prover, activateTx(a)())
    }
  }

  /**
   * A short box emits what it holds, not what the schedule says. Recording the nominal rate would let
   * it promise LIT it does not have, and the collateral contract would then take that out of the
   * lender's permit or fail outright and kill a slot for good.
   */
  property("activate: accepts a short supply, paying the founders nothing (emittedPriv)") {
    withCtx { ctx =>
      val (total, pub, split) = emissionAt(0, 100L * LIT)
      total shouldBe 100L * LIT
      pub shouldBe 100L * LIT
      split shouldBe empty
      val a = activateScenario(ctx, lit = 100L * LIT)
      accepts(a.prover, activateTx(a)())
    }
  }

  property("activate: rejects a successor still listing LIT once the supply is exhausted (litEmitted)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lit = 640L * LIT)
      val in = funding(ctx, a.lenderProver, Seq(Token(LFSMHelpers.LIT_ID, LIT)), 3)
      val out = a.emissionOut.addToken(Token(LFSMHelpers.LIT_ID, LIT))
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = out,
        inputs = Seq(a.emissionIn, a.queueIn, in)))
    }
  }

  // ─── path 2: Activate / validQueueIn and the active set ───────────────────

  property("activate: rejects a queue box that is not under the emission gate (CONST_GATE_HASH)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val fake = inputAt(queueUTXO(ctx, a.lenderProver, position = 0L, permit = a.permit,
        contract = Contract.SIGMA_TRUE), ctx, 1)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, fake, a.funding)))
    }
  }

  property("activate: rejects a lender who already holds a slot (notAlreadyActive)") {
    withCtx { ctx =>
      val entry = setEntry(lender(ctx))
      val a = activateScenario(ctx, lenderSet = Seq(entry))
      rejectsAtSigning(a.prover, activateTx(a)())
    }
  }

  property("activate: rejects a successor that appends the wrong lender (setUpdated)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(
        emissionOut = a.emissionOut.withReg(1, lenderSetValue(Seq(hashOf("someone-else"))))))
    }
  }

  property("activate: rejects a successor that leaves the active set unchanged (setUpdated)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(
        emissionOut = a.emissionOut.withReg(1, lenderSetValue(Seq.empty[Array[Byte]]))))
    }
  }

  // ─── path 2: Activate / rotation once the set is full ─────────────────────

  private val retiringLender: Array[Byte] = Blake2b256.hash("retiring-lender".getBytes("UTF-8"))

  property("rotation: accepts a proof of spend freeing a slot in a full active set") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lenderSet = fullSet(retiringLender, 42),
        retiring = Some((retiringLender, 42)))
      accepts(a.prover, activateTx(a)())
    }
  }

  property("rotation: rejects a full set with no proof of spend at INPUTS(2)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lenderSet = fullSet(retiringLender, 42),
        retiring = Some((retiringLender, 42)))
      val notAProof = funding(ctx, a.lenderProver, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)), 2)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, a.queueIn, notAProof)))
    }
  }

  property("rotation: rejects a proof of spend that is not under the emission gate (CONST_GATE_HASH)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lenderSet = fullSet(retiringLender, 42),
        retiring = Some((retiringLender, 42)))
      val fake = inputAt(proofOfSpendUTXO(ctx, retiringLender, contract = Contract.SIGMA_TRUE), ctx, 2)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, a.queueIn, fake, a.funding)))
    }
  }

  property("rotation: rejects a proof of spend whose first token is not the collateral token") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lenderSet = fullSet(retiringLender, 42),
        retiring = Some((retiringLender, 42)))
      val fake = inputAt(proofOfSpendUTXO(ctx, retiringLender)
        .setTokens(otherToken(), Token(LFSMHelpers.COLLAT_TOKEN, 1L)), ctx, 2)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, a.queueIn, fake, a.funding),
        burn = Seq(otherToken())))
    }
  }

  /**
   * A retirement cannot be used in the block that produced it. That severs the chain a miner could
   * otherwise build inside one block — spend a collateral box, take its retirement, reactivate the
   * same lender and spend that too — which would put two rollups in one block (analysis §26.3.3).
   */
  property("rotation: rejects a proof of spend created in this block (creationInfo)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, lenderSet = fullSet(retiringLender, 42),
        retiring = Some((retiringLender, 42)))
      val sameBlock = inputAt(proofOfSpendUTXO(ctx, retiringLender, createdAt = ctx.getHeight + 1), ctx, 2)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, a.queueIn, sameBlock, a.funding)))
    }
  }

  property("rotation: rejects a slot index that does not hold the retiring lender (CTX_SLOT_IDX)") {
    withCtx { ctx =>
      val set = fullSet(retiringLender, 42)
      val a = activateScenario(ctx, lenderSet = set, retiring = Some((retiringLender, 7)))
      rejectsAtSigning(a.prover, activateTx(a)())
    }
  }

  property("rotation: rejects a successor that does not put the new lender in the freed slot (nextSet)") {
    withCtx { ctx =>
      val set = fullSet(retiringLender, 42)
      val a = activateScenario(ctx, lenderSet = set, retiring = Some((retiringLender, 42)))
      rejectsAtSigning(a.prover, activateTx(a)(
        emissionOut = a.emissionOut.withReg(1, lenderSetValue(set))))
    }
  }

  // ─── path 2: Activate / validCollatBox ────────────────────────────────────

  property("activate: rejects a collateral box under the wrong script (CONST_COLLATERAL_HASH)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = a.collatOut.setContract(gateContract(ctx))))
    }
  }

  property("activate: rejects a collateral box carrying a third token (tokens.size)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val in = funding(ctx, a.lenderProver, Seq(otherToken()), 3)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = a.collatOut.addToken(otherToken()),
        inputs = Seq(a.emissionIn, a.queueIn, in)))
    }
  }

  property("activate: rejects a collateral box carrying more LIT than was emitted (collatLIT amount)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val in = funding(ctx, a.lenderProver, Seq(Token(LFSMHelpers.LIT_ID, LIT)), 3)
      val greedy = collateralUTXO(ctx, a.lenderProver, lit = a.emitted + a.permit + LIT,
        pub = 500L * LIT, founderSplit = founders)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = greedy,
        inputs = Seq(a.emissionIn, a.queueIn, in)))
    }
  }

  property("activate: rejects a collateral box overstating the pool's share (R6 pub)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val wrong = collateralUTXO(ctx, a.lenderProver, lit = a.emitted + a.permit,
        pub = 500L * LIT + 1L, founderSplit = founders)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = wrong))
    }
  }

  property("activate: rejects a collateral box dropping a founder from the split (R6 foundersMap)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val wrong = collateralUTXO(ctx, a.lenderProver, lit = a.emitted + a.permit,
        pub = 500L * LIT, founderSplit = founders.take(2))
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = wrong))
    }
  }

  property("activate: rejects a collateral box holding less than the lender's principal (value)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = a.collatOut.subValue(1L)))
    }
  }

  property("activate: rejects a collateral box changing the lender's fee (R4)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(
        collatOut = a.collatOut.withReg(0, ErgoValue.of(DUST_BUDGET + 1L))))
    }
  }

  property("activate: rejects a collateral box naming a different lender (R5)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val hijacked = a.collatOut.withReg(1, ErgoValue.of(contractOf(otherLender(ctx)).sigmaBoolean.get))
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = hijacked))
    }
  }

  property("activate: rejects a collateral box changing the extension flag (R7)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(collatOut = a.collatOut.withReg(3, ErgoValue.of(1.toByte))))
    }
  }

  /**
   * R8 is stamped from config R7 rather than being a constant in the collateral contract, so the
   * rollup chain can be replaced without reissuing anything. A box keeps whatever hash it was minted
   * under for its whole life, which is why a config change can never redirect one already live.
   */
  property("activate: rejects a collateral box naming a rollup the config did not (R8)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(
        collatOut = a.collatOut.withReg(4, bytesValue(hashOf("some-other-rollup")))))
    }
  }

  /**
   * A short R7 in the config would otherwise mint collateral boxes paying into nothing — permanently
   * dead active-set slots, the one damage class a later config update cannot repair (analysis §27.2).
   */
  property("activate: rejects a config whose rollup hash is not 32 bytes (rollupHoldingHash.size)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, rollupHash = hashOf("rollup").take(31))
      rejectsAtSigning(a.prover, activateTx(a)())
    }
  }

  // ─── path 2: Activate / queueAdvanced ─────────────────────────────────────

  property("activate: rejects a successor that does not advance the schedule (nextBlock)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = a.emissionOut.withReg(0, ErgoValue.of(0))))
    }
  }

  property("activate: rejects an extra queue token entering the transaction (totalInInputs)") {
    withCtx { ctx =>
      val a = activateScenario(ctx, propSupply = belowCeiling)
      val in = funding(ctx, a.lenderProver, Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L)), 3)
      rejectsAtSigning(a.prover, activateTx(a)(inputs = Seq(a.emissionIn, a.queueIn, in),
        burn = Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L))))
    }
  }

  property("activate: rejects a successor that does not take the queue token back (nextQueueTok)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val out = a.emissionOut.setTokens(
        a.emissionOut.tokens.updated(1, Token(LFSMHelpers.QUEUE_TOKEN, queueSupply - 1L)): _*)
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = out,
        burn = Seq(Token(LFSMHelpers.QUEUE_TOKEN, 1L))))
    }
  }

  property("activate: rejects a successor that gives up two collateral tokens (nextCollatTok)") {
    withCtx { ctx =>
      val a = activateScenario(ctx)
      val out = a.emissionOut.setTokens(
        a.emissionOut.tokens.updated(2, Token(LFSMHelpers.COLLAT_TOKEN, collatSupply - 2L)): _*)
      rejectsAtSigning(a.prover, activateTx(a)(emissionOut = out,
        burn = Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L))))
    }
  }

  // ─── path 3: Clear ────────────────────────────────────────────────────────

  property("clear: accepts spending a queue box whose lender already holds a slot") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      accepts(c.prover, clearTx(c)())
    }
  }

  /**
   * Strict order has no skip, so without this path a duplicate lender at the head would hold the whole
   * queue still. It is deliberately not a refund — refunding encourages spam.
   */
  property("clear: rejects a queue box whose lender is not in the active set (lenderAlreadyActive)") {
    withCtx { ctx =>
      val c = clearScenario(ctx, lenderSet = Seq(hashOf("somebody-else")))
      rejectsAtSigning(c.prover, clearTx(c)())
    }
  }

  property("clear: rejects a queue box that is not under the emission gate (CONST_GATE_HASH)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val fake = inputAt(queueUTXO(ctx, c.lenderProver, position = 0L, permit = c.permit,
        contract = Contract.SIGMA_TRUE), ctx, 1)
      rejectsAtSigning(c.prover, clearTx(c)(inputs = Seq(c.emissionIn, fake, c.funding)))
    }
  }

  property("clear: rejects a successor that advances the emission schedule (nextBlock)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = c.emissionOut.withReg(0, ErgoValue.of(1))))
    }
  }

  property("clear: rejects a successor that changes the active set (nextSet)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val out = c.emissionOut.withReg(1, lenderSetValue(Seq(hashOf("new-lender"))))
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = out))
    }
  }

  property("clear: rejects a successor that does not take the queue token back (nextQueueTok)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val out = c.emissionOut.setTokens(
        c.emissionOut.tokens.updated(1, Token(LFSMHelpers.QUEUE_TOKEN, queueSupply - 1L)): _*)
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = out))
    }
  }

  property("clear: rejects a successor that gives up a collateral token (nextCollatTok)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val out = c.emissionOut.setTokens(
        c.emissionOut.tokens.updated(2, Token(LFSMHelpers.COLLAT_TOKEN, collatSupply - 1L)): _*)
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = out))
    }
  }

  property("clear: rejects a successor that emits LIT (nextTokenLIT)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val out = c.emissionOut.setTokens(
        c.emissionOut.tokens.updated(3, Token(LFSMHelpers.LIT_ID, litSupply - LIT)): _*)
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = out))
    }
  }

  property("clear: rejects a successor carrying an extra token (tokens.size)") {
    withCtx { ctx =>
      val c = clearScenario(ctx)
      val in = funding(ctx, c.lenderProver, Seq(otherToken()), 3)
      rejectsAtSigning(c.prover, clearTx(c)(emissionOut = c.emissionOut.addToken(otherToken()),
        inputs = Seq(c.emissionIn, c.queueIn, in)))
    }
  }
}
