package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * The fee-per-share scheme end to end.
 *
 * Permanent fees are taken out of each swap's input and set aside in `pending`, apart from the reserves
 * the AMM trades with — so they are not exposed to impermanent loss and are not traded away. What each
 * provision is owed is never stored anywhere: it is its size times how far the accumulator has moved
 * since it last settled. The properties here check the arithmetic that makes that safe, and then run
 * the whole cycle through the real contracts.
 */
class LDFeeAccountingSpec extends AnyPropSpec with LithosDexSpecBase {

  private val sh1: Long = 5000000000L
  private val sh2: Long = 20000000000L

  /** Fees taken and accumulator reached after a series of swaps, as the contract would compute them. */
  private def run(state: PoolState, swaps: Seq[(Long, Boolean)]): PoolState =
    swaps.foldLeft(state) { case (s, (amount, ergIn)) => afterSwap(s, amount, ergIn) }

  // ─── how a fee is shared ──────────────────────────────────────────────────

  property("fees: two provisions are owed in proportion to their size") {
    val s = run(genesis, Seq((3L * Parameters.OneErg, true)))
    val a = owed(sh1, BigInt(0), s.accX)
    val b = owed(sh2, BigInt(0), s.accX)
    a should be > 0L
    // sh2 is four times sh1, so its share is four times as large up to the truncation of each.
    b shouldBe (a * 4L) +- 4L
  }

  property("fees: what a provision has already earned does not move when supply changes") {
    val s = run(genesis, Seq((3L * Parameters.OneErg, true)))
    val before = owed(sh1, BigInt(0), s.accX)
    // A deposit adds supply and holds the accumulators still, which is the whole reason no snapshot is
    // needed anywhere — nobody's earned fees are recomputed when the denominator moves.
    val diluted = s.copy(supply = s.supply + sh2)
    owed(sh1, BigInt(0), diluted.accX) shouldBe before
  }

  property("fees: a later swap's fee is split over the supply live at that moment") {
    val s = run(genesis, Seq((3L * Parameters.OneErg, true)))
    val narrow = afterSwap(s, 3L * Parameters.OneErg, ergIn = true)
    val wide = afterSwap(s.copy(supply = s.supply * 2L), 3L * Parameters.OneErg, ergIn = true)
    // The same fee over twice the supply moves the accumulator half as far.
    (narrow.accX - s.accX) should be > (wide.accX - s.accX)
    ((narrow.accX - s.accX) / 2) shouldBe (wide.accX - s.accX) +- BigInt(1)
  }

  property("fees: a provision that arrives late is owed nothing for what came before") {
    withCtx { ctx =>
      val s = run(genesis, Seq((3L * Parameters.OneErg, true), (5000L * 1000000L, false)))
      val late = provisionOf(ctx, sh1, s.accX, s.accY)
      owed(sh1, late.entryX, s.accX) shouldBe 0L
      owed(sh1, late.entryY, s.accY) shouldBe 0L
      // And it cannot spend the vault to settle nothing, which is what stops that being free griefing.
      val c = claimScenario(ctx, Seq(late), s.accX, s.accY)
      rejectsAtSigning(c.prover, claimTx(c)())
    }
  }

  // ─── solvency ─────────────────────────────────────────────────────────────

  property("fees: everything owed across every provision is less than what was flushed") {
    val opened = genesis.copy(supply = genesis.supply + sh1 + sh2)
    val s = run(opened, Seq(
      (3L * Parameters.OneErg, true), (5000L * 1000000L, false),
      (1L * Parameters.OneErg, true), (777L * 1000000L, false)))

    val owedX = owed(sh1, BigInt(0), s.accX) + owed(sh2, BigInt(0), s.accX)
    val owedY = owed(sh1, BigInt(0), s.accY) + owed(sh2, BigInt(0), s.accY)
    owedX should be > 0L
    owedY should be > 0L
    // Both roundings truncate downward and the genesis supply is permanently locked, so the vault is
    // always handed strictly more than every provision put together can ask of it.
    owedX should be < s.pendingX
    owedY should be < s.pendingY
    println(f"[fees] flushed X=${s.pendingX}%12d Y=${s.pendingY}%12d   " +
      f"owed X=$owedX%12d Y=$owedY%12d   residual X=${s.pendingX - owedX}%12d Y=${s.pendingY - owedY}%12d")
  }

  property("fees: the residual is the locked share's, and it stays in the vault forever") {
    val opened = genesis.copy(supply = genesis.supply + sh1 + sh2)
    val s = run(opened, Seq((10L * Parameters.OneErg, true)))
    val claimable = owed(sh1, BigInt(0), s.accX) + owed(sh2, BigInt(0), s.accX)
    val lockedShare = BigInt(s.pendingX) * BigInt(genesis.supply) / BigInt(opened.supply)
    // Roughly the locked fraction of the take, and never less than it after truncation.
    (s.pendingX - claimable) should be >= lockedShare.toLong
  }

  property("fees: truncation never rounds in a provision's favour") {
    // Numbers chosen so both divisions lose a remainder.
    val awkward = genesis.copy(supply = 999999999937L)
    val s = run(awkward, Seq((1234567891L, true)))
    val size = 7777777777L
    val exact = BigInt(size) * BigInt(s.pendingX) / BigInt(awkward.supply)
    owed(size, BigInt(0), s.accX).toLong should be <= exact.toLong
  }

