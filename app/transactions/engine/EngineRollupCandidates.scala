package transactions.engine

import akka.actor.{Actor, ActorRef}
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped}
import transactions.rollups.TransactionMessages.BuildBlockTxs
import transactions.engine.RollupExecution._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/** Candidate attempts and their funding holds are serialized by the engine mailbox. */
trait EngineRollupCandidates extends Actor {
  protected def candidateExecution(alive: () => Boolean): RollupExecution
  private implicit val candidateEc: ExecutionContext = context.dispatcher
  private val candidateWorker = context.system.dispatchers.lookup("lithos-contexts.engine-candidate-dispatcher")
  /** The finished build for one height, held until that height is asked for or dropped. */
  private var prepared = Option.empty[(Int, Seq[transactions.CandidateBundle])]
  /** The height being built for and which attempt is building it, so a superseded one is discarded. */
  private var buildingFor = Option.empty[(Int, java.util.UUID)]
  /** Requesters that arrived while the build for their height was still running. */
  private var waiting = Vector.empty[(ActorRef, Int)]
  private val rollupIncarnation = java.util.UUID.randomUUID()
  private val rollupAlive = new java.util.concurrent.atomic.AtomicBoolean(true)

  /**
   * Bond inputs withheld for fee-less submissions offered into this miner's own block, by the
   * height they were built for. They are never broadcast, so only a terminal event for that height
   * can end them, and it ends them as uncertain rather than free: the block may have taken them.
   */
  private var candidateLeases: Map[Int, Seq[String]] = Map.empty

  /** The most recent height a candidate package was built for, so a late lease is not stranded. */
  private var latestCandidateHeight: Int = 0

  abstract override def postStop(): Unit = {
    rollupAlive.set(false)
    candidateLeases.keys.toSeq.foreach(reconcileCandidates)
    super.postStop()
  }

  abstract override def receive: Receive = rollupReceive.orElse(super.receive)

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

    // A block is being assembled: build the same work fee-less and hand it back without
    // sending. The stubs stay queued so the funded copies still reach the mempool.
    case BuildBlockTxs(blockHeight, stubs, answer) if stubs.size > 100 =>
      if (answer) sender() ! BlockTxsReady(blockHeight, Seq.empty)

    // Already built for this height, so the request costs nothing but the reply.
    case BuildBlockTxs(blockHeight, _, true) if prepared.exists(_._1 == blockHeight) =>
      sender() ! BlockTxsReady(blockHeight, prepared.get._2)

    case BuildBlockTxs(blockHeight, stubs, answer) =>
      // A requester arriving mid-build waits for it rather than being told there is nothing: the
      // whole point of preparing early is that this work is ready, or nearly so.
      if (answer) waiting :+= (sender() -> blockHeight)
      if (!buildingFor.exists(_._1 == blockHeight)) {
        val build = java.util.UUID.randomUUID()
        buildingFor = Some(blockHeight -> build)
        prepared = None
        // A height that never got its own drop signal is over the moment a later one starts.
        latestCandidateHeight = math.max(latestCandidateHeight, blockHeight)
        candidateLeases.keys.filter(_ < blockHeight).toSeq.foreach(reconcileCandidates)
        Try(Future {
          candidateExecution(() => rollupAlive.get()).candidates(stubs, blockHeight)
        }(candidateWorker).onComplete(result =>
          self ! RollupCandidateBuilt(rollupIncarnation, build, blockHeight, result)))
          .failed.foreach(ex => self ! RollupCandidateBuilt(rollupIncarnation, build, blockHeight, Failure(ex)))
      }

    case RollupCandidateBuilt(incarnation, build, height, result) if incarnation == rollupIncarnation =>
      // Kept only while this is still the build that is wanted. One whose height was dropped mid-run
      // built for a package that will not be published, and offering it again to a replacement
      // repeats whatever the node refused.
      if (buildingFor.exists(_._2 == build)) {
        buildingFor = None
        prepared = Some(height -> result.getOrElse(Seq.empty))
      }
      // Requesters waiting on a build that no longer exists would otherwise hang until their ask
      // expires; ones waiting on a replacement for the same height are left to it.
      if (!buildingFor.exists(_._1 == height)) {
        val bundles = prepared.filter(_._1 == height).map(_._2).getOrElse(Seq.empty)
        val (ready, rest) = waiting.partition(_._2 == height)
        waiting = rest
        ready.foreach { case (replyTo, _) => replyTo ! BlockTxsReady(height, bundles) }
      }
    case _: RollupCandidateBuilt => ()
  }

  /**
   * Withhold every bond input offered for a height that can no longer land. Uncertain rather than
   * released: the transaction was never broadcast, so nothing resolves the box, and the block may
   * still have taken it.
   */
  private def reconcileCandidates(blockHeight: Int): Unit = {
    // Work prepared for a height that can no longer land is not offered to a later one, and a build
    // still running for such a height stops being the one whose result is kept.
    if (prepared.exists(_._1 <= blockHeight)) prepared = None
    if (buildingFor.exists(_._1 <= blockHeight)) buildingFor = None
    candidateLeases.get(blockHeight).foreach { leases =>
      candidateLeases -= blockHeight
      leases.foreach(lease => self ! EngineWalletMessages.MarkReservationUncertain(lease))
    }
  }
}
