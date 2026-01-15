package mutations

import org.ergoplatform.appkit.{Address, ErgoProver, SignedTransaction, UnsignedTransaction}
import work.lithos.mutations.Contract

case class NodeWallet(prover: ErgoProver) {
  val p2pk: Address = prover.getEip3Addresses.get(0)
  val contract: Contract = Contract.fromErgoContract(prover.getEip3Addresses.get(0).toErgoContract)

  def sign(uTx: UnsignedTransaction): SignedTransaction = {
    prover.sign(uTx)
  }
}