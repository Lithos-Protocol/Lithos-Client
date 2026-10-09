package contracts.specs.upkeep

import contracts.specs.harness.ContractSpecBase
import support.UpkeepContracts
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

object DueJobSpec {

  /** Compiled once; the tree takes no constants, so one compile serves every property. */
  private var cache: Option[Contract] = None

  def compiled(ctx: BlockchainContext): Contract = synchronized {
    cache.getOrElse {
      val contract = UpkeepContracts.mkDueJobContract(ctx)
      cache = Some(contract)
      contract
    }
  }
}

/**
 * DueJob.ergo — one property per named condition in the contract, plus the shape of what it
 * leaves free.
 *
 * Every negative is differential: it takes the beat the first property proves accepted and changes
 * exactly the field its condition reads, with the transaction rebalanced so it still builds. The
 * refusal is then the interpreter's, at signing, and the only thing that differs from the accepted
 * spend is the one the property names. `rejectsAtSigning` holds the spec to that: a perturbation
 * that merely unbalanced the transaction would fail at build and be reported as such.
 *
 * The properties for `sane`, `onlyOne` and the Long sum change the box rather than the successor,
 * since that is what their conditions read; each keeps the rest of the accepted beat as it was.
 *
 * The beat is at HEIGHT = tip height + 1, the next block, through a preHeader: the script compares
 * against HEIGHT exactly, and the mocked context's own height is one below where a candidate's
 * transactions are validated. The box is funded far above a lifetime of beats, so no negative is
 * confounded by the consensus minimum.
 */
class DueJobSpec extends AnyPropSpec with ContractSpecBase {

  private val period: Int = 10
  private val tip: Long = Parameters.OneErg / 100
  private val token: Token = Token(ErgoId.create("ab" * 32), 7L)

  private def dueJob(ctx: BlockchainContext): Contract = DueJobSpec.compiled(ctx)