  property("fees: settling twice over the same stretch pays nothing the second time") {
    withCtx { ctx =>
      val s = run(genesis, Seq((3L * Parameters.OneErg, true)))
      val first = claimScenario(ctx, Seq(provisionOf(ctx, sh1, BigInt(0), BigInt(0))), s.accX, s.accY)
      first.owedX should be > 0L
      accepts(first.prover, claimTx(first)())
      // The successor's entry is pinned to the vault's accumulator, so a second pass is owed nothing
      // and the vault refuses to be spent for it.
      val settled = provisionOf(ctx, sh1, s.accX, s.accY)
      val second = claimScenario(ctx, Seq(settled), s.accX, s.accY)
      second.owedX shouldBe 0L
      rejectsAtSigning(second.prover, claimTx(second)())
    }
  }

  // ─── fees against impermanent loss ────────────────────────────────────────

  property("fees: a price move changes what a provision redeems for, not what it is owed") {
    withCtx { ctx =>
      // 1e8 of each side buys 1e10 shares at the genesis ratio, so the deposit and the redemption are
      // measured against the same numbers.
      val size = 10000000000L
      val opened = genesis.copy(
        reservesX = genesis.reservesX + 100000000L,
        reservesY = genesis.reservesY + 100000000L,
        supply = genesis.supply + size)
      val moved = afterSwap(opened, 5L * Parameters.OneErg, ergIn = true)

      // Impermanent loss lands on the redemption: more of one side, less of the other.
      val q = moved.view.simRedeem(size)
      q.amountX should be > 100000000L
      q.amountY should be < 100000000L

      // The fee entitlement is a separate number held apart from the reserves, so the same trade that
      // caused the loss also earned the fee, and the fee is not exposed to it.
      val fee = owed(size, BigInt(0), moved.accX)
      fee should be > 0L
      moved.pendingX should be >= fee
      accepts(user(ctx), claimTx(claimScenario(ctx,
        Seq(provisionOf(ctx, size, BigInt(0), BigInt(0))), moved.accX, moved.accY))())
    }
  }

  // ─── conservation ─────────────────────────────────────────────────────────

  property("fees: pending is never part of the reserves the AMM quotes against") {
    val s = run(genesis, Seq((3L * Parameters.OneErg, true), (5000L * 1000000L, false)))
    s.pendingX should be > 0L
    s.pendingY should be > 0L
    // The box holds both, but only the reserves half moves the price.
    s.boxValue shouldBe s.reservesX + s.pendingX
    s.view.reservesX shouldBe s.reservesX
    s.view.spotPriceY shouldBe (BigDecimal(s.reservesY) / BigDecimal(s.reservesX))
  }

  // ─── the whole cycle through the real contracts ───────────────────────────

  property("fees: deposit, deposit, swap, swap, flush, claim, resize and redeem all sign in sequence") {
    withCtx { ctx =>
      val provTokenSupply = genesis.provTokens

      val d1 = depositScenario(ctx, sh1, genesis)
      accepts(d1.prover, depositTx(d1)())
      val p1 = Provision(d1.provOut, genesis.accX, genesis.accY, d1.ownerNFT, sh1)

      val d2 = depositScenario(ctx, sh2, d1.after)
      accepts(d2.prover, depositTx(d2)())
      val p2 = Provision(d2.provOut, d1.after.accX, d1.after.accY, d2.ownerNFT, sh2)

      // Two provisions are out, so two provision tokens have left the pool and nothing was created.
      d2.after.provTokens shouldBe provTokenSupply - 2L

      val w1 = swapScenario(ctx, 3L * Parameters.OneErg, ergIn = true, d2.after)
      accepts(w1.prover, swapTx(w1)())
      val w2 = swapScenario(ctx, 5000L * 1000000L, ergIn = false, w1.after)
      accepts(w2.prover, swapTx(w2)())
      val trading = w2.after

      val f = flushScenario(ctx, trading, VAULT_MIN, 0L)
      accepts(f.prover, flushTx(f)())
      val flushedX = trading.pendingX
      val flushedY = trading.pendingY

      // Everything the two provisions can ask for, against everything the vault was handed.
      val c = claimScenario(ctx, Seq(p1, p2), trading.accX, trading.accY,
        vaultX = VAULT_MIN + flushedX, vaultY = flushedY)
      c.owedX should be > 0L
      c.owedX should be < flushedX
      c.owedY should be < flushedY
      accepts(c.prover, claimTx(c)())
      // And not a nanoERG past it.
      rejectsAtSigning(c.prover, claimTx(c)(
        vaultOut = vaultUTXO(ctx, c.vaultX - c.owedX - 1L, c.vaultY - c.owedY, c.accX, c.accY)))

      val settled = f.after
      val r = resizeScenario(ctx, sh1, sh1 * 2L, settled, trading.accX, trading.accY,
        VAULT_MIN + flushedX, flushedY, PROVISION_MIN, d1.ownerNFT)
      accepts(r.prover, resizeTx(r)())

      val red = redeemScenario(ctx, sh2, r.after, trading.accX, trading.accY, d2.ownerNFT)
      accepts(red.prover, redeemTx(red)())

      // One provision token comes home with the redemption; the resized one is still out.
      red.after.provTokens shouldBe provTokenSupply - 1L
      red.after.supply shouldBe genesis.supply + sh1 * 2L
    }
  }

  property("fees: only a swap ever moves an accumulator") {
    withCtx { ctx =>
      // Pinned path by path elsewhere; stated once here because it is the invariant the vault's
      // solvency argument rests on.
      val s = run(genesis, Seq((3L * Parameters.OneErg, true)))
      s.accX should be > genesis.accX
      depositScenario(ctx, sh1, s).after.accX shouldBe s.accX
      redeemScenario(ctx, sh1, s.copy(supply = s.supply + sh1)).after.accX shouldBe s.accX
      flushScenario(ctx, s).after.accX shouldBe s.accX
      resizeScenario(ctx, sh1, sh2, s).after.accX shouldBe s.accX
      LDHelpers.POOL_SWAP shouldBe 0.toByte
    }
  }
}
