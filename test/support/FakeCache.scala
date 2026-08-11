package support

import play.api.cache.SyncCacheApi

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * An in-memory [[SyncCacheApi]], so actors that read the rollup cache can be built.
 *
 * Deliberately not a mock: the cache is read on paths a test drives incidentally rather than
 * asserts on, and a strict mock would fail on the first unstubbed call for no useful reason. Empty
 * by default, which is what "no mempool state for this rollup" looks like.
 */
class FakeCache extends SyncCacheApi {

  private val entries = mutable.Map.empty[String, Any]

  override def set(key: String, value: Any, expiration: Duration): Unit = entries(key) = value

  override def remove(key: String): Unit = entries.remove(key)

  override def getOrElseUpdate[A: ClassTag](key: String, expiration: Duration)(orElse: => A): A =
    get[A](key).getOrElse {
      val computed = orElse
      set(key, computed, expiration)
      computed
    }

  override def get[T: ClassTag](key: String): Option[T] =
    entries.get(key).flatMap {
      case t: T => Some(t)
      case _ => None
    }
}
