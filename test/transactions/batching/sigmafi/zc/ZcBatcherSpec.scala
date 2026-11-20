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
import transactions.candidate.BlockTxMessages.{BlockTxsReady, PrepareBlockTxs, RequestBlockTxs, Supersede}
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
                          expiryFee: Long = Fee, ownerHash: String = borrowerHash): NodeBox = {
    val terms = Seq(2 * Erg, Erg, 2 * Erg, h.toLong + 50000L, h.toLong + expiryOffset, expiryFee, 0L, 15000L, 60L)
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

  "ZcTracker.holdsTarget" should "hold stale followed heights below book.base or already in history without rescanning" in {
    val book = ZcBook.seeded(100, "aa" * 32, Seq.empty, Seq.empty, cut = false)
    ZcTracker.holdsTarget(book, Right(100 -> ("aa" * 32))) shouldBe true
    ZcTracker.holdsTarget(book, Right(95 -> ("bb" * 32))) shouldBe true  // below book.base -> no infinite rescan loop
    ZcTracker.holdsTarget(book, Right(101 -> ("cc" * 32))) shouldBe false
  }

  // ─── ZcBatcher actor end-to-end ───────────────────────────────────────────

  "ZcBatcher" should "offer fee-less candidate bundles superseding competing mempool spends" in {
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
}
