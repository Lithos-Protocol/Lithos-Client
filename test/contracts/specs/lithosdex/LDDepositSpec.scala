package contracts.specs.lithosdex

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_LiquidityPool op 1 — deposits.
 *
 * A deposit is where a provision box is born, so almost everything here is about what the pool is
 * willing to write into one. The entry it records is the whole fee-per-share scheme: a provision that
 * could enter at a stale accumulator would be owed every fee earned before it arrived, paid out of
 * money the vault holds for everybody else.
 */
class LDDepositSpec extends AnyPropSpec with LithosDexSpecBase {

  private val shares: Long = 10000000000L

  /** The most shares a given payment actually buys, floored on each side exactly as the contract does. */
  private def unlocked(s: PoolState, amountX: Long, amountY: Long): Long = {
    val x = BigInt(amountX) * s.supply / s.reservesX
    val y = BigInt(amountY) * s.supply / s.reservesY
    (if (x <= y) x else y).toLong
  }

  // ─── what the payment buys ────────────────────────────────────────────────

  property("deposit: accepts exactly the shares the payment unlocks (sharesUnlocked)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      val max = unlocked(d.before, d.quote.amountX, d.quote.amountY)
      max should be >= shares
      accepts(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(supply = d.before.supply + max)),
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, max)))
    }
  }

  property("deposit: rejects one share more than the payment unlocks (sharesUnlocked)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      val max = unlocked(d.before, d.quote.amountX, d.quote.amountY)
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(supply = d.before.supply + max + 1L)),
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, max + 1L)))
    }
  }

  property("deposit: rejects underpaying the ERG side by one nanoERG") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover,
        depositTx(d)(poolOut = poolUTXO(ctx, d.after.copy(reservesX = d.after.reservesX - 1L))))
    }
  }

  property("deposit: rejects underpaying the token side by one unit") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover,
        depositTx(d)(poolOut = poolUTXO(ctx, d.after.copy(reservesY = d.after.reservesY - 1L))))
    }
  }

  property("deposit: rejects paying one side and withdrawing the other") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(reservesY = d.before.reservesY - 1000L * 1000000L))))
    }
  }

  property("deposit: rejects unlocking no shares at all (deltaSupplyLP > 0)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(supply = d.before.supply)),
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, 0L)))
    }
  }

  property("deposit: the proportional bound still holds on a pool drained to CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      // The ERG side has to stay clear of CONST_MIN_RENT; it is the share supply that is at its floor.
      val drained = genesis.copy(supply = MIN_SUPPLY, reservesX = MIN_RENT * 2L, reservesY = 1000000L)
      val d = depositScenario(ctx, 500000L, drained)
      val max = unlocked(drained, d.quote.amountX, d.quote.amountY)
      accepts(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(supply = drained.supply + max)),
        provOut = provisionUTXO(ctx, drained.accX, drained.accY, d.ownerNFT, max)))
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(supply = drained.supply + max + 1L)),
        provOut = provisionUTXO(ctx, drained.accX, drained.accY, d.ownerNFT, max + 1L)))
    }
  }

  // ─── the entry the provision is born with ─────────────────────────────────

  property("deposit: a provision entering a traded pool is owed nothing that accrued before it") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      traded.accX should be > BigInt(0)
      traded.accY should be > BigInt(0)
      owed(shares, traded.accX, traded.accX) shouldBe 0L
      owed(shares, traded.accY, traded.accY) shouldBe 0L
      accepts(d.prover, depositTx(d)())
    }
  }

  property("deposit: rejects an entry back-dated on the ERG side (provisionBox.R4)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      // Entering at zero would make this provision owed every fee the pool has ever earned, paid out of
      // the vault at every other provider's expense.
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, BigInt(0), traded.accY, d.ownerNFT, shares)))
    }
  }

  property("deposit: rejects an entry back-dated on the token side (provisionBox.R5)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, traded.accX, BigInt(0), d.ownerNFT, shares)))
    }
  }

  property("deposit: rejects an entry ahead of the pool's accumulator (provisionBox.R4)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, traded.accX + 1, traded.accY, d.ownerNFT, shares)))
    }
  }

  property("deposit: rejects a provision recording more shares than were unlocked (provisionBox.R7)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, shares * 2L)))
    }
  }

  property("deposit: rejects a provision owned by an NFT that already exists (provisionBox.R6)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, LithosDexSpecBase.ownerNFT, shares)))
    }
  }

  // ─── what a provision box has to be ───────────────────────────────────────

  property("deposit: rejects a provision box under any script but the guard") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, shares,
          contract = userContract(ctx))))
    }
  }

  property("deposit: rejects a provision box carrying two provision tokens") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(provTokens = d.after.provTokens - 1L)),
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, shares,
          provTokenAmount = 2L)))
    }
  }

  property("deposit: rejects a provision box carrying a second token (tokens.size)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      val extra = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)))
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = d.provOut.addToken(Token(LithosDexSpecBase.unrelatedToken, 1L)),
        inputs = Seq(d.poolIn, d.fundingIn, extra)))
    }
  }

  property("deposit: rejects a provision box under CONST_PROVISION_MIN") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = provisionUTXO(ctx, d.before.accX, d.before.accY, d.ownerNFT, shares,
          value = PROVISION_MIN - 1L)))
    }
  }

  property("deposit: rejects a padded provision box") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        provOut = d.provOut.setRegs(d.provOut.registers ++ Seq(padding(400)): _*)))
    }
  }

  property("deposit: rejects the ownership NFT box being passed off as the provision") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(outputs = Seq(d.poolOut, d.nftOut)))
    }
  }

  // ─── the ownership NFT ────────────────────────────────────────────────────

  property("deposit: rejects minting two ownership NFTs (numTokenInOutputs)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(
        nftOut = UTXO(userContract(ctx), NFT_BOX_VALUE, Seq(Token(d.ownerNFT, 2L)))))
    }
  }

  property("deposit: rejects minting no ownership NFT at all (numTokenInOutputs)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover, depositTx(d)(outputs = Seq(d.poolOut, d.provOut)))
    }
  }

  // ─── the provision token supply ───────────────────────────────────────────

  property("deposit: rejects letting a spare provision token escape with the depositor") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      // Two leave the pool, one lands in the provision and the other in change — which is a forged
      // provision box waiting to happen.
      rejectsAtSigning(d.prover, depositTx(d)(
        poolOut = poolUTXO(ctx, d.after.copy(provTokens = d.after.provTokens - 1L))))
    }
  }

  property("deposit: rejects an outside provision token joining the inputs (provTokenHeld)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      val smuggler = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.provToken, 1L)))
      rejectsAtSigning(d.prover, depositTx(d)(inputs = Seq(d.poolIn, d.fundingIn, smuggler)))
    }
  }

  // ─── nothing else may move ────────────────────────────────────────────────

  property("deposit: rejects advancing the accumulator (accHeld)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejectsAtSigning(d.prover,
        depositTx(d)(poolOut = poolUTXO(ctx, d.after.copy(accX = d.before.accX + 1))))
    }
  }

  property("deposit: rejects sweeping pending fees into the reserves (deltaPendingX)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      rejectsAtSigning(d.prover, depositTx(d)(poolOut = poolUTXO(ctx,
        d.after.copy(pendingX = 0L, reservesX = d.after.reservesX + traded.pendingX))))
    }
  }

  property("deposit: rejects walking pending fees out of the pool") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares, traded)
      rejectsAtSigning(d.prover, depositTx(d)(poolOut = poolUTXO(ctx, d.after.copy(pendingY = 0L))))
    }
  }

  /**
   * `rejects` rather than `rejectsAtSigning` on purpose: this one genuinely cannot produce a buildable
   * transaction, and that is the property. Ergo only lets a new token take the id of INPUTS(0), and the
   * pool demands the ownership NFT carry its own box id — so moving the pool off INPUTS(0) leaves the
   * NFT unmintable before the contract is ever consulted.
   */
  property("deposit: cannot even be built with the pool off INPUTS(0)") {
    withCtx { ctx =>
      val d = depositScenario(ctx, shares)
      rejects(d.prover, depositTx(d)(inputs = Seq(d.fundingIn, d.poolIn)))
    }
  }
}
