package transactions.dex

import lithosdex.LDHelpers
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
import org.ergoplatform.appkit.scalaapi._
import support.FakeNodeContext
import work.lithos.mutations.{Token, UTXO}

import scala.util.{Failure, Success}

/**
 * Finding LithosDex boxes: what the node is asked, and what an answer is allowed to mean.
 *
 * Every defect pinned here had the same shape — a lookup that could not tell "this is not on chain"
 * from "the node did not say". A provision listing read confirmed-only, so it showed a box another
 * request had already spent and hid one a deposit had just created; a failed index page ended the
 * walk and returned what had been read, so a by-id lookup answered 404 for a box that exists and a
 * listing silently dropped positions. Both present to a caller as a missing provision, which is the
 * one thing they are not.
 */
class LDBoxesSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private lazy val fake = FakeNodeContext(numAddresses = 1)

  // `ctx => f(ctx)` rather than `f`: appkit's `execute` takes a java.util.function.Function, and
  // scalac SAM-converts a lambda literal where IntelliJ will not convert a Function1 value.
  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private def provToken(ctx: BlockchainContext): ErgoId = LDHelpers.getProvToken(ctx.getNetworkType)

  private def bigIntValue(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  /** A provision box as the node would report it: guard script, one provision token, R4..R7. */
  private def provisionNodeBox(ctx: BlockchainContext,
                               boxId: String,
                               shares: Long = 1000L,
                               owner: ErgoId = ErgoId.create("dd" * 32),
                               tree: String = null,
                               tokens: Seq[Token] = null): NodeBox = {
    val guard = if (tree == null) DexContracts(ctx).provisionGuard else DexContracts(ctx).provisionGuard
    val assets =
      if (tokens == null) Seq(NodeAsset(provToken(ctx).toString, 1L))
      else tokens.map(t => NodeAsset(t.id.toString, t.amount))
    val regs = Seq[ErgoValue[_]](
      bigIntValue(BigInt(0)), bigIntValue(BigInt(0)), bytesValue(owner.getBytes), ErgoValue.of(shares))
    NodeBox(
      boxId = boxId,
      transactionId = "ee" * 32,
      value = LDHelpers.PROVISION_MIN,
      index = 0,
      creationHeight = 100,
      ergoTree = if (tree == null) guard.ergoTreeHex else tree,
      assets = assets,
      additionalRegisters = NodeRegisters(
        regs.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
  }

  private def indexed(box: NodeBox, spentBy: Option[String] = None): IndexedBox =
    IndexedBox(box = box, address = "guard", inclusionHeight = 100, globalIndex = 7L,
      spentTransactionId = spentBy)

  private def emptyMempool(api: NodeApi): Unit = {
    when(api.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(api.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
  }

  private val boxId = "0f" * 32

  // ─── resolving one provision ──────────────────────────────────────────────

  "provisionById" should "resolve a confirmed provision by its own id" in withCtx { ctx =>
    val api = mock[NodeApi]
    emptyMempool(api)
    when(api.indexedBoxById(boxId)).thenReturn(Success(Some(indexed(provisionNodeBox(ctx, boxId)))))

    LDBoxes.provisionById(ctx, api, boxId).shares shouldEqual 1000L
  }

  it should "refuse a provision an unconfirmed transaction is already spending" in withCtx { ctx =>
    // Confirmed-only discovery reported this box as live, so a claim or resize was built on it and
    // rejected by the node for double spending — after selecting and signing against wallet inputs.
    val api = mock[NodeApi]
    val spender = NodeTransaction(id = "ab" * 32, inputs = Seq.empty, dataInputs = Seq.empty,
      outputs = Seq.empty)
    when(api.unconfirmedInputByBoxId(boxId)).thenReturn(Success(Some(spender)))
    when(api.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
    when(api.indexedBoxById(boxId)).thenReturn(Success(Some(indexed(provisionNodeBox(ctx, boxId)))))

    // Its own type, not a missing box: there is a successor to read, so the API answers 409 rather
    // than the 404 that tells a UI the position is gone.
    val thrown = intercept[LDBoxes.BoxBeingSpentException](LDBoxes.provisionById(ctx, api, boxId))
    thrown.getMessage should include("already being spent")
  }

  it should "resolve a provision that exists only in the mempool" in withCtx { ctx =>
    // The other half of the same asymmetry: a deposit's provision is spendable before it confirms,
    // and a confirmed-only lookup answered 404 for the box the caller had just created.
    val api = mock[NodeApi]
    when(api.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(api.indexedBoxById(boxId)).thenReturn(Success(None))
    when(api.unconfirmedOutputByBoxId(boxId))
      .thenReturn(Success(Some(provisionNodeBox(ctx, boxId, shares = 4242L))))

    LDBoxes.provisionById(ctx, api, boxId).shares shouldEqual 4242L
  }

  it should "refuse a box already spent by a block" in withCtx { ctx =>
    val api = mock[NodeApi]
    emptyMempool(api)
    when(api.indexedBoxById(boxId))
      .thenReturn(Success(Some(indexed(provisionNodeBox(ctx, boxId), spentBy = Some("cd" * 32)))))

    intercept[LDBoxes.NoSuchBoxException](LDBoxes.provisionById(ctx, api, boxId))
  }

  it should "refuse a box that is not under the provision guard" in withCtx { ctx =>
    // A box id is caller-supplied, so the shape is what makes this a provision. Reading registers off
    // whatever box the id names would let anyone hand this client a box of their own design.
    val api = mock[NodeApi]
    emptyMempool(api)
    when(api.indexedBoxById(boxId)).thenReturn(
      Success(Some(indexed(provisionNodeBox(ctx, boxId, tree = "10010101d17300")))))

    intercept[LDBoxes.NoSuchBoxException](LDBoxes.provisionById(ctx, api, boxId))
      .getMessage should include("not a LithosDex provision")
  }

  it should "refuse a guard box that does not hold exactly one provision token" in withCtx { ctx =>
    val api = mock[NodeApi]
    emptyMempool(api)
    when(api.indexedBoxById(boxId)).thenReturn(Success(Some(indexed(
      provisionNodeBox(ctx, boxId, tokens = Seq(Token(ErgoId.create("ba" * 32), 1L)))))))

    intercept[LDBoxes.NoSuchBoxException](LDBoxes.provisionById(ctx, api, boxId))
      .getMessage should include("not a LithosDex provision")
  }

  it should "report an unreachable index rather than a missing provision" in withCtx { ctx =>
    // The distinction the whole file turns on. A 404 tells a UI the position is gone; a 503 tells it
    // to try again. Answering the first when the truth is the second loses someone their position on
    // screen while it is still perfectly alive on chain.
    val api = mock[NodeApi]
    emptyMempool(api)
    when(api.indexedBoxById(boxId)).thenReturn(Failure(new RuntimeException("index off")))

    intercept[LDBoxes.IndexUnavailableException](LDBoxes.provisionById(ctx, api, boxId))
  }

  it should "report an unreachable mempool rather than a missing provision" in withCtx { ctx =>
    val api = mock[NodeApi]
    when(api.unconfirmedInputByBoxId(anyString())).thenReturn(Failure(new RuntimeException("no mempool")))
    when(api.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
    when(api.indexedBoxById(boxId)).thenReturn(Success(None))

    intercept[LDBoxes.IndexUnavailableException](LDBoxes.provisionById(ctx, api, boxId))
  }

  // ─── listing every provision ──────────────────────────────────────────────

  "provisionBoxes" should "ask the index with the mempool included" in withCtx { ctx =>
    // Pool and vault discovery is mempool-aware and provisions were not, so the three views of the
    // same DEX disagreed by one block in the one place a user acts.
    val api = mock[NodeApi]
    val captured = new java.util.concurrent.atomic.AtomicReference[MempoolOptions](null)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        captured.set(inv.getArgument[MempoolOptions](3))
        Success(Seq.empty[IndexedBox])
      }

    LDBoxes.provisionBoxes(ctx, api) shouldBe empty
    captured.get() shouldEqual MempoolOptions.WithMempool
  }

  it should "fail rather than return a short list when a page cannot be read" in withCtx { ctx =>
    // A failed page used to end the walk and hand back what had been read. To `ownedProvisions` that
    // is a user with fewer positions than they have, and to a by-id caller it was a 404.
    val api = mock[NodeApi]
    val page = (1 to 200).map(i => indexed(provisionNodeBox(ctx, f"$i%064x")))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[Paging](1).offset == 0) Success(page)
        else Failure(new RuntimeException("index dropped the connection"))
      }

    intercept[LDBoxes.IndexUnavailableException](LDBoxes.provisionBoxes(ctx, api))
  }

  "poolHistory" should "order same-height transitions by their global index" in withCtx { ctx =>
    // Several pool transitions land in one block routinely — a swap and the flush behind it. Ordered
    // by height alone the accumulator can appear to move backwards, and the fee inversion floors a
    // negative step to zero, so the block's fees are simply lost from the series.
    val api = mock[NodeApi]
    val tree = DexContracts(ctx).liquidityPool.ergoTreeHex
    def poolAt(idx: Long, boxId: String): IndexedBox =
      IndexedBox(box = poolNodeBox(ctx, boxId, tree), address = "pool",
        inclusionHeight = 500, globalIndex = idx)

    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0)
        Success(Paged(Seq(poolAt(9L, "0b" * 32), poolAt(3L, "0a" * 32)), 2))
      else Success(Paged(Seq.empty[IndexedBox], 2))
    }

    val (snapshots, complete) = LDBoxes.poolHistory(ctx, api)
    complete shouldBe true
    snapshots.map(s => (s.height, s.globalIndex)) shouldEqual Seq((500, 3L), (500, 9L))
  }

  it should "report a truncated walk rather than failing the request" in withCtx { ctx =>
    // The pool's lineage grows by one box per transaction that touches it, so a ceiling reached once
    // is reached forever. Raising it as an error meant the history endpoints would begin failing
    // permanently on a busy pool and never recover; a shorter series is still a usable answer.
    val api = mock[NodeApi]
    val tree = DexContracts(ctx).liquidityPool.ergoTreeHex
    val fullPage = (1 to 200).map(i =>
      IndexedBox(box = poolNodeBox(ctx, f"$i%064x", tree), address = "pool",
        inclusionHeight = 400 + i, globalIndex = i.toLong))
    // Never short, so the walk can only end at the ceiling.
    when(api.boxesByTokenId(anyString(), any[Paging]))
      .thenReturn(Success(Paged(fullPage, Int.MaxValue)))

    val (snapshots, complete) = LDBoxes.poolHistory(ctx, api)
    complete shouldBe false
    snapshots should not be empty
  }

  /** A pool box shaped only well enough to be found and ordered; registers are not read here. */
  private def poolNodeBox(ctx: BlockchainContext, boxId: String, tree: String): NodeBox = {
    val regs = Seq[ErgoValue[_]](
      ErgoValue.of(0L),
      ErgoValue.of(Colls.fromArray(Array(996L, 3L, 3L)), scalaLongType),
      ErgoValue.of(Colls.fromArray(Array(0L, 0L)), scalaLongType),
      bigIntValue(BigInt(0)),
      bigIntValue(BigInt(0)))
    NodeBox(
      boxId = boxId, transactionId = "ef" * 32, value = UTXO.ONE_ERG, index = 0, creationHeight = 100,
      ergoTree = tree,
      assets = Seq(
        NodeAsset(LDHelpers.getPoolNFT(ctx.getNetworkType).toString, 1L),
        NodeAsset(LDHelpers.getTokenY(ctx.getNetworkType).toString, 1000L),
        NodeAsset(provToken(ctx).toString, 10L)),
      additionalRegisters = NodeRegisters(
        regs.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
  }
}
