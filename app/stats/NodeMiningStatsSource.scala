package stats

import com.google.gson.JsonElement
import configs.{MiningStatsConfig, NodeContext}
import lfsm.RollupProtocol
import node.NodeApi
import node.model.{NodeHeader, Paging}
import node.rest.{NodeHttp, NodeHttpConfig, RestNodeApi}
import okhttp3.OkHttpClient
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoValue
import sigma.Coll
import state.messages.{BlockInfo, BlockTx, NodeSync, TxOutput}
import state.synchronization.{BlockReducer, CanonicalBlockSource, NodeHeights, SyncProtocolContext}

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.collection.mutable
import scala.util.{Failure, Try}

trait MiningStatsSource {
  def heights: NodeHeights
  def header(height: Int): MiningCursor
  def read(header: MiningCursor, previous: MiningCursor): MiningBlockRecord
  def collateral(at: MiningCursor): Option[CollateralStats] = None
  /** One header's difficulty, which the epoch table reads at boundaries instead of every block. */
  def sample(height: Int): DifficultySample
}

object NodeMiningStatsSource {
  // Builders share OkHttp's connection pool and dispatcher across polling cycles.
  private val sharedClient = new OkHttpClient()
  def open(node: NodeContext, settings: MiningStatsConfig, protocol: SyncProtocolContext): MiningStatsSource = {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.readBudgetMs)
    val config = NodeHttpConfig(node.getNodeUrl, Option(node.getNodeKey).filter(_.nonEmpty),
      connectTimeoutMs = settings.readTimeoutMs, readTimeoutMs = settings.readTimeoutMs,
      callTimeoutMs = settings.readTimeoutMs, maxResponseBytes = 8 * 1024 * 1024)
    val client = sharedClient.newBuilder().connectTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
      .readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
      .callTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS).build()
    def bounded[A](read: => Try[A]): Try[A] =
      if (System.nanoTime() >= deadline) Failure(new TimeoutException("mining statistics read budget exceeded")) else read
    val api = new RestNodeApi(new NodeHttp(config, client) {
      override def getJson(path: String, params: Seq[(String, String)]): Try[JsonElement] = bounded(super.getJson(path, params))
      override def getJsonOpt(path: String, params: Seq[(String, String)]): Try[Option[JsonElement]] = bounded(super.getJsonOpt(path, params))
      override def postJson(path: String, body: String, params: Seq[(String, String)]): Try[JsonElement] = bounded(super.postJson(path, body, params))
    })
    new NodeMiningStatsSource(api, protocol, node.getNodeWallet.contract.ergoTreeHex)
  }
  def cursor(header: NodeHeader): MiningCursor = MiningCursor(header.height, header.id, header.parentId, header.timestamp)
}

/** Uses indexed inputs and the canonical header chain, independently of live synchronization state. */
class NodeMiningStatsSource(api: NodeApi, protocol: SyncProtocolContext, minerTree: String) extends MiningStatsSource {
  import NodeMiningStatsSource.cursor
  private val chain = new CanonicalBlockSource(api)
  private val origins = mutable.Map.empty[String, Option[LithosBlockRecord]]
  override def heights: NodeHeights = chain.heights.get
  override def header(height: Int): MiningCursor = cursor(chain.headerAt(height).get)

  override def sample(height: Int): DifficultySample = {
    val header = chain.headerAt(height).get
    DifficultySample(header.height, header.timestamp, header.difficulty.toString)
  }

