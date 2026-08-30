package state.synchronization

import lfsm.states.PlasmaDictionary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.messages.{BlockInfo, BlockTx, InputSpendingProof, TxInput, TxOutput}
import state.persistence.{SnapshotCheckpoint, SnapshotDictionaryBase, SnapshotDictionarySource,
  StateSnapshotCodec, StateSnapshotIdentity}
import support.{ReducerFixtures, SyncFixtures}

import scala.util.Random

/** Seeded adversarial shapes for total functions that process node-controlled bytes and ordering. */
class SynchronizationTotalityFuzzSpec extends AnyFlatSpec with Matchers {

  private val Seed = 0x5eed51cL

  "MempoolView.buildGraph" should "be invariant under dependency-order permutations" in {
    val random = new Random(Seed)
    val roots = Vector.tabulate(12)(index => SyncFixtures.id(1000000 + index * 100))
    val transactions = roots.zipWithIndex.flatMap { case (root, rootIndex) =>
      (1 to 8).foldLeft((root, Vector.empty[BlockTx])) { case ((input, built), childIndex) =>
        val output = SyncFixtures.id(1000000 + rootIndex * 100 + childIndex)
        output -> (built :+ SyncFixtures.rollupTransform(
          rootIndex * 100 + childIndex, 100, input, output).tx)
      }._2
    }
    val expected = MempoolView.buildGraph(transactions, roots.toSet)

    (1 to 100).foreach { iteration =>
      withClue(s"permutation $iteration: ") {
        MempoolView.buildGraph(random.shuffle(transactions), roots.toSet) shouldEqual expected
      }
    }
  }

  it should "block every reachable cycle or conflict regardless of ordering" in {
    val random = new Random(Seed ^ 0x1111L)
    val root = SyncFixtures.id(1100000)
    val middle = SyncFixtures.id(1100001)
    val first = SyncFixtures.rollupTransform(1, 100, root, middle).tx
    val cycle = SyncFixtures.rollupTransform(2, 100, middle, root).tx
    val conflict = SyncFixtures.rollupTransform(3, 100, root, SyncFixtures.id(1100002)).tx

    (1 to 100).foreach { _ =>
      val graph = MempoolView.buildGraph(random.shuffle(Seq(first, cycle, conflict)), Set(root))
      graph.chains shouldBe empty
      graph.blockedRoots should contain(root)
    }
  }

  "StateSnapshotCodec" should "reject every sampled one-bit metadata corruption and truncation" in {
    val dictionary = PlasmaDictionary.empty()
    val state = ReducerFixtures.emptyState()
    val source = SnapshotDictionarySource(
      org.bouncycastle.util.encoders.Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val checkpoint = SnapshotCheckpoint(CommittedSyncMetadata.from(state), Vector(state.cursor),
      Map(DictionaryId.Miner -> source))
    val identity = StateSnapshotIdentity(ReducerFixtures.protocol())
    val encoded = StateSnapshotCodec.encodeMeta(checkpoint, identity, 64 * 1024 * 1024)
      .toOption.get
    val random = new Random(Seed ^ 0x2222L)

    (1 to 256).foreach { iteration =>
      val corrupt = encoded.clone()
      val byte = random.nextInt(corrupt.length)
      corrupt(byte) = (corrupt(byte) ^ (1 << random.nextInt(8))).toByte
      withClue(s"bit flip $iteration at $byte: ") {
        StateSnapshotCodec.decodeMeta(corrupt, identity).isLeft shouldBe true
      }
    }
    (0 until encoded.length by math.max(1, encoded.length / 64)).foreach { length =>
      withClue(s"truncation at $length: ") {
        StateSnapshotCodec.decodeMeta(encoded.take(length), identity).isLeft shouldBe true
      }
    }
    StateSnapshotCodec.decodeMeta(encoded :+ 0.toByte, identity).isLeft shouldBe true
  }

  "TransformJournal" should "accept only contiguous generated ancestry" in {
    val base = SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(99))
    val valid = (101 to 180).foldLeft(Vector.empty[TransformJournalEntry]) { (entries, height) =>
      val parent = entries.lastOption.map(_.cursor.blockId).getOrElse(base.blockId)
      val cursor = SyncCursor(height, SyncFixtures.id(height), parent)
      val state = ReducerFixtures.emptyState(height, cursor.blockId, height.toLong)
        .copy(cursor = cursor)
      entries :+ TransformJournalEntry(cursor, CommittedSyncMetadata.from(state), Vector.empty)
    }
    TransformJournal(base, valid).entries shouldEqual valid

    val random = new Random(Seed ^ 0x3333L)
    (1 to 100).foreach { iteration =>
      val index = random.nextInt(valid.size)
      val original = valid(index)
      val badCursor = if (iteration % 2 == 0)
        original.cursor.copy(height = original.cursor.height + 1)
      else original.cursor.copy(parentId = SyncFixtures.id(1200000 + iteration))
      val badState = ReducerFixtures.emptyState(badCursor.height, badCursor.blockId,
        badCursor.height.toLong).copy(cursor = badCursor)
      val mutated = valid.updated(index, TransformJournalEntry(badCursor,
        CommittedSyncMetadata.from(badState), Vector.empty))
      withClue(s"ancestry mutation $iteration at $index: ") {
        an[IllegalArgumentException] should be thrownBy TransformJournal(base, mutated)
      }
    }
  }

  "BlockReducer" should "contain randomized malformed rollup shapes as typed outcomes" in {
    val random = new Random(Seed ^ 0x4444L)
    val protocol = ReducerFixtures.protocol()
    val rollupId = SyncFixtures.id(1300000)
    val utxoId = SyncFixtures.id(1300001)
    val dictionary = PlasmaDictionary.empty()
    val base = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, utxoId,
      dictionary)
    val contextValues = Vector("", "00", "ff", "not-hex",
      org.ergoplatform.appkit.ErgoValue.of(7).toHex)

    (1 to 500).foreach { iteration =>
      val proof = if (random.nextBoolean()) None else Some(InputSpendingProof(
        random.alphanumeric.take(random.nextInt(80)).mkString,
        (0 until random.nextInt(8)).map { _ =>
          random.nextInt(8).toString -> contextValues(random.nextInt(contextValues.size))
        }.toMap))
      val outputCount = random.nextInt(4)
      val outputs = Vector.tabulate(outputCount) { outputIndex =>
        val baseOutput = ReducerFixtures.holdingOutput(
          SyncFixtures.id(1310000 + iteration * 4 + outputIndex),
          SyncFixtures.id(1320000 + iteration), 100, dictionary)
        baseOutput.copy(
          ergoTree = if (random.nextBoolean()) baseOutput.ergoTree else random.alphanumeric
            .take(random.nextInt(80)).mkString,
          registers = Vector.fill(random.nextInt(12))(
            contextValues(random.nextInt(contextValues.size))))
      }
      val tx = BlockTx(SyncFixtures.id(1320000 + iteration),
        Seq(TxInput(utxoId, proof)), Seq.empty, outputs)
      val block = BlockInfo(SyncFixtures.id(1330000 + iteration), 100, Seq(tx),
        base.cursor.blockId)

      withClue(s"malformed seed=$Seed iteration=$iteration: ") {
        noException should be thrownBy BlockReducer.applyBlock(base, block, protocol)
      }
    }
  }
}
