package transactions.candidate

import akka.actor.{Actor, ActorRef}
import transactions.candidate.BlockTxMessages.BlockTxsReady

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/**
 * Building a candidate source's work when the height is first known rather than when the package is
 * asked for.
 *
 * Every source is asked twice for a block: once to prepare, as soon as the height is known, and
 * again to collect, once genesis has reached miners. Without the first, a source's signing and node
 * reads all happen inside the collection deadline, where one slow source costs the block its extras.
 *
 * Three states are kept between them. A finished build is held until its height is asked for or
 * dropped; a running build is named by an attempt rather than by its height, so one whose height was
 * dropped mid-run cannot answer the replacement that follows it; and requesters that arrive while a
 * build is running wait for it rather than being told there is nothing, which is the whole point of
 * having started early.
 *
 * Held rather than inherited, because one actor may be two sources: [[transactions.engine.TransactionEngine]]
 * answers for rollups and emissions both, and a shared set of these fields would let one source's
 * build discard the other's.
 *
 * Every method belongs to the actor's own thread. Only `build` does not, which is why it is taken by
 * name and must close over values rather than read the actor's fields.
 *
 * @param owner   the actor to deliver completions to
 * @param onStart bookkeeping the source ties to the start of a build, run on the actor's thread
 */
final class CandidatePreparation(worker: ExecutionContext,
                                 owner: ActorRef,
                                 onStart: Int => Unit = _ => ()) {

  import CandidatePreparation._

  /** Distinguishes this source's completions from those of another sharing the same mailbox. */
  private val lane: UUID = UUID.randomUUID()

  /** The finished build for one height, held until that height is asked for or dropped. */
  private var prepared = Option.empty[(Int, Seq[CandidateBundle])]

  /** The height being built for and which attempt is building it, so a superseded one is discarded. */
  private var buildingFor = Option.empty[(Int, UUID)]

  /** Requesters that arrived while the build for their height was still running. */
  private var waiting = Vector.empty[(ActorRef, Int)]

  /**
   * Completions of this source's builds, to be chained into the actor's own receive.
   *
   * A result is kept only while its attempt is still the one wanted. One whose height was dropped
   * while it ran built for a package that will not be published, and offering it to a replacement
   * repeats whatever the node refused.
   */
  def receive: Actor.Receive = {
    case Prepared(id, attempt, height, result) if id == lane =>
      if (buildingFor.exists(_._2 == attempt)) {
        buildingFor = None
        prepared = Some(height -> result.getOrElse(Seq.empty[CandidateBundle]))
      }
      // Requesters waiting on a build that no longer exists would otherwise hang until their ask
      // expires; ones waiting on a replacement for the same height are left to it.
      if (!buildingFor.exists(_._1 == height)) {
        val bundles = preparedFor(height).getOrElse(Seq.empty[CandidateBundle])
        val (ready, rest) = waiting.partition(_._2 == height)
        waiting = rest
        ready.foreach { case (replyTo, _) => replyTo ! BlockTxsReady(height, bundles) }
      }
  }

  /** What was built for this height, if it was this height that was built for. */
  def preparedFor(blockHeight: Int): Option[Seq[CandidateBundle]] =
    prepared.filter(_._1 == blockHeight).map(_._2)

  /**
   * Start building for this height, or join the build already running for it.
   *
   * A `replyTo` is answered once the build finishes; preparation passes none, because preparing is
   * not a request and nothing is waiting on it.
   */
  def start(blockHeight: Int, replyTo: Option[ActorRef])(build: => Seq[CandidateBundle]): Unit = {
    replyTo.foreach(r => waiting :+= (r -> blockHeight))
    if (!buildingFor.exists(_._1 == blockHeight)) {
      val attempt = UUID.randomUUID()
      buildingFor = Some(blockHeight -> attempt)
      prepared = None
      onStart(blockHeight)
      implicit val ec: ExecutionContext = worker
      Try(Future(build).onComplete(result =>
        owner ! Prepared(lane, attempt, blockHeight, result)))
        .failed.foreach(ex => owner ! Prepared(lane, attempt, blockHeight, Failure(ex)))
    }
  }

  /** Answer a requester at once, for a source with nothing to offer this block. */
  def answerEmpty(blockHeight: Int, replyTo: Option[ActorRef]): Unit =
    replyTo.foreach(_ ! BlockTxsReady(blockHeight, Seq.empty[CandidateBundle]))

  /**
   * Forget what was prepared for a height that can no longer land, and stop the build still running
   * for one. Heights below it go too: a source is only ever asked about the block being mined.
   */
  def drop(blockHeight: Int): Unit = {
    if (prepared.exists(_._1 <= blockHeight)) prepared = None
    if (buildingFor.exists(_._1 <= blockHeight)) buildingFor = None
  }
}

object CandidatePreparation {
  /** Self-message: one source's build finished off the actor thread. */
  private[candidate] final case class Prepared(lane: UUID, attempt: UUID, height: Int,
                                               result: Try[Seq[CandidateBundle]])
}
