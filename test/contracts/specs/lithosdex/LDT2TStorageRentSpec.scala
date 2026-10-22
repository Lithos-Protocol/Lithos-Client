package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.UTXO

/**
 * Storage rent against the token/token pool and vault.
 *
 * Unlike the ERG/token pool, whose ERG is its reserves, these boxes hold ERG only as a deposit. A
 * collection deducts its fee from that deposit, and once the deposit no longer covers the fee the next
 * collection takes the box outright, reserve tokens and unclaimed fees included. So both boxes refuse an
 * extra register, which bounds their length, and the genesis floors in `LDHelpers` are sized against
 * that bound. The floors are what `LDT2TGenesis` warns on rather than anything a contract enforces.
 */
class LDT2TStorageRentSpec extends AnyPropSpec with LDT2TSpecBase {

  private def factor(ctx: BlockchainContext): Long =
    ctx.getDataSource.getParameters.getStorageFeeFactor.toLong

  private def sizeOf(ctx: BlockchainContext, box: UTXO): Int = inputAt(box, ctx, 0).bytes.length

  private val typeMax = BigInt(2).pow(255) - 1

  /**
   * The largest a pool box can be. The tokens and script are pinned, R5 is pinned to genesis's three
   * numerators and R6 to two, R7 and R8 are BigInts of at most 34 bytes, and no R9 may exist.
   */
  private def structuralMaxPool: UTXO = poolUTXO(PoolState(
    reservesX = Long.MaxValue / 2, reservesY = Long.MaxValue / 2,
    pendingX = Long.MaxValue / 2, pendingY = Long.MaxValue / 2,
    supply = 1L, provTokens = Long.MaxValue, accX = typeMax, accY = typeMax,
    feeParams = Array(Long.MaxValue, Long.MaxValue, Long.MaxValue), value = Long.MaxValue))

  /** The largest a vault box can be: pinned tokens and script, two BigInts, and no R6. */
  private def structuralMaxVault: UTXO =
    vaultUTXO(Long.MaxValue, Long.MaxValue, typeMax, typeMax, value = Long.MaxValue)

  property("rent: sizes and fees for the token/token boxes") {
    withCtx { ctx =>
      def show(name: String, box: UTXO, floor: Long): Unit = {
        val bytes = sizeOf(ctx, box)
        val fee = bytes * factor(ctx)
        println(f"[t2t rent] $name%-16s $bytes%5d bytes  fee $fee%13d  floor $floor%13d  " +
          f"collections survived ${floor / fee}%2d")
      }
      show("pool (max)", structuralMaxPool, LDHelpers.T2T_POOL_MIN_VALUE)
      show("pool (typical)", poolUTXO(traded), LDHelpers.T2T_POOL_MIN_VALUE)
      show("vault (max)", structuralMaxVault, LDHelpers.T2T_VAULT_MIN_VALUE)
      show("vault (typical)", vaultUTXO(1000000L, 1000000L, traded.accX, traded.accY), LDHelpers.T2T_VAULT_MIN_VALUE)
    }
  }

  property("rent: the pool floor clears two collections at its structural maximum") {
    withCtx { ctx =>
      LDHelpers.T2T_POOL_MIN_VALUE should be >= 2L * sizeOf(ctx, structuralMaxPool) * factor(ctx)
    }
  }

  property("rent: the vault floor clears two collections at its structural maximum") {
    withCtx { ctx =>
      LDHelpers.T2T_VAULT_MIN_VALUE should be >= 2L * sizeOf(ctx, structuralMaxVault) * factor(ctx)
    }
  }
}
