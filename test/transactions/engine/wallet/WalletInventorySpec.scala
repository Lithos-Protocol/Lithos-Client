package transactions.engine.wallet

import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
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
}
