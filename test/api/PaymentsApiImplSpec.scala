package api

import api.LithosApiErrors.LithosUnavailable
import api.models.PaymentTransaction
import lfsm.contracts.RollupContracts
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{verifyNoInteractions, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.json.Json
import support.FakeNodeContext

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/** Covers payout classification, filtered paging, and node-index failures. */
class PaymentsApiImplSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val config = Configuration.from(Map.empty[String, Any])

  private case class Fixture(impl: PaymentsApiImpl,
                             nodeApi: NodeApi,
                             minerAddress: String,
                             minerTree: String,
                             payoutTree: String)

  private def fixture(answer: Paging => Try[Paged[IndexedTransaction]],
                      seen: ArrayBuffer[Paging] = ArrayBuffer.empty): Fixture = {
    val nodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi)
    val minerAddress = wallet.p2pk.toString
    val minerTree = wallet.contract.ergoTreeHex
    val payoutTree = RollupContracts.mkPayoutContract(nodeContext.getNetwork).ergoTreeHex

    when(nodeApi.indexedTransactionsByAddress(anyString(), any[Paging]))
      .thenAnswer((inv: InvocationOnMock) => {
        inv.getArgument[String](0) shouldBe minerAddress
        val paging = inv.getArgument[Paging](1)
        seen += paging
        answer(paging)
      })

    Fixture(new PaymentsApiImpl(nodeContext), nodeApi, minerAddress, minerTree, payoutTree)
  }

  private def box(id: String,
                  tree: String,
                  address: String,
                  value: Long = 1000000L): IndexedBox =
    IndexedBox(
      NodeBox(id, "creation-" + id, value, 0, 400, tree),
      address = address,
      inclusionHeight = 400,
      globalIndex = 1L)

  private def tx(id: String,
                 payoutTree: String,
                 minerAddress: String,
                 minerTree: String,
                 confirmations: Int = 2,
                 height: Int = 500,
                 inputTree: Option[String] = None,
                 outputAddress: Option[String] = None,
                 outputTree: Option[String] = None,
                 amount: Long = 42000000L): IndexedTransaction = {
    val unrelated = box("unrelated-" + id, "unrelated-tree", "unrelated-address")
    val payout = box("payout-" + id, inputTree.getOrElse(payoutTree), "payout-address")
    val output = box("output-" + id, outputTree.getOrElse(minerTree),
      outputAddress.getOrElse(minerAddress), amount)

    IndexedTransaction(
      id = id,
      // The payout input is deliberately not first: classification is by exact script, not position.
      inputs = Seq(unrelated, payout),
      dataInputs = Seq.empty,
      outputs = Seq(output),
      inclusionHeight = height,
      numConfirmations = confirmations,
      blockId = "containing-chain-block-" + id,
      timestamp = 0L,
      index = 0,
      globalIndex = height.toLong,
      size = 100)
  }

  "Payment history" should "return only confirmed exact payout spends with an exact miner output" in {
    var built = Seq.empty[IndexedTransaction]
    lazy val f: Fixture = fixture(_ => Success(Paged(built, built.size)))
    built = Seq(
      tx("exact", f.payoutTree, f.minerAddress, f.minerTree, amount = 73000000L),
      tx("wrong-input", f.payoutTree, f.minerAddress, f.minerTree,
        inputTree = Some(f.payoutTree + "00")),
      tx("wrong-address", f.payoutTree, f.minerAddress, f.minerTree,
        outputAddress = Some(f.minerAddress + "x")),
      tx("wrong-output-tree", f.payoutTree, f.minerAddress, f.minerTree,
        outputTree = Some(f.minerTree + "00")),
      tx("unconfirmed", f.payoutTree, f.minerAddress, f.minerTree, confirmations = 0))

    val cache = mock[SyncCacheApi]
    val result = f.impl.getPayments(Some(10), Some(0), config, cache)

    result shouldEqual List(PaymentTransaction(
      transactionId = "exact",
      payoutUTXO = "payout-exact",
      amount = 73000000L,
      score = None,
      paymentHeight = 500,
      blockId = None,
      minedBlockHeight = None))
    // Payout index data cannot recover the rewarded block identity or exact NISP score.
    val json = Json.toJson(result.head)
    (json \ "score").asOpt[Long] shouldBe None
    (json \ "blockId").asOpt[String] shouldBe None
    (json \ "minedBlockHeight").asOpt[Int] shouldBe None

    // Payment history is independent of synchronization cache state.
    verifyNoInteractions(cache)
  }

  it should "apply offset and limit after filtering across raw index pages" in {
    val seen = ArrayBuffer.empty[Paging]
    var pages = Map.empty[Int, Paged[IndexedTransaction]]
    lazy val f: Fixture = fixture(p => Success(pages.getOrElse(p.offset, Paged(Seq.empty, 110))), seen)

    val firstMatches = (0 until 3).map(i =>
      tx(s"first-$i", f.payoutTree, f.minerAddress, f.minerTree, height = 600 - i))
    val noise = (0 until 97).map(i =>
      tx(s"noise-$i", f.payoutTree, f.minerAddress, f.minerTree,
        inputTree = Some("not-the-payout-tree")))
    val secondMatches = (0 until 10).map(i =>
      tx(s"second-$i", f.payoutTree, f.minerAddress, f.minerTree, height = 500 - i))
    pages = Map(
      0 -> Paged(firstMatches ++ noise, 110),
      100 -> Paged(secondMatches, 110))

    val result = f.impl.getPayments(Some(10), Some(2), config, mock[SyncCacheApi])

    result.map(_.transactionId) shouldEqual
      (Seq("first-2") ++ (0 until 9).map(i => s"second-$i"))
    seen.toSeq shouldEqual Seq(Paging(0, 100), Paging(100, 100))
  }

  it should "report an unavailable or disabled node index explicitly" in {
    val cause = new RuntimeException("extra index is disabled")
    val f = fixture(_ => Failure(cause))

    val thrown = intercept[LithosUnavailable](
      f.impl.getPayments(Some(10), Some(0), config, mock[SyncCacheApi]))

    thrown.getCause shouldBe cause
    thrown.getMessage should include("indexed node")
    thrown.getMessage should include("offset 0")
  }
}
