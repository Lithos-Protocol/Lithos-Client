package contracts.specs.forwards

import forwards.DFMath
import org.ergoplatform.appkit.{BlockchainContext, ErgoProver, ErgoValue, Parameters, UnsignedTransaction}
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * DF_Accumulator: one rule over every spend. The ring and last epoch stay the same or advance by one
 * recording of the last block's epoch, and the LIT balance falls by at most the reward, only when recording.
 */
class DFAccumulatorSpec extends AnyPropSpec with DFSpecBase {

  import DFSpecBase._

  private val BALANCE: Long = 100L * LIT

  /** One spend. Negatives replace the successor or the outputs and rebuild. */
  private case class Spend(ctx: BlockchainContext, before: AccState, after: AccState,
                           inputs: Seq[InputUTXO], prover: ErgoProver) {
    def successor: UTXO = accUTXO(after)

    def tx(out: UTXO = successor, outputs: Seq[UTXO] = null, height: Int = 0): UnsignedTransaction = {
      val outs = if (outputs == null) Seq(out) else outputs
      if (height == 0) build(ctx, inputs, outs, prover.getAddress)
      else buildAt(ctx, height, inputs, outs, prover.getAddress)
    }
  }

  /**
   * Records the last block's epoch over a ring last recorded `gap` epochs earlier, taking `taken`. The
   * reward reaches the settler through change. `fundingTokens` lets a negative bring tokens of its own.
   */
  private def recording[A](lastBlock: Int = fixtureLastBlock,
                           nBits: Long = fixtureNBits,
                           gap: Int = 1,
                           balance: Long = BALANCE,
                           taken: Long = RECORD_REWARD,
                           fundingTokens: Seq[Token] = Seq.empty[Token])(f: Spend => A): A =
    withLastBlock(lastBlock, nBits) { ctx =>
      val current = epochOf(lastBlock)
      val before = AccState(current - gap, traceableRing(current - gap, 1000000L), balance)
      // A gap of zero is not a recording, so the successor is the unchanged form less `taken`
      val after =
        if (gap > 0) afterRecord(before, current, difficultyOf(nBits), taken) else before.copy(lit = balance - taken)
      val p = settler(ctx)
      f(Spend(ctx, before, after, Seq(inputAt(accUTXO(before), ctx, 0), funding(ctx, p, 1, tokens = fundingTokens)), p))
    }

  /** A spend that records nothing, with `added` LIT brought by the funding and deposited. */
  private def unchanged[A](added: Long = 0L, balance: Long = BALANCE)(f: Spend => A): A =
    withCtx { ctx =>
      val before = AccState(fixtureEpoch, traceableRing(fixtureEpoch, 1000000L), balance)
      val after = before.copy(lit = balance + added)
      val p = settler(ctx)
      val fund = funding(ctx, p, 1, tokens = if (added > 0) Seq(Token(litId, added)) else Seq.empty[Token])
      f(Spend(ctx, before, after, Seq(inputAt(accUTXO(before), ctx, 0), fund), p))
    }

  // ─── header reading ───────────────────────────────────────────────────────

  property("header: the stock fixture's nBits decode to its own difficulty field") {
    difficultyOf(fixtureNBits) shouldBe 128274315345920L
  }

  property("header: a rewritten fixture puts the last block where the spec asks") {
    // The context's height is its newest full header, which becomes the pre-header; headers(0) is one below
    withLastBlock(200001) { ctx => ctx.getHeight shouldBe 200002 }
  }

  // ─── recording ────────────────────────────────────────────────────────────

  property("record: accepts recording the next epoch and taking the reward") {
    recording() { s => accepts(s.prover, s.tx()) }
  }

  property("record: accepts a recording that takes nothing") {
    recording(taken = 0L) { s => accepts(s.prover, s.tx()) }
  }

  property("record: rejects taking one unit more than the reward (balanceHeld)") {
    recording(taken = RECORD_REWARD + 1) { s => rejectsAtSigning(s.prover, s.tx()) }
  }

  property("record: writes the difficulty the last block's nBits encode") {
    val nBits = nBitsFor(3L * 128274315345920L)
    recording(nBits = nBits) { s =>
      s.after.ring(fixtureEpoch % RING_SIZE) shouldBe difficultyOf(nBits)
      accepts(s.prover, s.tx())
    }
  }

  property("record: rejects a difficulty one away from the header's (ringAdvanced)") {
    recording() { s =>
      val slot = fixtureEpoch % RING_SIZE
      val wrong = s.after.copy(ring = s.after.ring.updated(slot, s.after.ring(slot) + 1))
      rejectsAtSigning(s.prover, s.tx(accUTXO(wrong)))
    }
  }