  override def collateral(at: MiningCursor): Option[CollateralStats] = {
    val observed = System.currentTimeMillis()
    val seen = mutable.Set.empty[String]
    var count = 0
    var value = BigInt(0)
    var complete = false
    var page = 0
    // The priority fee each box carries, recovered from the finder's share named in R4. Collected
    // here because the same pass already has every box, and a second scan for it would double what
    // an inventory read costs the miner's own node.
    val fees = Vector.newBuilder[Long]
    var unreadable = 0
    while (!complete && page < 5) {
      val boxes = api.unspentBoxesByTokenId(protocol.collateralToken.toString, Paging(page * 200, 200)).get
      require(boxes.size <= 200, "collateral page exceeds its limit")
      boxes.foreach { box =>
        require(seen.add(box.boxId), "collateral index pages overlap")
        if (box.ergoTree == protocol.collateralErgoTree &&
          box.assets.exists(t => t.tokenId == protocol.collateralToken.toString && t.amount == 1L)) {
          count += 1
          value += box.value
          RollupProtocol.finderFeeOf(box.box.additionalRegisters.get(4)) match {
            case Some(share) => fees += RollupProtocol.priorityFeeOf(share)
            case None => unreadable += 1
          }
        }
      }
      complete = boxes.size < 200
      page += 1
    }
    val current = heights
    MiningStatsRefresh.requireSameChain(
      current.chain == at.height && current.usable == at.height && header(at.height) == at,
      "chain changed while reading collateral statistics")
    Some(CollateralStats("ready", Some(observed), Some(at), count, value.toString, partial = !complete,
      fees = CollateralFeeStats.of(fees.result(), unreadable)))
  }

  override def read(at: MiningCursor, previous: MiningCursor): MiningBlockRecord = {
    val fullHeader = chain.headerAt(at.height).get
    MiningStatsRefresh.requireSameChain(cursor(fullHeader) == at, "chain changed before reading mining statistics")
    val block = chain.blocksFor(Seq(fullHeader)).get.head
    val blocks = block.txs.flatMap(tx => genesis(block, tx, at)).toVector
    blocks.foreach(b => origins.update(b.collateralBoxId, Some(b)))
    val payments = Vector.newBuilder[MiningPaymentRecord]
    val rollups = Vector.newBuilder[RollupActivity]
    val fees = Vector.newBuilder[MiningTransactionFee]
    val batches = Vector.newBuilder[BatchingFee]
    val registrations = Vector.newBuilder[MinerRegistrationActivity]
    block.txs.foreach { tx =>
      val input = tx.inputs.headOption.flatMap(in => block.inputBox(in.id))
      val registration = input.filter(i => i.assets.exists(t => t.id == protocol.minerDictionaryToken && t.amount == 1L &&
        block.height > protocol.minerDictionaryStartHeight))
        .flatMap(_ => registrationActivity(tx))
      registrations ++= registration
      val rollup = input.filter(box => Set(protocol.holdingErgoTree, protocol.evaluationErgoTree,
        protocol.payoutErgoTree).contains(box.ergoTree)).flatMap { box =>
        origin(lfsm.RollupProtocol.rollupNFT(box.assets).id.toString).map(box -> _)
      }
      val activity = rollup.flatMap { case (box, mined) =>
        if (box.ergoTree == protocol.payoutErgoTree) {
          payments ++= MiningAccounting.payments(tx, box, at, mined,
            Hex.toHexString(protocol.localMinerHash), minerTree)
          None
        } else RollupStatistics.read(tx, box, mined, protocol,
          tx.inputs.lift(1).flatMap(in => block.inputBox(in.id)).map(_.ergoTree), minerTree)
      }
      rollups ++= activity
      val batch = BatchingStatistics.read(block, tx, protocol.networkType)
      batches ++= batch
      val kind = if (blocks.exists(_.transactionId == tx.id)) Some("genesis")
        else activity.map(_.kind).orElse(rollup.map(_ => "payout")).orElse(batch.headOption.map(_.protocol))
          .orElse(registration.map(_ => "registration"))
          .orElse(input.flatMap(BatchingStatistics.poolProtocol(_, protocol.networkType)).map(_ + "OtherPoolSpend"))
      kind.foreach(k => fees += MiningTransactionFee(tx.id, k, BatchingStatistics.transactionFee(tx).toString))
    }
    val networkPayments = payments.result()
    MiningBlockRecord(at, previous, fullHeader.difficulty.toString, blocks, networkPayments.filter(_.local),
      MiningActivity(networkPayments, rollups.result(), fees.result(), batches.result(), registrations.result()),
      block.txs.map(BatchingStatistics.transactionFee).sum.toString)
  }

