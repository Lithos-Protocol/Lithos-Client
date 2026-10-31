package stats

import akka.actor.ActorSystem
import api.{LDPoolIndex, PoolIndexSnapshot}
import configs.{Contexts, NodeContext, VerifiedPool, VerifiedRegistry}
import lithosdex.LDHelpers
import org.ergoplatform.appkit.NetworkType
import transactions.batching.lithosdex.LDBoxes

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Try}

object VerifiedPoolStatsRefresh {
  final case class Flight(owner: UUID, startedAt: Long, startedNanos: Long,
                          result: Future[Map[String, Try[DexStatsData]]])

  /**
   * The verified pools besides ERG:LIT that the pool index lists, each from the height it joined the list. A pool
   * the index has not found yet, or whose list entry gives no height, is left out until it does.
   */
  def targets(snapshot: PoolIndexSnapshot, network: NetworkType): Seq[DexStatsTarget] = {
    val registry = VerifiedRegistry.forNetwork(network)
    targets(snapshot, LDHelpers.getPoolNFT(network).toString, nft => registry.pool(nft).flatMap(_.addedHeight))
  }

  def targets(snapshot: PoolIndexSnapshot, canonicalNft: String, addedHeight: String => Option[Int]): Seq[DexStatsTarget] =
    snapshot.pools.filter(p => p.verified && p.nft != canonicalNft).flatMap { p =>
      val tree = p.n2t.map(_.liquidityPool.ergoTreeHex).orElse(p.t2t.map(_.liquidityPool.ergoTreeHex))
      for (t <- tree; since <- addedHeight(p.nft))
        yield DexStatsTarget(LDBoxes.PoolLineage(p.nft, t, p.vaultNft, p.kind == VerifiedPool.Kind.T2T),
          p.tokenX, p.tokenY, since)
    }
}

/**
 * One read slot for every verified pool besides ERG:LIT, read one after another on the stats dispatcher. Each pool
 * has the refresh budget to itself, and one that fails costs that pool alone. With no such pool a pass reads nothing.
 */
@Singleton
class VerifiedPoolStatsRefresh(source: PoolStatsSource, targets: () => Seq[DexStatsTarget], worker: ExecutionContext) {
  @Inject def this(source: PoolStatsSource, index: LDPoolIndex, node: NodeContext, system: ActorSystem) =
    this(source, () => VerifiedPoolStatsRefresh.targets(index.snapshot(), node.getNetwork),
      system.dispatchers.lookup(Contexts.key(Contexts.StatsRead)))
  import VerifiedPoolStatsRefresh._
  private val active = new AtomicReference[Flight]()

  def busy: Boolean = active.get() != null

  def start(owner: UUID): Boolean = {
    val promise = Promise[Map[String, Try[DexStatsData]]]()
    val flight = Flight(owner, System.currentTimeMillis(), System.nanoTime(), promise.future)
    if (!active.compareAndSet(null, flight)) false
    else {
      Try(worker.execute(new Runnable {
        override def run(): Unit = promise.complete(Try(targets().map(t => t.lineage.nft -> Try(source.read(t))).toMap))
      })).failed.foreach(ex => promise.tryComplete(Failure(ex)))
      true
    }
  }

  def takeCompleted(): Option[(Flight, Try[Map[String, Try[DexStatsData]]])] = Option(active.get()).flatMap { flight =>
    flight.result.value.flatMap { result =>
      if (active.compareAndSet(flight, null)) Some(flight -> result) else None
    }
  }
}
