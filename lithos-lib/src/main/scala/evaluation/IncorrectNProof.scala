package evaluation

import lfsm.states.Rollup
import org.ergoplatform.appkit.{ContextVar, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * The N table is what this contract checks a miner's claimed N against, so it rides on the proof
 * input as a fourth context variable. That extra variable is the ONLY thing this proof does
 * differently — everything else, including whether the mutator emits its own fee output, is the
 * shared implementation.
 */
case class IncorrectNProof(contract: Contract, miner: Array[Byte], nispTree: Rollup, evalInput: InputUTXO,
                           fpControl: InputUTXO, payload: Option[nisp.ResolvedNisp] = None)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl, payload) {
  override val logger: Logger = LoggerFactory.getLogger("IncorrectNProof")

  override protected def extraCtxVars: Seq[ContextVar] =
    Seq(ContextVar.of(3.toByte, NTable.ergoValue))
}
