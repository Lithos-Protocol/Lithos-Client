package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model._
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{NetworkType, Parameters}
import org.ergoplatform.appkit.impl.SignedTransactionImpl
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{atLeastOnce, never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{CanonicalNodeBox, FakeNodeContext}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{Contract, InputUTXO, MainnetEip27Constants}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

/**
 * What every script job gets from [[ScriptJob]], driven through [[FakeScriptJob]] against a mocked
 * node: discovery on an indexed node and on a plain one, and the assembly, keyless signing and
 * capital entries of a successor plan. A job's own rule — its script, its due predicate, its
 * successor — is that job's spec to prove; the heartbeat's is [[HeartbeatJobSpec]].
 */
class ScriptJobSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  /** A box id from any seed: the seed's bytes as hex, repeated, so `CanonicalNodeBox` can decode it. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  private val tree: String = Contract.SIGMA_TRUE.ergoTreeHex

  /**
   * @param indexed whether the node reports the extra index
   * @param boxIds  the configured list
   */
  private class Fixture(indexed: Boolean = true, boxIds: Seq[String] = Seq.empty) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val client = nodeContext.getClient
    val job = new FakeScriptJob(boxIds)

    /** What the index holds, and what a read by id answers from. */
    @volatile var atTree: Seq[NodeBox] = Seq.empty
    @volatile var live: Seq[NodeBox] = Seq.empty
    @volatile var indexDown: Boolean = false
    val pages = new AtomicInteger(0)

    when(api.indexerEnabled).thenReturn(indexed)
    when(api.unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        pages.incrementAndGet()
        if (indexDown) Failure(new RuntimeException("the index is down"))
        else {
          val asked = inv.getArgument[String](0)
          val paging = inv.getArgument[Paging](1)
          val all = atTree.filter(_.ergoTree == asked)
          Success(all.slice(paging.offset, paging.offset + paging.limit)
            .map(box => IndexedBox(box, "", box.creationHeight, 1L)))
        }
      }
    // The UTXO set only: a box missing from `live` is spent or not yet confirmed, and reads as absent.
    when(api.boxById(any[String])).thenAnswer { inv =>
      val asked = inv.getArgument[String](0)
      Success(live.find(_.boxId == asked))
    }

    def discover(): Seq[String] = client.execute(ctx => job.discover(ctx, api, ctx.getHeight))

    /** A box at the job's script, with the id its bytes commit to. */
    def scriptBox(seed: String, value: Long = Parameters.OneErg): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), value, 0, 100, tree)

    /** A box at this wallet's key: what a mistaken entry in the configured list looks like. */
    def strayBox(seed: String): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), Parameters.OneErg, 0, 100, wallet.contract.ergoTreeHex)
  }

  // ─── discovery ────────────────────────────────────────────────────────────

  "Discovery on an indexed node" should "list every box at the script the job maintains and skip the rest" in {
    val f = new Fixture()
    val a = f.scriptBox("a")
    val b = f.scriptBox("b")
    val notMaintained = f.scriptBox("c")
    // A register the node reports but that does not decode: skipped like a box the job refuses.
    val undecodable = NodeBox(id("d"), id("d"), Parameters.OneErg, 0, 100, tree, Seq.empty,
      NodeRegisters(Map("R4" -> "zz")))
    f.job.foreign = Set(notMaintained.boxId)
    f.atTree = Seq(a, notMaintained, b, undecodable, f.strayBox("e"))

    f.discover() shouldBe Seq(a.boxId, b.boxId)
    verify(f.api, never()).boxById(any[String])
  }

  it should "list the boxes it finds by priority, lowest first" in {
    val f = new Fixture()
    val late = f.scriptBox("late")
    val soon = f.scriptBox("soon")
    val unset = f.scriptBox("unset")
    f.job.priorities = Map(late.boxId -> 50L, soon.boxId -> 10L)
    f.atTree = Seq(late, unset, soon)

    f.discover() shouldBe Seq(unset.boxId, soon.boxId, late.boxId)
  }

  /** EIP-27 forbids the outputs to carry the token the recreation must keep, so no successor exists. */
  it should "skip a box carrying the re-emission token, as rent does" in {
    val f = new Fixture()
    val ordinary = f.scriptBox("a")
    val reEmission = CanonicalNodeBox(id("r"), id("r"), Parameters.OneErg, 0, 100, tree,
      Seq(NodeAsset(MainnetEip27Constants.TokenId, 1L)))
    f.client.execute(ctx => ctx.getNetworkType) shouldBe NetworkType.MAINNET
    f.atTree = Seq(reEmission, ordinary)

    f.discover() shouldBe Seq(ordinary.boxId)
  }

  it should "page the index until a short page" in {
    val f = new Fixture()
    val boxes = (0 until 150).map(i => f.scriptBox(s"p$i"))
    f.atTree = boxes

    f.discover() shouldBe boxes.map(_.boxId)
    f.pages.get shouldBe 2
    // newest first, so a box a beat just renewed stays in the window
    verify(f.api, atLeastOnce()).unspentBoxesByErgoTree(any[String], any[Paging],
      org.mockito.ArgumentMatchers.eq(SortDirection.Desc), any[MempoolOptions])
  }

  it should "stop after the most pages one pass reads" in {
    val f = new Fixture()
    val boxes = (0 until (ScriptJob.MaxPages + 1) * ScriptJob.PageSize).map(i => f.scriptBox(s"m$i"))
    f.atTree = boxes

    f.discover() should have size (ScriptJob.MaxPages * ScriptJob.PageSize).toLong
    f.pages.get shouldBe ScriptJob.MaxPages
  }

  it should "throw when the index cannot be read, so the source keeps its last pass" in {
    val f = new Fixture()
    f.atTree = Seq(f.scriptBox("a"))
    f.indexDown = true

    an[IllegalStateException] should be thrownBy f.discover()
  }

  /** The configured ids have to be known before the job exists, so a throwaway fixture mints the boxes. */
  it should "also read the configured list, first and without listing a box twice" in {
    val probe = new Fixture()
    val a = probe.scriptBox("a")
    val b = probe.scriptBox("b")
    val c = probe.scriptBox("c")
    val f = new Fixture(boxIds = Seq(b.boxId, a.boxId))
    f.atTree = Seq(c, a)
    f.live = Seq(a, b, c)

    f.discover() shouldBe Seq(b.boxId, a.boxId, c.boxId)
    f.job.configured shouldBe Set(a.boxId, b.boxId)
  }

  /** One call per id against the UTXO set: a box only the mempool holds is never offered. */
  it should "read each configured box by id from the UTXO set, one call each" in {
    val probe = new Fixture()
    val a = probe.scriptBox("a")
    val pending = probe.scriptBox("b")
    val f = new Fixture(boxIds = Seq(a.boxId, pending.boxId))
    f.live = Seq(a)

    f.discover() shouldBe Seq(a.boxId)
    verify(f.api, times(1)).boxById(a.boxId)
    verify(f.api, times(1)).boxById(pending.boxId)
    verify(f.api, never()).boxesWithPoolByIds(any[Seq[String]])
  }

  "Discovery on a plain node" should "read the configured list and never ask the index" in {
    val probe = new Fixture()
    val a = probe.scriptBox("a")
    val spent = probe.scriptBox("b")
    val stray = probe.strayBox("c")
    val f = new Fixture(indexed = false, boxIds = Seq(a.boxId, spent.boxId, stray.boxId))
    f.atTree = Seq(a, spent)
    f.live = Seq(a, stray)

    f.discover() shouldBe Seq(a.boxId)
    verify(f.api, never()).unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions])
  }

  it should "find nothing, and read nothing, with no list configured" in {
    val f = new Fixture(indexed = false)
    f.atTree = Seq(f.scriptBox("a"))

    f.discover() shouldBe empty
    verify(f.api, never()).boxById(any[String])
    verify(f.api, never()).unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions])
  }

  it should "throw when the configured boxes cannot be read" in {
    val f = new Fixture(indexed = false, boxIds = Seq(id("a")))
    when(f.api.boxById(any[String])).thenReturn(Failure(new RuntimeException("node down")))

    an[IllegalStateException] should be thrownBy f.discover()
  }

  // ─── build ────────────────────────────────────────────────────────────────

  "A build" should "sign the plan with no key and no fee, spending only the box, and declare its revenue" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)
      val box = f.scriptBox("a")
      val built = f.job.build(box.toInputUTXO(ctx), bc).getOrElse(fail("the job built nothing"))

      val outputs = built.tx.getOutputsToSpend.asScala
      outputs should have size 2
      outputs.head.getValue shouldBe box.value - FakeScriptJob.Tip
      outputs.foreach(_.getCreationHeight shouldBe bc.height)
      Contract(outputs(1).getErgoTree) shouldBe f.wallet.contract

      built.capital.map(_.value) shouldBe Seq(FakeScriptJob.Tip)
      built.capital.head.parentTxId shouldBe built.tx.getId
      built.capital.head.outputId shouldBe outputs(1).getId.toString

      RollupExecution.signedInputIds(built.tx) shouldBe Set(box.boxId)
      built.tx.getCost should be > 0
    }
  }

  it should "carry a data input the plan names, and still spend only the box" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)
      val box = f.scriptBox("a")
      val oracle = f.strayBox("oracle")
      f.job.dataInputs = Seq(oracle.toInputUTXO(ctx))

      val built = f.job.build(box.toInputUTXO(ctx), bc).getOrElse(fail("the job built nothing"))
      val tx = built.tx.asInstanceOf[SignedTransactionImpl].getTx
      tx.dataInputs.map(d => Hex.toHexString(d.boxId)) shouldBe Seq(oracle.boxId)
      RollupExecution.signedInputIds(built.tx) shouldBe Set(box.boxId)
      built.capital.map(_.value) shouldBe Seq(FakeScriptJob.Tip)
    }
  }

  it should "declare nothing when the plan names no revenue" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.revenue = Seq.empty
      val built = f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract))
        .getOrElse(fail("the job built nothing"))
      built.tx.getOutputsToSpend.size shouldBe 2
      built.capital shouldBe empty
    }
  }

  it should "build nothing when the job has no plan for the box" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.cannotPay = true
      f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)) shouldBe None
    }
  }

  /** The builder cannot place sub-minimum change without a fee output, which a block transaction has none of. */
  it should "throw on a plan that does not spend all of the box, naming the job" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.leftOver = 1L
      val thrown = the[IllegalArgumentException] thrownBy
        f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract))
      thrown.getMessage should include(f.job.name)
    }
  }

  /** The source treats a throw as a refusal, which is the right answer to a plan it cannot trust. */
  it should "throw on a plan whose revenue names an output it does not have" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.revenue = Seq(2)
      an[IllegalArgumentException] should be thrownBy
        f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract))
    }
  }

  it should "throw on a plan with an output at the fee proposition" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.feeOutput = FakeScriptJob.Tip / 2
      val thrown = the[IllegalArgumentException] thrownBy
        f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract))
      thrown.getMessage should include("fee")
    }
  }

  it should "throw on a plan whose revenue is not at this miner's collection contract" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.tipTo = Some(Contract.SIGMA_TRUE)
      val thrown = the[IllegalArgumentException] thrownBy
        f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract))
      thrown.getMessage should include("collection")
    }
  }

  it should "throw on a plan with an output at neither the box's script nor the collection contract" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      f.job.extraTo = Some(f.wallet.contract)
      val thrown = the[IllegalArgumentException] thrownBy
        f.job.build(f.scriptBox("a").toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, Contract.SIGMA_TRUE))
      thrown.getMessage should include("neither")
    }
  }

  "The build context" should "read the node's parameters and network from the context it is made from" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val bc = BuildContext(ctx, 1234, Contract.SIGMA_TRUE)
      bc.height shouldBe 1234
      bc.payTo shouldBe Contract.SIGMA_TRUE
      bc.network shouldBe ctx.getNetworkType
      bc.params.getMinValuePerByte shouldBe ctx.getDataSource.getParameters.getMinValuePerByte
    }
  }

  /** The same box as it stands, so the same length: its transaction id differs, not its size. */
  "The minimum value" should "be the node's price per byte times the box's length" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)
      val input: InputUTXO = f.scriptBox("a").toInputUTXO(ctx)
      ScriptJob.minimumValue(input.toUTXO.setCreationHeight(input.input.getCreationHeight), bc) shouldBe
        bc.params.getMinValuePerByte.toLong * input.bytes.length.toLong
    }
  }
}
