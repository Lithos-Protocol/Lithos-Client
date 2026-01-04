package evaluation

import lfsm.NISPTree
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.slf4j.{Logger, LoggerFactory}
import sigma.Colls
import sigma.exceptions.InterpreterException
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder}

import scala.util.Try

case class InvalidFormatProof(contract: Contract, miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                              fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("InvalidFormatProof")

}


