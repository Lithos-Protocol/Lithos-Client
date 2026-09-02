package contracts.specs.dictionary

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * `MinerData_Guard.ergo` + `MinerData_Logic.ergo` — a small on-box guard and the rules it runs out of
 * context var 64. The split keeps the box small and lets the logic grow without moving the address
 * every registered miner's box sits at.
 *
 * No op byte in the guard: every MinerData path is the miner's own and the signer script is
 * unconditional, so the logic dispatches for itself.
 */
class MinerDataSpec extends AnyPropSpec with DictionarySpecBase {

  private val credentialId: ErgoId =
    ErgoId.create("c2ede4ca11a0000000000000000000000000000000000000000000000000000c")

  // ─── changeDiff ───────────────────────────────────────────────────────────

  private case class Change(ctx: BlockchainContext,
                            height: Int,
                            in: InputUTXO,
                            out: UTXO,
                            funding: InputUTXO,
                            prover: ErgoProver,
                            identity: Contract,
                            minerHash: Array[Byte],
                            newHeight: Int,
                            newScore: Long,
                            previous: (Int, Long))

  private def change(ctx: BlockchainContext,
                     lastCommitAge: Long = commitSpacing + 20L,
                     newOffset: Long = nispWindow + 40L,
                     newScore: Long = 120000L,
                     op: Byte = 0.toByte,
                     signer: Contract = null,
                     logic: Contract = null,
                     startWithOne: Boolean = false,
                     at: Int = -1): Change = {
    val height = if (at < 0) ctx.getHeight + 1 else at
    val prover = miner(ctx)
    val identity = contractOf(prover)
    val minerHash = identity.hashedPropBytes

    val previous = ((height - lastCommitAge).toInt, 100000L)
    val older = (previous._1 - 5000, 90000L)
    val current = if (startWithOne) Seq(previous) else Seq(previous, older)

    val box = dataUTXO(ctx, credentialId, minerHash, current, creationHeight = Some(height - 900))
    val vars = Seq(
      bytesVar(0.toByte, (if (signer == null) identity else signer).valueBytes),
      opVar(1.toByte, op),
      bytesVar(64.toByte, (if (logic == null) dataLogic(ctx) else logic).valueBytes))

    val newHeight = (height + newOffset).toInt
    val out = dataUTXO(ctx, credentialId, minerHash,
      Seq((newHeight, newScore), previous), creationHeight = Some(height))

    Change(ctx, height, inputAt(box, ctx, 0).setCtxVars(vars: _*), out,
      fundingInput(ctx, prover, Parameters.OneErg), prover, identity, minerHash,
      newHeight, newScore, previous)
  }

  private def commit(c: Change,
                     out: UTXO = null,
                     inputs: Seq[InputUTXO] = null): UnsignedTransaction =
    buildAt(c.ctx, c.height,
      if (inputs == null) Seq(c.in, c.funding) else inputs,
      Seq(if (out == null) c.out else out),
      c.prover.getAddress)

  property("changeDiff: accepts a well-formed commitment change") {
    withCtx { ctx =>
      val c = change(ctx)
      accepts(c.prover, commit(c))
    }
  }

  /** A freshly registered box holds one commitment, and its first change produces two. */
  property("changeDiff: accepts the first change on a newly registered box (one commitment)") {
    withCtx { ctx =>
      val c = change(ctx, startWithOne = true)
      accepts(c.prover, commit(c))
    }
  }

  // ─── the guard's own conditions ───────────────────────────────────────────

