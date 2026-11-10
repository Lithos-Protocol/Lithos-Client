package transactions.engine.execution

import akka.actor.ActorRef
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnprocessable}
import api.models.{SFAssetLookupRequest, SFOrderRequest, SFTokenAmount}
import configs.NodeContext
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, IndexedTransaction, MempoolOptions, NodeAsset, NodeBox, NodeSpendingProof, Paging,
  SortDirection, WalletBalances}
import org.ergoplatform.appkit.{ErgoClient, ErgoValue, NetworkType}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, never, times, verify, when}
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import sigmafi.contracts.{FixedMaturity, SigmaFiContracts}
import support.FakeNodeContext
import transactions.batching.sigmafi.{SigmaFiBoxes, SigmaFiFixtures, SigmaFiUserTransactions}
import transactions.engine.wallet.EngineFunding
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

/**
 * What the SigmaFi endpoints refuse, through the checks, which sign nothing and need no wallet. Each refusal is a
 * transaction a contract would reject, or one no client on its shipped settings would ever settle.
 */
class SigmaFiAPIExecutionSpec extends AnyPropSpec with SigmaFiFixtures with MockitoSugar with org.scalatest.LoneElement {

  private val client: ErgoClient = FakeNodeContext.offlineClient()
  private val wallet = NodeWallet(FakeNodeContext.proverWith(client, 1))
  private val tip = client.execute(_.getHeight)

