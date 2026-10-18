package contracts.specs.forwards

import forwards.{DFHelpers, DFMath}
import forwards.contracts.DFTerms
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, UnsignedTransaction}
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * DF_Offer: a fill creates a position carrying the offer's terms, the maker on their side and both sides'
 * full amounts, inside the window and expiry rules. Anything else needs the maker's signature.
 *
 * The taker signs every fill: the offer reduces to true, so only the taker's funding needs a key.
 */
class DFOfferSpec extends AnyPropSpec with DFSpecBase {

  import DFSpecBase._

  /** LIT the taker brings beyond its own side, so over- and under-funded negatives still build. */
  private val TAKER_SPARE: Long = 10L * LIT

  /** One fill. Negatives replace the position or the inputs and rebuild. */
  private case class Fill(ctx: BlockchainContext, t: DFTerms, makerLong: Boolean, expiry: Int,
                          offer: UTXO, offerIn: InputUTXO, takerIn: InputUTXO, position: UTXO) {
    def tx(position: UTXO = this.position, inputs: Seq[InputUTXO] = null,
           outputs: Seq[UTXO] = null): UnsignedTransaction =
      build(ctx, if (inputs == null) Seq(offerIn, takerIn) else inputs,
        if (outputs == null) Seq(position) else outputs, taker(ctx).getAddress)
  }

  /** Window starts at the earliest epoch allowed and the offer expires 100 blocks out, unless given. */
  private def fillScenario(ctx: BlockchainContext,
                           makerLong: Boolean = true,
                           windowStart: Int = fixtureEpoch + 2,
                           windowLength: Int = 8,
                           strike: Long = STRIKE,
                           notional: Long = NOTIONAL,
                           expiry: Int = 0): Fill = {
    val t = terms(windowStart, windowLength, strike, notional)
    val exp = if (expiry == 0) ctx.getHeight + 100 else expiry
    val offer = offerUTXO(ctx, makerLong, t, exp)
    val (long, short) = if (makerLong) (maker(ctx), taker(ctx)) else (taker(ctx), maker(ctx))
    Fill(ctx, t, makerLong, exp, offer, inputAt(offer, ctx, 0),
      funding(ctx, taker(ctx), 1, tokens = Seq(Token(litId, DFMath.sideLit(notional) + TAKER_SPARE))),
      positionUTXO(long, short, t))
  }

  private def fill[A](f: Fill => A): A = withCtx(ctx => f(fillScenario(ctx)))

  // ─── fills ────────────────────────────────────────────────────────────────

  property("fill: accepts a long maker's offer") {
    fill { s => accepts(taker(s.ctx), s.tx()) }
  }

  property("fill: accepts a short maker's offer") {
    withCtx { ctx =>
      val s = fillScenario(ctx, makerLong = false)
      accepts(taker(ctx), s.tx())
    }
  }

  property("fill: the position holds twice the margin plus 1.5 LIT from each side") {
    fill { s =>
      s.position.tokens.head.amount shouldBe 2L * (NOTIONAL / 5 + SIDE_REWARD)
      accepts(taker(s.ctx), s.tx())
    }
  }

  // ─── expiry ───────────────────────────────────────────────────────────────

  property("expiry: accepts a fill at the expiry height itself") {
    withCtx { ctx => accepts(taker(ctx), fillScenario(ctx, expiry = ctx.getHeight).tx()) }
  }

  property("expiry: rejects a fill one block after expiry") {
    withCtx { ctx => rejectsAtSigning(taker(ctx), fillScenario(ctx, expiry = ctx.getHeight - 1).tx()) }
  }

  // ─── window ───────────────────────────────────────────────────────────────

  property("window: rejects a window starting one epoch after the last block's") {
    withCtx { ctx => rejectsAtSigning(taker(ctx), fillScenario(ctx, windowStart = fixtureEpoch + 1).tx()) }
  }

  property("window: accepts a window starting 120 epochs after the last block's") {
    withCtx { ctx => accepts(taker(ctx), fillScenario(ctx, windowStart = fixtureEpoch + DFHelpers.MAX_LEAD).tx()) }
  }

  property("window: rejects a window starting 121 epochs after the last block's") {
    withCtx { ctx =>
      rejectsAtSigning(taker(ctx), fillScenario(ctx, windowStart = fixtureEpoch + DFHelpers.MAX_LEAD + 1).tx())
    }
  }

  property("window: the latest start with the longest window ends 239 epochs after the fill") {
    withCtx { ctx =>
      val s = fillScenario(ctx, windowStart = fixtureEpoch + DFHelpers.MAX_LEAD, windowLength = 120)
      s.t.windowStart + s.t.windowLength - 1 - fixtureEpoch shouldBe 239
      accepts(taker(ctx), s.tx())
    }
  }

  property("window: the lead is counted from the last block, not from a pre-header in the next epoch") {
    withCtx { ctx =>
      val s = fillScenario(ctx, expiry = heightIn(fixtureEpoch + 2))
      accepts(taker(ctx), buildAt(ctx, heightIn(fixtureEpoch + 1), Seq(s.offerIn, s.takerIn), Seq(s.position),
        taker(ctx).getAddress))
    }
  }