  property("guard: rejects a substituted holding logic (authenticLogic)") {
    withCtx { ctx =>
      val c = change(ctx, logic = Contract.SIGMA_TRUE)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  /**
   * The binding that makes this box the miner's. A data box is read as a data input elsewhere and a
   * data input's script never runs, so this is the only place R5 is paired to a signer.
   */
  property("guard: rejects a signer whose hash is not the identity in R5 (authenticMiner)") {
    withCtx { ctx =>
      val c = change(ctx, signer = contractOf(proverWith(ctx, otherSecret)))
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  property("guard: rejects a transaction the identity script has not authorised (signerProp)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(proverWith(ctx, funderSecret), commit(c))
    }
  }

  property("guard: rejects an identity script that evaluates to false (signerProp)") {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val identity = Contract.SIGMA_FALSE
      val prover = proverWith(ctx, funderSecret)
      val previous = ((height - commitSpacing - 20L).toInt, 100000L)
      val box = dataUTXO(ctx, credentialId, identity.hashedPropBytes,
        Seq(previous, (previous._1 - 5000, 90000L)), creationHeight = Some(height - 900))
      val in = inputAt(box, ctx, 0).setCtxVars(dataVars(ctx, identity, 0.toByte): _*)
      val out = dataUTXO(ctx, credentialId, identity.hashedPropBytes,
        Seq(((height + nispWindow + 40L).toInt, 120000L), previous), creationHeight = Some(height))
      rejectsAtSigning(prover, buildAt(ctx, height,
        Seq(in, fundingInput(ctx, prover, Parameters.OneErg)), Seq(out), prover.getAddress))
    }
  }

  /** A contract identity works here for the same reason it works for a submission. */
  property("guard: accepts a contract as the miner identity (executeFromVar)") {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val identity = Contract.SIGMA_TRUE
      val anyone = proverWith(ctx, funderSecret)
      val previous = ((height - commitSpacing - 20L).toInt, 100000L)
      val box = dataUTXO(ctx, credentialId, identity.hashedPropBytes,
        Seq(previous, (previous._1 - 5000, 90000L)), creationHeight = Some(height - 900))
      val in = inputAt(box, ctx, 0).setCtxVars(dataVars(ctx, identity, 0.toByte): _*)
      val out = dataUTXO(ctx, credentialId, identity.hashedPropBytes,
        Seq(((height + nispWindow + 40L).toInt, 120000L), previous), creationHeight = Some(height))
      accepts(anyone, buildAt(ctx, height,
        Seq(in, fundingInput(ctx, anyone, Parameters.OneErg)), Seq(out), anyone.getAddress))
    }
  }

  // ─── the commitment rules ─────────────────────────────────────────────────

  /**
   * The notice period. Without it a miner sets a difficulty and mines under it immediately, which is
   * picking a score after seeing which shares they got.
   */
  property("changeDiff: rejects a commitment taking effect too soon (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx, newOffset = nispWindow - 1L)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  property("changeDiff: accepts a commitment exactly CONST_NISP_WINDOW out (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx, newOffset = nispWindow)
      accepts(c.prover, commit(c))
    }
  }

