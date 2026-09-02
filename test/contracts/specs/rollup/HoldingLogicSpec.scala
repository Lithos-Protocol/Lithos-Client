package contracts.specs.rollup

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Holding_Logic.ergo — one property per named condition in the contract.
 *
 * Every spend goes through Holding_Guard, so each transaction here carries the guard's two extra
 * variables: the op byte in 3 and the logic's own value bytes in 64. Op 0 is a NISP submission, op 1
 * is the transform into Evaluation. `HoldingGuardSpec` owns the dispatch and the hash check; this
 * spec owns the rules the logic applies once it is running.
 *
 * A submission is no longer value-conserving: it posts a refundable bond priced from its own claimed
 * score, so the successor is worth more than the box spent and R7's third slot grows to match.
 */
class HoldingLogicSpec extends AnyPropSpec with RollupSpecBase {

  private val period = LFSMHelpers.HOLDING_PERIOD

  /**
   * Context var 0 is the miner's identity *script*, executed via `executeFromVar[SigmaProp](0)`.
   * The tree key is the hash of the resulting proposition, so any script can be an identity — not
   * just a public key.
   */
  private def signerVar(identity: Contract): ContextVar =
    ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(identity.valueBytes), scalaByteType))

  private def opVar(op: Byte): ContextVar = ContextVar.of(3.toByte, ErgoValue.of(op))

  private def logicVar(ctx: BlockchainContext): ContextVar =
    ContextVar.of(64.toByte, ErgoValue.of(Colls.fromArray(holdingLogic(ctx).valueBytes), scalaByteType))

  /** The score the contract will read, which is zero for anything too short to carry one. */
  private def scoreSeenBy(score: Long, nispSize: Int): Long = if (nispSize > 8) score else 0L

  /** State shared by every submission-path case. */
  private case class Submission(ctx: BlockchainContext,
                                holdingIn: InputUTXO,
                                out: UTXO,
                                funding: InputUTXO,
                                prover: ErgoProver,
                                key: Array[Byte],
                                nisp: Array[Byte],
                                proof: ErgoValue[_],
                                periodStart: Long,
                                bond: Long)

  /**
   * Builds a well-formed submission. Each negative property takes this and perturbs exactly one
   * thing, so a rejection can only be caused by the condition under test.
   */
  private def submission(ctx: BlockchainContext,
                         score: Long = 5000L,
                         nispSize: Int = 8000,
                         priorMiners: Int = 0,
                         priorScore: Long = 0L,
                         priorBond: Long = 0L,
                         tokens: Seq[Token] = Seq.empty[Token]): Submission = {
    val holding = holdingContract(ctx)
    val prover = miner(ctx)
    val key = contractOf(prover).hashedPropBytes
    val nisp = nispBytes(score, nispSize)
    val bond = bondFor(scoreSeenBy(score, nispSize))

    val tree = treeWith(Seq.empty)
    val inTree = tree.ergoValue
    val insertion = tree.insert(key -> nisp)
    val outTree = tree.ergoValue

    val periodStart = ctx.getHeight.toLong - 100L

    val holdingIn = UTXO(holding, boxValue, tokens, Seq(
      inTree, ErgoValue.of(priorMiners), ErgoValue.of(BigInt(priorScore).bigInteger),
      stateReg(periodStart, periodStart, priorBond)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        signerVar(contractOf(prover)),
        ContextVar.of(1.toByte, ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(key), scalaByteType),
          ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
        ContextVar.of(2.toByte, insertion.proof.ergoValue),
        opVar(0.toByte),
        logicVar(ctx)
      )

    val out = UTXO(holding, boxValue + bond, tokens, Seq(
      outTree, ErgoValue.of(priorMiners + 1),
      ErgoValue.of(BigInt(priorScore + score).bigInteger),
      stateReg(periodStart, periodStart, priorBond + bond)
    ))

    Submission(ctx, holdingIn, out, fundingInput(ctx, prover), prover, key, nisp,
      insertion.proof.ergoValue, periodStart, bond)
  }

  private def submit(s: Submission, output: UTXO): UnsignedTransaction =
    build(s.ctx, Seq(s.holdingIn, s.funding), Seq(output), s.prover.getAddress)

  /** A submission whose identity is an arbitrary script rather than the signing miner's key. */
  private def submissionAs(ctx: BlockchainContext,
                           identity: Contract,
                           treeKey: Array[Byte],
                           spender: ErgoProver): (InputUTXO, UTXO, InputUTXO) = {
    val holding = holdingContract(ctx)
    val nisp = nispBytes(5000L, 8000)
    val bond = bondFor(5000L)

    val tree = treeWith(Seq.empty)
    val inTree = tree.ergoValue
    val insertion = tree.insert(treeKey -> nisp)
    val outTree = tree.ergoValue
    val periodStart = ctx.getHeight.toLong - 100L

    val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
      inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0L).bigInteger),
      stateReg(periodStart, periodStart, 0L)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        signerVar(identity),
        ContextVar.of(1.toByte, ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(treeKey), scalaByteType),
          ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
        ContextVar.of(2.toByte, insertion.proof.ergoValue),
        opVar(0.toByte),
        logicVar(ctx)
      )
    val out = UTXO(holding, boxValue + bond, Seq.empty[Token], Seq(
      outTree, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger),
      stateReg(periodStart, periodStart, bond)
    ))
    (in, out, fundingInput(ctx, spender))
  }

  // ─── path 1: submission ───────────────────────────────────────────────────

  property("submission: accepts a well-formed NISP from the signing miner") {
    withCtx { ctx =>
      val s = submission(ctx)
      accepts(s.prover, submit(s, s.out))
    }
  }

  property("submission: accepts a second miner joining a non-empty tree") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val first = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
      val firstNisp = nispBytes(3000L, 8000)
      val firstBond = bondFor(3000L)

      val tree = treeWith(Seq(first -> firstNisp))
      val inTree = tree.ergoValue

      val prover = miner(ctx)
      val key = contractOf(prover).hashedPropBytes
      val nisp = nispBytes(5000L, 8000)
      val bond = bondFor(5000L)
      val insertion = tree.insert(key -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(1), ErgoValue.of(BigInt(3000L).bigInteger),
        stateReg(periodStart, periodStart, firstBond)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(contractOf(prover)),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue),
          opVar(0.toByte),
          logicVar(ctx)
        )
      val out = UTXO(holding, boxValue + bond, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(2), ErgoValue.of(BigInt(8000L).bigInteger),
        stateReg(periodStart, periodStart, firstBond + bond)
      ))
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects a NISP keyed to someone other than the signer (authenticMiner)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val impostorKey = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
      val (in, out, funding) = submissionAs(ctx, contractOf(prover), impostorKey, prover)
      rejects(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects a successor that is not the holding contract (validUTXO)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejects(s.prover, submit(s, s.out.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("submission: rejects a change in tokens (tokensConserved)") {
    withCtx { ctx =>
      val s = submission(ctx, tokens = Seq(Token(fpTokenId, 100L)))
      rejects(s.prover, submit(s, s.out.setTokens(Token(fpTokenId, 99L))))
    }
  }

  property("submission: rejects a successor tree that does not match the insertion (treeUpdated)") {
    withCtx { ctx =>
      val s = submission(ctx)
      val wrongTree = treeWith(Seq(Blake2b256.hash("unrelated") -> nispBytes(1L, 16)))
      rejects(s.prover, submit(s, s.out.withReg(0, wrongTree.ergoValue)))
    }
  }

  property("submission: rejects when the miner count is not incremented (incrementedMiners)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejects(s.prover, submit(s, s.out.withReg(1, ErgoValue.of(0))))
    }
  }

  property("submission: rejects when the period start is moved (samePeriod)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s,
        s.out.withReg(3, stateReg(ctx.getHeight.toLong, s.periodStart, s.bond))))
    }
  }

  property("submission: rejects when the block height is moved (sameBlock)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s,
        s.out.withReg(3, stateReg(s.periodStart, s.periodStart + 1L, s.bond))))
    }
  }

  property("submission: rejects a NISP of 8 bytes or fewer") {
    withCtx { ctx =>
      val s = submission(ctx, nispSize = 8)
      rejects(s.prover, submit(s, s.out))
    }
  }

  property("submission: rejects a NISP whose score is not added to the running total (validNISP)") {
    withCtx { ctx =>
      val s = submission(ctx, score = 5000L, nispSize = 8000)
      rejects(s.prover, submit(s, s.out.withReg(2, ErgoValue.of(BigInt(1L).bigInteger))))
    }
  }

  property("submission: rejects a NISP claiming a non-positive score (validNISP)") {
    withCtx { ctx =>
      val s = submission(ctx, score = 0L, nispSize = 8000)
      rejects(s.prover, submit(s, s.out))
    }
  }

  property("submission: rejects a NISP below CONST_NISP_MIN (validNISP)") {
    withCtx { ctx =>
      val s = submission(ctx, score = 5000L, nispSize = 100)
      rejects(s.prover, submit(s, s.out))
    }
  }

  property("submission: accepts a NISP at exactly CONST_NISP_MIN") {
    withCtx { ctx =>
      val s = submission(ctx, score = 5000L, nispSize = LFSMHelpers.NISP_MIN)
      accepts(s.prover, submit(s, s.out))
    }
  }

  property("submission: rejects a NISP at CONST_NISP_MAX") {
    withCtx { ctx =>
      val s = submission(ctx, score = 5000L, nispSize = LFSMHelpers.NISP_MAX)
      rejects(s.prover, submit(s, s.out))
    }
  }

  property("submission: rejects when the holding box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejects(s.prover, build(s.ctx, Seq(s.funding, s.holdingIn), Seq(s.out), s.prover.getAddress))
    }
  }

  property("submission: rejects a transaction the identity script has not authorised (signerProp)") {
    withCtx { ctx =>
      val s = submission(ctx)
      val stranger = proverWith(ctx, funderSecret)
      rejects(stranger, submit(s, s.out))
    }
  }

  /**
   * The point of executing the identity from a context var: a miner identity can be any script, so
   * a contract can hold a payout claim.
   */
  property("submission: accepts a contract as the miner identity (executeFromVar)") {
    withCtx { ctx =>
      val anyone = proverWith(ctx, funderSecret)
      val identity = Contract.SIGMA_TRUE
      val (in, out, funding) = submissionAs(ctx, identity, identity.hashedPropBytes, anyone)
      accepts(anyone, build(ctx, Seq(in, funding), Seq(out), anyone.getAddress))
    }
  }

  property("submission: rejects an identity script whose hash is not the tree key (authenticMiner)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      // Satisfiable, but not the identity the key names.
      val (in, out, funding) =
        submissionAs(ctx, Contract.SIGMA_TRUE, contractOf(prover).hashedPropBytes, prover)
      rejects(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects an identity script that evaluates to false (signerProp)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val identity = Contract.SIGMA_FALSE
      val (in, out, funding) = submissionAs(ctx, identity, identity.hashedPropBytes, prover)
      rejects(prover, build(ctx, Seq(in, funding), Seq(out), prover.getAddress))
    }
  }

  /**
   * One NISP per identity per rollup, enforced by the AVL tree rejecting a duplicate key. The spam
   * bound leans on this.
   */
  property("submission: rejects a second NISP from an identity already in the tree") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val prover = miner(ctx)
      val key = contractOf(prover).hashedPropBytes
      val firstNisp = nispBytes(3000L, 8000)
      val secondNisp = nispBytes(5000L, 8000)
      val firstBond = bondFor(3000L)
      val bond = bondFor(5000L)

      val tree = treeWith(Seq(key -> firstNisp))
      val inTree = tree.ergoValue
      val insertion = tree.insert(key -> secondNisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(1), ErgoValue.of(BigInt(3000L).bigInteger),
        stateReg(periodStart, periodStart, firstBond)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(contractOf(prover)),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(secondNisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue),
          opVar(0.toByte),
          logicVar(ctx)
        )
      val out = UTXO(holding, boxValue + bond, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(2), ErgoValue.of(BigInt(8000L).bigInteger),
        stateReg(periodStart, periodStart, firstBond + bond)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  // ─── the entry bond (bondCollected) ───────────────────────────────────────
  //
  // Two equalities, and both matter. The box value has to rise by exactly the bond, and the ledger
  // in R7 slot 2 has to rise by exactly the same amount. Either one alone would let ERG and the
  // claim on it drift apart.

  property("bond: rejects a successor that keeps the old value (bondCollected)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s, s.out.subValue(s.bond)))
    }
  }

  property("bond: rejects a successor one nanoERG short of the bond (bondCollected)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s, s.out.subValue(1L)))
    }
  }

  /** Overpaying is refused too: the value and the ledger are pinned to each other, not bounded. */
  property("bond: rejects a successor holding more than the bond (bondCollected)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s, s.out.addValue(1L)))
    }
  }

  property("bond: rejects a successor whose ledger does not record the bond (bondCollected)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s,
        s.out.withReg(3, stateReg(s.periodStart, s.periodStart, 0L))))
    }
  }

  /**
   * The ledger is what Payout refunds against, so a successor claiming more than the ERG that
   * actually arrived is the shape that would let one miner draw down another's deposit.
   */
  property("bond: rejects a ledger entry larger than the value posted (bondCollected)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejectsAtSigning(s.prover, submit(s,
        s.out.withReg(3, stateReg(s.periodStart, s.periodStart, s.bond * 2L))))
    }
  }

  property("bond: accepts a submission onto a rollup that already holds bonds") {
    withCtx { ctx =>
      val s = submission(ctx, priorMiners = 1, priorScore = 3000L, priorBond = bondFor(3000L))
      accepts(s.prover, submit(s, s.out))
    }
  }

  /** Below the crossover the floor applies, above it the score does. */
  property("bond: a small score posts the floor, a large score posts score/divisor") {
    withCtx { ctx =>
      val floorScore = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR
      bondFor(floorScore / 2L) shouldBe LFSMHelpers.MIN_ENTRY_BOND
      bondFor(floorScore * 4L) shouldBe floorScore * 4L / LFSMHelpers.BOND_DIVISOR

      val big = submission(ctx, score = floorScore * 4L)
      big.bond should be > LFSMHelpers.MIN_ENTRY_BOND
      accepts(big.prover, submit(big, big.out))
    }
  }

  /** A bond priced from a different score than the NISP claims is what the contract has to refuse. */
  property("bond: rejects a bond priced below the claimed score") {
    withCtx { ctx =>
      val floorScore = LFSMHelpers.MIN_ENTRY_BOND * LFSMHelpers.BOND_DIVISOR
      val s = submission(ctx, score = floorScore * 4L)
      val cheap = LFSMHelpers.MIN_ENTRY_BOND
      val out = UTXO(s.out.contract, boxValue + cheap, s.out.tokens,
        s.out.registers.updated(3, stateReg(s.periodStart, s.periodStart, cheap)))
      rejectsAtSigning(s.prover, submit(s, out))
    }
  }

  property("state: rejects a successor whose state register is not three slots (stateSized)") {
    withCtx { ctx =>
      val s = submission(ctx)
      val four = ErgoValue.of(
        Colls.fromArray(Array(s.periodStart, s.periodStart, s.bond, 0L)), scalaLongType)
      rejectsAtSigning(s.prover, submit(s, s.out.withReg(3, four)))
    }
  }

  // ─── path 2: transform to evaluation ──────────────────────────────────────

  private def transform(ctx: BlockchainContext,
                        miners: Int = 3,
                        score: Long = 9000L,
                        bond: Long = 0L,
                        tokens: Seq[Token] = Seq.empty[Token]) = {
    val holding = holdingContract(ctx)
    val eval = evalContract(ctx)
    val prover = miner(ctx)
    val tree = treeWith(Seq(
      contractOf(prover).hashedPropBytes -> nispBytes(score, 8000)
    ))
    val periodStart = ctx.getHeight.toLong - period - 10L

    val in = UTXO(holding, boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      stateReg(periodStart, periodStart, bond)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(opVar(1.toByte), logicVar(ctx))

    val out = UTXO(eval, boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      stateReg(ctx.getHeight.toLong, periodStart, bond)
    ))
    (prover, in, out, tree, periodStart)
  }

  property("transform: converts the holding box into the evaluation contract once the period has passed") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects a successor that is not the evaluation contract (isEvaluationContract)") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.setContract(payoutContract(ctx))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the tree") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      val other = treeWith(Seq(Blake2b256.hash("someone else") -> nispBytes(1L, 16)))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(0, other.ergoValue)), prover.getAddress))
    }
  }

  property("transform: rejects a change to the miner count") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(1, ErgoValue.of(99))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the total score") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(2, ErgoValue.of(BigInt(1L).bigInteger))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the box value") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.subValue(Parameters.OneErg)), prover.getAddress))
    }
  }

  property("transform: rejects a new period start earlier than the end of the holding period") {
    withCtx { ctx =>
      val (prover, in, out, _, periodStart) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(3, stateReg(periodStart + period - 1L, periodStart, 0L))), prover.getAddress))
    }
  }

  property("transform: rejects when the block height is not carried over (sameBlock)") {
    withCtx { ctx =>
      val (prover, in, out, _, periodStart) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(3, stateReg(ctx.getHeight.toLong, periodStart + 1L, 0L))), prover.getAddress))
    }
  }

  /**
   * The bonds have to reach Evaluation intact: it is what Evaluation slashes against, and what it
   * subtracts from the box value to work out the reward Payout divides.
   */
  property("transform: carries the bond total into evaluation unchanged") {
    withCtx { ctx =>
      val held = bondFor(9000L)
      val (prover, in, out, _, _) = transform(ctx, bond = held)
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects a successor that drops the bond total") {
    withCtx { ctx =>
      val held = bondFor(9000L)
      val (prover, in, out, _, periodStart) = transform(ctx, bond = held)
      rejectsAtSigning(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(3, stateReg(ctx.getHeight.toLong, periodStart, 0L))), prover.getAddress))
    }
  }

  property("transform: rejects a successor that inflates the bond total") {
    withCtx { ctx =>
      val held = bondFor(9000L)
      val (prover, in, out, _, periodStart) = transform(ctx, bond = held)
      rejectsAtSigning(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(3, stateReg(ctx.getHeight.toLong, periodStart, held * 2L))), prover.getAddress))
    }
  }

  property("transform: rejects when the holding box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), Seq(out), prover.getAddress))
    }
  }
}
