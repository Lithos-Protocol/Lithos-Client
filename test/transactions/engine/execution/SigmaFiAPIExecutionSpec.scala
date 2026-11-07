package transactions.engine.execution

import akka.actor.ActorRef
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnprocessable}
import api.models.{SFAssetLookupRequest, SFOrderRequest, SFTokenAmount}
import configs.NodeContext
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, IndexedTransaction, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{ErgoClient, NetworkType}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import sigmafi.contracts.{FixedMaturity, SigmaFiContracts}
import support.FakeNodeContext
import transactions.batching.sigmafi.{SigmaFiBoxes, SigmaFiFixtures, SigmaFiUserTransactions}
import transactions.engine.wallet.EngineFunding
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

/**
 * What the SigmaFi endpoints refuse, through the checks, which sign nothing and need no wallet. Each refusal is a
 * transaction a contract would reject, or one no client on its shipped settings would ever settle.
 */
class SigmaFiAPIExecutionSpec extends AnyPropSpec with SigmaFiFixtures with MockitoSugar {

  private val client: ErgoClient = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))
  private val tip = client.execute(_.getHeight)

  private class Fixture(boxes: NodeBox*) {
    val api: NodeApi = mock[NodeApi]
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(boxes.filter(b => wanted.contains(b.boxId)))
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.indexedTransactionById(anyString())).thenReturn(Success(None))
    private val context = new NodeContext {
      override def getNetwork: NetworkType = NetworkType.MAINNET
      override def getExplorerURL: String = ""
      override def getClient: ErgoClient = client
      override def getNodeWallet: NodeWallet = wallet
      override def getNodeKey: String = "test-api-key"
      override def getNodeApi: NodeApi = api
      override def getNodeUrl: String = "http://127.0.0.1:9052/"
    }
    // Checks never reach the wallet, so the funding side is never asked
    val execution = new SigmaFiAPIExecution(context, EngineFunding(ActorRef.noSender, 1.second, ExecutionContext.global))
  }

  private val request = SFOrderRequest("5000000000", None, Some("1000000000"), None, "1100000000",
    Some(tip + 100), None, None, None)

  property("create: quotes a valid order, paying its collateral and the fee") {
    val quote = new Fixture().execution.createOrder(request, send = false).left.get
    quote.paysNanoErgs shouldBe (5000000000L + SigmaFiAPIExecution.TxFee).toString
    quote.terms.get.batchingFee shouldBe "50000000"
    quote.terms.get.liquidation shouldBe Some("MaturityLiquidation")
  }

  property("create: refuses a fee under the shipped floor, an unknown script, or a maturity too close") {
    val e = new Fixture().execution
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(batchingFee = Some("49999999")), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(liquidation = Some("DutchAuction")), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(maturityHeight = Some(tip + 2)), send = false)
  }

  property("create: refuses an on-close duration under 30, both schedules at once, or a lent token as collateral") {
    val e = new Fixture().execution
    an[LithosBadRequest] should be thrownBy
      e.createOrder(request.copy(maturityHeight = None, durationBlocks = Some(29)), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(durationBlocks = Some(720)), send = false)
    an[LithosBadRequest] should be thrownBy
      e.createOrder(request.copy(maturityHeight = None, durationBlocks = Some(Int.MaxValue)), send = false)
    val token = SFTokenAmount(tokenA.toString, "5")
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(principalNanoErgs = None,
      principalToken = Some(token), collateralTokens = Some(Seq(token))), send = false)
  }

  property("fill: refuses a token order whose bond repays in another token, and this wallet's own order") {
    client.execute { ctx =>
      val stranger = contractOf(miner(ctx))
      val mismatched = orderTerms(stranger, FixedMaturity(tip + 100), principalErg = MinBox,
        principalTokens = Seq(tokenA -> 500L)).copy(bondHash = SigmaFiContracts.tokenBond(tokenB).hashedPropBytes)
      val bad = nodeBox(ctx, UTXO(SigmaFiContracts.order(mismatched), 5 * Erg), 0)
      val mine = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(wallet.contract, FixedMaturity(tip + 100))), 5 * Erg), 1)
      val e = new Fixture(bad, mine).execution
      the[LithosUnprocessable] thrownBy e.fillOrder(bad.boxId, send = false) should have message
        "the order's bond does not match its loan, so its bond could not be repaid as asked"
      an[LithosUnprocessable] should be thrownBy e.fillOrder(mine.boxId, send = false)
    }
  }

  property("cancel: a box this wallet did not make is not found as its own") {
    client.execute { ctx =>
      val theirs = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(contractOf(miner(ctx)), FixedMaturity(tip + 100))),
        5 * Erg), 2)
      an[LithosNotFound] should be thrownBy new Fixture(theirs).execution.cancelOrder(theirs.boxId, send = false)
    }
  }

  property("repay and liquidate: refuse a matured bond's repayment, and a liquidation before maturity") {
    client.execute { ctx =>
      val matured = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 1), 3)
      val early = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 50), 4)
      val e = new Fixture(matured, early).execution
      an[LithosUnprocessable] should be thrownBy e.repay(matured.boxId, send = false)
      an[LithosUnprocessable] should be thrownBy e.liquidate(early.boxId, send = false)
      e.liquidate(matured.boxId, send = false).left.get.receivesNanoErgs shouldBe
        (50000000L - SigmaFiAPIExecution.TxFee).toString
    }
  }

  property("claim: refuses an asset the vault holds nothing for") {
    an[LithosUnprocessable] should be thrownBy new Fixture().execution.claim(assetId.toString, send = false)
  }

  property("repayment quotes return collateral without counting the bond singleton") {
    client.execute { ctx =>
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 100, collateral = Seq(Token(tokenB, 7L))))
      val quote = new Fixture(box).execution.repay(box.boxId, send = false).left.get
      quote.receivesTokens shouldBe Seq(SFTokenAmount(tokenB.toString, "7"))
      quote.terms.get.collateralTokens shouldBe quote.receivesTokens
    }
  }

  property("fill quotes include the deposit required by a large issuance description") {
    client.execute { ctx =>
      val borrower = contractOf(miner(ctx))
      val collateral = (1 to 32).map(i => Token(org.ergoplatform.sdk.ErgoId.create(f"${i + 1000}%064x"), i.toLong))
      val terms = orderTerms(borrower, FixedMaturity(tip + 100))
      val box = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg, collateral,
        Seq(SigmaFiBoxes.hintValue(borrower.ergoTree))), 40)
      val order = SigmaFiBoxes.order(box).get
      val deposit = SigmaFiUserTransactions.lenderUnit(ctx, wallet, order, None, tip + 100).value
      val quote = new Fixture(box).execution.fillOrder(box.boxId, send = false).left.get
      deposit should be > MinBox
      quote.receivesNanoErgs shouldBe deposit.toString
      quote.paysNanoErgs shouldBe (terms.principalErg + deposit + SigmaFiAPIExecution.TxFee).toString
    }
  }

  property("asset lookup authenticates the complete two-unit fill and retries an incomplete mint index") {
    client.execute { ctx =>
      val borrower = contractOf(miner(ctx))
      Seq("valid", "supply", "terms", "input", "principal", "delayed").zipWithIndex.foreach { case (variant, i) =>
        val terms = orderTerms(borrower, FixedMaturity(tip + 100))
        val box = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg, Seq(Token(tokenB, 7L))), i, "e9" * 32)
        val order = SigmaFiBoxes.order(box).get
        val funding = UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, (i + 100).toShort)
        val fill = wallet.sign(SigmaFiUserTransactions.fillOrder(ctx, wallet, order,
          SigmaFiBoxes.input(box, ctx).get, None, borrower).build(Seq(funding)))
        val txId = transactions.candidate.BlockTxMessages.CandidateTx.txId(fill)
        def indexed(b: NodeBox) = IndexedBox(b, "", tip + 1, 1L)
        val genesis = indexed(box).copy(spentTransactionId = Some(txId), spendingHeight = Some(tip + 1))
        val outputs = fill.getOutputsToSpend.asScala.zipWithIndex.map { case (b, n) =>
          indexed(nodeBox(ctx, InputUTXO(b).toUTXO, n, txId))
        }.toVector
        val changed = variant match {
          case "supply" => outputs.updated(0, outputs(0).copy(box = outputs(0).box.copy(
            assets = outputs(0).assets.map(_.copy(amount = 2L)))))
          case "terms" => outputs.updated(1, indexed(nodeBox(ctx, InputUTXO(fill.getOutputsToSpend.get(1)).toUTXO
            .withReg(2, org.ergoplatform.appkit.ErgoValue.of(Array(2 * Erg + 1, 50000000L))), 1, txId)))
          case "principal" => outputs.updated(2, outputs(2).copy(box = outputs(2).box.copy(value = MinBox)))
          case _ => outputs
        }
        val inputs = if (variant == "input") Seq(indexed(box.copy(boxId = "ff" * 32))) else Seq(genesis)
        val mint = IndexedTransaction(txId, inputs, Seq.empty, changed, tip + 1, 1, "block", 0L, 0, 1L, 1000)
        val f = new Fixture()
        when(f.api.indexedBoxById(box.boxId)).thenReturn(Success(Some(genesis)))
        when(f.api.indexedTransactionById(txId)).thenReturn(Success(Some(mint)))
        def lookup = f.execution.lookupAssets(SFAssetLookupRequest(Seq(box.boxId))).head
        if (variant == "delayed") {
          when(f.api.indexedTransactionById(txId)).thenReturn(Success(None))
          lookup.verified shouldBe false
          when(f.api.indexedTransactionById(txId)).thenReturn(Success(Some(mint)))
        }
        val result = lookup
        val valid = variant == "valid" || variant == "delayed"
        result.verified shouldBe valid
        result.bondAsset shouldBe valid
      }
    }
  }
}
