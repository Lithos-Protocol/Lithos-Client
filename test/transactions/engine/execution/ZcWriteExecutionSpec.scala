package transactions.engine.execution

import api.LithosApiErrors.{LithosStateChanged, LithosUnprocessable}
import api.zc.ZcDrafts
import configs.ZcConfig
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.{JsObject, Json}
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.Batcher
import transactions.batching.sigmafi.zc._
import transactions.batching.tracking.ScriptedChain
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.engine.wallet.EngineFunding
import work.lithos.mutations.{Token, UTXO}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success

/**
 * The write layer over a tracker seeded with real boxes: a check quotes what this wallet pays and receives, a refusal
 * comes back as a quote listing it and as 422 on a send, and a send refuses a box a fresh mempool shows spent, or a
 * quote that moved past the body's limits, before anything is reserved or signed.
 */
class ZcWriteExecutionSpec extends AnyFlatSpec with Matchers with Eventually {

  import transactions.batching.sigmafi.zc.ZcFixtures.{Erg => _, _}

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  private val Erg = 1000000000L

  private final case class World(exec: ZcWriteExecution, order: NodeBox, bond: NodeBox, asset: String)

  private def nodeOf(signed: SignedTransaction, i: Int): NodeBox = {
    val b = signed.getOutputsToSpend.get(i)
    NodeBox(b.getId.toString, CandidateTx.txId(signed), b.getValue, i, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
      b.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
  }

  /** A posted order of this wallet's and a live maturity-rule bond, both real boxes the tracker holds. */
  private def world(orderSpentInMempool: Boolean = false): World = {
    val c = new ScriptedChain
    val (ctx0, _, wallet) = FakeNodeContext(c.api)
    val ownHash = Hex.toHexString(wallet.contract.hashedPropBytes)
    val (order, bond, asset) = ctx0.getClient.execute { ctx: BlockchainContext =>
      val h = ctx.getHeight
      val body = Json.obj("market" -> "ERG", "pricing" -> "fixed", "principal" -> "1000000000", "repayment" -> "1100000000",
        "timing" -> Json.obj("mode" -> "fixed", "maturity" -> (h + 50000), "exact" -> true),
        "collateral" -> Json.obj("nanoErg" -> "3000000000"), "rule" -> Json.obj("kind" -> "maturity"))
      val draft = ZcDrafts.orderOf(body, h.toLong)
      val funding = UTXO(wallet.contract, 4 * Erg).toInput(ctx, ErgoId.create("f0" * 32), 0)
      val posted = Batcher.withNodeMinimums(ctx, wallet.sign(ZcUserTransactions.post(ctx, wallet,
        ZcUserTransactions.orderBox(draft, ownHash, h), wallet.contract).build(Seq(funding))))
      val asset = "d1" * 32
      val terms = Seq(2 * Erg, Erg, 2 * Erg, h + 1000L, h.toLong, 5000000L, 0L)
      val live = UTXO(ZcContracts.script(ZcKind.Bond, Some(ZcMarket.Erg)).contract, 3 * Erg, Seq(Token(ErgoId.create(asset), 2L)),
        Seq(bytes(asset), bytes(ownHash), longs(terms: _*), tokens(), bytes(expr(ZcExpression.MaturityLiquidation)),
          longs(50000000L, h - 100L, h + 1000L)), Some(h - 100)).toInput(ctx, ErgoId.create("f1" * 32), 0)
      val b = live.input
      val bondBox = NodeBox(b.getId.toString, "f1" * 32, b.getValue, 0, b.getCreationHeight, Hex.toHexString(b.getErgoTree.bytes),
        Seq(NodeAsset(asset, 2L)), NodeRegisters(b.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))
      (nodeOf(posted, 0), bondBox, asset)
    }
    val h = ctx0.getClient.execute(_.getHeight)
    c.extend(h - 40, h)
    c.indexed = h
    c.scans(Seq(order, bond).map(b => LDNodeFixtures.indexed(b, h - 10)): _*)
    val boxes = Seq(order, bond).map(b => b.boxId -> b).toMap
    when(c.api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer(inv =>
      Success(inv.getArgument[Seq[String]](0).flatMap(boxes.get)))
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(h))
    val r = new ZcResolver(() => c.api, t)
    val exec = new ZcWriteExecution(ctx0, EngineFunding(null, 1.second, ExecutionContext.global), t, r) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot = {
        val spentInMempool = if (orderSpentInMempool) Seq(order.boxId) else Seq.empty[String]
        val txs = spentInMempool.map(id => CompleteMempool.MempoolTx("ab" * 32, nodeTx("ab" * 32, Seq(id), Seq.empty), 100))
        CompleteMempool.Snapshot(c.headerId(h), txs.map(_.id).toSet, spentInMempool.toSet, System.currentTimeMillis(),
          transactions = txs.toVector)
      }
    }
    World(exec, order, bond, asset)
  }

  "A fill check" should "quote the bond it creates and the unit this wallet receives, signing nothing" in {
    val w = world()
    val quote = w.exec.fillOrder(w.order.boxId, Json.obj(), send = false)
    (quote \ "refusals").as[Seq[String]] shouldBe empty
    (quote \ "creates").as[Seq[JsObject]].map(c => (c \ "role").as[String]) should contain("bond")
    (quote \ "receivesTokens").as[Seq[JsObject]].map(t => (t \ "tokenId").as[String]) should contain(w.order.boxId)
    (quote \ "terms" \ "assetId").as[String] shouldBe w.order.boxId
    (quote \ "terms" \ "principal").as[String] shouldBe "1000000000"
    (quote \ "fees").as[Seq[JsObject]].map(f => (f \ "name").as[String]) should contain("liquidationReserve")
  }

  "A refused write" should "answer its refusals on the check and 422 on the send" in {
    val w = world()
    val quote = w.exec.liquidate(w.asset, Json.obj(), send = false)
    (quote \ "refusals").as[Seq[String]].head should include("matures at height")
    an[LithosUnprocessable] should be thrownBy w.exec.liquidate(w.asset, Json.obj(), send = true)
  }

  "A send" should "refuse a box a fresh mempool observation shows spent, with 409" in {
    val spent = world(orderSpentInMempool = true)
    val e = the[LithosStateChanged] thrownBy spent.exec.fillOrder(spent.order.boxId, Json.obj(), send = true)
    e.reason should include("already spent by unconfirmed transaction")
  }

  it should "refuse a quote that moved past the body's limits, with 409, before reserving anything" in {
    val w = world()
    val e = the[LithosStateChanged] thrownBy w.exec.fillOrder(w.order.boxId, Json.obj("limits" -> Json.obj("maxPay" -> "1")),
      send = true)
    e.reason should include("over the 1 the request allows")
  }
}
