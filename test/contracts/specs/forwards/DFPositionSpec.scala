package contracts.specs.forwards

import forwards.{DFHelpers, DFMath}
import forwards.contracts.DFTerms
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, UnsignedTransaction}
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * DF_Position: once the accumulator holds the whole window, the long side gets its margin plus the clamped
 * payout and the short side its margin less it, or both get their margins back if the window has left the
 * ring. Either way 2 LIT reach the accumulator, net of any recording reward taken in the same spend.
 *
 * The settler signs every settlement: the accumulator and the position both reduce to true.
 */
class DFPositionSpec extends AnyPropSpec with DFSpecBase {

  import DFSpecBase._

  private val BALANCE: Long = 100L * LIT

  /** A ring whose slot for epoch e holds `f(e)`, with `lastEpoch` the newest epoch it holds. */
  private def ringOf(lastEpoch: Int)(f: Int => Long): IndexedSeq[Long] =
    (0 until RING_SIZE).map(i => f(lastEpoch - (lastEpoch - i) % RING_SIZE))

  /** Difficulty `windowValue` inside the window, the strike everywhere else. */
  private def windowAt(t: DFTerms, windowValue: Int => Long): Int => Long =
    e => if (e >= t.windowStart && e < t.windowStart + t.windowLength) windowValue(e) else t.strike

  private def moved(strike: Long, percent: Long): Long = strike + strike * percent / 100

  /** One settlement. Negatives replace one input or output and rebuild. */
  private case class Settle(ctx: BlockchainContext, t: DFTerms, before: AccState, after: AccState,
                            owed: (Long, Long), inputs: Seq[InputUTXO], outputs: Seq[UTXO]) {
    def prover: ErgoProver = settler(ctx)
    def input(i: Int, in: InputUTXO): Settle = copy(inputs = inputs.updated(i, in))
    def output(i: Int, out: UTXO): Settle = copy(outputs = outputs.updated(i, out))
    def tx: UnsignedTransaction = build(ctx, inputs, outputs, prover.getAddress)
    def accOut: UTXO = outputs.head
  }

  /**
   * Settles a long-maker position whose window is `t`, against an accumulator last recorded at `lastEpoch`.
   * With `record`, the accumulator was last recorded one epoch before the last block's and this spend
   * records it, taking the reward. The long payout is OUTPUTS(1) and the short OUTPUTS(2); a side owed
   * nothing gets no output, and the settler's reward box fills OUTPUTS(1) if the long side is the one.
   */
  private def settleScenario(ctx: BlockchainContext,
                             t: DFTerms,
                             windowValue: Int => Long,
                             lastEpoch: Int = fixtureEpoch,
                             record: Boolean = false): Settle = {
    val ringEpoch = if (record) fixtureEpoch - 1 else lastEpoch
    val before = AccState(ringEpoch, ringOf(ringEpoch)(windowAt(t, windowValue)), BALANCE)
    val after =
      if (record) afterRecord(before, fixtureEpoch, difficultyOf(fixtureNBits), RECORD_REWARD)
      else before
    val deposited = after.copy(lit = after.lit + ACC_DEPOSIT)
    val owed = DFMath.owed(deposited.ring, deposited.lastEpoch, t.windowStart, t.windowLength, t.strike, t.notional)

    val long = maker(ctx)
    val short = taker(ctx)
    val reward = UTXO(contractOf(settler(ctx)), PAYOUT_ERG, Seq(Token(litId, DFHelpers.SETTLE_REWARD)))
    val outputs = Seq(accUTXO(deposited), if (owed._1 > 0) payoutUTXO(long, owed._1) else reward) ++
      (if (owed._2 > 0) Seq(payoutUTXO(short, owed._2)) else Seq.empty[UTXO])

    Settle(ctx, t, before, deposited, owed,
      Seq(inputAt(accUTXO(before), ctx, 0), inputAt(positionUTXO(long, short, t), ctx, 1),
        funding(ctx, settler(ctx), 2)),
      outputs)
  }

  /** A window of 8 that ended 13 epochs ago, so it is complete and well inside the ring. */
  private val endedWindow: DFTerms = terms(fixtureEpoch - 20, 8)

  private def settle[A](percent: Long, t: DFTerms = endedWindow)(f: Settle => A): A =
    withCtx(ctx => f(settleScenario(ctx, t, _ => moved(t.strike, percent))))

  private val M: Long = NOTIONAL / 5

  // ─── settlement ───────────────────────────────────────────────────────────

