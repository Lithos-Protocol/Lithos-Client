package state.synchronization

import node.NodeApi
import node.model.{NodeInput, NodeSpendingProof, NodeTransaction}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.ChainFixtures
import scala.util.Success

class CompleteMempoolSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val id = "ab" * 32
  private val input = "cd" * 32
  private def node(): NodeApi = {
    val api = mock[NodeApi]
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("aa" * 32))))
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id)))
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(Some(
      NodeTransaction(id, Seq(NodeInput(input, NodeSpendingProof.empty)), Seq.empty, Seq.empty))))
    api
  }
  "Complete collection" should "include inputs only after the inventory and anchor agree" in {
    val snapshot = CompleteMempool.collect(node()).get
    snapshot.ids shouldBe Set(id)
    snapshot.spent shouldBe Set(input)
  }
  it should "reject a missing body instead of publishing an empty spend view" in {
    val api = node()
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(None))
    CompleteMempool.collect(api).isFailure shouldBe true
  }
  it should "reject membership changes and same-height parent changes" in {
    val changed = node()
    when(changed.unconfirmedTransactionIds()).thenReturn(Success(Seq(id)), Success(Seq.empty))
    CompleteMempool.collect(changed).isFailure shouldBe true
    val reorg = node()
    when(reorg.info()).thenReturn(Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("aa" * 32))),
      Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("ef" * 32))))
    CompleteMempool.collect(reorg).isFailure shouldBe true
  }
  it should "keep a previous observation unusable after a failed refresh" in {
    val snapshot = CompleteMempool.collect(node()).get
    CompleteMempool.Observation(1L, Some(snapshot), Some("incomplete")).fresh shouldBe false
  }
  it should "reject duplicate inventory entries before fetching bodies" in {
    val api = node()
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id, id)))
    CompleteMempool.collect(api).isFailure shouldBe true
  }
  it should "exclude lender keys from joins outside any selected emission chain" in {
    val api = node()
    val (_, _, wallet) = support.FakeNodeContext()
    val prop = wallet.p2pk.getPublicKey
    val key = transactions.ProtocolContracts.hex(transactions.ProtocolContracts.lenderEntry(wallet.p2pk))
    val box = node.model.NodeBox("ef" * 32, id, 3000000L, 0, 100, "00",
      Seq(node.model.NodeAsset(lfsm.LFSMHelpers.QUEUE_TOKEN.toString, 1)),
      node.model.NodeRegisters(Map("R4" -> org.ergoplatform.appkit.ErgoValue.of(1L).toHex,
        "R5" -> org.ergoplatform.appkit.ErgoValue.of(sigma.SigmaProp(prop)).toHex)))
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(Some(
      NodeTransaction(id, Seq(NodeInput(input, NodeSpendingProof.empty)), Seq.empty, Seq(box)))))
    CompleteMempool.collect(api).get.lenderKeys shouldBe Set(key)
  }
}
