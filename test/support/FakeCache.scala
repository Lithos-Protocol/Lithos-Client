package support

import play.api.cache.SyncCacheApi

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/** In-memory [[SyncCacheApi]] with empty-by-default behavior for actor tests. */
class FakeCache extends SyncCacheApi {

  private val entries = mutable.Map.empty[String, Any]

  override def set(key: String, value: Any, expiration: Duration): Unit = entries.synchronized {
    entries(key) = value
  }

  override def remove(key: String): Unit = entries.synchronized {
    entries.remove(key)
  }

  override def getOrElseUpdate[A: ClassTag](key: String, expiration: Duration)(orElse: => A): A =
    entries.synchronized {
      entries.get(key).collect { case value: A => value }.getOrElse {
        val computed = orElse
        entries(key) = computed
        computed
      }
    }

  override def get[T: ClassTag](key: String): Option[T] =
    entries.synchronized {
      entries.get(key).collect { case value: T => value }
    }
}
