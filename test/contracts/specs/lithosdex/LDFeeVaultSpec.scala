package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_FeeVault op 0 and LD_Provision op 0 — settlement.
 *
 * The vault pays a provision its size times how far the vault's accumulator has moved since that
 * provision last settled, and pins the entry the successor must carry. Two things have to hold at once:
 * the vault must never release more than it computed as owed, and a provision must never be able to
 * present a larger size or an older entry than it really has.
 */
class LDFeeVaultSpec extends AnyPropSpec with LithosDexSpecBase {

  private val shares: Long = 10000000000L

  private def prov(ctx: BlockchainContext, size: Long, eX: BigInt, eY: BigInt,
                   ownerId: org.ergoplatform.sdk.ErgoId = LithosDexSpecBase.ownerNFT): Provision =
    provisionOf(ctx, size, eX, eY, ownerId)

  // ─── what the vault releases ──────────────────────────────────────────────

  property("claim: pays exactly size times the distance the accumulator has moved") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      c.owedX shouldBe (BigInt(shares) * traded.accX / SCALE).toLong
      c.owedY shouldBe (BigInt(shares) * traded.accY / SCALE).toLong
      accepts(c.prover, claimTx(c)())
    }
  }

  property("claim: rejects releasing one nanoERG more than was owed") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX - 1L, c.vaultY - c.owedY, c.accX, c.accY)))
    }
  }

  property("claim: rejects releasing one token more than was owed") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX, c.vaultY - c.owedY - 1L, c.accX, c.accY)))
    }
  }

  property("claim: accepts the vault keeping more than it owed") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      accepts(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX, c.vaultY, c.accX, c.accY)))
    }
  }

  property("claim: rejects draining the vault below CONST_VAULT_MIN") {
    withCtx { ctx =>
      // The buffer that keeps the vault alive between flushes. It is never part of any payout, however
      // much a provision is owed.
      val c = claimScenario(ctx, vaultX = VAULT_MIN + 10000000000L)
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, VAULT_MIN - 1L, c.vaultY - c.owedY, c.accX, c.accY)))
    }
  }

  property("claim: rejects the vault advancing its own accumulator") {
    withCtx { ctx =>
      // Only a flush may move it, and only with the money behind it. Moving it here would promise a
      // stretch of fees nobody ever delivered.
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX, c.vaultY - c.owedY, c.accX + 1, c.accY)))
    }
  }

  // ─── the entry the successor carries ──────────────────────────────────────

  property("claim: rejects leaving the entry short so the same stretch can be claimed again") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX - 1, c.accY, LithosDexSpecBase.ownerNFT, shares))))
    }
  }

  property("claim: rejects pushing the entry past the vault's accumulator") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX + 1, c.accY, LithosDexSpecBase.ownerNFT, shares))))
    }
  }

  property("claim: rejects a provision whose entry is ahead of the vault's accumulator") {
    withCtx { ctx =>
      // An entry ahead of the vault means settling against fees the pool has not flushed yet, which is
      // the one direction that could ask for money that is not here.
      val ahead = prov(ctx, shares, traded.accX + 1, BigInt(0))
      val c = claimScenario(ctx, Seq(ahead))
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  property("claim: rejects settling a provision that is owed nothing") {
    withCtx { ctx =>
      val settled = prov(ctx, shares, traded.accX, traded.accY)
      val c = claimScenario(ctx, Seq(settled))
      c.owedX shouldBe 0L
      c.owedY shouldBe 0L
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  property("claim: accepts a provision behind on only one side") {
    withCtx { ctx =>
      val c = claimScenario(ctx, Seq(prov(ctx, shares, traded.accX, BigInt(0))))
      c.owedX shouldBe 0L
      c.owedY should be > 0L
      accepts(c.prover, claimTx(c)())
    }
  }

  // ─── several provisions at once ───────────────────────────────────────────

  property("claim: settles two provisions of different sizes for their own amounts") {
    withCtx { ctx =>
      val big = prov(ctx, shares, BigInt(0), BigInt(0))
      val small = prov(ctx, shares / 4, BigInt(0), BigInt(0), LithosDexSpecBase.otherOwnerNFT)
      val c = claimScenario(ctx, Seq(big, small))
      owed(shares, BigInt(0), traded.accX) shouldBe owed(shares / 4, BigInt(0), traded.accX) * 4 +- 4L
      accepts(c.prover, claimTx(c)())
    }
  }

  property("claim: rejects overpaying one provision out of another's share") {
    withCtx { ctx =>
      val a = prov(ctx, shares, BigInt(0), BigInt(0))
      val b = prov(ctx, shares, traded.accX / 2, traded.accY / 2, LithosDexSpecBase.otherOwnerNFT)
      val c = claimScenario(ctx, Seq(a, b))
      // What is measured is what was OWED, not what the outputs moved — so a claimant cannot decide the
      // split between the provisions they are settling.
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX - 1L, c.vaultY - c.owedY, c.accX, c.accY)))
    }
  }

  property("claim: rejects a count of zero (CTX_COUNT > 0)") {
    withCtx { ctx =>
      val c = claimScenario(ctx, count = Some(0))
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  property("claim: rejects a count that reaches past the provisions into ordinary inputs") {
    withCtx { ctx =>
      val c = claimScenario(ctx, count = Some(2))
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  property("claim: an understated count cannot be paid for the provisions it skips") {
    withCtx { ctx =>
      val a = prov(ctx, shares, BigInt(0), BigInt(0))
      val b = prov(ctx, shares, BigInt(0), BigInt(0), LithosDexSpecBase.otherOwnerNFT)
      val c = claimScenario(ctx, Seq(a, b), count = Some(1))
      // Both provisions are spent, only the first is counted, and the vault will not release the
      // second's share.
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  // ─── what counts as a provision ───────────────────────────────────────────

  property("claim: rejects a box holding some other token in place of a provision token") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val fake = provisionUTXO(ctx, BigInt(0), BigInt(0), LithosDexSpecBase.ownerNFT, shares,
        contract = userContract(ctx), provTokenId = LithosDexSpecBase.unrelatedToken)
      val fakeOut = UTXO(userContract(ctx), PROVISION_MIN,
        Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)),
        Seq(bigIntValue(c.accX), bigIntValue(c.accY)))
      rejectsAtSigning(c.prover, claimTx(c)(
        inputs = Seq(c.vaultIn, inputAt(fake, ctx, 1), c.fundingIn),
        outputs = Seq(c.vaultOut, fakeOut)))
    }
  }

  property("claim: rejects a box holding two provision tokens for double weight") {
    withCtx { ctx =>
      val doubled = provisionUTXO(ctx, BigInt(0), BigInt(0), LithosDexSpecBase.ownerNFT, shares,
        contract = userContract(ctx), provTokenAmount = 2L)
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        inputs = Seq(c.vaultIn, inputAt(doubled, ctx, 1)) ++ c.nftIns ++ Seq(c.fundingIn)))
    }
  }

  /**
   * The vault's own comment states this: a provision token is the whole of authentication, on the
   * assumption that one can only ever reach a guard-locked box. This property pins the consequence —
   * a token in any other box is a working claim on the vault — so that the assumption is visibly load
   * bearing. `LDStorageRentSpec` covers the one way a token can actually escape.
   */
  property("claim: a loose provision token is a working claim on the vault, whatever box holds it") {
    withCtx { ctx =>
      val loose = provisionUTXO(ctx, BigInt(0), BigInt(0), LithosDexSpecBase.ownerNFT, shares,
        contract = userContract(ctx))
      val c = claimScenario(ctx)
      val looseOut = UTXO(userContract(ctx), PROVISION_MIN, Seq(Token(LithosDexSpecBase.provToken, 1L)),
        Seq(bigIntValue(c.accX), bigIntValue(c.accY)))
      accepts(c.prover, claimTx(c)(
        inputs = Seq(c.vaultIn, inputAt(loose, ctx, 1), c.fundingIn),
        outputs = Seq(c.vaultOut, looseOut)))
    }
  }

  // ─── the vault box itself ─────────────────────────────────────────────────

  property("claim: rejects the vault anywhere but INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val moved = vaultInput(ctx, vaultUTXO(ctx, c.vaultX, c.vaultY, c.accX, c.accY),
        LDHelpers.VAULT_CLAIM, 9, Some(1))
      rejectsAtSigning(c.prover, claimTx(c)(
        inputs = Seq(c.fundingIn, moved) ++ c.provIns ++ c.nftIns,
        outputs = Seq(c.vaultOut) ++ c.provOuts))
    }
  }

  property("claim: rejects dropping the vault NFT") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val naked = UTXO(vaultContract(ctx), c.vaultX - c.owedX,
        Seq(Token(LithosDexSpecBase.tokenY, c.vaultY - c.owedY)),
        Seq(bigIntValue(c.accX), bigIntValue(c.accY)))
      rejectsAtSigning(c.prover, claimTx(c)(vaultOut = naked))
    }
  }

  property("claim: rejects a third token in the vault box (tokens.size)") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val extra = funding(ctx, 31, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)))
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = c.vaultOut.addToken(Token(LithosDexSpecBase.unrelatedToken, 1L)),
        inputs = Seq(c.vaultIn) ++ c.provIns ++ c.nftIns ++ Seq(c.fundingIn, extra)))
    }
  }

  property("claim: accepts a padded vault successor") {
    withCtx { ctx =>
      // Deliberate — see LDFlushSpec. Only the provision, which can sit for years, is bounded.
      val c = claimScenario(ctx)
      accepts(c.prover, claimTx(c)(
        vaultOut = c.vaultOut.setRegs(c.vaultOut.registers ++ Seq(padding(1000)): _*)))
    }
  }

  property("claim: rejects a padded provision successor") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(c.provOuts.head.setRegs(c.provOuts.head.registers ++ Seq(padding(400)): _*))))
    }
  }

  property("claim: rejects sending the vault to another script") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX, c.vaultY - c.owedY, c.accX, c.accY,
          contract = userContract(ctx))))
    }
  }

  // ─── the provision's own side of a claim ──────────────────────────────────

  property("claim: rejects settling a provision without its ownership NFT (ownerPresent)") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        inputs = Seq(c.vaultIn) ++ c.provIns ++ Seq(c.fundingIn)))
    }
  }

  property("claim: rejects a provision inflating its size on the way through") {
    withCtx { ctx =>
      // Without the size pin a claimant could raise their own R7 between claims and drain the vault a
      // little more each time.
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX, c.accY, LithosDexSpecBase.ownerNFT, shares * 10L))))
    }
  }

  property("claim: rejects a provision changing hands during a settlement") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX, c.accY, LithosDexSpecBase.otherOwnerNFT, shares))))
    }
  }

  property("claim: rejects skimming the provision box's rent buffer") {
    withCtx { ctx =>
      val c = claimScenario(ctx, Seq(provisionOf(ctx, shares, BigInt(0), BigInt(0),
        value = 2L * PROVISION_MIN)))
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX, c.accY, LithosDexSpecBase.ownerNFT, shares,
          value = 2L * PROVISION_MIN - 1L))))
    }
  }

  property("claim: rejects the provision token being taken out of the provision box") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val stripped = UTXO(guardContract(ctx), PROVISION_MIN, Seq.empty,
        Seq(bigIntValue(c.accX), bigIntValue(c.accY),
          bytesValue(LithosDexSpecBase.ownerNFT.getBytes), ErgoValue.of(shares)))
      rejectsAtSigning(c.prover, claimTx(c)(provOuts = Seq(stripped)))
    }
  }

  property("claim: rejects moving a provision out from under the guard") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      rejectsAtSigning(c.prover, claimTx(c)(
        provOuts = Seq(provisionUTXO(ctx, c.accX, c.accY, LithosDexSpecBase.ownerNFT, shares,
          contract = userContract(ctx)))))
    }
  }

  property("claim: rejects a provision settling against a counterfeit vault") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val fake = inputAt(UTXO(userContract(ctx), 100L * Parameters.OneErg,
        Seq(Token(LithosDexSpecBase.unrelatedToken, 1L))), ctx, 0)
      rejectsAtSigning(c.prover,
        build(ctx, Seq(fake) ++ c.provIns ++ c.nftIns ++ Seq(c.fundingIn),
          Seq(UTXO(userContract(ctx), Parameters.MinFee)) ++ c.provOuts,
          c.prover.getAddress, Seq.empty, Seq.empty))
    }
  }
  // ─── the empty zip ────────────────────────────────────────────────────────
  //
  // provIns and provOuts are both slices, and slice clamps rather than throwing, so a claim whose
  // outputs are shorter than its count forms no pairs and settles nothing. That direction is safe:
  // owed is a sum of non-negative terms and appears only as a lower bound on what the vault keeps, so
  // dropping pairs can only make it keep more. These two pin the boundary.

  /**
   * A claim that settles nobody is accepted, and moves nothing: the accumulators are pinned and the
   * successor has to hold at least what the box held. It costs a fee-less transaction and a donation,
   * and buys a new box id — the same respend-griefing any single-UTXO contract allows.
   */
  property("claim: a count with no matching outputs settles nothing and takes nothing") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val vIn = vaultInput(ctx, vaultUTXO(ctx, c.vaultX, c.vaultY, c.accX, c.accY),
        LDHelpers.VAULT_CLAIM, 0, Some(1))
      val fund = funding(ctx, 9)
      val out = vaultUTXO(ctx, c.vaultX + fund.value, c.vaultY, c.accX, c.accY)
      accepts(c.prover, work.lithos.mutations.TxBuilder(ctx).setInputs(vIn, fund)
        .setOutputs(out).buildTx(0L, c.prover.getAddress))
    }
  }

  /**
   * The same shape with a fee is refused, which is what keeps the no-op above uninteresting: the fee
   * output lands at the first provision slot, so the fold runs and finds no provision token there.
   */
  property("claim: a no-op claim cannot pay a fee, since the fee output fills the provision slot") {
    withCtx { ctx =>
      val c = claimScenario(ctx)
      val vIn = vaultInput(ctx, vaultUTXO(ctx, c.vaultX, c.vaultY, c.accX, c.accY),
        LDHelpers.VAULT_CLAIM, 0, Some(1))
      val out = vaultUTXO(ctx, c.vaultX, c.vaultY, c.accX, c.accY)
      rejectsAtSigning(c.prover, build(ctx, Seq(vIn, funding(ctx, 9)), Seq(out), c.prover.getAddress))
    }
  }
}
