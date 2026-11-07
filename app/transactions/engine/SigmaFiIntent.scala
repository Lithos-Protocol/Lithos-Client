package transactions.engine

import api.models.{SFOrderRequest, SFResult}
import transactions.engine.execution.SigmaFiAPIExecution

/**
 * One SigmaFi operation that signs, carried to the engine as an ordinary intent. Reads and checks never touch
 * the wallet and are served without queuing.
 */
sealed trait SigmaFiIntent {
  def execute(execution: SigmaFiAPIExecution): SFResult
}

object SigmaFiIntent {

  private def id(s: String): Boolean = s.matches("[0-9a-fA-F]{64}")

  /** Whether the request is small enough to queue: ids of 64 hex characters, and short request fields. */
  private[engine] def admissible(intent: SigmaFiIntent): Boolean = intent match {
    case CreateOrder(r) =>
      r.collateralTokens.forall(_.size <= 32) && (Seq(r.collateralNanoErgs, r.repayment) ++ r.principalNanoErgs ++
        r.batchingFee ++ r.liquidation).forall(_.length <= 64)
    case CancelOrder(b) => id(b)
    case FillOrder(b) => id(b)
    case Repay(b) => id(b)
    case Liquidate(b) => id(b)
    case Claim(t) => id(t)
  }

  private def sent(answer: Either[_, SFResult]): SFResult =
    answer.fold(_ => throw new IllegalStateException("a send answered with a quote"), identity)

  final case class CreateOrder(request: SFOrderRequest) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.createOrder(request, send = true))
  }
  final case class CancelOrder(boxId: String) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.cancelOrder(boxId, send = true))
  }
  final case class FillOrder(boxId: String) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.fillOrder(boxId, send = true))
  }
  final case class Repay(boxId: String) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.repay(boxId, send = true))
  }
  final case class Liquidate(boxId: String) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.liquidate(boxId, send = true))
  }
  final case class Claim(tokenId: String) extends SigmaFiIntent {
    def execute(e: SigmaFiAPIExecution): SFResult = sent(e.claim(tokenId, send = true))
  }
}
