package contracts.specs.rollup

import lfsm.LFSMHelpers
import lfsm.contracts.DictionaryContracts
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * `FP_NonMatchingCommitment.ergo` — the score in a NISP has to equal the difficulty the miner
 * committed to in advance.
 *
 * It is the only proof reading state outside the rollup, and the only one whose data inputs vary: a
 * live registration sends it down the branch that reads the accused's MinerData box, anything else
 * down the branch that reads the dictionary alone. It is also the cheapest — eight score bytes, no
 * VLQ walking, no header — so it depends on none of the format gates.
 *
 * Two things carry the design and both are tested below. The governing commitment is chosen at the
 * rollup's own block rather than at submission, because submission height is nowhere on chain and is
 * the miner's to pick. And every branch is gated on the dictionary's own NFT, including the branch
 * that fires for a *missing* entry — without that, an attacker passes a box holding an empty tree and
 * removes any honest miner they like.
 */
class FPNonMatchingCommitmentSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val committed = 100000L
  private val window = LFSMHelpers.NISP_WINDOW

  private def mdToken(ctx: BlockchainContext): ErgoId = LFSMHelpers.getMDToken(ctx.getNetworkType)

  private def minerDataContract(ctx: BlockchainContext): Contract =
    DictionaryContracts.mkMinerDataContract(ctx, mdToken(ctx))

  /** A credential id belonging to nothing else in the protocol. */
  private val credential: ErgoId = ErgoId.create("2b" * 32)

  /** `[credentialId: 32][validUntil: 8]`, the entry MinerDictionary writes at registration. */
  private def entry(id: ErgoId, validUntil: Long): Array[Byte] =
    id.getBytes ++ Longs.toByteArray(validUntil)

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  /** The dictionary box: its NFT is what makes the tree in R4 the protocol's. */
  private def dictBox(ctx: BlockchainContext,
                      tree: Tree,
                      token: ErgoId = null): InputUTXO =
    UTXO(Contract.SIGMA_TRUE, Parameters.MinFee,
      Seq(Token(if (token == null) mdToken(ctx) else token, 1L)),
      Seq(tree.ergoValue)).toInput(ctx, ErgoId.create(dummyTxId), 4.toShort)

  /** The accused's MinerData box: R4 the commitments newest first, R5 their own hash. */
  private def dataBox(ctx: BlockchainContext,
                      commitments: Seq[(Int, Long)],
                      minerHash: Array[Byte],
                      token: ErgoId = credential,
                      contract: Contract = null): InputUTXO =
    UTXO(if (contract == null) minerDataContract(ctx) else contract,
      LFSMHelpers.MIN_DATA_BOX_AMNT,
      Seq(Token(token, 1L)),
      Seq(
        ErgoValue.of(Colls.fromArray(commitments.toArray),
          ErgoType.pairType(scalaIntType, scalaLongType)),
        bytesValue(minerHash))
    ).toInput(ctx, ErgoId.create(dummyTxId), 5.toShort)

  private case class Setup(f: Fraud, tree: Tree)

  /**
   * A proof against a miner whose NISP claims `score`, holding `commitments`, registered until
   * `validUntil`. `registered = false` leaves them out of the dictionary entirely.
   */
  private def setup(ctx: BlockchainContext,
                    score: Long,
                    commitments: Seq[(Int, Long)] = null,
                    registered: Boolean = true,
                    validUntil: Long = -1L,
                    entryBytes: Array[Byte] = null,
                    dictToken: ErgoId = null,
                    dataToken: ErgoId = credential,
                    dataContract: Contract = null,
                    dataMinerHash: Array[Byte] = null): Setup = {
    val prover = miner(ctx)
    val accused = contractOf(prover).hashedPropBytes
    val block = rollupBlock(ctx)
    val expiry = if (validUntil < 0L) block + LFSMHelpers.DATA_LIFETIME else validUntil

    val tree = treeWith(
      if (!registered) Seq.empty
      else Seq(accused -> (if (entryBytes == null) entry(credential, expiry) else entryBytes)))
    val lookup = tree.lookUp(accused)

    // In effect at the rollup's block: committed NISP_WINDOW or more blocks before it.
    val held = if (commitments != null) commitments
    else Seq(((block - window - 10L).toInt, committed))

    val live = registered && entryBytes == null && block < expiry
    val data =
      if (!live) Seq(dictBox(ctx, tree, dictToken))
      else Seq(dictBox(ctx, tree, dictToken),
        dataBox(ctx, held, if (dataMinerHash == null) accused else dataMinerHash,
          dataToken, dataContract))

    Setup(fraud(ctx, fpNonMatchingCommitment(ctx),
      nispBytes(score, realisticNispSize), score,
      extraVars = Seq(ContextVar.of(3.toByte, lookup.proof.ergoValue)),
      dataInputs = fpDataInput(ctx, Seq(fpNonMatchingCommitment(ctx))) +: data), tree)
  }

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: a score equal to the commitment in effect, so the proof declines cleanly") {
    withCtx { ctx =>
      val s = setup(ctx, committed)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  // ─── the fraud ────────────────────────────────────────────────────────────

  property("mismatch: a score above the commitment is caught") {
    withCtx { ctx =>
      val s = setup(ctx, committed + 1L)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("mismatch: a score below the commitment is caught") {
    withCtx { ctx =>
      val s = setup(ctx, committed - 1L)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  /** No registration means nothing on chain ever said what this miner could claim. */
  property("unregistered: a miner absent from the dictionary is caught") {
    withCtx { ctx =>
      val s = setup(ctx, committed, registered = false)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("expired: a registration that lapsed before the rollup's block is caught") {
    withCtx { ctx =>
      val s = setup(ctx, committed, validUntil = rollupBlock(ctx) - 1L)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("expired: a registration lapsing exactly at the rollup's block is caught") {
    withCtx { ctx =>
      val s = setup(ctx, committed, validUntil = rollupBlock(ctx))
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("expired: a registration lapsing one block after is still honoured") {
    withCtx { ctx =>
      val s = setup(ctx, committed, validUntil = rollupBlock(ctx) + 1L)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  /** A short entry cannot be read for an expiry, and the padding is what stops that throwing. */
  property("malformed: an entry that is not forty bytes is caught rather than thrown on") {
    withCtx { ctx =>
      val s = setup(ctx, committed, entryBytes = Array.fill(8)(1.toByte))
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  // ─── which commitment governs ─────────────────────────────────────────────

  /**
   * The newest commitment has not taken effect at the rollup's block, so the previous one governs.
   * Getting this backwards lets a miner change difficulty and have it apply retroactively.
   */
  property("governing: a newest commitment not yet in effect defers to the previous one") {
    withCtx { ctx =>
      val block = rollupBlock(ctx)
      val commitments = Seq(((block - 5L).toInt, 999999L), ((block - window - 50L).toInt, committed))
      val s = setup(ctx, committed, commitments = commitments)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("governing: claiming the not-yet-effective commitment is caught") {
    withCtx { ctx =>
      val block = rollupBlock(ctx)
      val commitments = Seq(((block - 5L).toInt, 999999L), ((block - window - 50L).toInt, committed))
      val s = setup(ctx, 999999L, commitments = commitments)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("governing: a commitment effective exactly NISP_WINDOW before the block governs") {
    withCtx { ctx =>
      val commitments = Seq(((rollupBlock(ctx) - window).toInt, committed))
      val s = setup(ctx, committed, commitments = commitments)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  /**
   * A single commitment that had not taken effect leaves the miner nothing to submit under. The
   * fallback is `-1`, which no NISP can carry because Holding requires a positive score.
   */
  property("governing: a lone commitment not yet in effect makes any submission fraud") {
    withCtx { ctx =>
      val commitments = Seq(((rollupBlock(ctx) - 5L).toInt, committed))
      val s = setup(ctx, committed, commitments = commitments)
      provesFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  // ─── the authentication chain ─────────────────────────────────────────────

  /**
   * The gate that matters most. An unauthenticated dictionary on the non-membership branch would let
   * anyone pass an empty tree and slash whoever they liked.
   */
  property("authentication: a dictionary without the NFT cannot slash an unregistered miner") {
    withCtx { ctx =>
      val s = setup(ctx, committed, registered = false, dictToken = ErgoId.create("cd" * 32))
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("authentication: a dictionary without the NFT cannot slash a mismatched score") {
    withCtx { ctx =>
      val s = setup(ctx, committed + 1L, dictToken = ErgoId.create("cd" * 32))
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  /** Anyone can send a box to the MinerData address, so the credential is what makes it the miner's. */
  property("authentication: a MinerData box not holding the recorded credential is refused") {
    withCtx { ctx =>
      val s = setup(ctx, committed + 1L, dataToken = ErgoId.create("ef" * 32))
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("authentication: a MinerData box under a different script is refused") {
    withCtx { ctx =>
      val s = setup(ctx, committed + 1L, dataContract = Contract.SIGMA_TRUE)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  property("authentication: a MinerData box naming a different miner in R5 is refused") {
    withCtx { ctx =>
      val s = setup(ctx, committed + 1L,
        dataMinerHash = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes)
      findsNoFraud(s.f.prover, fraudTx(s.f)())
    }
  }

  transitionChecks("nonMatchingCommitment") { ctx => setup(ctx, committed + 1L).f }
}
