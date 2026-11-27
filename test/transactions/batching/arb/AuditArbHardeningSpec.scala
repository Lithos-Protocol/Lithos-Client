package transactions.batching.arb

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import node.model.{NodeAsset, NodeBox, NodeRegisters, NodeTransaction}
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pricing.SwapCurve
import sigma.Colls
import support.FakeNodeContext
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.tracking.{PoolDeployment, PoolShapes, TrackedBox, TrackedPools}
import transactions.engine.EngineIntent
import transactions.rollups.TransactionMessages.{RollupTxStub, RollupTxType}
import work.lithos.mutations.{Token, UTXO}

/**
 * Regression tests for Follow-Up Audit 2 fixes across pricing, routing, tracking, and engine keys:
 * T4 (SwapCurve infinite loop & negative R6), T5 (singleton protection & native pool min ERG),
 * T7 (ArbRoutes dead-end pruning & step cap), T10 (EngineIntent.Rollups.key accused miner).
 */
class AuditArbHardeningSpec extends AnyFlatSpec with Matchers {

  private def id(n: Int): String = f"$n%064x"

  "SwapCurve.LithosDex" should "return None/0 quickly for out-of-range fee params instead of looping forever (T4)" in {
    // Previously dexFeeNum = 9970, sideFeeNum = 10001 caused a 9.22e18-iteration infinite loop
    SwapCurve.LithosDexNative(9970, 10001).in(1000000L, 1000000L, 1000L) shouldBe None
    SwapCurve.LithosDexToken(9970, 10001).in(1000000L, 1000000L, 1000L) shouldBe None
    SwapCurve.LithosDexNative(-9970, 10001).in(1000000L, 1000000L, 1000L) shouldBe None
    SwapCurve.LithosDexNative(0, 0).in(1000000L, 1000000L, 1000L) shouldBe None
    SwapCurve.LithosDexNative(10001, 0).in(1000000L, 1000000L, 1000L) shouldBe None
    SwapCurve.LithosDexNative(-9970, 10001).out(1000000L, 1000000L, 1000L) shouldBe 0L
    SwapCurve.LithosDexNative(-9970, 10001).marginal(1000000L, 1000000L) shouldBe 0.0
    SwapCurve.LithosDexNative(9970, 10000).marginal(1000000L, 1000000L) shouldBe 0.0
    // Valid fee params still quote normally
    SwapCurve.LithosDexNative(9970, 15).in(1000000L, 1000000L, 1000L).isDefined shouldBe true
    SwapCurve.LithosDexToken(9970, 15).in(1000000L, 1000000L, 1000L).isDefined shouldBe true
  }

  "LDLiquidityPool and LDT2TLiquidityPool" should "reject boxes with negative pending R6 or out-of-range fee R5 (T4)" in {
    val client = FakeNodeContext.offlineClient()
    val n2t = LDContracts(ErgoId.create("a1" * 32), ErgoId.create("a2" * 32), ErgoId.create("a3" * 32), NetworkType.MAINNET)
    val t2t = LDT2TContracts(ErgoId.create("b1" * 32), ErgoId.create("b2" * 32), ErgoId.create("b3" * 32))
    val tokX = ErgoId.create("c1" * 32)
    val tokY = ErgoId.create("c2" * 32)
    def longs(v: Array[Long]) = ErgoValue.of(Colls.fromArray(v), scalaLongType)
    val zero = ErgoValue.of(BigInt(0).bigInteger)

    client.execute { ctx =>
      val badPendingN2T = UTXO(n2t.liquidityPool, 2L * Parameters.OneErg,
        Seq(Token(n2t.poolNFT, 1L), Token(tokY, 1000000L), Token(n2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(-1000000000000L, 0L)), zero, zero)).toInput(ctx, ErgoId.create("d1" * 32), 0)
      an[IllegalArgumentException] should be thrownBy LDLiquidityPool(badPendingN2T)

      val badFeeN2T = UTXO(n2t.liquidityPool, 2L * Parameters.OneErg,
        Seq(Token(n2t.poolNFT, 1L), Token(tokY, 1000000L), Token(n2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(Array(9970L, 10001L, 0L)),
          longs(Array(0L, 0L)), zero, zero)).toInput(ctx, ErgoId.create("d2" * 32), 0)
      an[IllegalArgumentException] should be thrownBy LDLiquidityPool(badFeeN2T)

      val badPendingT2T = UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
        Seq(Token(t2t.poolNFT, 1L), Token(tokX, 1000000L), Token(tokY, 1000000L), Token(t2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(0L, -500000L)), zero, zero)).toInput(ctx, ErgoId.create("d3" * 32), 0)
      an[IllegalArgumentException] should be thrownBy LDT2TLiquidityPool(badPendingT2T)

      val badFeeT2T = UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
        Seq(Token(t2t.poolNFT, 1L), Token(tokX, 1000000L), Token(tokY, 1000000L), Token(t2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(Array(-9970L, 10001L, 0L)),
          longs(Array(0L, 0L)), zero, zero)).toInput(ctx, ErgoId.create("d4" * 32), 0)
      an[IllegalArgumentException] should be thrownBy LDT2TLiquidityPool(badFeeT2T)
    }
  }

