package transactions.batching.forwards

import forwards.{DFHelpers, DFMath}
import forwards.contracts.DifficultyForwardContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * What the client accepts as the accumulator or a position. Anyone can create a box at either address, so
 * every refusal here is a box a stranger could plant to make the batcher build something no contract accepts.
 */
class DFBoxesSpec extends AnyPropSpec with ForwardsFixtures {

  import _root_.contracts.specs.forwards.DFSpecBase._

  private val state = AccState(fixtureEpoch - 1, flatRing(difficultyOf(fixtureNBits)), 100L * LIT)
  private val window = terms(fixtureEpoch - 20, 8)

  private def readAcc(ctx: BlockchainContext, utxo: UTXO) = DFBoxes.accumulator(nodeBox(ctx, utxo, 0), contracts)
  private def readPosition(ctx: BlockchainContext, utxo: UTXO) = DFBoxes.position(nodeBox(ctx, utxo, 1), contracts)

  // ─── accumulator ──────────────────────────────────────────────────────────

  property("accumulator: reads the epoch, ring and balance of a genuine box") {
    withCtx { ctx =>
      val acc = readAcc(ctx, accUTXO(state)).get
      acc.lastEpoch shouldBe state.lastEpoch
      acc.ring shouldBe state.ring
      acc.lit shouldBe state.lit
    }
  }

  property("accumulator: an empty balance, with no LIT entry at all, reads as zero") {
    withCtx(ctx => readAcc(ctx, accUTXO(state.copy(lit = 0L))).get.lit shouldBe 0L)
  }

  property("accumulator: refuses another script, another NFT, or a balance in another token") {
    withCtx { ctx =>
      readAcc(ctx, accUTXO(state, contract = Contract.SIGMA_TRUE)) shouldBe None
      readAcc(ctx, accUTXO(state, nft = unrelatedToken)) shouldBe None
      readAcc(ctx, accUTXO(state, lit = unrelatedToken)) shouldBe None
    }
  }

  property("accumulator: refuses a third token") {
    withCtx(ctx => readAcc(ctx, accUTXO(state).addToken(Token(unrelatedToken, 5L))) shouldBe None)
  }

  property("accumulator: refuses a ring that is not 128 entries, and an epoch that is not an Int") {
    withCtx { ctx =>
      readAcc(ctx, accUTXO(state.copy(ring = state.ring.take(127)))) shouldBe None
      readAcc(ctx, accUTXO(state).withReg(0, ErgoValue.of(state.lastEpoch.toLong))) shouldBe None
      readAcc(ctx, accUTXO(state).setRegs(ErgoValue.of(state.lastEpoch))) shouldBe None
    }
  }

  // ─── position ─────────────────────────────────────────────────────────────

  property("position: reads its terms, and pays each side to that key's own script") {
    withCtx { ctx =>
      val p = readPosition(ctx, positionUTXO(maker(ctx), taker(ctx), window)).get
      p.terms shouldBe window
      p.windowEnd shouldBe window.windowStart + window.windowLength - 1
      p.lit shouldBe DFMath.positionLit(window.notional)
      p.long shouldBe contractOf(maker(ctx))
      p.short shouldBe contractOf(taker(ctx))
    }
  }

  property("position: refuses another script, a token other than LIT, or a second token") {
    withCtx { ctx =>
      val long = maker(ctx)
      val short = taker(ctx)
      readPosition(ctx, positionUTXO(long, short, window, contract = contracts.offer)) shouldBe None
      readPosition(ctx, positionUTXO(long, short, window, token = unrelatedToken)) shouldBe None
      readPosition(ctx, positionUTXO(long, short, window).addToken(Token(unrelatedToken, 1L))) shouldBe None
    }
  }

  property("position: refuses one unit of LIT short of both margins and the deposit, and accepts exactly that") {
    withCtx { ctx =>
      val floor = 2 * DFMath.margin(window.notional) + DFHelpers.ACC_DEPOSIT
      readPosition(ctx, positionUTXO(maker(ctx), taker(ctx), window, lit = floor - 1)) shouldBe None
      readPosition(ctx, positionUTXO(maker(ctx), taker(ctx), window, lit = floor)).map(_.lit) shouldBe Some(floor)
    }
  }

  property("position: refuses less ERG than a fill puts in") {
    withCtx { ctx =>
      readPosition(ctx, positionUTXO(maker(ctx), taker(ctx), window, value = POSITION_ERG - 1)) shouldBe None
    }
  }

  property("position: refuses a window or terms no fill creates") {
    withCtx { ctx =>
      val (long, short) = (maker(ctx), taker(ctx))
      Seq(terms(fixtureEpoch, 0), terms(fixtureEpoch, DFHelpers.MAX_WINDOW + 1), terms(-1, 8),
        terms(Int.MaxValue - 3, 8), terms(fixtureEpoch, 8, strike = 0L), terms(fixtureEpoch, 8, notional = 4L))
        .foreach(t => withClue(s"$t: ")(readPosition(ctx, positionUTXO(long, short, t)) shouldBe None))
      readPosition(ctx, positionUTXO(long, short, terms(fixtureEpoch, DFHelpers.MAX_WINDOW))) should not be empty
    }
  }

  property("position: refuses a key register holding anything but a SigmaProp") {
    withCtx { ctx =>
      val genuine = positionUTXO(maker(ctx), taker(ctx), window)
      val keyBytes = ErgoValue.of(contractOf(maker(ctx)).propBytes)
      readPosition(ctx, genuine.withReg(0, keyBytes)) shouldBe None
      readPosition(ctx, genuine.withReg(1, keyBytes)) shouldBe None
      readPosition(ctx, genuine.withReg(4, ErgoValue.of(window.windowStart))) shouldBe None
    }
  }

  // ─── rebuilding for a spend ───────────────────────────────────────────────

  property("input: refuses a box whose reported id is not the hash of its reported fields") {
    withCtx { ctx =>
      val box = nodeBox(ctx, positionUTXO(maker(ctx), taker(ctx), window), 1)
      DFBoxes.input(box, ctx).map(_.id.toString) shouldBe Some(box.boxId)
      DFBoxes.input(box.copy(value = box.value + 1), ctx) shouldBe None
    }
  }

  property("registers: the client writes the accumulator's registers in the types it reads back") {
    withCtx { ctx =>
      val registers = DifficultyForwardContracts.accumulatorRegisters(7, Seq.fill(128)(3L))
      readAcc(ctx, accUTXO(state).setRegs(registers: _*)).map(_.lastEpoch) shouldBe Some(7)
    }
  }
}
