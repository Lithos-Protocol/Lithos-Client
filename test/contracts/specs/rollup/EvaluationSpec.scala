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
 * context variable. The eval box checks only that the proof is authorised; the proof itself is
 * responsible for the resulting tree state, so these properties use `sigmaProp(true)` as a stand-in
 * proof to isolate Evaluation's own conditions.
 *
 * Path 2 (otherwise): the box transforms into the Payout contract.
 */
class EvaluationSpec extends AnyPropSpec with RollupSpecBase {

  private val period = LFSMHelpers.EVAL_PERIOD

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

  private def evalBox(ctx: BlockchainContext,
                      periodStart: Long,
                      tokens: Seq[Token] = Seq.empty[Token]): (UTXO, Tree, Int, Long) = {
    val tree = treeWith(Seq(contractOf(miner(ctx)).hashedPropBytes -> nispBytes(9000L, 8000)))
    val miners = 3
    val score = 9000L
    val box = UTXO(evalContract(ctx), boxValue, tokens, Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      ErgoValue.of(periodStart), ErgoValue.of(periodStart - LFSMHelpers.HOLDING_PERIOD)
    ))
    (box, tree, miners, score)
  }

  /** Eval box inside its challenge window, carrying the script to execute in context var 0. */
  private def duringEval(ctx: BlockchainContext,
                         script: Contract,
                         tokens: Seq[Token] = Seq.empty[Token]): (InputUTXO, UTXO) = {
    val periodStart = ctx.getHeight.toLong - 100L
    val (box, _, _, _) = evalBox(ctx, periodStart, tokens)
    val in = box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      .setCtxVars(ContextVar.of(0.toByte,
        ErgoValue.of(Colls.fromArray(script.valueBytes), scalaByteType)))
    (in, box)
  }

  // ─── path 1: fraud proof execution ────────────────────────────────────────

  property("eval: accepts a whitelisted fraud proof with an authentic FP_CONTROL data input") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val (in, out) = duringEval(ctx, fp)
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
    }
  }

  property("eval: rejects a fraud proof that is not whitelisted (validFP)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val (in, out) = duringEval(ctx, Contract.SIGMA_TRUE)
      val whitelistOfSomethingElse = fpDataInput(ctx, Seq(payoutContract(ctx)))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out),
        prover.getAddress, dataInputs = Seq(whitelistOfSomethingElse)))
    }
  }

  property("eval: rejects a data input that does not carry CONST_FP_ID (authenticFPData)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val (in, out) = duringEval(ctx, fp)
      val wrongToken = ErgoId.create("0000000000000000000000000000000000000000000000000000000000000001")
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp), wrongToken))))
    }
  }

  property("eval: rejects a whitelisted proof that evaluates to false (executeFromVar)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_FALSE
      val (in, out) = duringEval(ctx, fp)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
    }
  }

  property("eval: rejects a successor that is not the evaluation contract (contractSame)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val (in, out) = duringEval(ctx, fp)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out.setContract(Contract.SIGMA_TRUE)),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
    }
  }

  property("eval: rejects a transaction with more than two inputs (correctInputs)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val (in, out) = duringEval(ctx, fp)
      val extra = UTXO(contractOf(prover), Parameters.OneErg)
        .toInput(ctx, ErgoId.create(dummyTxId), 9.toShort)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover), extra), Seq(out),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
    }
  }

  property("eval: rejects when the eval box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val (in, out) = duringEval(ctx, fp)
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), Seq(out),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
    }
  }

  property("eval: rejects a change in tokens (tokensConserved)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val fp = Contract.SIGMA_TRUE
      val held = Token(fpTokenId, 100L)
      val (in, out) = duringEval(ctx, fp, tokens = Seq(held))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out.setTokens(Token(fpTokenId, 90L))),
        prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp))), burn = Seq(Token(fpTokenId, 10L))))
    }
  }

  // ─── path 1: AVL flag conservation ────────────────────────────────────────

  /**
   * The seven fraud proofs check `removeTree.digest == nextTree.digest` — digest only — so nothing
   * in them pins the successor tree's enabled-operation flags. A proof could therefore hand back a
   * tree with the right contents but operations disabled, bricking the rollup: Payout could not
   * remove, and no later fraud proof could run. `flagsConserved` closes that at the eval box.
   */
  private def treeRegWith(ctx: BlockchainContext, flags: AvlTreeFlags): ErgoValue[_] =
    treeWith(Seq(contractOf(miner(ctx)).hashedPropBytes -> nispBytes(9000L, 8000)), flags).ergoValue

  private def flagCase(ctx: BlockchainContext, flags: AvlTreeFlags) = {
    val prover = miner(ctx)
    val fp = Contract.SIGMA_TRUE
    val (in, out) = duringEval(ctx, fp)
    (prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out.withReg(0, treeRegWith(ctx, flags))),
      prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
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
    val prover = miner(ctx)
    val fp = Contract.SIGMA_TRUE
    val (in, out) = duringEval(ctx, fp)
    val swapped = treeWith(Seq.empty, AvlTreeFlags.AllOperationsAllowed, params).ergoValue
    (prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out.withReg(0, swapped)),
      prover.getAddress, dataInputs = Seq(fpDataInput(ctx, Seq(fp)))))
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

  /**
   * When the eval box carries tokens, `tokensAdded` requires the payout successor to snapshot the
   * token total into R8 — that snapshot is the denominator base Payout uses for every LIT share.
   */
  private def transform(ctx: BlockchainContext, tokens: Seq[Token] = Seq.empty[Token]) = {
    val periodStart = ctx.getHeight.toLong - period - 10L
    val (box, tree, miners, score) = evalBox(ctx, periodStart, tokens)
    val in = box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
    val base = Seq(
      tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger),
      ErgoValue.of(boxValue)
    )
    val regs = if (tokens.nonEmpty) base :+ ErgoValue.of(tokens.head.amount) else base
    val out = UTXO(payoutContract(ctx), boxValue, tokens, regs)
    (miner(ctx), in, out, tree)
  }

  property("transform: converts the eval box into the payout contract once the period has passed") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects a successor that is not the payout contract (isPayoutContract)") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.setContract(evalContract(ctx))), prover.getAddress))
    }
  }

  property("transform: rejects when R7 is not set to the block reward (nextPeriod == SELF.value)") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(3, ErgoValue.of(ctx.getHeight.toLong))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the tree") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      val other = treeWith(Seq(Blake2b256.hash("someone else") -> nispBytes(1L, 16)))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(0, other.ergoValue)), prover.getAddress))
    }
  }

  property("transform: rejects a change to the miner count") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(1, ErgoValue.of(99))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the total score") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(2, ErgoValue.of(BigInt(1L).bigInteger))), prover.getAddress))
    }
  }

  property("transform: rejects a change to the box value") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.subValue(Parameters.OneErg)), prover.getAddress))
    }
  }

  property("transform: rejects when the eval box is not the first input (onlyOne)") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx)
      rejects(prover, build(ctx, Seq(fundingInput(ctx, prover), in), Seq(out), prover.getAddress))
    }
  }

  property("transform: snapshots the token total into R8 when the box carries tokens (tokensAdded)") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx, tokens = Seq(Token(fpTokenId, 10000L)))
      accepts(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }

  property("transform: rejects a payout successor with no R8 when tokens are present (tokensAdded)") {
    withCtx { ctx =>
      val held = Token(fpTokenId, 10000L)
      val (prover, in, out, tree) = transform(ctx, tokens = Seq(held))
      val withoutR8 = UTXO(payoutContract(ctx), boxValue, Seq(held), out.registers.take(4))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(withoutR8), prover.getAddress))
    }
  }

  property("transform: rejects an R8 snapshot that does not match the token amount (tokensAdded)") {
    withCtx { ctx =>
      val (prover, in, out, _) = transform(ctx, tokens = Seq(Token(fpTokenId, 10000L)))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)),
        Seq(out.withReg(4, ErgoValue.of(9999L))), prover.getAddress))
    }
  }

  property("transform: cannot be taken while the challenge window is still open") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val periodStart = ctx.getHeight.toLong - 100L
      val (box, tree, miners, score) = evalBox(ctx, periodStart)
      val in = box.toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
      val out = UTXO(payoutContract(ctx), boxValue, Seq.empty[Token], Seq(
        tree.ergoValue, ErgoValue.of(miners), ErgoValue.of(BigInt(score).bigInteger), ErgoValue.of(boxValue)
      ))
      rejects(prover, build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress))
    }
  }
}
