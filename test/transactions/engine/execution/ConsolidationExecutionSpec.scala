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
  it should "count an owned box without selecting it" in {
    val api = mock[NodeApi]
    // Box 1 carries a token, which is selectable now; box 2 is owned by something else.
    serve(api, Vector(entry(1, 1, token = true), entry(2, 2), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set(f"${2}%064x"), 100, 1)
    plan.boxes.map(_.creationHeight) shouldBe Vector(1, 3)
    plan.status.total shouldBe 3
    plan.status.oldestExcluded shouldBe Some(2)
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

  // ─── token boxes ──────────────────────────────────────────────────────────

  /**
   * Emission pays LIT in amounts that leave dust behind, so token boxes are most of what a
   * fragmented wallet accumulates. Excluding them would leave the bulk of the problem untouched.
   */
  "Token boxes" should "be eligible rather than excluded" in {
    val api = mock[NodeApi]
    serve(api, Vector(entry(1, 1, token = true), entry(2, 2, token = true), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 100, 1)
    plan.status.eligible shouldBe 3
    plan.boxes.map(_.creationHeight) shouldBe Vector(1, 2, 3)
  }

  /**
   * Registers do not exclude a box either.
   *
   * The consolidated output carries value and tokens only, so a register on a spent box is dropped.
   * That is the intent: registers on a box at this miner's own key are data someone attached, not
   * protocol state, and leaving those boxes behind would strand part of a fragmented wallet.
   */
  it should "be eligible even when carrying registers" in {
    val api = mock[NodeApi]
    val withRegister = {
      val e = entry(1, 1)
      e.copy(box = e.box.copy(additionalRegisters = NodeRegisters(Map("R4" -> "0400"))))
    }
    serve(api, Vector(withRegister, entry(2, 2), entry(3, 3)))
    val plan = ConsolidationExecution.select(api, Set("wallet"), Set.empty, 100, 1)
    plan.status.eligible shouldBe 3
    plan.boxes.map(_.creationHeight) shouldBe Vector(1, 2, 3)
  }

  // ─── laying tokens out across outputs ─────────────────────────────────────

  private val fee = 1000000L
  private val min = work.lithos.mutations.UTXO.MIN_CHANGE

  /** An input holding `n` distinct tokens, each of amount 1. */
  private def held(value: Long, tokenIds: Seq[String]): work.lithos.mutations.InputUTXO =
    support.OfflineContext.withCtx { ctx =>
      work.lithos.mutations.UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, value,
        tokenIds.map(id => work.lithos.mutations.Token(org.ergoplatform.sdk.ErgoId.create(id), 1L)))
        .toInput(ctx, org.ergoplatform.sdk.ErgoId.create("ab" * 32), 0.toShort)
    }

  private def id(i: Int): String = f"$i%064x"

  "The output plan" should "put a token-free batch into one output" in {
    val plan = ConsolidationExecution.outputPlan(Seq(held(min * 4, Seq.empty)), fee, 0L)
    plan.size shouldBe 1
    plan.head._1 shouldBe empty
    plan.head._2 shouldBe (min * 4 - fee)
  }

  it should "sum a token id held across several inputs" in {
    val inputs = Seq(held(min * 3, Seq(id(1))), held(min * 3, Seq(id(1))))
    val plan = ConsolidationExecution.outputPlan(inputs, fee, 0L)
    plan.size shouldBe 1
    plan.head._1.map(t => t.id.toString -> t.amount) shouldBe Seq(id(1) -> 2L)
  }

  it should "keep 50 distinct ids on one output" in {
    val plan = ConsolidationExecution.outputPlan(
      Seq(held(min * 10, (1 to 50).map(id))), fee, 0L)
    plan.size shouldBe 1
    plan.head._1.size shouldBe 50
  }

  /** One past the ceiling is the case that has to split rather than overfill a box. */
  it should "split 51 distinct ids across two outputs" in {
    val plan = ConsolidationExecution.outputPlan(
      Seq(held(min * 10, (1 to 51).map(id))), fee, 0L)
    plan.size shouldBe 2
    plan.map(_._1.size) shouldBe Seq(50, 1)
    withClue("the first output takes the remainder so the rest sit at the minimum: ") {
      plan(1)._2 shouldBe min
      plan.map(_._2).sum shouldBe (min * 10 - fee)
    }
  }

  it should "give every token exactly one output and lose none" in {
    val ids = (1 to 130).map(id)
    val plan = ConsolidationExecution.outputPlan(Seq(held(min * 20, ids)), fee, 0L)
    plan.size shouldBe 3
    plan.flatMap(_._1).map(_.id.toString) should contain theSameElementsAs ids
    plan.flatMap(_._1).map(_.id.toString).distinct.size shouldBe 130
  }

  /**
   * Cancelled rather than trimmed. Dropping a group to fit would leave those tokens unaccounted for
   * and the transaction would not balance, so the batch waits for a pass that can fund it.
   */
  it should "refuse a batch that cannot fund one minimum output per group" in {
    val thin = Seq(held(fee + min, (1 to 51).map(id)))
    intercept[IllegalArgumentException](ConsolidationExecution.outputPlan(thin, fee, 0L))
  }

  /** Re-emission tokens are burned by the build, so they never reach an output. */
  it should "leave re-emission tokens out and set their obligation aside" in {
    val reemission = work.lithos.mutations.MainnetEip27Constants.TokenId
    val inputs = Seq(held(min * 10, Seq(reemission, id(1))))
    val plan = ConsolidationExecution.outputPlan(inputs, fee, obligation = min)
    plan.flatMap(_._1).map(_.id.toString) shouldBe Seq(id(1))
    withClue("the obligation is paid to the proxy, so it is not the plan's to spend: ") {
      plan.map(_._2).sum shouldBe (min * 10 - fee - min)
    }
  }
}
