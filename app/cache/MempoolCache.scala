package cache

import cache.MempoolCache.{MEMCHAIN_PREFIX, MEMCHAIN_TTL, MEMSTATE_PREFIX, MEMSTATE_TTL}
import lfsm.states.MinerTree
import play.api.cache.SyncCacheApi
import state.messages.MempoolMessages.{MempoolChain, MempoolState}

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class MempoolCache(cacheApi: SyncCacheApi) {

  def setMempoolChain(id: String, mempoolChain: MempoolChain): Unit = {
    cacheApi.set(MEMCHAIN_PREFIX + id, mempoolChain, MEMCHAIN_TTL)
  }
  def getMempoolChain(id: String): Option[MempoolChain] = {
    cacheApi.get(MEMCHAIN_PREFIX + id)
  }

  def setMempoolState[T <: MempoolState](id: String, mempoolRollupState: T): Unit = {
    cacheApi.set(MEMSTATE_PREFIX + id, mempoolRollupState, MEMSTATE_TTL)
  }

  def removeMempoolState(id: String): Unit = {
    cacheApi.remove(MEMSTATE_PREFIX + id)
  }

  def getMempoolState[T <: MempoolState : ClassTag](id: String): Option[T] = {
    implicitly[ClassTag[T]]
    cacheApi.get[T](MEMSTATE_PREFIX+id)
  }
}

object MempoolCache {
  private final val MEMSTATE_PREFIX = "MEMSTATE_"
  private final val MEMCHAIN_PREFIX = "MEMCHAIN_"
  // Mempool states should die after 5 minutes. Although SubmissionHandler may manually tell
  // mempool state managers to remove the state (for example when getting malformed transaction error)
  // this covers cases where a tx is removed from the mempool for a UTXO state which would not have been
  // transacted with after (e.g last NISP submission to a rollup which has ample time before holding transform)
  private final val MEMSTATE_TTL = 5.minutes

  // Mempool chains need not live long, because if they are relevant the state will last longer,
  // and if they are irrelevant (no associated state manager / synchronizer) they should not
  // take excess memory
  private final val MEMCHAIN_TTL = 30.seconds
  def apply(cache: SyncCacheApi) = new MempoolCache(cache)
}
