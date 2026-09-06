package transactions.engine

import api.models._
import cache.LDCache

sealed trait DexIntent {
  def execute(execution: DexExecution, cache: LDCache): Any
}

object DexIntent {
  /** Short-circuit before queuing oversized request strings, without rendering a copy. */
  private[engine] def fitsBudget(intent: DexIntent, maxBytes: Long = 32768L): Boolean = {
    var remaining = maxBytes
    def visit(value: Any): Boolean = {
      remaining -= 32
      if (remaining < 0) false
      else value match {
        case s: String => remaining -= s.length.toLong * 2; remaining >= 0
        case p: Product => p.productIterator.forall(visit)
        case _ => true
      }
    }
    visit(intent)
  }
  final case class Swap(request: LDSwapExecuteRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDSwapResult = e.swap(request, c)
  }
  final case class Deposit(request: LDDepositExecuteRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDDepositResult = e.deposit(request, c)
  }
  final case class Redeem(request: LDRedeemRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDRedeemResult = e.redeem(request, c)
  }
  final case class Claim(boxId: String, request: LDClaimRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDClaimResult = e.claimProvision(boxId, request, c)
  }
  final case class Resize(boxId: String, request: LDResizeRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDResizeResult = e.resize(boxId, request, c)
  }
  final case class Flush(request: LDFlushRequest) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): LDFlushResult = e.flush(request, c)
  }
  final case class Refresh(boxId: String) extends DexIntent {
    def execute(e: DexExecution, c: LDCache): Refreshed = e.refreshProvision(boxId)
  }
  final case class Refreshed(boxId: String, txId: String, outcome: String)
}