  /**
   * Only two commitments are ever kept, so without a full rollup lifetime between changes a miner can
   * make two while one of their NISPs is still challengeable, evicting the one they submitted under.
   */
  property("changeDiff: rejects a change made too soon after the last one (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx, lastCommitAge = commitSpacing - 1L)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  property("changeDiff: accepts a change at exactly the spacing boundary (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx, lastCommitAge = commitSpacing)
      accepts(c.prover, commit(c))
    }
  }

  property("changeDiff: rejects a non-positive committed score (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx, newScore = 0L)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  property("changeDiff: rejects a successor that drops the previous commitment (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c,
        out = c.out.withReg(0, commitmentsValue(Seq((c.newHeight, c.newScore))))))
    }
  }

  property("changeDiff: rejects a successor keeping three commitments (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c, out = c.out.withReg(0, commitmentsValue(
        Seq((c.newHeight, c.newScore), c.previous, (c.previous._1 - 5000, 90000L))))))
    }
  }

  /** Index 1 must be the commitment that was newest, not any commitment the miner likes. */
  property("changeDiff: rejects a successor that rewrites the preserved commitment (correctCommit)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c, out = c.out.withReg(0, commitmentsValue(
        Seq((c.newHeight, c.newScore), (c.previous._1, c.previous._2 + 1L))))))
    }
  }

  // ─── conservation ─────────────────────────────────────────────────────────

  property("changeDiff: rejects a successor that is not under the guard (validUTXO)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c, out = c.out.setContract(Contract.SIGMA_TRUE)))
    }
  }

  property("changeDiff: rejects a change in box value (valueConserved)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c, out = c.out.subValue(1L)))
    }
  }

  property("changeDiff: rejects topping the box up above the floor (valueConserved)") {
    withCtx { ctx =>
      val c = change(ctx)
      // The extra ERG has to come from somewhere, or this fails on funding and proves nothing.
      val funded = fundingInput(ctx, c.prover, 3L * Parameters.OneErg)
      rejectsAtSigning(c.prover, commit(c, out = c.out.addValue(Parameters.OneErg),
        inputs = Seq(c.in, funded)))
    }
  }

  property("changeDiff: rejects a successor that drops the credential (tokensConserved)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c,
        out = c.out.setTokens(), inputs = Seq(c.in, c.funding)))
    }
  }

  property("changeDiff: rejects a successor that restamps the identity (identityConserved)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c,
        out = c.out.withReg(1, bytesValue(Blake2b256.hash("not me")))))
    }
  }

  /** The structural bound behind the rent floor: refuse R6 and the whole tail goes with it. */
  property("changeDiff: rejects a successor padded past R5 (noExtraRegisters)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c,
        out = c.out.withRegNum(6, bytesValue(Array.fill(512)(7.toByte)))))
    }
  }

  /**
   * The same bound MinerDictionary puts on the box it creates. A successor declaring an old creation
   * height is collectible sooner, which puts the credential in a box that is never guarded. Run at a
   * high pinned HEIGHT, since the eviction delay exceeds the mocked context's own height.
   */
  property("changeDiff: rejects a successor backdated past the collection gap (notCollectibleEarly)") {
    withCtx { ctx =>
      val c = change(ctx, at = 2000000)
      rejectsAtSigning(c.prover, commit(c,
        out = c.out.setCreationHeight((c.height - evictDelay).toInt)))
    }
  }

  property("changeDiff: accepts a successor one block inside the bound (notCollectibleEarly)") {
    withCtx { ctx =>
      val c = change(ctx, at = 2000000)
      accepts(c.prover, commit(c,
        out = c.out.setCreationHeight((c.height - evictDelay + 1L).toInt)))
    }
  }

  property("changeDiff: rejects when the data box is not INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val c = change(ctx)
      rejectsAtSigning(c.prover, commit(c, inputs = Seq(c.funding, c.in)))
    }
  }

  property("changeDiff: rejects an unrecognised op byte (fail closed)") {
    withCtx { ctx =>
      val c = change(ctx, op = 9.toByte)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  // ─── the dictionary-driven paths, from this side ──────────────────────────

  /**
   * Remove hands everything to MinerDictionary, so all this box establishes is that the dictionary is
   * in front of it. Without it a miner spends their data box against any box and nothing constrains
   * the successor.
   */
  /** Op 1 is the dictionary's eviction path, which never spends a data box, so it fails closed here. */
  property("remove: rejects op 1, which no data box path uses (fail closed)") {
    withCtx { ctx =>
      val c = change(ctx, op = 1.toByte)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  property("remove: rejects op 2 with no dictionary at INPUTS(0) (authenticDictionary)") {
    withCtx { ctx =>
      val c = change(ctx, op = 2.toByte)
      rejectsAtSigning(c.prover, commit(c))
    }
  }

  /**
   * The dictionary constrains `INPUTS(1)` and nothing else, so a second data box at `INPUTS(2)` would
   * otherwise satisfy its own copy of this path with no contract constraining its successor.
   *
   * `INPUTS(0)` is a stand-in carrying the dictionary NFT, so this isolates the logic's own conjunct.
   */
  property("remove: rejects a second data box riding along at INPUTS(2) (handledByDictionary)") {
    withCtx { ctx =>
      val height = ctx.getHeight + 1
      val prover = miner(ctx)
      val identity = contractOf(prover)
      val minerHash = identity.hashedPropBytes
      val commitments = Seq((height - 900, 100000L), (height - 6000, 90000L))

      val standIn = inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg,
        Seq(Token(mdToken, 1L))), ctx, 0)
      val first = dataInput(ctx,
        dataUTXO(ctx, credentialId, minerHash, commitments), identity, 2.toByte, index = 1)
      val second = dataInput(ctx,
        dataUTXO(ctx, credentialId, minerHash, commitments, value = dataValue + 1L),
        identity, 2.toByte, index = 2)

      // The first box is at INPUTS(1) and is content; the second is not, and says so.
      rejectsAtSigning(prover, buildAt(ctx, height,
        Seq(standIn, first, second, fundingInput(ctx, prover, Parameters.OneErg)),
        Seq(UTXO(identity, Parameters.OneErg)), prover.getAddress,
        burn = Seq(Token(credentialId, 2L))))
    }
  }
}
