package stats

import lfsm.{LFSMPhase, RollupProtocol}
import lfsm.states.RollupInfoState
import nisp.NispCommitment
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoValue
import scorex.crypto.authds.{ADDigest, ADKey, SerializedAdProof}
import scorex.crypto.authds.avltree.batch.{BatchAVLVerifier, Lookup}
import scorex.crypto.hash.{Blake2b256, Digest32}
import sigma.{AvlTree, Coll}
import state.messages.{BlockTx, TxOutput}

/** Amounts owed by the protocol and the actual designated output are deliberately separate. */
final case class PayoutAmounts(score: String, rewardNanoErg: String, bondNanoErg: String,
                                 surplusNanoErg: String, litTokenId: Option[String],
                                 rewardLit: String, receivedLit: String)

/** One confirmed change to an authenticated rollup. Counts refer to entries, not unique people. */
final case class RollupActivity(transactionId: String, rollupNft: String, minedBlockId: String,
                                 kind: String, miners: Int, claimedScore: String, bondNanoErg: String,
                                 valueNanoErg: String, minerHash: Option[String] = None,
                                 proofValueHash: Option[String] = None, local: Boolean = false)

final case class MiningTransactionFee(transactionId: String, kind: String, nanoErg: String)
final case class BatchingFee(transactionId: String, orderId: String, protocol: String,
                               grossNanoErg: String, transactionFeeNanoErg: String)
final case class MinerRegistrationActivity(transactionId: String, kind: String, minerHash: String, local: Boolean)
final case class MiningActivity(networkPayments: Vector[MiningPaymentRecord] = Vector.empty,
                                  rollups: Vector[RollupActivity] = Vector.empty,
                                  transactionFees: Vector[MiningTransactionFee] = Vector.empty,
                                  batchingFees: Vector[BatchingFee] = Vector.empty,
                                  registrations: Vector[MinerRegistrationActivity] = Vector.empty)

/** Exact additive quantities. Keys have a fixed vocabulary defined by contribution(), never miner IDs. */
final case class MiningAccountingTotals(values: Map[String, String] = Map.empty) {
  def amount(name: String): BigInt = BigInt(values.getOrElse(name, "0"))
  def combine(other: MiningAccountingTotals, sign: Int): MiningAccountingTotals = {
    require(sign == 1 || sign == -1, "invalid accounting sign")
    val result = (values.keySet ++ other.values.keySet).map { name =>
      val next = amount(name) + sign * other.amount(name)
      require(next >= 0, s"negative mining statistic: $name")
      name -> next.toString
    }.filterNot(_._2 == "0").toMap
    MiningAccountingTotals(result)
  }
}

object MiningAccounting {
  private def bytes(value: String): Array[Byte] =
    ErgoValue.fromHex(value).getValue.asInstanceOf[Coll[Byte]].toArray

  /** Verify the lookup already carried by the spend; no mutable dictionary or NISP replay is needed. */
  def scores(payout: TxOutput, keys: Vector[Array[Byte]], proof: String): Vector[Long] = {
    val tree = ErgoValue.fromHex(payout.registers(0)).getValue.asInstanceOf[AvlTree]
    require(tree.keyLength == 32 && keys.nonEmpty && keys.size <= 10000, "invalid payout lookup size")
    val verifier = new BatchAVLVerifier[Digest32, Blake2b256.type](ADDigest @@ tree.digest.toArray,
      SerializedAdProof @@ bytes(proof), tree.keyLength, tree.valueLengthOpt,
      maxNumOperations = Some(keys.size))
    keys.map { key =>
      val entry = verifier.performOneOperation(Lookup(ADKey @@ key)).get.getOrElse(
        throw new IllegalArgumentException("payout key is absent from its authenticated dictionary"))
      val score = NispCommitment.decode(entry).score
      require(score > 0, "payout score must be positive")
      score
    }
  }

  def payments(tx: BlockTx, payout: TxOutput, at: MiningCursor, mined: LithosBlockRecord,
                 localHash: String, minerTree: String): Vector[MiningPaymentRecord] = {
    val miners = ErgoValue.fromHex(payout.registers(1)).getValue.asInstanceOf[Int]
    require(miners >= 0, "negative payout miner count")
    if (miners == 0) return Vector.empty
    val ext = tx.inputs.head.spendingProof.getOrElse(
      throw new IllegalArgumentException("payout extension is absent")).ext


    val keys = ErgoValue.fromHex(ext("0")).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(_.toArray).toVector
    require(keys.size <= miners && keys.map(Hex.toHexString).distinct.size == keys.size, "payout keys are not unique")
    val claimed = scores(payout, keys, ext("1"))
    val totalScore = BigInt(ErgoValue.fromHex(payout.registers(2)).getValue.asInstanceOf[sigma.data.CBigInt].wrappedValue)
    require(totalScore > 0 && claimed.map(BigInt(_)).sum <= totalScore, "invalid payout total score")
    val state = RollupInfoState.fromErgoValue(ErgoValue.fromHex(payout.registers(3)), LFSMPhase.PAYOUT)
    require(state.totalErgReward >= 0 && state.totalLitReward >= 0 && state.totalBond >= 0, "invalid payout amounts")

    val nft = RollupProtocol.rollupNFT(payout.assets).id.toString
    val successor = tx.outputs.headOption.exists(out => out.ergoTree == payout.ergoTree &&
      out.assets.headOption.exists(t => t.id.toString == nft && t.amount == 1L))
    val lit = RollupProtocol.litToken(payout.assets).map(_.id.toString)

    keys.zip(claimed).zipWithIndex.map { case ((key, score), index) =>
      val output = tx.outputs(index + (if (successor) 1 else 0))
      val hash = Hex.toHexString(key)
      require(Hex.toHexString(Blake2b256.hash(Hex.decode(output.ergoTree))) == hash,
        "designated payout output does not match its key")
      val local = hash == localHash
      require(!local || output.ergoTree == minerTree, "local payout script differs")
      val reward = BigInt(state.totalErgReward) * score / totalScore
      val bond = BigInt(RollupProtocol.bondForScore(score))
      val surplus = BigInt(output.value) - reward - bond
      val litReward = BigInt(state.totalLitReward) * score / totalScore
      val receivedLit = output.assets.filter(t => lit.contains(t.id.toString)).map(t => BigInt(t.amount)).sum
      require(surplus >= 0 && (!successor || surplus == 0) && receivedLit >= litReward,
        "designated output does not cover the payout")
      MiningPaymentRecord(tx.id, output.id, payout.id, nft, mined.blockId, mined.height,
        at.blockId, at.height, at.timestamp, output.value.toString,
        Some(PayoutAmounts(score.toString, reward.toString, bond.toString, surplus.toString, lit,
          litReward.toString, receivedLit.toString)), Some(hash), local)
    }
  }