  property("window: accepts lengths 1 and 120") {
    withCtx { ctx =>
      accepts(taker(ctx), fillScenario(ctx, windowLength = 1).tx())
      accepts(taker(ctx), fillScenario(ctx, windowLength = 120).tx())
    }
  }

  property("window: rejects lengths 0 and 121") {
    withCtx { ctx =>
      rejectsAtSigning(taker(ctx), fillScenario(ctx, windowLength = 0).tx())
      rejectsAtSigning(taker(ctx), fillScenario(ctx, windowLength = 121).tx())
    }
  }

  // ─── terms ────────────────────────────────────────────────────────────────

  property("terms: rejects a zero strike") {
    withCtx { ctx => rejectsAtSigning(taker(ctx), fillScenario(ctx, strike = 0L).tx()) }
  }

  property("terms: rejects a notional whose margin truncates to zero, and accepts the first that does not") {
    withCtx { ctx =>
      rejectsAtSigning(taker(ctx), fillScenario(ctx, notional = 4L).tx())
      accepts(taker(ctx), fillScenario(ctx, notional = 5L).tx())
    }
  }

  property("terms: rejects the maker's key on the taker's side") {
    fill { s =>
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(taker(s.ctx), maker(s.ctx), s.t)))
    }
  }

  property("terms: rejects the maker's key on both sides") {
    fill { s =>
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(taker(s.ctx), taker(s.ctx), s.t)))
    }
  }

  property("terms: rejects a changed strike, notional or window") {
    fill { s =>
      val lit = DFMath.positionLit(s.t.notional)
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t.copy(strike = s.t.strike + 1), lit)))
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t.copy(notional = s.t.notional + 5), lit)))
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t.copy(windowStart = s.t.windowStart + 1))))
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t.copy(windowLength = s.t.windowLength + 1))))
    }
  }

  property("terms: rejects a taker key the position could not read as a SigmaProp") {
    fill { s =>
      val unreadable = s.position.withReg(1, ErgoValue.of(Array.fill(33)(2.toByte)))
      rejectsAtSigning(taker(s.ctx), s.tx(unreadable))
    }
  }

  // ─── funding ──────────────────────────────────────────────────────────────

  property("funding: rejects a position one LIT unit short, or one over") {
    fill { s =>
      val exact = DFMath.positionLit(NOTIONAL)
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t, exact - 1)))
      rejectsAtSigning(taker(s.ctx), s.tx(positionUTXO(maker(s.ctx), taker(s.ctx), s.t, exact + 1)))
    }
  }

  property("funding: rejects the right amount of the wrong token") {
    withCtx { ctx =>
      val s = fillScenario(ctx)
      val amount = DFMath.positionLit(NOTIONAL)
      val takerIn = funding(ctx, taker(ctx), 1, tokens = Seq(Token(unrelatedToken, amount)))
      val wrong = positionUTXO(maker(ctx), taker(ctx), s.t, token = unrelatedToken)
      rejectsAtSigning(taker(ctx), s.tx(wrong, inputs = Seq(s.offerIn, takerIn)))
    }
  }

  property("funding: rejects a position holding one nanoERG under 0.004 ERG") {
    fill { s =>
      rejectsAtSigning(taker(s.ctx), s.tx(s.position.setValue(POSITION_ERG - 1)))
    }
  }

  // ─── double satisfaction ──────────────────────────────────────────────────

  property("onlyOne: rejects an offer filled from input 1") {
    fill { s =>
      val takerAt0 = funding(s.ctx, taker(s.ctx), 0, tokens = Seq(Token(litId, DFMath.sideLit(NOTIONAL) + TAKER_SPARE)))
      rejectsAtSigning(taker(s.ctx), s.tx(inputs = Seq(takerAt0, inputAt(s.offer, s.ctx, 1))))
    }
  }

  property("onlyOne: two identical offers cannot both fill into one position") {
    fill { s =>
      val twin = inputAt(s.offer, s.ctx, 2, "1111111111111111111111111111111111111111111111111111111111111111")
      rejectsAtSigning(taker(s.ctx), s.tx(inputs = Seq(s.offerIn, twin, s.takerIn)))
    }
  }

  // ─── cancel ───────────────────────────────────────────────────────────────

  property("cancel: the maker takes the offer back") {
    fill { s =>
      val makerIn = funding(s.ctx, maker(s.ctx), 1)
      val back = UTXO(contractOf(maker(s.ctx)), s.offer.value, s.offer.tokens)
      accepts(maker(s.ctx), build(s.ctx, Seq(s.offerIn, makerIn), Seq(back), maker(s.ctx).getAddress))
    }
  }

  property("cancel: nobody else can take the offer") {
    fill { s =>
      val back = UTXO(contractOf(taker(s.ctx)), s.offer.value, s.offer.tokens)
      rejectsAtSigning(taker(s.ctx), s.tx(outputs = Seq(back)))
    }
  }

  property("cancel: a position under another script is not a fill, so it needs the maker") {
    fill { s =>
      rejectsAtSigning(taker(s.ctx), s.tx(s.position.setContract(contractOf(taker(s.ctx)))))
    }
  }

  property("cancel: a position under a script that accepts anything is not a fill either") {
    fill { s =>
      rejectsAtSigning(taker(s.ctx), s.tx(s.position.setContract(Contract.SIGMA_TRUE)))
    }
  }
}