  /**
   * A dictionary spend that carries no operation is not a registration. Throwing on one instead
   * wedges history at that block, because every refresh cycle retries the block that failed.
   */
  private def registrationActivity(tx: BlockTx): Option[MinerRegistrationActivity] = {
    import lfsm.states.MinerDictionary
    val ext = tx.inputs.head.spendingProof.map(_.ext).getOrElse(Map.empty[String, String])
    val kind = ext.get("0").map(ErgoValue.fromHex(_).getValue.asInstanceOf[Byte]).collect {
      case MinerDictionary.ADD_MINER_OP => "add"
      case MinerDictionary.REMOVE_MINER_OP => "remove"
      case MinerDictionary.EVICT_MINER_OP => "evict"
    }
    kind.flatMap { action =>
      // Match synchronization: R5 of the new data box contains the authenticated miner hash.
      // Registration context slot 1 is an executable value, not serialized proposition bytes.
      val encoded = if (action == "add") Some(tx.outputs(1).registers(1)) else ext.get("5")
      encoded.map { hex =>
        val key = ErgoValue.fromHex(hex).getValue.asInstanceOf[Coll[Byte]].toArray
        require(key.length == 32, "invalid registration miner hash")
        MinerRegistrationActivity(tx.id, action, Hex.toHexString(key), java.util.Arrays.equals(key, protocol.localMinerHash))
      }
    }
  }

  private def genesis(block: BlockInfo, tx: BlockTx, at: MiningCursor): Option[LithosBlockRecord] = {
    val collateral = tx.inputs.headOption.flatMap(in => block.inputBox(in.id)).filter(input =>
      input.ergoTree == protocol.collateralErgoTree &&
        input.assets.exists(t => t.id == protocol.collateralToken && t.amount == 1L))
    if (at.height < protocol.rollupStartHeight || collateral.isEmpty ||
      !tx.outputs.headOption.exists(_.ergoTree == protocol.holdingErgoTree)) None
    else {
      // Reuse synchronization's genesis authentication and register invariants, without replaying dictionaries.
      val replay = new BlockReducer.RollupReplay(protocol)
      replay.apply(block, tx).fold(e => throw new IllegalArgumentException(e.message), identity)
      require(replay.rollup(block.id).isDefined, s"collateral spend ${tx.id} did not create an authenticated genesis")
      // A box the enforcer accepted always names a well-formed fee channel, so an unreadable one
      // means the genesis authentication above passed on something malformed: fail rather than
      // silently record the block as having bid nothing.
      val priorityFee = RollupProtocol.priorityFeeOf(
        RollupProtocol.finderFeeOf(collateral.get.registers.headOption).getOrElse(
          throw new IllegalArgumentException(
            s"collateral box ${collateral.get.id} spent by ${tx.id} has no readable fee channel in R4")))
      Some(LithosBlockRecord(at.blockId, at.height, at.timestamp, tx.id, tx.outputs.head.id,
        collateral.get.id, collateral.get.value.toString, tx.outputs.head.value.toString,
        priorityFee.toString))
    }
  }

  /** The NFT is minted from the spent collateral box; this also resolves origins before our history window. */
  private def origin(nft: String): Option[LithosBlockRecord] = origins.getOrElseUpdate(nft, {
    val collateral = api.indexedBoxById(nft).get.getOrElse(
      throw new IllegalStateException(s"node has no collateral origin for $nft"))
    if (collateral.ergoTree != protocol.collateralErgoTree ||
      !collateral.assets.exists(t => t.tokenId == protocol.collateralToken.toString && t.amount == 1L)) None
    else {
      val txId = collateral.spentTransactionId.getOrElse(throw new IllegalStateException("rollup collateral is unspent"))
      val tx = api.indexedTransactionById(txId).get.getOrElse(throw new IllegalStateException("rollup genesis is unavailable"))
      val at = header(tx.inclusionHeight)
      require(tx.numConfirmations > 0 && tx.blockId == at.blockId, "rollup genesis is not on the canonical chain")
      val inputs = tx.inputs.map(box => box.boxId -> NodeSync.txOutput(box)).toMap
      val info = BlockInfo(at.blockId, at.height, Seq(NodeSync.blockTx(tx)), at.parentId, inputs)
      genesis(info, info.txs.head, at).filter(_.collateralBoxId == nft)
    }
  })

}
