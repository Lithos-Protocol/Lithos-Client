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
 * Holding.ergo — one property per named condition in the contract.
 *
 * Path 1 (HEIGHT < periodStart + CONST_PERIOD_LENGTH): a miner inserts their NISP into the tree.
 * Path 2 (otherwise): the box transforms into the Evaluation contract.
 */
class HoldingSpec extends AnyPropSpec with RollupSpecBase {

  private val period = LFSMHelpers.HOLDING_PERIOD

  /**
   * Context var 0 is the miner's identity *script*, executed via `executeFromVar[SigmaProp](0)`.
   * The tree key is the hash of the resulting proposition, so any script can be an identity — not
   * just a public key.
   */
  private def signerVar(identity: Contract): ContextVar =
    ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(identity.valueBytes), scalaByteType))

  /** State shared by every submission-path case. */
  private case class Submission(ctx: BlockchainContext,
                                holdingIn: InputUTXO,
                                out: UTXO,
                                funding: InputUTXO,
                                prover: ErgoProver,
                                key: Array[Byte],
                                nisp: Array[Byte],
                                proof: ErgoValue[_],
                                priorScore: Long)

  /**
   * Builds a well-formed submission. Each negative property takes this and perturbs exactly one
   * thing, so a rejection can only be caused by the condition under test.
   */
  private def submission(ctx: BlockchainContext,
                         score: Long = 5000L,
                         nispSize: Int = 8000,
                         priorMiners: Int = 0,
                         priorScore: Long = 0L,
                         tokens: Seq[Token] = Seq.empty[Token]): Submission = {
    val holding = holdingContract(ctx)
    val prover = miner(ctx)
    val key = contractOf(prover).hashedPropBytes
    val nisp = nispBytes(score, nispSize)

    val tree = treeWith(Seq.empty)
    val inTree = tree.ergoValue
    val insertion = tree.insert(key -> nisp)
    val outTree = tree.ergoValue

    val periodStart = ctx.getHeight.toLong - 100L

    val holdingIn = UTXO(holding, boxValue, tokens, Seq(
      inTree, ErgoValue.of(priorMiners), ErgoValue.of(BigInt(priorScore).bigInteger), ErgoValue.of(periodStart)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(
        signerVar(contractOf(prover)),
        ContextVar.of(1.toByte, ErgoValue.pairOf(
          ErgoValue.of(Colls.fromArray(key), scalaByteType),
          ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
        ContextVar.of(2.toByte, insertion.proof.ergoValue)
      )

    val out = UTXO(holding, boxValue, tokens, Seq(
      outTree, ErgoValue.of(priorMiners + 1),
      ErgoValue.of(BigInt(priorScore + score).bigInteger), ErgoValue.of(periodStart)
    ))

    Submission(ctx, holdingIn, out, fundingInput(ctx, prover), prover, key, nisp, insertion.proof.ergoValue, priorScore)
  }

  private def submit(s: Submission, output: UTXO): UnsignedTransaction =
    build(s.ctx, Seq(s.holdingIn, s.funding), Seq(output), s.prover.getAddress)

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

      val tree = treeWith(Seq(first -> firstNisp))
      val inTree = tree.ergoValue

      val prover = miner(ctx)
      val key = contractOf(prover).hashedPropBytes
      val nisp = nispBytes(5000L, 8000)
      val insertion = tree.insert(key -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(1), ErgoValue.of(BigInt(3000L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(contractOf(prover)),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(2), ErgoValue.of(BigInt(8000L).bigInteger), ErgoValue.of(periodStart)
      ))
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects a NISP keyed to someone other than the signer (authenticMiner)") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val prover = miner(ctx)
      val impostorKey = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
      val nisp = nispBytes(5000L, 8000)

      val tree = treeWith(Seq.empty)
      val inTree = tree.ergoValue
      val insertion = tree.insert(impostorKey -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(contractOf(prover)),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(impostorKey), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger), ErgoValue.of(periodStart)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects a successor that is not the holding contract (validUTXO)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejects(s.prover, submit(s, s.out.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("submission: rejects a change in box value (rewardConserved)") {
    withCtx { ctx =>
      val s = submission(ctx)
      rejects(s.prover, submit(s, s.out.subValue(Parameters.OneErg)))
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
      rejects(s.prover, submit(s, s.out.withReg(3, ErgoValue.of(ctx.getHeight.toLong))))
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
      val s = submission(ctx, score = 5000L, nispSize = 7948)
      accepts(s.prover, submit(s, s.out))
    }
  }

  property("submission: rejects a NISP at CONST_NISP_MAX") {
    withCtx { ctx =>
      val s = submission(ctx, score = 5000L, nispSize = 26000)
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
   * a contract can hold a payout claim. Previously the var had to be a SigmaProp value.
   */
  property("submission: accepts a contract as the miner identity (executeFromVar)") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val identity = Contract.SIGMA_TRUE
      val key = identity.hashedPropBytes
      val nisp = nispBytes(5000L, 8000)

      val tree = treeWith(Seq.empty)
      val inTree = tree.ergoValue
      val insertion = tree.insert(key -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val anyone = proverWith(ctx, funderSecret)
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(identity),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger), ErgoValue.of(periodStart)
      ))
      accepts(anyone, build(ctx, Seq(in, fundingInput(ctx, anyone)), Seq(out), anyone.getAddress))
    }
  }

  property("submission: rejects an identity script whose hash is not the tree key (authenticMiner)") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val prover = miner(ctx)
      val key = contractOf(prover).hashedPropBytes
      val nisp = nispBytes(5000L, 8000)

      val tree = treeWith(Seq.empty)
      val inTree = tree.ergoValue
      val insertion = tree.insert(key -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(Contract.SIGMA_TRUE), // satisfiable, but not the identity the key names
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger), ErgoValue.of(periodStart)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  /**
   * One NISP per identity per rollup, enforced by the AVL tree rejecting a duplicate key. The spam
   * analysis in LITHOS_PROTOCOL_ANALYSIS 14.2 leans on this bound.
   */
  property("submission: rejects a second NISP from an identity already in the tree") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val prover = miner(ctx)
      val key = contractOf(prover).hashedPropBytes
      val firstNisp = nispBytes(3000L, 8000)
      val secondNisp = nispBytes(5000L, 8000)

      val tree = treeWith(Seq(key -> firstNisp))
      val inTree = tree.ergoValue
      val insertion = tree.insert(key -> secondNisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(1), ErgoValue.of(BigInt(3000L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(contractOf(prover)),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(secondNisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(2), ErgoValue.of(BigInt(8000L).bigInteger), ErgoValue.of(periodStart)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("submission: rejects an identity script that evaluates to false (signerProp)") {
    withCtx { ctx =>
      val holding = holdingContract(ctx)
      val prover = miner(ctx)
      val identity = Contract.SIGMA_FALSE
      val key = identity.hashedPropBytes
      val nisp = nispBytes(5000L, 8000)

      val tree = treeWith(Seq.empty)
      val inTree = tree.ergoValue
      val insertion = tree.insert(key -> nisp)
      val outTree = tree.ergoValue

      val periodStart = ctx.getHeight.toLong - 100L
      val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        inTree, ErgoValue.of(0), ErgoValue.of(BigInt(0L).bigInteger), ErgoValue.of(periodStart)
      )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
        .setCtxVars(
          signerVar(identity),
          ContextVar.of(1.toByte, ErgoValue.pairOf(
            ErgoValue.of(Colls.fromArray(key), scalaByteType),
            ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
          ContextVar.of(2.toByte, insertion.proof.ergoValue)
        )
      val out = UTXO(holding, boxValue, Seq.empty[Token], Seq(
        outTree, ErgoValue.of(1), ErgoValue.of(BigInt(5000L).bigInteger), ErgoValue.of(periodStart)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  // ─── path 2: transform to evaluation ──────────────────────────────────────

  private def transform(ctx: BlockchainContext,
                        miners: Int = 3,
                        score: Long = 9000L,
                        tokens: Seq[Token] = Seq.empty[Token]) = {
    val holding = holdingContract(ctx)
    val eval = evalContract(ctx)
    val prover = miner(ctx)
    val tree = treeWith(Seq(
      contractOf(prover).hashedPropBytes -> nispBytes(score, 8000)
    ))
    val periodStart = ctx.getHeight.toLong - period - 10L

    val in = UTXO(holding, boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger), ErgoValue.of(periodStart)
    )).toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

    val out = UTXO(eval, boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      ErgoValue.of(ctx.getHeight.toLong), ErgoValue.of(periodStart)
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
        Seq(out.withReg(3, ErgoValue.of(periodStart + period - 1L))), prover.getAddress))
    }
  }

  property("transform: rejects when R8 does not carry the original period start (nextBlock)") {
    withCtx { ctx =>
      val (prover, in, out, _, periodStart) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(4, ErgoValue.of(periodStart + 1L))), prover.getAddress))
    }
  }

  property("transform: rejects when the holding box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val (prover, in, out, _, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), Seq(out), prover.getAddress))
    }
  }
}
