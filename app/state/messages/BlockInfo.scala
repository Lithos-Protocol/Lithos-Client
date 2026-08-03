package state.messages

import org.ergoplatform.sdk.JavaHelpers
import org.ergoplatform.restapi.client.FullBlock

case class BlockInfo(id: String, height: Int, txs: Seq[BlockTx])

object BlockInfo {
  def fromSync(fb: FullBlock): BlockInfo = {
    BlockInfo(
      fb.getHeader.getId,
      fb.getHeader.getHeight,
      JavaHelpers.toIndexedSeq(fb.getBlockTransactions.getTransactions).map(BlockTx.fromSync))
  }
}