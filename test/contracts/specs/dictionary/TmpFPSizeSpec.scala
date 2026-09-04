package contracts.specs.dictionary

import lfsm.contracts.FraudProofContracts
import org.scalatest.propspec.AnyPropSpec

class TmpFPSizeSpec extends AnyPropSpec with DictionarySpecBase {
  property("size: FP_NonMatchingCommitment") {
    withCtx { ctx =>
      val c = FraudProofContracts.mkNonMatchingCommitmentContract(ctx.getNetworkType, mdToken)
      println(s"[size] FP_NonMatchingCommitment = ${c.ergoTree.bytes.length} bytes, v${c.ergoTree.version}")
    }
  }
}
