package contracts.specs.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.LDOrderContracts
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/** LD_SwapSellOrder and LD_SwapBuyOrder, executed against the real pool. */
class LDSwapOrderSpec extends AnyPropSpec with LDOrderSpecBase {

  import LithosDexSpecBase._

  private val REWARD = 1
  private val TAKINGS = 2
  private val FUNDING = 2

  private val otherOps: Seq[Byte] =
    Seq(LDHelpers.POOL_DEPOSIT, LDHelpers.POOL_REDEEM, LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE)

  private def tokensY(n: Long): Seq[Token] = if (n > 0) Seq(Token(tokenY, n)) else Seq.empty[Token]

  // ═══ SELL ═════════════════════════════════════════════════════════════════

  property("sell: a known-good execution is accepted") {
    withCtx { ctx =>
      accepts(executor(ctx), sellScenario(ctx).tx)
    }
  }

  property("sell: the owner may be paid one unit under the pool's output, never two (fairPrice)") {
    withCtx { ctx =>
      val e = sellScenario(ctx, minQuote = 1L)
      val s = e.before
      val base = Parameters.OneErg
      val out = s.view.simSwap(base, ergIn = true).amountOut
      val floor = fairFloor(s.reservesX, s.reservesY, base, FEE_PARAMS(1))
      floor should (be(out) or be(out - 1))
      def paying(toOwner: Long) = e
        .output(REWARD, rewardUTXO(ctx, REWARD_ERG, Seq(Token(tokenY, toOwner))))
        .output(TAKINGS, takingsUTXO(ctx, FEE, tokensY(out - toOwner)))
      accepts(executor(ctx), paying(floor).tx)
      rejectsAtSigning(executor(ctx), paying(floor - 1).tx)
    }
  }

  property("sell: the owner's minimum is inclusive (minQuote)") {
    withCtx { ctx =>
      val out = traded.view.simSwap(Parameters.OneErg, ergIn = true).amountOut
      accepts(executor(ctx), sellScenario(ctx, minQuote = out).tx)
      rejectsAtSigning(executor(ctx), sellScenario(ctx, minQuote = out + 1).tx)
    }
  }

  property("sell: rejects keeping one nanoERG of the owner's change") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).subValue(1L))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(1L)).tx)
    }
  }

  property("sell: rejects a reward paid to anyone but the owner") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("sell: rejects a reward in some other token") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      val out = e.outputs(REWARD).tokens.head.amount
      rejectsAtSigning(executor(ctx), e
        .input(FUNDING, executorFunding(ctx, 2, Seq(Token(unrelatedToken, out))))
        .output(REWARD, rewardUTXO(ctx, REWARD_ERG, Seq(Token(unrelatedToken, out))))
        .output(TAKINGS, takingsUTXO(ctx, FEE, tokensY(out))).tx)
    }
  }

  property("sell: rejects a miner fee above the order's cap") {
    withCtx { ctx =>
      accepts(executor(ctx), sellScenario(ctx, maxMinerFee = Parameters.MinFee).tx)
      rejectsAtSigning(executor(ctx), sellScenario(ctx, maxMinerFee = Parameters.MinFee - 1).tx)
    }
  }

  property("sell: refuses to execute from any input but 1") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(2), e.inputs(1))).tx)
    }
  }

  property("sell: refuses a pool other than the one it names") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      val elsewhere = LDOrderContracts.swapSell(terms(ctx, poolNFTId = unrelatedToken),
        Parameters.OneErg, e.outputs(REWARD).tokens.head.amount)
      rejectsAtSigning(executor(ctx),
        e.input(1, inputAt(UTXO(elsewhere, Parameters.OneErg + FEE + REWARD_ERG), ctx, 1)).tx)
    }
  }

  property("sell: two identical orders cannot share one reward (double satisfaction)") {
    withCtx { ctx =>
      val e = sellScenario(ctx)
      val twin = inputAt(e.inputs(1).toUTXO, ctx, 9)
      rejectsAtSigning(executor(ctx), e
        .copy(inputs = e.inputs.take(2) ++ Seq(twin) ++ e.inputs.drop(2))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(twin.value)).tx)
    }
  }

  property("sell: accepts only the pool's swap operation (stand-in pool isolates the order)") {
    withCtx { ctx =>
      accepts(executor(ctx), sellScenario(ctx, setup = PoolSetup(standIn = true)).tx)
      otherOps.foreach { op =>
        rejectsAtSigning(executor(ctx), sellScenario(ctx, setup = PoolSetup(standIn = true, op = Some(op))).tx)
      }
      rejectsAtSigning(executor(ctx), sellScenario(ctx, setup = PoolSetup(standIn = true, op = None)).tx)
    }
  }

  // ═══ BUY ══════════════════════════════════════════════════════════════════

  property("buy: a known-good execution is accepted") {
    withCtx { ctx =>
      accepts(executor(ctx), buyScenario(ctx).tx)
    }
  }

  property("buy: the owner may be paid one nanoERG under the pool's output, never two (fairPrice)") {
    withCtx { ctx =>
      val e = buyScenario(ctx, minQuote = 1L)
      val s = e.before
      val base = 2000L * 1000000L
      val out = s.view.simSwap(base, ergIn = false).amountOut
      val floor = fairFloor(s.reservesY, s.reservesX, base, FEE_PARAMS(2))
      floor should (be(out) or be(out - 1))
      def paying(toOwner: Long) = e
        .output(REWARD, rewardUTXO(ctx, REWARD_ERG + toOwner))
        .output(TAKINGS, takingsUTXO(ctx, out - toOwner))
      accepts(executor(ctx), paying(floor - FEE).tx)
      rejectsAtSigning(executor(ctx), paying(floor - FEE - 1).tx)
    }
  }

  property("buy: the owner's minimum is inclusive (minQuote)") {
    withCtx { ctx =>
      val out = traded.view.simSwap(2000L * 1000000L, ergIn = false).amountOut
      accepts(executor(ctx), buyScenario(ctx, minQuote = out - FEE).tx)
      rejectsAtSigning(executor(ctx), buyScenario(ctx, minQuote = out - FEE + 1).tx)
    }
  }

  property("buy: rejects an order holding some other token") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), buyScenario(ctx, orderToken = unrelatedToken).tx)
    }
  }

  property("buy: rejects a reward paid to anyone but the owner") {
    withCtx { ctx =>
      val e = buyScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("buy: rejects a miner fee above the order's cap") {
    withCtx { ctx =>
      accepts(executor(ctx), buyScenario(ctx, maxMinerFee = Parameters.MinFee).tx)
      rejectsAtSigning(executor(ctx), buyScenario(ctx, maxMinerFee = Parameters.MinFee - 1).tx)
    }
  }

  property("buy: refuses to execute from any input but 1") {
    withCtx { ctx =>
      val e = buyScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(2), e.inputs(1))).tx)
    }
  }

  property("buy: accepts only the pool's swap operation (stand-in pool isolates the order)") {
    withCtx { ctx =>
      accepts(executor(ctx), buyScenario(ctx, setup = PoolSetup(standIn = true)).tx)
      otherOps.foreach { op =>
        rejectsAtSigning(executor(ctx), buyScenario(ctx, setup = PoolSetup(standIn = true, op = Some(op))).tx)
      }
      rejectsAtSigning(executor(ctx), buyScenario(ctx, setup = PoolSetup(standIn = true, op = None)).tx)
    }
  }
}
