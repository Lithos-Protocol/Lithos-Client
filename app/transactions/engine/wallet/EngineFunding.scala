package transactions.engine.wallet
import transactions.engine.wallet.EngineWalletState

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mutations.NotEnoughInputsException
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * The one application-facing wallet selector, used by DEX, emission and rollup builds.
 *
 * It owns no state: [[EngineWalletState]] does, and every method here is a blocking ask against it.
 * `critical` routes the request to the reserved lane and higher input budget that NISP submissions
 * and fraud proofs depend on, so it is never set for optional or revenue work.
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
      (walletRef ? fundingRequest(SelectInputs(value, tokens, reservationId = reservationId,
        deadlineMillis = deadline))).mapTo[WalletInputs])
    val reservation = new FundingAllocation(reservationId, selected, this)
    if (!covers(selected, value, tokens)) {
      reservation.release()
      throw new NotEnoughInputsException(requirement(value, tokens))
    }
    reservation
  }

  override def reserveCovering(value: Long): FundingAllocation =
    reserveOneBox(value, "one input", p2pkOnly = false)

  override def reserveCoveringP2PK(value: Long): FundingAllocation =
    reserveOneBox(value, "one P2PK input", p2pkOnly = true)

  /** Both single-box requests differ only in which set the wallet draws from. */
  private def reserveOneBox(value: Long, description: String, p2pkOnly: Boolean): FundingAllocation = {
    if (value < 0)
      throw new IllegalArgumentException("Wallet requirements cannot be negative")
    val reservationId = UUID.randomUUID().toString
    val deadline = System.currentTimeMillis() + timeout.toMillis
    val selected = awaitReservation(
      reservationId,
      (walletRef ? fundingRequest(SelectInputs(value, reservationId = reservationId,
        deadlineMillis = deadline, single = true, p2pkOnly = p2pkOnly))).mapTo[WalletInputs])
    val reservation = new FundingAllocation(reservationId, selected, this)
    if (selected.isEmpty) {
      reservation.release()
      throw new NotEnoughInputsException(
        s"EngineWalletState could not reserve $description covering $value nanoERG")
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

  /** Recheck what came back actually covers the request, since the wallet may reply with less. */
  private def covers(inputs: Seq[InputUTXO], value: Long, tokens: Seq[Token]): Boolean = {
    val ergCovered = inputs.foldLeft(BigInt(0))((sum, box) => sum + box.value) >= BigInt(value)
    val requiredTokens = tokens.groupBy(_.id).map { case (id, requested) =>
      id -> requested.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    val heldTokens = inputs.flatMap(_.tokens).groupBy(_.id).map { case (id, held) =>
      id -> held.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    ergCovered && requiredTokens.forall {
      case (id, amount) => heldTokens.getOrElse(id, BigInt(0)) >= amount
    }
  }

  private def requirement(value: Long, tokens: Seq[Token]): String = {
    val tokenText = tokens.map(t => s"${t.amount} of ${t.id}").mkString(", ")
    s"EngineWalletState could not cover $value nanoERG" +
      (if (tokenText.isEmpty) "" else s" and $tokenText")
  }
}

object EngineFunding {
  // Funding can require context loading and several node reads before inputs can be reserved.
  final val AskTimeout: FiniteDuration = 30.seconds

  /**
   * The configured wait, for a caller that has the configuration to hand.
   *
   * Funding is serialised, so this bounds the queue behind a slow node read rather than the read
   * itself: a join asking per position is the case that reaches it first.
   */
  def askTimeout(config: play.api.Configuration): FiniteDuration =
    FiniteDuration(configs.WalletConfig(config).reservationTimeoutMs, MILLISECONDS)
}
