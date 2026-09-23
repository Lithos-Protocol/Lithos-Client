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
  private def serve(api: NodeApi, boxes: Vector[NodeBox]): Unit = {
    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { invocation =>
      val p = invocation.getArgument[Paging](1)
      Success(boxes.slice(p.offset, p.offset + p.limit).map { b =>
        WalletBox(b, "", Some(10), b.transactionId, b.index, Some(b.creationHeight),
          None, None, spent = false, onchain = true, Seq.empty)
      })
    }
  }

  "Wallet inventory" should "bound its descriptor cache while counting every page" in {
    val (ctx, api, wallet) = FakeNodeContext(numAddresses = 1)
    val boxes = Vector.tabulate(5000) { i =>
      NodeBox(f"${i + 1}%064x", "ab" * 32, 1000000L, 0, 1, wallet.contract.ergoTreeHex)
    }
    serve(api, boxes)
    val snapshot = new WalletInventory(ctx, api).snapshot(1000, Set.empty)
    snapshot.complete shouldBe true
    snapshot.truncated shouldBe true
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

  it should "skip wallet-reported rewards without indexer calls even with many derived addresses" in {
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
