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
                         tokens: Seq[Token] = Seq.empty[Token],
                         blockHeight: Long = -1L): Submission = {
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
    val block = if (blockHeight < 0L) periodStart else blockHeight

    val holdingIn = UTXO(holding, boxValue, tokens, Seq(
      inTree, ErgoValue.of(priorMiners), ErgoValue.of(BigInt(priorScore).bigInteger),
      stateReg(periodStart, block, priorBond)
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
      stateReg(periodStart, block, priorBond + bond)
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

  // ─── path 2, first branch: the block miner's top-up ───────────────────────
  //
  // Op 1 splits on `HEIGHT == currentBlock`. At the rollup's own block height it is a top-up: the box
  // value may rise and nothing else may move. At every later height it is the transform into
  // Evaluation, so the two branches never overlap.
  //
  // The height condition is what limits this to the block builder in practice. The holding box does
  // not exist until the genesis transaction lands, and `HEIGHT == currentBlock` only holds inside that
  // block, so the only party who can chain a top-up onto it is whoever is assembling the candidate.
  //
  // The added ERG lands in the reward pot: Evaluation hands Payout `SELF.value - totalBond`, so it is
  // divided among submitters by score rather than sitting apart.

  private case class TopUp(prover: ErgoProver, in: InputUTXO, out: UTXO, funding: InputUTXO,
                           height: Int, added: Long, bond: Long)

  private def topUp(ctx: BlockchainContext,
                    added: Long = Parameters.OneErg,
                    bond: Long = 0L,
                    miners: Int = 0,
                    score: Long = 0L,
                    tokens: Seq[Token] = Seq.empty[Token]): TopUp = {
    val holding = holdingContract(ctx)
    val prover = miner(ctx)
    val h = ctx.getHeight
    // At the rollup's own block the period start and the block height are one value, which is what
    // the collateral contract writes at genesis.
    val tree = treeWith(Seq.empty)
    val regs = Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      stateReg(h.toLong, h.toLong, bond))

    val in = UTXO(holding, boxValue, tokens, regs)
      .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(opVar(1.toByte), logicVar(ctx))
    val out = UTXO(holding, boxValue + added, tokens, regs)

    TopUp(prover, in, out, fundingInput(ctx, prover, 5L * Parameters.OneErg), h, added, bond)
  }

  private def topUpTx(ctx: BlockchainContext, t: TopUp,
                      out: UTXO = null,
                      height: Int = -1,
                      inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    buildAt(ctx, if (height < 0) t.height else height,
      if (inputs == null) Seq(t.in, t.funding) else inputs,
      Seq(if (out == null) t.out else out), t.prover.getAddress)

  property("topup: accepts added value in the rollup's own block") {
    withCtx { ctx =>
      val t = topUp(ctx)
      accepts(t.prover, topUpTx(ctx, t))
    }
  }

  property("topup: rejects a successor that does not raise the value") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.subValue(t.added)))
    }
  }

  property("topup: rejects a successor that lowers the value") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.subValue(t.added + 1L)))
    }
  }

  property("topup: accepts a single nanoERG, since the only bound is that value rises") {
    withCtx { ctx =>
      val t = topUp(ctx, added = 1L)
      accepts(t.prover, topUpTx(ctx, t))
    }
  }

  /**
   * The height condition is the whole restriction. One block later the same transaction takes op 1's
   * other branch, which demands an Evaluation successor and a period that has run its course.
   */
  property("topup: rejects the same spend one block after the rollup's own") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, height = t.height + 1))
    }
  }

  property("topup: rejects the same spend one block before the rollup's own") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, height = t.height - 1))
    }
  }

  property("topup: rejects a change to the tree") {
    withCtx { ctx =>
      val t = topUp(ctx)
      val other = treeWith(Seq(Blake2b256.hash("unrelated") -> nispBytes(1L, 16)))
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.withReg(0, other.ergoValue)))
    }
  }

  property("topup: rejects a change to the miner count") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.withReg(1, ErgoValue.of(1))))
    }
  }

  property("topup: rejects a change to the total score") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t,
        out = t.out.withReg(2, ErgoValue.of(BigInt(5000L).bigInteger))))
    }
  }

  property("topup: rejects a change to the period start") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t,
        out = t.out.withReg(3, stateReg(t.height.toLong - 1L, t.height.toLong, t.bond))))
    }
  }

  property("topup: rejects a change to the block height") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t,
        out = t.out.withReg(3, stateReg(t.height.toLong, t.height.toLong + 1L, t.bond))))
    }
  }

  /**
   * A top-up must not invent a bond liability. No submission can have landed at this height, so the
   * ledger is zero going in and has to stay there.
   */
  property("topup: rejects a successor that invents a bond liability") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t,
        out = t.out.withReg(3, stateReg(t.height.toLong, t.height.toLong, LFSMHelpers.MIN_ENTRY_BOND))))
    }
  }

  property("topup: rejects a change to the tokens") {
    withCtx { ctx =>
      val t = topUp(ctx, tokens = Seq(Token(fpTokenId, 100L)))
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.setTokens(Token(fpTokenId, 99L))))
    }
  }

  property("topup: rejects a successor under a different script") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("topup: rejects a successor whose state register is not three slots (stateSized)") {
    withCtx { ctx =>
      val t = topUp(ctx)
      val four = ErgoValue.of(
        Colls.fromArray(Array(t.height.toLong, t.height.toLong, t.bond, 0L)), scalaLongType)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, out = t.out.withReg(3, four)))
    }
  }

  property("topup: rejects when the holding box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val t = topUp(ctx)
      rejectsAtSigning(t.prover, topUpTx(ctx, t, inputs = Seq(t.funding, t.in)))
    }
  }

  /** Nobody signs for a top-up, so the guard runs no signer script and any spender may build one. */
  property("topup: is permissionless within the block, needing no miner identity") {
    withCtx { ctx =>
      val t = topUp(ctx)
      val outsider = proverWith(ctx, funderSecret)
      accepts(outsider, buildAt(ctx, t.height,
        Seq(t.in, fundingInput(ctx, outsider, 5L * Parameters.OneErg)),
        Seq(t.out), outsider.getAddress))
    }
  }

  property("topup: a topped-up box can be topped up again") {
    withCtx { ctx =>
      val t = topUp(ctx)
      val chained = t.out.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(opVar(1.toByte), logicVar(ctx))
      val second = UTXO(t.out.contract, t.out.value + Parameters.OneErg, t.out.tokens, t.out.registers)
      accepts(t.prover, buildAt(ctx, t.height,
        Seq(chained, fundingInput(ctx, t.prover, 5L * Parameters.OneErg)),
        Seq(second), t.prover.getAddress))
    }
  }

  // ─── the two op-1 branches do not overlap ─────────────────────────────────

  /**
   * `inSubmissionPhase` refuses the rollup's own block. That is what keeps the top-up branch from
   * sharing a height with a submission, and it is why the top-up's bond conservation is a statement
   * about zero rather than about an arbitrary balance.
   */
  property("submission: rejects a NISP submitted in the rollup's own block") {
    withCtx { ctx =>
      val h = ctx.getHeight
      val s = submission(ctx, blockHeight = h.toLong)
      rejectsAtSigning(s.prover, buildAt(ctx, h, Seq(s.holdingIn, s.funding), Seq(s.out),
        s.prover.getAddress))
    }
  }

  property("submission: accepts the same NISP one block later") {
    withCtx { ctx =>
      val h = ctx.getHeight
      val s = submission(ctx, blockHeight = h.toLong)
      accepts(s.prover, buildAt(ctx, h + 1, Seq(s.holdingIn, s.funding), Seq(s.out),
        s.prover.getAddress))
    }
  }
}
