package stats

import lfsm.states.{PlasmaDictionary, RollupInfoState}
import nisp.NispCommitment
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import state.messages.{BlockTx, InputSpendingProof, TxInput, TxOutput}
import support.{ReducerFixtures, SyncFixtures}
import work.lithos.mutations.{Contract, Token}

class MiningAccountingSpec extends AnyFlatSpec with Matchers {
  private val local = Contract.SIGMA_TRUE
  private val other = Contract.SIGMA_FALSE
  private val nft = ErgoId.create(SyncFixtures.id(800000))
  private val lit = ErgoId.create("ab" * 32)
  private val dictionary = PlasmaDictionary.empty()
  dictionary.insert(local.hashedPropBytes -> NispCommitment(100, "00" * 32).bytes,
    other.hashedPropBytes -> NispCommitment(200, "11" * 32).bytes)
  private val proof = dictionary.lookUp(local.hashedPropBytes, other.hashedPropBytes)
  private val at = MiningStatsStoreSpec.cursor(100)
  private val mined = LithosBlockRecord("mined", 50, 50000, "genesis", "holding", nft.toString, "1", "1", "0")
  private val input = TxOutput("payout", 14000001L, "payout-tree",
    ReducerFixtures.rollupRegisters(dictionary, 2, BigInt(300), RollupInfoState.payout(10000001L, 101, 4000000)),
    Seq(Token(nft, 1), Token(lit, 101)), "previous", 99, 0)
  private val keys = Array(local.hashedPropBytes, other.hashedPropBytes)
  private val extension = Map("0" -> ErgoValue.of(keys.map(Colls.fromArray(_)), ErgoType.collType(scalaByteType)).toHex,
    "1" -> proof.proof.ergoValue.toHex)
  private val outputs = Seq(
    TxOutput("local-paid", 5333333L, local.ergoTreeHex, Seq.empty, Seq(Token(lit, 33)), "tx", 100, 0),
    TxOutput("other-paid", 8666672L, other.ergoTreeHex, Seq.empty, Seq(Token(lit, 70)), "tx", 100, 1))
  private val tx = BlockTx("tx", Seq(TxInput(input.id, Some(InputSpendingProof(extension)))), Seq.empty, outputs)