  def contribution(record: MiningBlockRecord): MiningAccountingTotals = {
    val values = scala.collection.mutable.Map.empty[String, BigInt]
    def add(name: String, value: BigInt): Unit = {
      require(value >= 0, s"negative mining contribution: $name")
      values.update(name, values.getOrElse(name, BigInt(0)) + value)
    }
    add("chain.blocks", 1)
    add("lithos.blocks", if (record.blocks.nonEmpty) 1 else 0)
    add("chain.difficultySum", BigInt(record.difficulty))
    add("chain.transactionFeeNanoErg", BigInt(record.transactionFeesNanoErg))
    add("lithos.difficultySum", if (record.blocks.nonEmpty) BigInt(record.difficulty) else BigInt(0))
    if (record.blocks.nonEmpty) add("lithos.transactionFeeNanoErg", BigInt(record.transactionFeesNanoErg))
    record.blocks.foreach { b =>
      add("collateral.consumedNanoErg", BigInt(b.collateralNanoErg))
      add("holding.genesisNanoErg", BigInt(b.initialHoldingNanoErg))
      // The finder's own take. The pool's premium is deliberately absent: it is inside the holding
      // value above, and a second key for it would be summed into revenue twice.
      val finderFee = BigInt(b.finderFeeNanoErg)
      add("lithos.finderFeeNanoErg", finderFee)
      if (finderFee > 0) add("lithos.blocksWithFinderFee", 1)
    }
    def payment(p: MiningPaymentRecord, prefix: String): Unit = {
      add(s"$prefix.outputs", 1)
      add(s"$prefix.grossNanoErg", BigInt(p.grossNanoErg))
      p.amounts.foreach { a =>
        add(s"$prefix.rewardNanoErg", BigInt(a.rewardNanoErg))
        add(s"$prefix.bondNanoErg", BigInt(a.bondNanoErg))
        add(s"$prefix.surplusNanoErg", BigInt(a.surplusNanoErg))
        add(s"$prefix.rewardLit", BigInt(a.rewardLit))
        add(s"$prefix.receivedLit", BigInt(a.receivedLit))
      }
    }
    record.payments.foreach(payment(_, "local.payout"))
    record.activity.networkPayments.foreach(payment(_, "network.payout"))
    record.activity.rollups.foreach { e =>
      val prefix = s"rollup.${e.kind}"
      add(s"$prefix.count", 1)
      add(s"$prefix.miners", e.miners)
      add(s"$prefix.claimedScore", BigInt(e.claimedScore))
      add(s"$prefix.bondNanoErg", BigInt(e.bondNanoErg))
      add(s"$prefix.valueNanoErg", BigInt(e.valueNanoErg))
      if (e.local) {
        add(s"$prefix.localCount", 1)
        add(s"$prefix.localValueNanoErg", BigInt(e.valueNanoErg))
      }
    }
    record.activity.transactionFees.foreach { fee =>
      add(s"transaction.${fee.kind}.count", 1)
      add(s"transaction.${fee.kind}.feeNanoErg", BigInt(fee.nanoErg))
    }
    def batching(fee: BatchingFee, scope: String): Unit = {
      val prefix = s"$scope.${fee.protocol}"
      add(s"$prefix.orders", 1)
      add(s"$prefix.grossNanoErg", BigInt(fee.grossNanoErg))
      add(s"$prefix.transactionFeeNanoErg", BigInt(fee.transactionFeeNanoErg))
      add(s"$prefix.afterTransactionFeesNanoErg",
        BigInt(fee.grossNanoErg) - BigInt(fee.transactionFeeNanoErg))
    }
    record.activity.batchingFees.foreach { fee =>
      batching(fee, "batching")
      if (record.blocks.nonEmpty) batching(fee, "lithos.batching")
    }
    record.activity.registrations.foreach { event =>
      add(s"registration.${event.kind}.count", 1)
      if (event.local) add(s"registration.${event.kind}.localCount", 1)
    }
    MiningAccountingTotals(values.map { case (name, value) => name -> value.toString }.toMap)
  }
}
