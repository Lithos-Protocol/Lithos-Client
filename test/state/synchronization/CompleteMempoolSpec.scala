package state.synchronization

import node.NodeApi
import node.model.{NodeAsset, NodeBox, NodeInput, NodeRegisters, NodeSpendingProof, NodeTransaction, Paging}
import org.ergoplatform.appkit.ErgoValue
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.ChainFixtures
import scala.util.Success

class CompleteMempoolSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val id = "ab" * 32
  private val input = "cd" * 32

  /** Named so it does not shadow the `node` package the model types are read from. */
  private def mempoolApi(): NodeApi = {
    val api = mock[NodeApi]
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("aa" * 32))))
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id)))
    // Empty by default, so these cases take the by-id completion pass; the paging tests below
    // override it.
    when(api.unconfirmedTransactions(any[Paging])).thenReturn(Success(Seq.empty))
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(Some(
      NodeTransaction(id, Seq(NodeInput(input, NodeSpendingProof.empty)), Seq.empty, Seq.empty))))
    api
  }
  "Complete collection" should "include inputs only after the inventory and anchor agree" in {
    val snapshot = CompleteMempool.collect(mempoolApi()).get
    snapshot.ids shouldBe Set(id)
    snapshot.spent shouldBe Set(input)
  }
  it should "reject a missing body instead of publishing an empty spend view" in {
    val api = mempoolApi()
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(None))
    CompleteMempool.collect(api).isFailure shouldBe true
  }
  it should "reject membership changes and same-height parent changes" in {
    val changed = mempoolApi()
    when(changed.unconfirmedTransactionIds()).thenReturn(Success(Seq(id)), Success(Seq.empty))
    CompleteMempool.collect(changed).isFailure shouldBe true
    val reorg = mempoolApi()
    when(reorg.info()).thenReturn(Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("aa" * 32))),
      Success(ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("ef" * 32))))
    CompleteMempool.collect(reorg).isFailure shouldBe true
  }
  it should "keep a previous observation unusable after a failed refresh" in {
    val snapshot = CompleteMempool.collect(mempoolApi()).get
    CompleteMempool.Observation(1L, Some(snapshot), Some("incomplete")).fresh shouldBe false
  }
  it should "reject duplicate inventory entries before fetching bodies" in {
    val api = mempoolApi()
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id, id)))
    CompleteMempool.collect(api).isFailure shouldBe true
  }
  /**
   * A double spend in the mempool is ordinary. Failing the whole observation over one would leave
   * every consumer without spend information, so both claimants are recorded and the box stays
   * unavailable either way.
   */
  it should "record competing claims on one box instead of failing the observation" in {
    val api = mempoolApi()
    val rival = "ba" * 32
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id, rival)))
    when(api.unconfirmedTransactionById(rival)).thenReturn(Success(Some(
      NodeTransaction(rival, Seq(NodeInput(input, NodeSpendingProof.empty)), Seq.empty, Seq.empty))))

    val snapshot = CompleteMempool.collect(api).get
    snapshot.spent should contain(input)
    snapshot.conflicts(input) shouldBe Set(id, rival)
    snapshot.transactions.map(_.id).toSet shouldBe Set(id, rival)
  }

  /**
   * One request per member puts the whole mempool on the latency path of every engine operation,
   * which starves wallet selection and saturates the engine's admission queue. Bodies come from
   * paged reads; the inventory still decides what the observation must contain.
   */
  it should "read bodies in pages rather than one request per member" in {
    val api = mempoolApi()
    val members = (0 until 120).map(index => "%064x".format(index))
    val bodies = members.map(memberId =>
      NodeTransaction(memberId, Seq(NodeInput(memberId, NodeSpendingProof.empty)), Seq.empty, Seq.empty))
    when(api.unconfirmedTransactionIds()).thenReturn(Success(members))
    when(api.unconfirmedTransactions(any[Paging])).thenAnswer { invocation =>
      val paging = invocation.getArgument[Paging](0)
      Success(bodies.slice(paging.offset, paging.offset + paging.limit))
    }

    CompleteMempool.collect(api).get.transactions should have size 120
    // Three pages of fifty, and nothing left for the by-id completion pass.
    verify(api, times(3)).unconfirmedTransactions(any[Paging])
    verify(api, never()).unconfirmedTransactionById(anyString())
  }

  /** Offsets shift under churn, so anything the pages missed is still fetched by id. */
  it should "complete a member the paged reads did not supply" in {
    val api = mempoolApi()
    when(api.unconfirmedTransactions(any[Paging])).thenReturn(Success(Seq.empty))

    val snapshot = CompleteMempool.collect(api).get
    snapshot.transactions.map(_.id) shouldBe Vector(id)
    verify(api, times(1)).unconfirmedTransactionById(id)
  }

  /** Derived views read these rather than re-fetching, so the bodies have to survive collection. */
  it should "retain one body per member for the views derived from it" in {
    val snapshot = CompleteMempool.collect(mempoolApi()).get
    snapshot.transactions.map(_.id) shouldBe Vector(id)
    snapshot.transactions.head.body.inputs.map(_.boxId) shouldBe Seq(input)
    snapshot.transactions.head.sizeBytes should be > 0
  }

  /** Churn is not a node fault: the caller keeps its previous observation and walks again. */
  it should "report a membership change as raced rather than as a failure" in {
    val api = mempoolApi()
    when(api.unconfirmedTransactionIds()).thenReturn(Success(Seq(id)), Success(Seq.empty))
    CompleteMempool.collect(api).failed.get shouldBe a[CompleteMempool.Raced]
  }

  it should "exclude lender keys from joins outside any selected emission chain" in {
    val api = mempoolApi()
    val (_, _, wallet) = support.FakeNodeContext()
    val key = transactions.ProtocolContracts.hex(transactions.ProtocolContracts.lenderEntry(wallet.p2pk))
    // A queue box carries the position in R4 and the lender's SigmaProp in R5, which is the register
    // collection reads to derive the excluded key.
    val queueBox = NodeBox("ef" * 32, id, 3000000L, 0, 100, "00",
      Seq(NodeAsset(lfsm.LFSMHelpers.QUEUE_TOKEN.toString, 1)),
      NodeRegisters(Map("R4" -> ErgoValue.of(1L).toHex,
        "R5" -> ErgoValue.of(wallet.p2pk.getPublicKey).toHex)))
    when(api.unconfirmedTransactionById(id)).thenReturn(Success(Some(
      NodeTransaction(id, Seq(NodeInput(input, NodeSpendingProof.empty)), Seq.empty, Seq(queueBox)))))
    CompleteMempool.collect(api).get.lenderKeys shouldBe Set(key)
  }
}