  property("settle: difficulty 8% above the strike pays the long side 8% of notional") {
    settle(8) { s =>
      s.owed shouldBe ((M + NOTIONAL * 8 / 100, M - NOTIONAL * 8 / 100))
      accepts(s.prover, s.tx)
    }
  }

  property("settle: difficulty 8% below the strike pays the short side 8% of notional") {
    settle(-8) { s =>
      s.owed shouldBe ((M - NOTIONAL * 8 / 100, M + NOTIONAL * 8 / 100))
      accepts(s.prover, s.tx)
    }
  }

  property("settle: a mean exactly at the strike returns both margins") {
    settle(0) { s =>
      s.owed shouldBe ((M, M))
      accepts(s.prover, s.tx)
    }
  }

  property("settle: the mean is taken over the window, not per epoch") {
    withCtx { ctx =>
      // Half the window 10% up and half 6% down averages to 2% up
      val t = endedWindow
      val s = settleScenario(ctx, t, e => moved(t.strike, if (e < t.windowStart + 4) 10 else -6))
      s.owed shouldBe ((M + NOTIONAL * 2 / 100, M - NOTIONAL * 2 / 100))
      accepts(s.prover, s.tx)
    }
  }

  property("settle: rejects paying the long side one unit less") {
    settle(8) { s =>
      rejectsAtSigning(s.prover, s.output(1, payoutUTXO(maker(s.ctx), s.owed._1 - 1)).tx)
    }
  }

  property("settle: rejects paying the short side one unit less") {
    settle(-8) { s =>
      rejectsAtSigning(s.prover, s.output(2, payoutUTXO(taker(s.ctx), s.owed._2 - 1)).tx)
    }
  }

  property("settle: rejects paying either side to the other's key") {
    settle(8) { s =>
      rejectsAtSigning(s.prover, s.output(1, payoutUTXO(taker(s.ctx), s.owed._1)).tx)
      rejectsAtSigning(s.prover, s.output(2, payoutUTXO(maker(s.ctx), s.owed._2)).tx)
    }
  }

  property("settle: rejects a payout in a token other than LIT") {
    withCtx { ctx =>
      val s = settleScenario(ctx, endedWindow, _ => moved(STRIKE, 8))
      val withOther = s.input(2, funding(ctx, settler(ctx), 2, tokens = Seq(Token(unrelatedToken, s.owed._1))))
      rejectsAtSigning(s.prover, withOther.output(1, payoutUTXO(maker(ctx), s.owed._1, unrelatedToken)).tx)
    }
  }

  property("settle: rejects paying both margins back while the window is still in the ring") {
    settle(8) { s =>
      rejectsAtSigning(s.prover, s.output(1, payoutUTXO(maker(s.ctx), M)).output(2, payoutUTXO(taker(s.ctx), M)).tx)
    }
  }

  // ─── clamp ────────────────────────────────────────────────────────────────

  property("clamp: exactly +20% pays the long side the whole short margin, and the short side needs no output") {
    settle(20) { s =>
      s.owed shouldBe ((2 * M, 0L))
      s.outputs should have size 2
      accepts(s.prover, s.tx)
    }
  }

  property("clamp: +30% pays no more than +20%") {
    settle(30) { s =>
      s.owed shouldBe ((2 * M, 0L))
      accepts(s.prover, s.tx)
    }
  }

  property("clamp: -30% pays the short side the whole long margin, and OUTPUTS(1) is unconstrained") {
    settle(-30) { s =>
      s.owed shouldBe ((0L, 2 * M))
      s.outputs(1).contract shouldBe contractOf(settler(s.ctx))
      accepts(s.prover, s.tx)
    }
  }

  // ─── truncation ───────────────────────────────────────────────────────────

