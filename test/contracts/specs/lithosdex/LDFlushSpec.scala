package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_LiquidityPool op 3 and LD_FeeVault op 1 — flushes.
 *
 * A flush is the only way an accumulator ever reaches the vault, and the vault pays against nothing
 * else. So the single condition the whole scheme rests on is that the accumulator handed over never
 * runs ahead of the money handed over with it: advance it further and the vault owes fees that were
 * never delivered, which the last provisions to claim would discover as an empty box.
 */
class LDFlushSpec extends AnyPropSpec with LithosDexSpecBase {

  // ─── what leaves the pool ─────────────────────────────────────────────────

  property("flush: moves exactly the pending fees and nothing else") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      f.poolOut.value shouldBe f.before.boxValue - f.before.pendingX
      f.after.reservesX shouldBe f.before.reservesX
      f.after.reservesY shouldBe f.before.reservesY
      accepts(f.prover, flushTx(f)())
    }
  }

  property("flush: rejects the pool holding back one nanoERG of the pending fees") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover,
        flushTx(f)(poolOut = poolUTXO(ctx, f.after.copy(reservesX = f.after.reservesX + 1L))))
    }
  }

  property("flush: rejects taking reserves out under cover of a flush") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        poolOut = poolUTXO(ctx, f.after.copy(reservesX = f.after.reservesX - Parameters.OneErg))))
    }
  }

  property("flush: rejects taking reserves out on the token side") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        poolOut = poolUTXO(ctx, f.after.copy(reservesY = f.after.reservesY - 1000000L))))
    }
  }

  property("flush: rejects leaving pending non-zero afterwards") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(poolOut = poolUTXO(ctx, f.after.copy(pendingX = 1L))))
    }
  }

  property("flush: rejects moving supply") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover,
        flushTx(f)(poolOut = poolUTXO(ctx, f.after.copy(supply = f.after.supply + 1L))))
    }
  }

  property("flush: rejects advancing the pool's accumulator (accHeld)") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover,
        flushTx(f)(poolOut = poolUTXO(ctx, f.after.copy(accX = f.before.accX + 1))))
    }
  }

  property("flush: rejects a provision token joining the inputs (provTokenHeld)") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val smuggler = funding(ctx, 3, SLACK, Seq(Token(LithosDexSpecBase.provToken, 1L)))
      rejectsAtSigning(f.prover,
        flushTx(f)(inputs = Seq(f.poolIn, f.vaultIn, f.fundingIn, smuggler)))
    }
  }

  // ─── what reaches the vault ───────────────────────────────────────────────

  property("flush: rejects short-changing the vault by one nanoERG") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX - 1L, f.vaultY + f.before.pendingY,
          f.before.accX, f.before.accY)))
    }
  }

  property("flush: rejects short-changing the vault on the token side") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX, f.vaultY + f.before.pendingY - 1L,
          f.before.accX, f.before.accY)))
    }
  }

  property("flush: accepts over-delivering to the vault") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      accepts(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX + Parameters.OneErg,
          f.vaultY + f.before.pendingY, f.before.accX, f.before.accY)))
    }
  }

  property("flush: rejects an accumulator handed over ahead of the money behind it") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      // One tick of accX is a claim on fees at every provision's size. Nothing downstream re-derives
      // it, so this is the one place the vault could be made insolvent.
      rejectsAtSigning(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX, f.vaultY + f.before.pendingY,
          f.before.accX + 1, f.before.accY)))
    }
  }

  property("flush: rejects an accumulator left behind the fees delivered") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX, f.vaultY + f.before.pendingY,
          f.before.accX - 1, f.before.accY)))
    }
  }

  property("flush: rejects the token accumulator being moved on its own") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(f.prover, flushTx(f)(
        vaultOut = vaultUTXO(ctx, f.vaultX + f.before.pendingX, f.vaultY + f.before.pendingY,
          f.before.accX, f.before.accY + 1)))
    }
  }

  property("flush: rejects flushing token fees into a vault entry of a different token") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val wrong = vaultUTXO(ctx, f.vaultX + f.before.pendingX, f.before.pendingY,
        f.before.accX, f.before.accY, tokenYId = LithosDexSpecBase.unrelatedToken)
      val supply = funding(ctx, 3, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, f.before.pendingY)))
      rejectsAtSigning(f.prover,
        flushTx(f)(vaultOut = wrong, inputs = Seq(f.poolIn, f.vaultIn, f.fundingIn, supply)))
    }
  }

  property("flush: accepts a padded vault successor") {
    withCtx { ctx =>
      // Deliberate, for the same reason as the pool: the vault is spent by every flush and every
      // claim, so it never reaches collection age, and the padding lasts one spend.
      val f = flushScenario(ctx)
      accepts(f.prover, flushTx(f)(
        vaultOut = f.vaultOut.setRegs(f.vaultOut.registers ++ Seq(padding(1000)): _*)))
    }
  }

  property("flush: rejects dropping the vault NFT") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val naked = UTXO(vaultContract(ctx), f.vaultX + f.before.pendingX,
        Seq(Token(LithosDexSpecBase.tokenY, f.vaultY + f.before.pendingY)),
        Seq(bigIntValue(f.before.accX), bigIntValue(f.before.accY)))
      rejectsAtSigning(f.prover, flushTx(f)(vaultOut = naked))
    }
  }

  property("flush: rejects a counterfeit vault at INPUTS(1)") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      // Under a P2PK so nothing but the pool's own CONST_VAULT_NFT check can reject it.
      val fake = inputAt(UTXO(userContract(ctx), VAULT_MIN,
        Seq(Token(LithosDexSpecBase.unrelatedToken, 1L))), ctx, 1)
      rejectsAtSigning(f.prover, flushTx(f)(
        inputs = Seq(f.poolIn, fake, f.fundingIn),
        vaultOut = UTXO(userContract(ctx), VAULT_MIN + f.before.pendingX,
          Seq(Token(LithosDexSpecBase.unrelatedToken, 1L),
            Token(LithosDexSpecBase.tokenY, f.before.pendingY)))))
    }
  }

  property("flush: rejects the vault successor at the wrong output index") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val decoy = UTXO(userContract(ctx), Parameters.MinFee)
      rejectsAtSigning(f.prover, flushTx(f)(outputs = Seq(f.poolOut, decoy, f.vaultOut)))
    }
  }

  // ─── griefing ─────────────────────────────────────────────────────────────

  property("flush: rejects a flush with nothing pending") {
    withCtx { ctx =>
      // Every swap, deposit and claim has to pass through one of these two boxes, so an op that moves
      // nothing but still spends them is a cheap way to keep invalidating everyone else's work.
      val f = flushScenario(ctx, genesis)
      f.before.pendingX shouldBe 0L
      f.before.pendingY shouldBe 0L
      rejectsAtSigning(f.prover, flushTx(f)())
    }
  }

  property("flush: is impossible once pendingX exceeds the value's headroom over CONST_MIN_RENT") {
    withCtx { ctx =>
      // A flush moves exactly pendingX out, so it leaves the ERG reserve behind as the whole box value.
      // CONST_MIN_RENT is a floor on that value, which makes it a floor on the reserve a flush can
      // leave — so fees freeze whenever pendingX grows past what the box holds above the floor. Not a
      // defect but an operational bound: it is why a genesis reserve wants to sit well clear of the
      // floor, and why it recovers on its own as ERG-side buying refills the reserve.
      val squeezed = genesis.copy(reservesX = MIN_RENT, pendingX = 1000000000L, pendingY = 1000000L)
      squeezed.boxValue should be > MIN_RENT
      val f = flushScenario(ctx, squeezed, VAULT_MIN, 0L)
      f.poolOut.value shouldBe squeezed.reservesX
      rejectsAtSigning(f.prover, flushTx(f)())

      // One nanoERG of headroom is the whole difference.
      val ok = squeezed.copy(reservesX = MIN_RENT + 1L)
      accepts(user(ctx), flushTx(flushScenario(ctx, ok, VAULT_MIN, 0L))())
    }
  }

  property("flush: accepts a one-sided flush when only the ERG side has accrued") {
    withCtx { ctx =>
      val f = flushScenario(ctx, afterSwap(genesis, Parameters.OneErg, ergIn = true))
      f.before.pendingY shouldBe 0L
      accepts(f.prover, flushTx(f)())
    }
  }

  // ─── the vault's own side ─────────────────────────────────────────────────

  property("vault: rejects being spent alongside an ordinary swap (poolOp)") {
    withCtx { ctx =>
      // The vault is unconstrained on any pool path but flush and resize, so without this check adding
      // it as an extra input to a swap would let it be emptied into change.
      val s = swapScenario(ctx)
      val vaultIn = vaultInput(ctx, vaultUTXO(ctx, 100L * Parameters.OneErg, 0L, BigInt(0), BigInt(0)),
        LDHelpers.VAULT_FLUSH, 1)
      val drained = vaultUTXO(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0))
      rejectsAtSigning(s.prover,
        build(ctx, Seq(s.poolIn, vaultIn, s.fundingIn), Seq(s.poolOut, drained),
          s.prover.getAddress, Seq.empty, Seq.empty))
    }
  }

  property("vault: rejects being spent alongside a deposit (poolOp)") {
    withCtx { ctx =>
      val d = depositScenario(ctx)
      val vaultIn = vaultInput(ctx, vaultUTXO(ctx, 100L * Parameters.OneErg, 0L, BigInt(0), BigInt(0)),
        LDHelpers.VAULT_FLUSH, 2)
      val drained = vaultUTXO(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0))
      rejectsAtSigning(d.prover, depositTx(d)(
        inputs = Seq(d.poolIn, d.fundingIn, vaultIn),
        outputs = Seq(d.poolOut, d.provOut, drained, d.nftOut)))
    }
  }

  property("vault: rejects a flush with no pool in the transaction (CONST_POOL_NFT)") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val vaultIn = vaultInput(ctx, vaultUTXO(ctx, 100L * Parameters.OneErg, 0L, BigInt(0), BigInt(0)),
        LDHelpers.VAULT_FLUSH, 0)
      rejectsAtSigning(f.prover,
        build(ctx, Seq(vaultIn, funding(ctx, 1)),
          Seq(vaultUTXO(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0))),
          f.prover.getAddress, Seq.empty, Seq.empty))
    }
  }

  property("vault: an unrecognised op byte fails closed") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      Seq[Byte](2, 3, 127, -1).foreach { op =>
        val vaultIn = vaultInput(ctx, vaultUTXO(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0)), op, 0)
        rejectsAtSigning(f.prover,
          build(ctx, Seq(vaultIn, funding(ctx, 1)),
            Seq(vaultUTXO(ctx, VAULT_MIN, 0L, BigInt(0), BigInt(0))),
            f.prover.getAddress, Seq.empty, Seq.empty))
      }
    }
  }
}
