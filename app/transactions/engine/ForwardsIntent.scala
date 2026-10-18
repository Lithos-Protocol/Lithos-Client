package transactions.engine

import api.models.DFOfferRequest
import transactions.engine.execution.ForwardsAPIExecution

/**
 * One difficulty forward operation that signs, carried to the engine as an ordinary intent. Reads and quotes
 * never touch the wallet and are served without queuing.
 */
sealed trait ForwardsIntent {
  def execute(execution: ForwardsAPIExecution): Any
}

object ForwardsIntent {

  /** Whether the request is small enough to queue: box ids of 64 hex characters, and short request fields. */
  private[engine] def admissible(intent: ForwardsIntent): Boolean = intent match {
    case CreateOffer(r) =>
      Seq(r.makerSide, r.strike, r.notional).forall(_.length <= 64)
    case Fill(boxId) => boxId.matches("[0-9a-fA-F]{64}")
    case Cancel(boxId) => boxId.matches("[0-9a-fA-F]{64}")
    case Settle(boxId) => boxId.matches("[0-9a-fA-F]{64}")
  }

  final case class CreateOffer(request: DFOfferRequest) extends ForwardsIntent {
    def execute(e: ForwardsAPIExecution): Any = e.createOffer(request)
  }
  final case class Fill(boxId: String) extends ForwardsIntent {
    def execute(e: ForwardsAPIExecution): Any = e.fill(boxId)
  }
  final case class Cancel(boxId: String) extends ForwardsIntent {
    def execute(e: ForwardsAPIExecution): Any = e.cancel(boxId)
  }
  final case class Settle(boxId: String) extends ForwardsIntent {
    def execute(e: ForwardsAPIExecution): Any = e.settle(boxId)
  }
}
