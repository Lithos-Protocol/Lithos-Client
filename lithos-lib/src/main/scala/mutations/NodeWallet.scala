package mutations

import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{Address, ErgoProver, SignedTransaction, UnsignedTransaction}
import work.lithos.mutations.Contract

import scala.collection.JavaConverters._

/**
 * The client's signing identity: the master key plus every EIP-3 index the prover was built with.
 *
 * `node.numAddresses` decides how many indices that is. It must be at least
 * `emission.maxLenderKeys`, because lender keys are derived EIP-3 addresses and their boxes — permit
 * returns, swept coinbases — are unspendable by a prover that does not hold the matching secret.
 */
case class NodeWallet(prover: ErgoProver) {
  val p2pk: Address = prover.getEip3Addresses.get(0)
  val contract: Contract = Contract.fromErgoContract(prover.getEip3Addresses.get(0).toErgoContract)

  /** Every EIP-3 address this prover holds a secret for, index 0 first. */
  lazy val addresses: Seq[Address] = prover.getEip3Addresses.asScala.toSeq

  /** P2PK ErgoTrees this prover can sign for. A box outside this set fails at signing, not at selection. */
  lazy val signableTrees: Set[String] =
    (prover.getAddress +: addresses).map(a => Contract.fromAddress(a).ergoTreeHex).toSet

  /**
   * Miner reward scripts for every address, keyed by ergoTree hex.
   *
   * A coinbase pays `pk && HEIGHT > creationHeight + MINER_REWARD_DELAY`, not a plain key, so no
   * wallet reports these boxes and they have to be looked up by ergoTree. This prover can sign them —
   * the guard is a height check, not a second key.
   */
  lazy val rewardTrees: Map[String, Address] =
    addresses.map(a =>
      Contract(ErgoTreePredef.rewardOutputScript(NodeWallet.MINER_REWARD_DELAY, a.getPublicKey))
        .ergoTreeHex -> a).toMap

  def sign(uTx: UnsignedTransaction): SignedTransaction = {
    prover.sign(uTx)
  }
}

object NodeWallet {

  /** Blocks a coinbase stays locked. Ergo consensus, not a Lithos parameter. */
  final val MINER_REWARD_DELAY: Int = 720
}
