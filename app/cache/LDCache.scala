package cache

import cache.LDCache.{LD_POOL_BOX, LD_VAULT_BOX}
import lithosdex.states.{LDFeeValue, LDLiquidityState}
import play.api.cache.SyncCacheApi

/**
 * Last known LithosDex state.
 *
 * There is no plasma tree and nothing to synchronise, so following the pool is following one box,
 * plus the vault box. A provision's entitlement is derived from the accumulators rather than recorded
 * anywhere, so nothing about a provision is cached — it is read from its own box when asked for.
 *
 * That is what let the whole PlasmaDex mirroring layer — its cache, its two synchronizers and its
 * sync task — be deleted rather than ported.
 *
 * That is also why there is no LithosDex equivalent of `/dex/:lpId/sync`: with no tree there is no
 * synchronisation to be behind on, and a snapshot here is only ever a saved read.
 */
class LDCache(cacheApi: SyncCacheApi) {

  def setPool(state: LDLiquidityState): Unit = cacheApi.set(LD_POOL_BOX, state)

  def getPool: Option[LDLiquidityState] = cacheApi.get[LDLiquidityState](LD_POOL_BOX)

  def setVault(vault: LDFeeValue): Unit = cacheApi.set(LD_VAULT_BOX, vault)

  def getVault: Option[LDFeeValue] = cacheApi.get[LDFeeValue](LD_VAULT_BOX)

  /**
   * Height of the most recent flush, as far as this client has seen.
   *
   * Recorded rather than derived: the vault box carries the accumulators it was flushed with but not
   * when that happened, and the alternative — walking the vault's box lineage — is a chain of index
   * lookups for one cosmetic field. Absent until this client observes a flush, which is why the field
   * is optional everywhere it surfaces.
   */
  def setLastFlushHeight(height: Int): Unit = cacheApi.set(LDCache.LD_LAST_FLUSH, height)

  def getLastFlushHeight: Option[Int] = cacheApi.get[Int](LDCache.LD_LAST_FLUSH)

  /**
   * Save a vault reading, noting a flush if its accumulators moved.
   *
   * The accumulators only ever advance on a flush, so a change between two readings is a flush having
   * landed in between and the height it is attributed to is the height it was first seen at.
   */
  def observeVault(vault: LDFeeValue, height: Int): Unit = {
    val moved = getVault.exists(prev => prev.accX != vault.accX || prev.accY != vault.accY)
    if (moved) setLastFlushHeight(height)
    setVault(vault)
  }
}

object LDCache {
  private final val LD_POOL_BOX = "LD_POOL_BOX"
  private final val LD_VAULT_BOX = "LD_VAULT_BOX"
  private final val LD_LAST_FLUSH = "LD_LAST_FLUSH"

  def apply(cache: SyncCacheApi) = new LDCache(cache)
}
