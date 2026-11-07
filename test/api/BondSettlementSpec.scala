package api

import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, IndexedTransaction, NodeBox, NodeSpendingProof}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import support.FakeNodeContext
import transactions.batching.sigmafi.{SigmaFiBoxes, SigmaFiFixtures, SigmaFiTransactions, SigmaFiUserTransactions}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

class BondSettlementSpec extends AnyPropSpec with SigmaFiFixtures with MockitoSugar {
  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))

  private def history(ctx: BlockchainContext, box: NodeBox, signed: SignedTransaction, height: Int,
                       extension: Map[String, String]): (IndexedBox, IndexedTransaction) = {
    val id = transactions.candidate.BlockTxMessages.CandidateTx.txId(signed)
    val input = IndexedBox(box, "", ctx.getHeight - 1, 1L, Some(id), Some(height),
      Some(NodeSpendingProof("", extension)))
    val outputs = signed.getOutputsToSpend.asScala.zipWithIndex.map { case (b, i) =>
      IndexedBox(nodeBox(ctx, InputUTXO(b).toUTXO, i, id), "", height, i.toLong)
    }
    input -> IndexedTransaction(id, Seq(input), Seq.empty, outputs.toVector, height, 1, "ab" * 32, 1700000000000L, 0, 1L, 1000)
  }

  private def source(box: IndexedBox, tx: IndexedTransaction): NodeApi = {
    val api = mock[NodeApi]
    when(api.indexedBoxById(box.boxId)).thenReturn(Success(Some(box)))
    when(api.indexedTransactionById(tx.id)).thenReturn(Success(Some(tx)))
    api
  }

  property("ERG and token repayments expose the confirmed payout and returned collateral after the vault is claimed") {
    client.execute { ctx =>
      Seq(None, Some(tokenA)).zipWithIndex.foreach { case (repayToken, i) =>
        val box = nodeBox(ctx, bondUTXO(repayToken, wallet.contract, ctx.getHeight + 100,
          collateral = Seq(Token(tokenB, 7L)), repayment = if (repayToken.isEmpty) 2 * Erg else 550L), i)
        val bond = SigmaFiBoxes.bond(box).get
        val funding = UTXO(wallet.contract, 10 * Erg, repayToken.toSeq.map(Token(_, 550L))).toDummyInput(ctx)
        val paid = wallet.sign(SigmaFiUserTransactions.repay(ctx, wallet, bond, SigmaFiBoxes.input(box, ctx).get,
          wallet.contract).build(Seq(funding)))
        val (indexed, tx) = history(ctx, box, paid, ctx.getHeight, Map.empty)
        val result = BondSettlement.read(source(indexed, tx), box.boxId, bond.asset.toString, ctx.getHeight)
        result.status shouldBe "Repaid"
        result.settlement.get.txId shouldBe tx.id
        result.settlement.get.timestamp shouldBe Some(tx.timestamp)
        result.settlement.get.returnedCollateral.get.tokens.map(_.tokenId) shouldBe Seq(tokenB.toString)
        result.settlement.get.liquidationHash shouldBe None
        result.settlement.get.vault.assetId shouldBe bond.asset.toString
        // The reader uses historical transaction outputs, not the vault's current unspent set.
        result.settlement.get.vault.nanoErgs shouldBe (if (repayToken.isEmpty) 2 * Erg else MinBox).toString
      }
    }
  }

  property("a liquidation is identified by its authenticated context expression, with the actual vault contents") {
    client.execute { ctx =>
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, ctx.getHeight + 1, collateral = Seq(Token(tokenB, 9L))))
      val bond = SigmaFiBoxes.bond(box).get
      val closed = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> SigmaFiBoxes.input(box, ctx).get),
        wallet.contract, 0L, ctx.getHeight + 1)
      val (indexed, tx) = history(ctx, box, closed.signed, ctx.getHeight + 1, Map("0" -> ErgoValue.of(liq.bytes).toHex))
      val result = BondSettlement.read(source(indexed, tx), box.boxId, bond.asset.toString, ctx.getHeight + 1)
      result.status shouldBe "Liquidated"
      result.settlement.get.vault.nanoErgs shouldBe (box.value - bond.batchingFee).toString
      result.settlement.get.vault.tokens.map(_.amount) shouldBe Seq("9")
      result.settlement.get.returnedCollateral shouldBe None
      result.settlement.get.liquidationHash shouldBe Some(liq.hashHex)

      Seq(None, Some(NodeSpendingProof("", Map("0" -> ErgoValue.of(Array[Byte](1)).toHex))),
        Some(NodeSpendingProof("", Map("127" -> ErgoValue.of(0).toHex)))).foreach { proof =>
        val incomplete = indexed.copy(spendingProof = proof)
        val changed = tx.copy(inputs = Seq(incomplete))
        val uncertain = BondSettlement.read(source(incomplete, changed), box.boxId, bond.asset.toString, ctx.getHeight + 1)
        uncertain.status shouldBe "Unknown"
        uncertain.settlement shouldBe None
        uncertain.reason should not be empty
      }
    }
  }

  property("a live bond, missing index data and a failed node call are never reported as settled") {
    client.execute { ctx =>
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, ctx.getHeight + 100))
      val api = mock[NodeApi]
      val indexed = IndexedBox(box, "", ctx.getHeight - 1, 1L)
      when(api.indexedBoxById(box.boxId)).thenReturn(Success(Some(indexed)))
      BondSettlement.read(api, box.boxId, assetId.toString, ctx.getHeight).status shouldBe "Open"
      BondSettlement.read(api, box.boxId, assetId.toString, ctx.getHeight + 100).status shouldBe "Matured"
      when(api.indexedBoxById(box.boxId)).thenReturn(Success(None))
      BondSettlement.read(api, box.boxId, assetId.toString, ctx.getHeight).status shouldBe "Unknown"
      when(api.indexedBoxById(anyString())).thenReturn(Failure(new RuntimeException("unavailable")))
      BondSettlement.read(api, box.boxId, assetId.toString, ctx.getHeight).status shouldBe "Unknown"
    }
  }

  property("custom early liquidation is identified by its spend path, not maturity or payout currency") {
    client.execute { ctx =>
      val expression = Contract.SIGMA_TRUE.valueBytes
      val rule = sigmafi.contracts.LiquidationScript("custom", expression)
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, ctx.getHeight + 100,
        collateral = Seq(Token(tokenB, 7L)), liqHash = rule.hash))
      val bond = SigmaFiBoxes.bond(box).get
      val protocolInput = SigmaFiBoxes.input(box, ctx).get.withCtxVar(0.toByte, ErgoValue.of(expression))
      val vault = vaultUTXO(protocolInput.id, bond.asset, 2 * Erg)
      val executor = UTXO(wallet.contract, 3 * Erg - SigmaFiUserTransactions.TxFee, Seq(Token(tokenB, 7L)))
      val unsigned = TxBuilder(ctx).setInputs(protocolInput).setOutputs(vault, executor)
        .buildTx(SigmaFiUserTransactions.TxFee, wallet.p2pk, Seq(Token(bond.asset, 1L)))
      val closed = wallet.sign(unsigned)
      val (indexed, tx) = history(ctx, box, closed, ctx.getHeight, Map("0" -> ErgoValue.of(expression).toHex))
      val state = BondSettlement.read(source(indexed, tx), box.boxId, bond.asset.toString, ctx.getHeight)
      state.status shouldBe "Liquidated"
      state.settlement.get.vault.tokens shouldBe empty
      state.settlement.get.vault.nanoErgs shouldBe (2 * Erg).toString
      state.settlement.get.liquidationHash shouldBe Some(rule.hashHex)
    }
  }

  property("a batched liquidation uses the bond's actual input index for its payout") {
    client.execute { ctx =>
      val boxes = (1 to 2).map(i => nodeBox(ctx, bondUTXO(None, wallet.contract, ctx.getHeight + 1,
        collateral = Seq(Token(tokenB, i.toLong)), asset = org.ergoplatform.sdk.ErgoId.create(f"$i%02x" * 32)), i))
      val closed = SigmaFiTransactions.liquidate(ctx, wallet,
        boxes.map(b => SigmaFiBoxes.bond(b).get -> SigmaFiBoxes.input(b, ctx).get), wallet.contract, 0L, ctx.getHeight + 1)
      val (indexed, basic) = history(ctx, boxes(1), closed.signed, ctx.getHeight + 1, Map("0" -> ErgoValue.of(liq.bytes).toHex))
      val tx = basic.copy(inputs = Seq(indexed.copy(box = boxes.head), indexed))
      val state = BondSettlement.read(source(indexed, tx), boxes(1).boxId, boxes(1).assets.head.tokenId, ctx.getHeight + 1)
      state.status shouldBe "Liquidated"
      state.settlement.get.vault.tokens.map(_.amount) shouldBe Seq("2")
    }
  }
}
