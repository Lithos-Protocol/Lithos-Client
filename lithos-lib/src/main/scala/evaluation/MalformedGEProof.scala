package evaluation

import lfsm.states.Rollup
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO}

case class MalformedGEProof(contract: Contract, miner: Array[Byte], nispTree: Rollup, evalInput: InputUTXO,
                            fpControl: InputUTXO, payload: Option[nisp.ResolvedNisp] = None)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl, payload) {
  override val logger: Logger = LoggerFactory.getLogger("MalformedGEProof")

}


