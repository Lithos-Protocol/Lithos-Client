package support

import lfsm.states.{PlasmaDictionary, RollupInfoState, RollupMetadata}
import nisp.NispCommitment
import node.NodeApi
import node.model._
import org.bouncycastle.util.encoders.Hex
import org.mockito.Mockito.when
import scorex.crypto.hash.Blake2b256
import scorex.utils.Longs
import state.messages.{BlockTx, TxOutput}

import scala.util.Success

object NispHistoryFixtures {
  final case class Chain(api: NodeApi, target: RollupMetadata, origin: String,
                         requested: Map[String, NispCommitment], raw: Map[String, Array[Byte]],
                         boxes: Map[String, IndexedBox], transactions: Vector[IndexedTransaction])

  def chain(miners: Int = 2, payloadSize: Int = 8000): Chain = {
    val origin = SyncFixtures.id(980000)
    val rollupId = ChainFixtures.headerId(100)
    val genesis = ReducerFixtures.genesisTx(980, origin, SyncFixtures.id(980001), 100)
    val collateral = IndexedBox(NodeBox(origin, SyncFixtures.id(979999), 1000000L, 0, 90,
      ReducerFixtures.CollateralTree, Seq(NodeAsset(ReducerFixtures.CollateralToken.toString, 1))), "", 90, 0)
    val dictionary = PlasmaDictionary.empty()
    var prior = genesis.outputs.head.id
    var held = ReducerFixtures.RollupValue
    var bond = 0L
    var score = BigInt(0)
    var raw = Map.empty[String, Array[Byte]]
    var steps = Vector((genesis, 100))
    (0 until miners).foreach { index =>
      val key = Blake2b256.hash(s"nisp-history-miner-$index")
      val payload = Longs.toByteArray(index.toLong + 1) ++ Array.fill[Byte](payloadSize - 8)(index.toByte)
      raw += Hex.toHexString(key) -> payload
      val inserted = dictionary.insert(key -> NispCommitment.fromPayload(payload).bytes)
      score += index + 1
      val next = SyncFixtures.id(981000 + index)
      val tx = ReducerFixtures.submissionTx(981 + index, prior, next, 101, key, payload,
        inserted.proof.ergoValue.toHex, dictionary, index + 1, score, 100L,
        inputValue = held, inputBond = bond, nft = origin)
      bond += ReducerFixtures.bondFor(payload)
      held = ReducerFixtures.RollupValue + bond
      steps :+= tx -> 101
      prior = next
    }
    val boundary = SyncFixtures.id(989999)
    val evaluation = ReducerFixtures.holdingToEvaluationTx(9899, prior, boundary, 460,
      dictionary, miners, score, 460L, nispBlock = 100L, bond = bond, value = held, nft = origin)
    steps :+= evaluation -> 460

    def output(out: TxOutput, height: Int): IndexedBox = IndexedBox(NodeBox(out.id, out.txId, out.value,
      out.index, out.creationHeight, out.ergoTree,
      out.assets.map(t => NodeAsset(t.id.toString, t.amount)),
      NodeRegisters(out.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v }.toMap)), "", height, 0)

    var boxes = Map(origin -> collateral) ++ steps.flatMap { case (tx, height) =>
      tx.outputs.map(out => out.id -> output(out, height))
    }
    steps.foreach { case (tx, height) =>
      val key = tx.inputs.head.id
      boxes += key -> boxes(key).copy(spentTransactionId = Some(tx.id), spendingHeight = Some(height))
    }
    val indexed = steps.map { case (tx, height) =>
      val input = boxes(tx.inputs.head.id).copy(spendingProof = tx.inputs.head.spendingProof.map(p =>
        NodeSpendingProof("", p.ext)))
      IndexedTransaction(tx.id, Seq(input), Seq.empty, tx.outputs.map(out => boxes(out.id)),
        height, 1, ChainFixtures.headerId(height), 0L, 0, 0L, 0)
    }
    val api = ChainFixtures.nodeAt(460)
    boxes.foreach { case (id, box) => when(api.indexedBoxById(id)).thenReturn(Success(Some(box))) }
    indexed.foreach(tx => when(api.indexedTransactionById(tx.id)).thenReturn(Success(Some(tx))))
    val state = RollupInfoState.evaluation(460L, 100L, bond)
    val target = RollupMetadata(miners, score, state, held, 100, hasMiner = false, evaluated = false,
      rollupId, boundary, Hex.toHexString(dictionary.digest))
    Chain(api, target, origin, raw.map { case (k, v) => k -> NispCommitment.fromPayload(v) }, raw, boxes, indexed)
  }
}
