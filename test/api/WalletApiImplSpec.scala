package api

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import configs.{VerifiedRegistry, VerifiedRegistrySpec}
import lfsm.LFSMHelpers
import node.NodeApi
import node.model.{NodeAsset, TokenInfo, WalletBalances => NodeWalletBalances}
import org.ergoplatform.appkit.NetworkType
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import support.FakeNodeContext
import transactions.engine.wallet.EngineWalletMessages._

import scala.util.Success

/**
 * The wallet reads above the node and the wallet actor:
 *
 *  - a verified token is described by the shipped list, never by the name its minter chose;
 *  - the node's token index is asked only about tokens the list does not name;
 *  - reservations are reported beside the spendable balance they were taken out of;
 *  - a reward sweep's three answers each become a status the caller can act on.
 */
class WalletApiImplSpec
  extends TestKit(ActorSystem("wallet-api-spec"))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val litId = LFSMHelpers.getLitId(NetworkType.MAINNET).toString
  private val strangerId = "5e" * 32

  /** Stands in for `EngineWalletState`. `claimReply` is a function so a test can answer `RewardClaimFailed`. */
  private class StubWallet(rewards: RewardSummary, claimReply: () => Any) extends Actor {
    override def receive: Receive = {
      case GetSpendableBalance => sender() ! SpendableBalance(182400000000L)
      case GetReservations => sender() ! Reservations(2, 3000000000L)
      case GetUnlockedRewards => sender() ! rewards
      case ClaimUnlockedRewards => sender() ! claimReply()
    }
  }

  private val noRewards = RewardSummary(0, 0, 0L, 0L, None)

  private case class Fixture(api: WalletApiImpl, nodeApi: NodeApi)

  private def fixture(assets: Seq[NodeAsset] = Seq.empty,
                      tokenInfo: Seq[TokenInfo] = Seq.empty,
                      rewards: RewardSummary = noRewards,
                      claimReply: () => Any = () => RewardsClaimed(Seq.empty)): Fixture = {
    val nodeApi = mock[NodeApi]
    val (ctx, _, _) = FakeNodeContext(nodeApi)
    when(nodeApi.walletBalances()).thenReturn(Success(NodeWalletBalances(500, 200000000000L, assets)))
    when(nodeApi.tokensByIds(any[Seq[String]])).thenReturn(Success(tokenInfo))
    val walletRef: ActorRef = system.actorOf(Props(new StubWallet(rewards, claimReply)))
    val api = new WalletApiImpl(ctx, walletRef) {
      override protected lazy val registry: VerifiedRegistry = VerifiedRegistrySpec.shippedMainnet
    }
    Fixture(api, nodeApi)
  }

  "A verified token" should "carry the shipped list's name, not the one its minter chose" in {
    // The index answers whatever was minted. A copycat id is a different token, but the index would
    // happily call the real one anything if asked, so a verified id is never looked up at all.
    val f = fixture(assets = Seq(NodeAsset(litId, 45000000000L)),
      tokenInfo = Seq(TokenInfo(litId, "bb" * 32, 1L, "Free LIT Airdrop", "", 0)))
    val token = f.api.getWalletBalances.tokens.head

    token.verified shouldBe true
    token.name shouldBe Some("LIT")
    token.ticker shouldBe Some("LIT")
    token.decimals shouldBe Some(9)
    verify(f.nodeApi, never()).tokensByIds(any[Seq[String]])
  }

  "An unverified token" should "carry the index's name, and be looked up alone" in {
    val f = fixture(assets = Seq(NodeAsset(litId, 1L), NodeAsset(strangerId, 7L)),
      tokenInfo = Seq(TokenInfo(strangerId, "cc" * 32, 100L, "LIT", "", 2)))
    val token = f.api.getWalletBalances.tokens.find(_.tokenId == strangerId).get

    withClue("a stranger may name its token LIT; only the flag tells the two apart: ") {
      token.name shouldBe Some("LIT")
      token.verified shouldBe false
    }
    token.ticker shouldBe None
    token.decimals shouldBe Some(2)
    verify(f.nodeApi).tokensByIds(Seq(strangerId))
  }

  it should "still be listed when the node has no token index" in {
    val f = fixture(assets = Seq(NodeAsset(strangerId, 7L)))
    when(f.nodeApi.tokensByIds(any[Seq[String]])).thenThrow(new RuntimeException("index disabled"))
    val token = f.api.getWalletBalances.tokens.head
    token.amount shouldBe "7"
    token.name shouldBe None
  }

  "The balances" should "report the spendable figure beside what is reserved, and nothing about collateral" in {
    val balances = fixture().api.getWalletBalances
    balances.nanoErgs shouldBe "182400000000"
    balances.reservedBoxes shouldBe 2
    balances.reservedNanoErgs shouldBe "3000000000"
    (Json.toJson(balances) \ "collateral").toOption shouldBe None
  }

  "The reward summary" should "count every reward box, locked or not, and the unlocked part apart" in {
    val summary = fixture(rewards = RewardSummary(4, 2, 12000000000L, 6000000000L, Some(30))).api.getRewardSummary
    summary.boxes shouldBe 4
    summary.unlockedBoxes shouldBe 2
    summary.totalNanoErgs shouldBe "12000000000"
    summary.unlockedNanoErgs shouldBe "6000000000"
    summary.blocksUntilFirstUnlock shouldBe Some(30)
  }

  "A sweep that failed" should "become a status carrying the reason, not a 500" in {
    // `EngineWalletState` really does answer `RewardClaimFailed`: `runRewardSweep` catches Throwable
    // and completes its promise with it.
    val f = fixture(
      rewards = RewardSummary(0, 2, 0L, 6000000000L, None),
      claimReply = () => RewardClaimFailed("node refused the sweep"))
    val thrown = the[LithosApiErrors.LithosUnprocessable] thrownBy f.api.claimUnlockedRewards
    thrown.getMessage should include("node refused the sweep")
  }

  it should "report nothing-to-claim when the wallet holds no unlocked boxes" in {
    a[WalletApi.NothingToClaim] should be thrownBy fixture().api.claimUnlockedRewards
  }

  it should "report a concurrent sweep rather than success with an empty list" in {
    // The pre-check passed, so an empty result means another caller holds the leases.
    val f = fixture(
      rewards = RewardSummary(0, 2, 0L, 6000000000L, None),
      claimReply = () => RewardsClaimed(Seq.empty))
    val thrown = the[WalletApi.NothingToClaim] thrownBy f.api.claimUnlockedRewards
    thrown.getMessage should include("already sweeping")
  }

  it should "sum only the accepted chunks into what was claimed" in {
    val f = fixture(
      rewards = RewardSummary(3, 3, 9000000000L, 9000000000L, None),
      claimReply = () => RewardsClaimed(Seq(
        RewardClaimChunk("a1" * 32, 2, 6000000000L),
        RewardClaimChunk("a2" * 32, 1, 3000000000L, outcome = "rejected"))))
    val result = f.api.claimUnlockedRewards
    result.claims.map(_.outcome) shouldBe Seq("accepted", "rejected")
    result.claimedNanoErgs shouldBe "6000000000"
  }

  "The verified list" should "name LIT as the shipped list states it" in {
    val tokens = fixture().api.getVerifiedTokens.tokens
    tokens.find(_.tokenId == litId).map(t => (t.ticker, t.decimals)) shouldBe Some(("LIT", 9))
    tokens.exists(_.tokenId == strangerId) shouldBe false
  }
}
