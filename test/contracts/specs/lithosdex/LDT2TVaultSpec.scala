package contracts.specs.lithosdex

import lithosdex.LDHelpers
import lithosdex.states.{LDT2TFeeValue, LDT2TLiquidityState}
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.Token

/**
 * LD_T2T_FeeVault, and the two pool paths that hand it fees: flush (op 3) and resize (op 4).
 *
 * The vault keeps both tokens at fixed indices from genesis, so the properties that matter beyond the
 * ERG/token vault's are that fees land in the right entry, that neither entry can be dropped or swapped,
 * and that its ERG, which is only storage here, can never be paid out.
 */
class LDT2TVaultSpec extends AnyPropSpec with LDT2TSpecBase {

  import LDT2TSpecBase._

  private val POOL = 0
  private val VAULT_OUT = 1

  // ─── flush ────────────────────────────────────────────────────────────────

  property("flush: moves exactly the pending fees of both tokens into their own entries") {
    withCtx { ctx =>
      traded.pendingX should be > 0L
      traded.pendingY should be > 0L
      accepts(user(ctx), flushScenario(ctx).tx)
    }
  }

  property("flush: rejects short-changing the vault by one unit of X") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(VAULT_OUT,
        vaultUTXO(SEED + traded.pendingX - 1L, SEED + traded.pendingY, traded.accX, traded.accY)).tx)
    }
  }

  property("flush: rejects short-changing the vault by one unit of Y") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(VAULT_OUT,
        vaultUTXO(SEED + traded.pendingX, SEED + traded.pendingY - 1L, traded.accX, traded.accY)).tx)
    }
  }

  property("flush: rejects X fees delivered into the vault's Y entry and the reverse") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val crossed = vaultUTXO(SEED + traded.pendingX, SEED + traded.pendingY, traded.accX, traded.accY,
        tokenXId = tokenY, tokenYId = tokenX)
      // The crossed amounts need tokens the pool did not release, so the funding brings them
      val topUp = funding(ctx, 2, Seq(Token(tokenX, traded.pendingY), Token(tokenY, traded.pendingX)))
      rejectsAtSigning(user(ctx), f.input(2, topUp).output(VAULT_OUT, crossed).tx)
    }
  }

  property("flush: rejects taking the vault's storage ERG") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(VAULT_OUT, f.outputs(VAULT_OUT).subValue(1L)).tx)
    }
  }

  property("flush: rejects taking the pool's storage ERG") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(POOL, f.outputs(POOL).subValue(1L)).tx)
    }
  }

  property("flush: rejects a vault successor padded with an R6 (noExtraRegisters)") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(VAULT_OUT,
        f.outputs(VAULT_OUT).withReg(2, bytesValue(Array.fill(64)(7.toByte)))).tx)
    }
  }

  property("flush: rejects an accumulator handed over ahead of the fees behind it") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      rejectsAtSigning(user(ctx), f.output(VAULT_OUT,
        vaultUTXO(SEED + traded.pendingX, SEED + traded.pendingY, traded.accX + 1, traded.accY)).tx)
    }
  }

  property("flush: rejects taking reserves out under cover of a flush") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val drained = traded.copy(pendingX = 0L, pendingY = 0L, reservesX = traded.reservesX - 1L)
      rejectsAtSigning(user(ctx), f.output(POOL, poolUTXO(drained)).tx)
    }
  }

  property("flush: rejects a flush with nothing pending") {
    withCtx { ctx =>
      val idle = traded.copy(pendingX = 0L, pendingY = 0L)
      rejectsAtSigning(user(ctx), flushScenario(ctx, state = idle).tx)
    }
  }

  property("flush: accepts a one-sided flush when only token Y has accrued") {
    withCtx { ctx =>
      val (_, oneSided, _) = swapScenario(ctx, xIn = false, state = genesis)
      oneSided.pendingX shouldBe 0L
      accepts(user(ctx), flushScenario(ctx, state = oneSided).tx)
    }
  }

  property("vault: rejects being spent alongside an ordinary swap (poolOp)") {
    withCtx { ctx =>
      // Only flush and resize pin the vault's successor, so on any other pool path it could be emptied
      val (s, _, _) = swapScenario(ctx)
      val full = vaultUTXO(SEED + 1000000L, SEED + 1000000L, BigInt(0), BigInt(0))
      val drained = vaultUTXO(SEED, SEED, BigInt(0), BigInt(0))
      rejectsAtSigning(user(ctx), s.copy(
        inputs = Seq(s.inputs.head, vaultInput(ctx, full, LDHelpers.VAULT_FLUSH, 1), s.inputs(1)),
        outputs = s.outputs :+ drained).tx)
    }
  }

  property("vault: rejects a flush with no pool in the transaction (CONST_POOL_NFT)") {
    withCtx { ctx =>
      val full = vaultUTXO(SEED + 1000000L, SEED + 1000000L, BigInt(0), BigInt(0))
      rejectsAtSigning(user(ctx), build(ctx,
        Seq(vaultInput(ctx, full, LDHelpers.VAULT_FLUSH, 0), funding(ctx, 1)),
        Seq(vaultUTXO(SEED, SEED, BigInt(0), BigInt(0))), user(ctx).getAddress))
    }
  }

  property("vault: an unrecognised op byte fails closed") {
    withCtx { ctx =>
      Seq[Byte](2, 3, 127, -1).foreach { op =>
        val v = vaultUTXO(SEED, SEED, BigInt(0), BigInt(0))
        rejectsAtSigning(user(ctx), build(ctx, Seq(vaultInput(ctx, v, op, 0), funding(ctx, 1)),
          Seq(v), user(ctx).getAddress))
      }
    }
  }

  // ─── resize ───────────────────────────────────────────────────────────────

  property("resize: a grow and a shrink are both accepted") {
    withCtx { ctx =>
      val (grow, _) = resizeScenario(ctx)
      accepts(user(ctx), grow.tx)
      val (shrink, _) = resizeScenario(ctx, newShares = 4000000000L)
      accepts(user(ctx), shrink.tx)
    }
  }

  property("resize: rejects short-changing the vault on the flush half") {
    withCtx { ctx =>
      val (r, _) = resizeScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(2,
        vaultUTXO(SEED + traded.pendingX, SEED + traded.pendingY - 1L, traded.accX, traded.accY)).tx)
    }
  }

  property("resize: rejects taking more X than proportional out of a shrink (resizeAMM)") {
    withCtx { ctx =>
      val (r, q) = resizeScenario(ctx, newShares = 4000000000L)
      val next = traded.copy(reservesX = traded.reservesX - q.amountX - 1L, reservesY = traded.reservesY - q.amountY,
        pendingX = 0L, pendingY = 0L, supply = traded.supply + q.delta)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next)).tx)
    }
  }

  property("resize: rejects a provision escaping the guard (provOut script)") {
    withCtx { ctx =>
      val (r, _) = resizeScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(1, r.outputs(1).setContract(userContract(ctx))).tx)
    }
  }

  // ─── claim ────────────────────────────────────────────────────────────────

  property("claim: pays exactly size times the distance the accumulator has moved, in both tokens") {
    withCtx { ctx =>
      val (c, owedX, owedY) = claimScenario(ctx)
      owedX should be > 0L
      owedY should be > 0L
      accepts(user(ctx), c.tx)
    }
  }

  property("claim: rejects releasing one unit of X more than was owed") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      val v = c.outputs.head
      val short = v.setTokens(v.tokens.head, v.tokens(1) - 1L, v.tokens(2))
      rejectsAtSigning(user(ctx), c.output(0, short).tx)
    }
  }

  property("claim: rejects releasing one unit of Y more than was owed") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      val v = c.outputs.head
      val short = v.setTokens(v.tokens.head, v.tokens(1), v.tokens(2) - 1L)
      rejectsAtSigning(user(ctx), c.output(0, short).tx)
    }
  }

  property("claim: rejects paying out the vault's storage ERG") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      rejectsAtSigning(user(ctx), c.output(0, c.outputs.head.subValue(1L)).tx)
    }
  }

  property("claim: rejects dropping a token entry from the vault") {
    withCtx { ctx =>
      // Taking a whole balance removes its entry, which the layout refuses, so the seed stays put
      val (c, _, _) = claimScenario(ctx)
      val v = c.outputs.head
      rejectsAtSigning(user(ctx), c.output(0, v.setTokens(v.tokens.head, v.tokens(1))).tx)
    }
  }

  property("claim: rejects a fourth token in the vault box (tokens.size)") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      rejectsAtSigning(user(ctx), c.copy(inputs = c.inputs :+ funding(ctx, 31, Seq(Token(unrelatedToken, 1L))))
        .output(0, c.outputs.head.addToken(Token(unrelatedToken, 1L))).tx)
    }
  }

  property("claim: rejects swapping the vault's two token entries") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      val v = c.outputs.head
      rejectsAtSigning(user(ctx), c.output(0, v.setTokens(v.tokens.head, v.tokens(2), v.tokens(1))).tx)
    }
  }

  property("claim: rejects a vault successor padded with an R6 (noExtraRegisters)") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      rejectsAtSigning(user(ctx), c.output(0, c.outputs.head.withReg(2, bytesValue(Array.fill(64)(7.toByte)))).tx)
    }
  }

  property("claim: settles two provisions of different sizes for their own amounts") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx, Seq(
        provisionOf(10000000000L),
        provisionOf(3000000000L, entryX = traded.accX / 2, entryY = traded.accY / 3, ownerId = otherOwnerNFT)))
      accepts(user(ctx), c.tx)
    }
  }

  property("claim: rejects a provision whose entry is ahead of the vault's accumulator") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx, Seq(provisionOf(10000000000L, entryX = traded.accX + 1)))
      rejectsAtSigning(user(ctx), c.tx)
    }
  }

  property("claim: rejects settling a provision owed nothing") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx, Seq(provisionOf(10000000000L, traded.accX, traded.accY)))
      rejectsAtSigning(user(ctx), c.tx)
    }
  }

  property("claim: rejects leaving the entry short so the same stretch can be claimed again") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      rejectsAtSigning(user(ctx), c.output(1,
        provisionUTXO(traded.accX - 1, traded.accY, ownerNFT, 10000000000L)).tx)
    }
  }

  property("claim: rejects the vault anywhere but INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      val reordered = c.inputs.last +: c.inputs.init
      rejectsAtSigning(user(ctx), c.copy(inputs = reordered).tx)
    }
  }

  // ─── the client's view ────────────────────────────────────────────────────

  property("client: LDT2TFeeValue owes exactly what the vault pays out") {
    withCtx { ctx =>
      val (c, owedX, owedY) = claimScenario(ctx)
      val vault = LDT2TFeeValue.fromBox(c.inputs.head, 0, 1)
      vault.owed(10000000000L, BigInt(0), BigInt(0)) shouldBe ((owedX, owedY))
      vault.canClaim(BigInt(0), BigInt(0)) shouldBe true
      vault.canClaim(traded.accX, traded.accY) shouldBe false
      vault.payableX shouldBe vault.balanceX - SEED
      accepts(user(ctx), c.tx)
    }
  }

  property("client: afterFlush is the vault a flush produces") {
    withCtx { ctx =>
      val f = flushScenario(ctx)
      val before = LDT2TFeeValue.fromBox(f.inputs(1), 0, 1)
      val pool = LDT2TLiquidityState.fromBox(f.inputs.head, 0, 1)
      val expected = LDT2TFeeValue.fromBox(inputAt(f.outputs(VAULT_OUT), ctx, 1), 0, 2)
      LDT2TFeeValue.afterFlush(before, pool, 2).copy(utxoId = expected.utxoId) shouldEqual expected
      accepts(user(ctx), f.tx)
    }
  }

  property("claim: rejects a box holding the ERG/token deployment's provision token instead") {
    withCtx { ctx =>
      val (c, _, _) = claimScenario(ctx)
      val foreign = provisionUTXO(BigInt(0), BigInt(0), ownerNFT, 10000000000L)
        .setTokens(Token(LithosDexSpecBase.provToken, 1L))
      rejectsAtSigning(user(ctx), c.input(1, provisionInput(ctx, foreign, LDHelpers.PROV_CLAIM, 1))
        .output(1, provisionUTXO(traded.accX, traded.accY, ownerNFT, 10000000000L)
          .setTokens(Token(LithosDexSpecBase.provToken, 1L))).tx)
    }
  }
}
