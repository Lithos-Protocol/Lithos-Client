package stats

import configs.ZcConfig
import node.model.IndexedBox
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.ZcMarket
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext
import scala.util.Success

/**
 * The fill history over a scripted chain: a new bond from an order, a resale priced from the bond terms the replay
 * learned, a hand-made bond refused, a reorg undoing a fill, and an index that stays off until configured.
 */
class ZcFillsSpec extends AnyFlatSpec with Matchers {

  import transactions.batching.sigmafi.zc.ZcFixtures._
  import transactions.batching.sigmafi.zc.ZcMarketFixtures._

  private val start = H + 1
  private val inline: ExecutionContext = ExecutionContext.fromExecutor((r: Runnable) => r.run())
  private val zc = ZcConfig(enabled = true, 1000, fillsFromHeight = start)
  private val asset = id(30)
  private val askId = id(32)

  /** A chain to `H + 3`: the fill of order `asset` at `H + 1`, a resale of its unit at `H + 2`. */
  private def chain(): ScriptedChain = {
    val c = new ScriptedChain
    c.extend(H - 5, H + 3)
    c.indexed = H + 3
    c.block(H + 1, nodeTx("f1" * 32, Seq(asset, id(900)),
      Seq(wallet(id(33), assets = Seq(asset -> 1L)), oracleBond(id(31), asset, start = H + 1L), wallet(id(34)))))
    c.block(H + 2, nodeTx("f2" * 32, Seq(askId, id(901)),
      Seq(box(id(35), party(3), 1000000000L, Seq.empty, regs(bytes(askId), long(1L))), wallet(id(36), assets = Seq(asset -> 1L)))))
    val boxes = Map(asset -> oracleOrder(asset), askId -> ask(askId, asset, 1L, Erg, 1L))
    doAnswer(inv => Success(boxes.get(inv.getArgument[String](0)).map(b =>
      IndexedBox(b, "x", H, 1L, Some("aa" * 32), Some(H + 1)))))
      .when(c.api).indexedBoxById(anyString())
    c
  }

  private def index(c: ScriptedChain, settings: ZcConfig = zc): ZcFills =
    new ZcFills(settings, () => ZcFillStore.inMemory(settings), () => c.api, inline, enabled = true)

  "The fill history" should "record a new bond, then a resale priced from the bond it learned" in {
    val c = chain()
    val f = index(c)
    f.tick()
    f.standing.status shouldBe "ready"
    f.standing.indexedTo shouldBe Some(H + 3)
    val fills = f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills
    fills.map(x => (x.kind, x.height, x.via)) shouldBe Seq(("resale", H + 2, "wallet"), ("new", H + 1, "wallet"))
    val bond = fills.find(_.kind == "new").get
    (bond.cost, bond.face, bond.bondId, bond.maturity) shouldBe ((Erg, 1050000000L, asset, 1749600L))
    bond.yieldBps shouldBe transactions.batching.sigmafi.zc.ZcPricing.yieldBps(BigInt(1050000000L), BigInt(Erg), 1749600L - (H + 1))
    val resale = fills.find(_.kind == "resale").get
    (resale.id, resale.sourceId, resale.cost, resale.face) shouldBe ((s"${"f2" * 32}:0", askId, Erg, 1050000000L))
  }

  it should "refuse a bond whose INPUTS(0) was not an order" in {
    val c = chain()
    doAnswer(_ => Success(None)).when(c.api).indexedBoxById(anyString())
    val f = index(c)
    f.tick()
    f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills shouldBe empty
  }

  it should "undo a fill the canonical chain drops" in {
    val c = chain()
    val f = index(c)
    f.tick()
    f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills should have size 2
    // Another block at H + 2 without the resale, and the chain grows past it
    c.extend(H + 2, H + 4, branch = 1)
    c.indexed = H + 4
    f.tick()
    f.tick()
    f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills.map(_.kind) shouldBe Seq("new")
    f.standing.indexedTo shouldBe Some(H + 4)
  }

  it should "stay off, reading nothing, until a start height is set" in {
    val c = chain()
    val f = index(c, zc.copy(fillsFromHeight = 0))
    f.tick()
    f.standing.status shouldBe "not-configured"
    c.sliceReads.get shouldBe 0
  }
}
