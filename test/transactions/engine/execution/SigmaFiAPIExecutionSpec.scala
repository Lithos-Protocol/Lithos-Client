package transactions.engine.execution

import akka.actor.ActorRef
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnprocessable}
import api.models.{SFOrderRequest, SFTokenAmount}
import configs.NodeContext
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{ErgoClient, NetworkType}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import sigmafi.contracts.{FixedMaturity, SigmaFiContracts}
import support.FakeNodeContext
import transactions.batching.sigmafi.SigmaFiFixtures
import transactions.engine.wallet.EngineFunding
import work.lithos.mutations.UTXO

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
}