  property("record: carries the last recorded value into every skipped epoch") {
    recording(gap = 5) { s =>
      val carried = s.before.ring(s.before.lastEpoch % RING_SIZE)
      (1 until 5).foreach(k => s.after.ring((s.before.lastEpoch + k) % RING_SIZE) shouldBe carried)
      accepts(s.prover, s.tx())
    }
  }

  property("record: rejects a skipped epoch given anything but the carried value") {
    recording(gap = 5) { s =>
      val slot = (s.before.lastEpoch + 2) % RING_SIZE
      val wrong = s.after.copy(ring = s.after.ring.updated(slot, s.after.ring(slot) + 1))
      rejectsAtSigning(s.prover, s.tx(accUTXO(wrong)))
    }
  }

  property("record: after a gap of 128 or more epochs, rewrites every slot") {
    recording(gap = 200) { s =>
      val carried = s.before.ring(s.before.lastEpoch % RING_SIZE)
      s.after.ring.count(_ == carried) shouldBe RING_SIZE - 1
      accepts(s.prover, s.tx())
    }
  }

  property("record: at a gap of exactly 127, keeps only the last recorded slot") {
    recording(gap = 127) { s =>
      val lastSlot = s.before.lastEpoch % RING_SIZE
      s.after.ring(lastSlot) shouldBe s.before.ring(lastSlot)
      accepts(s.prover, s.tx())
    }
  }

  property("record: rejects rewriting a slot recorded before lastEpoch") {
    recording(gap = 3) { s =>
      val slot = (s.before.lastEpoch - 4) % RING_SIZE
      val wrong = s.after.copy(ring = s.after.ring.updated(slot, s.after.ring(slot) + 1))
      rejectsAtSigning(s.prover, s.tx(accUTXO(wrong)))
    }
  }

  property("record: rejects advancing lastEpoch with the ring left unchanged") {
    recording() { s =>
      rejectsAtSigning(s.prover, s.tx(accUTXO(s.after.copy(ring = s.before.ring))))
    }
  }

  property("record: rejects recording an epoch after the last block's") {
    recording() { s =>
      val ahead = afterRecord(s.before, fixtureEpoch + 1, difficultyOf(fixtureNBits))
      rejectsAtSigning(s.prover, s.tx(accUTXO(ahead)))
    }
  }

  property("record: rejects a successor naming a later epoch than the ring it writes") {
    recording() { s =>
      rejectsAtSigning(s.prover, s.tx(accUTXO(s.after.copy(lastEpoch = fixtureEpoch + 1))))
    }
  }

  property("record: rejects moving lastEpoch backwards, even with the ring the contract would write") {
    // An accumulator ahead of the chain: only reachable through a genesis that named a future epoch
    recording(gap = 0, taken = 0L) { s =>
      val ahead = s.before.copy(lastEpoch = fixtureEpoch + 3)
      val back = AccState(fixtureEpoch,
        ahead.ring.updated(fixtureEpoch % RING_SIZE, difficultyOf(fixtureNBits)), ahead.lit - RECORD_REWARD)
      val inputs = s.inputs.updated(0, inputAt(accUTXO(ahead), s.ctx, 0))
      rejectsAtSigning(s.prover, s.copy(before = ahead, inputs = inputs).tx(accUTXO(back)))
    }
  }

  property("record: rejects a second recording in the same epoch") {
    recording(gap = 0, taken = 0L) { s =>
      // lastEpoch is already the current epoch, so only the unchanged form is left and it pays nothing
      accepts(s.prover, s.tx())
      val again = s.before.copy(lit = s.before.lit - RECORD_REWARD)
      rejectsAtSigning(s.prover, s.tx(accUTXO(again)))
      val rewritten = s.before.copy(ring = s.before.ring.updated(fixtureEpoch % RING_SIZE, 5L))
      rejectsAtSigning(s.prover, s.tx(accUTXO(rewritten)))
    }
  }

  property("record: rejects a successor ring one entry longer") {
    recording() { s =>
      rejectsAtSigning(s.prover, s.tx(accUTXO(s.after.copy(ring = s.after.ring :+ 7L))))
    }
  }

  // ─── epoch boundaries and the pre-header ──────────────────────────────────

  property("boundary: the last block of an epoch still records that epoch") {
    val lastOf964 = 965 * 128
    epochOf(lastOf964) shouldBe 964
    recording(lastBlock = lastOf964) { s => accepts(s.prover, s.tx()) }
  }

