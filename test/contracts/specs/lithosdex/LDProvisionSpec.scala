package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * LD_Provision_Guard, and LD_Provision's refresh path.
 *
 * A provision box carries the guard, which holds nothing but a hash and runs the real logic from
 * context var 64. Everything about what a provision is lives in that logic, so the guard's only job is
 * to refuse to run anything else. The refresh path is the other half of a provision surviving: it may
 * sit untouched for years, and a box old enough for storage rent can be taken by a miner along with the
 * provision token that is the provision.
 */
class LDProvisionSpec extends AnyPropSpec with LithosDexSpecBase {

  private val shares: Long = 10000000000L

  /** HEIGHT is fixed by the preHeader, so both thresholds are exact numbers rather than approximations. */
  private val ageThreshold: Int = refreshHeight - REFRESH_AGE
  private val slackThreshold: Int = refreshHeight - HEIGHT_SLACK

  // ─── the guard ────────────────────────────────────────────────────────────

  property("guard: names the provision logic by the hash of its value bytes") {
    withCtx { ctx =>
      val c = ldContracts(ctx)
      c.provisionGuard.propBytes.length should be < 100
      Blake2b256.hash(c.provisionLogic.valueBytes) shouldBe c.provisionLogic.hashedValueBytes
    }
  }

  property("guard: rejects a substituted script, however permissive") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      // sigmaProp(true) in var 64 would let anyone take the provision token, which is why the hash is
      // checked before a single line of the supplied script is trusted.
      val bad = provisionInput(ctx, r.prov.utxo, LDHelpers.PROV_REFRESH, 0, Contract.SIGMA_TRUE)
      rejectsAtSigning(r.prover, refreshTx(r)(inputs = Seq(bad, r.fundingIn)))
    }
  }

  property("guard: rejects another LithosDex script in the logic slot") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val bad = provisionInput(ctx, r.prov.utxo, LDHelpers.PROV_REFRESH, 0, vaultContract(ctx))
      rejectsAtSigning(r.prover, refreshTx(r)(inputs = Seq(bad, r.fundingIn)))
    }
  }

  property("guard: rejects a spend with no logic supplied at all") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val bare = inputAt(r.prov.utxo, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(LDHelpers.PROV_REFRESH))
      rejectsAtSigning(r.prover, refreshTx(r)(inputs = Seq(bare, r.fundingIn)))
    }
  }

  property("guard: the supplied logic really runs — a path that must fail still fails") {
    withCtx { ctx =>
      // Correct bytes, correct hash, and an op the logic rejects. If the guard only hashed and never
      // executed, this would sign.
      val r = refreshScenario(ctx)
      val bad = provisionInput(ctx, r.prov.utxo, 4.toByte, 0)
      rejectsAtSigning(r.prover, refreshTx(r)(inputs = Seq(bad, r.fundingIn)))
    }
  }

  property("provision: every op byte outside the four documented paths fails closed") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      Seq[Byte](4, 5, 127, -1).foreach { op =>
        val bad = provisionInput(ctx, r.prov.utxo, op, 0)
        rejectsAtSigning(r.prover, refreshTx(r)(inputs = Seq(bad, r.fundingIn)))
      }
    }
  }

  // ─── how old a box has to be ──────────────────────────────────────────────

  property("refresh: accepts a box one block past CONST_REFRESH_AGE") {
    withCtx { ctx =>
      val r = refreshScenario(ctx, createdAt = ageThreshold - 1)
      accepts(r.prover, refreshTx(r)())
    }
  }

  property("refresh: rejects a box exactly at CONST_REFRESH_AGE") {
    withCtx { ctx =>
      val r = refreshScenario(ctx, createdAt = ageThreshold)
      rejectsAtSigning(r.prover, refreshTx(r)())
    }
  }

  property("refresh: rejects a box that is not old enough") {
    withCtx { ctx =>
      // Otherwise anyone could re-create somebody's box on repeat for the price of a fee, invalidating
      // every transaction its owner attempted against it.
      val r = refreshScenario(ctx, createdAt = refreshHeight - 10)
      rejectsAtSigning(r.prover, refreshTx(r)())
    }
  }

  property("refresh: rejects a successor that is only younger, not young") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover,
        refreshTx(r)(provOut = r.provOut.setCreationHeight(slackThreshold)))
    }
  }

  property("refresh: accepts a successor one block inside CONST_HEIGHT_SLACK") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      accepts(r.prover, refreshTx(r)(provOut = r.provOut.setCreationHeight(slackThreshold + 1)))
    }
  }

  property("refresh: rejects a successor back-dated to the box's own age") {
    withCtx { ctx =>
      val r = refreshScenario(ctx, createdAt = ageThreshold - 1)
      rejectsAtSigning(r.prover,
        refreshTx(r)(provOut = r.provOut.setCreationHeight(ageThreshold - 1)))
    }
  }

  // ─── nothing may change ───────────────────────────────────────────────────

  property("refresh: is permissionless — no ownership NFT is anywhere in the transaction") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      // Renewing costs a fee and moves nothing, so the only reason to do it is to keep a box alive —
      // including one whose owner has lost their key.
      accepts(r.prover, refreshTx(r)())
    }
  }

  property("refresh: rejects skimming the box's value") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover, refreshTx(r)(provOut = r.provOut.subValue(1L)))
    }
  }

  property("refresh: accepts topping the box up") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      accepts(r.prover, refreshTx(r)(provOut = r.provOut.addValue(Parameters.OneErg)))
    }
  }

  property("refresh: rejects taking the provision token") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val stripped = UTXO(guardContract(ctx), PROVISION_MIN, Seq.empty, r.provOut.registers)
        .setCreationHeight(r.height)
      rejectsAtSigning(r.prover, refreshTx(r)(provOut = stripped))
    }
  }

  property("refresh: rejects padding the box with an extra register") {
    withCtx { ctx =>
      // The one that has to be enforced rather than assumed: refresh is permissionless, so without a
      // length bound anyone may pad somebody else's provision past the storage fee CONST_PROVISION_MIN
      // was sized against, leaving it collectible along with the provision token in it.
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover, refreshTx(r)(
        provOut = r.provOut.setRegs(r.provOut.registers ++ Seq(padding(400)): _*)
          .setCreationHeight(r.height)))
    }
  }

  property("refresh: rejects a second token in the refreshed box") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val extra = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)))
      rejectsAtSigning(r.prover, refreshTx(r)(
        provOut = r.provOut.addToken(Token(LithosDexSpecBase.unrelatedToken, 1L)),
        inputs = Seq(r.provIn, r.fundingIn, extra)))
    }
  }

  property("refresh: rejects moving the ERG entry") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover,
        refreshTx(r)(provOut = r.provOut.withReg(0, bigIntValue(traded.accX - 1))))
    }
  }

  property("refresh: rejects moving the token entry") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover,
        refreshTx(r)(provOut = r.provOut.withReg(1, bigIntValue(traded.accY - 1))))
    }
  }

  property("refresh: rejects repointing the provision at another owner") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover, refreshTx(r)(
        provOut = r.provOut.withReg(2, bytesValue(LithosDexSpecBase.otherOwnerNFT.getBytes))))
    }
  }

  property("refresh: rejects inflating the provision's size") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      rejectsAtSigning(r.prover,
        refreshTx(r)(provOut = r.provOut.withReg(3, ErgoValue.of(shares * 10L))))
    }
  }

  property("refresh: rejects moving the box out from under the guard") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val moved = UTXO(userContract(ctx), r.provOut.value, r.provOut.tokens, r.provOut.registers)
        .setCreationHeight(r.height)
      rejectsAtSigning(r.prover, refreshTx(r)(provOut = moved))
    }
  }

  property("refresh: rejects the successor at the wrong output index") {
    withCtx { ctx =>
      val r = refreshScenario(ctx)
      val decoy = UTXO(userContract(ctx), Parameters.MinFee)
      rejectsAtSigning(r.prover,
        buildAt(ctx, Seq(r.provIn, r.fundingIn), Seq(decoy, r.provOut),
          r.prover.getAddress, r.height))
    }
  }

  property("refresh: accepts two aged provisions renewed in one transaction") {
    withCtx { ctx =>
      val a = provisionOf(ctx, shares, traded.accX, traded.accY)
      val b = provisionOf(ctx, shares / 2, traded.accX, traded.accY, LithosDexSpecBase.otherOwnerNFT)
      val agedA = a.utxo.setCreationHeight(ageThreshold - 1)
      val agedB = b.utxo.setCreationHeight(ageThreshold - 1)
      accepts(user(ctx), buildAt(ctx,
        Seq(provisionInput(ctx, agedA, LDHelpers.PROV_REFRESH, 0),
          provisionInput(ctx, agedB, LDHelpers.PROV_REFRESH, 1),
          funding(ctx, 2)),
        Seq(a.utxo.setCreationHeight(refreshHeight), b.utxo.setCreationHeight(refreshHeight)),
        user(ctx).getAddress, refreshHeight))
    }
  }

  property("refresh: rejects two provisions swapping their successors") {
    withCtx { ctx =>
      val a = provisionOf(ctx, shares, traded.accX, traded.accY)
      val b = provisionOf(ctx, shares / 2, traded.accX, traded.accY, LithosDexSpecBase.otherOwnerNFT)
      val agedA = a.utxo.setCreationHeight(ageThreshold - 1)
      val agedB = b.utxo.setCreationHeight(ageThreshold - 1)
      rejectsAtSigning(user(ctx), buildAt(ctx,
        Seq(provisionInput(ctx, agedA, LDHelpers.PROV_REFRESH, 0),
          provisionInput(ctx, agedB, LDHelpers.PROV_REFRESH, 1),
          funding(ctx, 2)),
        Seq(b.utxo.setCreationHeight(refreshHeight), a.utxo.setCreationHeight(refreshHeight)),
        user(ctx).getAddress, refreshHeight))
    }
  }
}
