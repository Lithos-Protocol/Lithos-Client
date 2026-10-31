package stats

import configs.VerifiedRegistry
import forwards.DFHelpers
import forwards.contracts.DFTerms
import mutations.NodeWallet
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, NetworkType, Parameters}
import org.scalatest.propspec.AnyPropSpec
import state.messages.{BlockInfo, BlockTx, NodeSync, TxInput, TxOutput}
import transactions.batching.forwards.{DFAccState, DFBoxes, DFStep, ForwardsFixtures, ForwardsTransactions}
import work.lithos.mutations.{Token, UTXO}

import scala.collection.JavaConverters._

/**
 * Forwards spends as the statistics see them in a block. Each spend is signed by the client's own builders against
 * the real accumulator and position scripts, then read back the way `NodeMiningStatsSource` reads a block.
 */
class ForwardsStatisticsSpec extends AnyPropSpec with ForwardsFixtures {

  import _root_.contracts.specs.forwards.DFSpecBase._

  private val BALANCE: Long = 100L * LIT

  /** The fixture deployment's LIT, listed as verified, as a shipped list would list a real token. */
  private val listed: VerifiedRegistry = VerifiedRegistry.parse(NetworkType.TESTNET,
    s"""{"testnet":[{"tokenId":"$litId","name":"Fixture LIT","ticker":"FLIT","decimals":9}]}""",
    """{"testnet":[]}""").toOption.get

  private def atLastBlock[A](f: (BlockchainContext, NodeWallet) => A): A = {
    val client = lastBlockClient(fixtureLastBlock, fixtureNBits)
    val wallet = walletOf(client)
    client.execute(ctx => f(ctx, wallet))
  }

  private def accBox(ctx: BlockchainContext, lastEpoch: Int, balance: Long,
                     ring: IndexedSeq[Long] = flatRing(STRIKE)): (NodeBox, DFAccState) = {
    val box = nodeBox(ctx, accUTXO(AccState(lastEpoch, ring, balance)), 0)
    box -> DFAccState(DFBoxes.accumulator(box, contracts).get, DFBoxes.input(box, ctx).get)
  }

  private def walletFunding(ctx: BlockchainContext, wallet: NodeWallet, value: Long): NodeBox =
    nodeBox(ctx, UTXO(wallet.contract, value), 7, "ef" * 32)

  /** A window of 8 that ended 13 epochs before the fixture's, which the accumulator holds 8% over the strike. */
  private val ended: DFTerms = terms(fixtureEpoch - 20, 8)
  private def ringMoved(lastEpoch: Int): IndexedSeq[Long] = (0 until RING_SIZE).map { i =>
    val e = lastEpoch - (lastEpoch - i) % RING_SIZE
    if (e >= ended.windowStart && e < ended.windowStart + ended.windowLength) STRIKE + STRIKE * 8 / 100 else STRIKE
  }

  /** The signed spend as a block holding it reports it, with `inputs` resolved. */
  private def inBlock(step: DFStep, inputs: Seq[NodeBox]): (BlockInfo, BlockTx) = {
    val id = step.signed.getId.replace("\"", "")
    val outputs = step.signed.getOutputsToSpend.asScala.toVector.zipWithIndex.map { case (b, i) =>
      TxOutput(b.getId.toString, b.getValue, Hex.toHexString(b.getErgoTree.bytes),
        b.getRegisters.asScala.map(_.toHex).toSeq, b.getTokens.asScala.map(Token.ergo).toSeq, id, b.getCreationHeight, i)
    }
    val tx = BlockTx(id, inputs.map(b => TxInput(b.boxId, None)), Seq.empty, outputs)
    BlockInfo("block", fixtureLastBlock + 1, Seq(tx), resolvedInputs = inputs.map(b => b.boxId -> NodeSync.txOutput(b)).toMap) -> tx
  }

  private def read(block: BlockInfo, tx: BlockTx, minerTree: String,
                   registry: VerifiedRegistry = listed): Vector[BatchingFee] =
    BatchingStatistics.read(block, tx, NetworkType.TESTNET, minerTree, Some(contracts), registry)

