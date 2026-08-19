package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_LiquidityPool op 0 — swaps.
 *
 * A swap is the only path that moves an accumulator, and the only one that puts money into `pending`.
 * Both are pinned to the nanoERG, so the properties here split three ways: the constant-product bound
 * (slippage), the split between reserves and pending (whether providers are paid at all), and the
 * accumulator advance (whether the vault is later asked for money it was never given).
 */
class LDSwapSpec extends AnyPropSpec with LithosDexSpecBase {

  /** A pool thin enough on the ERG side that CONST_MIN_RENT is actually reachable through the curve. */
  private val thin = genesis.copy(reservesX = MIN_RENT + 2000000L)

  // ─── the constant-product bound ───────────────────────────────────────────

  property("swap: accepts exactly the output the curve allows (satisfiesAMM, ERG in)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      // simSwap floors the same bound the contract tests, so this is the largest legal output.
      s.after.reservesY shouldBe s.before.reservesY - s.quote.amountOut
      accepts(s.prover, swapTx(s)())
    }
  }

  property("swap: rejects one nanotoken more than the curve allows (satisfiesAMM, ERG in)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(reservesY = s.after.reservesY - 1L))))
    }
  }

  property("swap: rejects one nanoERG more than the curve allows (satisfiesAMM, token in)") {
    withCtx { ctx =>
      val s = swapScenario(ctx, amountIn = 2000L * 1000000L, ergIn = false)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(reservesX = s.after.reservesX - 1L))))
    }
  }

  property("swap: accepts less than the curve allows — slippage protection is the taker's problem") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      accepts(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(reservesY = s.after.reservesY + 1L))))
    }
  }

  property("swap: rejects taking ERG out while putting nothing in") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val theft = genesis.copy(reservesX = genesis.reservesX - Parameters.OneErg)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, theft), inputs = Seq(s.poolIn, funding(ctx, 1))))
    }
  }

  property("swap: rejects taking both sides out at once") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val theft = genesis.copy(
        reservesX = genesis.reservesX - Parameters.OneErg,
        reservesY = genesis.reservesY - 1000L * 1000000L)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, theft), inputs = Seq(s.poolIn, funding(ctx, 1))))
    }
  }

  property("swap: a long run of swaps with no flush is accepted at every step") {
    withCtx { ctx =>
      // `reservesHeld` bounds a balance against what the box already owes, not against what it has
      // flushed, so nothing about it makes a flush a precondition for trading. Pending and reserves
      // move independently: an input adds its fee to pending and the remainder to the reserve, so
      // pending growing never consumes the reserve it is held apart from.
      val run = (1 to 24).foldLeft(genesis) { (state, i) =>
        val ergIn = i % 2 == 1
        val amount = if (ergIn) Parameters.OneErg / 2 else 1000L * 1000000L
        val s = swapScenario(ctx, amount, ergIn, state)
        accepts(s.prover, swapTx(s)())
        s.after
      }
      run.pendingX should be > genesis.pendingX
      run.pendingY should be > genesis.pendingY
      run.reservesX should be > 0L
      run.reservesY should be > 0L
      println(f"[noflush] after 24 unflushed swaps: pendingX ${run.pendingX}%d of reservesX " +
        f"${run.reservesX}%d, pendingY ${run.pendingY}%d of reservesY ${run.reservesY}%d")
    }
  }

  property("swap: still accepted when pending has grown past the reserves it sits beside") {
    withCtx { ctx =>
      // The condition is `balance > owed`, not `owed < reserve`, so a pool carrying far more pending
      // than reserves still trades normally. Only a swap that would leave the box owing more than it
      // holds is refused — which is what the drain below needs and an ordinary swap never does.
      val lopsided = genesis.copy(pendingX = genesis.reservesX * 3L, pendingY = genesis.reservesY * 3L)
      lopsided.pendingY should be > lopsided.reservesY
      accepts(user(ctx), swapTx(swapScenario(ctx, Parameters.OneErg, ergIn = true, lopsided))())
      accepts(user(ctx), swapTx(swapScenario(ctx, 2000L * 1000000L, ergIn = false, lopsided))())
    }
  }

  property("swap: no both-sides-out swap exists while pending stays a fraction of the reserves") {
    // The normal operating regime. Anyone may flush for a MinFee, and every provider is paid for it,
    // so this is where a live pool sits.
    val normal = genesis.copy(pendingX = genesis.reservesX / 100, pendingY = genesis.reservesY / 100)
    bothSidesOut(normal) shouldBe None
  }

  property("swap: rejects a swap that drains both sides once pending has grown past the reserves") {
    withCtx { ctx =>
      // Regression test for a real defect. Before `reservesHeld`, nothing on this path required the
      // reserves to stay positive or the two deltas to have opposite signs, so once pending exceeded
      // reserves the constant-product inequality became an ordering between two negative products and
      // admitted a swap taking ERG and tokens out together, for nothing in — 19.14 ERG and 21,000
      // tokens out of a pool holding 22 ERG and 21,000 tokens.
      val drained = bothSidesOut(pendingHeavy).getOrElse(fail("the construction should exist here"))

      drained.reservesY should be < 0L
      (pendingHeavy.boxValue - drained.boxValue) should be > pendingHeavy.reservesX
      val poolIn = poolInput(ctx, poolUTXO(ctx, pendingHeavy), LDHelpers.POOL_SWAP)
      rejectsAtSigning(user(ctx),
        build(ctx, Seq(poolIn, funding(ctx, 1)), Seq(poolUTXO(ctx, drained)),
          user(ctx).getAddress, Seq.empty, Seq.empty))
    }
  }

  property("swap: rejects reducing the fees already promised to the vault") {
    withCtx { ctx =>
      // The same defect stated on its own: `feeYAdded` pins deltaPendingY to a product of totalDeltaY,
      // which is negative whenever tokens leave through the else branch. Pending is money the vault has
      // already been promised, and no swap may take it back.
      val drained = bothSidesOut(pendingHeavy).getOrElse(fail("the construction should exist here"))

      drained.pendingY should be < pendingHeavy.pendingY
      drained.accY should be < pendingHeavy.accY
      val poolIn = poolInput(ctx, poolUTXO(ctx, pendingHeavy), LDHelpers.POOL_SWAP)
      rejectsAtSigning(user(ctx),
        build(ctx, Seq(poolIn, funding(ctx, 1)), Seq(poolUTXO(ctx, drained)),
          user(ctx).getAddress, Seq.empty, Seq.empty))
    }
  }

  property("swap: rejects a successor claiming more pending than the box holds (reservesHeld)") {
    withCtx { ctx =>
      // The condition stated directly rather than through a constructed drain: the successor would owe
      // the vault more ERG than it has, which is what a negative reserve means.
      val s = swapScenario(ctx, Parameters.OneErg, ergIn = true, traded)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx,
        s.after.copy(pendingX = s.after.boxValue + 1L, reservesX = -1L))))
    }
  }

  property("swap: accepts a donation — tokens in, nothing out") {
    withCtx { ctx =>
      val s = swapScenario(ctx, amountIn = 2000L * 1000000L, ergIn = false)
      val donated = s.after.copy(reservesX = s.before.reservesX)
      accepts(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, donated)))
    }
  }

  // ─── the fee split: reserves versus pending ───────────────────────────────

  property("swap: rejects keeping the protocol fee in the reserves (feeXAdded)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      // The fee lands in reserves instead of pending, and the accumulator moves by nothing — which is
      // consistent, so `feeXAdded` is the only conjunct left to reject it.
      val kept = s.after.copy(
        pendingX = s.before.pendingX,
        reservesX = s.after.reservesX + s.quote.protocolFee,
        accX = s.before.accX)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, kept)))
    }
  }

  property("swap: rejects a protocol fee one nanoERG short (feeXAdded)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val short = s.after.copy(
        pendingX = s.after.pendingX - 1L,
        reservesX = s.after.reservesX + 1L,
        accX = s.before.accX + (BigInt(s.quote.protocolFee - 1L) * SCALE) / BigInt(s.before.supply))
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, short)))
    }
  }

  property("swap: rejects a protocol fee one nanoERG over (feeXAdded)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val over = s.after.copy(
        pendingX = s.after.pendingX + 1L,
        reservesX = s.after.reservesX - 1L,
        accX = s.before.accX + (BigInt(s.quote.protocolFee + 1L) * SCALE) / BigInt(s.before.supply))
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, over)))
    }
  }

  property("swap: rejects draining pending on the token side during an ERG-in swap (deltaPendingY)") {
    withCtx { ctx =>
      val s = swapScenario(ctx, state = traded)
      // The pending token fees are moved into the reserves, where the AMM can trade them away.
      val raided = s.after.copy(pendingY = 0L, reservesY = s.after.reservesY + traded.pendingY)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, raided)))
    }
  }

  property("swap: rejects draining pending on the ERG side during a token-in swap (deltaPendingX)") {
    withCtx { ctx =>
      val s = swapScenario(ctx, amountIn = 2000L * 1000000L, ergIn = false, state = traded)
      val raided = s.after.copy(pendingX = 0L, reservesX = s.after.reservesX + traded.pendingX)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, raided)))
    }
  }

  property("swap: rejects walking pending out of the box entirely") {
    withCtx { ctx =>
      val s = swapScenario(ctx, state = traded)
      // Reserves untouched, accumulator advanced correctly, but the pending ERG simply leaves.
      val stolen = s.after.copy(pendingX = 0L)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, stolen)))
    }
  }

  // ─── the accumulator ──────────────────────────────────────────────────────

  property("swap: advances accX by exactly the fee over the live supply (accXAdvanced)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      s.after.accX shouldBe (BigInt(s.quote.protocolFee) * SCALE) / BigInt(s.before.supply)
      accepts(s.prover, swapTx(s)())
    }
  }

  property("swap: rejects a fee taken with no accumulator advance (accXAdvanced)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(accX = s.before.accX))))
    }
  }

  property("swap: rejects an accumulator advanced past the fee that funded it (accXAdvanced)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(accX = s.after.accX + 1))))
    }
  }

  property("swap: rejects moving the token accumulator on an ERG-in swap (accYAdvanced)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(accY = s.after.accY + 1))))
    }
  }

  property("swap: the accumulator truncates down, and the truncated value is the only one accepted") {
    withCtx { ctx =>
      // A supply that does not divide the fee, so the advance genuinely loses a remainder.
      val awkward = genesis.copy(supply = 999999999937L)
      val s = swapScenario(ctx, state = awkward)
      val exact = (BigInt(s.quote.protocolFee) * SCALE) / BigInt(awkward.supply)
      (BigInt(s.quote.protocolFee) * SCALE) % BigInt(awkward.supply) should not be BigInt(0)
      s.after.accX shouldBe exact
      accepts(s.prover, swapTx(s)())
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(accX = exact + 1))))
    }
  }

  // ─── supply and the provision token ───────────────────────────────────────

  property("swap: rejects minting LP shares (deltaSupplyLP)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(supply = s.after.supply + 1000000L))))
    }
  }

  property("swap: rejects burning LP shares (deltaSupplyLP)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(supply = s.after.supply - 1000000L))))
    }
  }

  property("swap: rejects letting a provision token escape the pool") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(provTokens = s.after.provTokens - 1L))))
    }
  }

  property("swap: rejects an outside provision token joining the inputs (provTokenHeld)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      // Carried by a plain P2PK so nothing but the pool's own count can reject this. One loose provision
      // token is enough to forge a provision box, so the pool refuses to be spent alongside one.
      val smuggler = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.provToken, 1L)))
      rejectsAtSigning(s.prover, swapTx(s)(inputs = Seq(s.poolIn, s.fundingIn, smuggler)))
    }
  }

  // ─── griefing and pool identity ───────────────────────────────────────────

  property("swap: rejects a swap that moves nothing") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, genesis), inputs = Seq(s.poolIn, funding(ctx, 1))))
    }
  }

  property("swap: rejects draining the pool below CONST_MIN_RENT") {
    withCtx { ctx =>
      val q = thin.view.simSwapForOutput(thin.reservesX - MIN_RENT + 1000000L, ergIn = false).get
      val s = swapScenario(ctx, amountIn = q.amountIn, ergIn = false, state = thin)
      s.after.boxValue should be < MIN_RENT
      rejectsAtSigning(s.prover, swapTx(s)())
    }
  }

  property("swap: accepts a swap that leaves the pool just above CONST_MIN_RENT") {
    withCtx { ctx =>
      val q = thin.view.simSwapForOutput(thin.reservesX - MIN_RENT - 1000L, ergIn = false).get
      val s = swapScenario(ctx, amountIn = q.amountIn, ergIn = false, state = thin)
      s.after.boxValue should be > MIN_RENT
      accepts(s.prover, swapTx(s)())
    }
  }

  property("swap: rejects a successor whose pending collection has been padded") {
    withCtx { ctx =>
      // Nothing above index 1 is ever read, so without a length check the successor could carry an
      // arbitrarily long Coll[Long] here — bytes that raise the box's storage fee and the cost of
      // every later spend, at no cost to whoever added them.
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = s.poolOut.withReg(2,
        longsValue(Array(s.after.pendingX, s.after.pendingY) ++ Array.fill(500)(0L)))))
    }
  }

  property("swap: accepts a successor carrying a register beyond R8") {
    withCtx { ctx =>
      // Deliberate. Padding the pool is transient — it survives exactly one spend and the next
      // spender drops it for free — and the pool is spent far too often for a storage period to
      // elapse, so a length bound here could only ever reject an honest swap.
      val s = swapScenario(ctx)
      accepts(s.prover, swapTx(s)(
        poolOut = s.poolOut.setRegs(s.poolOut.registers ++ Seq(padding(1000)): _*)))
    }
  }

  property("swap: rejects rewriting the fee schedule (nextFeeParams)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after.copy(feeParams = Array(9985L, 0L, 0L)))))
    }
  }

  property("swap: rejects sending the pool to another script") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after, contract = userContract(ctx))))
    }
  }

  property("swap: rejects swapping the pool NFT for one minted in the same transaction") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = poolUTXO(ctx, s.after, poolNFTId = s.poolIn.id),
          burn = Seq(Token(LithosDexSpecBase.poolNFT, 1L))))
    }
  }

  property("swap: rejects re-ordering the token list") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val out = s.poolOut
      val shuffled = out.setTokens(out.tokens.head, out.tokens(2), out.tokens(1))
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = shuffled))
    }
  }

  property("swap: rejects replacing the traded token with another (tokenY1)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val swapped = poolUTXO(ctx, s.after, tokenYId = LithosDexSpecBase.unrelatedToken)
      val supply = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, s.after.tokenBalance)))
      rejectsAtSigning(s.prover,
        swapTx(s)(poolOut = swapped, inputs = Seq(s.poolIn, s.fundingIn, supply),
          burn = Seq(Token(LithosDexSpecBase.tokenY, s.after.tokenBalance))))
    }
  }

  property("swap: rejects a fourth token in the pool box (tokens.size)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val fat = s.poolOut.addToken(Token(LithosDexSpecBase.unrelatedToken, 1L))
      val supply = funding(ctx, 2, SLACK, Seq(Token(LithosDexSpecBase.unrelatedToken, 1L)))
      rejectsAtSigning(s.prover, swapTx(s)(poolOut = fat, inputs = Seq(s.poolIn, s.fundingIn, supply)))
    }
  }

  property("swap: rejects the pool anywhere but INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      rejectsAtSigning(s.prover, swapTx(s)(inputs = Seq(s.fundingIn, s.poolIn)))
    }
  }

  property("swap: rejects the successor anywhere but OUTPUTS(0)") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val decoy = UTXO(userContract(ctx), Parameters.MinFee)
      rejectsAtSigning(s.prover,
        swapTx(s)(outputs = Seq(decoy, s.poolOut), inputs = Seq(s.poolIn, s.fundingIn)))
    }
  }

  property("pool: an unrecognised op byte fails closed") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      val poolIn = poolInput(ctx, poolUTXO(ctx, genesis), 9.toByte)
      rejectsAtSigning(s.prover,
        build(ctx, Seq(poolIn, funding(ctx, 1)), Seq(poolUTXO(ctx, genesis)),
          s.prover.getAddress, Seq.empty, Seq.empty))
    }
  }

  property("pool: every op byte the contract names is one of the five documented paths") {
    withCtx { ctx =>
      val s = swapScenario(ctx)
      Seq(LDHelpers.POOL_SWAP, LDHelpers.POOL_DEPOSIT, LDHelpers.POOL_REDEEM,
        LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE) shouldBe Seq[Byte](0, 1, 2, 3, 4)
      Seq[Byte](5, 6, 127, -1).foreach { op =>
        val poolIn = poolInput(ctx, poolUTXO(ctx, genesis), op)
        rejectsAtSigning(s.prover,
          build(ctx, Seq(poolIn, funding(ctx, 1)), Seq(poolUTXO(ctx, genesis)),
            s.prover.getAddress, Seq.empty, Seq.empty))
      }
    }
  }
}
