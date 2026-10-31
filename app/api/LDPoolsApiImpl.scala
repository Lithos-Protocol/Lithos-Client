package api

import akka.actor.{ActorRef, ActorSystem}
import api.models._
import configs.{LithosDexOrdersConfig, LithosDexPoolsConfig, NodeContext, VerifiedPool}
import lithosdex.LDHelpers
import lithosdex.states.{LDFeeValue, LDT2TFeeValue}
import node.MutationConversions._
import node.model.{MempoolOptions, Paging, SortDirection}
import play.api.Configuration
import transactions.engine.execution.{DexPoolCreation, DexPoolExecution}
import transactions.engine.wallet.EngineFunding

import javax.inject.{Inject, Named, Singleton}

/**
 * The pool list and detail, read from [[LDPoolIndex]]'s snapshot, and orders on any pool through
 * [[DexPoolExecution]]. Only a pool's detail and its order routes read the node live.
 */
@Singleton
class LDPoolsApiImpl @Inject()(nodeContext: NodeContext, index: LDPoolIndex,
                               @Named("transaction-engine") engine: ActorRef,
                               @Named("lithosdex-batcher") batcher: ActorRef, config: Configuration,
                               system: ActorSystem) extends LDPoolsApi {

  private lazy val funding = EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher)
  private lazy val orders = new DexPoolExecution(nodeContext, funding, index, LithosDexOrdersConfig(config),
    heldPlacements = () => LithosDexApiImpl.heldPlacements(batcher))
  private lazy val creation = new DexPoolCreation(nodeContext, funding, index)
  private val hidden = LithosDexPoolsConfig(config).hidden

  override def checkCreatePool(request: LDPoolCreateRequest): LDPoolCreateQuote = creation.check(request)

  override def createPool(request: LDPoolCreateExecuteRequest): LDPoolCreated = creation.create(request)

  override def listPoolOrders(poolNft: String): LDOrderList = orders.listOrders(poolNft)

  override def listPoolProvisions(poolNft: String): LDProvisionList = orders.listProvisions(poolNft)

  override def lookupNfts(request: LDNftLookupRequest): LDNftLookupList = {
    val found = orders.lookupNfts(request.tokenIds)
    val tokens = index.snapshot().tokens
    LDNftLookupList(request.tokenIds.map(_.toLowerCase).distinct.map { id =>
      found.get(id).fold(LDNftLookup(id, provisionNft = false)) { f =>
        val minted = f.mint.map(m => LDNftMint(m.height, m.shares.toString, LDAmounts(m.amountX), LDAmounts(m.amountY),
          m.genesis))
        LDNftLookup(id, provisionNft = f.live.isDefined, Some(LDPoolsApiImpl.summary(f.pool, tokens)),
          f.live.map(_.provision), f.live.map(l => LDAmounts(l.amountX)), f.live.map(l => LDAmounts(l.amountY)),
          redeemed = f.live.isEmpty, minted = minted, lastClaimedHeight = f.live.flatMap(_.lastClaimedHeight),
          refreshBountyNanoErg = f.live.flatMap(_.refreshBounty).map(LDAmounts(_)),
          refreshableHeight = f.live.map(_.refreshableHeight))
      }
    })
  }

  override def claimPoolProvision(poolNft: String, boxId: String, request: LDClaimRequest): LDClaimResult =
    orders.claim(poolNft, boxId, request)

  override def checkPoolFlush(poolNft: String): LDFlushCheck = orders.checkFlush(poolNft)

  override def flushPool(poolNft: String, request: LDFlushRequest): LDFlushResult = orders.flush(poolNft, request)

  override def provisionTerms(poolNft: String, boxId: String): (Long, BigInt, BigInt) = {
    val p = orders.provisionOf(poolNft, boxId)
    (p.shares, p.entryX, p.entryY)
  }

  override def checkSwapOrder(poolNft: String, request: LDPoolSwapRequest): LDPoolSwapQuote =
    orders.checkSwap(poolNft, request)

  override def placeSwapOrder(poolNft: String, request: LDPoolSwapExecuteRequest): LDPoolOrderPlaced =
    orders.placeSwap(poolNft, request)

  override def checkDepositOrder(poolNft: String, request: LDPoolDepositRequest): LDPoolDepositQuote =
    orders.checkDeposit(poolNft, request)

  override def placeDepositOrder(poolNft: String, request: LDPoolDepositExecuteRequest): LDPoolOrderPlaced =
    orders.placeDeposit(poolNft, request)

  override def checkRedeemOrder(poolNft: String, request: LDPoolRedeemRequest): LDPoolRedeemQuote =
    orders.checkRedeem(poolNft, request)

  override def placeRedeemOrder(poolNft: String, request: LDPoolRedeemRequest): LDPoolOrderPlaced =
    orders.placeRedeem(poolNft, request)

  /** @inheritdoc */
  override def listPools: LDPoolList = {
    val s = index.snapshot()
    LDPoolList(s.status, s.height, s.truncated,
      s.pools.filterNot(p => hidden.contains(p.nft.toLowerCase)).map(LDPoolsApiImpl.summary(_, s.tokens)))
  }

  /** @inheritdoc */
  override def getPool(poolNft: String): LDPoolDetail = {
    val s = index.snapshot()
    val pool = s.pools.find(_.nft.equalsIgnoreCase(poolNft)).getOrElse {
      if (s.status != "ready") throw LithosApiErrors.LithosUnavailable("the pool index is still building; retry shortly")
      throw LithosApiErrors.LithosNotFound(s"no LithosDex pool with NFT $poolNft")
    }
    LDPoolDetail(LDPoolsApiImpl.summary(pool, s.tokens), vault(pool))
  }

  /** The pool's vault as it stands now: the one unspent box holding its NFT. */
  private def vault(pool: IndexedPool): LDPoolVault = nodeContext.getClient.execute { ctx =>
    val boxes = nodeContext.getNodeApi.unspentBoxesByTokenId(pool.vaultNft, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).getOrElse(
      throw LithosApiErrors.LithosUnavailable(s"could not read the vault of pool ${pool.nft}"))
    val box = boxes match {
      case Seq(one) => one.box.toInputUTXO(ctx)
      case _ => throw LithosApiErrors.LithosUnavailable(s"pool ${pool.nft}'s vault is not one unspent box right now")
    }
    pool.kind match {
      case VerifiedPool.Kind.N2T =>
        val v = LDFeeValue.fromBox(box, 0, ctx.getHeight)
        LDPoolVault(pool.vaultNft, box.id.toString, LDAmounts(v.accX), LDAmounts(v.accY), LDAmounts(v.balanceX),
          LDAmounts(v.balanceY))
      case VerifiedPool.Kind.T2T =>
        val v = LDT2TFeeValue.fromBox(box, 0, ctx.getHeight)
        LDPoolVault(pool.vaultNft, box.id.toString, LDAmounts(v.accX), LDAmounts(v.accY), LDAmounts(v.balanceX),
          LDAmounts(v.balanceY))
    }
  }
}

