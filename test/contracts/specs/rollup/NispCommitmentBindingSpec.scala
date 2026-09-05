package contracts.specs.rollup

import org.ergoplatform.appkit.{ContextVar, ErgoValue}
import org.scalatest.propspec.AnyPropSpec

class NispCommitmentBindingSpec extends AnyPropSpec with FraudProofSpecBase {
  private val score = 100000L

  property("every payload proof requires exactly 40 authenticated bytes and the same score prefix") {
    withCtx { ctx =>
      val raw = nispBytes(score, realisticNispSize)
      val entry = nisp.NispCommitment.fromPayload(raw)
      val invalid = Seq(entry.bytes.take(7), entry.bytes.take(39), entry.bytes :+ 0.toByte,
        entry.copy(score = score + 1).bytes)
      set(ctx).ordered.tail.foreach { script =>
        invalid.foreach { bytes =>
          val f = fraud(ctx, script, raw, score,
            extraVars = Seq(ContextVar.of(3.toByte, evaluation.NTable.ergoValue)), committedEntry = Some(bytes))
          findsNoFraud(f.prover, fraudTx(f)())
        }
      }
    }
  }

  property("every payload proof rejects a wrong preimage before parsing hostile bytes") {
    withCtx { ctx =>
      set(ctx).ordered.tail.foreach { script =>
        val f = fraud(ctx, script, nispBytes(score, realisticNispSize), score,
          extraVars = Seq(ContextVar.of(3.toByte, evaluation.NTable.ergoValue)))
        val wrong = f.fpIn.setCtxVars((f.fpIn.ctxVars.filterNot(_.getId == 4.toByte) :+
          ContextVar.of(4.toByte, ErgoValue.of(Array.fill[Byte](9)(-1)))): _*)
        findsNoFraud(f.prover, fraudTx(f)(inputs = Seq(f.evalIn, wrong)))
      }
    }
  }

  property("every payload proof rejects an absent or short payload cleanly") {
    withCtx { ctx =>
      set(ctx).ordered.tail.foreach { script =>
        val f = fraud(ctx, script, nispBytes(score, realisticNispSize), score,
          extraVars = Seq(ContextVar.of(3.toByte, evaluation.NTable.ergoValue)))
        val absent = f.fpIn.setCtxVars(f.fpIn.ctxVars.filterNot(_.getId == 4.toByte): _*)
        findsNoFraud(f.prover, fraudTx(f)(inputs = Seq(f.evalIn, absent)))
        Seq(Array.emptyByteArray, Array.fill[Byte](7)(0)).foreach { bytes =>
          val wrong = f.fpIn.setCtxVars((f.fpIn.ctxVars.filterNot(_.getId == 4.toByte) :+
            ContextVar.of(4.toByte, ErgoValue.of(bytes))): _*)
          findsNoFraud(f.prover, fraudTx(f)(inputs = Seq(f.evalIn, wrong)))
        }
      }
    }
  }
}
