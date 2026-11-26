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

  /** Testnet runs the mainnet start height: the history waits, then starts once the chain reaches it. */
  it should "wait, rather than fail, on a chain not yet at its start height" in {
    val c = chain()
    val f = index(c, zc.copy(fillsFromHeight = H + 10))
    f.tick()
    f.standing.status shouldBe "loading"
    c.sliceReads.get shouldBe 0

    c.extend(H + 4, H + 12)
    c.indexed = H + 12
    f.tick()
    f.standing.status shouldBe "ready"
    f.standing.indexedTo shouldBe Some(H + 12)
  }

  it should "stay off, reading nothing, until a start height is set" in {
    val c = chain()
    val f = index(c, zc.copy(fillsFromHeight = 0))
    f.tick()
    f.standing.status shouldBe "not-configured"
    c.sliceReads.get shouldBe 0
  }

  it should "prune fills older than fillsRetentionDays while preserving live bond facts for later resales" in {
    // 1 day retention = 720 blocks; bond created at H + 1 matures at 1749600 (well past cutoff)
    val retention = 1
    val c = new ScriptedChain
    val end = H + ZcFills.BlocksPerDay + ZcFills.RollbackBuffer + 20
    c.extend(H - 5, end)
    c.indexed = end
    c.block(H + 1, nodeTx("f1" * 32, Seq(asset, id(900)),
      Seq(wallet(id(33), assets = Seq(asset -> 1L)), oracleBond(id(31), asset, start = H + 1L), wallet(id(34)))))
    // A resale past the retention cutoff must still price from the preserved live BondFacts
    val resaleHeight = H + ZcFills.BlocksPerDay + 50
    c.block(resaleHeight, nodeTx("f2" * 32, Seq(askId, id(901)),
      Seq(box(id(35), party(3), 1000000000L, Seq.empty, regs(bytes(askId), long(1L))), wallet(id(36), assets = Seq(asset -> 1L)))))
    val boxes = Map(asset -> oracleOrder(asset), askId -> ask(askId, asset, 1L, Erg, 1L))
    doAnswer(inv => Success(boxes.get(inv.getArgument[String](0)).map(b =>
      IndexedBox(b, "x", H, 1L, Some("aa" * 32), Some(H + 1)))))
      .when(c.api).indexedBoxById(anyString())

    val settings = zc.copy(fillsRetentionDays = retention)
    val db = ZcFillStore.inMemory(settings)
    val f = new ZcFills(settings, () => db, () => c.api, inline, enabled = true, blocksPerCycle = 1000)
    f.tick()
    f.standing.status shouldBe "ready"
    // Old fill at H + 1 was pruned, recent resale at resaleHeight stays, and live bond facts stayed intact!
    val fills = f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills
    fills.map(x => (x.kind, x.height)) shouldBe Seq(("resale", resaleHeight))
    db.hasBlock(H + 1) shouldBe false
    db.hasBlock(end) shouldBe true
    db.bond(asset) shouldBe defined

    // Once pruning advances past a bond's maturity, both the matured bond fact and its shares are cleaned up
    val maturedAsset = id(40)
    val maturedShare = id(41)
    val liveAsset = id(42)
    val liveShare = id(43)
    val cleanChain = new ScriptedChain
    cleanChain.extend(100, 1000)
    val cleanDb = ZcFillStore.inMemory(settings)
    cleanDb.initialize(100, cleanChain.header(100).id)
    cleanDb.append(cleanChain.header(101), ZcFills.BlockFills(
      Vector.empty,
      Map(maturedAsset -> ZcFills.BondFacts("erg", Erg, maturity = 150L),
        liveAsset -> ZcFills.BondFacts("erg", Erg, maturity = 5000L)),
      Map(maturedShare -> (maturedAsset -> 10L), liveShare -> (liveAsset -> 10L))))
    cleanDb.append(cleanChain.header(1000), ZcFills.BlockFills(Vector.empty, Map.empty, Map.empty))
    cleanDb.prune(cutoffHeight = 200)
    cleanDb.hasBlock(101) shouldBe false
    cleanDb.bond(maturedAsset) shouldBe None
    cleanDb.share(maturedShare) shouldBe None
    cleanDb.bond(liveAsset) shouldBe defined
    cleanDb.share(liveShare) shouldBe defined
  }

  it should "keep fills forever when fillsRetentionDays is 0 and preserve store identity across retention changes" in {
    val c = new ScriptedChain
    val end = H + ZcFills.BlocksPerDay + ZcFills.RollbackBuffer + 20
    c.extend(H - 5, end)
    c.indexed = end
    c.block(H + 1, nodeTx("f1" * 32, Seq(asset, id(900)),
      Seq(wallet(id(33), assets = Seq(asset -> 1L)), oracleBond(id(31), asset, start = H + 1L), wallet(id(34)))))
    val boxes = Map(asset -> oracleOrder(asset))
    doAnswer(inv => Success(boxes.get(inv.getArgument[String](0)).map(b =>
      IndexedBox(b, "x", H, 1L, Some("aa" * 32), Some(H + 1)))))
      .when(c.api).indexedBoxById(anyString())

    val forever = zc.copy(fillsRetentionDays = 0)
    val db = ZcFillStore.inMemory(forever)
    val f = new ZcFills(forever, () => db, () => c.api, inline, enabled = true, blocksPerCycle = 1000)
    f.tick()
    f.fills(ZcMarket.Erg, 0, None, None, None, 50).fills.map(_.height) shouldBe Seq(H + 1)
    db.hasBlock(H + 1) shouldBe true

    // Changing fillsRetentionDays must not alter store identity (no wipe & re-replay)
    ZcFillStore.identity(forever) shouldBe ZcFillStore.identity(zc.copy(fillsRetentionDays = 1460))
  }
}
