package evaluation

import lfsm.states.Rollup
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.slf4j.{Logger, LoggerFactory}
import sigma.exceptions.InterpreterException
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder}

import scala.util.Try

case class NonUniqueHeadersProof(contract: Contract, miner: Array[Byte], nispTree: Rollup, evalInput: InputUTXO,
                                 fpControl: InputUTXO, payload: Option[nisp.ResolvedNisp] = None)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl, payload) {
  override val logger: Logger = LoggerFactory.getLogger("NonUniqueHeadersProof")

}


