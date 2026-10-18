package transactions.engine.wallet

import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{when, verify, never}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import work.lithos.mutations.UTXO
import scala.util.Success

class WalletInventorySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /** @param unconfirmed ids the node reports as still in the mempool */
  private def serve(api: NodeApi, boxes: Vector[NodeBox], unconfirmed: Set[String] = Set.empty): Unit = {
    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { invocation =>
      val p = invocation.getArgument[Paging](1)
      Success(boxes.slice(p.offset, p.offset + p.limit).map { b =>
        WalletBox(b, "", Some(10), b.transactionId, b.index, Some(b.creationHeight),
          None, None, spent = false, onchain = !unconfirmed.contains(b.boxId), Seq.empty)
      })
    }
  }

  "A confirmed-only selection" should "take neither a box still in the mempool nor locally built change" in {
    // A fee-less candidate transaction carries no parent for its wallet input, and the node skips a
    // spend of a box that is not yet in a block
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      // Smallest is change, then pending, so each pick below is the smallest box it may take.
      val pending = UTXO(wallet.contract, 2000000000L).toDummyInput(ctx)
      val confirmed = UTXO(wallet.contract, 3000000000L).toDummyInput(ctx)
      val change = UTXO(wallet.contract, 1500000000L).toDummyInput(ctx)
      serve(api, Vector(WalletInventory.nodeBox(pending), WalletInventory.nodeBox(confirmed)),
        unconfirmed = Set(pending.id.toString))
      val inventory = new WalletInventory(node, api)
      def pick(confirmedOnly: Boolean, known: Vector[NodeBox]) = inventory.select(ctx, 1000000000L,
        Seq.empty, Set.empty, single = true, p2pkOnly = false, rewardsOnly = false, known = known,
        confirmedOnly = confirmedOnly).map(_.id)

      pick(confirmedOnly = false, Vector(WalletInventory.nodeBox(change))) shouldBe Vector(change.id)
      pick(confirmedOnly = false, Vector.empty) shouldBe Vector(pending.id)
      pick(confirmedOnly = true, Vector(WalletInventory.nodeBox(change))) shouldBe Vector(confirmed.id)
    }
  }

  "Wallet inventory" should "bound its descriptor cache while counting every page" in {
    val (ctx, api, wallet) = FakeNodeContext(numAddresses = 1)
    val boxes = Vector.tabulate(5000) { i =>
      NodeBox(f"${i + 1}%064x", "ab" * 32, 1000000L, 0, 1, wallet.contract.ergoTreeHex)
    }
    serve(api, boxes)
    // The last box is far past the descriptor ceiling; the absent id stands for one already spent.
    val snapshot = new WalletInventory(ctx, api).snapshot(1000, Set.empty,
      watched = Set(boxes.last.boxId, "ff" * 32))
    snapshot.truncated shouldBe true
    snapshot.boxes.map(_.id) should not contain boxes.last.boxId
    snapshot.seen shouldBe Set(boxes.last.boxId)
    snapshot.spendable shouldBe BigInt(5000000000L)
    snapshot.boxes.size should be <= WalletInventory.MaxDescriptors
    snapshot.boxes.map(_.retainedBytes).sum should be <= WalletInventory.MaxDescriptorBytes
  }

  it should "select a covering box beyond the cache horizon without retaining or hydrating preceding dust" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      val finalInput = UTXO(wallet.contract, 100000000000L).toDummyInput(ctx)
      val boxes = Vector.tabulate(5000) { i =>
        NodeBox(f"${i + 1}%064x", "ab" * 32, 1000000L, 0, 1, wallet.contract.ergoTreeHex)
      } :+ WalletInventory.nodeBox(finalInput)
      serve(api, boxes)
      val selected = new WalletInventory(node, api).select(ctx, 5000000000L, Seq.empty, Set.empty,
        single = false, p2pkOnly = true, rewardsOnly = false)
      selected.map(_.id) shouldBe Vector(finalInput.id)
      val excluded = new WalletInventory(node, api).select(ctx, 5000000000L, Seq.empty,
        boxes.take(5000).map(_.boxId).toSet, single = true, p2pkOnly = true, rewardsOnly = false)
      excluded.map(_.id) shouldBe Vector(finalInput.id)
    }
  }

  "A single-box request" should "take the smallest covering box, not the first" in {
    // A 0.003 ERG bond took the reporter's 1,874 ERG box, and box after box like it, because the
    // walk stopped at the first box in node order that covered it.
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      val large = UTXO(wallet.contract, 1874000000000L).toDummyInput(ctx)
      val nine = UTXO(wallet.contract, 9000000000L).toDummyInput(ctx)
      val dust = UTXO(wallet.contract, 1000000L).toDummyInput(ctx)
      val small = UTXO(wallet.contract, 50000000L).toDummyInput(ctx)
      serve(api, Vector(large, nine, dust, small).map(WalletInventory.nodeBox))
      new WalletInventory(node, api).select(ctx, 3000000L, Seq.empty, Set.empty, single = true,
        p2pkOnly = false, rewardsOnly = false).map(_.id) shouldBe Vector(small.id)
    }
  }

  it should "prefer a tokenless box to a smaller one carrying tokens" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      val withLit = UTXO(wallet.contract, 10000000L, Seq(work.lithos.mutations.Token(
        org.ergoplatform.sdk.ErgoId.create("ab" * 32), 5L))).toDummyInput(ctx)
      val plain = UTXO(wallet.contract, 900000000L).toDummyInput(ctx)
      serve(api, Vector(withLit, plain).map(WalletInventory.nodeBox))
      new WalletInventory(node, api).select(ctx, 3000000L, Seq.empty, Set.empty, single = true,
        p2pkOnly = false, rewardsOnly = false).map(_.id) shouldBe Vector(plain.id)
    }
  }

  it should "stop reading once a box within twice the request covers it" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      val close = UTXO(wallet.contract, 5000000L).toDummyInput(ctx)
      // A full first page ending in a close box, so a second request would only come from reading on.
      val boxes = Vector.tabulate(WalletInventory.PageSize - 1)(i =>
        NodeBox(f"${i + 1}%064x", "ab" * 32, 1000000000L + i, 0, 1, wallet.contract.ergoTreeHex)) :+
        WalletInventory.nodeBox(close)
      serve(api, boxes)
      new WalletInventory(node, api).select(ctx, 3000000L, Seq.empty, Set.empty, single = true,
        p2pkOnly = false, rewardsOnly = false).map(_.id) shouldBe Vector(close.id)
      verify(api, never()).walletUnspentBoxes(any[ConfirmationRange],
        org.mockito.ArgumentMatchers.eq(Paging(WalletInventory.PageSize, WalletInventory.PageSize)))
    }
  }

  "A request the wallet cannot cover" should "fail as a shortfall, not as a broken read" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    node.getClient.execute { ctx =>
      serve(api, Vector(WalletInventory.nodeBox(UTXO(wallet.contract, 1000000L).toDummyInput(ctx))))
      for (single <- Seq(false, true))
        intercept[mutations.NotEnoughInputsException](new WalletInventory(node, api).select(ctx, 3000000L,
          Seq.empty, Set.empty, single, p2pkOnly = false, rewardsOnly = false))
    }
  }

  "Wallet inventory" should "skip wallet-reported rewards without indexer calls even with many derived addresses" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 100)
    node.getClient.execute { ctx =>
      val masterTree = wallet.rewardTrees.find(_._2 == wallet.prover.getAddress).get._1
      val reward = UTXO(work.lithos.mutations.Contract(sigma.ast.ErgoTree.fromHex(masterTree)), 5000000000L)
        .setCreationHeight(1).toDummyInput(ctx)
      val plain = UTXO(wallet.contract, 2000000000L).toDummyInput(ctx)
      serve(api, Vector(WalletInventory.nodeBox(reward), WalletInventory.nodeBox(plain)))
      when(api.indexerEnabled).thenReturn(true)
      val inventory = new WalletInventory(node, api)
      for (single <- Seq(false, true); p2pkOnly <- Seq(false, true)) {
        inventory.select(ctx, 1000000000L, Seq.empty, Set.empty, single, p2pkOnly,
          rewardsOnly = false, known = Vector(WalletInventory.nodeBox(reward))).map(_.id) shouldBe Vector(plain.id)
      }
      verify(api, never()).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
      verify(api, never()).indexerEnabled
    }
  }

  it should "classify master rewards without an indexer and deduplicate both reward sources when indexed" in {
    val (node, api, wallet) = FakeNodeContext(numAddresses = 1)
    val masterTree = wallet.rewardTrees.find(_._2 == wallet.prover.getAddress).get._1
    val derivedTree = wallet.rewardTrees.find(_._2 == wallet.p2pk).get._1
    val mature = NodeBox("01" * 32, "ab" * 32, 5000000000L, 0, 1, masterTree)
    val locked = NodeBox("02" * 32, "ab" * 32, 3000000000L, 0, 280, masterTree)
    val derived = NodeBox("03" * 32, "ab" * 32, 2000000000L, 0, 2, derivedTree)
    serve(api, Vector(mature, locked))
    val inventory = new WalletInventory(node, api)
    val unindexed = inventory.snapshot(1000, Set.empty)
    unindexed.unlockedCount shouldBe 1
    unindexed.lockedCount shouldBe 1
    unindexed.nextUnlock shouldBe Some(1)
    unindexed.boxes.forall(_.reward) shouldBe true
    when(api.indexerEnabled).thenReturn(true)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val p = inv.getArgument[Paging](1)
        Success(Vector(mature, locked, derived).filter(_.ergoTree == inv.getArgument[String](0))
          .slice(p.offset, p.offset + p.limit).map(b => IndexedBox(b, "reward", b.creationHeight, 1L)))
      }
    val indexed = inventory.snapshot(1000, Set.empty)
    indexed.boxes.size shouldBe 3
    indexed.unlockedCount shouldBe 2
    indexed.spendable shouldBe BigInt(0)
    indexed.unlocked shouldBe BigInt(7000000000L)
    indexed.locked shouldBe BigInt(3000000000L)
    val excluded = inventory.snapshot(1001, Set(mature.boxId))
    excluded.unlockedCount shouldBe 2
    excluded.lockedCount shouldBe 0
    excluded.spendable shouldBe BigInt(0)
    excluded.unlocked shouldBe BigInt(5000000000L)
  }
}
