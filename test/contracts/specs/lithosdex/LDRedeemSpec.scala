package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_LiquidityPool op 2 and LD_Provision op 1 — redemptions.
 *
 * A redemption is the only path that takes liquidity back out, so it is where impermanent loss actually
 * lands: what comes back is a share of the reserves as they stand now, in whatever composition the
 * trading since has left them. The pool bounds it proportionally on both sides and the provision
 * contract supplies the authorisation and retires the ownership NFT.
 */
class LDRedeemSpec extends AnyPropSpec with LithosDexSpecBase {

  private val shares: Long = 10000000000L

  // ─── the proportional bound ───────────────────────────────────────────────

  property("redeem: accepts exactly the proportional share of both reserves (satisfiesAMM)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      r.quote.amountX shouldBe (BigInt(shares) * genesis.reservesX / genesis.supply).toLong
      accepts(r.prover, redeemTx(r)())
    }
  }

  property("redeem: rejects one nanoERG more than proportional (satisfiesAMM)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(reservesX = r.after.reservesX - 1L))))
    }
  }

  property("redeem: rejects one token more than proportional (satisfiesAMM)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(reservesY = r.after.reservesY - 1L))))
    }
  }

  property("redeem: accepts leaving liquidity behind: the pool never owes the exact amount") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      accepts(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(reservesX = r.after.reservesX + 1L))))
    }
  }

  // ─── impermanent loss ─────────────────────────────────────────────────────

  property("redeem: returns the post-trade composition, not what was deposited") {
    withCtx { ctx =>
      // Deposit at genesis, then let two ERG of buying move the price against the provider.
      val opened = genesis.copy(
        reservesX = genesis.reservesX + 100000000L,
        reservesY = genesis.reservesY + 100000000L,
        supply = genesis.supply + shares)
      val moved = afterSwap(opened, 2L * Parameters.OneErg, ergIn = true)
      val r = redeemScenario(ctx, shares, moved)

      // The classic shape: more of the side that was sold into the pool, less of the side bought out.
      r.quote.amountX should be > 100000000L
      r.quote.amountY should be < 100000000L
      accepts(r.prover, redeemTx(r)())
    }
  }

  property("redeem: rejects taking back the deposited amounts after the price has moved") {
    withCtx { ctx =>
      val opened = genesis.copy(
        reservesX = genesis.reservesX + 100000000L,
        reservesY = genesis.reservesY + 100000000L,
        supply = genesis.supply + shares)
      val moved = afterSwap(opened, 2L * Parameters.OneErg, ergIn = true)
      val r = redeemScenario(ctx, shares, moved)
      // Insisting on the original token side is exactly the impermanent loss the provider is trying to
      // avoid, and the pool has no notion of what was deposited to grant it.
      rejectsAtSigning(r.prover, redeemTx(r)(
        poolOut = poolUTXO(ctx, r.after.copy(reservesY = moved.reservesY - 100000000L))))
    }
  }

  // ─── the size that leaves ─────────────────────────────────────────────────

  property("redeem: rejects burning fewer shares than the provision records") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(supply = r.after.supply + 1L))))
    }
  }

  property("redeem: rejects burning more shares than the provision records") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(supply = r.after.supply - 1L))))
    }
  }

  property("redeem: accepts a redemption that leaves supply exactly at CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      val tight = genesis.copy(supply = MIN_SUPPLY + 1000L)
      val r = redeemScenario(ctx, 1000L, tight)
      r.after.supply shouldBe MIN_SUPPLY
      accepts(r.prover, redeemTx(r)())
    }
  }

  property("redeem: rejects a redemption that takes supply under CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      // Supply is the divisor every swap's accumulator advance uses. Letting the last redemption reach
      // zero would finish the pool, so the floor is what makes the whole scheme survivable.
      val tight = genesis.copy(supply = MIN_SUPPLY + 999L)
      val r = redeemScenario(ctx, 1000L, tight)
      r.after.supply shouldBe MIN_SUPPLY - 1L
      rejectsAtSigning(r.prover, redeemTx(r)())
    }
  }

  property("redeem: a share large enough to breach CONST_MIN_RENT cannot be closed") {
    withCtx { ctx =>
      // The rent floor sits under the whole box value, so it bounds withdrawal as well as storage: a
      // provider whose proportional share would take the box below it cannot exit in one step, and the
      // last CONST_MIN_RENT of the ERG side is unwithdrawable by anyone. The genesis convention covers
      // this — the locked share that no provision references should be worth more than the floor — but
      // it is a real constraint on how a pool is launched, not just on how it is stored.
      val shallow = genesis.copy(reservesX = MIN_RENT + 1000000000L, supply = 20000000000L)
      val r = redeemScenario(ctx, 19900000000L, shallow)
      r.poolOut.value should be < MIN_RENT
      rejectsAtSigning(r.prover, redeemTx(r)())

      // The same provider can still take out everything above the floor.
      val partial = redeemScenario(ctx, 3000000000L, shallow)
      partial.poolOut.value should be > MIN_RENT
      accepts(partial.prover, redeemTx(partial)())
    }
  }

  property("redeem: accepts a payout box carrying registers of its own") {
    withCtx { ctx =>
      // Composability. Redeem produces no provision successor, so it has no reason to care what is in
      // OUTPUTS(1) — that box is the redeemer's payout and may belong to another protocol. The R8 check
      // the other three paths use is scoped inside each of them for exactly this reason; hoisted to a
      // top-level val it would evaluate here too, and a typed R8 would fail the read and the redemption.
      val r = redeemScenario(ctx, shares)
      val regs = Seq(bigIntValue(BigInt(1)), bigIntValue(BigInt(2)),
        bytesValue(LithosDexSpecBase.ownerNFT.getBytes), ErgoValue.of(3L), ErgoValue.of(4L))
      accepts(r.prover, redeemTx(r)(payoutOut = r.payoutOut.setRegs(regs: _*)))

      // And with R8 as a Coll[Byte], the one type the check reads natively.
      val asColl = regs.updated(4, padding(64))
      accepts(r.prover, redeemTx(r)(payoutOut = r.payoutOut.setRegs(asColl: _*)))
    }
  }

  // ─── fees are not touched by a redemption ─────────────────────────────────

  property("redeem: rejects advancing the accumulator (accHeld)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares, traded)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(accX = traded.accX + 1))))
    }
  }

  property("redeem: rejects walking pending fees out with the liquidity (deltaPendingX)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares, traded)
      rejectsAtSigning(r.prover, redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(pendingX = 0L))))
    }
  }

  property("redeem: rejects sweeping pending fees into the redeemed reserves") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares, traded)
      rejectsAtSigning(r.prover, redeemTx(r)(poolOut = poolUTXO(ctx,
        r.after.copy(pendingY = 0L, reservesY = r.after.reservesY + traded.pendingY))))
    }
  }

  // ─── the provision token comes home ───────────────────────────────────────

  property("redeem: rejects the provision token escaping instead of returning to the pool") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover,
        redeemTx(r)(poolOut = poolUTXO(ctx, r.after.copy(provTokens = r.before.provTokens))))
    }
  }

  property("redeem: rejects a second provision token in the inputs (numTokenInInputs)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      val smuggler = funding(ctx, 4, SLACK, Seq(Token(LithosDexSpecBase.provToken, 1L)))
      rejectsAtSigning(r.prover,
        redeemTx(r)(inputs = Seq(r.poolIn, r.provIn, r.nftIn, r.fundingIn, smuggler),
          poolOut = poolUTXO(ctx, r.after.copy(provTokens = r.after.provTokens + 1L))))
    }
  }

  property("redeem: rejects a provision box under any script but the guard") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      val fake = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, shares,
        contract = userContract(ctx))
      rejectsAtSigning(r.prover, redeemTx(r)(inputs =
        Seq(r.poolIn, inputAt(fake, ctx, 1), r.nftIn, r.fundingIn)))
    }
  }

  property("redeem: rejects a counterfeit provision holding some other token") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      val fake = provisionUTXO(ctx, r.prov.entryX, r.prov.entryY, r.prov.ownerNFT, shares,
        provTokenId = LithosDexSpecBase.unrelatedToken)
      rejectsAtSigning(r.prover, redeemTx(r)(
        inputs = Seq(r.poolIn, provisionInput(ctx, fake, LDHelpers.PROV_REDEEM, 1), r.nftIn, r.fundingIn),
        poolOut = poolUTXO(ctx, r.after.copy(provTokens = r.before.provTokens))))
    }
  }

  // ─── authorisation, and retiring the NFT ──────────────────────────────────

  property("redeem: rejects a redemption without the ownership NFT in the inputs (ownerPresent)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      rejectsAtSigning(r.prover, redeemTx(r)(
        inputs = Seq(r.poolIn, r.provIn, r.fundingIn),
        payoutOut = UTXO(userContract(ctx), r.quote.amountX + r.prov.utxo.value - NFT_BOX_VALUE,
          Seq(Token(LithosDexSpecBase.tokenY, r.quote.amountY))),
        burn = Seq.empty[Token]))
    }
  }

  property("redeem: rejects keeping the ownership NFT alive (nftBurned)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      // Leaving the NFT in circulation leaves a bearer claim pointing at a provision that no longer
      // exists, which is the sort of thing that gets sold to somebody.
      val keep = UTXO(userContract(ctx), NFT_BOX_VALUE, Seq(Token(r.prov.ownerNFT, 1L)))
      rejectsAtSigning(r.prover, redeemTx(r)(
        outputs = Seq(r.poolOut, r.payoutOut.subValue(NFT_BOX_VALUE), keep),
        burn = Seq.empty[Token]))
    }
  }

  property("redeem: rejects a provision redeeming with no pool in the transaction (authenticPool)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      // Without the pool there is nothing to check that the liquidity leaving matches R7 — the whole
      // point of pinning INPUTS(0) to the pool NFT.
      val provIn = provisionInput(ctx, r.prov.utxo, LDHelpers.PROV_REDEEM, 0)
      rejectsAtSigning(r.prover,
        build(ctx, Seq(provIn, inputAt(nftUTXO(ctx, r.prov.ownerNFT), ctx, 2), funding(ctx, 3)),
          Seq(UTXO(userContract(ctx), PROVISION_MIN)), r.prover.getAddress,
          Seq.empty, Seq(Token(r.prov.ownerNFT, 1L))))
    }
  }

  property("redeem: rejects an unrelated box standing in for the pool at INPUTS(0)") {
    withCtx { ctx =>
      val r = redeemScenario(ctx, shares)
      val decoy = inputAt(UTXO(userContract(ctx), SLACK,
        Seq(Token(LithosDexSpecBase.unrelatedToken, 1L))), ctx, 5)
      val provIn = provisionInput(ctx, r.prov.utxo, LDHelpers.PROV_REDEEM, 1)
      rejectsAtSigning(r.prover,
        build(ctx, Seq(decoy, provIn, inputAt(nftUTXO(ctx, r.prov.ownerNFT), ctx, 2), funding(ctx, 3)),
          Seq(UTXO(userContract(ctx), PROVISION_MIN), UTXO(userContract(ctx), PROVISION_MIN)),
          r.prover.getAddress, Seq.empty, Seq(Token(r.prov.ownerNFT, 1L))))
    }
  }
}
