package transactions.wallet

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mutations.NotEnoughInputsException
import transactions.wallet.WalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * The one application-facing wallet selector.
 *
 * [[WalletManager]] remains the state owner; this class is the single adapter used by DEX,
 * emission, and rollup transactions for selection and reservation lifecycle messages.
 */
case class WalletSelector(walletRef: ActorRef,
                          timeout: FiniteDuration,
                          implicit val executionContext: ExecutionContext)
  extends WalletSource {

  private implicit val askTimeout: Timeout = Timeout(timeout)

  override def reserve(value: Long, tokens: Seq[Token]): WalletReservation = {
    if (value < 0 || tokens.exists(_.amount < 0))
      throw new IllegalArgumentException("Wallet requirements cannot be negative")
    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? RetrieveInputs(value, tokens, trackUsed = true, reservationId, deadline))
        .mapTo[WalletInputs])
    val reservation = new WalletReservation(reservationId, selected, this)
    if (!covers(selected, value, tokens)) {
      reservation.release()
      throw new NotEnoughInputsException(requirement(value, tokens))
    }
    reservation
  }

  override def reserveCovering(value: Long): WalletReservation =
    reserveOneBox(value, "one input")(RetrieveCoveringInput(_, trackUsed = true, _, _))

  override def reserveCoveringP2PK(value: Long): WalletReservation =
    reserveOneBox(value, "one P2PK input")(RetrieveCoveringP2PKInput(_, trackUsed = true, _, _))

  /** Both single-box requests differ only in which set the manager draws from. */
  private def reserveOneBox(value: Long, what: String)
                           (request: (Long, String, Long) => Any): WalletReservation = {
    if (value < 0)
      throw new IllegalArgumentException("Wallet requirements cannot be negative")
    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? request(value, reservationId, deadline)).mapTo[WalletInputs])
    val reservation = new WalletReservation(reservationId, selected, this)
    if (selected.isEmpty) {
      reservation.release()
      throw new NotEnoughInputsException(
        s"WalletManager could not reserve $what covering $value nanoERG")
    }
    reservation
  }

  override def reserveKnown(inputs: Seq[InputUTXO]): WalletReservation = {
    if (inputs.isEmpty)
      throw new IllegalArgumentException("A known-output reservation cannot be empty")
    val expectedIds = inputs.map(_.id.toString)
    if (expectedIds.distinct.size != expectedIds.size)
      throw new IllegalArgumentException("A known-output reservation cannot contain duplicate box ids")
    if (inputs.size > WalletManager.MAX_TX_INPUTS)
      throw new IllegalArgumentException(
        s"A known-output reservation cannot exceed ${WalletManager.MAX_TX_INPUTS} inputs")

    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? ReserveKnownInputs(inputs, reservationId, deadline)).mapTo[WalletInputs])
    val reservation = new WalletReservation(reservationId, selected, this)
    if (selected.map(_.id.toString).toSet != expectedIds.toSet) {
      reservation.release()
      throw new ReservationExpiredException(
        s"WalletManager could not reserve ${inputs.size} exact known output(s)")
    }
    reservation
  }

  override def giveBack(boxes: Seq[InputUTXO]): Unit = walletRef ! ReturnInputs(boxes)

  private[wallet] def releaseReservation(reservationId: String): Unit =
    walletRef ! ReleaseInputs(reservationId)

  private[wallet] def commitReservation(reservationId: String, outputs: Seq[InputUTXO]): Unit =
    walletRef ! CommitReservation(reservationId, outputs)

  private[wallet] def beginReservationSubmission(reservationId: String,
                                                 expectedInputIds: Set[String]): Boolean = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val reply = Await.result(
      (walletRef ? BeginReservationSubmission(reservationId, expectedInputIds, deadline))
        .mapTo[ReservationSubmissionStarted],
      timeout)
    if (reply.reservationId != reservationId)
      throw new ReservationExpiredException(
        s"WalletManager acknowledged reservation ${reply.reservationId}, expected $reservationId")
    reply.accepted
  }

  private[wallet] def cancelReservationSubmission(reservationId: String,
                                                   expectedInputIds: Set[String]): Boolean = {
    val request = CancelReservationSubmission(reservationId, expectedInputIds)
    try {
      val reply = Await.result(
        (walletRef ? request).mapTo[ReservationSubmissionCancelled],
        timeout)
      if (reply.reservationId != reservationId)
        throw new ReservationExpiredException(
          s"WalletManager cancelled reservation ${reply.reservationId}, expected $reservationId")
      reply.accepted
    } catch {
      case NonFatal(ex) =>
        // The cancel is exact-id validated and idempotent. Retrying without waiting prevents an
        // acknowledged-but-never-sent lease from remaining Submitting if only its reply was lost.
        walletRef ! request
        throw ex
    }
  }

  private[wallet] def holdReservationForCandidate(reservationId: String): Boolean = {
    val reply = Await.result(
      (walletRef ? HoldReservationForCandidate(reservationId)).mapTo[ReservationHeldForCandidate],
      timeout)
    if (reply.reservationId != reservationId)
      throw new ReservationExpiredException(
        s"WalletManager held reservation ${reply.reservationId}, expected $reservationId")
    reply.accepted
  }

  private[wallet] def markReservationUncertain(reservationId: String): Unit =
    walletRef ! MarkReservationUncertain(reservationId)

  private def awaitReservation(reservationId: String,
                               result: Future[WalletInputs]): Seq[InputUTXO] =
    try {
      val reply = Await.result(result, timeout)
      if (reply.reservationId != reservationId)
        throw new ReservationExpiredException(
          s"WalletManager replied for reservation ${reply.reservationId}, expected $reservationId")
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
    s"WalletManager could not cover $value nanoERG" +
      (if (tokenText.isEmpty) "" else s" and $tokenText")
  }
}

object WalletSelector {
  final val AskTimeout: FiniteDuration = 10.seconds
}
