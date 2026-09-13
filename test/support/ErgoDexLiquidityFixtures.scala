package support

import node.MutationConversions._
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import sigma.ast.{ByteArrayConstant, Constant, ErgoTree, LongConstant, SType}
import transactions.batching.ergodex.{ErgoDexOrder, OrderKind}

import scala.io.Source

/** Published native v1 contract bytes with deterministic pool and order terms. */
object ErgoDexLiquidityFixtures {
  val Fee = 6000000L
  val MinerFee = 2000000L
  val CirculatingLP = 1000000000L

  def poolBox(ctx: BlockchainContext): NodeBox = {
    val original = ErgoDexFixtures.poolBox
    val box = original.copy(value = 1000000000000L, assets = Seq(original.assets.head,
      original.assets(1).copy(amount = Long.MaxValue - CirculatingLP),
      original.assets(2).copy(amount = 1000000L)))
    box.copy(boxId = box.toInputUTXO(ctx).id.toString)
  }

  def order(ctx: BlockchainContext, kind: OrderKind, amountX: Long = 1000000000L,
            amountY: Long = 1000L, amountLP: Long = 1000000L, fee: Long = Fee): ErgoDexOrder = {
    val name = if (kind == OrderKind.Deposit) "deposit" else "redeem"
    val source = Source.fromResource(s"ergodex/$name-v1.hex")
    val tree = try ErgoTree.fromHex(source.mkString.trim) finally source.close()
    val pool = poolBox(ctx)
    val owner = ErgoTree.fromHex(ErgoDexFixtures.orderBox.ergoTree).constants.head
    val nft = ByteArrayConstant(Hex.decode(pool.assets.head.tokenId))
    val terms: Map[Int, Constant[SType]] =
      if (kind == OrderKind.Deposit) Map(0 -> owner, 2 -> LongConstant(amountX), 12 -> nft,
        15 -> LongConstant(fee), 16 -> LongConstant(amountX), 17 -> LongConstant(fee), 22 -> LongConstant(MinerFee))
      else Map(0 -> owner, 11 -> nft, 12 -> LongConstant(fee), 16 -> LongConstant(MinerFee))
    val constants = tree.constants.zipWithIndex.map { case (value, index) => terms.getOrElse(index, value) }
    val script = ErgoTree(tree.header, constants, tree.root.right.get)
    val asset = if (kind == OrderKind.Deposit) NodeAsset(pool.assets(2).tokenId, amountY)
      else NodeAsset(pool.assets(1).tokenId, amountLP)
    val value = fee + 2000000L + (if (kind == OrderKind.Deposit) amountX else 0L)
    val box = ErgoDexFixtures.orderBox.copy(value = value, ergoTree = script.bytesHex, assets = Seq(asset))
    ErgoDexOrder.parse(box.copy(boxId = box.toInputUTXO(ctx).id.toString)).get
  }

  def swap(ctx: BlockchainContext): ErgoDexOrder = {
    val original = ErgoDexFixtures.orderBox
    val tree = ErgoTree.fromHex(original.ergoTree)
    val script = ErgoTree(tree.header, tree.constants.updated(10, LongConstant(1L)), tree.root.right.get)
    val box = original.copy(ergoTree = script.bytesHex, assets = original.assets.map(_.copy(amount = 1000L)))
    ErgoDexOrder.parse(box.copy(boxId = box.toInputUTXO(ctx).id.toString)).get
  }
}
