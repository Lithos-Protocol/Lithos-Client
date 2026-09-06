package transactions.engine
import transactions.engine.EngineWalletState

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mutations.NotEnoughInputsException
import transactions.engine.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * The one application-facing wallet selector.
 *
 * [[EngineWalletState]] remains the state owner; this class is the single adapter used by DEX,
 * emission, and rollup transactions for selection and reservation lifecycle messages.
 */
case class EngineFunding(walletRef: ActorRef,
                          timeout: FiniteDuration,
                          implicit val executionContext: ExecutionContext,
                          critical: Boolean = false)
  extends FundingSource {

  private implicit val askTimeout: Timeout = Timeout(timeout)
  private def fundingRequest(request: Any): Any = if (critical) CriticalWalletRequest(request) else request

  override def reserve(value: Long, tokens: Seq[Token]): FundingAllocation = {
    if (value < 0 || tokens.exists(_.amount < 0))
      throw new IllegalArgumentException("Wallet requirements cannot be negative")
    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? fundingRequest(RetrieveInputs(value, tokens, trackUsed = true, reservationId, deadline)))
        .mapTo[WalletInputs])
    val reservation = new FundingAllocation(reservationId, selected, this)
    if (!covers(selected, value, tokens)) {
      reservation.release()
      throw new NotEnoughInputsException(requirement(value, tokens))
    }
    reservation
  }

  override def reserveCovering(value: Long): FundingAllocation =
    reserveOneBox(value, "one input")(RetrieveCoveringInput(_, trackUsed = true, _, _))

  override def reserveCoveringP2PK(value: Long): FundingAllocation =
    reserveOneBox(value, "one P2PK input")(RetrieveCoveringP2PKInput(_, trackUsed = true, _, _))

  /** Both single-box requests differ only in which set the manager draws from. */
  private def reserveOneBox(value: Long, what: String)
                           (request: (Long, String, Long) => Any): FundingAllocation = {
    if (value < 0)
      throw new IllegalArgumentException("Wallet requirements cannot be negative")
    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? fundingRequest(request(value, reservationId, deadline))).mapTo[WalletInputs])
    val reservation = new FundingAllocation(reservationId, selected, this)
    if (selected.isEmpty) {
      reservation.release()
      throw new NotEnoughInputsException(
        s"EngineWalletState could not reserve $what covering $value nanoERG")
    }
    reservation
  }

  override def reserveKnown(inputs: Seq[InputUTXO]): FundingAllocation = {
    if (inputs.isEmpty)
      throw new IllegalArgumentException("A known-output reservation cannot be empty")
    val expectedIds = inputs.map(_.id.toString)
    if (expectedIds.distinct.size != expectedIds.size)
      throw new IllegalArgumentException("A known-output reservation cannot contain duplicate box ids")
    if (inputs.size > EngineWalletState.MAX_TX_INPUTS)
      throw new IllegalArgumentException(
        s"A known-output reservation cannot exceed ${EngineWalletState.MAX_TX_INPUTS} inputs")

    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? fundingRequest(ReserveKnownInputs(inputs, reservationId, deadline))).mapTo[WalletInputs])
    val reservation = new FundingAllocation(reservationId, selected, this)
    if (selected.map(_.id.toString).toSet != expectedIds.toSet) {
      reservation.release()
      throw new FundingExpiredException(
        s"EngineWalletState could not reserve ${inputs.size} exact known output(s)")
    }
    reservation
  }

  override def giveBack(boxes: Seq[InputUTXO]): Unit = walletRef ! ReturnInputs(boxes)

  private[engine] def releaseReservation(reservationId: String): Unit =
    walletRef ! ReleaseInputs(reservationId)

  private[engine] def holdReservationForCandidate(reservationId: String): Boolean = {
    val reply = Await.result(
      (walletRef ? HoldReservationForCandidate(reservationId)).mapTo[ReservationHeldForCandidate],
      timeout)
    if (reply.reservationId != reservationId)
      throw new FundingExpiredException(
        s"EngineWalletState held reservation ${reply.reservationId}, expected $reservationId")
    reply.accepted
  }

  private[engine] def markReservationUncertain(reservationId: String): Unit =
    walletRef ! MarkReservationUncertain(reservationId)

  private def awaitReservation(reservationId: String,
                               result: Future[WalletInputs]): Seq[InputUTXO] =
    try {
      val reply = Await.result(result, timeout)
      if (reply.reservationId != reservationId)
        throw new FundingExpiredException(
          s"EngineWalletState replied for reservation ${reply.reservationId}, expected $reservationId")
      reply.inputs
    } catch {
      case NonFatal(ex) =>
        // The request carries a deadline, so if it is still queued it cannot reserve after timing
        // out. If it won the race and already reserved, this id-specific release clears only it.
        walletRef ! ReleaseInputs(reservationId)
        throw ex
    }

  private def covers(inputs: Seq[InputUTXO], value: Long, tokens: Seq[Token]): Boolean = {
    val ergCovered = inputs.foldLeft(BigInt(0))((sum, box) => sum + box.value) >= BigInt(value)
    val required = tokens.groupBy(_.id).map { case (id, entries) =>
      id -> entries.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    val held = inputs.flatMap(_.tokens).groupBy(_.id).map { case (id, entries) =>
      id -> entries.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    ergCovered && required.forall { case (id, amount) => held.getOrElse(id, BigInt(0)) >= amount }
  }

  private def requirement(value: Long, tokens: Seq[Token]): String = {
    val tokenText = tokens.map(t => s"${t.amount} of ${t.id}").mkString(", ")
    s"EngineWalletState could not cover $value nanoERG" +
      (if (tokenText.isEmpty) "" else s" and $tokenText")
  }
}

object EngineFunding {
  final val AskTimeout: FiniteDuration = 10.seconds
}
