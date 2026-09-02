package contracts.specs.rollup

import lfsm.LFSMHelpers
import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Evaluation.ergo — one property per named condition in the contract.
 *
 * Path 1 (HEIGHT < periodStart + CONST_PERIOD_LENGTH): a whitelisted fraud proof is executed from a
 * context variable, and Evaluation itself moves the ERG — it prices the removed entry's bond from
 * the score delta the proof is about to be held to, takes it off the box and pays it to the prover.
 * The proof owns the tree and the counters, so these properties use `sigmaProp(true)` as a stand-in
 * to isolate Evaluation's own conditions.
 *
 * Path 2 (otherwise): the box transforms into the Payout contract, handing it a reward figure that
 * excludes the bonds it is still holding.
 */
class EvaluationSpec extends AnyPropSpec with RollupSpecBase {

  private val period = LFSMHelpers.EVAL_PERIOD

  /** The scenario every fraud-proof property starts from: three miners, one being removed. */
  private val minersHeld = 3
  private val scoreHeld = 9000L
  private val scoreRemoved = 9000L

  private def fpControl(ctx: BlockchainContext): Contract =
    RollupContracts.mkFPControlTestnetContract(ctx, miner(ctx).getAddress.getPublicKey)

  /** An FP_CONTROL data input whitelisting the value-hashes of the given scripts. */
  private def fpDataInput(ctx: BlockchainContext,
                          whitelisted: Seq[Contract],
                          tokenId: ErgoId = fpTokenId): InputUTXO =
    UTXO(fpControl(ctx), Parameters.MinFee,
      tokens = Seq(Token(tokenId, 1L)),
      registers = Seq(ErgoValue.of(
        Colls.fromArray(whitelisted.map(c => Colls.fromArray(c.hashedValueBytes)).toArray),
        ErgoType.collType(scalaByteType)))
    ).toInput(ctx, ErgoId.create(dummyTxId), 3.toShort)

  private def evalTree(ctx: BlockchainContext): Tree =
    treeWith(Seq(contractOf(miner(ctx)).hashedPropBytes -> nispBytes(scoreHeld, 8000)))

  /** A whole fraud-proof spend: the box, its slashed successor, and the prover's reward output. */
  private case class Slash(ctx: BlockchainContext,
                           in: InputUTXO,
                           out: UTXO,
                           reward: UTXO,
                           proverIn: InputUTXO,
                           prover: ErgoProver,
                           script: Contract,
                           bond: Long,
                           periodStart: Long,
                           blockHeight: Long,
                           bondHeld: Long)

  private def slash(ctx: BlockchainContext,
                    script: Contract = Contract.SIGMA_TRUE,
                    tokens: Seq[Token] = Seq.empty[Token],
                    removedScore: Long = scoreRemoved,
                    bondHeld: Long = bondFor(scoreRemoved)): Slash = {
    val prover = miner(ctx)
    val periodStart = ctx.getHeight.toLong - 100L
    val blockHeight = periodStart - LFSMHelpers.HOLDING_PERIOD
    val tree = evalTree(ctx)
    val bond = bondFor(removedScore)

    val in = UTXO(evalContract(ctx), boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(minersHeld), ErgoValue.of(BigInt(scoreHeld).bigInteger),
      stateReg(periodStart, blockHeight, bondHeld)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(ContextVar.of(0.toByte,
        ErgoValue.of(Colls.fromArray(script.valueBytes), scalaByteType)))

    val out = UTXO(evalContract(ctx), boxValue - bond, tokens, Seq(
      tree.ergoValue, ErgoValue.of(minersHeld - 1),
      ErgoValue.of(BigInt(scoreHeld - removedScore).bigInteger),
      stateReg(periodStart, blockHeight, bondHeld - bond)
    ))

    val proverIn = fundingInput(ctx, prover)
    Slash(ctx, in, out, UTXO(contractOf(prover), bond), proverIn, prover, script, bond,
      periodStart, blockHeight, bondHeld)
  }

  /** Curried so each negative names the single field it perturbs. */
  private def slashTx(s: Slash)(out: UTXO = s.out,
                                reward: UTXO = s.reward,
                                inputs: Seq[InputUTXO] = Seq(s.in, s.proverIn),
                                dataInputs: Seq[InputUTXO] = Seq.empty,
                                burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val data = if (dataInputs.isEmpty) Seq(fpDataInput(s.ctx, Seq(s.script))) else dataInputs
    build(s.ctx, inputs, Seq(out, reward), s.prover.getAddress, data, burn)
  }

  // ─── path 1: fraud proof execution ────────────────────────────────────────

  property("eval: accepts a whitelisted fraud proof with an authentic FP_CONTROL data input") {
    withCtx { ctx =>
      val s = slash(ctx)
      accepts(s.prover, slashTx(s)())
    }
  }

  property("eval: rejects a fraud proof that is not whitelisted (validFP)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejects(s.prover, slashTx(s)(dataInputs = Seq(fpDataInput(ctx, Seq(payoutContract(ctx))))))
    }
  }

