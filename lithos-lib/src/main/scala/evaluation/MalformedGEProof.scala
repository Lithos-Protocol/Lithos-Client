package evaluation

import lfsm.states.Rollup
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO}

case class MalformedGEProof(contract: Contract, miner: Array[Byte], nispTree: Rollup, evalInput: InputUTXO,
                            fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("MalformedGEProof")

}


