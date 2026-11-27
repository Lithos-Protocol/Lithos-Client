package transactions.batching.sigmafi.zc

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{BatchingConfig, SigmaFiBatchingConfig, ZcConfig}
import mutations.NodeWallet
import node.NodeApi
import node.model._
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.tracking.ScriptedChain
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, ChainFromMempool, IncludeExisting, PrepareBlockTxs, RequestBlockTxs, Supersede}
import transactions.candidate.CandidateBudget
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

object ZcBatcherSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("akka.test.single-expect-default = 20s")
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * End-to-end tests for [[ZcBatchPlanner]] and [[ZcBatcher]]: candidate fee-less packing and offer-match chaining,
 * oracle-busy deferral, broadcast delay (`broadcastAfterBlocks`), competing mempool superseding, and resilience
 * against undisclosed-script spam floods and unbuildable pack pieces.
 */
class ZcBatcherSpec extends TestKit(ActorSystem("zc-batcher-spec", ZcBatcherSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import ZcFixtures.{Erg => _, _}
  import ZcPricing.{RefreshAge, RefreshBounty}

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Erg = 1000000000L
  private val MinBox = ZcBoxes.MinBox
  private val Reserve = 50000000L
  private val Fee = 5000000L
  private val SigUsd = ZcContracts.SigUsdId

  private val client = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 3))
  private val feeTo: Contract = wallet.contract
  private val borrower: Contract = Contract.fromErgoContract(wallet.addresses(1).toErgoContract)
  private val borrowerHash: String = Hex.toHexString(borrower.hashedPropBytes)
  private val lender: Contract = Contract.fromErgoContract(wallet.addresses(2).toErgoContract)
  private val lenderHash: String = Hex.toHexString(lender.hashedPropBytes)

  private val seq = new AtomicInteger(500)
  private def nextId(): ErgoId = ErgoId.create(f"${seq.incrementAndGet()}%064x")
  private def seeded(ctx: BlockchainContext, u: UTXO): InputUTXO = u.toInput(ctx, nextId(), 0)
  private def tok(id: String, n: Long): Token = Token(ErgoId.create(id), n)
  private def nodeOf(in: InputUTXO): NodeBox =
    ZcBatchTransactions.nodeBoxOf(in.input, in.input.getTransactionId, in.input.getTransactionIndex)

  private def oracleNodeBox(ctx: BlockchainContext, price: Long, created: Int): NodeBox =
    nodeOf(seeded(ctx, UTXO(Contract.SIGMA_TRUE, MinBox, Seq(tok(ZcContracts.OracleNft, 1L)),
      Seq(ErgoValue.of(price), ErgoValue.of(1)), Some(created))))

  private def maturedBondBox(ctx: BlockchainContext, h: Int, assetHex: String, feeReserve: Long = Reserve,
                             maturityOffset: Long = -10L): NodeBox =
    nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
      2 * Erg + feeReserve, Seq(tok(assetHex, 2L)),
      Seq(bytes(assetHex), bytes(borrowerHash),
        longs(2 * Erg, Erg, 2 * Erg, h.toLong + maturityOffset, h.toLong - 100, Fee, 0L),
        tokens(), bytes(expr(ZcExpression.MaturityLiquidation)),
        longs(feeReserve, h.toLong - 100, h.toLong + maturityOffset)),
      Some(h - 100))))

  private def ergOrderBox(ctx: BlockchainContext, h: Int, expiryOffset: Long = 5000L,
                          expiryFee: Long = Fee, ownerHash: String = borrowerHash,
                          repayment: Long = 2 * Erg): NodeBox = {
    val terms = Seq(repayment, Erg, repayment, h.toLong + 50000L, h.toLong + expiryOffset, expiryFee, 0L, 15000L, 60L)
    nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
      expiryFee + MinBox, Seq(tok(SigUsd, 2000L)),
      Seq(longs(terms: _*), tokens(), bytes(ownerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))),
      Some(h - 10))))
  }

  private def ergOfferBox(ctx: BlockchainContext, h: Int, capital: Long = 10 * Erg): NodeBox = {
    val terms = Seq(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L)
    nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract, capital, Seq.empty,
      Seq(bytes(lenderHash), longs(terms: _*), bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)),
        longs(), ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10))))
  }

  private def discloseTx(api: NodeApi, box: NodeBox, tree: String, height: Int): Unit = {
    val tx = IndexedTransaction(box.transactionId, Seq.empty, Seq.empty,
      Seq(IndexedBox(box.copy(ergoTree = tree), "", height, 1L)), height, 1, "", 0L, 0, 0L, 100)
    when(api.indexedTransactionById(box.transactionId)).thenReturn(Success(Some(tx)))
  }

  private def bookOf(h: Int, oracle: Option[NodeBox], entries: (NodeBox, Int)*): ZcBook =
    ZcBook.seeded(h, "00" * 32,
      entries.flatMap { case (b, inc) => ZcBoxes.read(b).map(ZcEntry(_, inc)) },
      oracle.map(_ -> (h - 5)).toSeq, cut = false)

  // ─── ZcBatchPlanner tests ─────────────────────────────────────────────────

  "ZcBatchPlanner" should "pack liquidations/refunds/refreshes, separate aged bond refreshes, and chain offer matches" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)

      val matured = maturedBondBox(ctx, h, "11" * 32)
      val expOrder = ergOrderBox(ctx, h, expiryOffset = -5L)
      val agedBond = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract,
        2 * Erg + Reserve, Seq(tok("22" * 32, 2L)),
        Seq(bytes("22" * 32), bytes(borrowerHash),
          longs(2 * Erg, Erg, 2 * Erg, h.toLong + 10000, h.toLong - 500000, Fee, 0L),
          tokens(), bytes(expr(ZcExpression.MaturityLiquidation)),
          longs(Reserve, old.toLong, h.toLong + 10000)), Some(old))))

      // One offer matching two open orders in the same block (chaining offer continuation!)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      val openOrder1 = ergOrderBox(ctx, h, expiryOffset = 5000L)
      val openOrder2 = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, expOrder, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, agedBond, borrower.ergoTreeHex, old)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, openOrder1, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, openOrder2, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, Some(oracle),
        matured -> (h - 100), expOrder -> (h - 10), agedBond -> old,
        offer -> (h - 10), openOrder1 -> (h - 10), openOrder2 -> (h - 10))

      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.failedIds shouldBe empty
      // 1 regular pack (matured bond + expired order), 2 chained offer matches, 1 separate aged bond refresh
      val kinds = built.txs.map(_._1.kind)
      kinds should contain(ZcBatchTransactions.KindPack)
      kinds.count(_ == ZcBatchTransactions.KindMatchOrder) shouldBe 2
      kinds should contain(ZcBatchTransactions.KindBondRefresh)

      // The second offer match must declare the first offer match's txId as its parent
      val matches = built.txs.filter(_._1.kind == ZcBatchTransactions.KindMatchOrder)
      matches(0)._2 shouldBe empty
      matches(1)._2 shouldBe Seq(matches(0)._1.txId)
    }
  }

  it should "defer oracle-dependent work when oracleUsable is false while still executing maturity liquidations" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      val matured = maturedBondBox(ctx, h, "33" * 32)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      val openOrder = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, openOrder, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, Some(oracle), matured -> (h - 100), offer -> (h - 10), openOrder -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None, oracleUsable = false)

      built.txs.map(_._1.kind) shouldBe Vector(ZcBatchTransactions.KindPack)
      built.txs.head._1.spentIds shouldBe Set(matured.boxId)
    }
  }

  it should "remain resilient against undisclosed-script spam floods and still fill real orders" in {
    client.execute { ctx =>
      val h = 1900000
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)

      // 100 expired spam orders with higher expiryFee (10 ERG) whose script hashes were never disclosed
      val spamBoxes = (1 to 100).map { i =>
        val fakeHash = f"aa${i}%062x"
        val box = ergOrderBox(ctx, h, expiryOffset = -5L, expiryFee = 10 * Erg, ownerHash = fakeHash)
        // Return an indexed tx carrying no matching script -> marks each spam box as known undisclosed
        val emptyTx = IndexedTransaction(box.transactionId, Seq.empty, Seq.empty, Seq.empty, h - 10, 1, "", 0L, 0, 0L, 100)
        when(chain.api.indexedTransactionById(box.transactionId)).thenReturn(Success(Some(emptyTx)))
        box
      }
      // Pre-mark spam boxes as inspected (as warmCaches / prior pass would)
      val warmBudget = new ZcBudget(200)
      spamBoxes.flatMap(ZcBoxes.read(_)).foreach(r => resolver.script(r, pending = false, warmBudget))
      spamBoxes.flatMap(ZcBoxes.read(_)).forall(r => resolver.knownDisclosed(r).contains(false)) shouldBe true

      // One real expired order with normal 0.005 ERG fee and disclosed borrower script
      val realOrder = ergOrderBox(ctx, h, expiryOffset = -5L, expiryFee = Fee, ownerHash = borrowerHash)
      discloseTx(chain.api, realOrder, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, None, (spamBoxes.map(_ -> (h - 10)) :+ (realOrder -> (h - 10))): _*)
      val readsBefore = resolver.reads.get()
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      // Real order is NOT starved past MaxPackablePieces (80), and spam cost zero extra node reads!
      built.txs.size shouldBe 1
      built.txs.head._1.spentIds shouldBe Set(realOrder.boxId)
      (resolver.reads.get() - readsBefore) shouldBe 1L // only the single real order's script lookup
    }
  }

  it should "bisect a pack containing an unbuildable spam box so valid pieces still execute" in {
    client.execute { ctx =>
      val h = 1900000
      val validBond = maturedBondBox(ctx, h, "44" * 32)
      // A spam expired lot packed with 100 distinct tokens at MinBox value (~3,300 bytes * 360 > 1,000,000 nanoERG!)
      val manyTokens = (1 to 100).map(i => tok(f"bb${i}%062x", 1L))
      val bloatedLot = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract,
        MinBox + Fee, manyTokens,
        Seq(bytes(borrowerHash), longs(2 * Erg, Erg, h.toLong - 100, h.toLong - 10, h.toLong - 5, Fee)), Some(h - 50))))

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, bloatedLot, borrower.ergoTreeHex, h - 50)

      val book = bookOf(h - 1, None, validBond -> (h - 100), bloatedLot -> (h - 50))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.txs.size shouldBe 1
      built.txs.head._1.spentIds shouldBe Set(validBond.boxId)
      built.failedIds shouldBe Set(bloatedLot.boxId)
    }
  }

  // ─── ZcTracker.holdsTarget regression ─────────────────────────────────────

  it should "choose a broadcast window match's maturity for the block after the tip, where it lands" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      // The window ends 723 blocks after the bond's start and the offer takes 720 or more at any of the three blocks a
      // broadcast may land in, tip + 1 to tip + 3, so only tip + 723 suits both.
      val window = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.PricedOrder, Some(ZcMarket.Erg)).contract, 4 * Erg,
        Seq.empty, Seq(longs(2 * Erg, Erg, 2 * Erg, 30L, h.toLong + 5000L, Fee, 2L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation)),
          bytesList(expr(ZcExpression.CurveOrderPricing), ""), longs(723L, 50000L)), Some(h - 10))))
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, window, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)

      val book = bookOf(h, Some(oracle), offer -> (h - 10), window -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = Parameters.MinFee, feeTo = feeTo, slots = 10,
        allowance = CandidateBudget.Unbounded, deadline = None, broadcastWait = Some(1))

      val matched = built.txs.map(_._1).find(_.kind == ZcBatchTransactions.KindMatchOrder)
        .getOrElse(fail(s"no match built; failed ${built.failedIds}"))
      val bond = ZcBoxes.read(ZcBatchTransactions.nodeBoxOf(matched.signed.getOutputsToSpend.get(1), matched.txId, 1))
        .collect { case b: ZcBond => b }.get
      bond.maturity shouldBe h + 723L
    }
  }

  it should "build the rest of a pack when one of its pieces cannot be built" in {
    client.execute { ctx =>
      val h = 1900000
      val old = h - RefreshAge.toInt - 100
      val matured = maturedBondBox(ctx, h, "34" * 32)
      // A Dutch vault of 60 tokens: its refreshed successor would pass the node's 4,096 bytes.
      val held = (1 to 60).map(i => tok(f"${i + 100}%02x" * 32, 1000L))
      val vault = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.DutchAuctionVault, None).contract, 10 * Erg,
        tok("35" * 32, 100000001L) +: held, Seq(bytes("36" * 32), longs(100000000L, 100000000L, Fee), byte(2),
          longs(2 * Erg, 10000L, 9000L, 720L, 2160L, 100L, h.toLong - 5000L), bytes("")), Some(old))))
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      val book = bookOf(h - 1, None, matured -> (h - 100), vault -> old)
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)
      built.txs.map(_._1.spentIds) shouldBe Vector(Set(matured.boxId))
      built.failedIds shouldBe Set(vault.boxId)
    }
  }

  it should "schedule no share mint whose Dutch auction terms zcs1 would refuse" in {
    client.execute { ctx =>
      val h = 1900000
      val asset = "37" * 32
      val live = maturedBondBox(ctx, h, asset, maturityOffset = 10000L)
      def request(floorBps: Long) = nodeOf(seeded(ctx, UTXO(
        ZcContracts.script(ZcKind.DutchAuctionMint, Some(ZcMarket.Erg)).contract, 20000000L, Seq(tok(asset, 1L)),
        Seq(bytes(""), bytes(""), byte(0), bytes(lenderHash), longs(100000000L, Fee, Fee, 10000L, floorBps, 720L, 2160L, 100L),
          ZcUserTransactions.bytesList(Seq("a", "b", "c").map(_.getBytes("UTF-8")))), Some(h - 10))))
      def attempted(req: NodeBox): Boolean = {
        val chain = new ScriptedChain
        val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
        val resolver = new ZcResolver(() => chain.api, tracker)
        discloseTx(chain.api, req, lender.ergoTreeHex, h - 10)
        val built = ZcBatchPlanner.build(ctx, wallet, bookOf(h - 1, None, live -> (h - 100), req -> (h - 10)), resolver,
          SigmaFiBatchingConfig.Default, signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10,
          allowance = CandidateBudget.Unbounded, deadline = None)
        built.txs.exists(_._1.spentIds.contains(req.boxId)) || built.failedIds.contains(req.boxId)
      }
      attempted(request(9000L)) shouldBe true
      attempted(request(10001L)) shouldBe false
    }
  }

  "ZcTracker.holdsTarget" should "hold stale followed heights below book.base or already in history without rescanning" in {
    val book = ZcBook.seeded(100, "aa" * 32, Seq.empty, Seq.empty, cut = false)
    ZcTracker.holdsTarget(book, Right(100 -> ("aa" * 32))) shouldBe true
    ZcTracker.holdsTarget(book, Right(95 -> ("bb" * 32))) shouldBe true  // below book.base -> no infinite rescan loop
    ZcTracker.holdsTarget(book, Right(101 -> ("cc" * 32))) shouldBe false
  }

  // ─── ZcBatcher actor end-to-end ───────────────────────────────────────────

  it should "match at a pending refresh's oracle box when the confirmed one is too old, reading the new box" in {
    client.execute { ctx =>
      val h = 1900000
      val stale = oracleNodeBox(ctx, 500000000L, h - 200)
      val pending = ZcBoxes.oracle(oracleNodeBox(ctx, 500000000L, h - 1)).get
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      val order = ergOrderBox(ctx, h, expiryOffset = 5000L)
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, order, borrower.ergoTreeHex, h - 10)
      val book = bookOf(h - 1, Some(stale), offer -> (h - 10), order -> (h - 10))
      def build(b: ZcBook) = ZcBatchPlanner.build(ctx, wallet, b, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded, deadline = None)
      build(book).txs.filter(_._1.kind == ZcBatchTransactions.KindMatchOrder) shouldBe empty
      val matched = build(book.withOracle(pending)).txs.filter(_._1.kind == ZcBatchTransactions.KindMatchOrder)
      matched should have size 1
      val read = matched.head._1.signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx.dataInputs
        .map(d => Hex.toHexString(d.boxId))
      read shouldBe Seq(pending.box.boxId)
    }
  }

  "ZcBatcher" should "carry a pending oracle refresh ahead of the candidate bundle that reads its box" in {
    val chain = new ScriptedChain
    val tip = 1900000
    chain.extend(tip - 200, tip)
    chain.indexed = tip
    val (nodeContext, _, _) = FakeNodeContext(chain.api, numAddresses = 3)
    val (stale, fresh, offer, order) = nodeContext.getClient.execute { ctx =>
      (oracleNodeBox(ctx, 500000000L, tip - 150), oracleNodeBox(ctx, 500000000L, tip),
        ergOfferBox(ctx, tip + 1, capital = 10 * Erg), ergOrderBox(ctx, tip + 1, expiryOffset = 5000L))
    }
    chain.scans(LDNodeFixtures.indexed(stale, tip - 150), LDNodeFixtures.indexed(offer, tip - 10),
      LDNodeFixtures.indexed(order, tip - 10))
    discloseTx(chain.api, offer, lender.ergoTreeHex, tip - 10)
    discloseTx(chain.api, order, borrower.ergoTreeHex, tip - 10)
    val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
    val resolver = new ZcResolver(() => chain.api, tracker)
    tracker.poke()
    awaitAssert(tracker.book.map(_.height) shouldBe Some(tip), 10.seconds, 50.millis)
    tracker.book.flatMap(_.oracle).map(_.box.boxId) shouldBe Some(stale.boxId)

    val refreshId = "fa" * 32
    val refresh = CompleteMempool.MempoolTx(refreshId,
      NodeTransaction(refreshId, Seq(NodeInput(stale.boxId, NodeSpendingProof.empty)), Seq.empty, Seq(fresh)), 800)
    val anchor = chain.header(tip).id
    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh) sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(
          anchor, Set(refreshId), Set(stale.boxId), System.nanoTime(), transactions = Vector(refresh))), None)
        this
      }
    })
    val settings = SigmaFiBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = false),
      broadcastAfterBlocks = 1, minBatchingFeeNanoErg = Reserve, minActionFeeNanoErg = 2000000L)
    val batcher = system.actorOf(Props(new ZcBatcher(nodeContext, settings, servesCandidates = true,
      engine.ref, tracker, resolver, useTrueProp = false)))
    val requester = TestProbe()
    requester.send(batcher, PrepareBlockTxs(tip + 1, 10, anchor))
    requester.send(batcher, RequestBlockTxs(tip + 1, 10))
    val ready = requester.expectMsgType[BlockTxsReady]

    val reading = ready.bundles.filter(_.members.exists(_.kind == ZcBatchTransactions.KindMatchOrder))
    reading should have size 1
    reading.head.members.map(_.kind) shouldBe Vector(CandidateTx.MempoolAncestor, ZcBatchTransactions.KindMatchOrder)
    reading.head.members.head.id shouldBe refreshId
    reading.head.interactions should contain allOf(ChainFromMempool(refreshId), IncludeExisting(refreshId))
    reading.head.carriesItsParents shouldBe true
    system.stop(batcher)
  }

  it should "offer fee-less candidate bundles superseding competing mempool spends" in {
    val chain = new ScriptedChain
    val tip = 1900000
    chain.extend(tip - 20, tip)
    chain.indexed = tip

    val (nodeContext, _, _) = FakeNodeContext(chain.api, numAddresses = 2)
    val matured = nodeContext.getClient.execute { ctx =>
      maturedBondBox(ctx, tip + 1, "55" * 32, maturityOffset = -5L)
    }
    chain.scans(LDNodeFixtures.indexed(matured, tip - 10))

    val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
    val resolver = new ZcResolver(() => chain.api, tracker)
    tracker.poke()
    awaitAssert(tracker.book.map(_.height) shouldBe Some(tip), 10.seconds, 50.millis)

    val competitorTxId = "ee" * 32
    val competitor = CompleteMempool.MempoolTx(competitorTxId,
      NodeTransaction(competitorTxId, Seq(NodeInput(matured.boxId, NodeSpendingProof.empty)), Seq.empty, Seq.empty), 100)
    val anchor = chain.header(tip).id

    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh) {
          sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor,
            Set(competitorTxId), Set(matured.boxId), System.nanoTime(), transactions = Vector(competitor))), None)
        }
        this
      }
    })

    val settings = SigmaFiBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = false),
      broadcastAfterBlocks = 1, minBatchingFeeNanoErg = Reserve, minActionFeeNanoErg = 2000000L)
    val batcher = system.actorOf(Props(new ZcBatcher(nodeContext, settings, servesCandidates = true,
      engine.ref, tracker, resolver, useTrueProp = false)))

    val requester = TestProbe()
    requester.send(batcher, PrepareBlockTxs(tip + 1, 10, anchor))
    requester.send(batcher, RequestBlockTxs(tip + 1, 10))
    val ready = requester.expectMsgType[BlockTxsReady]

    ready.bundles should have size 1
    ready.bundles.head.members.head.kind shouldBe ZcBatchTransactions.KindPack
    ready.bundles.head.interactions should contain(Supersede(Set(competitorTxId)))
    ready.bundles.head.capital.map(_.value).sum shouldBe Reserve
    system.stop(batcher)
  }

  it should "respect broadcastAfterBlocks and skip mempool-spent boxes on broadcast passes" in {
    client.execute { ctx =>
      val h = 1900000
      val justMatured = maturedBondBox(ctx, h, "66" * 32, maturityOffset = 0L)   // matured at h
      val agedMatured = maturedBondBox(ctx, h, "77" * 32, maturityOffset = -3L)  // matured at h - 3

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      val book = bookOf(h, None, justMatured -> (h - 50), agedMatured -> (h - 50))

      // Broadcast pass at tip = h (landing at h + 1) with broadcastAfterBlocks = 2:
      // justMatured (elig = h): h + 1 < h + 2 -> must wait!
      // agedMatured (elig = h - 3): h + 1 >= (h - 3) + 2 = h - 1 -> eligible to broadcast!
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = Parameters.MinFee, feeTo = feeTo, slots = 10,
        allowance = CandidateBudget.Unbounded, deadline = None, broadcastWait = Some(2))

      built.txs should have size 1
      built.txs.head._1.spentIds shouldBe Set(agedMatured.boxId)
      built.txs.head._1.feeBox.value shouldBe Reserve - Parameters.MinFee
    }
  }

  it should "match inline builds identically when using precomputed matches, and fall back when an excluded box was taken" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      val order1 = ergOrderBox(ctx, h, expiryOffset = 5000L)
      val order2 = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, order1, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, order2, borrower.ergoTreeHex, h - 10)
      val book = bookOf(h - 1, Some(oracle), offer -> (h - 10), order1 -> (h - 10), order2 -> (h - 10))
      ZcBatchPlanner.warmCaches(book, ctx, resolver, SigmaFiBatchingConfig.Default, (h - 1).toLong)

      val pre = ZcBatchPlanner.precomputeMatches(book, resolver)
      pre.matches(book, h - 1, resolver.generation, Set.empty) shouldBe true
      pre.matches(book, h - 1, resolver.generation, Set(order1.boxId)) shouldBe false
      pre.matches(book.copy(headerId = "ff" * 32), h - 1, resolver.generation, Set.empty) shouldBe false

      val inline = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)
      val cached = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None, precomputed = Some(pre))

      cached.txs.map(_._1.spentIds) shouldBe inline.txs.map(_._1.spentIds)
      cached.txs.map(_._1.fees) shouldBe inline.txs.map(_._1.fees)

      // Excluding order1 invalidates precomputed matches cleanly so order2 is still matched
      val withExcluded = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None, excluded = Set(order1.boxId), precomputed = Some(pre))
      withExcluded.txs.map(_._1.spentIds) shouldBe Vector(Set(order2.boxId, offer.boxId))
    }
  }

  it should "not blacklist unwarmed orders or kill an offer chain when resolver reads run out" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      // order1 has a higher yield (different ownerHash, unwarmed) and order2 has a warmed borrower script
      val unwarmedHash = "cd" * 32
      val order1 = ergOrderBox(ctx, h, expiryOffset = 5000L, repayment = 1200000000L, ownerHash = unwarmedHash)
      val order2 = ergOrderBox(ctx, h, expiryOffset = 5000L, repayment = 1100000000L, ownerHash = borrowerHash)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, order2, borrower.ergoTreeHex, h - 10)
      // Warm ONLY offer and order2; leave order1 unwarmed and pass resolverReads = 0!
      val warmBudget = new ZcBudget(10)
      ZcBoxes.read(offer).foreach(resolver.script(_, pending = false, warmBudget))
      ZcBoxes.read(order2).foreach(resolver.script(_, pending = false, warmBudget))

      val book = bookOf(h - 1, Some(oracle), offer -> (h - 10), order1 -> (h - 10), order2 -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None, resolverReads = 0)

      // order1 was softly skipped (NOT blacklisted in failedIds!) and the offer chain stayed alive to match order2!
      built.failedIds shouldBe empty
      built.txs.size shouldBe 1
      built.txs.head._1.spentIds shouldBe Set(order2.boxId, offer.boxId)
    }
  }

  it should "bump resolver.generation when learning an undisclosed script so precomputed matches invalidate" in {
    client.execute { ctx =>
      val h = 1900000
      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      val spam = ergOrderBox(ctx, h, expiryOffset = 5000L, ownerHash = "ef" * 32)
      val emptyTx = IndexedTransaction(spam.transactionId, Seq.empty, Seq.empty, Seq.empty, h - 10, 1, "", 0L, 0, 0L, 100)
      when(chain.api.indexedTransactionById(spam.transactionId)).thenReturn(Success(Some(emptyTx)))

      val book = bookOf(h - 1, None, spam -> (h - 10))
      val pre = ZcBatchPlanner.precomputeMatches(book, resolver)
      val genBefore = resolver.generation
      ZcBoxes.read(spam).foreach(resolver.script(_, pending = false, new ZcBudget(5)))
      resolver.generation should be > genBefore
      pre.matches(book, h - 1, resolver.generation, Set.empty) shouldBe false
    }
  }

  it should "cap collateralArbPass attempts and stop immediately on timeout or uncertain outcome while noting ownArbs" in {
    import play.api.libs.json.{JsObject, Json}
    import transactions.batching.arb.ArbFixtures
    import transactions.batching.tracking.{PoolTracker, TrackerFixtures}
    import transactions.engine.EngineBroadcast
    import scala.util.{Failure, Try}

    val chain = new ScriptedChain
    val tip = 1900000
    chain.extend(tip - 20, tip)
    chain.indexed = tip
    val anchor = chain.header(tip).id
    val (nodeContext, _, _) = FakeNodeContext(chain.api, numAddresses = 2)
    val tokenA = "aa" * 32

    val (pools, lots) = nodeContext.getClient.execute { ctx =>
      val count = ZcBatcher.MaxArbAttemptsPerPass + 3
      val ps = (1 to count).map(i => ArbFixtures.nativePool(ctx, ArbFixtures.id(i), tokenA, 1000 * Erg, 1000000L))
      val ls = (1 to count).map { i =>
        val u = UTXO(ZcContracts.script(ZcKind.LotAuction, Some(ZcMarket.Erg)).contract, 3000000L,
          Seq(Token(ErgoId.create(tokenA), 5000L)),
          Seq(bytes(borrowerHash), longs(2 * Erg, 2 * Erg, (tip - 10).toLong, (tip + 100).toLong, (tip + 500).toLong, 2000000L)),
          Some(tip - 10))
        val in = u.toInput(ctx, ErgoId.create(f"bb$i%062x"), 0)
        ZcBatchTransactions.nodeBoxOf(in.input, in.input.getTransactionId, 0)
      }
      (ps, ls)
    }
    chain.scans((pools ++ lots).map(LDNodeFixtures.indexed(_, tip - 5)): _*)
    lots.foreach(l => discloseTx(chain.api, l, borrower.ergoTreeHex, tip - 5))

    val poolTracker = new PoolTracker(() => chain.api, org.ergoplatform.appkit.NetworkType.MAINNET,
      ExecutionContext.global, retryMs = 20L)
    poolTracker.follow(tip, anchor)
    awaitAssert(poolTracker.latest.map(_.headerId) shouldBe Some(anchor), 10.seconds, 50.millis)

    val zcTracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default, retryMs = 20L)
    zcTracker.poke()
    awaitAssert(zcTracker.book.map(_.headerId) shouldBe Some(anchor), 10.seconds, 50.millis)
    val resolver = new ZcResolver(() => chain.api, zcTracker)
    val book = zcTracker.book.get
    val observed = CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty,
      System.nanoTime(), transactions = Vector.empty)), None)
    val settings = SigmaFiBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = false),
      broadcastAfterBlocks = 1, minBatchingFeeNanoErg = Reserve, minActionFeeNanoErg = 2000000L,
      collateralArb = configs.ZcCollateralArbConfig.Default.copy(broadcast = true))
    val engine = TestProbe()

    def runWith(answer: ZcMempoolPlan => Try[JsObject]): (Int, Set[String]) = {
      val calls = new AtomicInteger(0)
      val ref = akka.testkit.TestActorRef[ZcBatcher](Props(new ZcBatcher(nodeContext, settings,
        servesCandidates = false, engine.ref, zcTracker, resolver, useTrueProp = false, pools = Some(poolTracker)) {
        override protected def submitArb(planned: ZcMempoolPlan): Try[JsObject] = {
          calls.incrementAndGet()
          answer(planned)
        }
      }))
      try {
        val refused = nodeContext.getClient.execute(ctx =>
          ref.underlyingActor.collateralArbPass(ctx, book, observed, Set.empty, tip + 1))
        (calls.get(), refused)
      } finally system.stop(ref)
    }

    // 1. Repeated rejections are bounded by MaxArbAttemptsPerPass
    val (rejCalls, rejIds) = runWith(_ => Success(Json.obj("outcome" -> EngineBroadcast.Rejected, "reason" -> "busy")))
    rejCalls shouldBe ZcBatcher.MaxArbAttemptsPerPass
    rejIds should have size ZcBatcher.MaxArbAttemptsPerPass

    // 2. TimeoutException stops the pass immediately after the first timed-out attempt
    val (toCalls, toIds) = runWith(_ => Failure(new java.util.concurrent.TimeoutException("engine stalled")))
    toCalls shouldBe 1
    toIds should have size 1

    // 3. Uncertain outcome notes target & pool boxes in tracker.ownArbs and stops the pass immediately
    var uncertainPlan: ZcMempoolPlan = null
    val (uncCalls, uncIds) = runWith { p =>
      uncertainPlan = p
      Success(Json.obj("txId" -> ("cc" * 32), "outcome" -> EngineBroadcast.Uncertain, "reason" -> "lost reply"))
    }
    uncCalls shouldBe 1
    uncIds shouldBe Set(uncertainPlan.targetId)
    zcTracker.ownArbs.boxes should contain allOf(uncertainPlan.targetId, uncertainPlan.poolBox.boxId)
  }

  it should "blame only a poisoned order on leg 1 so the honest offer still fills the next order" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      // Poisoned order offers higher yield (ranks #1 in ZcMatcher) while packing 100 junk tokens so bondOut exceeds 4,096 B
      val junkTokens = tok(SigUsd, 2000L) +: (1 to 100).map(i => tok(f"dd${i}%062x", 1L))
      val poisonedOrder = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, junkTokens,
        Seq(longs(3 * Erg, Erg, 3 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))), Some(h - 10))))
      val honestOrder = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, poisonedOrder, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, honestOrder, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, Some(oracle), offer -> (h - 10), poisonedOrder -> (h - 10), honestOrder -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.failedIds shouldBe Set(poisonedOrder.boxId)
      built.failedIds should not contain offer.boxId
      val matched = built.txs.map(_._1).filter(_.kind == ZcBatchTransactions.KindMatchOrder)
      matched should have size 1
      matched.head.spentIds shouldBe Set(honestOrder.boxId, offer.boxId)
    }
  }

  it should "blame only a poisoned offer with an oversized owner script and leave the honest order unpenalized" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      // A ~3 KB disclosed owner script passes script parsing but makes the MinBox delivery output fall under its
      // per-byte minimum (~1.1M nanoERG > 1M MinBox).
      val padHex = "01" * 3000
      val bloatedOwner = Contract.fromErgoContract(ctx.compileContract(
        org.ergoplatform.appkit.ConstantsBuilder.empty(),
        s"""{ sigmaProp(fromBase16("$padHex")(HEIGHT % 1) == 1) }"""))
      bloatedOwner.ergoTreeHex.length should be > 6000
      val bloatedHash = Hex.toHexString(bloatedOwner.hashedPropBytes)
      val badOffer = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract,
        10 * Erg, Seq.empty,
        Seq(bytes(bloatedHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(),
          ZcUserTransactions.bytesList(Seq.empty), bytes("")),
        Some(h - 10))))
      val honestOrder = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, badOffer, bloatedOwner.ergoTreeHex, h - 10)
      discloseTx(chain.api, honestOrder, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, Some(oracle), badOffer -> (h - 10), honestOrder -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.failedIds shouldBe Set(badOffer.boxId)
      built.failedIds should not contain honestOrder.boxId
    }
  }

  it should "diagnose a script-failing poisoned order on leg 1 so the honest offer is not blacklisted and fills the next order" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 5)
      val offer = ergOfferBox(ctx, h, capital = 10 * Erg)
      // Order with wrong-typed R8 (Long instead of Coll[Byte]): ZcBoxes treats non-byte R8 as guardHash = None, so
      // ZcRules/ZcMatcher/ZcSizing all accept it and rank it #1 (higher yield), while Order's on-chain
      // SELF.R8[Coll[Byte]] fails script reduction on INPUTS(0) during wallet.sign.
      val poisonedOrder = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, Seq(tok(SigUsd, 4000L)),
        Seq(longs(5 * Erg, Erg, 5 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg)),
          ErgoValue.of(42L)),
        Some(h - 10))))
      val honestOrder = ergOrderBox(ctx, h, expiryOffset = 5000L)

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, poisonedOrder, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, honestOrder, borrower.ergoTreeHex, h - 10)

      val book = bookOf(h - 1, Some(oracle), offer -> (h - 10), poisonedOrder -> (h - 10), honestOrder -> (h - 10))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.failedIds shouldBe Set(poisonedOrder.boxId)
      built.failedIds should not contain offer.boxId
      val matched = built.txs.map(_._1).filter(_.kind == ZcBatchTransactions.KindMatchOrder)
      matched should have size 1
      matched.head.spentIds shouldBe Set(honestOrder.boxId, offer.boxId)
    }
  }

  it should "re-validate matchAsk against the live offer continuation after a failed leg 1 so a share ask fills whole" in {
    client.execute { ctx =>
      val h = 1900000
      val oracle = oracleNodeBox(ctx, 500000000L, h - 15)
      val oracleIn = InputUTXO(UTXO(Contract.SIGMA_TRUE, MinBox, Seq(tok(ZcContracts.OracleNft, 1L)),
        Seq(ErgoValue.of(500000000L), ErgoValue.of(1)), Some(h - 15)).toInput(ctx, ErgoId.create(oracle.transactionId), 0).input)
      // Create a real bond + Selective share mint so the ask's origin and request boxes are valid on-chain proofs
      val mintOfferIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract,
        10 * Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 8000L, 5000L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)),
          longs(ZcPricing.ShareCount, Fee, Fee, 10000L, 9000L, 720L, 2160L, 100L),
          ZcUserTransactions.bytesList(Seq("ZCS-Bond-Shares", "{}", "6").map(_.getBytes("UTF-8"))),
          bytes(ZcContracts.script(ZcKind.DutchAuctionMint, Some(ZcMarket.Erg)).treeHash)),
        Some(h - 20)))
      val mintOffer = ZcBoxes.read(nodeOf(mintOfferIn)).get.asInstanceOf[ZcOffer]
      val originOrderIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, Seq(tok(SigUsd, 2000L)),
        Seq(longs(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))), Some(h - 20)))
      val originOrder = ZcBoxes.read(nodeOf(originOrderIn)).get.asInstanceOf[ZcOrder]
      val filled = ZcBatchTransactions.matchOrder(ctx, wallet, mintOffer, mintOfferIn, originOrder, originOrderIn,
        borrower, lender, h.toLong + 50000L, Erg, oracleIn, feeTo, 0L, h - 10)
      val reqBox = ZcBatchTransactions.nodeBoxOf(filled.tx.signed.getOutputsToSpend.get(0), filled.tx.txId, 0)
      val req = ZcBoxes.read(reqBox).get.asInstanceOf[ZcShareRequest]
      val reqIn = InputUTXO(filled.tx.signed.getOutputsToSpend.get(0))
      val bondBox = ZcBatchTransactions.nodeBoxOf(filled.tx.signed.getOutputsToSpend.get(1), filled.tx.txId, 1)
      val bondIn = InputUTXO(filled.tx.signed.getOutputsToSpend.get(1))
      val minted = ZcBatchTransactions.mintShares(ctx, wallet, req, reqIn, lender, Some((bondIn, originOrder.repayment, None)),
        feeTo, 0L, h - 10)
      val shareVaultBox = ZcBatchTransactions.nodeBoxOf(minted.signed.getOutputsToSpend.get(1), minted.txId, 1)

      // Offer has 3 ERG, buys shares, and matches:
      //  leg 1: poisoned oversized order asking 2 ERG (785% APR -> ranks #1, so ZcMatcher plans leg 2 as partial)
      //  leg 2: SaleOrder for all 100M shares at 1.5 ERG (173% APR -> ranks #2)
      // When leg 1 fails with MatchFault(blameSource = true), live curOffer still has 3 ERG and re-validation buys all 100M shares!
      val offer = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract,
        3 * Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 8000L, 5000L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(),
          ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10))))
      val junkTokens = tok(SigUsd, 4000L) +: (1 to 100).map(i => tok(f"de${i}%062x", 1L))
      val poisonedOrder = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, junkTokens,
        Seq(longs(5 * Erg, 2 * Erg, 5 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))), Some(h - 10))))
      val shareAsk = nodeOf(seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract,
        3000000L, Seq(tok(req.shareId, ZcPricing.ShareCount)),
        Seq(bytes(borrowerHash), longs(3 * Erg / 2, ZcPricing.ShareCount, 1L, h.toLong + 5000L)), Some(h - 5))))

      val chain = new ScriptedChain
      val tracker = new ZcTracker(() => chain.api, ExecutionContext.global, ZcConfig.Default)
      val resolver = new ZcResolver(() => chain.api, tracker)
      discloseTx(chain.api, offer, lender.ergoTreeHex, h - 10)
      discloseTx(chain.api, poisonedOrder, borrower.ergoTreeHex, h - 10)
      discloseTx(chain.api, shareAsk, borrower.ergoTreeHex, h - 5)
      when(chain.api.indexedBoxById(originOrder.boxId))
        .thenReturn(Success(Some(IndexedBox(originOrder.box, "", h - 20, 1L, Some(filled.tx.txId), Some(h - 10)))))
      when(chain.api.indexedBoxById(req.shareId))
        .thenReturn(Success(Some(IndexedBox(reqBox, "", h - 10, 1L, Some(minted.txId), Some(h - 10)))))

      val book = bookOf(h - 1, Some(oracle),
        offer -> (h - 10), poisonedOrder -> (h - 10), bondBox -> (h - 10), shareVaultBox -> (h - 10), shareAsk -> (h - 5))
      val built = ZcBatchPlanner.build(ctx, wallet, book, resolver, SigmaFiBatchingConfig.Default,
        signingHeight = h, minerFee = 0L, feeTo = feeTo, slots = 10, allowance = CandidateBudget.Unbounded,
        deadline = None)

      built.failedIds shouldBe Set(poisonedOrder.boxId)
      val askTxs = built.txs.map(_._1).filter(_.kind == ZcBatchTransactions.KindMatchAsk)
      askTxs should have size 1
      askTxs.head.spentIds shouldBe Set(shareAsk.boxId, offer.boxId)
      // Live re-evaluation bought the full 100M shares (not the smaller partial fill planned when leg 1 was assumed to succeed)
      ZcBoxes.payoutTag(ZcBatchTransactions.nodeBoxOf(askTxs.head.signed.getOutputsToSpend.get(0), askTxs.head.txId, 0)) shouldBe
        Some(shareAsk.boxId -> ZcPricing.ShareCount)
    }
  }

  it should "attribute ErgoScript signing failures to the exact failing input in matchOrder and matchAsk" in {
    client.execute { ctx =>
      val h = 1900000
      val oracleIn = seeded(ctx, UTXO(Contract.SIGMA_TRUE, MinBox, Seq(tok(ZcContracts.OracleNft, 1L)),
        Seq(ErgoValue.of(500000000L), ErgoValue.of(1)), Some(h - 5)))
      val goodOfferIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract,
        10 * Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong + 5000L, 1L, 30L, 0L, 0L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(),
          ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10)))
      val goodOffer = ZcBoxes.read(nodeOf(goodOfferIn)).get.asInstanceOf[ZcOffer]

      // Expired offer fails INPUTS(1) while honest order passes INPUTS(0)
      val expiredOfferIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.LendOffer, Some(ZcMarket.Erg)).contract,
        10 * Erg, Seq.empty,
        Seq(bytes(lenderHash), longs(500L, 720L, 262800L, Fee, Reserve, 13000L, h.toLong - 1L, 1L, 30L, 0L, 0L),
          bytes(expr(ZcExpression.CollateralPolicy, ZcMarket.Erg)), longs(),
          ZcUserTransactions.bytesList(Seq.empty), bytes("")), Some(h - 10)))
      val expiredOffer = ZcBoxes.read(nodeOf(expiredOfferIn)).get.asInstanceOf[ZcOffer]

      val goodOrderIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, Seq(tok(SigUsd, 2000L)),
        Seq(longs(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong + 5000L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))), Some(h - 10)))
      val goodOrder = ZcBoxes.read(nodeOf(goodOrderIn)).get.asInstanceOf[ZcOrder]

      // Expired order fails INPUTS(0) while honest offer passes INPUTS(1)
      val expiredOrderIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.Order, Some(ZcMarket.Erg)).contract,
        Fee + MinBox, Seq(tok(SigUsd, 2000L)),
        Seq(longs(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong - 1L, Fee, 0L, 15000L, 60L), tokens(),
          bytes(borrowerHash), bytes(expr(ZcExpression.OracleLiquidation, ZcMarket.Erg))), Some(h - 10)))
      val expiredOrder = ZcBoxes.read(nodeOf(expiredOrderIn)).get.asInstanceOf[ZcOrder]

      val offerFault = intercept[ZcBatchTransactions.MatchFault] {
        ZcBatchTransactions.matchOrder(ctx, wallet, expiredOffer, expiredOfferIn, goodOrder, goodOrderIn,
          borrower, lender, h.toLong + 50000L, Erg, oracleIn, feeTo, 0L, h)
      }
      offerFault.blameOffer shouldBe true
      offerFault.blameSource shouldBe false

      val orderFault = intercept[ZcBatchTransactions.MatchFault] {
        ZcBatchTransactions.matchOrder(ctx, wallet, goodOffer, goodOfferIn, expiredOrder, expiredOrderIn,
          borrower, lender, h.toLong + 50000L, Erg, oracleIn, feeTo, 0L, h)
      }
      orderFault.blameOffer shouldBe false
      orderFault.blameSource shouldBe true

      // Same per-input script diagnosis for matchAsk
      val filled = ZcBatchTransactions.matchOrder(ctx, wallet, goodOffer, goodOfferIn, goodOrder, goodOrderIn,
        borrower, lender, h.toLong + 50000L, Erg, oracleIn, feeTo, 0L, h)
      val bondIn = InputUTXO(filled.tx.signed.getOutputsToSpend.get(1))
      val goodAskIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(goodOrder.boxId, 1L)), Seq(bytes(borrowerHash), longs(Erg, 1L, 1L, h.toLong + 5000L)), Some(h - 5)))
      val goodAsk = ZcBoxes.read(nodeOf(goodAskIn)).get.asInstanceOf[ZcAsk]
      val expiredAskIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(goodOrder.boxId, 1L)), Seq(bytes(borrowerHash), longs(Erg, 1L, 1L, h.toLong - 1L)), Some(h - 5)))
      val expiredAsk = ZcBoxes.read(nodeOf(expiredAskIn)).get.asInstanceOf[ZcAsk]

      val askFault = intercept[ZcBatchTransactions.MatchFault] {
        ZcBatchTransactions.matchAsk(ctx, wallet, filled.nextOffer, filled.nextOfferInput, expiredAsk, expiredAskIn,
          borrower, lender, 1L, Erg, bondIn, oracleIn, goodOrderIn.bytes, None, feeTo, 0L, h)
      }
      askFault.blameOffer shouldBe false
      askFault.blameSource shouldBe true

      val askOfferFault = intercept[ZcBatchTransactions.MatchFault] {
        ZcBatchTransactions.matchAsk(ctx, wallet, expiredOffer, expiredOfferIn, goodAsk, goodAskIn,
          borrower, lender, 1L, Erg, bondIn, oracleIn, goodOrderIn.bytes, None, feeTo, 0L, h)
      }
      askOfferFault.blameOffer shouldBe true
      askOfferFault.blameSource shouldBe false

      // ZcRules.fillRefusal also rejects asks with minFill < 1
      val zeroMinFillIn = seeded(ctx, UTXO(ZcContracts.script(ZcKind.SaleOrder, Some(ZcMarket.Erg)).contract, 3000000L,
        Seq(tok(goodOrder.boxId, 1L)), Seq(bytes(borrowerHash), longs(Erg, 1L, 0L, h.toLong + 5000L)), Some(h - 5)))
      val zeroMinFillAsk = ZcBoxes.read(nodeOf(zeroMinFillIn)).get.asInstanceOf[ZcAsk]
      ZcRules.fillRefusal(zeroMinFillAsk, 1L, h.toLong) shouldBe defined
    }
  }
}
