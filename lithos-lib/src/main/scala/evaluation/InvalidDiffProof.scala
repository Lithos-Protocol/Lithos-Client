package evaluation

import lfsm.states.NISPTree
import mutations.BoxLoader
import nisp.NISP
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoProver, ErgoValue, Parameters, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Longs
import sigma.AvlTree
import sigma.data.CBigInt
import work.lithos.mutations.{Contract, InputUTXO, Mutator, TxBuilder, TxContext, UTXO}

import scala.util.{Success, Try}
import sigma.exceptions.InterpreterException
case class InvalidDiffProof(contract: Contract, miner: Array[Byte], nispTree: NISPTree, evalInput: InputUTXO,
                            fpControl: InputUTXO)
  extends FraudProof(contract, miner, nispTree, evalInput, fpControl) {
  override val logger: Logger = LoggerFactory.getLogger("InvalidDiffProof")

}


