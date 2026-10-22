package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/**
 * LD_T2T_LiquidityPool ops 0–2: swaps, deposits and redemptions.
 *
 * The arithmetic is LD_LiquidityPool's, so these concentrate on what changed: both sides are tokens, the
 * pool's ERG is storage that may grow but never shrink, and there are four token entries to keep in place.
 * Every boundary uses `LDT2TLiquidityPool`, so each one also checks the client's quoting math.
 */
class LDT2TPoolSpec extends AnyPropSpec with LDT2TSpecBase {

  import LDT2TSpecBase._

  private val POOL = 0

  // ─── swaps ────────────────────────────────────────────────────────────────

  property("swap: accepts exactly the output the curve allows, X in") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx, xIn = true)
      accepts(user(ctx), s.tx)
    }
  }

  property("swap: accepts exactly the output the curve allows, Y in") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx, xIn = false)
      accepts(user(ctx), s.tx)
    }
  }

  property("swap: rejects one unit of Y more than the curve allows (satisfiesAMM, X in)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = true)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next.copy(reservesY = next.reservesY - 1L))).tx)
    }
  }

  property("swap: rejects one unit of X more than the curve allows (satisfiesAMM, Y in)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = false)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next.copy(reservesX = next.reservesX - 1L))).tx)
    }
  }

  property("swap: each side pays its own fee into its own pending (feeXAdded, feeYAdded)") {
    withCtx { ctx =>
      val (_, nextX, qX) = swapScenario(ctx, xIn = true)
      val (_, nextY, qY) = swapScenario(ctx, xIn = false)
      qX.protocolFee shouldBe 1000L * 1000000L * FEE_PARAMS(1) / FEE_DENOM
      qY.protocolFee shouldBe 1000L * 1000000L * FEE_PARAMS(2) / FEE_DENOM
      nextX.pendingX shouldBe traded.pendingX + qX.protocolFee
      nextY.pendingY shouldBe traded.pendingY + qY.protocolFee
    }
  }

  property("swap: rejects a protocol fee one unit short (feeXAdded)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = true)
      rejectsAtSigning(user(ctx), s.output(POOL,
        poolUTXO(next.copy(pendingX = next.pendingX - 1L, reservesX = next.reservesX + 1L))).tx)
    }
  }

  property("swap: rejects charging the X-side fee to a Y-in swap") {
    withCtx { ctx =>
      val (s, next, q) = swapScenario(ctx, xIn = false)
      val xRate = q.amountIn * FEE_PARAMS(1) / FEE_DENOM
      val wrong = next.copy(
        pendingY = traded.pendingY + xRate,
        reservesY = traded.reservesY + q.amountIn - xRate,
        accY = traded.accY + (BigInt(xRate) * SCALE) / BigInt(traded.supply))
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(wrong)).tx)
    }
  }

  property("swap: rejects an accumulator advanced past the fee that funded it (accXAdvanced)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = true)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next.copy(accX = next.accX + 1))).tx)
    }
  }

  property("swap: rejects moving the Y accumulator on an X-in swap (accYAdvanced)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = true)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next.copy(accY = next.accY + 1))).tx)
    }
  }

  property("swap: rejects taking both tokens out at once") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx)
      val theft = traded.copy(reservesX = traded.reservesX - 1000000L, reservesY = traded.reservesY - 1000000L)
      rejectsAtSigning(user(ctx),
        s.input(1, funding(ctx, 1)).output(POOL, poolUTXO(theft)).tx)
    }
  }

  property("swap: rejects a swap that moves nothing") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.input(1, funding(ctx, 1)).output(POOL, poolUTXO(traded)).tx)
    }
  }

  property("swap: rejects a successor claiming more pending than the box holds (reservesHeld)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx, xIn = true)
      // Same balances, everything relabelled as pending, so only the reserve bound can object
      val hollow = next.copy(reservesX = 0L, pendingX = next.balanceX)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(hollow)).tx)
    }
  }

  // ─── the pool's ERG ───────────────────────────────────────────────────────

  property("pool: rejects taking one nanoERG of the pool's storage value") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next.copy(value = next.value - 1L))).tx)
    }
  }

  property("pool: accepts topping up the pool's storage value") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      accepts(user(ctx), s.output(POOL, poolUTXO(next.copy(value = next.value + Parameters.OneErg))).tx)
    }
  }

  property("pool: a redemption takes no ERG from the pool either") {
    withCtx { ctx =>
      val (r, next, _) = redeemScenario(ctx)
      accepts(user(ctx), r.tx)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next.copy(value = next.value - 1L)))
        .output(1, r.outputs(1).addValue(1L)).tx)
    }
  }

  property("pool: rejects a successor padded with an R9, which would raise its storage fee") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next).withReg(5, bytesValue(Array.fill(64)(7.toByte)))).tx)
    }
  }

  // ─── layout ───────────────────────────────────────────────────────────────

  property("pool: rejects swapping token X and token Y around") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      val out = poolUTXO(next)
      val swapped = out.setTokens(out.tokens.head, out.tokens(2), out.tokens(1), out.tokens(3))
      rejectsAtSigning(user(ctx), s.output(POOL, swapped).tx)
    }
  }

  property("pool: rejects replacing token X with another (tokenX1)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      val supply = funding(ctx, 2, Seq(Token(unrelatedToken, next.balanceX)))
      rejectsAtSigning(user(ctx), s.copy(
        inputs = s.inputs :+ supply,
        outputs = Seq(poolUTXO(next, tokenXId = unrelatedToken)),
        burn = Seq(Token(tokenX, next.balanceX))).tx)
    }
  }

  property("pool: rejects a fifth token in the pool box (tokens.size)") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      val supply = funding(ctx, 2, Seq(Token(unrelatedToken, 1L)))
      rejectsAtSigning(user(ctx), s.copy(inputs = s.inputs :+ supply)
        .output(POOL, poolUTXO(next).addToken(Token(unrelatedToken, 1L))).tx)
    }
  }

  property("pool: rejects the pool anywhere but INPUTS(0) (onlyOne)") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.copy(inputs = s.inputs.reverse).tx)
    }
  }

  property("pool: rejects sending the pool to another script") {
    withCtx { ctx =>
      val (s, next, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.output(POOL, poolUTXO(next).setContract(userContract(ctx))).tx)
    }
  }

  property("pool: an unrecognised op byte fails closed") {
    withCtx { ctx =>
      Seq[Byte](5, 6, 127, -1).foreach { op =>
        rejectsAtSigning(user(ctx), build(ctx, Seq(poolInput(ctx, poolUTXO(traded), op), funding(ctx, 1)),
          Seq(poolUTXO(traded)), user(ctx).getAddress))
      }
    }
  }

  property("pool: rejects an outside provision token joining a swap (provTokenHeld)") {
    withCtx { ctx =>
      val (s, _, _) = swapScenario(ctx)
      rejectsAtSigning(user(ctx), s.copy(inputs = s.inputs :+ funding(ctx, 2, Seq(Token(provToken, 1L)))).tx)
    }
  }

  // ─── deposits ─────────────────────────────────────────────────────────────

  property("deposit: accepts exactly the shares the tokens unlock (sharesUnlocked)") {
    withCtx { ctx =>
      val (d, _, _) = depositScenario(ctx)
      accepts(user(ctx), d.tx)
    }
  }

  property("deposit: rejects one share more than the tokens unlock (sharesUnlocked)") {
    withCtx { ctx =>
      val (d, next, q) = depositScenario(ctx)
      val greedy = next.copy(supply = next.supply + 1L)
      val nft = d.inputs.head.id
      rejectsAtSigning(user(ctx), d.output(POOL, poolUTXO(greedy))
        .output(1, provisionUTXO(traded.accX, traded.accY, nft, q.shares + 1L)).tx)
    }
  }

  property("deposit: rejects underpaying token X by one unit") {
    withCtx { ctx =>
      val (d, next, _) = depositScenario(ctx)
      rejectsAtSigning(user(ctx), d.output(POOL, poolUTXO(next.copy(reservesX = next.reservesX - 1L))).tx)
    }
  }

  property("deposit: rejects underpaying token Y by one unit") {
    withCtx { ctx =>
      val (d, next, _) = depositScenario(ctx)
      rejectsAtSigning(user(ctx), d.output(POOL, poolUTXO(next.copy(reservesY = next.reservesY - 1L))).tx)
    }
  }

  property("deposit: rejects a provision entering behind the pool's accumulator (provisionBox.R4)") {
    withCtx { ctx =>
      val (d, _, q) = depositScenario(ctx)
      val nft = d.inputs.head.id
      rejectsAtSigning(user(ctx), d.output(1, provisionUTXO(traded.accX - 1, traded.accY, nft, q.shares)).tx)
    }
  }

  property("deposit: rejects a provision box under any script but the guard") {
    withCtx { ctx =>
      val (d, _, q) = depositScenario(ctx)
      val nft = d.inputs.head.id
      rejectsAtSigning(user(ctx), d.output(1,
        provisionUTXO(traded.accX, traded.accY, nft, q.shares, contract = userContract(ctx))).tx)
    }
  }

  property("deposit: rejects a provision locked under the ERG/token deployment's guard") {
    withCtx { ctx =>
      val (d, _, q) = depositScenario(ctx)
      val nft = d.inputs.head.id
      val otherGuard = LithosDexSpecBase.compiled(ctx).provisionGuard
      rejectsAtSigning(user(ctx), d.output(1,
        provisionUTXO(traded.accX, traded.accY, nft, q.shares, contract = otherGuard)).tx)
    }
  }

  property("deposit: rejects a provision box under CONST_PROVISION_MIN") {
    withCtx { ctx =>
      val (d, _, q) = depositScenario(ctx)
      val nft = d.inputs.head.id
      rejectsAtSigning(user(ctx), d.output(1,
        provisionUTXO(traded.accX, traded.accY, nft, q.shares, value = PROVISION_MIN - 1L)).tx)
    }
  }

  property("deposit: rejects minting two ownership NFTs (numTokenInOutputs)") {
    withCtx { ctx =>
      val (d, _, _) = depositScenario(ctx)
      val nft = d.inputs.head.id
      rejectsAtSigning(user(ctx), d.output(2, UTXO(userContract(ctx), NFT_BOX_VALUE, Seq(Token(nft, 2L)))).tx)
    }
  }

  property("deposit: rejects letting a spare provision token escape with the depositor") {
    withCtx { ctx =>
      val (d, next, _) = depositScenario(ctx)
      rejectsAtSigning(user(ctx), d.output(POOL, poolUTXO(next.copy(provTokens = next.provTokens - 1L)))
        .output(2, d.outputs(2).addToken(Token(provToken, 1L))).tx)
    }
  }

  property("deposit: rejects sweeping pending fees into the reserves") {
    withCtx { ctx =>
      val (d, next, _) = depositScenario(ctx)
      rejectsAtSigning(user(ctx), d.output(POOL,
        poolUTXO(next.copy(pendingX = 0L, reservesX = next.reservesX + next.pendingX))).tx)
    }
  }

  // ─── redemptions ──────────────────────────────────────────────────────────

  property("redeem: accepts exactly the proportional share of both tokens (satisfiesAMM)") {
    withCtx { ctx =>
      val (r, _, _) = redeemScenario(ctx)
      accepts(user(ctx), r.tx)
    }
  }

  property("redeem: rejects one unit of X more than proportional") {
    withCtx { ctx =>
      val (r, next, q) = redeemScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next.copy(reservesX = next.reservesX - 1L)))
        .output(1, UTXO(userContract(ctx), PROVISION_MIN,
          Seq(Token(tokenX, q.amountX + 1L), Token(tokenY, q.amountY)))).tx)
    }
  }

  property("redeem: rejects one unit of Y more than proportional") {
    withCtx { ctx =>
      val (r, next, q) = redeemScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next.copy(reservesY = next.reservesY - 1L)))
        .output(1, UTXO(userContract(ctx), PROVISION_MIN,
          Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY + 1L)))).tx)
    }
  }

  property("redeem: rejects a redemption that takes supply under CONST_MIN_SUPPLY") {
    withCtx { ctx =>
      val thin = traded.copy(supply = MIN_SUPPLY + 5000L)
      val (atFloor, _, _) = redeemScenario(ctx, shares = 5000L, state = thin)
      accepts(user(ctx), atFloor.tx)
      val (under, _, _) = redeemScenario(ctx, shares = 5001L, state = thin)
      rejectsAtSigning(user(ctx), under.tx)
    }
  }

  property("redeem: rejects a provision box under any script but the guard") {
    withCtx { ctx =>
      val (r, _, _) = redeemScenario(ctx)
      val fake = provisionUTXO(BigInt(0), BigInt(0), ownerNFT, 10000000000L, contract = userContract(ctx))
      rejectsAtSigning(user(ctx), r.input(1, inputAt(fake, ctx, 1)).tx)
    }
  }

  property("redeem: rejects the provision token escaping instead of returning to the pool") {
    withCtx { ctx =>
      val (r, next, _) = redeemScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next.copy(provTokens = next.provTokens - 1L)))
        .output(1, r.outputs(1).addToken(Token(provToken, 1L))).tx)
    }
  }

  property("redeem: rejects advancing an accumulator (accHeld)") {
    withCtx { ctx =>
      val (r, next, _) = redeemScenario(ctx)
      rejectsAtSigning(user(ctx), r.output(POOL, poolUTXO(next.copy(accX = next.accX + 1))).tx)
    }
  }

  // ─── the client's view ────────────────────────────────────────────────────

  property("client: the input simSwapForOutput quotes buys at least the output asked for, and is accepted") {
    withCtx { ctx =>
      Seq(true, false).foreach { xIn =>
        val wanted = 500L * 1000000L
        val q = traded.view.simSwapForOutput(wanted, xIn).get
        q.amountOut should be >= wanted
        val (s, _, _) = swapScenario(ctx, amountIn = q.amountIn, xIn = xIn)
        accepts(user(ctx), s.tx)
      }
    }
  }

  property("client: simDeposit spends no more than it is given and its shares are accepted") {
    withCtx { ctx =>
      val q = traded.view.simDeposit(700L * 1000000L, 2000L * 1000000L)
      q.amountX should be <= 700L * 1000000L
      q.amountY should be <= 2000L * 1000000L
      val (d, _, _) = depositScenario(ctx, shares = q.shares)
      accepts(user(ctx), d.tx)
    }
  }

  property("client: LDT2TLiquidityState and the pool view read back the box they describe") {
    withCtx { ctx =>
      val box = inputAt(poolUTXO(traded), ctx, 0)
      val state = lithosdex.states.LDT2TLiquidityState.fromBox(box, 0, 1)
      val view = lithosdex.LDT2TLiquidityPool(box)
      // PoolState compares feeParams by reference, so the contents are checked apart
      state.feeParams.toSeq shouldEqual FEE_PARAMS.toSeq
      view.feeParams.toSeq shouldEqual FEE_PARAMS.toSeq
      stateOf(state.pool).copy(feeParams = FEE_PARAMS) shouldEqual traded
      stateOf(view).copy(feeParams = FEE_PARAMS) shouldEqual traded
      (state.tokenX, state.tokenY, state.provToken) shouldEqual ((tokenX, tokenY, provToken))
    }
  }

  property("pool: every op byte the contract names is one of the five documented paths") {
    Seq(LDHelpers.POOL_SWAP, LDHelpers.POOL_DEPOSIT, LDHelpers.POOL_REDEEM,
      LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE) shouldBe Seq[Byte](0, 1, 2, 3, 4)
  }
}
