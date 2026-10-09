package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{CanonicalNodeBox, FakeNodeContext}
import transactions.engine.execution.RollupExecution
import configs.UpkeepConfig
import transactions.upkeep.jobs.HeartbeatJob
import transactions.upkeep.jobs.HeartbeatJob.Beat
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

import scala.collection.JavaConverters._
import scala.util.Success

/**
 * The heartbeat's own rule against a mocked node: which boxes at its script it maintains, what
 * `due` says at the boundary, and what its plan produces once signed. Discovery, paging, the
 * configured list and assembly are every script job's and [[ScriptJobSpec]]'s to prove; the
 * contract's own rules are [[contracts.specs.upkeep.DueJobSpec]]'s. Here the successor is checked
 * against those rules field by field, and signing it offline shows the plan is one the keyless
 * prover can sign.
 */
class HeartbeatJobSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val period: Int = 10
  private val tip: Long = Parameters.OneErg / 100
  private val tokenId: String = "ab" * 32

  /** A box id from any seed: the seed's bytes as hex, repeated, so `CanonicalNodeBox` can decode it. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  /** An indexed node, so discovery asks the index for every box at the script. */
  private class Fixture {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val client = nodeContext.getClient
    /** minTip 0, so the free-beat cases below are reachable; the default is tested on its own. */
    val job = new HeartbeatJob(Seq.empty, minTip = 0L)
    val tree: String = client.execute(ctx => HeartbeatJob.contract(ctx.getNetworkType).ergoTreeHex)

    /** What the index holds. */
    @volatile var atTree: Seq[NodeBox] = Seq.empty

    when(api.indexerEnabled).thenReturn(true)
    when(api.unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val asked = inv.getArgument[String](0)
        val paging = inv.getArgument[Paging](1)
        Success(atTree.filter(_.ergoTree == asked).slice(paging.offset, paging.offset + paging.limit)
          .map(box => IndexedBox(box, "", box.creationHeight, 1L)))
      }

    def discover(): Seq[String] = client.execute(ctx => job.discover(ctx, api, ctx.getHeight))

    /** A due-job box as the node reports it, with the id its bytes commit to. */
    def dueBox(seed: String, lastBeat: Int = 1000, period: Int = period, tip: Long = tip,
               value: Long = Parameters.OneErg, assets: Seq[NodeAsset] = Seq.empty,
               registers: Seq[ErgoValue[_]] = null): NodeBox = {
      val regs: Seq[ErgoValue[_]] =
        if (registers == null) Seq(ErgoValue.of(lastBeat), ErgoValue.of(period), ErgoValue.of(tip))
        else registers
      CanonicalNodeBox(id(seed), id(seed), value, 0, lastBeat, tree, assets,
        NodeRegisters(regs.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
    }
  }

  // ─── the tree ─────────────────────────────────────────────────────────────

  "The pinned tree" should "be what DueJob.ergo compiles to, on every network" in {
    support.UpkeepContracts.mkDueJobContract(NetworkType.MAINNET).ergoTreeHex shouldBe HeartbeatJob.TreeHex
    support.UpkeepContracts.mkDueJobContract(NetworkType.TESTNET).ergoTreeHex shouldBe HeartbeatJob.TreeHex
  }

  it should "be the tree discovery compares against, whatever the network" in {
    HeartbeatJob.contract(NetworkType.MAINNET).ergoTreeHex shouldBe HeartbeatJob.TreeHex
    HeartbeatJob.contract(NetworkType.TESTNET).ergoTreeHex shouldBe HeartbeatJob.TreeHex
    new Fixture().tree shouldBe HeartbeatJob.TreeHex
  }

  // ─── discovery ────────────────────────────────────────────────────────────

  "Discovery" should "keep every box at the contract whose registers are a beat, and skip the rest" in {
    val f = new Fixture()
    val a = f.dueBox("a")
    val b = f.dueBox("b", lastBeat = 2000, period = 1, tip = 0L)
    val wrongR4 = f.dueBox("c", registers = Seq(ErgoValue.of(1000L), ErgoValue.of(period), ErgoValue.of(tip)))
    val wrongR6 = f.dueBox("d", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period), ErgoValue.of(tip.toInt)))
    val noR6 = f.dueBox("e", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period)))
    val bare = f.dueBox("f", registers = Seq.empty)
    val neverDue = f.dueBox("g", period = 0)
    val negativeTip = f.dueBox("h", tip = -1L)
    // A register the node reports but that does not decode: skipped like a wrong type, not thrown on.
    val undecodable = NodeBox(id("i"), id("i"), Parameters.OneErg, 0, 1000, f.tree, Seq.empty,
      NodeRegisters(Map("R4" -> "zz", "R5" -> ErgoValue.of(period).toHex, "R6" -> ErgoValue.of(tip).toHex)))
    f.atTree = Seq(a, wrongR4, b, wrongR6, noR6, bare, neverDue, negativeTip, undecodable)

    f.discover() shouldBe Seq(a.boxId, b.boxId)
    verify(f.api, never()).boxById(any[String])
  }

  // ─── due ──────────────────────────────────────────────────────────────────

  "Due" should "be exact at the boundary: last beat plus period" in {
    val f = new Fixture()
    val box = f.dueBox("a", lastBeat = 1000, period = period)
    f.client.execute { ctx =>
      val input = box.toInputUTXO(ctx)
      f.job.due(input, 1000 + period - 1) shouldBe false
      f.job.due(input, 1000 + period) shouldBe true
      f.job.due(input, 1000 + period + 1) shouldBe true
      f.job.due(input, 1000 + 100 * period) shouldBe true
    }
  }

  it should "be false for a box whose registers are not a beat" in {
    val f = new Fixture()
    val malformed = f.dueBox("a", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period.toLong), ErgoValue.of(tip)))
    f.client.execute { ctx =>
      f.job.due(malformed.toInputUTXO(ctx), Int.MaxValue) shouldBe false
    }
  }

  "Expected revenue" should "be what the beat would pay, and nothing for a box whose registers are not a beat" in {
    val f = new Fixture()
    val malformed = f.dueBox("b", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period.toLong), ErgoValue.of(tip)))
    f.client.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)
      f.job.expectedRevenue(f.dueBox("a", tip = 3 * tip).toInputUTXO(ctx), bc) shouldBe 3 * tip
      f.job.expectedRevenue(malformed.toInputUTXO(ctx), bc) shouldBe 0L
      // A declared tip the box cannot pay counts for nothing: the beat would be free, or declined.
      val floor = floorOf(f, 1000000L, bc.height, bc)
      f.job.expectedRevenue(f.dueBox("c", tip = Long.MaxValue / 2, value = floor + 1000L).toInputUTXO(ctx), bc) shouldBe 0L
      f.job.expectedRevenue(f.dueBox("d", tip = Long.MaxValue / 2, value = floor - 1L).toInputUTXO(ctx), bc) shouldBe 0L
      // and a box that can spare only part of its tip is worth exactly what its beat pays
      val partial = f.dueBox("e", tip = 3 * tip, value = floor + tip).toInputUTXO(ctx)
      val paid = f.job.build(partial, bc).getOrElse(fail("the partial beat was declined")).capital.map(_.value).sum
      paid should (be > 0L and be <= tip)
      f.job.expectedRevenue(partial, bc) shouldBe paid
      new HeartbeatJob(Seq.empty, minTip = 2 * tip).expectedRevenue(partial, bc) shouldBe 0L
    }
  }

  "A beat" should "read R4, R5 and R6 and nothing past them" in {
    val annotated: Seq[ErgoValue[_]] = Seq(ErgoValue.of(1000), ErgoValue.of(period), ErgoValue.of(tip), ErgoValue.of(42L))
    val short: Seq[ErgoValue[_]] = Seq(ErgoValue.of(1000), ErgoValue.of(period))
    Beat.of(annotated) shouldBe Some(Beat(1000, period, tip))
    Beat.of(short) shouldBe None
    Beat.of(Seq.empty[ErgoValue[_]]) shouldBe None
  }

  // ─── build ────────────────────────────────────────────────────────────────

  /** The successor as a signed transaction's output, read back the way the next build will read it. */
  private def successorOf(built: UpkeepJob.Built): InputUTXO = InputUTXO(built.tx.getOutputsToSpend.get(0))

  "A build" should "advance the box to a successor at the block, lighter by the tip paid to payTo" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val box = f.dueBox("a", lastBeat = height - period, value = 2L * Parameters.OneErg,
        assets = Seq(NodeAsset(tokenId, 5L)))
      val input = box.toInputUTXO(ctx)
      f.job.due(input, height) shouldBe true

      val built = f.job.build(input, BuildContext(ctx, height, f.wallet.contract)).getOrElse(fail("the job built nothing"))
      val outputs = built.tx.getOutputsToSpend.asScala
      outputs should have size 2

      val successor = successorOf(built)
      successor.contract.ergoTreeHex shouldBe f.tree
      successor.value shouldBe box.value - tip
      successor.tokens.map(t => (t.id.toString, t.amount)) shouldBe Seq((tokenId, 5L))
      successor.input.getCreationHeight shouldBe height
      Beat.of(successor) shouldBe Some(Beat(height, period, tip))

      val paid = outputs(1)
      Contract(paid.getErgoTree) shouldBe f.wallet.contract
      paid.getValue shouldBe tip
      paid.getCreationHeight shouldBe height
      built.capital.map(_.value) shouldBe Seq(tip)
      built.capital.head.parentTxId shouldBe built.tx.getId
      built.capital.head.outputId shouldBe paid.getId.toString

      // Spends only the box, with no fee output, and what it costs the block is measured.
      RollupExecution.signedInputIds(built.tx) shouldBe Set(box.boxId)
      Upkeep.undiscoveredInputs(built.tx, Set(box.boxId)) shouldBe empty
      built.tx.getCost should be > 0
    }
  }

  it should "pay the tip to whatever contract it is handed" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val input = f.dueBox("a", lastBeat = height - period).toInputUTXO(ctx)
      val built = f.job.build(input, BuildContext(ctx, height, Contract.SIGMA_TRUE)).getOrElse(fail("the job built nothing"))
      Contract(built.tx.getOutputsToSpend.get(1).getErgoTree) shouldBe Contract.SIGMA_TRUE
      built.capital.map(_.value) shouldBe Seq(tip)
    }
  }

  it should "leave a tip too small for a box of its own in the successor, and pay nothing" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      Seq(1L, 0L).foreach { small =>
        val box = f.dueBox(s"s$small", lastBeat = height - period, tip = small)
        val built = f.job.build(box.toInputUTXO(ctx), BuildContext(ctx, height, f.wallet.contract))
          .getOrElse(fail("the job built nothing"))
        built.tx.getOutputsToSpend.size shouldBe 1
        val successor = successorOf(built)
        successor.value shouldBe box.value
        Beat.of(successor) shouldBe Some(Beat(height, period, small))
        built.capital shouldBe empty
      }
    }
  }

  /**
   * The least the successor of a box worth `value` may keep at `height`, sized the way the job sizes
   * it: the box recreated at its full value with R4 at the height.
   */
  private def floorOf(f: Fixture, value: Long, height: Int, bc: BuildContext): Long =
    ScriptJob.minimumValue(UTXO(Contract(ErgoTree.fromHex(f.tree)), value, Seq.empty,
      Seq(ErgoValue.of(height), ErgoValue.of(period), ErgoValue.of(tip))).setCreationHeight(height), bc)

  it should "pay only what the box can spare above its successor's floor" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val bc = BuildContext(ctx, height, f.wallet.contract)
      // Sized at a value with as many VLQ bytes (four) as the box value built below, so the floor is the box's own.
      val floor = floorOf(f, 10000000L, height, bc)
      val spare = tip / 2
      val box = f.dueBox("a", lastBeat = height - period, value = floor + spare)
      floorOf(f, box.value, height, bc) shouldBe floor

      val built = f.job.build(box.toInputUTXO(ctx), bc).getOrElse(fail("the job built nothing"))
      built.tx.getOutputsToSpend.size shouldBe 2
      successorOf(built).value shouldBe floor
      Beat.of(successorOf(built)) shouldBe Some(Beat(height, period, tip))
      built.tx.getOutputsToSpend.get(1).getValue shouldBe spare
      built.capital.map(_.value) shouldBe Seq(spare)
    }
  }

  it should "beat for free when what the box can spare is too small for a box of its own" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val bc = BuildContext(ctx, height, f.wallet.contract)
      val floor = floorOf(f, 1000000L, height, bc)
      val box = f.dueBox("a", lastBeat = height - period, value = floor + 1000L)
      floorOf(f, box.value, height, bc) shouldBe floor
      f.job.due(box.toInputUTXO(ctx), height) shouldBe true

      val built = f.job.build(box.toInputUTXO(ctx), bc).getOrElse(fail("the job built nothing"))
      built.tx.getOutputsToSpend.size shouldBe 1
      successorOf(built).value shouldBe box.value
      Beat.of(successorOf(built)) shouldBe Some(Beat(height, period, tip))
      built.capital shouldBe empty
    }
  }

  /** Below its own successor's minimum the box cannot be recreated: declined, so the source holds it. */
  it should "decline a box worth less than its successor's floor rather than build one the node refuses" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val bc = BuildContext(ctx, height, f.wallet.contract)
      val floor = floorOf(f, 1000000L, height, bc)
      val poor = f.dueBox("a", lastBeat = height - period, value = floor - 1L)
      f.job.due(poor.toInputUTXO(ctx), height) shouldBe true
      f.job.build(poor.toInputUTXO(ctx), bc) shouldBe None
      val exact = f.dueBox("b", lastBeat = height - period, value = floor)
      successorOf(f.job.build(exact.toInputUTXO(ctx), bc).getOrElse(fail("a box at its floor was declined")))
        .value shouldBe floor
    }
  }

  "minTip" should "keep a job to boxes that offer at least that tip" in {
    val f = new Fixture()
    val choosy = new HeartbeatJob(Seq.empty, minTip = tip)
    f.client.execute { ctx =>
      choosy.maintains(f.dueBox("a", tip = tip).toInputUTXO(ctx)) shouldBe true
      choosy.maintains(f.dueBox("b", tip = tip - 1L).toInputUTXO(ctx)) shouldBe false
      f.job.maintains(f.dueBox("c", tip = 0L).toInputUTXO(ctx)) shouldBe true
    }
    // the default declines a free beat: a beat must pay at least a thousandth of an ERG
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val bc = BuildContext(ctx, height, f.wallet.contract)
      val floor = floorOf(f, 1000000L, height, bc)
      val byDefault = HeartbeatJob.Factory.make(UpkeepConfig.Job(enabled = true))
      byDefault.build(f.dueBox("g", lastBeat = height - period, value = floor + 1000L).toInputUTXO(ctx), bc) shouldBe None
      byDefault.build(f.dueBox("h", lastBeat = height - period, tip = HeartbeatJob.DefaultMinTip).toInputUTXO(ctx), bc)
        .map(_.capital.map(_.value).sum) shouldBe Some(HeartbeatJob.DefaultMinTip)
    }
    HeartbeatJob.Factory.check(play.api.Configuration.from(Map("minTip" -> -1L))) should not be empty
    HeartbeatJob.Factory.check(play.api.Configuration.from(Map("minTip" -> 5L))) shouldBe empty
    HeartbeatJob.Factory.check(play.api.Configuration.empty) shouldBe empty
  }

  "A build of a malformed box" should "build nothing for a box whose registers are not a beat" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val malformed = f.dueBox("a", registers = Seq(ErgoValue.of(1000L), ErgoValue.of(period), ErgoValue.of(tip)))
      f.job.build(malformed.toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)) shouldBe None
    }
  }
}
