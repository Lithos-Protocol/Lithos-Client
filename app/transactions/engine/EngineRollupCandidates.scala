package transactions.engine

import akka.actor.Actor
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped}
import transactions.candidate.{CandidateBundle, CandidatePreparation}
import transactions.rollups.TransactionMessages.{BuildBlockTxs, CandidateStubsBuilt}
import transactions.engine.execution.RollupExecution._
import transactions.engine.wallet.EngineWalletMessages
import transactions.engine.execution.RollupExecution

/** Candidate attempts and their funding holds are serialized by the engine mailbox. */
trait EngineRollupCandidates extends Actor {
  protected def candidateExecution(alive: () => Boolean): RollupExecution
  /**
   * A height that never got its own drop signal is over the moment a later one starts, so leases
   * left behind by earlier heights are reconciled as each build begins.
   */
  private val rollupPreparation = new CandidatePreparation(
    context.system.dispatchers.lookup("lithos-contexts.engine-candidate-dispatcher"), self,
    onStart = blockHeight => {
      latestCandidateHeight = math.max(latestCandidateHeight, blockHeight)
      candidateLeases.keys.filter(_ < blockHeight).toSeq.foreach(reconcileCandidates)
    })
  private val rollupAlive = new java.util.concurrent.atomic.AtomicBoolean(true)

  /**
   * Bond inputs withheld for fee-less submissions offered into this miner's own block, by the
   * height they were built for. They are never broadcast, so only a terminal event for that height
   * can end them, and it ends them as uncertain rather than free: the block may have taken them.
   */
  private var candidateLeases: Map[Int, Seq[String]] = Map.empty

  /** The most recent height a candidate package was built for, so a late lease is not stranded. */
  private var latestCandidateHeight: Int = 0

  /**
   * Fee-less submissions built for each height, by rollup. A refresh at that height reuses one still
   * spending the rollup's tip rather than reserving another bond box; they end with their height.
   */
  private var heldSubmissions: Map[Int, Map[String, CandidateBundle]] = Map.empty

  abstract override def postStop(): Unit = {
    rollupAlive.set(false)
    candidateLeases.keys.toSeq.foreach(reconcileCandidates)
    super.postStop()
  }

  abstract override def receive: Receive =
    rollupReceive.orElse(rollupPreparation.receive).orElse(super.receive)

  /** Funded operations use the engine schedule; this mailbox only coordinates candidate builds. */
  private def rollupReceive: Receive = {
    // Sent from the build Future, so the map is only ever written on this thread. A lease that
    // arrives after its height has passed is reconciled at once rather than stored and forgotten.
    case CandidateLeaseTaken(blockHeight, reservation) =>
      if (blockHeight < latestCandidateHeight) self ! EngineWalletMessages.MarkReservationUncertain(reservation)
      else candidateLeases += blockHeight ->
        (candidateLeases.getOrElse(blockHeight, Seq.empty[String]) :+ reservation)

    // Whatever was offered for that height can no longer land, so its bond inputs stop being
    // candidate-held. Uncertain, not released: the block may have included the transaction.
    case CandidateTxsDropped(blockHeight) =>
      // The watermark moves past the dropped height, not just to it, so a lease still in flight when
      // this arrives is reconciled on the way in.
      latestCandidateHeight = math.max(latestCandidateHeight, blockHeight + 1)
      reconcileCandidates(blockHeight)

    case CandidateSubmissionHeld(blockHeight, rollupId, bundle) =>
      if (blockHeight >= latestCandidateHeight) heldSubmissions += blockHeight ->
        (heldSubmissions.getOrElse(blockHeight, Map.empty[String, CandidateBundle]) + (rollupId -> bundle))

    // A block is being assembled: build the same work fee-less and hand it back without
    // sending. The stubs stay queued; the requester's report says which ones the build carries.
    case BuildBlockTxs(blockHeight, stubs, answer, _, _, _) if stubs.size > 100 =>
      if (answer) sender() ! BlockTxsReady(blockHeight, Seq.empty)

    // Already built for this height, so the request costs nothing but the reply. A refresh rebuilds,
    // because the work worth offering changes within a height as stubs arrive and sync catches up.
    case BuildBlockTxs(blockHeight, _, true, false, _, _) if rollupPreparation.preparedFor(blockHeight).isDefined =>
      sender() ! BlockTxsReady(blockHeight, rollupPreparation.preparedFor(blockHeight).get)

    // The report goes out before the bundles reach the package, so the stubs are held before any
    // job can carry them
    case BuildBlockTxs(blockHeight, stubs, answer, _, limit, reportTo) =>
      val alive = () => rollupAlive.get()
      val held = heldSubmissions.getOrElse(blockHeight, Map.empty[String, CandidateBundle])
      rollupPreparation.start(blockHeight, if (answer) Some(sender()) else None) {
        val build = candidateExecution(alive).candidates(stubs, blockHeight, limit, held)
        reportTo.foreach(_ ! CandidateStubsBuilt(blockHeight, stubs, build.built))
        build.bundles
      }
  }

  /**
   * Withhold every bond input offered for a height that can no longer land. Uncertain rather than
   * released: the transaction was never broadcast, so nothing resolves the box, and the block may
   * still have taken it.
   */
  private def reconcileCandidates(blockHeight: Int): Unit = {
    rollupPreparation.drop(blockHeight)
    heldSubmissions = heldSubmissions.filter(_._1 > blockHeight)
    candidateLeases.get(blockHeight).foreach { leases =>
      candidateLeases -= blockHeight
      leases.foreach(lease => self ! EngineWalletMessages.MarkReservationUncertain(lease))
    }
  }
}
