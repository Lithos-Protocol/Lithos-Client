package api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import configs.NodeContext
import transactions.wallet.WalletMessages.{GetSpendableBalance, SpendableBalance}

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

/**
 * Balances and the collateral position in one read.
 *
 * The collateral half is composed rather than re-derived: it is expensive — two token scans paged
 * to exhaustion — and [[CollateralMarketApiImpl]] already owns it along with its short-TTL snapshot
 * and the invalidation that follows a join or a claim. A second derivation here would have given
 * the panel a differently-stale copy of the same answer.
 *
 * Blocking node IO and a wallet ask, so this runs off the request pool.
 */
@Singleton
class WalletApiImpl @Inject()(nodeContext: NodeContext,
                              collateral: CollateralMarketApi,
                              @Named("wallet-manager") walletManager: ActorRef) extends WalletApi {

  private val QuickAsk = Timeout(5 seconds)

  /** @inheritdoc */
  override def getWalletBalances: WalletBalances = {
    val nodeApi = nodeContext.getNodeApi
    val balances = nodeApi.walletBalances().getOrElse(
      throw LithosApiErrors.LithosUnavailable("could not read wallet balances: is the node wallet unlocked?"))

    // One lookup for every token at once, and none at all for a wallet holding none. Names and
    // decimals are cosmetic, so a node without the index still returns the balances rather than
    // failing the request.
    val info =
      if (balances.assets.isEmpty) Map.empty[String, node.model.TokenInfo]
      else Try(nodeApi.tokensByIds(balances.assets.map(_.tokenId)).getOrElse(Seq.empty))
        .getOrElse(Seq.empty)
        .map(t => t.id -> t).toMap

    // SPENDABLE rather than the node's total: unreserved wallet boxes plus matured coinbases. This
    // is the figure a join quote's `affordNow` is decided against, so a reader comparing the two is
    // comparing like with like. The node's total counts boxes a transaction in flight already owns.
    //
    // Checked rather than cast. A dead wallet times out and a busy one is late, so a reply of the
    // wrong type is a defect here, and a ClassCastException naming two internal classes is not what
    // a reader needs to see for one.
    val spendable = Await.result(
      (walletManager ? GetSpendableBalance)(QuickAsk), QuickAsk.duration) match {
      case reply: SpendableBalance => reply
      case other => throw new IllegalStateException(
        s"the wallet answered GetSpendableBalance with ${other.getClass.getSimpleName}")
    }

    WalletBalances(
      primaryAddress = nodeContext.getNodeWallet.p2pk.toString,
      nanoErgs = spendable.nanoErgs.toString,
      tokens = balances.assets.map(a => WalletToken(
        tokenId = a.tokenId,
        name = info.get(a.tokenId).map(_.name),
        decimals = info.get(a.tokenId).map(_.decimals),
        amount = a.amount.toString)),
      collateral = collateral.getWalletStatus)
  }
}
