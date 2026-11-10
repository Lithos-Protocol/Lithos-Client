package transactions.batching.sigmafi

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigmafi.contracts.{FixedMaturity, OnClose, SigmaFiContracts}
import support.FakeNodeContext
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

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
      bond.box.assets.head shouldBe NodeAsset(asset.toString, 1L)
      filled.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).filter(_.getId == asset).map(_.getValue.toLong).sum shouldBe 2L
      def text(register: Int): String = new String(ErgoValue.fromHex(unit.additionalRegisters.get(register).get)
        .getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8")
      text(4) shouldBe SigmaFiUserTransactions.bondAssetName(asset)
      (play.api.libs.json.Json.parse(text(5)) \ "bondAsset").as[String] shouldBe asset.toString
      text(6) shouldBe "0"
      (bond.asset, bond.maturity, bond.repayment) shouldBe ((asset, maturity, terms.repayment))
      bond.collateral shouldBe Seq(NodeAsset(tokenB.toString, 7L))
      bond.principalErg shouldBe terms.principalErg
      bond.principalTokens shouldBe terms.principalTokens
      bond.box.value shouldBe order.box.value + bond.batchingFee
      bond.collateralErg shouldBe order.box.value
      out(filled, 2).value shouldBe terms.principalErg

      val repaid = sign(ctx, SigmaFiUserTransactions.repay(ctx, wallet, bond, input(ctx, bond.box), wallet.contract),
        funds(ctx, 10 * Erg))
      val vault = SigmaFiBoxes.vault(out(repaid, 0)).get
      (vault.asset, vault.box.value) shouldBe ((asset, terms.repayment + bond.batchingFee))
      out(repaid, 1).assets shouldBe Seq(NodeAsset(tokenB.toString, 7L))
      repaid.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain asset

      val claimed = sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, asset, Seq(input(ctx, vault.box))),
        input(ctx, unit), funds(ctx, Erg))
      claimed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain asset
      claimed.getOutputsToSpend.asScala.head.getValue.toLong shouldBe terms.repayment + bond.batchingFee
    }
  }

  property("ERG and token orders liquidate and claim without moving the bond singleton into the vault") {
    client.execute { ctx =>
      Seq(None, Some(tokenA)).foreach { repayToken =>
        val terms = orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100),
          principalErg = if (repayToken.isDefined) MinBox else Erg,
          principalTokens = repayToken.toSeq.map(_ -> 500L), repayment = if (repayToken.isDefined) 550L else 2 * Erg)
        val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg, Seq(Token(tokenB, 9))),
          funds(ctx, 10 * Erg, Token(tokenB, 9)))
        val order = SigmaFiBoxes.order(out(created, 0)).get
        val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box),
          repayToken, wallet.contract), funds(ctx, 10 * Erg, repayToken.toSeq.map(Token(_, 500L)): _*))
        val unit = input(ctx, out(filled, 0))
        val bond = SigmaFiBoxes.bond(out(filled, 1)).get
        val liquidated = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> input(ctx, bond.box)),
          wallet.contract, SigmaFiUserTransactions.TxFee, Math.toIntExact(bond.maturity))
        val settlement = out(liquidated.signed, 0)
        settlement.assets shouldBe Seq(NodeAsset(tokenB.toString, 9L))
        liquidated.signed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain bond.asset
        val claimed = sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, bond.asset, Seq(input(ctx, settlement))),
          unit, funds(ctx, Erg))
        out(claimed, 0).assets shouldBe settlement.assets
        claimed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain bond.asset
      }
    }
  }

  property("order fills reject missing, misplaced and excess singleton issuance after transaction construction") {
    client.execute { ctx =>
      val terms = orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100))
      val orderBox = UTXO(SigmaFiContracts.order(terms), 5 * Erg).toInput(ctx, ErgoId.create("e8" * 32), 0)
        .withCtxVar(0.toByte, ErgoValue.of(50000000L))
      val asset = orderBox.id
      val lender = UTXO(wallet.contract, MinBox, Seq(Token(asset, 1L)))
      val bond = bondUTXO(None, wallet.contract, ctx.getHeight + 100, value = 5 * Erg + 50000000L, asset = asset)
      val principal = UTXO(wallet.contract, Erg)
      val funding = funds(ctx, 10 * Erg)
      noException should be thrownBy wallet.sign(TxBuilder(ctx).setInputs(orderBox, funding)
        .setOutputs(lender, bond, principal).buildTx(SigmaFiUserTransactions.TxFee, wallet.p2pk))
      val malformed = Seq(
        Seq(lender, bond.copy(tokens = Seq.empty), principal),
        Seq(lender.copy(tokens = Seq(Token(asset, 2L))), bond, principal),
        Seq(lender, bond.copy(tokens = Seq(Token(tokenB, 1L), Token(asset, 1L))), principal))
      malformed.zipWithIndex.foreach { case (outputs, i) =>
        val extra = if (i == 2) Seq(funds(ctx, Erg, Token(tokenB, 1L))) else Seq.empty
        val unsigned = TxBuilder(ctx).setInputs(Seq(orderBox, funding) ++ extra: _*)
          .setOutputs(outputs: _*).buildTx(SigmaFiUserTransactions.TxFee, wallet.p2pk)
        an[Exception] should be thrownBy wallet.sign(unsigned)
      }
    }
  }

  property("repayment preserves the circulating unit and rejects funding that would consume it") {
    client.execute { ctx =>
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, ctx.getHeight + 100))
      val bond = SigmaFiBoxes.bond(box).get
      val plan = SigmaFiUserTransactions.repay(ctx, wallet, bond, input(ctx, box), wallet.contract)
      plan.avoidTokens shouldBe Set(bond.asset.toString)
      an[IllegalArgumentException] should be thrownBy plan.build(Seq(funds(ctx, 10 * Erg, Token(bond.asset, 1L))))
      val ordinary = sign(ctx, plan, funds(ctx, 10 * Erg))
      ordinary.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain bond.asset
      // Omitting the explicit burn moves the singleton into change; the bond must reject at signing.
      val outputs = ordinary.getOutputsToSpend.asScala.take(2).map(b => InputUTXO(b).toUTXO)
      val unsigned = TxBuilder(ctx).setInputs(input(ctx, box), funds(ctx, 10 * Erg))
        .setOutputs(outputs: _*).buildTx(SigmaFiUserTransactions.TxFee, wallet.p2pk)
      an[Exception] should be thrownBy wallet.sign(unsigned)
    }
  }

  property("the vault lets the unit claim a box anyone made for a live bond's asset, so a claim must wait for the bond") {
    client.execute { ctx =>
      val terms = orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100))
      val order = SigmaFiBoxes.order(out(sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg,
        Seq.empty), funds(ctx, 10 * Erg)), 0)).get
      val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box), None,
        wallet.contract), funds(ctx, 10 * Erg))
      val bond = SigmaFiBoxes.bond(out(filled, 1)).get
      val gift = UTXO(SigmaFiContracts.vault, MinBox, Seq.empty, Seq(ErgoValue.of(Array.fill(32)(1.toByte)),
        ErgoValue.of(bond.asset.getBytes), ErgoValue.of(0.toByte))).toInput(ctx, ErgoId.create("f2" * 32), 0)
      // The contract accepts it: the unit is burned while the bond's singleton still lives
      val claimed = sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, bond.asset, Seq(gift)), input(ctx, out(filled, 0)),
        funds(ctx, Erg))
      claimed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).map(_.getId) should not contain bond.asset
    }
  }

  property("a claim pays each vault box into its own output, so a stranger's token-heavy box cannot oversize it") {
    client.execute { ctx =>
      val asset = ErgoId.create("c7" * 32)
      def vault(i: Int): InputUTXO = UTXO(SigmaFiContracts.vault, 2 * Erg,
        (1 to 70).map(t => Token(ErgoId.create(f"${i * 1000 + t}%064x"), t.toLong)),
        Seq(ErgoValue.of(Array.fill(32)(i.toByte)), ErgoValue.of(asset.getBytes), ErgoValue.of(1.toByte)))
        .toInput(ctx, ErgoId.create(f"$i%064x"), 0)
      val vaults = Seq(vault(1), vault(2))
      vaults.foreach(_.bytes.length should be <= sigma.data.SigmaConstants.MaxBoxSize.value)
      val claimed = sign(ctx, SigmaFiUserTransactions.claim(ctx, wallet, asset, vaults), funds(ctx, Erg, Token(asset, 1L)))
      val outputs = claimed.getOutputsToSpend.asScala
      outputs.foreach(_.getBytes.length should be <= sigma.data.SigmaConstants.MaxBoxSize.value)
      outputs.flatMap(_.getTokens.asScala).map(_.getId).toSet shouldBe vaults.flatMap(_.tokens).map(_.id).toSet
      outputs.filter(o => work.lithos.mutations.Contract(o.getErgoTree) == wallet.contract)
        .map(_.getValue.toLong).sum should be >= vaults.map(_.value).sum
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

  property("an order cancels with a box at their creator's address, and not with any other") {
    client.execute { ctx =>
      val order = SigmaFiBoxes.order(out(sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet,
        orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100)), 5 * Erg, Seq.empty), funds(ctx, 10 * Erg)), 0)).get
      val back = wallet.sign(SigmaFiUserTransactions.cancel(ctx, wallet, input(ctx, order.box), funds(ctx, Erg), wallet.contract))
      back.getOutputsToSpend.get(0).getValue.toLong shouldBe 6 * Erg - SigmaFiUserTransactions.TxFee

      // Spendable by anyone, so only the order's own script can refuse the transaction
      withClue("a box elsewhere does not authorize it: ") {
        val stranger = UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, Erg).toInput(ctx, ErgoId.create("f1" * 32), 0)
        an[Exception] should be thrownBy wallet.sign(SigmaFiUserTransactions.cancel(ctx, wallet,
          input(ctx, order.box), stranger, wallet.contract))
      }
    }
  }

  property("issuance metadata includes the asset suffix, exact amounts, schedule and build context") {
    client.execute { ctx =>
      val large = 9007199254740993L
      val terms = orderTerms(wallet.contract, OnClose(720), principalErg = large, repayment = large + 1)
      val order = SigmaFiBoxes.order(nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg,
        Seq(Token(tokenB, 9))))).get
      val json = play.api.libs.json.Json.parse(SigmaFiUserTransactions.bondAssetDescription(order, None,
        ctx.getHeight + 720, ctx.getHeight))
      (json \ "type").as[String] shouldBe "SigmaFi ZC-Bond"
      (json \ "bondAsset").as[String] shouldBe order.boxId
      (json \ "principal" \ "nanoErgs").as[Long] shouldBe large
      (json \ "repayment").as[Long] shouldBe large + 1
      (json \ "maturity").as[Int] shouldBe ctx.getHeight + 720
      (json \ "durationBlocks").as[Int] shouldBe 720
      (json \ "height").as[Int] shouldBe ctx.getHeight
      (json \ "liquidationHash").as[String] shouldBe liq.hashHex
      (json \ "collateral" \ "tokens" \ 0 \ "id").as[String] shouldBe tokenB.toString
      SigmaFiUserTransactions.bondAssetName(ErgoId.create(order.boxId)) shouldBe s"SigmaFi-ZC-Bond-${order.boxId.take(6)}"
    }
  }

  property("a fill with many collateral tokens funds the lender unit for its serialized metadata size") {
    client.execute { ctx =>
      val collateral = (1 to 32).map(i => Token(ErgoId.create(f"${i + 1000}%064x"), i.toLong))
      val terms = orderTerms(wallet.contract, FixedMaturity(ctx.getHeight + 100))
      val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, 5 * Erg, collateral),
        funds(ctx, 10 * Erg, collateral: _*))
      val order = SigmaFiBoxes.order(out(created, 0)).get
      val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box), None,
        wallet.contract), funds(ctx, 10 * Erg))
      val unit = filled.getOutputsToSpend.get(0)
      unit.getValue.toLong should be > MinBox
      unit.getValue.toLong should be >= unit.getBytes.length.toLong * ctx.getDataSource.getParameters.getMinValuePerByte
      SigmaFiBoxes.bond(out(filled, 1)).get.collateral.size shouldBe 32
    }
  }

  property("fills preserve Long schedules, principal token data and an independently selected reserve") {
    client.execute { ctx =>
      Seq(FixedMaturity(Int.MaxValue.toLong + 100), OnClose(Int.MaxValue.toLong + 200)).foreach { schedule =>
        val terms = orderTerms(wallet.contract, schedule, principalErg = MinBox,
          principalTokens = Seq(tokenA -> 500L), repayment = 550L)
        val created = sign(ctx, SigmaFiUserTransactions.createOrder(ctx, wallet, terms, MinBox, Seq(Token(tokenB, 3L))),
          funds(ctx, Erg, Token(tokenB, 3L)))
        val order = SigmaFiBoxes.order(out(created, 0)).get
        val reserve = 75000000L
        val filled = sign(ctx, SigmaFiUserTransactions.fillOrder(ctx, wallet, order, input(ctx, order.box),
          Some(tokenA), wallet.contract, reserve), funds(ctx, Erg, Token(tokenA, 500L)))
        val bond = SigmaFiBoxes.bond(out(filled, 1)).get
        bond.maturity should be > Int.MaxValue.toLong
        bond.principalTokens shouldBe terms.principalTokens
        bond.principalErg shouldBe MinBox
        bond.collateralErg shouldBe MinBox
        bond.batchingFee shouldBe reserve
        val repaid = sign(ctx, SigmaFiUserTransactions.repay(ctx, wallet, bond, input(ctx, bond.box), wallet.contract),
          funds(ctx, Erg, Token(tokenA, 550L)))
        out(repaid, 0).value shouldBe MinBox + reserve
        out(repaid, 1).value shouldBe MinBox
        out(repaid, 1).assets shouldBe Seq(NodeAsset(tokenB.toString, 3L))
      }
    }
  }
}
