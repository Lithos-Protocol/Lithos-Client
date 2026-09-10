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

  // ─── the floor below which a pass is not worth its fee ─────────────────────

  /** Merging n boxes into one removes n - 1, so a pass that can only merge two buys one box. */
  "The minimum-inputs floor" should "report no work rather than paying a fee for one box" in {
    val api = mock[NodeApi]
    serve(api, Vector(entry(1, 1), entry(2, 2), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 100, 2, minInputs = 3)
    plan.boxes shouldBe empty
    plan.status.outcome shouldBe ConsolidationExecution.OutcomeNoWork
    withClue("the totals are still observed, so the refusal is informed rather than blind: ") {
      plan.status.total shouldBe 3
      plan.status.eligible shouldBe 3
    }
  }

  it should "admit the same wallet once the floor is met" in {
    val api = mock[NodeApi]
    serve(api, Vector(entry(1, 1), entry(2, 2), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 100, 2, minInputs = 2)
    plan.boxes.map(_.creationHeight) shouldBe Vector(1, 2)
    plan.status.outcome shouldBe ConsolidationExecution.OutcomeEligible
  }

  it should "refuse a floor that cannot remove a box at all" in {
    val api = mock[NodeApi]
    serve(api, Vector(entry(1, 1)))
    intercept[IllegalArgumentException](
      ConsolidationExecution.select(api, Set("wallet"), Set.empty, 100, 1, minInputs = 1))
  }

  /**
   * The walk is paged from configuration, so a large wallet costs fewer node reads.
   *
   * A short page ends the walk, so 250 boxes at a page of 300 is one read where the default page of
   * 100 would be three. That ratio is the whole point on a wallet of thousands.
   */
  "The scan" should "page at the configured size" in {
    val api = mock[NodeApi]
    val wallet = Vector.tabulate(250)(i => entry(i + 1, i + 1))

    serve(api, wallet)
    val wide = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 1000, 249,
      minInputs = 2, limits = configs.WalletConfig.Default.copy(pageSize = 300))
    wide.status.total shouldBe 250
    org.mockito.Mockito.verify(api, org.mockito.Mockito.times(1))
      .walletUnspentBoxes(any[ConfirmationRange], any[Paging])

    val narrow = mock[NodeApi]
    serve(narrow, wallet)
    ConsolidationExecution.select(narrow, Set("wallet"), Set.empty, 1000, 249,
      minInputs = 2, limits = configs.WalletConfig.Default.copy(pageSize = 100))
    org.mockito.Mockito.verify(narrow, org.mockito.Mockito.times(3))
      .walletUnspentBoxes(any[ConfirmationRange], any[Paging])
  }
}
