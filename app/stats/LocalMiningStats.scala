package stats

import java.util.UUID

final case class FraudObservation(rollupBlockId: String, minerHash: String, proofContractHash: String, observedAt: Long,
                                    transactionId: Option[String] = None)
final case class FraudSubmissionObserved(rollupBlockId: String, minerHash: String, proofContractHash: String, transactionId: String)
final case class LocalMiningObservation(kind: String, session: String, sequence: Long,
                                           startedAt: Long, observedAt: Long, counters: Map[String, String],
                                           fraud: Vector[FraudObservation] = Vector.empty, stopped: Boolean = false,
                                           superShareHeights: Vector[Int] = Vector.empty)
final case class LocalMiningActivityView(status: String, observation: LocalMiningObservation,
                                            deduplicationComplete: Boolean)

/** Actor-confined counters; the only outgoing message is a periodically resent cumulative snapshot. */
final class LocalMiningStats(val kind: String) {
  require(LocalMiningStats.Kinds.contains(kind), "unknown local mining statistics producer")
  private val session = UUID.randomUUID().toString
  private val started = System.currentTimeMillis()
  private var sequence = 0L
  private var counters = Map.empty[String, BigInt]
  private var discoveries = Vector.empty[FraudObservation]
  private var superShareHeights = Vector.empty[Int]

  def add(name: String, amount: BigInt = BigInt(1)): Unit =
    counters += name -> (counters.getOrElse(name, BigInt(0)) + amount)

  /** Keeps the highest super-share heights, newest first. A NISP never uses more than these. */
  def superShare(height: Int): Unit =
    superShareHeights = (height +: superShareHeights).sorted(Ordering[Int].reverse).take(NispStatus.RequiredShares)

  def found(rollup: String, miner: String, proof: String): Unit = {
    if (!discoveries.exists(f => f.rollupBlockId == rollup && f.minerHash == miner && f.proofContractHash == proof)) {
      if (discoveries.size == 100) add("discoveryEvictions")
      discoveries = (discoveries :+ FraudObservation(rollup, miner, proof, System.currentTimeMillis())).takeRight(100)
      add("discoveriesWithinSession")
    }
  }

  def submitted(event: FraudSubmissionObserved): Unit = {
    if (!discoveries.exists(_.transactionId.contains(event.transactionId))) {
      val matching = discoveries.indexWhere(f => f.rollupBlockId == event.rollupBlockId &&
        f.minerHash == event.minerHash && f.proofContractHash == event.proofContractHash)
      val observation = FraudObservation(event.rollupBlockId, event.minerHash, event.proofContractHash,
        System.currentTimeMillis(), Some(event.transactionId))
      if (matching >= 0) discoveries = discoveries.updated(matching, observation)
      else {
        if (discoveries.size == 100) add("discoveryEvictions")
        discoveries = (discoveries :+ observation).takeRight(100)
      }
      add("nodeAcceptedProofsWithinSession")
    }
  }

  def snapshot(stopped: Boolean = false): LocalMiningObservation = {
    sequence += 1
    LocalMiningObservation(kind, session, sequence, started, System.currentTimeMillis(),
      counters.map { case (name, value) => name -> value.toString }, discoveries, stopped, superShareHeights)
  }
}

object LocalMiningStats {
  val Kinds: Set[String] = Set("shares", "solutions", "fraud")
}
