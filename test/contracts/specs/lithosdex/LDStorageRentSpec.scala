package contracts.specs.lithosdex

import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.UTXO

/**
 * Storage rent, which is the one way a LithosDex box can be spent without its script running at all.
 *
 * Ergo's rule: once a box is `StoragePeriod` blocks old a miner may spend it, and
 *
 *   - if `storageFeeFactor * box.bytes.length >  box.value` the spend is unconditional — script,
 *     tokens and registers are all forfeit, and the miner keeps whatever the box held;
 *   - otherwise the successor must carry the same ergo tree, the same tokens and the same R4–R9, and
 *     at least `box.value - storageFee`, which makes a collection behave like a forced refresh.
 *
 * Only the **provision** is defended against it, and the split is deliberate. A provision can be
 * abandoned by one owner while the protocol runs on, its refresh path is optional, and its token is a
 * claim on the fee vault the moment it leaves a guard-locked box. The pool and the vault cannot reach
 * collection age without the protocol having stopped — no swaps, no flushes, no fees left to lose —
 * and a floor on either is paid for every day it exists: `CONST_MIN_RENT` also bounds a redemption and
 * bounds what a flush may leave behind.
 *
 * The floor is only meaningful because the box's length is bounded. Length is otherwise chosen by
 * whoever builds the box, up to Ergo's `MaxBoxSize` of 4096, where the fee is 5.12 ERG.
 */
class LDStorageRentSpec extends AnyPropSpec with LithosDexSpecBase {

  /** `Constants.StoragePeriod` — four years of blocks at two minutes each. */
  private val STORAGE_PERIOD: Int = 1051200

  /** The ceiling miners can vote `storageFeeFactor` up to, from Ergo's own parameter bounds. */
  private val FACTOR_MAX: Long = 2500000L

  private def factor(ctx: BlockchainContext): Long =
    ctx.getDataSource.getParameters.getStorageFeeFactor.toLong

  private def sizeOf(ctx: BlockchainContext, box: UTXO): Int = inputAt(box, ctx, 0).bytes.length

  /**
   * The largest a provision box can be, rather than the largest one observed.
   *
   * `SBigInt` serializes as a length prefix plus at most `SigmaConstants.MaxBigIntSizeInBytes` (32)
   * bytes, so R4 and R5 cost at most 34 each however far the accumulators have run; R6 is pinned to a
   * 32-byte id and R7 to a `Long`; the tokens and the script are pinned; and no register above R7 may
   * exist. Every term is bounded by a type or a pin, so this is a maximum, not a measurement.
   */
  private def structuralMaxProvision(ctx: BlockchainContext): UTXO = {
    val typeMax = BigInt(2).pow(255) - 1
    provisionUTXO(ctx, typeMax, typeMax, LithosDexSpecBase.ownerNFT, Long.MaxValue)
  }

  property("rent: sizes and fees for every box the protocol keeps") {
    withCtx { ctx =>
      println(s"[rent] storageFeeFactor ${factor(ctx)} nanoERG/byte, votable ceiling $FACTOR_MAX")
      println(s"[rent] storage period $STORAGE_PERIOD blocks, refresh age $REFRESH_AGE")
      def show(name: String, box: UTXO, floor: Long, defended: Boolean): Unit = {
        val bytes = sizeOf(ctx, box)
        val fee = bytes * factor(ctx)
        println(f"[rent] $name%-22s $bytes%5d bytes  fee $fee%13d  floor $floor%13d  " +
          f"${if (defended) f"collections survived ${floor / fee}%2d" else "not defended, by design"}")
      }
      show("provision (max)", structuralMaxProvision(ctx), PROVISION_MIN, defended = true)
      show("provision (typical)", provisionOf(ctx, 10000000000L, traded.accX, traded.accY).utxo,
        PROVISION_MIN, defended = true)
      show("fee vault", vaultUTXO(ctx, VAULT_MIN, 1000000L, traded.accX, traded.accY), VAULT_MIN,
        defended = false)
      show("liquidity pool", poolUTXO(ctx, traded), MIN_RENT, defended = false)
    }
  }

  property("rent: a provision clears two collections at its structural maximum") {
    withCtx { ctx =>
      // Two covers eight years of neglect. One collection takes the fee and preserves the box; the
      // next takes it outright if what is left no longer clears the fee. Measured against the
      // structural maximum so the margin does not depend on how far the accumulators have run.
      val fee = sizeOf(ctx, structuralMaxProvision(ctx)) * factor(ctx)
      withClue(s"a provision at its structural maximum costs $fee per collection: ") {
        PROVISION_MIN should be >= 2L * fee
      }
    }
  }

  property("rent: a collection cannot take a provision's token") {
    withCtx { ctx =>
      // The condition that matters: below it Ergo's expired-box rule spends the box with no script
      // evaluation at all, and the provision token goes with it — where it is a working claim on the
      // vault for whatever size its holder writes into R7.
      PROVISION_MIN should be > sizeOf(ctx, structuralMaxProvision(ctx)) * factor(ctx)
    }
  }

  property("rent: the pool and vault floors sit below their own fee, deliberately") {
    withCtx { ctx =>
      // Recorded so a later reader does not "fix" it. Covering the fee would mean unwithdrawable
      // ERG-side depth in the pool and locked capital in the vault, to defend boxes that only reach
      // collection age if the protocol has already stopped.
      MIN_RENT should be < sizeOf(ctx, poolUTXO(ctx, traded)) * factor(ctx)
      VAULT_MIN should be < sizeOf(ctx, vaultUTXO(ctx, VAULT_MIN, 1000000L, traded.accX,
        traded.accY)) * factor(ctx)
    }
  }

  property("rent: no box the protocol keeps can approach Ergo's own size limit") {
    withCtx { ctx =>
      // `SigmaConstants.MaxBoxSize`. A box padded to it costs 5.12 ERG per period, which no floor here
      // covers — so what keeps the provision safe is that its length cannot be chosen by a stranger.
      val ergoMax = 4096
      sizeOf(ctx, structuralMaxProvision(ctx)) should be < ergoMax
      (ergoMax * factor(ctx)) should be > PROVISION_MIN
    }
  }

  property("rent: the refresh window opens with a full storage period still to run") {
    withCtx { ctx =>
      // A refreshed box is young again, so the schedule only works if the window between "old enough
      // to refresh" and "old enough to collect" is wide, and wide enough to survive a stale mempool.
      REFRESH_AGE should be < STORAGE_PERIOD
      (REFRESH_AGE + HEIGHT_SLACK) should be < STORAGE_PERIOD
      (STORAGE_PERIOD - REFRESH_AGE) should be >= REFRESH_AGE
    }
  }
}
