package transactions.engine.execution

import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scala.util.{Failure, Success}

class ConsolidationExecutionSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private def entry(i: Int, height: Int, token: Boolean = false): WalletBox = {
    val b = NodeBox(f"$i%064x", "ab" * 32, 2000000L, 0, height, "wallet",
      if (token) Seq(NodeAsset("cd" * 32, 1)) else Seq.empty)
    WalletBox(b, "", Some(5), b.transactionId, 0, Some(height), None, None, false, true, Seq.empty)
  }
  private def serve(api: NodeApi, entries: Vector[WalletBox]): Unit =
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { invocation =>
      val p = invocation.getArgument[Paging](1)
      Success(entries.slice(p.offset, p.offset + p.limit))
    }
  "Consolidation selection" should "find the oldest boxes across unsorted pages and reduce only the excess" in {
    val api = mock[NodeApi]
    val entries = Vector.tabulate(5000)(i => entry(i + 1, 5000 - i))
    serve(api, entries)
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 10000, 4998)
    plan.status.total shouldBe 5000
    plan.boxes.map(_.creationHeight) shouldBe Vector(1, 2, 3)
    plan.status.blocksUntilRent shouldBe Some(org.ergoplatform.wallet.protocol.Constants.StoragePeriod.toLong + 1 - 10000)
  }
  it should "count excluded token and owned boxes without selecting them" in {
    val api = mock[NodeApi]
    serve(api, Vector(entry(1, 1, token = true), entry(2, 2), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set(f"${2}%064x"), 100, 1)
    plan.boxes.map(_.creationHeight) shouldBe Vector(3)
    plan.status.total shouldBe 3
    plan.status.oldestExcluded shouldBe Some(1)
    plan.status.targetUnreachable shouldBe true
  }
  it should "discard a partial scan when a later page fails" in {
    val api = mock[NodeApi]
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenReturn(
      Success(Vector.tabulate(100)(i => entry(i + 1, i))), Failure(new RuntimeException("unavailable")))
    intercept[RuntimeException](ConsolidationExecution.select(api, Set("wallet"), Set.empty, 1000, 1))
  }
}
