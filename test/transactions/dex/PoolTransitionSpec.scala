package transactions.dex

import lithosdex.LDHelpers
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{FakeNodeContext, LDNodeFixtures}

import scala.util.{Failure, Success}

/**
 * Telling a swap from everything else that spends the pool box, and finding the ones still in the
 * mempool.
 *
 * The pool box does not record which operation produced it, so a swap has to be recognised from what
 * moved between two consecutive boxes. Getting that wrong in either direction is silent: a deposit
 * counted as a swap invents a trade that never happened, and a swap missed drops one from the table.
 *
 * The amounts matter as much as the classification. The pool splits a trader's input between the
 * reserve and the pending fee, so reading the reserve delta alone under-reports every swap by exactly
 * the protocol fee.
 */
class PoolTransitionSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private lazy val fake = FakeNodeContext(numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val erg = 1000000000L
  private val ReservesX = 10L * erg
  private val ReservesY = 10000L * 1000000L

  /** A pool box wrapped as the index would report it, so `poolHistory` can decode it. */
  private def at(ctx: BlockchainContext,
                 reservesX: Long,
                 reservesY: Long,
                 pendingX: Long = 0L,
                 pendingY: Long = 0L,
                 supply: Long = LDHelpers.GENESIS_SUPPLY,
                 provTokens: Long = 1000000L,
                 height: Int = 500,
                 globalIndex: Long = 1L,
                 index: Int = 0): IndexedBox =
    LDNodeFixtures.indexed(
      LDNodeFixtures.poolBox(ctx, reservesX, reservesY, pendingX = pendingX, pendingY = pendingY,
        supply = supply, provTokens = provTokens, index = index),
      height = height, globalIndex = globalIndex)

  /** Decode two boxes into snapshots the way `poolHistory` does, then classify the step. */
  private def transition(a: IndexedBox, b: IndexedBox): Option[LDBoxes.SwapTransition] = {
    val api = mock[NodeApi]
    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Paged(Seq(a, b), 2))
      else Success(Paged(Seq.empty[IndexedBox], 2))
    }
    withCtx { ctx =>
      val (snaps, _) = LDBoxes.poolHistory(ctx, api)
      snaps should have size 2
      LDBoxes.classifySwap(snaps.head, snaps(1), Some(snaps(1).height))
    }
  }

  // ─── what counts as a swap ────────────────────────────────────────────────

  "An ERG-in swap" should "be classified with the fee counted into amountIn" in withCtx { ctx =>
    // 1 ERG in: the curve takes 0.9985 ERG and 0.0015 ERG is set aside as a protocol fee. Reading
    // the reserve delta alone would report 0.9985 and under-report every swap by the fee.
    val tradedIn = 998500000L
    val protocolFee = 1500000L
    val out = 30124883L
    val swap = transition(
      at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L),
      at(ctx, ReservesX + tradedIn, ReservesY - out, pendingX = protocolFee,
        height = 501, globalIndex = 2L, index = 1))

    swap.map(_.ergIn) shouldEqual Some(true)
    swap.map(_.amountIn) shouldEqual Some(tradedIn + protocolFee)
    swap.map(_.amountOut) shouldEqual Some(out)
    swap.flatMap(_.height) shouldEqual Some(501)
  }

  "A token-in swap" should "be classified in the other direction" in withCtx { ctx =>
    val tradedIn = 998500000L
    val protocolFee = 1500000L
    val out = 92000000L
    val swap = transition(
      at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L),
      at(ctx, ReservesX - out, ReservesY + tradedIn, pendingY = protocolFee,
        height = 501, globalIndex = 2L, index = 1))

    swap.map(_.ergIn) shouldEqual Some(false)
    swap.map(_.amountIn) shouldEqual Some(tradedIn + protocolFee)
    swap.map(_.amountOut) shouldEqual Some(out)
  }

  // ─── what does not ────────────────────────────────────────────────────────

  "A deposit" should "not be counted as a swap" in withCtx { ctx =>
    // Both reserves rise together, which is what excludes it — a swap moves them against each other.
    // Supply rising excludes it a second time; the test below is the one that pins THAT.
    transition(
      at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L),
      at(ctx, ReservesX + erg, ReservesY + 300000000L, supply = LDHelpers.GENESIS_SUPPLY + 1000000L,
        provTokens = 999999L, height = 501, globalIndex = 2L, index = 1)) shouldBe None
  }

  "A redemption" should "not be counted as a swap" in withCtx { ctx =>
    transition(
      at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L),
      at(ctx, ReservesX - erg, ReservesY - 300000000L, supply = LDHelpers.GENESIS_SUPPLY - 1000000L,
        provTokens = 1000001L, height = 501, globalIndex = 2L, index = 1)) shouldBe None
  }

  "A flush" should "not be counted as a swap" in withCtx { ctx =>
    // Pending moves to the vault; the reserves the AMM trades with do not change at all.
    transition(
      at(ctx, ReservesX, ReservesY, pendingX = 5000000L, pendingY = 200000L,
        height = 500, globalIndex = 1L),
      at(ctx, ReservesX, ReservesY, height = 501, globalIndex = 2L, index = 1)) shouldBe None
  }

  "A resize" should "not be counted as a swap" in withCtx { ctx =>
    // A resize moves reserves AND flushes, so it looks the most like a swap of anything here —
    // supply is the only thing separating them.
    transition(
      at(ctx, ReservesX, ReservesY, pendingX = 5000000L, height = 500, globalIndex = 1L),
      at(ctx, ReservesX + erg, ReservesY + 300000000L, supply = LDHelpers.GENESIS_SUPPLY + 1000000L,
        height = 501, globalIndex = 2L, index = 1)) shouldBe None
  }

  "A transition that changes supply while the reserves move against each other" should
    "not be counted as a swap" in withCtx { ctx =>
    // The shape the supply test exists for, and the only one that reaches it: no operation the pool
    // performs produces this, so it can only come from a box read off chain. Without the supply
    // test it reads as a perfectly ordinary ERG-in swap and invents a trade from nothing.
    transition(
      at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L),
      at(ctx, ReservesX + erg, ReservesY - 30000000L, supply = LDHelpers.GENESIS_SUPPLY + 1000000L,
        height = 501, globalIndex = 2L, index = 1)) shouldBe None
  }

  // ─── the mempool ──────────────────────────────────────────────────────────

  /** A mempool transaction spending `spends` and producing a pool box in the given state. */
  private def memTx(ctx: BlockchainContext,
                    id: String,
                    spends: String,
                    reservesX: Long,
                    reservesY: Long,
                    pendingX: Long,
                    index: Int): NodeTransaction =
    NodeTransaction(
      id = id,
      inputs = Seq(NodeInput(spends, NodeSpendingProof.empty)),
      dataInputs = Seq.empty,
      outputs = Seq(LDNodeFixtures.poolBox(ctx, reservesX, reservesY, pendingX = pendingX,
        index = index, txId = id)))

  private def mempool(ctx: BlockchainContext, confirmed: IndexedBox, txs: Seq[NodeTransaction]) = {
    val api = mock[NodeApi]
    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Paged(Seq(confirmed), 1))
      else Success(Paged(Seq.empty[IndexedBox], 1))
    }
    when(api.unconfirmedTransactionsByErgoTree(anyString(), any[Paging])).thenReturn(Success(txs))
    val (snaps, _) = LDBoxes.poolHistory(ctx, api)
    (api, snaps)
  }

  "A mempool swap" should "be found and reported with no height" in withCtx { ctx =>
    // An unconfirmed swap can still roll back, so it has to be distinguishable from a confirmed one
    // rather than defaulted to a height.
    val confirmed = at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L)
    val tx = memTx(ctx, "ab" * 32, confirmed.boxId,
      ReservesX + 998500000L, ReservesY - 30124883L, 1500000L, index = 1)
    val (api, snaps) = mempool(ctx, confirmed, Seq(tx))

    val swaps = LDBoxes.mempoolSwaps(ctx, api, snaps.map(s => s.boxId -> s).toMap)
    swaps should have size 1
    swaps.head.height shouldBe None
    swaps.head.txId shouldEqual "ab" * 32
    swaps.head.ergIn shouldBe true
    swaps.head.amountIn shouldEqual 998500000L + 1500000L
  }

  it should "order a chain of them newest first" in withCtx { ctx =>
    // Two swaps chained in the mempool, handed back by the node in the wrong order. Ordering by
    // arrival would show them backwards; each one's predecessor is found by the box it spends.
    val confirmed = at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L)
    val firstOut = LDNodeFixtures.poolBox(ctx, ReservesX + 1000000000L, ReservesY - 30000000L,
      pendingX = 1500000L, index = 1, txId = "aa" * 32)
    val txA = NodeTransaction("aa" * 32,
      Seq(NodeInput(confirmed.boxId, NodeSpendingProof.empty)),
      Seq.empty, Seq(firstOut))
    val txB = memTx(ctx, "bb" * 32, firstOut.boxId,
      ReservesX + 2000000000L, ReservesY - 59000000L, 3000000L, index = 2)

    // Deliberately reversed: the newer transaction is returned first.
    val (api, snaps) = mempool(ctx, confirmed, Seq(txB, txA))
    val swaps = LDBoxes.mempoolSwaps(ctx, api, snaps.map(s => s.boxId -> s).toMap)

    swaps.map(_.txId) shouldEqual Seq("bb" * 32, "aa" * 32)
  }

  it should "take the transaction's own id, not the one the output box reports" in withCtx { ctx =>
    // A mempool response is not obliged to fill in `transactionId` on its outputs, and the
    // transaction being iterated is by definition the one that created the box — so reading the id
    // from anywhere else can only be wrong. Here the box deliberately disagrees.
    val confirmed = at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L)
    val out = LDNodeFixtures.poolBox(ctx, ReservesX + 998500000L, ReservesY - 30124883L,
      pendingX = 1500000L, index = 1, txId = "00" * 32)
    val tx = NodeTransaction("dd" * 32,
      Seq(NodeInput(confirmed.boxId, NodeSpendingProof.empty)), Seq.empty, Seq(out))
    val (api, snaps) = mempool(ctx, confirmed, Seq(tx))

    val swaps = LDBoxes.mempoolSwaps(ctx, api, snaps.map(s => s.boxId -> s).toMap)
    swaps.map(_.txId) shouldEqual Seq("dd" * 32)
  }

  it should "ignore a transaction whose predecessor is not a known pool box" in withCtx { ctx =>
    // A pool box appearing from nothing this client can see is not a transition it can price.
    val confirmed = at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L)
    val orphan = memTx(ctx, "cc" * 32, "ff" * 32,
      ReservesX + 998500000L, ReservesY - 30124883L, 1500000L, index = 1)
    val (api, snaps) = mempool(ctx, confirmed, Seq(orphan))

    LDBoxes.mempoolSwaps(ctx, api, snaps.map(s => s.boxId -> s).toMap) shouldBe empty
  }

  it should "report an unreachable mempool rather than an empty list" in withCtx { ctx =>
    val confirmed = at(ctx, ReservesX, ReservesY, height = 500, globalIndex = 1L)
    val api = mock[NodeApi]
    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Paged(Seq(confirmed), 1))
      else Success(Paged(Seq.empty[IndexedBox], 1))
    }
    when(api.unconfirmedTransactionsByErgoTree(anyString(), any[Paging]))
      .thenReturn(Failure(new RuntimeException("mempool off")))
    val (snaps, _) = LDBoxes.poolHistory(ctx, api)

    intercept[LDBoxes.IndexUnavailableException](
      LDBoxes.mempoolSwaps(ctx, api, snaps.map(s => s.boxId -> s).toMap))
  }

  // ─── reserves are decoded net of pending ──────────────────────────────────

  "A snapshot" should "report reserves net of what the box holds for the vault" in withCtx { ctx =>
    // The box value covers reservesX + pendingX and the token entry reservesY + pendingY. Reading
    // the balances raw would count fees awaiting a flush as tradable depth and misprice everything.
    val api = mock[NodeApi]
    val box = at(ctx, ReservesX, ReservesY, pendingX = 7000000L, pendingY = 250000L,
      height = 500, globalIndex = 1L)
    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Paged(Seq(box), 1))
      else Success(Paged(Seq.empty[IndexedBox], 1))
    }

    val (snaps, _) = LDBoxes.poolHistory(ctx, api)
    snaps.head.reservesX shouldEqual ReservesX
    snaps.head.reservesY shouldEqual ReservesY
    snaps.head.pendingX shouldEqual 7000000L
    snaps.head.pendingY shouldEqual 250000L
  }
}
