package work.lithos
package mutations

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import scorex.crypto.hash.Blake2b256
import sigmastate.Values.{ErgoTree, FalseSigmaProp, TrueSigmaProp}

case class Contract(ergoTree: ErgoTree, mutators: Seq[Mutator] = Seq.empty[Mutator]) {
  def ergoContract(ctx: BlockchainContext): ErgoContract = ctx.newContract(ergoTree)

  def propBytes: Array[Byte] = ergoTree.bytes

  def hashedPropBytes: Array[Byte] = Blake2b256.hash(propBytes)


  def mainnetAddress: Address = Address.fromErgoTree(ergoTree, NetworkType.MAINNET)
  def testnetAddress: Address = Address.fromErgoTree(ergoTree, NetworkType.TESTNET)

  def address(networkType: NetworkType): Address = Address.fromErgoTree(ergoTree, networkType)

  override def toString: String = Hex.toHexString(hashedPropBytes)

  override def equals(obj: Any): Boolean = {
    obj match {
      case c: Contract => c.ergoTree.equals(this.ergoTree)
      case _ => false
    }
  }
}

object Contract {
  final val SIGMA_TRUE: Contract = new Contract(ErgoTree.fromProposition(TrueSigmaProp))
  final val SIGMA_FALSE: Contract = new Contract(ErgoTree.fromProposition(FalseSigmaProp))

  def apply(ergoTree: ErgoTree, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    new Contract(ergoTree, mutators)
  }

  def fromAddressString(address: String, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(Address.create(address).toErgoContract.getErgoTree, mutators)
  }

  def fromErgoContract(contract: ErgoContract, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(contract.getErgoTree, mutators)
  }

  def fromAddress(address: Address, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract(address.toErgoContract.getErgoTree, mutators)
  }

  def fromErgoScript(ctx: BlockchainContext, constants: Constants, script: String, mutators: Seq[Mutator] = Seq.empty[Mutator]): Contract = {
    Contract.fromErgoContract(ctx.compileContract(constants, script), mutators)
  }
}
