package evaluation

import lfsm.states.Rollup
import org.slf4j.{Logger, LoggerFactory}
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * Binds a super-share to the miner who submitted it, by walking the genesis transaction the share
 * carries down to the holding output's R8.
 *
 * Needs nothing beyond the three variables every proof carries: the transaction and the collateral
 * box are already inside the NISP, and the rollup NFT is on the evaluation box.
 */
case class MalformedGenesisProof(contract: Contract, miner: Array[Byte], nispTree: Rollup,
                                 evalInput: InputUTXO, fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("MalformedGenesisProof")
}
