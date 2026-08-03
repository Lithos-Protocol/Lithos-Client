package node

import node.model.{IndexedBox, NodeAsset, NodeBox, NodeTransaction, WalletBox}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Bridges the node model onto the `work.lithos.mutations` types.
 *
 * Kept separate from the model so `node.model` stays free of appkit and mutations, which is what
 * lets the Play app map the same model onto its own synchronization types.
 */
object MutationConversions {

  implicit class NodeAssetOps(val asset: NodeAsset) extends AnyVal {
    def toToken: Token = Token(ErgoId.create(asset.tokenId), asset.amount)
  }

  implicit class NodeBoxOps(val box: NodeBox) extends AnyVal {

    def contract: Contract = Contract(ErgoTree.fromHex(box.ergoTree))

    def tokens: Seq[Token] = box.assets.map(a => Token(ErgoId.create(a.tokenId), a.amount))

    def registerValues: Seq[ErgoValue[_]] = box.additionalRegisters.ordered.map(ErgoValue.fromHex)

    def toUTXO: UTXO =
      UTXO(contract, box.value, tokens, registerValues, Some(box.creationHeight))

    def toInputUTXO(ctx: BlockchainContext): InputUTXO =
      toUTXO.toInput(ctx, ErgoId.create(box.transactionId), box.index.toShort)

    def id: ErgoId = ErgoId.create(box.boxId)
  }

  implicit class IndexedBoxOps(val indexed: IndexedBox) extends AnyVal {
    def toUTXO: UTXO = new NodeBoxOps(indexed.box).toUTXO

    def toInputUTXO(ctx: BlockchainContext): InputUTXO = new NodeBoxOps(indexed.box).toInputUTXO(ctx)
  }

  implicit class WalletBoxOps(val walletBox: WalletBox) extends AnyVal {
    def toUTXO: UTXO = new NodeBoxOps(walletBox.box).toUTXO

    def toInputUTXO(ctx: BlockchainContext): InputUTXO = new NodeBoxOps(walletBox.box).toInputUTXO(ctx)
  }

  implicit class NodeTransactionOps(val tx: NodeTransaction) extends AnyVal {

    def outputUTXOs: Seq[UTXO] = tx.outputs.map(o => new NodeBoxOps(o).toUTXO)

    def outputInputUTXOs(ctx: BlockchainContext): Seq[InputUTXO] =
      tx.outputs.map(o => new NodeBoxOps(o).toInputUTXO(ctx))

    def spentBoxIds: Seq[ErgoId] = tx.inputs.map(i => ErgoId.create(i.boxId))
  }
}
