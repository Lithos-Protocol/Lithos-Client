package stats

import configs.{MiningStatsConfig, NodeContext}
import lfsm.states.{PlasmaDictionary, RollupInfoState}
import node.NodeApi
import node.model._
import okhttp3.mockwebserver.{MockResponse, MockWebServer, SocketPolicy}
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
import state.messages.TxOutput
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}

import java.util.concurrent.TimeUnit
import scala.util.{Success, Try}

class NodeMiningStatsSourceSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val minerTree = work.lithos.mutations.Contract.SIGMA_TRUE.ergoTreeHex
  private val protocol = ReducerFixtures.protocol().copy(localMinerHash =
    scorex.crypto.hash.Blake2b256.hash(org.bouncycastle.util.encoders.Hex.decode(minerTree)))
  private val origin = ReducerFixtures.DefaultOrigin
  private val genesisTx = ReducerFixtures.genesisTx(1, origin, SyncFixtures.id(222), 100)
  private def header(h: Int) = ChainFixtures.header(h).copy(timestamp = h.toLong * 1000, difficulty = BigInt(1) << 80)
  private def indexed(out: TxOutput): IndexedBox = IndexedBox(NodeBox(out.id, out.txId, out.value, out.index,
    out.creationHeight, out.ergoTree, out.assets.map(t => NodeAsset(t.id.toString, t.amount)),
    NodeRegisters(out.registers.zipWithIndex.map { case (value, index) => s"R${index + 4}" -> value }.toMap)),
    "", out.creationHeight, 1L)
  private def transaction(id: String, height: Int, inputs: Seq[IndexedBox], outputs: Seq[IndexedBox]): IndexedTransaction =
    IndexedTransaction(id, inputs, Seq.empty, outputs, height, 1, header(height).id, header(height).timestamp, 0, 1L, 100)
  private val collateral = indexed(ReducerFixtures.resolvedInput(origin, Some(protocol.collateralToken), 100))
    .copy(spentTransactionId = Some(genesisTx.id))
  private val genesis = transaction(genesisTx.id, 100, Seq(collateral), genesisTx.outputs.map(indexed))

  private def payout(finalSpend: Boolean = false, keys: Array[Array[Byte]] = Array(protocol.localMinerHash),
                     miners: Int = 1): IndexedTransaction = {
    val dictionary = PlasmaDictionary.empty()
    keys.foreach(key => dictionary.insert(key -> nisp.NispCommitment(100, "00" * 32).bytes))
    val lookup = dictionary.lookUp(keys: _*)
    val registers = ReducerFixtures.rollupRegisters(dictionary, miners, BigInt(100),
      RollupInfoState.payout(10000000, 0, 2000000))
    val input = indexed(genesisTx.outputs.head.copy(id = SyncFixtures.id(333), ergoTree = protocol.payoutErgoTree,
      registers = registers)).copy(spendingProof = Some(NodeSpendingProof("", Map(
      "0" -> ErgoValue.of(keys.map(Colls.fromArray(_)), ErgoType.collType(scalaByteType)).toHex,
      "1" -> lookup.proof.ergoValue.toHex))))
    val paid = indexed(genesisTx.outputs.head.copy(id = SyncFixtures.id(444), ergoTree = minerTree,
      value = 12000000, assets = Seq.empty, registers = Seq.empty))
    val change = paid.copy(box = paid.box.copy(boxId = SyncFixtures.id(555), value = 50000000))
    val successor = input.copy(box = input.box.copy(boxId = SyncFixtures.id(666)), spendingProof = None)
    transaction(SyncFixtures.id(777), 120, Seq(input),
      (if (finalSpend) Seq.empty else Seq(successor)) ++ Seq(paid, change))
  }

  private def fixture(txs: Seq[IndexedTransaction], at: Int = 120): (NodeApi, NodeMiningStatsSource) = {
    val api = mock[NodeApi]
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(at)))
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(at, at)))
    when(api.chainSlice(any[Option[Int]], any[Option[Int]])).thenAnswer { call =>
      val from = call.getArgument[Option[Int]](0).get + 1
      val to = call.getArgument[Option[Int]](1).get
      Success((from to to).map(header)): Try[Seq[NodeHeader]]
    }
    when(api.indexedBlocksByHeaderIds(any[Seq[String]])).thenReturn(Success(Seq(
      IndexedBlock(header(at), txs, NodeExtension(header(at).id, "", Seq.empty), None, 100))))
    when(api.indexedBoxById(origin)).thenReturn(Success(Some(collateral)))
    when(api.indexedTransactionById(genesis.id)).thenReturn(Success(Some(genesis)))
    (api, new NodeMiningStatsSource(api, protocol, minerTree))
  }
  private def read(source: NodeMiningStatsSource, at: Int = 120): MiningBlockRecord =
    source.read(NodeMiningStatsSource.cursor(header(at)), NodeMiningStatsSource.cursor(header(at - 1)))

  "Canonical mining source" should "authenticate genesis collateral and reuse the reducer's NFT checks" in {
    val (_, source) = fixture(Seq(genesis), 100)
    val result = read(source, 100)
    result.blocks.map(_.collateralBoxId) shouldBe Vector(origin)
    result.difficulty shouldBe (BigInt(1) << 80).toString
    val fake = genesis.copy(inputs = Seq(collateral.copy(box = collateral.box.copy(ergoTree = "unrelated"))))
    read(fixture(Seq(fake), 100)._2, 100).blocks shouldBe empty
    val bad = genesis.copy(outputs = genesis.outputs.map(b => b.copy(box = b.box.copy(assets = Seq.empty))))
    intercept[IllegalArgumentException](read(fixture(Seq(bad), 100)._2, 100))
  }

  it should "resolve a pre-window genesis and count designated payouts without same-address change" in {
    Seq(false, true).foreach { finalSpend =>
      val (api, source) = fixture(Seq(payout(finalSpend)))
      val records = read(source).payments
      records should have size 1
      records.head.grossNanoErg shouldBe "12000000"
      records.head.outputId shouldBe SyncFixtures.id(444)
      records.head.minedHeight shouldBe 100
      records.head.minedBlockId shouldBe header(100).id
      verify(api).indexedTransactionById(genesis.id)
    }
  }

  it should "exclude drains, other miners and script lookalikes without an authenticated NFT origin" in {
    read(fixture(Seq(payout(miners = 0)))._2).payments shouldBe empty
    val otherTree = work.lithos.mutations.Contract.SIGMA_FALSE.ergoTreeHex
    val otherKey = scorex.crypto.hash.Blake2b256.hash(org.bouncycastle.util.encoders.Hex.decode(otherTree))
    val other = payout(keys = Array(otherKey))
    val otherPaid = other.copy(outputs = other.outputs.map(out =>
      if (out.boxId == SyncFixtures.id(444)) out.copy(box = out.box.copy(ergoTree = otherTree)) else out))
    val result = read(fixture(Seq(otherPaid))._2)
    result.payments shouldBe empty
    result.activity.networkPayments should have size 1
    val (api, source) = fixture(Seq(payout()))
    when(api.indexedBoxById(origin)).thenReturn(Success(Some(collateral.copy(box = collateral.box.copy(ergoTree = "fake")))))
    read(source).payments shouldBe empty
    verify(api, never()).indexedTransactionById(anyString())
  }

  it should "fail a mixed-branch origin or missing payout context rather than publish partial income" in {
    val (api, source) = fixture(Seq(payout()))
    when(api.indexedTransactionById(genesis.id)).thenReturn(Success(Some(genesis.copy(blockId = "orphan"))))
    intercept[IllegalArgumentException](read(source)).getMessage should include("canonical")
    val tx = payout()
    val broken = tx.copy(inputs = tx.inputs.map(_.copy(spendingProof = None)))
    intercept[IllegalArgumentException](read(fixture(Seq(broken))._2)).getMessage should include("extension")
  }

  it should "bound an unresponsive node request without invoking an Appkit context" in {
    val server = new MockWebServer()
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    server.start()
    try {
      val node = mock[NodeContext]
      when(node.getNodeUrl).thenReturn(server.url("/").toString)
      when(node.getNodeKey).thenReturn("")
      when(node.getNodeWallet).thenReturn(FakeNodeContext()._3)
      val source = NodeMiningStatsSource.open(node, MiningStatsConfig(readTimeoutMs = 100, readBudgetMs = 1000), protocol)
      val began = System.nanoTime()
      intercept[Exception](source.heights)
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) should be < 3000L
      verify(node, never()).getClient
    } finally server.shutdown()
  }

  "Collateral statistics" should "filter authentic unspent boxes and label a capped index read as partial" in {
    val (api, source) = fixture(Seq.empty)
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(collateral, collateral.copy(box = collateral.box.copy(boxId = "fake", ergoTree = "unrelated")))))
    val inventory = source.collateral(NodeMiningStatsSource.cursor(header(120))).get
    inventory.boxes shouldBe 1
    inventory.nanoErg shouldBe collateral.value.toString
    inventory.partial shouldBe false
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { call =>
        val offset = call.getArgument[Paging](1).offset
        Success((offset until offset + 200).map(i => collateral.copy(box = collateral.box.copy(boxId = s"box-$i")))): Try[Seq[IndexedBox]]
      }
    val capped = source.collateral(NodeMiningStatsSource.cursor(header(120))).get
    capped.boxes shouldBe 1000
    capped.partial shouldBe true
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(121)))
    intercept[IllegalArgumentException](source.collateral(NodeMiningStatsSource.cursor(header(120))))
  }

  "Registration statistics" should "use the authenticated data-box identity rather than hashing executable context bytes" in {
    val tree = lfsm.states.MinerDictionary.initialState
    val miner = work.lithos.mutations.Contract.SIGMA_TRUE
    val add = ReducerFixtures.minerAdd(tree, miner, protocol.minerDictionaryToken,
      SyncFixtures.id(710), SyncFixtures.id(711), 120)
    val input = indexed(ReducerFixtures.dictionaryOutput(tree.utxoId, "previous", 119, tree.dictionary,
      protocol.minerDictionaryToken)).copy(spendingProof = Some(NodeSpendingProof("", add.inputs.head.spendingProof.get.ext)))
    val tx = transaction(add.id, 120, Seq(input), add.outputs.map(indexed))
    val records = read(fixture(Seq(tx))._2).activity.registrations
    records.map(_.minerHash) shouldBe Vector(miner.hashedPropBytesHex)
    records.head.local shouldBe true
    records.head.kind shouldBe "add"
  }
}