  "Payout accounting" should "verify compact lookup proofs and separate floor rewards, bonds, final surplus and LIT" in {
    val payments = MiningAccounting.payments(tx, input, at, mined, local.hashedPropBytesHex, local.ergoTreeHex)
    payments.map(_.local) shouldBe Vector(true, false)
    val amounts = payments.map(_.amounts.get)
    amounts.map(_.rewardNanoErg) shouldBe Vector("3333333", "6666667")
    amounts.map(_.bondNanoErg) shouldBe Vector("2000000", "2000000")
    amounts.map(_.surplusNanoErg) shouldBe Vector("0", "5")
    amounts.map(_.rewardLit) shouldBe Vector("33", "67")
    amounts.map(_.receivedLit) shouldBe Vector("33", "70")
    // Every share carries the whole payout it was cut from, and when the rollup was mined.
    amounts.map(a => (a.rollupScore, a.rollupMiners, a.rollupRewardNanoErg, a.rollupRewardLit)).distinct shouldBe
      Vector(("300", 2, "10000001", "101"))
    payments.map(_.minedTimestamp).distinct shouldBe Vector(mined.timestamp)
    val record = MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "123", Vector.empty,
      payments.filter(_.local), MiningActivity(networkPayments = payments))
    val totals = MiningTotals().include(record, 1)
    totals.accounting.amount("network.payout.rewardNanoErg") shouldBe BigInt(10000000)
    totals.accounting.amount("local.payout.bondNanoErg") shouldBe BigInt(2000000)
    totals.include(record, -1) shouldBe MiningTotals()
  }

  it should "reject a proof for a different digest, underpayment, wrong output owner and duplicate keys" in {
    val emptyRegisters = input.registers.updated(0, PlasmaDictionary.empty().ergoValue.toHex)
    intercept[Exception](MiningAccounting.payments(tx, input.copy(registers = emptyRegisters), at, mined,
      local.hashedPropBytesHex, local.ergoTreeHex))
    val underpaid = tx.copy(outputs = outputs.updated(0, outputs.head.copy(value = 5333332L)))
    intercept[IllegalArgumentException](MiningAccounting.payments(underpaid, input, at, mined,
      local.hashedPropBytesHex, local.ergoTreeHex))
    val wrongOwner = tx.copy(outputs = outputs.updated(0, outputs.head.copy(ergoTree = other.ergoTreeHex)))
    intercept[IllegalArgumentException](MiningAccounting.payments(wrongOwner, input, at, mined,
      local.hashedPropBytesHex, local.ergoTreeHex))
    val repeated = ErgoValue.of(Array.fill(2)(Colls.fromArray(local.hashedPropBytes)), ErgoType.collType(scalaByteType)).toHex
    val duplicate = tx.copy(inputs = Seq(TxInput(input.id, Some(InputSpendingProof(extension.updated("0", repeated))))))
    intercept[IllegalArgumentException](MiningAccounting.payments(duplicate, input, at, mined,
      local.hashedPropBytesHex, local.ergoTreeHex))
  }

  "Rollup accounting" should "separate top-ups, participation and confirmed bond slashing" in {
    val protocol = ReducerFixtures.protocol().copy(localMinerHash = local.hashedPropBytes)
    def box(tree: String, count: Int, score: Long, bond: Long, value: Long) = input.copy(ergoTree = tree,
      value = value, registers = ReducerFixtures.rollupRegisters(dictionary, count, BigInt(score),
        RollupInfoState.holding(60, 50, bond)))
    val holding = box(protocol.holdingErgoTree, 2, 300, 4000000, 14000001)
    val topup = tx.copy(outputs = Seq(holding.copy(value = 15000001)))
    RollupStatistics.read(topup, holding, mined, protocol, None, local.ergoTreeHex).get.valueNanoErg shouldBe "1000000"
    val transition = tx.copy(outputs = Seq(holding.copy(ergoTree = protocol.evaluationErgoTree)))
    val evaluation = RollupStatistics.read(transition, holding, mined, protocol, None, local.ergoTreeHex).get
    evaluation.miners shouldBe 2
    evaluation.claimedScore shouldBe "300"
    val before = holding.copy(ergoTree = protocol.evaluationErgoTree)
    val after = box(protocol.evaluationErgoTree, 1, 200, 2000000, 12000001)
    val fraud = tx.copy(inputs = Seq(
      TxInput(before.id, Some(InputSpendingProof(Map("0" -> ErgoValue.of(local.valueBytes).toHex)))),
      TxInput("prover", Some(InputSpendingProof(Map("0" -> ErgoValue.of(local.hashedPropBytes).toHex))))),
      outputs = Seq(after))
    val result = RollupStatistics.read(fraud, before, mined, protocol, Some(local.ergoTreeHex), local.ergoTreeHex).get
    result.kind shouldBe "fraudProof"
    result.bondNanoErg shouldBe "2000000"
    result.claimedScore shouldBe "100"
    result.minerHash shouldBe Some(local.hashedPropBytesHex)
    result.local shouldBe true
    result.proofValueHash shouldBe Some(Hex.toHexString(local.hashedValueBytes))
    result.proofValueHash should not be Some(local.hashedPropBytesHex)
    // The removed entry is this client's own, so the slash is booked against it as well.
    result.localTarget shouldBe true
    val slashed = MiningAccounting.contribution(MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "1",
      Vector.empty, Vector.empty, MiningActivity(rollups = Vector(result))))
    slashed.amount("local.slashed.count") shouldBe BigInt(1)
    slashed.amount("local.slashed.bondNanoErg") shouldBe BigInt(2000000)
    slashed.amount("rollup.fraudProof.localBondNanoErg") shouldBe BigInt(2000000)
    val proverOnly = RollupStatistics.read(fraud.copy(inputs = fraud.inputs.updated(1,
      TxInput("prover", Some(InputSpendingProof(Map("0" -> ErgoValue.of(other.hashedPropBytes).toHex)))))),
      before, mined, protocol, Some(local.ergoTreeHex), local.ergoTreeHex).get
    proverOnly.local shouldBe true
    proverOnly.localTarget shouldBe false
  }

  "Payout readiness" should "carry the LIT the whole rollup distributes" in {
    val protocol = ReducerFixtures.protocol()
    val evaluation = input.copy(ergoTree = protocol.evaluationErgoTree, registers =
      ReducerFixtures.rollupRegisters(dictionary, 2, BigInt(300), RollupInfoState.evaluation(60, 50, 4000000)))
    val ready = tx.copy(outputs = Seq(input.copy(ergoTree = protocol.payoutErgoTree)))
    val event = RollupStatistics.read(ready, evaluation, mined, protocol, None, local.ergoTreeHex).get
    event.kind shouldBe "payoutReady"
    event.valueNanoErg shouldBe "10000001"
    event.rewardLit shouldBe Some("101")
    event.minedHeight shouldBe mined.height
  }

  "Block accounting" should "keep whole-network fees separate from Lithos-block fees and count work once per block" in {
    val genesis = mined.copy(blockId = at.blockId, height = at.height, timestamp = at.timestamp)
    val batch = BatchingFee("batch", "order", "ergodex", "100", "3")
    val record = MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "999", Vector.empty,
      Vector.empty, MiningActivity(batchingFees = Vector(batch)), transactionFeesNanoErg = "7")
    val network = MiningAccounting.contribution(record)
    network.amount("chain.transactionFeeNanoErg") shouldBe BigInt(7)
    network.amount("batching.ergodex.grossNanoErg") shouldBe BigInt(100)
    network.amount("lithos.transactionFeeNanoErg") shouldBe BigInt(0)
    val lithos = record.copy(blocks = Vector(genesis, genesis.copy(transactionId = "another-genesis")))
    val totals = MiningAccounting.contribution(lithos)
    totals.amount("lithos.transactionFeeNanoErg") shouldBe BigInt(7)
    totals.amount("lithos.batching.ergodex.afterTransactionFeesNanoErg") shouldBe BigInt(97)
    totals.amount("lithos.blocks") shouldBe BigInt(1)
    totals.amount("lithos.difficultySum") shouldBe BigInt(999)
    MiningTotals().include(lithos, 1).blocks shouldBe 1
  }

  it should "pool fee-less executions in Lithos blocks and keep only this client's fee-paying ones as its own" in {
    val genesis = mined.copy(blockId = at.blockId, height = at.height, timestamp = at.timestamp)
    def totals(inLithos: Boolean, fees: BatchingFee*) = MiningAccounting.contribution(MiningBlockRecord(at,
      MiningStatsStoreSpec.cursor(99), "1", if (inLithos) Vector(genesis) else Vector.empty, Vector.empty,
      MiningActivity(batchingFees = fees.toVector)))
    val candidate = BatchingFee("c", "o1", "lithosdex", "6000000", "0", local = true)
    val broadcast = BatchingFee("b", "o2", "lithosdex", "6000000", "1000000", local = true)
    val stranger = BatchingFee("s", "o3", "ergodex", "4000000", "1100000")

    val lithos = totals(inLithos = true, candidate, broadcast, stranger)
    lithos.amount("pool.batching.lithosdex.orders") shouldBe BigInt(1)
    lithos.amount("pool.batching.lithosdex.grossNanoErg") shouldBe BigInt(6000000)
    // Mined into someone's Lithos block from the mempool, it paid a fee and its takings stayed ours.
    lithos.amount("local.batching.lithosdex.afterTransactionFeesNanoErg") shouldBe BigInt(5000000)
    lithos.amount("local.batching.ergodex.orders") shouldBe BigInt(0)
    lithos.amount("pool.batching.ergodex.orders") shouldBe BigInt(0)

    // Outside a Lithos block nothing is pooled, including a fee-less execution in a block of our own.
    val plain = totals(inLithos = false, candidate, stranger)
    plain.amount("pool.batching.lithosdex.orders") shouldBe BigInt(0)
    plain.amount("local.batching.lithosdex.afterTransactionFeesNanoErg") shouldBe BigInt(6000000)
  }

  it should "total kept tokens by verified id, count the rest, and credit this client only what reached it" in {
    val genesis = mined.copy(blockId = at.blockId, height = at.height, timestamp = at.timestamp)
    val lit = "c1" * 32
    val record = MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "1", Vector(genesis), Vector.empty,
      MiningActivity(batchingFees = Vector(
        BatchingFee("own", "acc", "forwards", "0", "0", local = true, Seq(TokenAmount(lit, "5000000000")), 1),
        BatchingFee("theirs", "acc2", "forwards", "0", "0", tokens = Seq(TokenAmount(lit, "5000000000"))))))
    val totals = MiningAccounting.contribution(record)
    totals.amount(s"batching.forwards.tokens.$lit") shouldBe BigInt(10000000000L)
    totals.amount("batching.forwards.otherTokens") shouldBe BigInt(1)
    totals.amount(s"local.received.forwards.tokens.$lit") shouldBe BigInt(5000000000L)
    totals.amount("local.received.forwards.otherTokens") shouldBe BigInt(1)
    // Tokens never top up the rollup, so nothing about them is pooled.
    totals.values.keys.filter(k => k.startsWith("pool.") && k.contains("tokens")) shouldBe empty
    // Removing the record takes away exactly what adding it gave.
    MiningTotals().include(record, 1).include(record, -1).accounting.values shouldBe empty
  }

  it should "keep a fee above its gross apart, so a recording paid from the wallet leaves no key below zero" in {
    val record = MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "1", Vector.empty, Vector.empty,
      MiningActivity(batchingFees = Vector(BatchingFee("rec", "acc", "forwards", "0", "1000000", local = true))))
    val totals = MiningAccounting.contribution(record)
    totals.amount("local.batching.forwards.afterTransactionFeesNanoErg") shouldBe BigInt(0)
    totals.amount("local.batching.forwards.feeAboveGrossNanoErg") shouldBe BigInt(1000000)
    totals.amount("batching.forwards.transactionFeeNanoErg") shouldBe BigInt(1000000)
  }

  "Lender returns" should "total each selection path apart, and pool its APR over the ERG-blocks locked" in {
    val genesis = mined.copy(blockId = at.blockId, height = at.height, timestamp = at.timestamp)
    // Joined 1,000 blocks before this one, so the queue box's ERG is locked for 1,720 blocks
    val bid = genesis.copy(selection = Some(CollateralSelection("bid", at.height - 1000, at.height - 10,
      "3000000000", "3050000000")))
    val record = MiningBlockRecord(at, MiningStatsStoreSpec.cursor(99), "1", Vector(bid), Vector.empty)
    val totals = MiningAccounting.contribution(record)
    totals.amount("collateral.bid.boxes") shouldBe BigInt(1)
    totals.amount("collateral.bid.waitBlocks") shouldBe BigInt(1000)
    totals.amount("collateral.bid.capitalNanoErgBlocks") shouldBe BigInt(3000000000L) * 1720
    totals.values.keys.exists(_.startsWith("collateral.clearance")) shouldBe false
    MiningTotals().include(record, 1).include(record, -1).accounting.values shouldBe empty

    val stats = CollateralSelectionStats.of(totals, Some(1))
    stats.coveredBlocks shouldBe 1
    stats.bid.netNanoErg shouldBe "50000000"
    // 0.05 ERG over 3 ERG for 1,720 blocks, at 262,800 blocks a year
    stats.bid.aprPercent shouldBe Some(254.65)
    stats.clearance.aprPercent shouldBe None
    stats.clearance.meanWaitBlocks shouldBe None

    withClue("a record from before selections parses without one, and counts toward neither path: ") {
      import play.api.libs.json.Json
      import MiningStatsData.blockFormat
      (Json.toJsObject(genesis) - "selection").as[LithosBlockRecord] shouldBe genesis
      CollateralSelectionStats.of(MiningAccounting.contribution(record.copy(blocks = Vector(genesis))), None)
        .coveredBlocks shouldBe 0
    }
  }

  "A stored execution" should "parse without its token fields, as executions recorded before them were stored" in {
    import play.api.libs.json.Json
    import MiningStatsData.batchingFeeFormat
    val stored = Json.obj("transactionId" -> "t", "orderId" -> "o", "protocol" -> "ergodex", "grossNanoErg" -> "5",
      "transactionFeeNanoErg" -> "1", "local" -> true)
    stored.as[BatchingFee] shouldBe BatchingFee("t", "o", "ergodex", "5", "1", local = true)
    val withTokens = BatchingFee("t", "o", "forwards", "0", "0", tokens = Seq(TokenAmount("c1" * 32, "5")), otherTokens = 2)
    Json.toJson(withTokens).as[BatchingFee] shouldBe withTokens
  }
}
