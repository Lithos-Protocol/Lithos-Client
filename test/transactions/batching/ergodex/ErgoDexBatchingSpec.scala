package transactions.batching.ergodex

import configs.BatchingConfig
import node.model.{NodeAsset, NodeBox, NodeInput, NodeSpendingProof, NodeTransaction}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.ErgoDexFixtures._
import transactions.batching.BatchingMempool
import transactions.candidate.BlockTxMessages.Supersede
import transactions.candidate.CapitalOrigin
import work.lithos.mutations.UTXO

import scala.concurrent.duration._

class ErgoDexBatchingSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private val pool = ErgoDexPool.native(poolBox).getOrElse(fail("pool fixture did not parse"))
  private val order = ErgoDexOrder.parse(orderBox).getOrElse(fail("order fixture did not parse"))
  private val poolIncluded = poolBox.creationHeight + 1
  private val revenue = ErgoDexExecution.price(order, pool, 0L).getOrElse(fail("fixture fill was refused")).revenue

  private def id(seed: String): String = (seed * 64).take(64)

  private def mempoolTx(txId: String, spends: Seq[String], outputs: Seq[NodeBox] = Seq.empty) =
    CompleteMempool.MempoolTx(txId,
      NodeTransaction(txId, spends.map(NodeInput(_, NodeSpendingProof.empty)), Seq.empty, outputs), 100)

  private def spenders(txs: CompleteMempool.MempoolTx*): BatchingMempool.Spenders =
    BatchingMempool.spenders(CompleteMempool.Snapshot(id("a"), txs.map(_.id).toSet,
      txs.flatMap(_.body.inputs.map(_.boxId)).toSet, System.nanoTime(), transactions = txs.toVector))

  private def poolOutput(boxId: String): NodeBox = poolBox.copy(boxId = boxId, transactionId = id("f"))

  "Broadcast pricing" should "refuse an opening fill whose takings cannot fund a box once the fee is paid" in {
    val capped = order.copy(maxMinerFee = revenue - UTXO.MIN_CHANGE + 1)
    ErgoDexExecution.price(capped, pool, 0L, minerFeeCeiling = Long.MaxValue) shouldBe None
    withClue("the same fill added to an existing box still earns, so it is kept: ") {
      ErgoDexExecution.price(capped, pool, 0L, fundsItsOwnBox = false,
        minerFeeCeiling = Long.MaxValue) should not be empty
    }
  }

  it should "refuse a fill that earns nothing once the fee is paid, even inside a run" in {
    val capped = order.copy(maxMinerFee = revenue)
    ErgoDexExecution.price(capped, pool, 0L, fundsItsOwnBox = false,
      minerFeeCeiling = Long.MaxValue) shouldBe None
  }

  it should "pay the lower of the order's cap and this client's ceiling" in {
    val capped = order.copy(maxMinerFee = revenue)
    withClue("a ceiling under the cap leaves the takings positive: ") {
      ErgoDexExecution.price(capped, pool, 0L, minerFeeCeiling = 2000000L) should not be empty
    }
  }

  it should "leave fee-less pricing unchanged" in {
    ErgoDexExecution.price(order.copy(maxMinerFee = revenue), pool, 0L) should not be empty
  }

  private val config = BatchingConfig.Default.copy(minRevenueNanoErg = 0L)

  "Discovery" should "track an order that prices against a live pool" in {
    ErgoDexBatching.track(Seq(order), Seq(pool -> poolIncluded), poolIncluded + 10, config) shouldBe
      Map(order.boxId -> pool.nft)
  }

  it should "skip a pool untouched for longer than the age limit" in {
    val tip = poolIncluded + config.maxPoolAgeBlocks
    ErgoDexBatching.track(Seq(order), Seq(pool -> poolIncluded), tip, config) should not be empty
    ErgoDexBatching.track(Seq(order), Seq(pool -> poolIncluded), tip + 1, config) shouldBe empty
  }

  it should "skip a denied pool" in {
    ErgoDexBatching.track(Seq(order), Seq(pool -> poolIncluded), poolIncluded,
      config.copy(deniedPools = Set(pool.nft))) shouldBe empty
  }

  it should "skip an order paying under the revenue floor" in {
    ErgoDexBatching.track(Seq(order), Seq(pool -> poolIncluded), poolIncluded,
      config.copy(minRevenueNanoErg = revenue + 1)) shouldBe empty
  }

  it should "keep the newest pools when there are more than it may hold" in {
    val older = pool.copy(nft = id("e"))
    val olderOrder = order.copy(poolNft = older.nft, box = orderBox.copy(boxId = id("1")))
    ErgoDexBatching.track(Seq(order, olderOrder), Seq(pool -> poolIncluded, older -> (poolIncluded - 1)),
      poolIncluded, config.copy(maxTrackedPools = 1)) shouldBe Map(order.boxId -> pool.nft)
  }

  it should "keep the orders that pay most when there are more than it may hold" in {
    val smaller = order.copy(baseAmount = order.baseAmount / 2, box = orderBox.copy(boxId = id("1")))
    ErgoDexBatching.track(Seq(smaller, order), Seq(pool -> poolIncluded), poolIncluded,
      config.copy(maxTrackedOrders = 1)) shouldBe Map(order.boxId -> pool.nft)
  }

  "An unconfirmed spend" should "name every transaction claiming a box the run spends, and nothing else" in {
    val claims = spenders(mempoolTx(id("b"), Seq(poolBox.boxId)), mempoolTx(id("c"), Seq(id("9"))))
    BatchingMempool.competitors(claims, Seq(poolBox.boxId, order.boxId)) shouldBe Set(id("b"))
  }

  it should "mark an order withdrawn only when its claimant recreates no pool" in {
    BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders()) shouldBe false
    BatchingMempool.withdrawn(order.boxId, order.poolNft,
      spenders(mempoolTx(id("b"), Seq(poolBox.boxId, order.boxId), Seq(poolOutput(id("d")))))) shouldBe false
    BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders(mempoolTx(id("b"), Seq(order.boxId)))) shouldBe true
  }

  "The pool tip" should "be the confirmed box when nothing spends it" in {
    BatchingMempool.poolTip(poolBox, pool.nft, spenders()) shouldBe Some(poolBox)
  }

  it should "follow a run of unconfirmed executions to its last pool output" in {
    val claims = spenders(
      mempoolTx(id("b"), Seq(poolBox.boxId, id("1")), Seq(poolOutput(id("d")))),
      mempoolTx(id("c"), Seq(id("d"), id("2")), Seq(poolOutput(id("e")))))
    BatchingMempool.poolTip(poolBox, pool.nft, claims).map(_.boxId) shouldBe Some(id("e"))
  }

  it should "have no tip when two transactions contest the same pool box" in {
    val claims = spenders(
      mempoolTx(id("b"), Seq(poolBox.boxId), Seq(poolOutput(id("d")))),
      mempoolTx(id("c"), Seq(poolBox.boxId), Seq(poolOutput(id("e")))))
    BatchingMempool.poolTip(poolBox, pool.nft, claims) shouldBe None
  }

  it should "have no tip when the pool box's spender recreates no pool" in {
    val claims = spenders(mempoolTx(id("b"), Seq(poolBox.boxId),
      Seq(poolBox.copy(boxId = id("d"), assets = Seq(NodeAsset(id("7"), 1L))))))
    BatchingMempool.poolTip(poolBox, pool.nft, claims) shouldBe None
  }

  private val wallet1 = NodeBox(id("9"), id("8"), 2000000000L, 0, 100, "0008cd02" + "11" * 32)

  private def snapshotOf(txs: CompleteMempool.MempoolTx*): CompleteMempool.Snapshot =
    CompleteMempool.Snapshot(id("a"), txs.map(_.id).toSet, txs.flatMap(_.body.inputs.map(_.boxId)).toSet,
      System.nanoTime(), transactions = txs.toVector)

  "Mempool discovery" should "find orders among unconfirmed outputs and nothing else" in {
    val snapshot = snapshotOf(mempoolTx(id("b"), Seq(wallet1.boxId), Seq(poolBox, orderBox, wallet1)))
    ErgoDexBatching.unconfirmedOrders(snapshot, Set.empty, 10).map(_.boxId) shouldBe Vector(orderBox.boxId)
    ErgoDexBatching.unconfirmedOrders(snapshot, Set(order.poolNft), 10) shouldBe empty
    ErgoDexBatching.unconfirmedOrders(snapshot, Set.empty, 0) shouldBe empty
  }

  private def walletPlaced(placement: CompleteMempool.MempoolTx, others: CompleteMempool.MempoolTx*): Boolean = {
    val snapshot = snapshotOf(placement +: others: _*)
    val boxes = Map(wallet1.boxId -> wallet1, poolBox.boxId -> poolBox) ++
      snapshot.transactions.flatMap(_.body.outputs.map(box => box.boxId -> box))
    BatchingMempool.placementChain(placement, BatchingMempool.creators(snapshot), 8)
      .exists(_.forall(ErgoDexBatching.placedByWallet(_, boxes, BatchingMempool.spenders(snapshot))))
  }

  "A placement" should "be carried when it spends only a confirmed wallet box" in {
    walletPlaced(mempoolTx(id("b"), Seq(wallet1.boxId), Seq(orderBox))) shouldBe true
  }

  it should "not be carried when it spends a pool, which is a bot's execution" in {
    walletPlaced(mempoolTx(id("b"), Seq(poolBox.boxId, wallet1.boxId), Seq(orderBox))) shouldBe false
  }

  it should "not be carried when it recreates a pool" in {
    walletPlaced(mempoolTx(id("b"), Seq(wallet1.boxId), Seq(poolOutput(id("d")), orderBox))) shouldBe false
  }

  it should "not be carried when an ancestor input is missing" in {
    walletPlaced(mempoolTx(id("b"), Seq(wallet1.boxId), Seq(orderBox)),
      mempoolTx(id("c"), Seq(id("7")), Seq(wallet1))) shouldBe false
  }

  it should "carry a P2PK chain in spend order and count each shared ancestor once" in {
    val left = wallet1.copy(boxId = id("4"))
    val right = wallet1.copy(boxId = id("5"))
    val root = mempoolTx(id("c"), Seq(wallet1.boxId), Seq(left, right))
    val branch = mempoolTx(id("d"), Seq(left.boxId), Seq(wallet1.copy(boxId = id("6"))))
    val placed = mempoolTx(id("b"), Seq(id("6"), right.boxId), Seq(orderBox))
    val creators = BatchingMempool.creators(snapshotOf(placed, branch, root))
    BatchingMempool.placementChain(placed, creators, 3).get.map(_.id) shouldBe Vector(root.id, branch.id, placed.id)
    BatchingMempool.placementChain(placed, creators, 2) shouldBe None
    walletPlaced(placed, branch, root) shouldBe true
    walletPlaced(placed, branch, root, mempoolTx(id("e"), Seq(wallet1.boxId))) shouldBe false
  }

  it should "reject bot proceeds even behind an intervening P2PK transaction" in {
    val proceeds = wallet1.copy(boxId = id("4"))
    val change = wallet1.copy(boxId = id("5"))
    val bot = mempoolTx(id("c"), Seq(poolBox.boxId), Seq(proceeds))
    val transfer = mempoolTx(id("d"), Seq(proceeds.boxId), Seq(change))
    val placement = mempoolTx(id("b"), Seq(change.boxId), Seq(orderBox))
    walletPlaced(placement, transfer, bot) shouldBe false
  }

  it should "reject cycles and data inputs in ancestors" in {
    val change = wallet1.copy(boxId = id("4"))
    val root = mempoolTx(id("c"), Seq(wallet1.boxId), Seq(change))
    val placed = mempoolTx(id("b"), Seq(change.boxId), Seq(orderBox))
    val withData = root.copy(body = root.body.copy(dataInputs = Seq(node.model.NodeDataInput(id("5")))))
    walletPlaced(placed, withData) shouldBe false
    val cycle = root.copy(body = root.body.copy(inputs = Seq(NodeInput(orderBox.boxId, NodeSpendingProof.empty))))
    walletPlaced(placed, cycle) shouldBe false
  }

  it should "not be carried when another unconfirmed transaction spends the same input" in {
    walletPlaced(mempoolTx(id("b"), Seq(wallet1.boxId), Seq(orderBox)),
      mempoolTx(id("c"), Seq(wallet1.boxId))) shouldBe false
  }

  it should "not be carried when an input could not be read, or it has data inputs" in {
    walletPlaced(mempoolTx(id("b"), Seq(id("6")), Seq(orderBox))) shouldBe false
    val withData = mempoolTx(id("b"), Seq(wallet1.boxId), Seq(orderBox))
    walletPlaced(withData.copy(body = withData.body.copy(dataInputs = Seq(node.model.NodeDataInput(id("5")))))) shouldBe false
  }

  it should "not be carried past the input limit" in {
    val many = (0 to BatchingMempool.MaxPlacementInputs).map(i => f"$i%064x")
    val placement = mempoolTx(id("b"), many, Seq(orderBox))
    val snapshot = snapshotOf(placement)
    ErgoDexBatching.placedByWallet(placement, many.map(i => i -> wallet1.copy(boxId = i)).toMap,
      BatchingMempool.spenders(snapshot)) shouldBe false
  }

  "Fitting a run" should "count placements against the slots, once each" in {
    val fill = ErgoDexExecution.price(order, pool, 0L).get
    val second = fill.copy(order = order.copy(box = orderBox.copy(boxId = id("1"))))
    val third = fill.copy(order = order.copy(box = orderBox.copy(boxId = id("2"))))
    val shared = mempoolTx(id("b"), Seq(wallet1.boxId))
    val placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx] =
      o => if (o.boxId == orderBox.boxId) Vector.empty else Vector(shared)
    val fillPlacementOf: ErgoDexFill => Vector[CompleteMempool.MempoolTx] = f => placementOf(f.order)

    val (taken, carried) = BatchingMempool.fitting(Vector(fill, second, third), 4, fillPlacementOf)
    taken should have size 3
    carried.map(_.id) shouldBe Vector(id("b"))
    BatchingMempool.fitting(Vector(fill, second, third), 2, fillPlacementOf)._1 should have size 1
    BatchingMempool.fitting(Vector(second, third), 2, fillPlacementOf, Set(shared.id))._1 should have size 2
  }

  private val height = poolBox.creationHeight + 1

  private def runOf(ctx: org.ergoplatform.appkit.BlockchainContext, orders: Seq[ErgoDexOrder], limit: Int = 10,
                    deadline: Deadline = 1.minute.fromNow): ErgoDexRun = {
    import node.MutationConversions._
    ErgoDexExecution.run(ctx, wallet, poolBox.toInputUTXO(ctx), pool, orders, limit, 0L, height, 0L,
      useTrueProp = false, deadline)
  }

  "A built run" should "supersede its competitors and declare its takings as executor revenue" in {
    nodeContext.getClient.execute { ctx =>
      val chain = runOf(ctx, Seq(order)).chain.getOrElse(fail("the fixture run did not build"))
      val fill = chain.fills.head
      val bundle = chain.bundle(Set(id("b")))

      bundle.members.map(_.id) shouldBe chain.transactions.map(_.getId)
      bundle.interactions shouldBe Seq(Supersede(Set(id("b"))))
      bundle.capital should have size 1
      bundle.capital.head.origin shouldBe CapitalOrigin.ExecutorReward
      bundle.capital.head.value shouldBe fill.revenue
      bundle.capital.head.parentTxId shouldBe chain.transactions.last.getId
      withClue("the takings stay the miner's own when TrueProp collection is off: ") {
        bundle.capital.head.box.contract.ergoTreeHex shouldBe wallet.contract.ergoTreeHex
      }
    }
  }

  it should "put placements first and declare the chain it makes from them" in {
    nodeContext.getClient.execute { ctx =>
      val chain = runOf(ctx, Seq(order)).chain.get
      val placement = transactions.candidate.BlockTxMessages.CandidateTx.ancestor(
        mempoolTx(id("b"), Seq(wallet1.boxId), Seq(orderBox)).body)
      val bundle = chain.bundle(Set.empty, Vector(placement))

      bundle.members.map(_.id) shouldBe (placement.id +: chain.transactions.map(_.getId))
      bundle.interactions should contain allOf(
        transactions.candidate.BlockTxMessages.ChainFromMempool(placement.id),
        transactions.candidate.BlockTxMessages.IncludeExisting(placement.id))
      bundle.carriesItsParents shouldBe true
    }
  }

  it should "name an order whose fields do not hash to its id, and build nothing for it" in {
    nodeContext.getClient.execute { ctx =>
      val misreported = order.copy(box = orderBox.copy(index = 1))
      ErgoDexExecution.price(misreported, pool, 0L) should not be empty
      runOf(ctx, Seq(misreported)) shouldBe ErgoDexRun(None, Vector.empty, Vector(misreported.boxId), cutShort = false)
    }
  }

  it should "pass over an order that cannot be built and execute the next against the same pool" in {
    nodeContext.getClient.execute { ctx =>
      // Twice the fee ranks it first; a box id its fields do not hash to makes it unbuildable
      val unbuildable = order.copy(dexFeePerTokenNum = order.dexFeePerTokenNum * 2,
        box = orderBox.copy(boxId = id("e"), index = 3))
      val run = runOf(ctx, Seq(order, unbuildable))
      run.unbuildable shouldBe Vector(unbuildable.boxId)
      run.chain.map(_.fills.map(_.order.boxId)) shouldBe Some(Vector(order.boxId))
      run.chain.get.poolIdAt(0) shouldBe poolBox.boxId
    }
  }

  it should "build nothing past its deadline, and say it stopped with orders left" in {
    nodeContext.getClient.execute { ctx =>
      val run = runOf(ctx, Seq(order), deadline = Deadline.now - 1.second)
      run.chain shouldBe None
      run.cutShort shouldBe true
    }
  }

  it should "declare nothing to supersede when nothing competes" in {
    nodeContext.getClient.execute { ctx =>
      runOf(ctx, Seq(order)).chain.get.bundle(Set.empty).interactions shouldBe empty
    }
  }

  it should "declare only the last takings box of a run, and name the pool box each execution spends" in {
    nodeContext.getClient.execute { ctx =>
      import node.MutationConversions._
      val larger = orderBox.copy(value = orderBox.value + UTXO.MIN_CHANGE)
      val second = ErgoDexOrder.parse(larger.copy(boxId = larger.toInputUTXO(ctx).id.toString()))
        .getOrElse(fail("second order did not parse"))
      val chain = runOf(ctx, Seq(order, second), limit = 2).chain.getOrElse(fail("the two-order run did not build"))
      withClue("both orders must build, or this test exercises one execution: ") { chain.fills should have size 2 }
      val bundle = chain.bundle(Set.empty)

      bundle.members should have size 2
      val first = chain.transactions.head.getOutputsToSpend
      withClue("the second execution must spend the first one's pool and takings: ") {
        bundle.members(1).inputIds should contain allOf(first.get(0).getId.toString(), first.get(2).getId.toString())
      }
      chain.poolIdAt(1) shouldBe first.get(0).getId.toString()
      bundle.capital should have size 1
      bundle.capital.head.value shouldBe chain.fills.map(_.revenue).sum
      bundle.capital.head.parentTxId shouldBe chain.transactions.last.getId
    }
  }
}
