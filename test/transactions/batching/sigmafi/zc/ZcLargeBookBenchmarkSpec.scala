package transactions.batching.sigmafi.zc

import configs.{SigmaFiBatchingConfig, ZcConfig}
import mutations.NodeWallet
import node.model.{IndexedBox, IndexedTransaction, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import support.FakeNodeContext
import transactions.batching.arb.{ArbFixtures, ArbPoolEntry, ErgoDexArbPool}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.tracking.ScriptedChain
import transactions.candidate.CandidateBudget
import work.lithos.mutations.{Contract, Token, UTXO}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

/**
 * Latency and heap-allocation benchmark on a synthetic 10,000-box spam book (`sigmafi.zc.maxBoxes` ceiling):
 * mixed orders, offers, asks, lots, bonds, share vaults and undisclosed scripts, measuring both
 * `ZcBatchPlanner.build` and `ZcCollateralArb.bestPlan` on the candidate critical path.
 */
class ZcLargeBookBenchmarkSpec extends AnyFlatSpec with Matchers {

  import ArbFixtures.{Erg => _, id => arbId, _}
  import ZcFixtures.{Erg => _, _}
  import ZcPricing.ShareCount

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Reserve = 50000000L
  private val Fee = 5000000L
  private val SigUsd = ZcContracts.SigUsdId
  private val TokenA = "aa" * 32

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 3))
  private val feeTo: Contract = wallet.contract
  private val borrower: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val borrowerHash: String = Hex.toHexString(borrower.hashedPropBytes)
  private val lender: Contract = Contract.fromErgoContract(wallet.addresses(2).toErgoContract)
  private val lenderHash: String = Hex.toHexString(lender.hashedPropBytes)

  private val seq = new AtomicInteger(10000)
  private def nextHex(): String = f"${seq.incrementAndGet()}%064x"

  private def synthBox(ergoTreeHex: String, value: Long, assets: Seq[node.model.NodeAsset],
                       regs: Seq[ErgoValue[_]], creationHeight: Int): NodeBox = {
    val boxId = nextHex()
    val txId = nextHex()
    NodeBox(boxId, txId, value, 0, creationHeight, ergoTreeHex, assets,
      node.model.NodeRegisters(regs.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
  }

  private def usedHeapBytes(): Long = {
    val rt = Runtime.getRuntime
    rt.totalMemory() - rt.freeMemory()
  }

  "Large 10,000-box spam book" should "measure ZcBatchPlanner.build and ZcCollateralArb.bestPlan latency and heap" in {
    client.execute { ctx =>
      val h = 1900000
      val oracleUtxo = UTXO(Contract.SIGMA_TRUE, MinBox, Seq(Token(ErgoId.create(ZcContracts.OracleNft), 1L)),
        Seq(ErgoValue.of(500000000L), ErgoValue.of(1)), Some(h - 5)).toInput(ctx, ErgoId.create("99" * 32), 0)
      val oracleBox = ZcBatchTransactions.nodeBoxOf(oracleUtxo.input, oracleUtxo.input.getTransactionId, 0)

      val orderTree = ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).ergoTreeHex
      val offerTree = ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).ergoTreeHex
      val askTree = ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).ergoTreeHex
      val lotTree = ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).ergoTreeHex
      val bondTree = ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).ergoTreeHex
      val vaultTree = ZcContracts.script(ZcKind.DutchAuctionVault, None).ergoTreeHex

      val liqOracle = bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))
      val liqMat = bytes(expr(ZcExpression.MaturityLiquidation))
      val policyBytes = bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg))
      val emptyToks = tokens()
      val emptyLongs = longs()
      val emptyMeta = ZcUserTransactions.bytesList(Seq.empty)
      val emptyBytes = bytes("")

      val entries = Vector.newBuilder[ZcEntry]

      // 2,000 ERG LendOffers (disclosed lenderHash, 5% yield floor)
      val offerTerms = longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L)
      val offerRegs = Seq(bytes(lenderHash), offerTerms, policyBytes, emptyLongs, emptyMeta, emptyBytes)
      var i = 0
      while (i < 2000) {
        val box = synthBox(offerTree, 10 * Erg + i.toLong, Seq.empty, offerRegs, h - 10)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 10))
        i += 1
      }

      // 3,000 ERG Orders (2,500 open matchable orders + 500 undisclosed expired spam orders)
      val openOrderTerms = longs(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L)
      val openOrderRegs = Seq(openOrderTerms, emptyToks, bytes(borrowerHash), liqOracle)
      val sigUsdAsset = Seq(node.model.NodeAsset(SigUsd, 2000L))
      i = 0
      while (i < 2500) {
        val box = synthBox(orderTree, Fee + MinBox + i.toLong, sigUsdAsset, openOrderRegs, h - 10)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 10))
        i += 1
      }
      val expSpamTerms = longs(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong - 5L, 10 * Erg, 0L, 15000L, 60L)
      val spamHash = "ef" * 32
      val expSpamRegs = Seq(expSpamTerms, emptyToks, bytes(spamHash), liqOracle)
      i = 0
      while (i < 500) {
        val box = synthBox(orderTree, 10 * Erg + MinBox, sigUsdAsset, expSpamRegs, h - 10)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 10))
        i += 1
      }

      // 1,500 SaleOrder asks (1,000 unpriced spam tokens + 500 dust TokenA asks)
      val askRegs = Seq(bytes(borrowerHash), longs(MinBox, 1L, 1L, h.toLong + 500L))
      i = 0
      while (i < 1500) {
        val tokId = if (i < 1000) f"dd${i}%062x" else TokenA
        val box = synthBox(askTree, MinBox, Seq(node.model.NodeAsset(tokId, 1L)), askRegs, h - 10)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 10))
        i += 1
      }

      // 1,500 LotAuctions (1,499 unpriced spam lots + 1 real profitable TokenA lot)
      val lotRegs = Seq(bytes(borrowerHash), longs(2 * Erg, 2 * Erg, h.toLong - 10, h.toLong + 100, h.toLong + 500, 2000000L))
      i = 0
      while (i < 1499) {
        val box = synthBox(lotTree, 3000000L, Seq(node.model.NodeAsset(f"ee${i}%062x", 10L)), lotRegs, h - 10)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 10))
        i += 1
      }
      val realLotUtxo = UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(Token(ErgoId.create(TokenA), 5000L)), lotRegs, Some(h - 10)).toInput(ctx, ErgoId.create("88" * 32), 0)
      val realLotBox = ZcBatchTransactions.nodeBoxOf(realLotUtxo.input, realLotUtxo.input.getTransactionId, 0)
      ZcBoxes.read(realLotBox).foreach(r => entries += ZcEntry(r, h - 10))

      // 1,000 active Bonds
      i = 0
      while (i < 1000) {
        val asset = f"11${i}%062x"
        val bondRegs = Seq(bytes(asset), bytes(borrowerHash),
          longs(2 * Erg, Erg, 2 * Erg, h.toLong + 10000, h.toLong - 100, Fee, 0L),
          emptyToks, liqMat, longs(Reserve, h.toLong - 100, h.toLong + 10000))
        val box = synthBox(bondTree, 2 * Erg + Reserve, Seq(node.model.NodeAsset(asset, 2L)), bondRegs, h - 100)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 100))
        i += 1
      }

      // 1,000 DutchAuctionVaults (unpriced spam tokens)
      i = 0
      while (i < 1000) {
        val shareId = f"22${i}%062x"
        val asset = f"33${i}%062x"
        val vRegs = Seq(bytes(asset), longs(ShareCount, ShareCount, 5000000L), byte(1),
          longs(2 * Erg, 10000L, 9000L, 100L, 500L, 100L, h.toLong - 50L), emptyBytes)
        val box = synthBox(vaultTree, MinBox,
          Seq(node.model.NodeAsset(shareId, ShareCount + 1L), node.model.NodeAsset(f"44${i}%062x", 10L)),
          vRegs, h - 50)
        ZcBoxes.read(box).foreach(r => entries += ZcEntry(r, h - 50))
        i += 1
      }

      val allEntries = entries.result()
      allEntries.size shouldBe 10000
      val book = ZcBook.seeded(h - 1, "ab" * 32, allEntries, Seq(oracleBox -> (h - 5)), cut = false)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      // Pre-warm borrower & lender scripts in resolver (simulating warm cache on candidate path)
      val seedTx1 = IndexedTransaction("tx-b", Seq.empty, Seq.empty,
        Seq(IndexedBox(realLotBox.copy(ergoTree = borrower.ergoTreeHex), "", h - 10, 1L),
          IndexedBox(realLotBox.copy(ergoTree = lender.ergoTreeHex), "", h - 10, 1L)),
        h - 10, 1, "", 0L, 0, 0L, 100)
      when(chain.api.indexedTransactionById(realLotBox.transactionId)).thenReturn(Success(Some(seedTx1)))
      resolver.script(ZcBoxes.read(realLotBox).get, pending = false, new ZcBudget(4)) shouldBe Some(borrower.ergoTreeHex)
      resolver.knownScript(lenderHash) shouldBe Some(lender.ergoTreeHex)

      // Pre-mark the 500 spam orders as known-undisclosed so candidate path skips them
      val spamEntries = allEntries.collect { case e @ ZcEntry(o: ZcOrder, _) if o.borrowerHash == spamHash => e }
      spamEntries.foreach { e =>
        val emptyTx = IndexedTransaction(e.record.box.transactionId, Seq.empty, Seq.empty, Seq.empty, h - 10, 1, "", 0L, 0, 0L, 100)
        when(chain.api.indexedTransactionById(e.record.box.transactionId)).thenReturn(Success(Some(emptyTx)))
      }
      val warmBudget = new ZcBudget(600)
      spamEntries.foreach(e => resolver.script(e.record, pending = false, warmBudget))

      val pA = ArbPoolEntry(ErgoDexArbPool(ErgoDexPool.parse(nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L)).get),
        nativePool(ctx, arbId(1), TokenA, 1000 * Erg, 1000000L))
      val pools = Map(pA.nft -> pA.pool)

      // Warmup JVM JIT
      ZcCollateralArb.bestPlan(book, Seq.empty, pools, _ => Some(borrower), h, 8 * Erg, MinBox, 6, 100000L, Set.empty)
      val mbWarm = ZcMarketBook.of(ZcView(book, None, 0L))
      new ZcMatcher(mbWarm, resolver.knownOrigin).batch(ZcMarket.Erg, rounds = 5)

      // 1. Measure ZcCollateralArb.bestPlan on 10,000-box book
      val heapBeforeArb = usedHeapBytes()
      val tArb0 = System.nanoTime()
      val arbPlan = ZcCollateralArb.bestPlan(book, Seq.empty, pools,
        r => resolver.knownScript(r.namedHash.getOrElse("")).map(_ => borrower), h, 8 * Erg, MinBox, 6, 100000L, Set.empty)
      val arbMs = (System.nanoTime() - tArb0) / 1e6
      val arbHeapMb = (usedHeapBytes() - heapBeforeArb) / (1024.0 * 1024.0)
      arbPlan.map(_._1.target.boxId) shouldBe Some(realLotBox.boxId)

      // 2. Measure ZcMarketBook.of + ZcMatcher.batch (5 rounds) on 10,000-box book
      val heapBeforeMatch = usedHeapBytes()
      val tBook0 = System.nanoTime()
      val mb = ZcMarketBook.of(ZcView(book, None, 0L))
      val bookMs = (System.nanoTime() - tBook0) / 1e6
      val tMatch0 = System.nanoTime()
      val run = new ZcMatcher(mb, resolver.knownOrigin).batch(ZcMarket.Erg, rounds = 5)
      val matchMs = (System.nanoTime() - tMatch0) / 1e6
      val matchHeapMb = (usedHeapBytes() - heapBeforeMatch) / (1024.0 * 1024.0)

      // 3. Measure full ZcBatchPlanner.build inline (cold) vs with off-critical-path precomputed matches (warm)
      val heapBeforeBuild = usedHeapBytes()
      val tBuild0 = System.nanoTime()
      val builtCold = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 20, allowance = CandidateBudget.Unbounded,
        deadline = Some(Deadline.now + 5.seconds), resolverReads = 0)
      val buildColdMs = (System.nanoTime() - tBuild0) / 1e6
      val buildColdHeapMb = (usedHeapBytes() - heapBeforeBuild) / (1024.0 * 1024.0)

      val tPre0 = System.nanoTime()
      val pre = ZcBatchPlanner.precomputeMatches(book, resolver)
      val preMs = (System.nanoTime() - tPre0) / 1e6

      val heapBeforeWarm = usedHeapBytes()
      val tWarm0 = System.nanoTime()
      val builtWarm = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 20, allowance = CandidateBudget.Unbounded,
        deadline = Some(Deadline.now + 5.seconds), resolverReads = 0, precomputed = Some(pre))
      val buildWarmMs = (System.nanoTime() - tWarm0) / 1e6
      val buildWarmHeapMb = (usedHeapBytes() - heapBeforeWarm) / (1024.0 * 1024.0)

      info(f"10k-box benchmark results:")
      info(f"  ZcCollateralArb.bestPlan         : $arbMs%.1f ms (heap delta $arbHeapMb%.1f MB)")
      info(f"  ZcMarketBook.of                  : $bookMs%.1f ms")
      info(f"  ZcMatcher.batch (5 rnds)         : $matchMs%.1f ms (taken=${run.taken.values.map(_.size).sum}, heap delta $matchHeapMb%.1f MB)")
      info(f"  ZcBatchPlanner.precomputeMatches : $preMs%.1f ms (off critical path)")
      info(f"  ZcBatchPlanner.build (inline)    : $buildColdMs%.1f ms (txs=${builtCold.txs.size}, heap delta $buildColdHeapMb%.1f MB)")
      info(f"  ZcBatchPlanner.build (precomp)   : $buildWarmMs%.1f ms (txs=${builtWarm.txs.size}, heap delta $buildWarmHeapMb%.1f MB)")

      arbMs should be < 200.0
      buildWarmMs should be < 200.0
    }
  }
}