  property("truncation: a fractional payout rounds toward zero on both sides, and is exact to the unit") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - 20, 7, strike = 1000003L, notional = 7777777777777L)
      Seq(1000172L, 999833L).foreach { d =>
        val s = settleScenario(ctx, t, _ => d)
        val raw = BigInt(t.notional) * (BigInt(d) * 7 - BigInt(7) * t.strike) / (BigInt(7) * t.strike)
        s.owed._1 - DFMath.margin(t.notional) shouldBe raw.toLong
        accepts(s.prover, s.tx)
        val (side, key, amount) = if (raw > 0) (1, maker(ctx), s.owed._1) else (2, taker(ctx), s.owed._2)
        rejectsAtSigning(s.prover, s.output(side, payoutUTXO(key, amount - 1)).tx)
      }
    }
  }

  // ─── completeness ─────────────────────────────────────────────────────────

  property("complete: accepts a window whose last epoch is the newest recorded") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - 7, 8)
      accepts(settler(ctx), settleScenario(ctx, t, _ => moved(STRIKE, 5)).tx)
    }
  }

  property("complete: rejects a window one epoch short of recorded") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - 6, 8)
      rejectsAtSigning(settler(ctx), settleScenario(ctx, t, _ => moved(STRIKE, 5)).tx)
    }
  }

  property("complete: a settlement that records the window's last epoch can settle in the same spend") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - 7, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 5), record = true)
      s.before.lastEpoch shouldBe fixtureEpoch - 1
      s.after.lit shouldBe BALANCE - RECORD_REWARD + ACC_DEPOSIT
      accepts(s.prover, s.tx)
    }
  }

  property("complete: the same window cannot settle while the accumulator is left unrecorded") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - 7, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 5), record = true)
      val unrecorded = s.before.copy(lit = s.before.lit + ACC_DEPOSIT)
      rejectsAtSigning(s.prover, s.output(0, accUTXO(unrecorded)).tx)
    }
  }

  // ─── deposit ──────────────────────────────────────────────────────────────

  property("deposit: rejects an accumulator raised by one unit under 2 LIT") {
    settle(8) { s =>
      rejectsAtSigning(s.prover, s.output(0, accUTXO(s.after.copy(lit = s.after.lit - 1))).tx)
    }
  }

  property("deposit: when the spend also records, rejects a net rise of one unit under 1 LIT") {
    withCtx { ctx =>
      val s = settleScenario(ctx, terms(fixtureEpoch - 20, 8), _ => moved(STRIKE, 5), record = true)
      // The accumulator allows the 1 LIT recording reward, so this is the position's deposit check alone
      rejectsAtSigning(s.prover, s.output(0, accUTXO(s.after.copy(lit = s.after.lit - 1))).tx)
    }
  }

  // ─── refund ───────────────────────────────────────────────────────────────

  property("refund: a window whose first epoch has left the ring returns both margins") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - RING_SIZE, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 15))
      s.owed shouldBe ((M, M))
      accepts(s.prover, s.tx)
    }
  }

  property("refund: rejects settling an overwritten window as though it were whole") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - RING_SIZE, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 15))
      rejectsAtSigning(s.prover, s.output(1, payoutUTXO(maker(ctx), M + 1)).output(2, payoutUTXO(taker(ctx), M - 1)).tx)
    }
  }

  property("refund: one epoch earlier the window is still whole and settles normally") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - RING_SIZE + 1, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 15))
      s.owed shouldBe ((M + NOTIONAL * 15 / 100, M - NOTIONAL * 15 / 100))
      accepts(s.prover, s.tx)
      rejectsAtSigning(s.prover, s.output(1, payoutUTXO(maker(ctx), M)).output(2, payoutUTXO(taker(ctx), M)).tx)
    }
  }

  property("refund: recording the epoch that overwrites the window's first epoch makes the same spend a refund") {
    withCtx { ctx =>
      val t = terms(fixtureEpoch - RING_SIZE, 8)
      val s = settleScenario(ctx, t, _ => moved(STRIKE, 15), record = true)
      s.before.lastEpoch - t.windowStart shouldBe RING_SIZE - 1
      s.owed shouldBe ((M, M))
      accepts(s.prover, s.tx)
    }
  }

  // ─── shape ────────────────────────────────────────────────────────────────

  property("shape: rejects the position anywhere but input 1") {
    settle(8) { s =>
      val fund = funding(s.ctx, settler(s.ctx), 1)
      val pos = inputAt(positionUTXO(maker(s.ctx), taker(s.ctx), s.t), s.ctx, 2)
      rejectsAtSigning(s.prover, s.copy(inputs = Seq(s.inputs.head, fund, pos)).tx)
    }
  }

  property("shape: a second position in the same spend is rejected") {
    settle(8) { s =>
      val second = inputAt(positionUTXO(maker(s.ctx), taker(s.ctx), s.t), s.ctx, 3)
      rejectsAtSigning(s.prover, s.copy(inputs = s.inputs :+ second).tx)
    }
  }

  property("shape: rejects a stand-in accumulator without the NFT") {
    settle(8) { s =>
      val fakeIn = inputAt(accUTXO(s.before, contract = Contract.SIGMA_TRUE, nft = unrelatedToken), s.ctx, 0)
      val fakeOut = accUTXO(s.after, contract = Contract.SIGMA_TRUE, nft = unrelatedToken)
      rejectsAtSigning(s.prover, s.input(0, fakeIn).output(0, fakeOut).tx)
    }
  }
}