  property("eval: rejects a data input that does not carry CONST_FP_ID (authenticFPData)") {
    withCtx { ctx =>
      val s = slash(ctx)
      val wrongToken = ErgoId.create("0000000000000000000000000000000000000000000000000000000000000001")
      rejects(s.prover, slashTx(s)(dataInputs = Seq(fpDataInput(ctx, Seq(s.script), wrongToken))))
    }
  }

  property("eval: rejects a whitelisted proof that evaluates to false (executeFromVar)") {
    withCtx { ctx =>
      val s = slash(ctx, script = Contract.SIGMA_FALSE)
      rejects(s.prover, slashTx(s)())
    }
  }

  property("eval: rejects a successor that is not the evaluation contract (contractSame)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejects(s.prover, slashTx(s)(out = s.out.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("eval: rejects a transaction with more than two inputs (correctInputs)") {
    withCtx { ctx =>
      val s = slash(ctx)
      val extra = UTXO(contractOf(s.prover), Parameters.OneErg)
        .toInput(ctx, ErgoId.create(dummyTxId), 9.toShort)
      rejects(s.prover, slashTx(s)(inputs = Seq(s.in, s.proverIn, extra)))
    }
  }

  property("eval: rejects when the eval box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejects(s.prover, slashTx(s)(inputs = Seq(s.proverIn, s.in)))
    }
  }

  property("eval: rejects a change in tokens (tokensConserved)") {
    withCtx { ctx =>
      val s = slash(ctx, tokens = Seq(Token(fpTokenId, 100L)))
      rejects(s.prover, slashTx(s)(out = s.out.setTokens(Token(fpTokenId, 90L)),
        burn = Seq(Token(fpTokenId, 10L))))
    }
  }

  // ─── path 1: the bond slash ───────────────────────────────────────────────
  //
  // Evaluation owns the ERG on this path. It reads the removed entry's score off the register
  // transition the proof is about to be held to, prices the bond from it, and requires that exact
  // amount to leave both the box value and the ledger, landing on the prover's own script.

  property("slash: rejects a successor that keeps the whole box value (valueSlashed)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(out = s.out.addValue(s.bond)))
    }
  }

  property("slash: rejects a successor giving up more than the bond (valueSlashed)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(out = s.out.subValue(1L)))
    }
  }

  property("slash: rejects a ledger that does not shed the bond (bondSlashed)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(
        out = s.out.withReg(3, stateReg(s.periodStart, s.blockHeight, s.bondHeld))))
    }
  }

  /**
   * The half that stops the box quietly keeping a forfeited deposit: value and ledger have to move
   * by the same amount, so a successor that sheds the ERG but not the liability is refused.
   */
  property("slash: rejects a ledger shedding more than the value did (bondSlashed)") {
    withCtx { ctx =>
      // Three bonds held, one being slashed, so emptying the ledger sheds the other two for free.
      val s = slash(ctx, bondHeld = bondFor(scoreRemoved) * 3L)
      rejectsAtSigning(s.prover, slashTx(s)(
        out = s.out.withReg(3, stateReg(s.periodStart, s.blockHeight, 0L))))
    }
  }

  property("slash: rejects a prover reward of the wrong amount (proverPaid)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(reward = s.reward.subValue(1L)))
    }
  }

  property("slash: rejects a prover reward larger than the bond (proverPaid)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(reward = s.reward.addValue(1L)))
    }
  }

  /**
   * The forfeited bond has to reach whoever built the proof. Sending it anywhere else is the shape
   * that would let a third party harvest slashes off other people's transactions.
   */
  property("slash: rejects a reward paid to a script other than the prover's input (proverPaid)") {
    withCtx { ctx =>
      val s = slash(ctx)
      val stranger = contractOf(proverWith(ctx, otherSecret))
      rejectsAtSigning(s.prover, slashTx(s)(reward = UTXO(stranger, s.bond)))
    }
  }

  /** The bond is priced from the score the successor claims to remove, not from a flat rate. */
  property("slash: prices the bond from the score delta the proof declares") {
    withCtx { ctx =>
      val bigScore = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR * 4L
      val s = slash(ctx, removedScore = bigScore, bondHeld = bondFor(bigScore))
      s.bond should be > LFSMHelpers.MIN_ENTRY_BOND
      accepts(s.prover, slashTx(s)())
    }
  }

  /**
   * A removal that under-declares the score would slash a smaller bond and leave the difference
   * stranded in the box, so the two have to be priced off the same number.
   */
  property("slash: rejects a bond priced from a score other than the declared delta") {
    withCtx { ctx =>
      val bigScore = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR * 4L
      val s = slash(ctx, removedScore = bigScore, bondHeld = bondFor(bigScore))
      val floor = LFSMHelpers.MIN_ENTRY_BOND
      rejectsAtSigning(s.prover, slashTx(s)(
        out = UTXO(s.out.contract, boxValue - floor, s.out.tokens,
          s.out.registers.updated(3, stateReg(s.periodStart, s.blockHeight, s.bondHeld - floor))),
        reward = UTXO(contractOf(s.prover), floor)))
    }
  }

  /**
   * A proof declaring a bigger score delta than the entry it removes would price a bond larger than
   * the ledger holds, and the payout box would then owe a negative refund it can never satisfy. No
   * shipped proof can produce that, but the whitelist is what decides which proofs exist.
   */
  property("slash: rejects a slash larger than the ledger holds (bondSolvent)") {
    withCtx { ctx =>
      val big = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR * 4L
      val s = slash(ctx, removedScore = big, bondHeld = LFSMHelpers.MIN_ENTRY_BOND)
      (s.bondHeld - s.bond) should be < 0L
      rejectsAtSigning(s.prover, slashTx(s)())
    }
  }

  property("slash: accepts a slash that empties the ledger exactly (bondSolvent)") {
    withCtx { ctx =>
      val s = slash(ctx, bondHeld = bondFor(scoreRemoved))
      (s.bondHeld - s.bond) shouldBe 0L
      accepts(s.prover, slashTx(s)())
    }
  }

  property("slash: rejects a successor that moves the period start (samePeriod)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(
        out = s.out.withReg(3, stateReg(s.periodStart + 1L, s.blockHeight, s.bondHeld - s.bond))))
    }
  }

  property("slash: rejects a successor that moves the block height (sameBlock)") {
    withCtx { ctx =>
      val s = slash(ctx)
      rejectsAtSigning(s.prover, slashTx(s)(
        out = s.out.withReg(3, stateReg(s.periodStart, s.blockHeight + 1L, s.bondHeld - s.bond))))
    }
  }

  property("slash: rejects a successor whose state register is not three slots (stateSized)") {
    withCtx { ctx =>
      val s = slash(ctx)
      val four = ErgoValue.of(
        Colls.fromArray(Array(s.periodStart, s.blockHeight, s.bondHeld - s.bond, 0L)), scalaLongType)
      rejectsAtSigning(s.prover, slashTx(s)(out = s.out.withReg(3, four)))
    }
  }

  // ─── path 1: AVL flag conservation ────────────────────────────────────────

  /**
   * The fraud proofs check `removeTree.digest == nextTree.digest` — digest only — so nothing in them
   * pins the successor tree's enabled-operation flags. A proof could therefore hand back a tree with
   * the right contents but operations disabled, bricking the rollup: Payout could not remove, and no
   * later fraud proof could run. `flagsConserved` closes that at the eval box.
   */
  private def treeRegWith(ctx: BlockchainContext, flags: AvlTreeFlags): ErgoValue[_] =
    treeWith(Seq(contractOf(miner(ctx)).hashedPropBytes -> nispBytes(scoreHeld, 8000)), flags).ergoValue

  private def flagCase(ctx: BlockchainContext, flags: AvlTreeFlags) = {
    val s = slash(ctx)
    (s.prover, slashTx(s)(out = s.out.withReg(0, treeRegWith(ctx, flags))))
  }

  property("eval: accepts a successor tree with all operations enabled (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = flagCase(ctx, AvlTreeFlags.AllOperationsAllowed)
      accepts(prover, tx)
    }
  }

  property("eval: rejects a successor tree with insert disabled (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = flagCase(ctx, AvlTreeFlags(insertAllowed = false, updateAllowed = true, removeAllowed = true))
      rejects(prover, tx)
    }
  }

  property("eval: rejects a successor tree with update disabled (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = flagCase(ctx, AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = true))
      rejects(prover, tx)
    }
  }

  property("eval: rejects a successor tree with remove disabled (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = flagCase(ctx, AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false))
      rejects(prover, tx)
    }
  }

  property("eval: rejects a read-only successor tree (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = flagCase(ctx, AvlTreeFlags.ReadOnly)
      rejects(prover, tx)
    }
  }

  /** Same brick as a disabled flag, via a field the operation flags do not cover. */
  private def paramCase(ctx: BlockchainContext, params: PlasmaParameters) = {
    val s = slash(ctx)
    val swapped = treeWith(Seq.empty, AvlTreeFlags.AllOperationsAllowed, params).ergoValue
    (s.prover, slashTx(s)(out = s.out.withReg(0, swapped)))
  }

  property("eval: rejects a successor tree whose key length has been changed (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = paramCase(ctx, PlasmaParameters(33, None))
      rejects(prover, tx)
    }
  }

  property("eval: rejects a successor tree that has been given a fixed value length (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = paramCase(ctx, PlasmaParameters(32, Some(64)))
      rejects(prover, tx)
    }
  }

  property("eval: accepts a successor tree with the same key and value lengths (flagsConserved)") {
    withCtx { ctx =>
      val (prover, tx) = paramCase(ctx, PlasmaParameters.default)
      accepts(prover, tx)
    }
  }

  // ─── path 2: transform to payout ──────────────────────────────────────────

  private case class Handoff(prover: ErgoProver,
                             in: InputUTXO,
                             out: UTXO,
                             tree: Tree,
                             bondHeld: Long,
                             baseReward: Long,
                             lit: Long)

  private def transform(ctx: BlockchainContext,
                        tokens: Seq[Token] = Seq.empty[Token],
                        bondHeld: Long = 0L): Handoff = {
    val periodStart = ctx.getHeight.toLong - period - 10L
    val blockHeight = periodStart - LFSMHelpers.HOLDING_PERIOD
    val tree = evalTree(ctx)
    val lit = tokens.lift(1).map(_.amount).getOrElse(0L)
    val baseReward = boxValue - bondHeld

    val in = UTXO(evalContract(ctx), boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(minersHeld), ErgoValue.of(BigInt(scoreHeld).bigInteger),
      stateReg(periodStart, blockHeight, bondHeld)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

    val out = UTXO(payoutContract(ctx), boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(minersHeld), ErgoValue.of(BigInt(scoreHeld).bigInteger),
      stateReg(baseReward, lit, bondHeld)
    ))
    Handoff(miner(ctx), in, out, tree, bondHeld, baseReward, lit)
  }

  private def handoffTx(ctx: BlockchainContext, h: Handoff, out: UTXO = null,
                        inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    build(ctx,
      if (inputs == null) Seq(h.in, fundingInput(ctx, h.prover)) else inputs,
      Seq(if (out == null) h.out else out), h.prover.getAddress)

  property("transform: converts the eval box into the payout contract once the period has passed") {
    withCtx { ctx =>
      val h = transform(ctx)
      accepts(h.prover, handoffTx(ctx, h))
    }
  }

  property("transform: rejects a successor that is not the payout contract (isPayoutContract)") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h, h.out.setContract(evalContract(ctx))))
    }
  }

  property("transform: rejects a reward slot that is not the box value less the bonds (baseReward)") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h,
        h.out.withReg(3, stateReg(ctx.getHeight.toLong, h.lit, h.bondHeld))))
    }
  }

  /**
   * The condition that keeps a submitter's deposit out of everyone else's payout. Handing Payout the
   * whole box value as the reward would divide the bonds pro rata alongside the block reward, so the
   * miners would be paid with each other's refunds and the box would run dry before the last one.
   */
  property("transform: rejects a reward slot that includes the bonds still held (baseReward)") {
    withCtx { ctx =>
      val held = bondFor(scoreHeld) * 3L
      val h = transform(ctx, bondHeld = held)
      rejectsAtSigning(h.prover, handoffTx(ctx, h,
        h.out.withReg(3, stateReg(boxValue, h.lit, held))))
    }
  }

  property("transform: hands the bond total to Payout unchanged") {
    withCtx { ctx =>
      val held = bondFor(scoreHeld) * 3L
      val h = transform(ctx, bondHeld = held)
      h.baseReward shouldBe boxValue - held
      accepts(h.prover, handoffTx(ctx, h))
    }
  }

  property("transform: rejects a successor that drops the bond total") {
    withCtx { ctx =>
      val held = bondFor(scoreHeld) * 3L
      val h = transform(ctx, bondHeld = held)
      rejectsAtSigning(h.prover, handoffTx(ctx, h,
        h.out.withReg(3, stateReg(h.baseReward, h.lit, 0L))))
    }
  }

  property("transform: rejects a change to the tree") {
    withCtx { ctx =>
      val h = transform(ctx)
      val other = treeWith(Seq(Blake2b256.hash("someone else") -> nispBytes(1L, 16)))
      rejects(h.prover, handoffTx(ctx, h, h.out.withReg(0, other.ergoValue)))
    }
  }

  property("transform: rejects a change to the miner count") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h, h.out.withReg(1, ErgoValue.of(99))))
    }
  }

  property("transform: rejects a change to the total score") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h, h.out.withReg(2, ErgoValue.of(BigInt(1L).bigInteger))))
    }
  }

  property("transform: rejects a change to the box value") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h, h.out.subValue(Parameters.OneErg)))
    }
  }

  property("transform: rejects when the eval box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val h = transform(ctx)
      rejects(h.prover, handoffTx(ctx, h, null, Seq(fundingInput(ctx, h.prover), h.in)))
    }
  }

  // ─── path 2: the LIT snapshot ─────────────────────────────────────────────
  //
  // Slot 1 stops being a block height at this boundary and becomes the LIT total, which is the
  // denominator base Payout uses for every LIT share.

  private def withLit(ctx: BlockchainContext, amount: Long): Seq[Token] =
    Seq(Token(ErgoId.create("22" * 32), 1L), Token(fpTokenId, amount))

  property("transform: snapshots the token total when the box carries LIT (litSnapshot)") {
    withCtx { ctx =>
      val h = transform(ctx, tokens = withLit(ctx, 10000L))
      h.lit shouldBe 10000L
      accepts(h.prover, handoffTx(ctx, h))
    }
  }

  property("transform: rejects a snapshot that does not match the token amount (litSnapshot)") {
    withCtx { ctx =>
      val h = transform(ctx, tokens = withLit(ctx, 10000L))
      rejects(h.prover, handoffTx(ctx, h, h.out.withReg(3, stateReg(h.baseReward, 9999L, h.bondHeld))))
    }
  }

  /**
   * Unconditional, unlike the old token check that skipped a box holding none: Payout keeps its bond
   * ledger in the slot after this one, so a box carrying only the rollup NFT still has to write a
   * zero here rather than leaving the slot out.
   */
  property("transform: a box carrying no LIT must still snapshot a zero (litSnapshot)") {
    withCtx { ctx =>
      val h = transform(ctx, tokens = Seq(Token(ErgoId.create("22" * 32), 1L)))
      h.lit shouldBe 0L
      accepts(h.prover, handoffTx(ctx, h))
      rejects(h.prover, handoffTx(ctx, h, h.out.withReg(3, stateReg(h.baseReward, 1L, h.bondHeld))))
    }
  }

  property("transform: cannot be taken while the challenge window is still open") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val periodStart = ctx.getHeight.toLong - 100L
      val tree = evalTree(ctx)
      val in = UTXO(evalContract(ctx), boxValue, Seq.empty[Token], Seq(
        tree.ergoValue, ErgoValue.of(minersHeld), ErgoValue.of(BigInt(scoreHeld).bigInteger),
        stateReg(periodStart, periodStart, 0L)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      val out = UTXO(payoutContract(ctx), boxValue, Seq.empty[Token], Seq(
        tree.ergoValue, ErgoValue.of(minersHeld), ErgoValue.of(BigInt(scoreHeld).bigInteger),
        stateReg(boxValue, 0L, 0L)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }
}