  private class Fixture(boxes: NodeBox*) {
    val api: NodeApi = mock[NodeApi]
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(boxes.filter(b => wanted.contains(b.boxId)))
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.indexedTransactionById(anyString())).thenReturn(Success(None))
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.boxById(anyString())).thenReturn(Success(None))
    when(api.walletBalances()).thenReturn(Success(WalletBalances(tip, 0L, Seq.empty)))
    private val context = new NodeContext {
      override def getNetwork: NetworkType = NetworkType.MAINNET
      override def getExplorerURL: String = ""
      override def getClient: ErgoClient = client
      override def getNodeWallet: NodeWallet = wallet
      override def getNodeKey: String = "test-api-key"
      override def getNodeApi: NodeApi = api
      override def getNodeUrl: String = "http://127.0.0.1:9052/"
    }
    // Checks never reach the wallet, so the funding side is never asked
    val execution = new SigmaFiAPIExecution(context, EngineFunding(ActorRef.noSender, 1.second, ExecutionContext.global))

    /** An execution that sends, observing `snapshot` as the mempool and funding through `funding`. */
    def sending(snapshot: state.synchronization.CompleteMempool.Snapshot, funding: EngineFunding): SigmaFiAPIExecution =
      new SigmaFiAPIExecution(context, funding) {
        override protected def mempoolSnapshot(): state.synchronization.CompleteMempool.Snapshot = snapshot
      }
  }

  private val request = SFOrderRequest("5000000000", None, Some("1000000000"), None, "1100000000",
    Some(tip + 100), None, None, None)

  property("create: quotes a valid order, paying its collateral and the fee") {
    val quote = new Fixture().execution.createOrder(request, send = false).left.get
    quote.paysNanoErgs shouldBe (5000000000L + SigmaFiAPIExecution.TxFee).toString
    quote.terms.get.batchingFee shouldBe "0"
    quote.terms.get.liquidation shouldBe Some("MaturityLiquidation")
  }

  property("create: refuses a fee under the shipped floor, an unknown script, or a maturity too close") {
    val e = new Fixture().execution
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(batchingFee = Some("49999999")), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(liquidation = Some("DutchAuction")), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(maturityHeight = Some(tip + 2)), send = false)
  }

  property("create: refuses an on-close duration under 30, both schedules at once, or a lent token as collateral") {
    val e = new Fixture().execution
    an[LithosBadRequest] should be thrownBy
      e.createOrder(request.copy(maturityHeight = None, durationBlocks = Some(29)), send = false)
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(durationBlocks = Some(720)), send = false)
    an[LithosBadRequest] should be thrownBy
      e.createOrder(request.copy(maturityHeight = None, durationBlocks = Some(Long.MaxValue)), send = false)
    val token = SFTokenAmount(tokenA.toString, "5")
    an[LithosBadRequest] should be thrownBy e.createOrder(request.copy(principalNanoErgs = None,
      principalToken = Some(token), collateralTokens = Some(Seq(token))), send = false)
  }

  property("fill: refuses a token order whose bond repays in another token, and this wallet's own order") {
    client.execute { ctx =>
      val stranger = contractOf(miner(ctx))
      val mismatched = orderTerms(stranger, FixedMaturity(tip + 100), principalErg = MinBox,
        principalTokens = Seq(tokenA -> 500L)).copy(bondHash = SigmaFiContracts.tokenBond(tokenB).hashedPropBytes)
      val bad = nodeBox(ctx, UTXO(SigmaFiContracts.order(mismatched), 5 * Erg), 0)
      val mine = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(wallet.contract, FixedMaturity(tip + 100))), 5 * Erg), 1)
      val e = new Fixture(bad, mine).execution
      the[LithosUnprocessable] thrownBy e.fillOrder(bad.boxId, send = false) should have message
        "the order's bond does not match its loan, so its bond could not be repaid as asked"
      an[LithosUnprocessable] should be thrownBy e.fillOrder(mine.boxId, send = false)
    }
  }

  property("cancel: a box this wallet did not make is not found as its own") {
    client.execute { ctx =>
      val theirs = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(contractOf(miner(ctx)), FixedMaturity(tip + 100))),
        5 * Erg), 2)
      an[LithosNotFound] should be thrownBy new Fixture(theirs).execution.cancelOrder(theirs.boxId, send = false)
    }
  }

  property("repay and liquidate: refuse a matured bond's repayment, and a liquidation before maturity") {
    client.execute { ctx =>
      val matured = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 1), 3)
      val early = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 50), 4)
      val e = new Fixture(matured, early).execution
      an[LithosUnprocessable] should be thrownBy e.repay(matured.boxId, send = false)
      an[LithosUnprocessable] should be thrownBy e.liquidate(early.boxId, send = false)
      e.liquidate(matured.boxId, send = false).left.get.receivesNanoErgs shouldBe
        (50000000L - SigmaFiAPIExecution.TxFee).toString
    }
  }

  property("claim: refuses an asset the vault holds nothing for") {
    an[LithosUnprocessable] should be thrownBy new Fixture().execution.claim(assetId.toString, send = false)
  }

  /** Scans of each tree in `trees` answer its boxes on their first page, and every other tree answers nothing. */
  private def treesHold(f: Fixture, trees: Map[String, Seq[NodeBox]]): Unit =
    vaultPages(f, trees.getOrElse(SigmaFiContracts.vault.ergoTreeHex, Seq.empty), trees - SigmaFiContracts.vault.ergoTreeHex)

  /** The vault scan pages through `vault` a hundred at a time; other trees in `trees` answer on their first page. */
  private def vaultPages(f: Fixture, vault: Seq[NodeBox], trees: Map[String, Seq[NodeBox]] = Map.empty): Unit =
    doAnswer { inv: org.mockito.invocation.InvocationOnMock =>
      val offset = inv.getArgument[Paging](1).offset
      val tree = inv.getArgument[String](0)
      val boxes = if (tree.equalsIgnoreCase(SigmaFiContracts.vault.ergoTreeHex)) vault.slice(offset, offset + 100)
        else if (offset > 0) Seq.empty else trees.getOrElse(tree, Seq.empty)
      Success(boxes.map(b => IndexedBox(b, "", tip, 1L)))
    }.when(f.api).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])

  private def walletHolds(f: Fixture, assets: org.ergoplatform.sdk.ErgoId*): Unit =
    doAnswer((_: org.mockito.invocation.InvocationOnMock) =>
      Success(WalletBalances(tip, 10 * Erg, assets.map(a => NodeAsset(a.toString, 1L)))))
      .when(f.api).walletBalances()

  /** The unspent boxes holding each token, read again on every call. */
  private def tokenHeldBy(f: Fixture, holders: () => Seq[NodeBox]): Unit =
    doAnswer { inv: org.mockito.invocation.InvocationOnMock =>
      val id = inv.getArgument[String](0)
      Success(holders().filter(_.assets.exists(_.tokenId == id)).map(b => IndexedBox(b, "", tip, 1L)))
    }.when(f.api).unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])

  /**
   * A bond asset minted to this wallet by a signed fill and settled by a signed repayment, as the node's index
   * reports both. `vault` is the repayment's settlement output; `bond` the bond it spent.
   */
  private case class Settled(asset: org.ergoplatform.sdk.ErgoId, unit: NodeBox, bond: NodeBox, vault: NodeBox,
                             boxes: Map[String, IndexedBox], txs: Map[String, IndexedTransaction])

  private def settled(ctx: org.ergoplatform.appkit.BlockchainContext, i: Int, orderTx: String): Settled = {
    def indexed(box: NodeBox) = IndexedBox(box, "", tip, 1L)
    def outputs(tx: org.ergoplatform.appkit.SignedTransaction) = {
      val id = transactions.candidate.BlockTxMessages.CandidateTx.txId(tx)
      tx.getOutputsToSpend.asScala.zipWithIndex.map { case (b, n) => indexed(nodeBox(ctx, InputUTXO(b).toUTXO, n, id)) }.toVector
    }
    def record(id: String, inputs: Seq[IndexedBox], outs: Seq[IndexedBox]) =
      IndexedTransaction(id, inputs, Seq.empty, outs, tip, 1, "block", 1700000000000L, 0, 1L, 1000)
    val borrower = contractOf(miner(ctx))
    val orderBox = nodeBox(ctx, UTXO(SigmaFiContracts.order(orderTerms(borrower, FixedMaturity(tip + 100))), 5 * Erg,
      Seq(Token(tokenB, i + 20L))), i, orderTx)
    val order = SigmaFiBoxes.order(orderBox).get
    val fill = wallet.sign(SigmaFiUserTransactions.fillOrder(ctx, wallet, order, SigmaFiBoxes.input(orderBox, ctx).get,
      None, borrower).build(Seq(UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, (i + 300).toShort))))
    val fillId = transactions.candidate.BlockTxMessages.CandidateTx.txId(fill)
    val fillOuts = outputs(fill)
    val bond = SigmaFiBoxes.bond(fillOuts(1).box).get
    val repay = wallet.sign(SigmaFiUserTransactions.repay(ctx, wallet, bond, SigmaFiBoxes.input(bond.box, ctx).get, borrower)
      .build(Seq(UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, (i + 400).toShort))))
    val repayId = transactions.candidate.BlockTxMessages.CandidateTx.txId(repay)
    val genesis = indexed(orderBox).copy(spentTransactionId = Some(fillId), spendingHeight = Some(tip))
    val spentBond = indexed(bond.box).copy(spentTransactionId = Some(repayId), spendingHeight = Some(tip),
      spendingProof = Some(NodeSpendingProof("", Map("1" -> ErgoValue.of(0).toHex, "2" -> ErgoValue.of(1).toHex))))
    val repayOuts = outputs(repay)
    Settled(bond.asset, fillOuts(0).box, bond.box, repayOuts(0).box,
      Map(genesis.boxId -> genesis, spentBond.boxId -> spentBond),
      Map(fillId -> record(fillId, Seq(genesis), fillOuts), repayId -> record(repayId, Seq(spentBond), repayOuts)))
  }

  /** The index answers every settled asset's history, and the UTXO set holds each settlement output. */
  private def indexHolds(f: Fixture, all: Settled*): Unit = {
    val boxes = all.flatMap(_.boxes).toMap
    val txs = all.flatMap(_.txs).toMap
    doAnswer((inv: org.mockito.invocation.InvocationOnMock) => Success(boxes.get(inv.getArgument[String](0))))
      .when(f.api).indexedBoxById(anyString())
    doAnswer((inv: org.mockito.invocation.InvocationOnMock) => Success(txs.get(inv.getArgument[String](0))))
      .when(f.api).indexedTransactionById(anyString())
    doAnswer((inv: org.mockito.invocation.InvocationOnMock) =>
      Success(all.map(_.vault).find(_.boxId == inv.getArgument[String](0))))
      .when(f.api).boxById(anyString())
  }

  private def gift(ctx: org.ergoplatform.appkit.BlockchainContext, asset: org.ergoplatform.sdk.ErgoId, n: Int,
                   value: Long = MinBox): NodeBox =
    nodeBox(ctx, vaultUTXO(org.ergoplatform.sdk.ErgoId.create("dd" * 32), asset, value), n, "d7" * 32)

  property("claim: refuses while a bond still holds the asset's other unit, then takes the settlement once it is gone") {
    client.execute { ctx =>
      val s = settled(ctx, 1, "e1" * 32)
      val stranger = gift(ctx, s.asset, 1)
      val f = new Fixture()
      indexHolds(f, s)
      treesHold(f, Map(SigmaFiContracts.vault.ergoTreeHex -> Seq(stranger, s.vault)))
      walletHolds(f, s.asset)
      @volatile var holders = Seq(s.bond, s.unit)
      tokenHeldBy(f, () => holders)
      val refused = the[LithosUnprocessable] thrownBy f.execution.claim(s.asset.toString, send = false)
      refused.getMessage should include(s.bond.boxId)
      holders = Seq(s.unit)
      val quote = f.execution.claim(s.asset.toString, send = false).left.get
      quote.receivesNanoErgs shouldBe (s.vault.value + stranger.value).toString
      quote.notes.head should include(s.vault.boxId)
      quote.warnings shouldBe empty
    }
  }

  property("claim: takes the settlement output found by id when the vault scan never reaches it") {
    client.execute { ctx =>
      val s = settled(ctx, 2, "e2" * 32)
      // A vault crowded past the scan's cap with boxes that read as nothing; the settlement is beyond them
      val crowd = (0 until 2100).map(n => NodeBox(f"$n%064x", "ab" * 32, MinBox, 0, tip, SigmaFiContracts.vault.ergoTreeHex))
      val f = new Fixture()
      indexHolds(f, s)
      vaultPages(f, crowd :+ s.vault)
      walletHolds(f, s.asset)
      tokenHeldBy(f, () => Seq(s.unit))
      val quote = f.execution.claim(s.asset.toString, send = false).left.get
      quote.receivesNanoErgs shouldBe s.vault.value.toString
      quote.warnings.loneElement should include("more than one scan reads")
    }
  }

  property("claim: a thousand dust boxes naming the asset neither block nor swell it, and what is left is said") {
    client.execute { ctx =>
      val s = settled(ctx, 3, "e3" * 32)
      val dust = (0 until 1000).map(n => gift(ctx, s.asset, n + 10, MinBox + n))
      val f = new Fixture()
      indexHolds(f, s)
      vaultPages(f, dust :+ s.vault)
      walletHolds(f, s.asset)
      tokenHeldBy(f, () => Seq(s.unit))
      val quote = f.execution.claim(s.asset.toString, send = false).left.get
      val largest = dust.map(_.value).sorted.reverse.take(SigmaFiAPIExecution.MaxClaimExtras)
      quote.receivesNanoErgs shouldBe (s.vault.value + largest.sum).toString
      quote.notes.head should include(s"${SigmaFiAPIExecution.MaxClaimExtras} other vault box(es)")
      quote.warnings.loneElement should startWith(s"${1000 - SigmaFiAPIExecution.MaxClaimExtras} more vault box(es)")
      quote.warnings.loneElement should include("locks them for good")
      withClue("the portfolio shows the boxes the claim takes, not the thousand: ") {
        val holding = f.execution.getPortfolio.lending.loneElement
        holding.claimable shouldBe true
        holding.vault.map(_.boxId) shouldBe s.vault.boxId +: dust.sortBy(-_.value).take(SigmaFiAPIExecution.MaxClaimExtras).map(_.boxId)
      }
    }
  }

  property("claim: refuses when the settlement cannot be established, rather than burn on a guess") {
    client.execute { ctx =>
      val s = settled(ctx, 4, "e4" * 32)
      val unverified = org.ergoplatform.sdk.ErgoId.create("c4" * 32)
      val f = new Fixture()
      treesHold(f, Map(SigmaFiContracts.vault.ergoTreeHex -> Seq(s.vault, gift(ctx, unverified, 2))))
      walletHolds(f, s.asset, unverified)
      tokenHeldBy(f, () => Seq(s.unit))
      withClue("an asset no verified order minted: ") {
        val refused = the[LithosUnprocessable] thrownBy f.execution.claim(unverified.toString, send = false)
        refused.getMessage should include("not verified")
      }
      withClue("a verified asset whose settling transaction the index has not returned: ") {
        indexHolds(f, s.copy(txs = s.txs.filterNot(_._2.inputs.exists(_.boxId == s.bond.boxId))))
        val refused = the[LithosUnprocessable] thrownBy f.execution.claim(s.asset.toString, send = false)
        refused.getMessage should include("cannot be established")
      }
      withClue("a settlement output the UTXO set no longer holds: ") {
        indexHolds(f, s)
        doAnswer((_: org.mockito.invocation.InvocationOnMock) => Success(None)).when(f.api).boxById(anyString())
        val refused = the[LithosUnprocessable] thrownBy f.execution.claim(s.asset.toString, send = false)
        refused.getMessage should include("not unspent on chain")
      }
    }
  }

  property("claim: a pending payment into the vault no longer blocks the send") {
    client.execute { ctx =>
      val s = settled(ctx, 5, "e5" * 32)
      val f = new Fixture()
      indexHolds(f, s)
      treesHold(f, Map(SigmaFiContracts.vault.ergoTreeHex -> Seq(s.vault)))
      walletHolds(f, s.asset)
      tokenHeldBy(f, () => Seq(s.unit))
      val pending = node.model.NodeTransaction("77" * 32, Seq.empty, Seq.empty, Seq(gift(ctx, s.asset, 3)))
      val snapshot = state.synchronization.CompleteMempool.Snapshot("cc" * 32, Set(pending.id), Set.empty,
        System.nanoTime(), transactions = Vector(state.synchronization.CompleteMempool.MempoolTx(pending.id, pending, 100)))
      object Funded extends RuntimeException("reached the wallet funding")
      val funding = new EngineFunding(ActorRef.noSender, 1.second, ExecutionContext.global) {
        override def reserveAvoiding(value: Long, tokens: Seq[Token], avoidTokens: Set[String]) = throw Funded
      }
      // It reaches the wallet funding, which is past every refusal a claim makes
      the[RuntimeException] thrownBy f.sending(snapshot, funding).claim(s.asset.toString, send = true) shouldBe Funded
    }
  }

  property("portfolio: each holding is claimable exactly when a claim of it goes through") {
    client.execute { ctx =>
      val repaid = settled(ctx, 6, "e6" * 32)
      val outOfScan = settled(ctx, 7, "e7" * 32)
      val unverified = org.ergoplatform.sdk.ErgoId.create("c6" * 32)
      // A zero repayment is a term the order contract allows and this client's bond reader refuses
      val live = Seq(2 * Erg, 0L).map(r => nodeBox(ctx, bondUTXO(None, contractOf(miner(ctx)), tip + 50, repayment = r,
        asset = org.ergoplatform.sdk.ErgoId.create(if (r > 0) "c7" * 32 else "c8" * 32)), 12 + r.signum))
      val liveUnits = live.map(b => nodeBox(ctx, UTXO(wallet.contract, MinBox, Seq(Token(org.ergoplatform.sdk.ErgoId.create(
        b.assets.head.tokenId), 1L))), 20 + live.indexOf(b)))
      val gifts = Seq(gift(ctx, repaid.asset, 4), gift(ctx, unverified, 5)) ++
        live.map(b => gift(ctx, org.ergoplatform.sdk.ErgoId.create(b.assets.head.tokenId), 6 + live.indexOf(b)))
      val crowd = (0 until 2000).map(n => NodeBox(f"${n + 5000}%064x", "ab" * 32, MinBox, 0, tip, SigmaFiContracts.vault.ergoTreeHex))
      val f = new Fixture()
      indexHolds(f, repaid, outOfScan)
      vaultPages(f, gifts ++ Seq(repaid.vault) ++ crowd :+ outOfScan.vault, Map(SigmaFiContracts.ergBond.ergoTreeHex -> live))
      val assets = Seq(repaid.asset, outOfScan.asset, unverified) ++ live.map(b => org.ergoplatform.sdk.ErgoId.create(b.assets.head.tokenId))
      walletHolds(f, assets: _*)
      tokenHeldBy(f, () => Seq(repaid.unit, outOfScan.unit) ++ live ++ liveUnits)
      val holdings = f.execution.getPortfolio.lending.map(h => h.assetId -> h).toMap
      holdings.keySet shouldBe assets.map(_.toString).toSet
      assets.foreach { a =>
        val claims = scala.util.Try(f.execution.claim(a.toString, send = false)).isSuccess
        withClue(s"$a: ") { holdings(a.toString).claimable shouldBe claims }
      }
      Seq(repaid, outOfScan).foreach(s => holdings(s.asset.toString).claimable shouldBe true)
      holdings(outOfScan.asset.toString).vault.map(_.boxId) shouldBe Seq(outOfScan.vault.boxId)
    }
  }

  property("repay: warns while two blocks or fewer can carry the repayment, in the quote and the send's result") {
    client.execute { ctx =>
      // The next block is tip + 1, and a repayment counts only in a block below maturity
      val bonds = (1 to 4).map(left => left -> nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 1 + left), 30 + left))
      val f = new Fixture(bonds.map(_._2): _*)
      // Spelled out rather than taken from the warning's own function, so a moved threshold fails here
      def late(left: Int): Seq[String] = Seq(s"Only $left block(s) can still carry this repayment: it counts only " +
        s"in a block below height ${tip + 1 + left}. If it is not mined by then, it fails and the bond can be liquidated.")
      bonds.foreach { case (left, box) =>
        withClue(s"$left block(s) left: ") {
          f.execution.repay(box.boxId, send = false).left.get.warnings shouldBe (if (left <= 2) late(left) else Seq.empty)
        }
      }

      // Sent through the real broadcast boundary, an engine stand-in answering its observation and pin
      val system = akka.actor.ActorSystem("sigmafi-repay-send")
      try {
        val anchor = "cc" * 32
        val engine = akka.testkit.TestProbe()(system)
        engine.setAutoPilot(new akka.testkit.TestActor.AutoPilot {
          def run(sender: ActorRef, msg: Any): akka.testkit.TestActor.AutoPilot = {
            msg match {
              case state.synchronization.CompleteMempool.Refresh =>
                sender ! state.synchronization.CompleteMempool.Observation(1L, Some(state.synchronization.CompleteMempool
                  .Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None)
              case _: transactions.engine.wallet.EngineWalletMessages.PinEngineInputs => sender ! true
              case _ => ()
            }
            this
          }
        })
        when(f.api.info()).thenReturn(Success(support.ChainFixtures.infoAt(tip).copy(bestFullHeaderId = Some(anchor))))
        when(f.api.sendTransaction(anyString())).thenAnswer { inv =>
          Success("\"" + """"id"\s*:\s*"([0-9a-f]{64})"""".r.findFirstMatchIn(inv.getArgument[String](0)).get.group(1) + "\"")
        }
        var funded = 0
        val funding = new EngineFunding(engine.ref, 5.seconds, ExecutionContext.global) {
          override def reserveAvoiding(value: Long, tokens: Seq[Token], avoidTokens: Set[String]) = {
            funded += 1
            new transactions.engine.wallet.FundingAllocation(s"repay-$funded", Seq(UTXO(wallet.contract, 10 * Erg)
              .toInput(ctx, org.ergoplatform.sdk.ErgoId.create("f1" * 32), funded.toShort)), this)
          }
        }
        val snapshot = state.synchronization.CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())
        bonds.filter(b => b._1 == 1 || b._1 == 3).foreach { case (left, box) =>
          withClue(s"sent with $left block(s) left: ") {
            val sent = f.sending(snapshot, funding).repay(box.boxId, send = true).right.get
            sent.outcome shouldBe "accepted"
            sent.warnings shouldBe (if (left == 1) late(1) else Seq.empty)
          }
        }
      } finally akka.testkit.TestKit.shutdownActorSystem(system)
    }
  }

  property("repayment quotes return collateral without counting the bond singleton") {
    client.execute { ctx =>
      val box = nodeBox(ctx, bondUTXO(None, wallet.contract, tip + 100, collateral = Seq(Token(tokenB, 7L))))
      val quote = new Fixture(box).execution.repay(box.boxId, send = false).left.get
      quote.receivesTokens shouldBe Seq(SFTokenAmount(tokenB.toString, "7"))
      quote.terms.get.collateralTokens shouldBe quote.receivesTokens
      quote.receivesNanoErgs shouldBe (box.value - 50000000L).toString
      quote.terms.get.principalNanoErgs shouldBe Some(Erg.toString)
    }
  }

  property("fill quotes include the deposit required by a large issuance description") {
    client.execute { ctx =>
      val borrower = contractOf(miner(ctx))
      val collateral = (1 to 32).map(i => Token(org.ergoplatform.sdk.ErgoId.create(f"${i + 1000}%064x"), i.toLong))
      val terms = orderTerms(borrower, FixedMaturity(tip + 100))
      val box = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg, collateral,
        Seq(SigmaFiBoxes.hintValue(borrower.ergoTree))), 40)
      val order = SigmaFiBoxes.order(box).get
      val deposit = SigmaFiUserTransactions.lenderUnit(ctx, wallet, order, None, tip + 100).value
      val quote = new Fixture(box).execution.fillOrder(box.boxId, send = false).left.get
      deposit should be > MinBox
      quote.receivesNanoErgs shouldBe deposit.toString
      quote.paysNanoErgs shouldBe (terms.principalErg + deposit + SigmaFiAPIExecution.TxFee + 50000000L).toString
    }
  }

  property("fill reserve overrides are lender-funded, support zero with a warning, and reject negative amounts") {
    client.execute { ctx =>
      val borrower = contractOf(miner(ctx))
      val terms = orderTerms(borrower, FixedMaturity(tip + 100))
      val box = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), MinBox, Seq.empty,
        Seq(SigmaFiBoxes.hintValue(borrower.ergoTree))), 45)
      val e = new Fixture(box).execution
      val standard = e.fillOrder(box.boxId, send = false).left.get
      val raised = e.fillOrder(box.boxId, send = false, Some("75000000")).left.get
      raised.terms.get.batchingFee shouldBe "75000000"
      BigInt(raised.paysNanoErgs) - BigInt(standard.paysNanoErgs) shouldBe 25000000L
      val zero = e.fillOrder(box.boxId, send = false, Some("0")).left.get
      zero.terms.get.batchingFee shouldBe "0"
      zero.warnings should not be empty
      an[LithosBadRequest] should be thrownBy e.fillOrder(box.boxId, send = false, Some("-1"))
      an[LithosBadRequest] should be thrownBy e.fillOrder(box.boxId, send = false, Some(Long.MaxValue.toString))
    }
  }

  property("asset lookup authenticates the complete two-unit fill and retries an incomplete mint index") {
    client.execute { ctx =>
      val borrower = contractOf(miner(ctx))
      Seq("valid", "supply", "terms", "input", "principal", "delayed").zipWithIndex.foreach { case (variant, i) =>
        val terms = orderTerms(borrower, FixedMaturity(tip + 100))
        val box = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg, Seq(Token(tokenB, 7L))), i, "e9" * 32)
        val order = SigmaFiBoxes.order(box).get
        val funding = UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, (i + 100).toShort)
        val fill = wallet.sign(SigmaFiUserTransactions.fillOrder(ctx, wallet, order,
          SigmaFiBoxes.input(box, ctx).get, None, borrower).build(Seq(funding)))
        val txId = transactions.candidate.BlockTxMessages.CandidateTx.txId(fill)
        def indexed(b: NodeBox) = IndexedBox(b, "", tip + 1, 1L)
        val genesis = indexed(box).copy(spentTransactionId = Some(txId), spendingHeight = Some(tip + 1))
        val outputs = fill.getOutputsToSpend.asScala.zipWithIndex.map { case (b, n) =>
          indexed(nodeBox(ctx, InputUTXO(b).toUTXO, n, txId))
        }.toVector
        val changed = variant match {
          case "supply" => outputs.updated(0, outputs(0).copy(box = outputs(0).box.copy(
            assets = outputs(0).assets.map(_.copy(amount = 2L)))))
          case "terms" => outputs.updated(1, indexed(nodeBox(ctx, InputUTXO(fill.getOutputsToSpend.get(1)).toUTXO
            .withReg(2, org.ergoplatform.appkit.ErgoValue.of(Array(2 * Erg + 1, 50000000L))), 1, txId)))
          case "principal" => outputs.updated(2, outputs(2).copy(box = outputs(2).box.copy(value = MinBox)))
          case _ => outputs
        }
        val inputs = if (variant == "input") Seq(indexed(box.copy(boxId = "ff" * 32))) else Seq(genesis)
        val mint = IndexedTransaction(txId, inputs, Seq.empty, changed, tip + 1, 1, "block", 0L, 0, 1L, 1000)
        val f = new Fixture()
        when(f.api.indexedBoxById(box.boxId)).thenReturn(Success(Some(genesis)))
        when(f.api.indexedTransactionById(txId)).thenReturn(Success(Some(mint)))
        def lookup = f.execution.lookupAssets(SFAssetLookupRequest(Seq(box.boxId))).head
        if (variant == "delayed") {
          when(f.api.indexedTransactionById(txId)).thenReturn(Success(None))
          lookup.verified shouldBe false
          when(f.api.indexedTransactionById(txId)).thenReturn(Success(Some(mint)))
        }
        val result = lookup
        val valid = variant == "valid" || variant == "delayed"
        result.verified shouldBe valid
        result.bondAsset shouldBe valid
        verify(f.api, never()).unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
        verify(f.api, never()).unspentBoxesByErgoTree(org.mockito.ArgumentMatchers.eq(SigmaFiContracts.ergBond.ergoTreeHex),
          any[Paging], any[SortDirection], any[MempoolOptions])
      }
    }
  }

  property("asset lookup shares one history read for a batch repayment and refreshes it on the next request") {
    client.execute { ctx =>
      def indexed(box: NodeBox) = IndexedBox(box, "", tip, 1L)
      def outputs(tx: org.ergoplatform.appkit.SignedTransaction) = {
        val id = transactions.candidate.BlockTxMessages.CandidateTx.txId(tx)
        tx.getOutputsToSpend.asScala.zipWithIndex.map { case (b, n) =>
          indexed(nodeBox(ctx, InputUTXO(b).toUTXO, n, id))
        }.toVector
      }
      def transaction(id: String, inputs: Seq[IndexedBox], outs: Seq[IndexedBox]) =
        IndexedTransaction(id, inputs, Seq.empty, outs, tip, 1, "block", 1700000000000L, 0, 1L, 1000)
      val borrower = contractOf(miner(ctx))
      val mints = (0 to 1).map { i =>
        val terms = orderTerms(borrower, FixedMaturity(tip + 100))
        val orderBox = nodeBox(ctx, UTXO(SigmaFiContracts.order(terms), 5 * Erg,
          Seq(Token(tokenB, i + 7L))), i, "d2" * 32)
        val order = SigmaFiBoxes.order(orderBox).get
        val funding = UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, (i + 200).toShort)
        val fill = wallet.sign(SigmaFiUserTransactions.fillOrder(ctx, wallet, order,
          SigmaFiBoxes.input(orderBox, ctx).get, None, borrower).build(Seq(funding)))
        val outs = outputs(fill)
        val id = transactions.candidate.BlockTxMessages.CandidateTx.txId(fill)
        val genesis = indexed(orderBox).copy(spentTransactionId = Some(id), spendingHeight = Some(tip))
        (genesis, transaction(id, Seq(genesis), outs), SigmaFiBoxes.bond(outs(1).box).get)
      }
      val bonds = mints.map(_._3)
      val inputs = bonds.zipWithIndex.map { case (b, i) => SigmaFiBoxes.input(b.box, ctx).get
        .withCtxVar(1.toByte, ErgoValue.of(i * 2)).withCtxVar(2.toByte, ErgoValue.of(i * 2 + 1)) }
      val payouts = bonds.flatMap { b => Seq(
        UTXO(SigmaFiContracts.vault, b.repayment + b.batchingFee, Seq.empty, Seq(
          ErgoValue.of(org.ergoplatform.sdk.ErgoId.create(b.boxId).getBytes), ErgoValue.of(b.asset.getBytes), ErgoValue.of(0.toByte))),
        UTXO(borrower, b.collateralErg, b.collateral.map(a => Token(org.ergoplatform.sdk.ErgoId.create(a.tokenId), a.amount)),
          Seq(ErgoValue.of(org.ergoplatform.sdk.ErgoId.create(b.boxId).getBytes)))) }
      val funding = UTXO(wallet.contract, 10 * Erg).toInput(ctx, assetId, 250)
      val paid = wallet.sign(TxBuilder(ctx).setInputs(inputs :+ funding: _*).setOutputs(payouts: _*)
        .buildTx(SigmaFiAPIExecution.TxFee, wallet.p2pk, bonds.map(b => Token(b.asset, 1L))))
      val closeId = transactions.candidate.BlockTxMessages.CandidateTx.txId(paid)
      val closed = bonds.zipWithIndex.map { case (b, i) => indexed(b.box).copy(spentTransactionId = Some(closeId),
        spendingHeight = Some(tip), spendingProof = Some(NodeSpendingProof("", Map(
          "1" -> ErgoValue.of(i * 2).toHex, "2" -> ErgoValue.of(i * 2 + 1).toHex)))) }
      val history = mints.map(m => m._2.id -> m._2).toMap + (closeId -> transaction(closeId, closed, outputs(paid)))
      val boxes = (mints.map(_._1) ++ closed).map(b => b.boxId -> b).toMap
      val f = new Fixture()
      when(f.api.indexedBoxById(anyString())).thenAnswer(i => Success(boxes.get(i.getArgument[String](0))))
      when(f.api.indexedTransactionById(anyString())).thenAnswer(i => Success(history.get(i.getArgument[String](0))))
      val request = SFAssetLookupRequest(bonds.map(_.asset.toString))
      val result = f.execution.lookupAssets(request)
      result.map(_.status) shouldBe Seq("Repaid", "Repaid")
      result.foreach { asset => asset.verified shouldBe true; asset.bond shouldBe None }
      verify(f.api, times(1)).indexedTransactionById(closeId)
      f.execution.lookupAssets(request).map(_.status) shouldBe Seq("Repaid", "Repaid")
      verify(f.api, times(2)).indexedTransactionById(closeId)
    }
  }
}
