package stats

import mutations.NodeWallet
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigmafi.contracts.SigmaFiContracts
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, NodeSync, TxInput, TxOutput}
import support.FakeNodeContext
import transactions.batching.sigmafi.{SigmaFiBoxes, SigmaFiFixtures, SigmaFiLiquidation, SigmaFiTransactions}
import work.lithos.mutations.Token

import scala.collection.JavaConverters._

/**
 * SigmaFi liquidations as the statistics see them in a block: signed by the client's own builder against the
 * pinned contracts, then read back the way `NodeMiningStatsSource` reads a block.
 */
class SigmaFiStatisticsSpec extends AnyPropSpec with SigmaFiFixtures {

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))
  private val fee = 50000000L

  private def bonds(ctx: BlockchainContext): Seq[NodeBox] = (1 to 2).map { i =>
    nodeBox(ctx, bondUTXO(None, contractOf(miner(ctx)), 100, fee = fee + i, asset = ErgoId.create(f"$i%02x" * 32)), i, "e7" * 32)
  }

  private def liquidation(ctx: BlockchainContext, boxes: Seq[NodeBox], minerFee: Long): SigmaFiLiquidation =
    SigmaFiTransactions.liquidate(ctx, wallet,
      boxes.map(b => SigmaFiBoxes.bond(b).get -> SigmaFiBoxes.input(b, ctx).get), wallet.contract, minerFee, ctx.getHeight + 1)

  /** The signed liquidation as a block holding it reports it. `withScript` keeps each input's context variable 0. */
  private def inBlock(l: SigmaFiLiquidation, inputs: Seq[NodeBox], withScript: Boolean = true): (BlockInfo, BlockTx) = {
    val id = l.txId
    val outputs = l.signed.getOutputsToSpend.asScala.toVector.zipWithIndex.map { case (b, i) =>
      TxOutput(b.getId.toString, b.getValue, Hex.toHexString(b.getErgoTree.bytes),
        b.getRegisters.asScala.map(_.toHex).toSeq, b.getTokens.asScala.map(Token.ergo).toSeq, id, b.getCreationHeight, i)
    }
    val script = ErgoValue.of(SigmaFiContracts.MaturityLiquidation.bytes).toHex
    val tx = BlockTx(id, inputs.map(b => TxInput(b.boxId,
      if (withScript) Some(InputSpendingProof(Map("0" -> script))) else None)), Seq.empty, outputs)
    BlockInfo("block", 1, Seq(tx), resolvedInputs = inputs.map(b => b.boxId -> NodeSync.txOutput(b)).toMap) -> tx
  }

  property("a fee-less liquidation is one record per bond, each its own fee, credited to this miner") {
    client.execute { ctx =>
      val boxes = bonds(ctx)
      val (block, tx) = inBlock(liquidation(ctx, boxes, 0L), boxes)
      BatchingStatistics.read(block, tx, NetworkType.MAINNET, wallet.contract.ergoTreeHex) shouldBe Vector(
        BatchingFee(tx.id, boxes.head.boxId, "sigmafi", (fee + 1).toString, "0", local = true),
        BatchingFee(tx.id, boxes(1).boxId, "sigmafi", (fee + 2).toString, "0", local = true))
      BatchingStatistics.read(block, tx, NetworkType.MAINNET, "00").map(_.local) shouldBe Vector(false, false)
    }
  }

  property("a broadcast liquidation's miner fee is split across its records, so every record shows one") {
    client.execute { ctx =>
      val boxes = bonds(ctx)
      val (block, tx) = inBlock(liquidation(ctx, boxes, Parameters.MinFee), boxes)
      BatchingStatistics.read(block, tx, NetworkType.MAINNET, wallet.contract.ergoTreeHex)
        .map(_.transactionFeeNanoErg) shouldBe Vector((Parameters.MinFee / 2).toString, (Parameters.MinFee / 2).toString)
    }
  }

  property("a bond spent without a liquidation script, as a repayment is, is not counted") {
    client.execute { ctx =>
      val boxes = bonds(ctx)
      val (block, tx) = inBlock(liquidation(ctx, boxes, 0L), boxes, withScript = false)
      BatchingStatistics.read(block, tx, NetworkType.MAINNET, wallet.contract.ergoTreeHex)
        .filter(_.protocol == "sigmafi") shouldBe empty
    }
  }

  property("custom liquidation expressions are not priced as maturity batching fees") {
    client.execute { ctx =>
      val boxes = bonds(ctx)
      val (block, tx) = inBlock(liquidation(ctx, boxes, 0L), boxes)
      val custom = ErgoValue.of(Array[Byte](1, 2, 3)).toHex
      val changed = tx.copy(inputs = tx.inputs.map(_.copy(spendingProof = Some(InputSpendingProof(Map("0" -> custom))))))
      BatchingStatistics.read(block.copy(txs = Seq(changed)), changed, NetworkType.MAINNET,
        wallet.contract.ergoTreeHex).filter(_.protocol == "sigmafi") shouldBe empty
    }
  }

  property("maturity attribution requires settlement tags, exact collateral, and disappearance of the singleton") {
    client.execute { ctx =>
      val boxes = bonds(ctx).take(1)
      val (block, tx) = inBlock(liquidation(ctx, boxes, 0L), boxes)
      val wrongTag = tx.outputs.head.copy(registers = tx.outputs.head.registers.updated(0, ErgoValue.of(assetId.getBytes).toHex))
      val wrongTokens = tx.outputs.head.copy(assets = Seq(Token(tokenB, 1L)))
      val singletonSurvives = tx.outputs.last.copy(assets = Seq(Token(ErgoId.create(boxes.head.assets.head.tokenId), 1L)))
      Seq(tx.copy(outputs = wrongTag +: tx.outputs.tail), tx.copy(outputs = wrongTokens +: tx.outputs.tail),
        tx.copy(outputs = tx.outputs.init :+ singletonSurvives)).foreach { changed =>
        BatchingStatistics.read(block, changed, NetworkType.MAINNET, wallet.contract.ergoTreeHex)
          .filter(_.protocol == "sigmafi") shouldBe empty
      }
    }
  }
}
