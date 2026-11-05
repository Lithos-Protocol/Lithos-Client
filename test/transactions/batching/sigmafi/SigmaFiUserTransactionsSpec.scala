package transactions.batching.sigmafi

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigmafi.contracts.{FixedMaturity, OnClose, SigmaFiContracts, ZCOfferTerms}
import support.FakeNodeContext
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/**
 * The user transactions, chained through a bond's whole life: each is signed, which runs every SigmaFi script it
 * spends, and its outputs are read back and spent by the next. A signature is the pinned contracts accepting it.
 */
class SigmaFiUserTransactionsSpec extends AnyPropSpec with SigmaFiFixtures {

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))

  private var fundingIndex = 0

  /** A fresh wallet box holding `erg` and `tokens`, distinct from every other. */
  private def funds(ctx: BlockchainContext, erg: Long, tokens: Token*): InputUTXO = {
    fundingIndex += 1
    UTXO(wallet.contract, erg, tokens).toInput(ctx, ErgoId.create("f0" * 32), fundingIndex.toShort)
  }

  private def sign(ctx: BlockchainContext, plan: SFPlan, funding: InputUTXO*): SignedTransaction =
    transactions.batching.Batcher.withNodeMinimums(ctx, wallet.sign(plan.build(funding)))

  /** Output `i` of `signed`, as the node would report it. */
  private def out(signed: SignedTransaction, i: Int): NodeBox = {
    val b = signed.getOutputsToSpend.get(i)
    NodeBox(b.getId.toString, CandidateTx.txId(signed), b.getValue, i, b.getCreationHeight,
      Hex.toHexString(b.getErgoTree.bytes), b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
  }

  private def input(ctx: BlockchainContext, box: NodeBox): InputUTXO = SigmaFiBoxes.input(box, ctx).get

  property("an ERG order is created, filled, repaid and claimed, each step accepted by its contract") {
    client.execute { ctx =>
      val maturity = ctx.getHeight + 100
      val terms = orderTerms(wallet.contract, FixedMaturity(maturity))
      val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg, Seq(Token(tokenB, 7))),
        funds(ctx, 20 * Erg, Token(tokenB, 7)))
      val order = SigmaFiBoxes.order(out(created, 0)).get
      SigmaFiBoxes.hintTrees(order.box).map(_.bytes.toSeq) shouldBe Seq(wallet.contract.ergoTree.bytes.toSeq)

      val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box), None,
        wallet.contract), funds(ctx, 10 * Erg))
      val asset = ErgoId.create(order.boxId)
      val unit = out(filled, 0)
      unit.assets shouldBe Seq(NodeAsset(asset.toString, 1L))
      val bond = SigmaFiBoxes.bond(out(filled, 1)).get
      (bond.asset, bond.maturity, bond.repayment) shouldBe ((asset, maturity, terms.repayment))
      bond.collateral shouldBe Seq(NodeAsset(tokenB.toString, 7L))
      out(filled, 2).value shouldBe terms.principalErg

      val repaid = sign(ctx, SigmaFiUserTransactions.repay(ctx, wallet, bond, input(ctx, bond.box), wallet.contract),
        funds(ctx, 10 * Erg))
      val vault = SigmaFiBoxes.vault(out(repaid, 0)).get
      (vault.asset, vault.box.value) shouldBe ((asset, terms.repayment))
      out(repaid, 1).assets shouldBe Seq(NodeAsset(tokenB.toString, 7L))

      val claimed = sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, asset, Seq(input(ctx, vault.box))),
        input(ctx, unit), funds(ctx, Erg))
      claimed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain asset
      claimed.getOutputsToSpend.asScala.head.getValue.toLong shouldBe terms.repayment
    }
  }

  property("an offer is created and closed, and its bond liquidated at maturity and claimed") {
    client.execute { ctx =>
      val maturity = ctx.getHeight + 1
      val terms = offerTerms(wallet.contract, maturity, collateralTokens = Seq(tokenB -> 9L))
      val created = sign(ctx, SigmaFiUserTransactions.createOffer(ctx, wallet, terms, Erg, Seq.empty), funds(ctx, 5 * Erg))
      val offer = SigmaFiBoxes.offer(out(created, 0)).get
      offer.principalErg shouldBe Erg

      val closed = sign(ctx, SigmaFiUserTransactions.closeOffer(ctx, wallet, offer, input(ctx, offer.box), None,
        wallet.contract), funds(ctx, 20 * Erg, Token(tokenB, 9)))
      val unit = out(closed, 0)
      unit.additionalRegisters.get(4).map(h => new String(org.ergoplatform.appkit.ErgoValue.fromHex(h).getValue
        .asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8")) shouldBe Some("SigmaFi ZC-Bond")
      val bond = SigmaFiBoxes.bond(out(closed, 1)).get
      bond.borrowerHash.toSeq shouldBe wallet.contract.hashedPropBytes.toSeq
      out(closed, 2).value shouldBe Erg

      val liquidated = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> input(ctx, bond.box)), wallet.contract,
        SigmaFiUserTransactions.TxFee, ctx.getHeight + 1)
      val vault = SigmaFiBoxes.vault(out(liquidated.signed, 0)).get
      vault.box.assets shouldBe Seq(NodeAsset(tokenB.toString, 9L))
      sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, bond.asset, Seq(input(ctx, vault.box))),
        input(ctx, unit), funds(ctx, Erg))
    }
  }

  property("a token loan is filled with the token and repaid in it, into its own token bond") {
    client.execute { ctx =>
      val terms = orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100), principalErg = MinBox,
        principalTokens = Seq(tokenA -> 500L), repayment = 550L)
      val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg, Seq.empty), funds(ctx, 10 * Erg))
      val order = SigmaFiBoxes.order(out(created, 0)).get
      val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box), Some(tokenA),
        wallet.contract), funds(ctx, Erg, Token(tokenA, 500)))
      val bond = SigmaFiBoxes.bond(out(filled, 1)).get
      bond.repayToken shouldBe Some(tokenA)
      out(filled, 2).assets shouldBe Seq(NodeAsset(tokenA.toString, 500L))
      val repaid = sign(ctx, SigmaFiUserTransactions.repay(ctx, wallet, bond, input(ctx, bond.box), wallet.contract),
        funds(ctx, Erg, Token(tokenA, 550)))
      out(repaid, 0).assets shouldBe Seq(NodeAsset(tokenA.toString, 550L))
    }
  }

  property("an on-close order's fill stamps its maturity its duration after the tip, which the contract accepts") {
    client.execute { ctx =>
      val terms = orderTerms(wallet.contract, OnClose(720))
      val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg, Seq.empty), funds(ctx, 10 * Erg))
      val order = SigmaFiBoxes.order(out(created, 0)).get
      val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box), None,
        wallet.contract), funds(ctx, 10 * Erg))
      SigmaFiBoxes.bond(out(filled, 1)).get.maturity shouldBe ctx.getHeight + 720
    }
  }

  property("an order and an offer cancel with a box at their creator's address, and not with any other") {
    client.execute { ctx =>
      val order = SigmaFiBoxes.order(out(sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet,
        orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100)), 5 * Erg, Seq.empty), funds(ctx, 10 * Erg)), 0)).get
      val back = wallet.sign(SigmaFiUserTransactions.cancel(ctx, wallet, input(ctx, order.box), funds(ctx, Erg), wallet.contract))
      back.getOutputsToSpend.get(0).getValue.toLong shouldBe 6 * Erg - SigmaFiUserTransactions.TxFee

      val offer = SigmaFiBoxes.offer(out(sign(ctx, SigmaFiUserTransactions.createOffer(ctx, wallet,
        offerTerms(wallet.contract, ctx.getHeight + 100), Erg, Seq.empty), funds(ctx, 5 * Erg)), 0)).get
      wallet.sign(SigmaFiUserTransactions.cancel(ctx, wallet, input(ctx, offer.box), funds(ctx, Erg), wallet.contract))

      // Spendable by anyone, so only the order's own script can refuse the transaction
      withClue("a box elsewhere does not authorize it: ") {
        val stranger = UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, Erg).toInput(ctx, ErgoId.create("f1" * 32), 0)
        an[Exception] should be thrownBy wallet.sign(SigmaFiUserTransactions.cancel(ctx, wallet,
          input(ctx, order.box), stranger, wallet.contract))
      }
    }
  }

  property("a bond asset's description carries its terms as JSON a wallet can read") {
    val d = SigmaFiUserTransactions.bondAssetDescription(Erg, Seq.empty, 2 * Erg, None, Left(1000), 5 * Erg,
      Seq(tokenB -> 9L), 50000000L, "MaturityLiquidation")
    val json = play.api.libs.json.Json.parse(d)
    (json \ "type").as[String] shouldBe "SigmaFi ZC-Bond"
    (json \ "maturity").as[Int] shouldBe 1000
    (json \ "collateral" \ "tokens" \ 0 \ "id").as[String] shouldBe tokenB.toString
  }
}
