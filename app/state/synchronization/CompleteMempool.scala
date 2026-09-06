package state.synchronization

import node.NodeApi
import node.rest.NodeCodecs

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** Complete local inventory, independent of offset ordering. No hydrated transaction survives collection. */
object CompleteMempool {
  case object Refresh
  final case class Observation(revision: Long, snapshot: Option[Snapshot], failure: Option[String]) {
    def fresh: Boolean = failure.isEmpty && snapshot.exists(s => System.nanoTime() - s.observedAt < MaxAgeNanos)
  }
  final case class Snapshot(anchor: String, ids: Set[String], spent: Set[String], observedAt: Long)
  final val MaxTransactions = 4096
  final val MaxBytes = 16 * 1024 * 1024L
  final val MaxInputs = 65536
  final val MaxAgeNanos = 15000000000L
  final val MaxWalkNanos = 30000000000L

  def anchor(node: NodeApi): String = {
    val info = node.info().get
    val id = info.bestFullHeaderId.getOrElse(throw new IllegalStateException("node has no full chain anchor"))
    require(id.matches("[0-9a-fA-F]{64}"), "invalid full chain anchor")
    id.toLowerCase
  }

  def collect(node: NodeApi): Try[Snapshot] = Try {
    val started = System.nanoTime()
    def budget(): Unit = require(System.nanoTime() - started < MaxWalkNanos, "mempool refresh deadline exceeded")
    def inventory(): Set[String] = {
      budget()
      val ids = node.unconfirmedTransactionIds().get
      require(ids.size <= MaxTransactions && ids.distinct.size == ids.size, "mempool inventory exceeds limit or contains duplicates")
      require(ids.forall(_.matches("[0-9a-f]{64}")), "invalid mempool transaction id")
      ids.toSet
    }
    val tip = anchor(node)
    val ids = inventory()
    var bytes = 0L
    var spends = Set.empty[String]
    ids.toVector.sorted.foreach { id =>
      budget()
      // The byIds route on the supported node returns IDs, not transaction bodies.
      val tx = node.unconfirmedTransactionById(id).get.getOrElse(
        throw new IllegalStateException("mempool member disappeared during collection"))
      require(tx.id == id, "mempool body does not match requested id")
      bytes += NodeCodecs.encodeTransaction(tx).toString.getBytes(UTF_8).length
      require(bytes <= MaxBytes, "mempool body budget exceeded")
      val inputs = tx.inputs.map(_.boxId)
      require(inputs.forall(_.matches("[0-9a-f]{64}")) && inputs.distinct.size == inputs.size,
        "invalid or duplicate mempool input")
      require(!inputs.exists(spends.contains), "conflicting mempool input")
      spends ++= inputs
      require(spends.size <= MaxInputs, "mempool spend index budget exceeded")
    }
    require(inventory() == ids && anchor(node) == tip, "mempool membership or chain anchor changed")
    budget()
    Snapshot(tip, ids, spends, System.nanoTime())
  }
}