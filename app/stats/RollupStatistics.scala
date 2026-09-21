package stats

import lfsm.RollupProtocol
import nisp.NispCommitment
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoValue
import scorex.crypto.hash.Blake2b256
import sigma.Coll
import state.messages.{BlockTx, TxOutput}
import state.synchronization.SyncProtocolContext

/** Reads confirmed register transitions after the rollup NFT's collateral origin is authenticated. */
private[stats] object RollupStatistics {
  private def miners(box: TxOutput): Int = ErgoValue.fromHex(box.registers(1)).getValue.asInstanceOf[Int]
  private def score(box: TxOutput): BigInt =
    BigInt(ErgoValue.fromHex(box.registers(2)).getValue.asInstanceOf[sigma.data.CBigInt].wrappedValue)
  private def state(box: TxOutput): Vector[Long] = {
    val values = ErgoValue.fromHex(box.registers(3)).getValue.asInstanceOf[Coll[Long]].toArray.toVector
    require(values.size == 3 && values(2) >= 0, "invalid rollup statistics state")
    values
  }
  private def bytes(value: String): Array[Byte] = ErgoValue.fromHex(value).getValue.asInstanceOf[Coll[Byte]].toArray

  def read(tx: BlockTx, input: TxOutput, mined: LithosBlockRecord,
              protocol: SyncProtocolContext, proverTree: Option[String], minerTree: String): Option[RollupActivity] = {
    val nft = RollupProtocol.rollupNFT(input.assets).id.toString
    val next = tx.outputs.headOption.filter(out => out.assets.headOption.exists(t => t.id.toString == nft && t.amount == 1L))
    next.flatMap { out =>
      def event(kind: String, count: Int, claimed: BigInt, bond: BigInt, value: BigInt,
                   key: Option[String] = None, proof: Option[String] = None, local: Boolean = false) = {
        require(count >= 0 && claimed >= 0 && bond >= 0 && value >= 0, "invalid rollup contribution")
        Some(RollupActivity(tx.id, nft, mined.blockId, kind, count, claimed.toString, bond.toString,
          value.toString, key, proof, local))
      }
      val before = state(input)
      if (input.ergoTree == protocol.holdingErgoTree && out.ergoTree == protocol.holdingErgoTree) {
        val after = state(out)
        if (miners(out) == miners(input) && score(out) == score(input) && after == before &&
          out.registers(0) == input.registers(0) && out.value > input.value)
          event("topUp", 0, 0, 0, BigInt(out.value) - input.value)
        else {
          val ext = tx.inputs.head.spendingProof.getOrElse(throw new IllegalArgumentException("submission extension absent")).ext
          val pair = ErgoValue.fromHex(ext("1")).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
          val key = Hex.toHexString(pair._1.toArray)
          val claimed = NispCommitment.fromPayload(pair._2.toArray).score
          val bond = RollupProtocol.bondForScore(claimed)
          require(miners(out) == miners(input) + 1 && score(out) - score(input) == claimed &&
            after(2) - before(2) == bond && BigInt(out.value) - input.value == bond,
            "submission register deltas differ")
          event("submission", 1, BigInt(claimed), BigInt(bond), 0, Some(key),
            local = key == Hex.toHexString(protocol.localMinerHash))
        }
      } else if (input.ergoTree == protocol.holdingErgoTree && out.ergoTree == protocol.evaluationErgoTree) {
        event("evaluation", miners(out), score(out), BigInt(state(out)(2)), BigInt(out.value))
      } else if (input.ergoTree == protocol.evaluationErgoTree && out.ergoTree == protocol.evaluationErgoTree) {
        val claimed = score(input) - score(out)
        require(claimed.isValidLong && claimed > 0 && miners(out) == miners(input) - 1, "invalid fraud score delta")
        val bond = BigInt(RollupProtocol.bondForScore(claimed.toLong))
        require(BigInt(before(2)) - state(out)(2) == bond && BigInt(input.value) - out.value == bond,
          "fraud bond deltas differ")
        val key = Hex.toHexString(bytes(tx.inputs(1).spendingProof.get.ext("0")))
        val script = bytes(tx.inputs.head.spendingProof.get.ext("0"))
        event("fraudProof", 1, claimed, bond, bond, Some(key), Some(Hex.toHexString(Blake2b256.hash(script))),
          local = proverTree.contains(minerTree))
      } else if (input.ergoTree == protocol.evaluationErgoTree && out.ergoTree == protocol.payoutErgoTree) {
        val after = state(out)
        require(BigInt(out.value) == BigInt(after(0)) + after(2), "payout reward includes its bonds")
        event("payoutReady", miners(out), score(out), BigInt(after(2)), BigInt(after(0)))
      } else None
    }
  }
}
