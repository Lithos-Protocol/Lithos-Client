package state.messages

import node.model.{IndexedBlock, IndexedBox, IndexedTransaction, NodeAsset, NodeBlock, NodeBox, NodeDataInput, NodeInput, NodeSpendingProof, NodeTransaction, WalletBox}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.Token

/**
 * Maps the node model onto the synchronization message types.
 *
 * The mirror of `node.MutationConversions`, which does the same job for the `work.lithos.mutations`
 * types. Both directions read the same node model, so a call site picks whichever shape it needs
 * without the node layer knowing either exists.
 */
object NodeSync {

  def token(asset: NodeAsset): Token = Token(ErgoId.create(asset.tokenId), asset.amount)

  def spendingProof(proof: NodeSpendingProof): InputSpendingProof =
    InputSpendingProof(proof.proofBytes, proof.extension)

  def txInput(in: NodeInput): TxInput = TxInput(in.boxId, Some(spendingProof(in.spendingProof)))

  def txDataInput(in: NodeDataInput): TxInput = TxInput(in.boxId, None)

  def txOutput(box: NodeBox): TxOutput = TxOutput(
    id = box.boxId,
    value = box.value,
    ergoTree = box.ergoTree,
    registers = box.additionalRegisters.ordered,
    assets = box.assets.map(token),
    txId = box.transactionId,
    creationHeight = box.creationHeight,
    index = box.index
  )

  def txOutput(box: IndexedBox): TxOutput = txOutput(box.box)

  def txOutput(box: WalletBox): TxOutput = txOutput(box.box)

  def blockTx(tx: NodeTransaction): BlockTx = BlockTx(
    id = tx.id,
    inputs = tx.inputs.map(txInput),
    dataInputs = tx.dataInputs.map(txDataInput),
    outputs = tx.outputs.map(txOutput)
  )

  def blockTx(tx: IndexedTransaction): BlockTx = BlockTx(
    id = tx.id,
    inputs = tx.inputs.map(b => TxInput(b.boxId, b.spendingProof.map(spendingProof))),
    dataInputs = tx.dataInputs.map(txDataInput),
    outputs = tx.outputs.map(txOutput)
  )

  def blockInfo(block: NodeBlock): BlockInfo =
    BlockInfo(block.header.id, block.header.height, block.transactions.map(blockTx))

  def blockInfo(block: IndexedBlock): BlockInfo =
    BlockInfo(block.header.id, block.header.height, block.blockTransactions.map(blockTx))

  implicit class NodeBlockSyncOps(val block: NodeBlock) extends AnyVal {
    def toBlockInfo: BlockInfo = blockInfo(block)
  }

  implicit class IndexedBlockSyncOps(val block: IndexedBlock) extends AnyVal {
    def toBlockInfo: BlockInfo = blockInfo(block)
  }

  implicit class NodeTransactionSyncOps(val tx: NodeTransaction) extends AnyVal {
    def toBlockTx: BlockTx = blockTx(tx)
  }

  implicit class IndexedTransactionSyncOps(val tx: IndexedTransaction) extends AnyVal {
    def toBlockTx: BlockTx = blockTx(tx)
  }

  implicit class NodeBoxSyncOps(val box: NodeBox) extends AnyVal {
    def toTxOutput: TxOutput = txOutput(box)
  }

  implicit class IndexedBoxSyncOps(val box: IndexedBox) extends AnyVal {
    def toTxOutput: TxOutput = txOutput(box)
  }

  implicit class WalletBoxSyncOps(val box: WalletBox) extends AnyVal {
    def toTxOutput: TxOutput = txOutput(box)
  }
}