  property("a recording broadcast alone keeps 5 LIT, moves no ERG, and pays its fee from the wallet") {
    atLastBlock { (ctx, wallet) =>
      val (accNode, acc) = accBox(ctx, fixtureEpoch - 1, BALANCE)
      val funding = walletFunding(ctx, wallet, Parameters.MinFee + UTXO.MIN_CHANGE)
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(DFBoxes.input(funding, ctx).get), Parameters.MinFee)
      val (block, tx) = inBlock(step, Seq(accNode, funding))

      val mine = wallet.contract.ergoTreeHex
      read(block, tx, mine) shouldBe Vector(BatchingFee(tx.id, accNode.boxId, "forwards", "0",
        Parameters.MinFee.toString, local = true, Seq(TokenAmount(litId.toString, DFHelpers.RECORD_REWARD.toString)), 0))
      read(block, tx, "00").map(_.local) shouldBe Vector(false)
    }
  }

  property("a fee-less settlement that records keeps the position's leftover ERG and both rewards") {
    atLastBlock { (ctx, wallet) =>
      val (accNode, acc) = accBox(ctx, fixtureEpoch - 1, BALANCE, ringMoved(fixtureEpoch - 1))
      val (position, positionBox) = positionAt(ctx, maker(ctx), taker(ctx), ended, 1)
      val step = ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, positionBox,
        ForwardsTransactions.recordingAt(ctx), 0L)
      val (block, tx) = inBlock(step, Seq(accNode, position.box))

      val fees = read(block, tx, wallet.contract.ergoTreeHex)
      fees.map(f => (f.orderId, f.grossNanoErg, f.transactionFeeNanoErg, f.local)) shouldBe
        Vector((position.box.boxId, (position.box.value - 2 * ForwardsTransactions.PayoutErg).toString, "0", true))
      fees.head.tokens shouldBe Seq(TokenAmount(litId.toString, (DFHelpers.SETTLE_REWARD + DFHelpers.RECORD_REWARD).toString))
    }
  }

  property("a spend of an empty accumulator keeps nothing, and is not counted as work") {
    atLastBlock { (ctx, wallet) =>
      val (accNode, acc) = accBox(ctx, fixtureEpoch - 1, 0L)
      val funding = walletFunding(ctx, wallet, UTXO.MIN_CHANGE)
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(DFBoxes.input(funding, ctx).get), 0L)
      val (block, tx) = inBlock(step, Seq(accNode, funding))
      read(block, tx, wallet.contract.ergoTreeHex) shouldBe empty
    }
  }

  property("a reward in a token the list does not name is counted, and its id never kept") {
    atLastBlock { (ctx, wallet) =>
      val (accNode, acc) = accBox(ctx, fixtureEpoch - 1, BALANCE)
      val funding = walletFunding(ctx, wallet, UTXO.MIN_CHANGE)
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(DFBoxes.input(funding, ctx).get), 0L)
      val (block, tx) = inBlock(step, Seq(accNode, funding))
      val fees = read(block, tx, wallet.contract.ergoTreeHex, VerifiedRegistry.forNetwork(NetworkType.TESTNET))
      fees.map(f => (f.tokens, f.otherTokens)) shouldBe Vector((Seq.empty, 1))
    }
  }

  property("an accumulator spend when the network has no forwards deployment is no execution of anything") {
    atLastBlock { (ctx, wallet) =>
      val (accNode, acc) = accBox(ctx, fixtureEpoch - 1, BALANCE)
      val funding = walletFunding(ctx, wallet, UTXO.MIN_CHANGE)
      val step = ForwardsTransactions.record(ctx, wallet, contracts, acc, ForwardsTransactions.recordingAt(ctx).get,
        Seq(DFBoxes.input(funding, ctx).get), 0L)
      val (block, tx) = inBlock(step, Seq(accNode, funding))
      BatchingStatistics.read(block, tx, NetworkType.TESTNET, wallet.contract.ergoTreeHex, None, listed) shouldBe empty
    }
  }
}