object LDPoolsApiImpl {

  /** ERG's display facts, for the X side of an ERG/token pool. */
  private val Erg = LDPoolToken("ERG", Some("ERG"), Some("ERG"), Some(9), verified = true)

  def summary(p: IndexedPool, tokens: Map[String, TokenMeta]): LDPoolSummary = {
    def token(id: String) = tokens.get(id).fold(LDPoolToken(id, None, None, None, verified = false))(m =>
      LDPoolToken(id, m.name, m.ticker, m.decimals, m.verified))
    val x = p.tokenX.fold(Erg)(token)
    val y = token(p.tokenY)
    val price = for (dx <- x.decimals; dy <- y.decimals if p.reservesX > 0)
      yield (BigDecimal(p.reservesY) / BigDecimal(10).pow(dy) / (BigDecimal(p.reservesX) / BigDecimal(10).pow(dx))).toDouble
    LDPoolSummary(
      poolNFT = p.nft,
      kind = p.kind.name,
      utxoId = p.boxId,
      inclusionHeight = p.inclusionHeight,
      tokenX = x,
      tokenY = y,
      reservesX = LDAmounts(p.reservesX),
      reservesY = LDAmounts(p.reservesY),
      pendingX = LDAmounts(p.pendingX),
      pendingY = LDAmounts(p.pendingY),
      supply = LDAmounts(p.supply),
      feeParams = LDFeeParams(p.feeParams.toArray, LDHelpers.FEE_DENOM),
      provTokensLeft = LDAmounts(p.provTokensLeft),
      price = price,
      verified = p.verified,
      genesisChecked = p.genesis.isDefined,
      problems = p.genesis.map(_.problems).getOrElse(Seq.empty),
      warnings = p.genesis.map(_.warnings).getOrElse(Seq.empty))
  }
}
