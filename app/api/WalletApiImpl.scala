package api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import configs.{NodeContext, VerifiedRegistry}
import transactions.engine.wallet.EngineWalletMessages._

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.Try

/**
 * Balances, coinbase rewards and the verified token list. Balances and rewards do blocking node IO
 * and wallet asks, so they run off the request pool; the verified list is read once per process.
 */
@Singleton
class WalletApiImpl @Inject()(nodeContext: NodeContext,
                              @Named("transaction-engine") walletManager: ActorRef,
                              arbStakes: transactions.batching.arb.ArbStake) extends WalletApi {

  /** Ask timeout for the fast wallet replies; the claim covers a multi-broadcast sweep. */
  private val QuickAsk = Timeout(5 seconds)
  private val ClaimAsk = Timeout(180 seconds)

  protected lazy val registry: VerifiedRegistry = VerifiedRegistry.forNetwork(nodeContext.getNetwork)

  /** @inheritdoc */
  override def getWalletBalances: WalletBalances = {
    val nodeApi = nodeContext.getNodeApi
    val balances = nodeApi.walletBalances().getOrElse(
      throw LithosApiErrors.LithosUnavailable("could not read wallet balances: is the node wallet unlocked?"))

    // One lookup for the unverified tokens at once, and none when every token is verified. The index's
    // names and decimals are cosmetic, so a node without it still returns the balances.
    val unlisted = balances.assets.map(_.tokenId).filterNot(id => registry.token(id).isDefined)
    val info =
      if (unlisted.isEmpty) Map.empty[String, node.model.TokenInfo]
      else Try(nodeApi.tokensByIds(unlisted).getOrElse(Seq.empty))
        .getOrElse(Seq.empty)
        .map(t => t.id -> t).toMap

    // Spendable means unreserved plain wallet boxes, the figure a join quote's `affordNow` is decided
    // against. The node's total also counts boxes a transaction in flight already owns.
    val spendable = askWallet[SpendableBalance](GetSpendableBalance, QuickAsk)
    val reservations = askWallet[Reservations](GetReservations, QuickAsk)

    WalletBalances(
      primaryAddress = nodeContext.getNodeWallet.p2pk.toString,
      nanoErgs = spendable.nanoErgs.toString,
      reservedBoxes = reservations.boxes,
      reservedNanoErgs = reservations.nanoErgs.toString,
      tokens = balances.assets.map { a =>
        registry.token(a.tokenId) match {
          case Some(v) =>
            WalletToken(a.tokenId, Some(v.name), Some(v.ticker), Some(v.decimals), a.amount.toString, verified = true)
          case None =>
            val t = info.get(a.tokenId)
            WalletToken(a.tokenId, t.map(_.name), None, t.map(_.decimals), a.amount.toString, verified = false)
        }
      })
  }

  /** @inheritdoc */
  override def getRewardSummary: WalletRewardSummary = {
    val rewards = askWallet[RewardSummary](GetUnlockedRewards, QuickAsk)
    WalletRewardSummary(rewards.lockedBoxes, rewards.unlockedBoxes,
      rewards.lockedNanoErgs.toString, rewards.unlockedNanoErgs.toString,
      rewards.blocksUntilFirstUnlock)
  }

  /** @inheritdoc */
  override def claimUnlockedRewards: WalletRewardClaimResult = {
    val summary = askWallet[RewardSummary](GetUnlockedRewards, QuickAsk)
    if (summary.unlockedBoxes == 0)
      throw new WalletApi.NothingToClaim("no unlocked reward boxes to claim")

    Await.result((walletManager ? ClaimUnlockedRewards)(ClaimAsk), ClaimAsk.duration) match {
      case RewardsClaimed(chunks) if chunks.isEmpty =>
        // The pre-check above passed, so another caller is holding the leases right now.
        throw new WalletApi.NothingToClaim("another claim is already sweeping these boxes - try again shortly")
      case RewardsClaimed(chunks) =>
        WalletRewardClaimResult(
          claims = chunks.map(c => WalletRewardClaimedChunk(c.txId, c.boxes, c.nanoErgs.toString, c.outcome)),
          claimedNanoErgs = chunks.filter(_.outcome == "accepted").map(c => BigInt(c.nanoErgs)).sum.toString)
      // The sweep raised before finishing; the reason is the one thing the caller needs.
      case RewardClaimFailed(reason) =>
        throw LithosApiErrors.LithosUnprocessable(s"the reward sweep could not complete: $reason")
      case other =>
        throw new IllegalStateException(s"the wallet answered a claim with ${other.getClass.getSimpleName}")
    }
  }

  /** @inheritdoc */
  override def getVerifiedTokens: VerifiedTokenList =
    VerifiedTokenList(registry.tokens.map(v => VerifiedTokenInfo(v.tokenId, v.name, v.ticker, v.decimals)))

  /** @inheritdoc */
  override def getArbStake: WalletArbStake = {
    val there = atArbAddress(node.model.MempoolOptions.ConfirmedOnly)
    WalletArbStake(
      address = arbStakes.address,
      enabled = arbStakes.target > 0L,
      paused = arbStakes.paused,
      targetNanoErgs = arbStakes.target.toString,
      stakeNanoErgs = arbStakes.shown(there).map(_.value).getOrElse(0L).toString,
      boxes = there.size,
      nanoErgs = there.map(box => BigInt(box.value)).sum.toString)
  }

  /** @inheritdoc */
  override def resumeArbStake: WalletArbStake = {
    import transactions.engine.TransactionEngine
    if (arbStakes.target <= 0L)
      throw LithosApiErrors.LithosUnprocessable("arb is off in this client's configuration (batching.arb.enabled)")
    if (arbStakes.paused) {
      // A box the mempool spends is the reclaim's: resumed before it confirms, a block of ours would undo it
      val unspent = atArbAddress(node.model.MempoolOptions.WithMempool).map(_.boxId).toSet
      val spending = atArbAddress(node.model.MempoolOptions.ConfirmedOnly).count(box => !unspent.contains(box.boxId))
      if (spending > 0)
        throw LithosApiErrors.LithosUnprocessable(s"a pending transaction still spends $spending box(es) at the arb " +
          "address; resume once it confirms")
      Await.result((walletManager ? TransactionEngine.ResumeArb)(QuickAsk), QuickAsk.duration) match {
        case _: TransactionEngine.ArbResumed => ()
        case deferred: TransactionEngine.Deferred =>
          throw LithosApiErrors.LithosUnavailable(s"arb was not resumed: ${deferred.reason}")
        case other =>
          throw new IllegalStateException(s"the engine answered a resume with ${other.getClass.getSimpleName}")
      }
    }
    getArbStake
  }

  private def atArbAddress(mempool: node.model.MempoolOptions): Vector[node.model.NodeBox] =
    arbStakes.boxes(mempool).getOrElse(
      throw LithosApiErrors.LithosUnavailable("could not read the arb address: is the node's extra index on?"))

  /** @inheritdoc */
  override def reclaimArbStake: WalletArbReclaimResult = {
    import transactions.engine.execution.ArbReserveExecution.Reclaimed
    import transactions.engine.{EngineBroadcast, EngineIntent, TransactionEngine}
    // Run by the engine, in the lane the reserve runs in, so the two can never be in flight together
    Await.result((walletManager ? TransactionEngine.Submit(EngineIntent.ArbReclaim))(ClaimAsk), ClaimAsk.duration) match {
      case None => throw new WalletApi.NothingToClaim("no confirmed box at the arb address to reclaim")
      case Some(sent: Reclaimed) if sent.outcome == EngineBroadcast.Rejected =>
        throw LithosApiErrors.LithosUnprocessable("the node refused the reclaim" +
          sent.reason.map(": " + _).getOrElse("") + ". Arb stays off until it is resumed; try again shortly")
      case Some(sent: Reclaimed) =>
        WalletArbReclaimResult(sent.txId, sent.boxes, sent.nanoErgs.toString, sent.outcome, sent.left)
      case deferred: TransactionEngine.Deferred =>
        throw LithosApiErrors.LithosUnavailable(s"the reclaim was not started: ${deferred.reason}")
      case other =>
        throw new IllegalStateException(s"the engine answered a reclaim with ${other.getClass.getSimpleName}")
    }
  }

  /**
   * Ask the wallet actor and check the reply's type instead of casting it. A dead wallet times out and a
   * busy one is late, so a reply of the wrong type is a defect here, reported as one.
   */
  private def askWallet[T](msg: Any, timeout: Timeout)(implicit tag: ClassTag[T]): T =
    Await.result((walletManager ? msg)(timeout), timeout.duration) match {
      case reply if tag.runtimeClass.isInstance(reply) => reply.asInstanceOf[T]
      case other => throw new IllegalStateException(
        s"the wallet answered $msg with ${other.getClass.getSimpleName}, not ${tag.runtimeClass.getSimpleName}")
    }
}
