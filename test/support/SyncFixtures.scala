package support

import lfsm.LFSMPhase
import lfsm.states.NISPTree
import sigma.data.AvlTreeFlags
import state.messages.RollupMessages.{Genesis, RollupTransform}
import state.messages.{BlockInfo, BlockTx, TxInput, TxOutput}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

object SyncFixtures {

  def id(number: Int): String = f"$number%064x"

  def emptyNispTree(blockId: String, utxoId: String, startHeight: Int): NISPTree =
    NISPTree(
      dictionary = PlasmaMap[Array[Byte], Array[Byte]](
        AvlTreeFlags.AllOperationsAllowed,
        PlasmaParameters.default),
      numMiners = 0,
      totalScore = BigInt(0),
      currentPeriod = Some(startHeight.toLong),
      totalReward = 0L,
      startHeight = startHeight,
      hasMiner = false,
      phase = LFSMPhase.HOLDING,
      blockId = blockId,
      utxoId = utxoId)

  def genesis(blockId: String, utxoId: String, height: Int): Genesis =
    Genesis(emptyNispTree(blockId, utxoId, height), BlockInfo(blockId, height, Seq.empty))

  def rollupTransform(number: Int, height: Int, inputId: String, outputId: String): RollupTransform = {
    val txId = id(10000 + number)
    val output = TxOutput(
      id = outputId,
      value = 0L,
      ergoTree = "",
      registers = Seq.empty,
      assets = Seq.empty,
      txId = txId,
      creationHeight = height,
      index = 0)
    val tx = BlockTx(txId, Seq(TxInput(inputId, None)), Seq.empty, Seq(output))
    RollupTransform(BlockInfo(id(20000 + number), height, Seq(tx)), tx)
  }

  def plasmaEntries(count: Int, valueSize: Int): Seq[(Array[Byte], Array[Byte])] =
    (0 until count).map { index =>
      // BatchAVLProver reserves the all-zero boundary key, so fixture keys start at one.
      val keyNumber = index + 1
      val key = Array.fill[Byte](32)(0)
      key(28) = (keyNumber >>> 24).toByte
      key(29) = (keyNumber >>> 16).toByte
      key(30) = (keyNumber >>> 8).toByte
      key(31) = keyNumber.toByte
      val value = Array.tabulate[Byte](valueSize)(offset => ((index + offset) & 0xff).toByte)
      key -> value
    }
}