  "ArbRoutes.enumerate" should "prune dead-end DAGs via backward BFS and enforce maxSteps (T7)" in {
    def fakePool(n: Int, x: ArbAsset, y: ArbAsset): ArbPool =
      ErgoDexArbPool(ErgoDexPool(id(n), id(n + 50000), y.tokenId.get, 1000000000L, 1000000000L, 1000000L, 997,
        id(n + 100000), x.tokenId, 1000000L))
    val h1 = ArbAsset.token(id(9001))
    val h2 = ArbAsset.token(id(9002))
    val h3 = ArbAsset.token(id(9003))
    val h4 = ArbAsset.token(id(9004))
    // 1 ERG<->H1 + 200 H1<->H2 + 200 H2<->H3 + 200 H3<->H4 = 8M dead-end paths if unpruned, zero valid cycles
    val deadEndPools: Seq[ArbPool] = Seq(fakePool(1, ArbAsset.Erg, h1)) ++
      (0 until 200).map(i => fakePool(10 + i, h1, h2)) ++
      (0 until 200).map(i => fakePool(1000 + i, h2, h3)) ++
      (0 until 200).map(i => fakePool(2000 + i, h3, h4))
    val t0 = System.nanoTime()
    val (routes, cut) = ArbRoutes.enumerate(deadEndPools, ArbDiscovery.MaxRoutes)
    val elapsedMs = (System.nanoTime() - t0) / 1000000L
    routes shouldBe empty
    cut shouldBe false
    elapsedMs should be < 2000L

    // Cyclic dense graph with tight maxSteps cap must set cut = true and stop promptly
    val cyclePools: Seq[ArbPool] =
      (0 until 30).map(i => fakePool(3000 + i, ArbAsset.Erg, h1)) ++
      (0 until 30).map(i => fakePool(4000 + i, h1, h2)) ++
      (0 until 30).map(i => fakePool(5000 + i, ArbAsset.Erg, h2))
    val (_, cutBySteps) = ArbRoutes.enumerate(cyclePools, ArbDiscovery.MaxRoutes, maxSteps = 50)
    cutBySteps shouldBe true
  }

  "TrackedPools" should "never block singletons when pool boxes exceed cap (T5)" in {
    val shapes = new PoolShapes(NetworkType.MAINNET)
    val oracleNft = sigmausd.SigmaUsdContracts.OracleNft
    val oracleBox = NodeBox(id(1), id(10), 1000000L, 0, 101, "00", Seq(NodeAsset(oracleNft, 1L)))
    val filled = (2 to 5).map(i => TrackedBox(NodeBox(id(i), id(10 + i), 1000000L, 0, 100, "00", Seq(NodeAsset(id(i), 1L))),
      100, PoolDeployment.ErgoDexNative))
    val base = TrackedPools.seeded(100, id(50), filled, cut = false)
    val tx = NodeTransaction(id(90), Seq.empty, Seq.empty, Seq(oracleBox))
    val after = base.applied(101, id(51), id(50), Seq(tx), shapes, cap = 3)
    after.pools.exists(_.boxId == oracleBox.boxId) shouldBe true
  }

  "ErgoDexPool.native" should "reject dust boxes below MinStorageRent (T5)" in {
    val dust = NodeBox(id(1), id(2), ErgoDexPool.MinStorageRent - 1L, 0, 100, "00",
      Seq(NodeAsset(id(3), 1L), NodeAsset(id(4), 1000L), NodeAsset(id(5), 1000L)),
      NodeRegisters(Map("R4" -> "04c40f")))
    ErgoDexPool.native(dust) shouldBe None
  }

  "EngineIntent.Rollups.key" should "distinguish fraud proofs targeting different accused miners (T10)" in {
    val minerA = Array.fill[Byte](33)(0x02)
    val minerB = Array.fill[Byte](33)(0x03)
    val stubA = RollupTxStub(id(1), Some(10L), RollupTxType.NISPEvaluation, fpInfo = Some(minerA -> "diff"))
    val stubB = RollupTxStub(id(1), Some(10L), RollupTxType.NISPEvaluation, fpInfo = Some(minerB -> "diff"))
    val stubA2 = RollupTxStub(id(1), Some(10L), RollupTxType.NISPEvaluation, fpInfo = Some(minerA.clone() -> "diff"))
    EngineIntent.Rollups(Vector(stubA)).key should not be EngineIntent.Rollups(Vector(stubB)).key
    EngineIntent.Rollups(Vector(stubA)).key shouldBe EngineIntent.Rollups(Vector(stubA2)).key
  }
}
