package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import mutations.{NodeWallet, NotEnoughInputsException}
import node.NodeApi
import node.model._
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{FakeCache, FakeNodeContext}
import transactions.wallet.WalletManager
import transactions.wallet.WalletMessages.RefreshBoxes

import scala.util.Success

/**
 * Which box funds a fraud proof.
 *
 * `Evaluation.es` requires output 1 to use input 1's proposition, and `FraudProof.standardMutator`
 * builds the slashing reward as `UTXO(tCtx.inputs(1).contract, slashed)`. So a matured coinbase
 * chosen here is recreated under the miner-reward script at the new creation height and locked again
 * for 720 blocks — with a valid transaction, a successful signature and no error anywhere.
 */
object FraudProofFundingSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class FraudProofFundingSpec extends TestKit(ActorSystem("fp-funding-spec", FraudProofFundingSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  private val quietConfig = Configuration.from(Map(
    "state.disableTransforms" -> true,
    "state.autoCommit" -> false,
    "stratum.diff" -> "4.0G",
    "stratum.stratumPort" -> 4444,
    "stratum.extraNonce1Size" -> 2,
    "stratum.connectionTimeout" -> 60000,
    "stratum.blockRefreshInterval" -> 1000,
    "stratum.reduceShareMessages" -> false,
    "stratum.diffRefreshInterval" -> 60000
  ))

  private val storedDataBox: DataBoxSource = new DataBoxSource {
    override def getDataBoxToken: Option[ErgoId] = Some(ErgoId.create("11" * 32))
  }

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "aa" * 32, value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /** Matured: the mocked chain sits at 123,413, far past the 720-block delay on height 1. */
  private def coinbase(wallet: NodeWallet, value: Long): IndexedBox =
    IndexedBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "bb" * 32, value = value, index = 0,
        creationHeight = 1, ergoTree = wallet.rewardTrees.keys.head),
      address = "reward", inclusionHeight = 1, globalIndex = 1L)

  /** A real `WalletManager` over a real `SubmissionHandler`, so selection is the production path. */
  private def handlerOver(walletErg: Long, rewardErg: Long): (SubmissionHandler, NodeWallet) = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val boxes = Seq(walletBox(wallet, walletErg))
    val rewards = Seq(coinbase(wallet, rewardErg))

    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(boxes) else Success(Seq.empty[WalletBox])
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(rewards.filter(_.box.ergoTree == inv.getArgument[String](0)))
      }

    val mgr: ActorRef = system.actorOf(Props(new WalletManager(ctx)))
    mgr ! RefreshBoxes
    // The refresh runs off the mailbox; settle it before selecting, as the wallet specs do.
    Thread.sleep(1200)

    val handler = TestActorRef[SubmissionHandler](Props(new SubmissionHandler(
      quietConfig, ctx, new FakeCache, storedDataBox,
      TestProbe().ref, TestProbe().ref, mgr)))
    (handler.underlyingActor, wallet)
  }

  private val oneFee = SubmissionHandler.InitialTxInfo(Map("rollup-a" -> (erg / 2)))

  "A fraud proof's initial transaction" should "be funded from a plain P2PK box" in {
    // The coinbase is the cheaper covering box, which is exactly what the generic single-box request
    // is built to prefer.
    val (handler, wallet) = handlerOver(walletErg = 5 * erg, rewardErg = 2 * erg)
    val inputs = handler.initialTxInputs(oneFee, isFPTx = true)

    inputs should have size 1
    inputs.head.value shouldEqual 5 * erg
    withClue("a reward box here is recreated under the reward script and relocked for 720 blocks: ") {
      wallet.rewardTrees.keys should not contain inputs.head.contract.ergoTreeHex
    }
    inputs.head.contract.ergoTreeHex shouldEqual wallet.contract.ergoTreeHex
  }

  it should "refuse rather than take a coinbase when no plain box can cover it" in {
    // Refusing is the right ending. The batch drops this attempt and retries, where taking the
    // coinbase would have cost the miner the reward for 720 blocks to save one retry. It surfaces as
    // the same exception an uncoverable request has always raised, so the caller needs no new branch.
    val (handler, _) = handlerOver(walletErg = erg / 10, rewardErg = 5 * erg)
    val refused = intercept[NotEnoughInputsException] {
      handler.initialTxInputs(oneFee, isFPTx = true)
    }
    refused.getMessage should include("P2PK")
  }

  "A non-fraud-proof initial transaction" should "still be free to sweep a coinbase" in {
    // Unchanged on purpose: its outputs do not copy an input's proposition, and spending a coinbase
    // is the only thing that moves that ERG somewhere an ordinary wallet can see.
    val (handler, wallet) = handlerOver(walletErg = erg / 10, rewardErg = 5 * erg)
    val inputs = handler.initialTxInputs(oneFee, isFPTx = false)

    inputs.map(_.value).sum should be >= (erg / 2)
    wallet.rewardTrees.keys should contain(inputs.maxBy(_.value).contract.ergoTreeHex)
  }
}
