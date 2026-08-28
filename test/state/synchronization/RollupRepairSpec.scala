package state.synchronization

import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Longs
import state.messages.{BlockTx, InputSpendingProof, TxOutput}
import support.{ChainFixtures, ReducerFixtures, SyncFixtures}

import scala.util.Success

/** Drives the real indexed repair walk; install accounting is covered separately by RepairInstallSpec. */
class RollupRepairSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()

  "RollupRepair" should "rebuild from collateral through only authenticated indexed links" in {
    val chain = repairChain()

    val result = new RollupRepair(chain.api, protocol, maxTransforms = 10)
      .run(chain.fault, committedHeight = 110)

    val rebuilt = result.asInstanceOf[RollupRepairResult.Rebuilt].tree
    rebuilt.utxoId shouldEqual chain.tipId
    rebuilt.numMiners shouldEqual 2
    rebuilt.totalScore shouldEqual BigInt(12)
    verify(chain.api, never()).indexedBlocksByHeaderIds(any[Seq[String]])
  }

  it should "reject a transaction endpoint that returns another transaction id" in {
    val chain = repairChain()
    when(chain.api.indexedTransactionById(chain.firstSpend.id))
      .thenReturn(Success(Some(chain.indexed(chain.firstSpend.id).copy(id = SyncFixtures.id(899001)))))

    val result = failed(chain)
    result.reason should include("for requested transaction")
    result.retryable shouldBe true
  }

  it should "reject a returned transaction that does not spend the followed box" in {
    val chain = repairChain()
    val indexed = chain.indexed(chain.firstSpend.id)
    when(chain.api.indexedTransactionById(chain.firstSpend.id)).thenReturn(Success(Some(
      indexed.copy(inputs = indexed.inputs.map(_.copy(
        box = indexed.inputs.head.box.copy(boxId = SyncFixtures.id(899002))))))))

    val result = failed(chain)
    result.reason should include("does not spend followed box")
    result.retryable shouldBe true
  }

  it should "refuse a spent box whose index omits the spending height" in {
    val chain = repairChain()
    when(chain.api.indexedBoxById(chain.genesisOutputId))
      .thenReturn(Success(Some(chain.boxes(chain.genesisOutputId).copy(spendingHeight = None))))

    val result = failed(chain)
    result.reason should include("without a spending height")
    result.retryable shouldBe true
  }

  it should "refuse an invalid transaction inclusion height" in {
    val chain = repairChain()
    val indexed = chain.indexed(chain.firstSpend.id)
    when(chain.api.indexedBoxById(chain.genesisOutputId))
      .thenReturn(Success(Some(chain.boxes(chain.genesisOutputId).copy(spendingHeight = Some(-1)))))
    when(chain.api.indexedTransactionById(chain.firstSpend.id))
      .thenReturn(Success(Some(indexed.copy(inclusionHeight = -1))))

    val result = failed(chain)
    result.reason should include("no valid inclusion height")
    result.retryable shouldBe true
  }

  it should "refuse a spend whose authoritative inclusion is inconsistent with the box index" in {
    val chain = repairChain()
    when(chain.api.indexedBoxById(chain.genesisOutputId))
      .thenReturn(Success(Some(chain.boxes(chain.genesisOutputId).copy(spendingHeight = Some(100)))))

    val result = failed(chain)
    result.reason should include("reports spending height 100")
    result.retryable shouldBe true
  }

  it should "refuse an indexed transaction that names a noncanonical block" in {
    val chain = repairChain()
    val indexed = chain.indexed(chain.firstSpend.id)
    when(chain.api.indexedTransactionById(chain.firstSpend.id))
      .thenReturn(Success(Some(indexed.copy(blockId = SyncFixtures.id(899003)))))

    val result = failed(chain)
    result.reason should include("but canonical height")
    result.retryable shouldBe true
  }

  it should "reject a linked spend that changes no target rollup state" in {
    val chain = repairChain()
    val indexed = chain.indexed(chain.firstSpend.id)
    val unrelated = indexed.inputs.head.copy(
      box = indexed.inputs.head.box.copy(boxId = SyncFixtures.id(899004)))
    when(chain.api.indexedTransactionById(chain.firstSpend.id))
      .thenReturn(Success(Some(indexed.copy(inputs = Seq(unrelated, indexed.inputs.head)))))

    val result = failed(chain)
    result.reason should include("changed no rollup state")
    result.retryable shouldBe false
  }

  /** A spend after the cutoff is valid evidence that the current box was still live at the cursor. */
  it should "stop before a canonically included spend above the repair cutoff" in {
    val chain = repairChain()

    val result = new RollupRepair(chain.api, protocol, maxTransforms = 10)
      .run(chain.fault, committedHeight = 100)

    result.asInstanceOf[RollupRepairResult.Rebuilt].tree.utxoId shouldEqual chain.genesisOutputId
  }

  private def failed(chain: RepairChain): RollupRepairResult.Failed =
    new RollupRepair(chain.api, protocol, maxTransforms = 10)
      .run(chain.fault, committedHeight = 110)
      .asInstanceOf[RollupRepairResult.Failed]

  private final case class RepairChain(api: NodeApi,
                                       fault: QuarantineFault,
                                       genesisOutputId: String,
                                       firstSpend: BlockTx,
                                       tipId: String,
                                       boxes: Map[String, IndexedBox],
                                       indexed: Map[String, IndexedTransaction])

  private def repairChain(): RepairChain = {
    val genesisHeight = 100
    val collateralId = SyncFixtures.id(880001)
    val genesisOutputId = SyncFixtures.id(880002)
    val firstOutputId = SyncFixtures.id(880003)
    val tipId = SyncFixtures.id(880004)
    val rollupId = ChainFixtures.headerId(genesisHeight)
    val genesis = ReducerFixtures.genesisTx(80, collateralId, genesisOutputId, genesisHeight)

    val firstDictionary = lfsm.states.PlasmaDictionary.empty()
    val firstKey = SyncFixtures.plasmaEntries(1, 16).head._1
    val firstValue = Longs.toByteArray(5L) ++ Array.fill[Byte](8)(1)
    val firstInsertion = firstDictionary.insert(firstKey -> firstValue)
    val firstSpend = ReducerFixtures.submissionTx(81, genesisOutputId, firstOutputId, 101,
      firstKey, firstValue, firstInsertion.proof.ergoValue.toHex, firstDictionary,
      numMiners = 1, totalScore = BigInt(5), period = genesisHeight.toLong)

    val secondDictionary = firstDictionary.copy()
    val secondKey = SyncFixtures.plasmaEntries(2, 16).last._1
    val secondValue = Longs.toByteArray(7L) ++ Array.fill[Byte](8)(2)
    val secondInsertion = secondDictionary.insert(secondKey -> secondValue)
    val secondSpend = ReducerFixtures.submissionTx(82, firstOutputId, tipId, 102,
      secondKey, secondValue, secondInsertion.proof.ergoValue.toHex, secondDictionary,
      numMiners = 2, totalScore = BigInt(12), period = genesisHeight.toLong)

    val collateral = IndexedBox(
      NodeBox(collateralId, SyncFixtures.id(879999), 1000000L, 0, 90,
        ReducerFixtures.CollateralTree,
        Seq(NodeAsset(ReducerFixtures.CollateralToken.toString, 1L)), NodeRegisters.empty),
      "", 90, 0L, Some(genesis.id), Some(genesisHeight), None)
    val genesisOutput = indexedOutput(genesis.outputs.head, genesisHeight,
      Some(firstSpend.id), Some(101), firstSpend.inputs.head.spendingProof)
    val firstOutput = indexedOutput(firstSpend.outputs.head, 101,
      Some(secondSpend.id), Some(102), secondSpend.inputs.head.spendingProof)
    val tip = indexedOutput(secondSpend.outputs.head, 102, None, None, None)

    val boxes = Map(collateralId -> collateral, genesisOutputId -> genesisOutput,
      firstOutputId -> firstOutput, tipId -> tip)
    val indexed = Map(
      genesis.id -> indexedTransaction(genesis, genesisHeight, Seq(collateral), rollupId),
      firstSpend.id -> indexedTransaction(firstSpend, 101, Seq(genesisOutput), ChainFixtures.headerId(101)),
      secondSpend.id -> indexedTransaction(secondSpend, 102, Seq(firstOutput), ChainFixtures.headerId(102)))

    val api = ChainFixtures.nodeAt(110)
    boxes.foreach { case (id, box) =>
      when(api.indexedBoxById(id)).thenReturn(Success(Some(box)))
    }
    indexed.foreach { case (id, tx) =>
      when(api.indexedTransactionById(id)).thenReturn(Success(Some(tx)))
    }

    RepairChain(api, QuarantineFault(rollupId, genesisHeight, collateralId,
      "prior indexed decode failure", retryable = true), genesisOutputId, firstSpend, tipId, boxes, indexed)
  }

  private def indexedTransaction(tx: BlockTx,
                                 height: Int,
                                 inputs: Seq[IndexedBox],
                                 blockId: String): IndexedTransaction = {
    val withProofs = inputs.zip(tx.inputs).map { case (box, in) =>
      box.copy(spendingProof = in.spendingProof.map(p => NodeSpendingProof(p.proof, p.ext)))
    }
    IndexedTransaction(tx.id, withProofs, Seq.empty,
      tx.outputs.map(out => indexedOutput(out, height, None, None, None)),
      height, 10, blockId, 0L, 0, 0L, 0)
  }

  private def indexedOutput(out: TxOutput,
                            inclusionHeight: Int,
                            spentBy: Option[String],
                            spendingHeight: Option[Int],
                            proof: Option[InputSpendingProof]): IndexedBox =
    IndexedBox(
      NodeBox(out.id, out.txId, out.value, out.index, out.creationHeight, out.ergoTree,
        out.assets.map(token => NodeAsset(token.id.toString, token.amount)),
        NodeRegisters(out.registers.zipWithIndex.map { case (value, index) =>
          s"R${index + 4}" -> value
        }.toMap)),
      "", inclusionHeight, 0L, spentBy, spendingHeight,
      proof.map(value => NodeSpendingProof(value.proof, value.ext)))
}