  private def regs(lastBeat: Int, period: Int, tip: Long): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(lastBeat), ErgoValue.of(period), ErgoValue.of(tip))

  /**
   * One beat of one box: the box as it stands, due exactly at `height`, and the two outputs the
   * executor is expected to make. The tip goes to the miner; the contract does not care where.
   */
  private case class Beat(ctx: BlockchainContext,
                          box: InputUTXO,
                          successor: UTXO,
                          tipOut: UTXO,
                          height: Int,
                          lastBeat: Int,
                          prover: ErgoProver)

  private def beat(ctx: BlockchainContext, tokens: Seq[Token] = Seq(token)): Beat = {
    val height = ctx.getHeight + 1
    val lastBeat = height - period
    val standing = UTXO(dueJob(ctx), boxValue, tokens, regs(lastBeat, period, tip)).setCreationHeight(lastBeat)
    val successor = UTXO(dueJob(ctx), boxValue - tip, tokens, regs(height, period, tip)).setCreationHeight(height)
    val tipOut = UTXO(contractOf(miner(ctx)), tip).setCreationHeight(height)
    Beat(ctx, inputAt(standing, ctx, 0), successor, tipOut, height, lastBeat, miner(ctx))
  }

  /** A due-job box with the given terms, standing since `lastBeat`, as the spend's input at `index`. */
  private def standing(ctx: BlockchainContext, lastBeat: Int, period: Int, tip: Long,
                       value: Long = boxValue, tokens: Seq[Token] = Seq(token), index: Int = 0): InputUTXO =
    inputAt(UTXO(dueJob(ctx), value, tokens, regs(lastBeat, period, tip)).setCreationHeight(lastBeat), ctx, index)

  /** Fee-less and balanced to zero change, as the client's own block transactions are. */
  private def beatTx(b: Beat)(outputs: Seq[UTXO] = Seq(b.successor, b.tipOut),
                              height: Int = b.height,
                              inputs: Seq[InputUTXO] = Seq(b.box)): UnsignedTransaction =
    TxBuilder(b.ctx)
      .setInputs(inputs: _*)
      .setOutputs(outputs: _*)
      .setPreHeader(b.ctx.createPreHeader().height(height).build())
      .buildTx(0L, b.prover.getAddress)

  // ─── the beat ─────────────────────────────────────────────────────────────

  property("beat: a due box advances to a successor stamped with the height, lighter by the tip") {
    withCtx { ctx =>
      val b = beat(ctx)
      val signed = accepts(b.prover, beatTx(b)())
      signed.getOutputsToSpend.size shouldBe 2
      signed.getOutputsToSpend.get(0).getValue shouldBe boxValue - tip
      signed.getOutputsToSpend.get(1).getValue shouldBe tip
    }
  }

  /** Keyless end to end: the script asks for no signature, so a prover holding no secret suffices. */
  property("beat: a prover holding no secret advances the box") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(ctx.newProverBuilder().build(), beatTx(b)())
    }
  }

  property("beat: a box carrying no tokens advances the same way") {
    withCtx { ctx =>
      val b = beat(ctx, tokens = Seq.empty)
      accepts(b.prover, beatTx(b)())
    }
  }

  property("due: the same beat one block early is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      val early = b.height - 1
      early shouldBe b.lastBeat + period - 1
      // R4 follows HEIGHT, so `due` is the only condition that changes.
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(early)).setCreationHeight(early),
          b.tipOut.setCreationHeight(early)),
        height = early))
    }
  }

  property("due: a box overdue by many blocks still advances") {
    withCtx { ctx =>
      val b = beat(ctx)
      val late = b.height + 5 * period
      accepts(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(late)).setCreationHeight(late),
          b.tipOut.setCreationHeight(late)),
        height = late))
    }
  }

  /**
   * R4 + R5 is taken in Long, so a period near `Int.MaxValue` makes a height no chain reaches and
   * the box is never due. The property shows the refusal; it cannot tell a Long sum from an Int one
   * (an Int overflow would fail the script too), so what it holds is that such a box is refused.
   */
  property("due: a box whose R4 + R5 passes Int.MaxValue is not due") {
    withCtx { ctx =>
      val b = beat(ctx)
      val long = Int.MaxValue
      (b.lastBeat.toLong + long.toLong) should be > Int.MaxValue.toLong
      val box = standing(ctx, lastBeat = b.lastBeat, period = long, tip = tip)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(5, ErgoValue.of(long)), b.tipOut),
        inputs = Seq(box)))
    }
  }

  // ─── the box's own terms ──────────────────────────────────────────────────

  /** A period of zero would make the box due every block; it is no heartbeat, and `sane` refuses it. */
  property("sane: a box with a period of zero is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      val box = standing(ctx, lastBeat = b.lastBeat, period = 0, tip = tip)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(5, ErgoValue.of(0)), b.tipOut),
        inputs = Seq(box)))
    }
  }

  /**
   * A negative tip is nonsense the box would carry forever; `sane` refuses it. Differential: with
   * tip -1 `valueKept` asks the successor to keep one nanoERG more than the box held, so a second,
   * plain input funds exactly that, and `sane` is the one condition left to fail.
   */
  property("sane: a box with a negative tip is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      val box = standing(ctx, lastBeat = b.lastBeat, period = period, tip = -1L)
      val funding = inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg).setCreationHeight(b.lastBeat), ctx, 1)
      val richer = UTXO(dueJob(ctx), boxValue + 1L, Seq(token), regs(b.height, period, -1L)).setCreationHeight(b.height)
      val change = UTXO(Contract.SIGMA_TRUE, Parameters.OneErg - 1L).setCreationHeight(b.height)
      rejectsAtSigning(b.prover, beatTx(b)(outputs = Seq(richer, change), inputs = Seq(box, funding)))
    }
  }

  /**
   * A tip at or above the value is the creator offering the whole box: `valueKept` asks the
   * successor for nothing, and only the consensus minimum for a box keeps anything in it.
   */
  property("valueKept: a tip above the value advances, and all but a box's minimum may be taken") {
    withCtx { ctx =>
      val b = beat(ctx)
      val value = Parameters.OneErg
      val bigTip = 2L * value
      val kept = Parameters.MinChangeValue
      val box = standing(ctx, lastBeat = b.lastBeat, period = period, tip = bigTip, value = value)
      val successor = b.successor.setValue(kept).withRegNum(6, ErgoValue.of(bigTip))
      val taken = b.tipOut.setValue(value - kept)
      accepts(b.prover, beatTx(b)(outputs = Seq(successor, taken), inputs = Seq(box)))
    }
  }

  // ─── one box per transaction ──────────────────────────────────────────────

  /**
   * Two boxes with the same script, tokens, R5 and R6 would each accept the one `OUTPUTS(0)`, and
   * the spender would keep the second box whole. `onlyOne` holds each box to `INPUTS(0)`, so the
   * second input's script refuses. Built exactly as the merge would be: one successor worth the
   * larger box less the tip, and the rest to the spender.
   */
  property("onlyOne: a due box that is not INPUTS(0) is refused, so two cannot share one successor") {
    withCtx { ctx =>
      val b = beat(ctx, tokens = Seq.empty)
      val smaller = boxValue / 2
      val second = standing(ctx, lastBeat = b.lastBeat, period = period, tip = tip,
        value = smaller, tokens = Seq.empty, index = 1)
      second.id should not be b.box.id
      val rest = b.tipOut.setValue(smaller + tip)
      rejectsAtSigning(b.prover, beatTx(b)(outputs = Seq(b.successor, rest), inputs = Seq(b.box, second)))
    }
  }

  // ─── the successor ────────────────────────────────────────────────────────

  /** Without it a beat would leave the box as close to storage rent as it stood before. */
  property("freshStamp: a successor keeping the input's creation height is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.setCreationHeight(b.lastBeat), b.tipOut)))
    }
  }

  property("beatStamped: a successor whose R4 is not HEIGHT is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(b.height - 1)), b.tipOut)))
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(b.height + 1)), b.tipOut)))
    }
  }

  property("periodKept: a successor with a changed R5 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(5, ErgoValue.of(period + 1)), b.tipOut)))
    }
  }

  /** Even a smaller tip: the terms are the creator's, and an executor may not rewrite them either way. */
  property("tipKept: a successor with a changed R6 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(6, ErgoValue.of(tip - 1L)), b.tipOut)))
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(6, ErgoValue.of(tip + 1L)), b.tipOut)))
    }
  }

  property("sameTokens: a successor that dropped the box's token is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      // The token has to land somewhere for the transaction to build; the tip output takes it.
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.setTokens(), b.tipOut.setTokens(token))))
    }
  }

  property("valueKept: a successor keeping less than value - R6 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.subValue(1L), b.tipOut.addValue(1L))))
    }
  }

  property("sameScript: a successor under another script is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.setContract(Contract.SIGMA_TRUE), b.tipOut)))
    }
  }

  /** The successor is OUTPUTS(0) by position: a correct one anywhere else does not count. */
  property("successor: the tip output ahead of the successor is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(outputs = Seq(b.tipOut, b.successor)))
    }
  }

  // ─── what the contract leaves free ────────────────────────────────────────

  property("remainder: the tip may go to anyone") {
    withCtx { ctx =>
      val b = beat(ctx)
      val elsewhere = b.tipOut.setContract(contractOf(proverWith(ctx, otherSecret)))
      accepts(b.prover, beatTx(b)(outputs = Seq(b.successor, elsewhere)))
    }
  }

  property("remainder: a successor keeping more than value - R6 advances, with a smaller tip") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(b.prover, beatTx(b)(
        outputs = Seq(b.successor.addValue(tip / 2), b.tipOut.subValue(tip / 2))))
    }
  }

  /** A beat for nothing: how a tip too small for a box of its own is still paid, by not being taken. */
  property("remainder: a successor keeping the whole value advances with no tip output") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(b.prover, beatTx(b)(outputs = Seq(b.successor.setValue(boxValue))))
    }
  }

  property("remainder: a successor may carry registers past R6") {
    withCtx { ctx =>
      val b = beat(ctx)
      val annotated = b.successor.setRegs((b.successor.registers :+ ErgoValue.of(42L)): _*)
      accepts(b.prover, beatTx(b)(outputs = Seq(annotated, b.tipOut)))
    }
  }
}