  property("boundary: the first block of an epoch records the new one") {
    val firstOf965 = 965 * 128 + 1
    epochOf(firstOf965) shouldBe 965
    recording(lastBlock = firstOf965) { s => accepts(s.prover, s.tx()) }
  }

  property("boundary: the epoch comes from the last block, not from a pre-header in the next epoch") {
    recording() { s =>
      val nextEpochHeight = heightIn(fixtureEpoch + 1)
      accepts(s.prover, s.tx(height = nextEpochHeight))
      val byPreHeader = afterRecord(s.before, fixtureEpoch + 1, difficultyOf(fixtureNBits))
      rejectsAtSigning(s.prover, s.tx(accUTXO(byPreHeader), height = nextEpochHeight))
    }
  }

  // ─── spends that record nothing ───────────────────────────────────────────

  property("unchanged: accepts a spend that changes nothing") {
    unchanged() { s => accepts(s.prover, s.tx()) }
  }

  property("unchanged: accepts a deposit of any size") {
    unchanged(added = 5L * LIT) { s => accepts(s.prover, s.tx()) }
  }

  property("unchanged: rejects taking a single unit of LIT (balanceHeld)") {
    unchanged() { s => rejectsAtSigning(s.prover, s.tx(accUTXO(s.after.copy(lit = s.after.lit - 1)))) }
  }

  property("unchanged: rejects a changed slot (ringAdvanced)") {
    unchanged() { s =>
      rejectsAtSigning(s.prover, s.tx(accUTXO(s.after.copy(ring = s.after.ring.updated(0, s.after.ring(0) + 1)))))
    }
  }

  // ─── balance ──────────────────────────────────────────────────────────────

  property("balance: a recording over an empty balance is accepted and pays nothing") {
    recording(balance = 0L, taken = 0L) { s =>
      s.successor.tokens should have size 1
      accepts(s.prover, s.tx())
    }
  }

  property("balance: with less than the reward left, a recording may take all of it") {
    recording(balance = 4L * LIT / 10, taken = 4L * LIT / 10) { s => accepts(s.prover, s.tx()) }
  }

  // ─── identity ─────────────────────────────────────────────────────────────

  property("identity: rejects a successor under another script") {
    recording() { s => rejectsAtSigning(s.prover, s.tx(s.successor.setContract(Contract.SIGMA_TRUE))) }
  }

  property("identity: rejects a successor holding another token where the NFT was") {
    // An empty balance, so the swapped token sits alone at token 0 and the balance check has nothing to refuse
    recording(balance = 0L, taken = 0L, fundingTokens = Seq(Token(unrelatedToken, 1L))) { s =>
      rejectsAtSigning(s.prover, s.tx(s.successor.setTokens(Token(unrelatedToken, 1L))))
    }
  }

  property("identity: rejects a successor holding one nanoERG less, or one more") {
    recording() { s =>
      rejectsAtSigning(s.prover, s.tx(s.successor.subValue(1L)))
      rejectsAtSigning(s.prover, s.tx(s.successor.addValue(1L)))
    }
  }

  property("identity: rejects a third token") {
    recording(fundingTokens = Seq(Token(unrelatedToken, 5L))) { s =>
      rejectsAtSigning(s.prover, s.tx(s.successor.addToken(Token(unrelatedToken, 5L))))
    }
  }

  property("identity: rejects a balance held in a token other than LIT (litHeld)") {
    // As many units as the LIT balance, so balanceHeld would pass on the amount alone
    recording(fundingTokens = Seq(Token(unrelatedToken, BALANCE))) { s =>
      rejectsAtSigning(s.prover, s.tx(s.successor.setTokens(Token(accNFT, 1L), Token(unrelatedToken, BALANCE))))
    }
  }

  property("identity: rejects a successor anywhere but OUTPUTS(0)") {
    recording() { s =>
      val other = UTXO(contractOf(s.prover), Parameters.MinFee)
      rejectsAtSigning(s.prover, s.tx(outputs = Seq(other, s.successor)))
    }
  }

  property("identity: padding past R5 is accepted, since the box is spent every epoch") {
    recording() { s =>
      val padded = s.successor.copy(registers = s.successor.registers :+ ErgoValue.of(Array.fill(200)(7.toByte)))
      accepts(s.prover, s.tx(padded))
    }
  }

  // ─── the client's mirror ──────────────────────────────────────────────────

  property("mirror: DFMath.recordedRing refuses an epoch that is not after the last") {
    an[IllegalArgumentException] should be thrownBy
      DFMath.recordedRing(traceableRing(10, 0L), 10, 10, 5L)
  }
}
