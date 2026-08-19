package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_LiquidityPool op 4, LD_Provision op 3 and LD_FeeVault op 1 — resizes.
 *
 * A resize changes a provision's size without closing it, which means what it has already earned has to
 * be settled without any money moving. The pool does that by shifting the entry so that size times the
 * distance to the accumulator comes out unchanged, and it flushes in the same transaction so the vault's
 * accumulator is the same number — without that, a shrinking provision would keep a claim on fees the
 * vault has never been handed.
 */
class LDResizeSpec extends AnyPropSpec with LithosDexSpecBase {

  private val oldShares: Long = 10000000000L
  private val bigger: Long = 15000000000L
  private val smaller: Long = 4000000000L

  // ─── the entry identity ───────────────────────────────────────────────────

  property("resize: what is already owed survives a grow unchanged, or rounds down") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      val before = owed(oldShares, r.prov.entryX, traded.accX)
      val after = owed(bigger, r.quote.nextEntryX, traded.accX)
      before should be > 0L
      after should be <= before
      (before - after) should be < 2L
      accepts(r.prover, resizeTx(r)())
    }
  }

  property("resize: what is already owed survives a shrink unchanged, or rounds down") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      val before = owed(oldShares, r.prov.entryY, traded.accY)
      val after = owed(smaller, r.quote.nextEntryY, traded.accY)
      before should be > 0L
      after should be <= before
      accepts(r.prover, resizeTx(r)())
    }
  }

  property("resize: rejects an entry one tick further back than the identity allows") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      // A shorter distance is fine and a longer one is not: this is the only place a provision's claim
      // could be stretched without anyone paying for it.
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX - 1, r.quote.nextEntryY,
          r.prov.ownerNFT, smaller)))
    }
  }

  property("resize: rejects an entry one tick further forward than the identity allows") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX + 1, r.quote.nextEntryY,
          r.prov.ownerNFT, smaller)))
    }
  }

  property("resize: rejects carrying the old entry through unchanged on a shrink") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      // The naive implementation. It would quietly forfeit most of what the provision had earned.
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, smaller)))
    }
  }

  property("resize: rejects the token-side entry being moved on its own") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX, r.quote.nextEntryY - 1,
          r.prov.ownerNFT, bigger)))
    }
  }

  // ─── what the size change costs ───────────────────────────────────────────

  property("resize: rejects growing without paying for the extra shares (resizeAMM)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(reservesX = r.before.reservesX))))
    }
  }

  property("resize: rejects underpaying a grow by one nanoERG (resizeAMM)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(reservesX = r.after.reservesX - 1L))))
    }
  }

  property("resize: rejects taking more than proportional out of a shrink (resizeAMM)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(reservesY = r.after.reservesY - 1L))))
    }
  }

  property("resize: rejects the pool moving more supply than the provision's size change") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(supply = r.after.supply + 1L))))
    }
  }

  property("resize: rejects a resize that changes nothing (deltaSupplyLP != 0)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.before.copy(pendingX = 0L, pendingY = 0L)),
        provOut = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, oldShares)))
    }
  }

  property("resize: rejects shrinking a provision to nothing (pNew > 0)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, smaller)
      // A size of nothing is a closed provision, which is Redeem's job — and Redeem is what burns the
      // ownership NFT, so allowing it here would leave a live NFT pointing at an empty claim.
      val closed = traded.copy(
        reservesX = traded.reservesX - r.before.view.simRedeem(oldShares).amountX,
        reservesY = traded.reservesY - r.before.view.simRedeem(oldShares).amountY,
        pendingX = 0L, pendingY = 0L,
        supply = traded.supply - oldShares)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, closed),
        provOut = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, 0L)))
    }
  }

  property("resize: accepts a shrink that lands exactly on CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      val tight = traded.copy(supply = MIN_SUPPLY + 999L)
      val r = resizeScenario(ctx, 1000L, 1L, tight)
      r.after.supply shouldBe MIN_SUPPLY
      accepts(r.prover, resizeTx(r)())
    }
  }

  property("resize: rejects a shrink that takes supply under CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      val tight = traded.copy(supply = MIN_SUPPLY + 998L)
      val r = resizeScenario(ctx, 1000L, 1L, tight)
      r.after.supply shouldBe MIN_SUPPLY - 1L
      rejectsAtSigning(r.prover, resizeTx(r)())
    }
  }

  // ─── the provision box's identity ─────────────────────────────────────────

  property("resize: rejects repointing the provision at a different owner (provOut.R6)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX, r.quote.nextEntryY,
          LithosDexSpecBase.otherOwnerNFT, bigger)))
    }
  }

  property("resize: rejects draining the provision box below CONST_PROVISION_MIN") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX, r.quote.nextEntryY, r.prov.ownerNFT,
          bigger, value = PROVISION_MIN - 1L)))
    }
  }

  property("resize: rejects skimming the provision box's rent buffer (identityHeld)") {
    withCtx { ctx =>
      // Above CONST_PROVISION_MIN throughout, so only the provision's own `value >= SELF.value` bites.
      val r = resizeScenario(ctx, oldShares, bigger, provValue = 2L * PROVISION_MIN)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = provisionUTXO(ctx, r.quote.nextEntryX, r.quote.nextEntryY, r.prov.ownerNFT,
          bigger, value = 2L * PROVISION_MIN - 1L)))
    }
  }

  property("resize: rejects a padded provision successor") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = r.provOut.setRegs(r.provOut.registers ++ Seq(padding(400)): _*)))
    }
  }

  property("resize: rejects a second token riding along in the provision box") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      val extra = funding(ctx, 5, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)))
      rejectsAtSigning(r.prover, resizeTx(r)(
        provOut = r.provOut.addToken(Token(LithosDexSpecBase.unrelatedToken, 1L)),
        inputs = Seq(r.poolIn, r.provIn, r.vaultIn, r.nftIn, r.fundingIn, extra)))
    }
  }

  property("resize: rejects the provision token being pocketed instead of staying put") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(provTokens = r.after.provTokens + 1L)),
        provOut = UTXO(userContract(ctx), PROVISION_MIN)
          .setRegs(bigIntValue(r.quote.nextEntryX), bigIntValue(r.quote.nextEntryY),
            bytesValue(r.prov.ownerNFT.getBytes), ErgoValue.of(bigger))))
    }
  }

  property("resize: rejects a provision escaping the guard through the redeem path") {
    withCtx { ctx =>
      // The pool pins provOut's tokens, value and registers. If it does not also pin its script, the
      // provision logic is the only thing left holding it — and that logic only constrains a successor
      // on its own resize path. Spending the provision under Redeem instead satisfies everything Redeem
      // asks (the pool NFT is at INPUTS(0), the owner holds the NFT, the NFT is burned) while leaving
      // this output unconstrained, which puts a provision token in an ordinary box. From there its
      // holder writes any size into R7 and the vault pays against it — see LDFeeVaultSpec's
      // "a loose provision token is a working claim on the vault".
      val r = resizeScenario(ctx, oldShares, smaller)
      val redeeming = provisionInput(ctx, r.prov.utxo, LDHelpers.PROV_REDEEM, 1)
      val escaped = provisionUTXO(ctx, r.quote.nextEntryX, r.quote.nextEntryY, r.prov.ownerNFT,
        smaller, contract = userContract(ctx))
      rejectsAtSigning(r.prover, resizeTx(r)(
        inputs = Seq(r.poolIn, redeeming, r.vaultIn, r.nftIn, r.fundingIn),
        outputs = Seq(r.poolOut, escaped, r.vaultOut),
        burn = Seq(Token(r.prov.ownerNFT, 1L))))
    }
  }

  property("resize: rejects a provision box under any script but the guard") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      val fake = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, oldShares,
        contract = userContract(ctx))
      rejectsAtSigning(r.prover, resizeTx(r)(
        inputs = Seq(r.poolIn, inputAt(fake, ctx, 1), r.vaultIn, r.nftIn, r.fundingIn)))
    }
  }

  property("resize: rejects a second provision box in the transaction") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      val smuggler = funding(ctx, 5, SLACK, Seq(Token(LithosDexSpecBase.provToken, 1L)))
      rejectsAtSigning(r.prover, resizeTx(r)(
        inputs = Seq(r.poolIn, r.provIn, r.vaultIn, r.nftIn, r.fundingIn, smuggler)))
    }
  }

  // ─── authorisation ────────────────────────────────────────────────────────

  property("resize: rejects a resize without the ownership NFT in the inputs (ownerPresent)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover,
        resizeTx(r)(inputs = Seq(r.poolIn, r.provIn, r.vaultIn, r.fundingIn)))
    }
  }

  property("resize: rejects a provision resizing next to a pool doing something else") {
    withCtx { ctx =>
      // LD_Provision op 3 only checks that INPUTS(0) holds the pool NFT, not what the pool is doing.
      // What closes that gap is the pool's own provision-token count on every other path.
      val s = swapScenario(ctx, state = traded)
      val prov = provisionOf(ctx, oldShares, BigInt(0), BigInt(0))
      val provIn = provisionInput(ctx, prov.utxo, LDHelpers.PROV_RESIZE, 1)
      rejectsAtSigning(s.prover,
        build(ctx, Seq(s.poolIn, provIn, inputAt(nftUTXO(ctx, prov.ownerNFT), ctx, 3), s.fundingIn),
          Seq(s.poolOut, prov.utxo), s.prover.getAddress, Seq.empty, Seq.empty))
    }
  }

  // ─── the flush half is not optional ───────────────────────────────────────

  property("resize: rejects leaving the pending fees in the pool") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(pendingX = traded.pendingX, pendingY = traded.pendingY)),
        vaultOut = vaultUTXO(ctx, r.vaultX, r.vaultY, traded.accX, traded.accY)))
    }
  }

  property("resize: rejects short-changing the vault on the flush half") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        vaultOut = vaultUTXO(ctx, r.vaultX + traded.pendingX - 1L, r.vaultY + traded.pendingY,
          traded.accX, traded.accY)))
    }
  }

  property("resize: rejects handing the vault an accumulator ahead of the money") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        vaultOut = vaultUTXO(ctx, r.vaultX + traded.pendingX, r.vaultY + traded.pendingY,
          traded.accX + 1, traded.accY)))
    }
  }

  property("resize: rejects leaving the vault's accumulator where it was") {
    withCtx { ctx =>
      // This is precisely the gap the same-transaction flush exists to close: settle the entry against
      // the pool's accumulator while the vault still holds an older one and the provision walks away
      // with a claim on fees the vault was never given.
      val r = resizeScenario(ctx, oldShares, smaller)
      rejectsAtSigning(r.prover, resizeTx(r)(
        vaultOut = vaultUTXO(ctx, r.vaultX + traded.pendingX, r.vaultY + traded.pendingY,
          BigInt(0), BigInt(0))))
    }
  }

  property("resize: rejects a counterfeit vault at INPUTS(2)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      val fake = inputAt(UTXO(userContract(ctx), VAULT_MIN,
        Seq(Token(LithosDexSpecBase.unrelatedToken, 1L))), ctx, 2)
      rejectsAtSigning(r.prover, resizeTx(r)(
        inputs = Seq(r.poolIn, r.provIn, fake, r.nftIn, r.fundingIn),
        vaultOut = UTXO(userContract(ctx), VAULT_MIN + traded.pendingX,
          Seq(Token(LithosDexSpecBase.unrelatedToken, 1L),
            Token(LithosDexSpecBase.tokenY, traded.pendingY)))))
    }
  }

  property("resize: rejects the accumulator moving during a resize (accHeld)") {
    withCtx { ctx =>
      val r = resizeScenario(ctx, oldShares, bigger)
      rejectsAtSigning(r.prover, resizeTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(accX = traded.accX + 1))))
    }
  }
}
