package transactions.batching.forwards

import contracts.specs.forwards.DFSpecBase
import forwards.contracts.DFTerms
import mutations.NodeWallet
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver}
import support.{FakeNodeContext, LDNodeFixtures}
import work.lithos.mutations.{InputUTXO, UTXO}

/**
 * Accumulator and position boxes as the node reports them, at the contracts `DFSpecBase` builds from the pinned
 * templates, so what the client builds is signed against the real scripts. Boxes go through `DFBoxes` the way
 * the batcher reads them, which also exercises the parser on every genuine shape.
 */
trait ForwardsFixtures extends DFSpecBase {

  /** A wallet like the client's: mnemonic-derived, EIP-3 index 0 the reward and change address. */
  protected def walletOf(client: ErgoClient): NodeWallet = NodeWallet(FakeNodeContext.proverWith(client, 1))

  protected def nodeBox(ctx: BlockchainContext, utxo: UTXO, index: Int, txId: String = "ab" * 32): NodeBox =
    LDNodeFixtures.nodeBox(ctx, utxo, index, txId)

  protected def accAt(ctx: BlockchainContext, s: AccState, index: Int = 0): DFAccState = {
    val box = nodeBox(ctx, accUTXO(s), index)
    DFAccState(DFBoxes.accumulator(box, contracts).get, DFBoxes.input(box, ctx).get)
  }

  /** A position the batcher would read, long and short as given. Each needs its own `index` to be distinct. */
  protected def positionAt(ctx: BlockchainContext, long: ErgoProver, short: ErgoProver, t: DFTerms,
                           index: Int, value: Long = POSITION_ERG): (DFPosition, InputUTXO) = {
    val box = nodeBox(ctx, positionUTXO(long, short, t, value = value), index, "cd" * 32)
    DFBoxes.position(box, contracts).get -> DFBoxes.input(box, ctx).get
  }

  /** A plain box at the wallet's own address, as the engine hands out for funding. */
  protected def walletBox(ctx: BlockchainContext, wallet: NodeWallet, value: Long, index: Int = 7): InputUTXO =
    inputAt(UTXO(wallet.contract, value), ctx, index, "ef" * 32)
}
