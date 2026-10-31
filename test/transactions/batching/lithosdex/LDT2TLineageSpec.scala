package transactions.batching.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderContracts, LDOrderTerms, LDT2TContracts}
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.LDBoxes.{PoolSnapshot, TransitionKind}
import work.lithos.mutations.{Token, UTXO}

/**
 * Reading a token/token pool's lineage for its graphs: reserves come from its tokens, its ERG being storage, and an
 * order fill is attributed through the token/token order templates.
 */
class LDT2TLineageSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val tokenX = ErgoId.create("d4" * 32)
  private val tokenY = ErgoId.create("d5" * 32)
  private val t2t = LDT2TContracts(ErgoId.create("d1" * 32), ErgoId.create("d2" * 32), ErgoId.create("d3" * 32))
  private val Fee = 3000000L

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def zero: ErgoValue[_] = ErgoValue.of(BigInt(0).bigInteger)

  private def snapshot(rx: Long, ry: Long, px: Long, py: Long, txId: String) =
    PoolSnapshot(100, 1L, "b" + txId.take(63), txId, rx, ry, px, py, BigInt(0), BigInt(0), 1000000L)

  "A token/token pool box" should "read its reserves from its tokens, less what it holds for the vault" in withCtx { ctx =>
    val box = LDNodeFixtures.indexed(LDNodeFixtures.nodeBox(ctx, UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
      Seq(Token(t2t.poolNFT, 1L), Token(tokenX, 5010L), Token(tokenY, 8020L), Token(t2t.provToken, 1000L)),
      Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(10L, 20L)),
        zero, zero))))
    val read = LDBoxes.readSnapshot(box, tokenToken = true).getOrElse(fail("the pool box did not read"))
    (read.reservesX, read.reservesY, read.pendingX, read.pendingY) shouldBe ((5000L, 8000L, 10L, 20L))
  }

  "A token/token swap" should "be attributed to the token/token order the transaction filled" in withCtx { ctx =>
    val owner = fake._3.p2pk.getPublicKey
    val order = LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.t2tSwap(LDOrderTerms(owner, t2t.poolNFT, Fee, Fee / 3), 1L),
      LDOrderTransactions.t2tOrderValue(Fee), Seq(Token(tokenX, 1000L))), index = 1, txId = "f7" * 32)
    val (prev, next) = (snapshot(5000L, 8000L, 0L, 0L, "a1" * 32), snapshot(5997L, 6670L, 3L, 0L, "a2" * 32))
    def classify(tokenToken: Boolean) = LDBoxes.classifyTransition(prev, next, next.txId, Some(101),
      i => if (i == 1) Some(order) else None, t2t.poolNFT.toString, t2t.vaultNFT.toString, tokenToken)
      .getOrElse(fail("the swap did not classify"))
    val swap = classify(tokenToken = true)
    (swap.kind, swap.orderBoxId) shouldBe ((TransitionKind.Swap, Some(order.boxId)))
    swap.swap.map(s => (s.ergIn, s.amountIn, s.amountOut)) shouldBe Some((true, 1000L, 1330L))
    // Read through the ERG/token templates the same fill looks like a direct spend
    classify(tokenToken = false).orderBoxId shouldBe None
  }

  "A token/token flush" should "be read as moving both pending sides into the vault" in {
    val (prev, next) = (snapshot(5000L, 8000L, 10L, 20L, "a3" * 32), snapshot(5000L, 8000L, 0L, 0L, "a4" * 32))
    val flush = LDBoxes.classifyTransition(prev, next, next.txId, Some(102), _ => None, t2t.poolNFT.toString,
      t2t.vaultNFT.toString, tokenToken = true).getOrElse(fail("the flush did not classify"))
    (flush.kind, flush.amountX, flush.amountY) shouldBe ((TransitionKind.Flush, 10L, 20L))
  }
}
