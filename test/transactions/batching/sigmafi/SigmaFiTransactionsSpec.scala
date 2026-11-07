package transactions.batching.sigmafi

import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigmafi.contracts.SigmaFiContracts
import support.FakeNodeContext
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._

/**
 * Liquidations the batcher builds. Each is signed, which runs every bond's script and the maturity liquidation
 * it executes, so a signature here is the pinned contracts accepting the transaction.
 */
class SigmaFiTransactionsSpec extends AnyPropSpec with SigmaFiFixtures {

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))

  /** The block a candidate is built for: one above the offline chain's tip. */
  private def nextBlock(ctx: BlockchainContext): Int = ctx.getHeight + 1

  private def bondAt(ctx: BlockchainContext, index: Int, repayToken: Option[ErgoId] = None, maturity: Int,
                     collateral: Seq[Token] = Seq.empty, fee: Long = 50000000L,
                     liqHash: Array[Byte] = liq.hash): (SigmaFiBond, InputUTXO) = {
    val box = nodeBox(ctx, bondUTXO(repayToken, contractOf(miner(ctx)), maturity, collateral = collateral, fee = fee,
      asset = ErgoId.create(f"$index%02x" * 32), liqHash = liqHash), index, "e7" * 32)
    SigmaFiBoxes.bond(box).get -> SigmaFiBoxes.input(box, ctx).get
  }

  private def ergIn(ctx: BlockchainContext, l: SigmaFiLiquidation): Long =
    l.signed.getOutputsToSpend.asScala.map(_.getValue.toLong).sum

  property("liquidate: an ERG bond settles whole into the vault, less its fee, tagged with its id and asset") {
    client.execute { ctx =>
      val (bond, input) = bondAt(ctx, 1, maturity = nextBlock(ctx), collateral = Seq(Token(tokenB, 3), Token(tokenA, 9)))
      val l = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> input), wallet.contract, 0L, nextBlock(ctx))
      val outs = l.signed.getOutputsToSpend.asScala
      outs should have size 2
      val vault = outs.head
      Contract(vault.getErgoTree) shouldBe SigmaFiContracts.vault
      vault.getValue.toLong shouldBe bond.box.value - bond.batchingFee
      vault.getTokens.asScala.map(t => t.getId.toString -> t.getValue.toLong) shouldBe Seq(tokenB.toString -> 3L, tokenA.toString -> 9L)
      vault.getRegisters.asScala.map(_.toHex) shouldBe Seq(
        ErgoValue.of(ErgoId.create(bond.boxId).getBytes).toHex, ErgoValue.of(bond.asset.getBytes).toHex)
      outs(1).getValue.toLong shouldBe bond.batchingFee
      l.feeBox.value shouldBe bond.batchingFee
      withClue("fee-less: everything the bond held is in the two outputs") { ergIn(ctx, l) shouldBe bond.box.value }
    }
  }

  property("liquidate: a token bond settles the same way, its repayment token playing no part") {
    client.execute { ctx =>
      val (bond, input) = bondAt(ctx, 2, repayToken = Some(tokenA), maturity = nextBlock(ctx) - 50)
      val l = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> input), wallet.contract, 0L, nextBlock(ctx))
      l.fees shouldBe bond.batchingFee
    }
  }

  property("liquidate: a batch pairs each bond with the output at its own index, and pays one fee box") {
    client.execute { ctx =>
      val bonds = (3 to 5).map(i => bondAt(ctx, i, maturity = nextBlock(ctx) - i, collateral = Seq(Token(tokenB, i.toLong)),
        fee = 50000000L + i))
      val l = SigmaFiTransactions.liquidate(ctx, wallet, bonds, wallet.contract, 0L, nextBlock(ctx))
      val outs = l.signed.getOutputsToSpend.asScala
      bonds.indices.foreach { i =>
        outs(i).getRegisters.asScala.head.toHex shouldBe ErgoValue.of(ErgoId.create(bonds(i)._1.boxId).getBytes).toHex
        outs(i).getTokens.asScala.head.getValue.toLong shouldBe bonds(i)._1.collateral.head.amount
      }
      outs.last.getValue.toLong shouldBe bonds.map(_._1.batchingFee).sum
      l.bundle(Set("ab" * 32)).capital.map(_.value) shouldBe Seq(bonds.map(_._1.batchingFee).sum)
    }
  }

  property("liquidate: a broadcast pays the miner fee out of the fees, the rest to the wallet") {
    client.execute { ctx =>
      val bonds = Seq(bondAt(ctx, 6, maturity = 100), bondAt(ctx, 7, maturity = 100))
      val l = SigmaFiTransactions.liquidate(ctx, wallet, bonds, wallet.contract, Parameters.MinFee, nextBlock(ctx))
      val outs = l.signed.getOutputsToSpend.asScala
      outs.find(o => Contract(o.getErgoTree) == Contract.FEE).map(_.getValue.toLong) shouldBe Some(Parameters.MinFee)
      l.feeBox.value shouldBe 2 * 50000000L - Parameters.MinFee
      Contract(l.feeBox.input.getErgoTree) shouldBe wallet.contract
    }
  }

  property("liquidate: a bond maturing in the block being built signs, though appkit's tip is a block lower") {
    client.execute { ctx =>
      val (bond, input) = bondAt(ctx, 8, maturity = nextBlock(ctx))
      noException should be thrownBy
        SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> input), wallet.contract, 0L, nextBlock(ctx))
    }
  }

  property("liquidate: refuses a bond not yet due, under another script, or whose fees cannot pay the miner fee") {
    client.execute { ctx =>
      val (early, earlyIn) = bondAt(ctx, 9, maturity = nextBlock(ctx) + 1)
      withClue("the maturity liquidation refuses it at signing: ") {
        an[Exception] should be thrownBy
          SigmaFiTransactions.liquidate(ctx, wallet, Seq(early -> earlyIn), wallet.contract, 0L, nextBlock(ctx))
      }
      val (other, otherIn) = bondAt(ctx, 10, maturity = 100, liqHash = Array.fill(32)(1.toByte))
      an[IllegalArgumentException] should be thrownBy
        SigmaFiTransactions.liquidate(ctx, wallet, Seq(other -> otherIn), wallet.contract, 0L, nextBlock(ctx))
      val (small, smallIn) = bondAt(ctx, 11, maturity = 100, fee = Parameters.MinFee)
      an[IllegalArgumentException] should be thrownBy
        SigmaFiTransactions.liquidate(ctx, wallet, Seq(small -> smallIn), wallet.contract, Parameters.MinFee, nextBlock(ctx))
    }
  }

  property("liquidatable: due by the block, under the maturity script, at or above the floor") {
    client.execute { ctx =>
      val floor = 50000000L
      val (due, _) = bondAt(ctx, 12, maturity = 1000)
      SigmaFiBatcher.liquidatable(due, 1000, floor) shouldBe true
      SigmaFiBatcher.liquidatable(due, 999, floor) shouldBe false
      SigmaFiBatcher.liquidatable(due, 1000, floor + 1) shouldBe false
      val (other, _) = bondAt(ctx, 13, maturity = 1000, liqHash = Array.fill(32)(1.toByte))
      SigmaFiBatcher.liquidatable(other, 1000, floor) shouldBe false
    }
  }

  property("maturity settlement rejects diverted collateral, an excessive fee and a surviving singleton at signing") {
    client.execute { ctx =>
      Seq(None, Some(tokenA)).zipWithIndex.foreach { case (repayToken, index) =>
        val (bond, input) = bondAt(ctx, 30 + index, repayToken, nextBlock(ctx), Seq(Token(tokenB, 7L)))
        val protocolInput = input.withCtxVar(0.toByte, ErgoValue.of(liq.bytes))
        val valid = SigmaFiTransactions.vaultBox(bond)
        val badSettlements = Seq(valid.copy(tokens = Seq.empty), valid.subValue(1L))
        badSettlements.foreach { settlement =>
          val diverted = input.tokens.drop(1).filterNot(t => settlement.tokens.exists(_.id == t.id))
          val fee = UTXO(wallet.contract, bond.box.value - settlement.value, diverted)
          val unsigned = TxBuilder(ctx).setInputs(protocolInput).setOutputs(settlement, fee)
            .setPreHeader(ctx.createPreHeader().height(nextBlock(ctx)).build())
            .buildTx(0L, wallet.p2pk, Seq(Token(bond.asset, 1L)))
          an[Exception] should be thrownBy wallet.sign(unsigned)
        }
        // Without the explicit burn, Appkit puts the singleton in change. Construction must succeed first.
        val unsigned = TxBuilder(ctx).setInputs(protocolInput).setOutputs(valid)
          .setPreHeader(ctx.createPreHeader().height(nextBlock(ctx)).build()).buildTx(0L, wallet.p2pk)
        an[Exception] should be thrownBy wallet.sign(unsigned)
      }
    }
  }
}
