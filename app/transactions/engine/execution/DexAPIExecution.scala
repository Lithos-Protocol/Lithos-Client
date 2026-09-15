package transactions.engine.execution
import api.LithosDexApi

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import cache.LDCache
import lithosdex.states.{LDFeeValue, LDLiquidityState}
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.MempoolOptions
import org.ergoplatform.appkit.{Address, BlockchainContext}
import org.ergoplatform.sdk.ErgoId
import configs.NodeContext
import sigma.ast.ErgoTree
import transactions.batching.BatchingMempool
import transactions.engine.wallet.EngineFunding
import transactions.batching.lithosdex.LDBoxes.Provision
import transactions.batching.lithosdex.{LDBoxes, LDFundedTx, LDOrderBook, LDOrderTransactions, LDOrderTx, LithosDexExecution, LithosDexOrder, LithosDexTransactions}
import work.lithos.mutations.InputUTXO

import javax.inject.Inject

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._
import state.synchronization.CompleteMempool
import transactions.engine.wallet.EngineWalletMessages._
import transactions.engine.DexIntent
import transactions.engine.EngineBroadcast

/**
 * Provides a default implementation for [[LithosDexApi]].
 *
 * Every endpoint reads the live boxes rather than trusting a snapshot: a quote is only worth anything
 * against the box a transaction would actually spend, and the arithmetic in `LDLiquidityPool` is exact
 * against that box. The cache is written on the way past so anything that wants a last-known state has
 * one, but nothing here reads a quote out of it.
 *
 * Box discovery, wallet balances and broadcasting all go through the node — the indexer endpoints for
 * boxes, the wallet endpoints for what this client owns. No explorer is involved.
 */
class DexAPIExecution(nodeContext: NodeContext, walletSelector: EngineFunding,
                      alive: () => Boolean = () => true,
                      orderDefaults: configs.LithosDexOrdersConfig = configs.LithosDexOrdersConfig.Default,
                      heldPlacements: () => Seq[CompleteMempool.MempoolTx] = () => Seq.empty) extends LithosDexApi {
  protected def executionNode: NodeApi = nodeContext.getNodeApi

  /** Default history bucket: a day of blocks at two minutes each */
  private final val DefaultBucket = 720

  // ══════════════════════════════════════════════════════════════════════════
  //  READ-ONLY STATE
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def getPool(ldCache: LDCache): LDPoolInfo = withDex { (ctx, nodeApi) =>
    val box = LDBoxes.poolBox(ctx, nodeApi)
    val state = poolState(ctx, box)
    ldCache.setPool(state)

    val liquidity = state.pool
    val token = tokenInfo(nodeApi, state.tokenY)

    LDPoolInfo(
      poolNFT = state.poolNFT.toString,
      utxoId = state.utxoId,
      tokenY = state.tokenY.toString,
      tokenName = token.map(_.name),
      tokenDecimals = token.map(_.decimals),
      reservesX = LDAmounts(state.reservesX),
      reservesY = LDAmounts(state.reservesY),
      pendingX = LDAmounts(state.pendingX),
      pendingY = LDAmounts(state.pendingY),
      supply = LDAmounts(state.supply),
      feeParams = LDFeeParams(state.feeParams, LDHelpers.FEE_DENOM),
      accX = LDAmounts(state.accX),
      accY = LDAmounts(state.accY),
      provTokensLeft = LDAmounts(state.provTokensLeft),
      canFlush = liquidity.canFlush,
      canDeposit = liquidity.canDeposit,
      syncHeight = state.syncHeight)
  }

  /** @inheritdoc */
  override def getVault(ldCache: LDCache): LDVaultInfo = withDex { (ctx, nodeApi) =>
    val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(vault, ctx.getHeight)

    LDVaultInfo(
      vaultNFT = vault.vaultNFT.toString,
      utxoId = vault.utxoId,
      accX = LDAmounts(vault.accX),
      accY = LDAmounts(vault.accY),
      balanceX = LDAmounts(vault.balanceX),
      balanceY = LDAmounts(vault.balanceY),
      payableX = LDAmounts(vault.payableX),
      payableY = LDAmounts(vault.payableY),
      tokenY = vault.tokenY.map(_.toString),
      vaultMin = LDAmounts(LDHelpers.VAULT_MIN),
      lastFlushHeight = ldCache.getLastFlushHeight,
      syncHeight = vault.syncHeight)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SWAP
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkSwap(request: LDSwapRequest, ldCache: LDCache): LDSwapQuote = withDex { (ctx, nodeApi) =>
    val amountIn = positive("amountIn", LDAmounts.parseLong("amountIn", request.amountIn))
    LDSwapQuote(pool(ctx, nodeApi, ldCache).simSwap(amountIn, request.ergIn))
  }

  /** @inheritdoc */
  override def checkSwapOutput(request: LDSwapOutputRequest, ldCache: LDCache): LDSwapQuote =
    withDex { (ctx, nodeApi) =>
      val amountOut = positive("amountOut", LDAmounts.parseLong("amountOut", request.amountOut))
      val liquidity = pool(ctx, nodeApi, ldCache)

      // An output must stay strictly under the reserve it is drawn from — the curve only approaches it.
      // That is a property of the pool rather than of the request, which is why it is a 422.
      liquidity.simSwapForOutput(amountOut, request.ergIn).map(LDSwapQuote(_)).getOrElse(
        throw LithosUnprocessable(
          s"the pool cannot produce $amountOut: it holds only " +
            s"${if (request.ergIn) liquidity.reservesY else liquidity.reservesX} on that side, " +
            "and an output has to stay strictly under the reserve it is drawn from"))
    }

  /** @inheritdoc */
  override def swap(request: LDSwapExecuteRequest, ldCache: LDCache): LDSwapResult =
    mutating("this swap") {
      withDex { (ctx, nodeApi) =>
        val amountIn = positive("amountIn", LDAmounts.parseLong("amountIn", request.amountIn))
        // A negative floor is no floor: `amountOut >= minOutput` is then true at any price the pool
        // happens to offer, which is exactly what minOutput exists to prevent.
        val minOutput = nonNegative("minOutput", LDAmounts.parseLong("minOutput", request.minOutput))

        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        ldCache.setPool(poolState(ctx, poolBox))

        val built = fund(LithosDexTransactions.swap(
          ctx, wallet, poolBox, amountIn, request.ergIn, minOutput))
        val submission = send(ctx, built)
        LDSwapResult(LDAmounts(built.value.quote.amountOut), submission.txId, outcome = submission.outcome)
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  DEPOSIT
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkDeposit(request: LDDepositRequest, ldCache: LDCache): LDDepositQuote =
    withDex { (ctx, nodeApi) =>
      val liquidity = pool(ctx, nodeApi, ldCache)
      val shares = LDAmounts.parseLong("shares", request.shares)
      val availableX = LDAmounts.parseLong("availableX", request.availableX)
      val availableY = LDAmounts.parseLong("availableY", request.availableY)

      val quote = shares match {
        case Some(requested) => liquidity.simDepositForShares(positive("shares", requested))
        case None =>
          // Both sides or neither: a deposit is balanced by construction, so one side alone says
          // nothing about how many shares the funds can buy.
          (availableX, availableY) match {
            case (Some(erg), Some(tokens)) =>
              liquidity.simDeposit(positive("availableX", erg), positive("availableY", tokens))
            case _ => throw LithosBadRequest(
              "supply either 'shares', or both 'availableX' and 'availableY'")
          }
      }
      LDDepositQuote(quote)
    }

  /** @inheritdoc */
  override def deposit(request: LDDepositExecuteRequest, ldCache: LDCache): LDDepositResult =
    mutating("this deposit") {
      withDex { (ctx, nodeApi) =>
        val shares = positive("shares", LDAmounts.parseLong("shares", request.shares))
        val maxX = LDAmounts.parseLong("maxAmountX", request.maxAmountX)
        val maxY = LDAmounts.parseLong("maxAmountY", request.maxAmountY)

        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        val state = poolState(ctx, poolBox)
        ldCache.setPool(state)

        // Priced against the box that will be spent, before anything is built. A deposit's cost comes
        // from the reserves, so a pool that traded since the quote charges a different price.
        val quote = state.pool.simDepositForShares(shares)
        atMost("maxAmountX", maxX, quote.amountX, "the ERG this deposit costs")
        atMost("maxAmountY", maxY, quote.amountY, "the token amount this deposit costs")

        val built = fund(LithosDexTransactions.deposit(ctx, wallet, poolBox, shares))
        val submission = send(ctx, built)
        LDDepositResult(
          provisionBoxId = built.value.provisionBoxId,
          ownerNFT = built.value.ownerNFT.toString,
          shares = LDAmounts(built.value.quote.shares),
          amountX = LDAmounts(built.value.quote.amountX),
          amountY = LDAmounts(built.value.quote.amountY),
          txId = submission.txId, outcome = submission.outcome)
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  REDEEM
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkRedeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemQuote =
    withDex { (ctx, nodeApi) =>
      val liquidity = pool(ctx, nodeApi, ldCache)
      val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
      val prov = LDBoxes.provisionById(ctx, nodeApi, request.provisionBoxId)

      val quote = liquidity.simRedeem(prov.shares)
      // Settled but unclaimed fees are forfeited when the box is spent. The contract permits that on
      // purpose, so it cannot be refused here — but a client that does not show it will lose someone
      // their money silently.
      val (owedX, owedY) = vault.owed(prov.shares, prov.entryX, prov.entryY)
      val (unclaimedX, unclaimedY) = (math.max(0L, owedX), math.max(0L, owedY))

      LDRedeemQuote(
        shares = LDAmounts(quote.shares),
        amountX = LDAmounts(quote.amountX),
        amountY = LDAmounts(quote.amountY),
        withinMinSupply = quote.withinMinSupply,
        unclaimedX = LDAmounts(unclaimedX),
        unclaimedY = LDAmounts(unclaimedY))
    }

  /** @inheritdoc */
  override def redeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemResult =
    mutating("this redemption") {
      withDex { (ctx, nodeApi) =>
        val minX = LDAmounts.parseLong("minAmountX", request.minAmountX)
        val minY = LDAmounts.parseLong("minAmountY", request.minAmountY)

        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        val state = poolState(ctx, poolBox)
        ldCache.setPool(state)

        val prov = LDBoxes.provisionById(ctx, nodeApi, request.provisionBoxId)
        val quote = state.pool.simRedeem(prov.shares)
        atLeast("minAmountX", minX, quote.amountX, "the ERG this redemption returns")
        atLeast("minAmountY", minY, quote.amountY, "the token amount this redemption returns")

        // Spending the provision box forfeits whatever the vault had already settled to it. The
        // contract allows it and this client must not refuse it, but it is not something a caller
        // does by leaving a field out — so it has to be asked for by name.
        val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
        val (unclaimedX, unclaimedY) = vault.owed(prov.shares, prov.entryX, prov.entryY)
        if ((unclaimedX > 0 || unclaimedY > 0) && !request.acknowledgeUnclaimedFees.contains(true))
          throw LithosBadRequest(
            s"provision ${prov.boxId} has $unclaimedX nanoERG and $unclaimedY token(s) of settled " +
              "fees that closing it would forfeit to nobody. Claim first, or resend with " +
              "'acknowledgeUnclaimedFees': true.")

        val built = fund(LithosDexTransactions.redeem(
          ctx, wallet, poolBox, prov))
        val submission = send(ctx, built)
        LDRedeemResult(
          shares = LDAmounts(built.value.quote.shares),
          amountX = LDAmounts(built.value.quote.amountX),
          amountY = LDAmounts(built.value.quote.amountY),
          txId = submission.txId, outcome = submission.outcome)
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  PROVISIONS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def listProvisions(ldCache: LDCache): LDProvisionList = withDex { (ctx, nodeApi) =>
    val liquidity = pool(ctx, nodeApi, ldCache)
    val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(vault, ctx.getHeight)

    // A provision whose NFT waits in one of this wallet's redeem orders is still this wallet's, and the
    // wallet cannot claim it until the order fills or is cancelled
    val inOrders = LDOrderBook.redeemOrders(ctx, nodeApi, wallet.signableTrees)
    LDProvisionList(LDBoxes.ownedProvisions(ctx, nodeApi, inOrders.keySet).map { prov =>
      val order = inOrders.get(prov.ownerNFT.toString)
      val described = describe(liquidity, vault, prov)
      described.copy(canClaim = described.canClaim && order.isEmpty, redeemOrderBoxId = order)
    })
  }

  /**
   * Both fee figures, and their difference.
   *
   * `accrued` is what the pool says the provision has earned, unflushed fees included. `claimable` is
   * what the vault can actually pay, which is smaller by whatever has not been flushed. Returning one
   * without the other misleads — the gap is precisely what a flush would release.
   */
  private def describe(liquidity: LDLiquidityPool, vault: LDFeeValue, prov: Provision): LDProvision = {
    val (accruedX, accruedY) = liquidity.feesAccrued(prov.shares, prov.entryX, prov.entryY)
    val (owedX, owedY) = vault.owed(prov.shares, prov.entryX, prov.entryY)

    // A provision opened or resized while the pool held unflushed fees enters at an accumulator AHEAD
    // of the vault's, and the contract's own arithmetic is negative there. Execution already refuses
    // that case; showing it as a negative claim reads as a debt the holder owes. Clamped for display
    // only — nothing here is used to build a transaction.
    val awaitingFlush = owedX < 0 || owedY < 0
    val claimableX = math.max(0L, owedX)
    val claimableY = math.max(0L, owedY)

    LDProvision(
      boxId = prov.boxId,
      ownerNFT = prov.ownerNFT.toString,
      shares = LDAmounts(prov.shares),
      entryX = LDAmounts(prov.entryX),
      entryY = LDAmounts(prov.entryY),
      value = LDAmounts(prov.value),
      createdHeight = prov.createdHeight,
      shareOfSupply = liquidity.shareOfSupply(prov.shares),
      accruedX = LDAmounts(accruedX),
      accruedY = LDAmounts(accruedY),
      claimableX = LDAmounts(claimableX),
      claimableY = LDAmounts(claimableY),
      unflushedX = LDAmounts(math.max(0L, accruedX - claimableX)),
      unflushedY = LDAmounts(math.max(0L, accruedY - claimableY)),
      canClaim = vault.canClaim(prov.entryX, prov.entryY),
      awaitingFlush = awaitingFlush)
  }

  /** @inheritdoc */
  override def claimProvision(boxId: String, request: LDClaimRequest, ldCache: LDCache): LDClaimResult =
    mutating("this claim") {
      withDex { (ctx, nodeApi) =>
        val minX = LDAmounts.parseLong("minAmountX", request.minAmountX)
        val minY = LDAmounts.parseLong("minAmountY", request.minAmountY)

        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        expectVault(request.expectedVaultBoxId, vaultBox)
        val vault = vaultState(ctx, vaultBox)
        ldCache.observeVault(vault, ctx.getHeight)

        val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
        expectProvision(request.expectedProvisionBoxId, prov)
        if (!vault.canClaim(prov.entryX, prov.entryY))
          throw LithosBadRequest(
            s"provision $boxId has nothing to settle: its entry is level with the vault's accumulator. " +
              "The pool has not been flushed since it last claimed; flush first, or wait for one.")

        val (owedX, owedY) = vault.owed(prov.shares, prov.entryX, prov.entryY)
        atLeast("minAmountX", minX, owedX, "the ERG this claim settles")
        atLeast("minAmountY", minY, owedY, "the token amount this claim settles")

        val built = fund(LithosDexTransactions.claim(
          ctx, wallet, vaultBox, Seq(prov)))

        // OUTPUTS(1) is this provision's successor: the vault sits at input 0 and each provision at
        // 1..count, each finding its own successor at the matching output index.
        val successor = built.value.tx.getOutputsToSpend.get(1).getId.toString
        val submission = send(ctx, built)
        LDClaimResult(LDAmounts(built.value.claimedX), LDAmounts(built.value.claimedY), successor,
          submission.txId, outcome = submission.outcome)
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  RESIZE
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkResize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeQuote =
    withDex { (ctx, nodeApi) =>
      val liquidity = pool(ctx, nodeApi, ldCache)
      val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
      LDResizeQuote(liquidity.simResize(prov.shares, newShares(request, prov), prov.entryX, prov.entryY))
    }

  /** @inheritdoc */
  override def resize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeResult =
    mutating("this resize") {
      withDex { (ctx, nodeApi) =>
        val maxX = LDAmounts.parseLong("maxAmountX", request.maxAmountX)
        val maxY = LDAmounts.parseLong("maxAmountY", request.maxAmountY)
        val minX = LDAmounts.parseLong("minAmountX", request.minAmountX)
        val minY = LDAmounts.parseLong("minAmountY", request.minAmountY)

        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        expectVault(request.expectedVaultBoxId, vaultBox)
        val state = poolState(ctx, poolBox)
        ldCache.setPool(state)

        val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
        expectProvision(request.expectedProvisionBoxId, prov)
        val next = newShares(request, prov)

        // The two directions need opposite bounds: increasing takes funds from the caller, decreasing
        // returns them. Checking only one leaves the other side priced by whatever the pool became.
        val quote = state.pool.simResize(prov.shares, next, prov.entryX, prov.entryY)
        if (quote.increasing) {
          atMost("maxAmountX", maxX, quote.amountX, "the ERG this resize contributes")
          atMost("maxAmountY", maxY, quote.amountY, "the token amount this resize contributes")
        } else {
          atLeast("minAmountX", minX, quote.amountX, "the ERG this resize withdraws")
          atLeast("minAmountY", minY, quote.amountY, "the token amount this resize withdraws")
        }

        val built = fund(LithosDexTransactions.resize(
          ctx, wallet, poolBox, vaultBox, prov, next))

        val submission = send(ctx, built)
        LDResizeResult(
          boxId = built.value.boxId,
          newShares = LDAmounts(built.value.quote.newShares),
          amountX = LDAmounts(built.value.quote.amountX),
          amountY = LDAmounts(built.value.quote.amountY),
          txId = submission.txId, outcome = submission.outcome)
      }
    }

  /** A resize to zero is a redemption, and a resize to the current size moves nothing: both refused. */
  private def newShares(request: LDResizeRequest, prov: Provision): Long = {
    val next = LDAmounts.parseLong("newShares", request.newShares)
    if (next <= 0) throw LithosBadRequest(
      "'newShares' must be positive: a resize to zero is a redemption, use /dex/redeem")
    if (next == prov.shares) throw LithosBadRequest(
      s"provision ${prov.boxId} is already ${prov.shares} shares: a resize must change the size")
    next
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FLUSH
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkFlush(ldCache: LDCache): LDFlushCheck = withDex { (ctx, nodeApi) =>
    val liquidity = pool(ctx, nodeApi, ldCache)
    val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(vault, ctx.getHeight)

    LDFlushCheck(
      pendingX = LDAmounts(liquidity.pendingX),
      pendingY = LDAmounts(liquidity.pendingY),
      canFlush = liquidity.canFlush,
      poolAccX = LDAmounts(liquidity.accX),
      poolAccY = LDAmounts(liquidity.accY),
      vaultAccX = LDAmounts(vault.accX),
      vaultAccY = LDAmounts(vault.accY),
      vaultBalanceAfterX = LDAmounts(vault.balanceX + liquidity.pendingX),
      vaultBalanceAfterY = LDAmounts(vault.balanceY + liquidity.pendingY),
      lastFlushHeight = ldCache.getLastFlushHeight,
      currentHeight = ctx.getHeight)
  }

  /** @inheritdoc */
  override def flush(request: LDFlushRequest, ldCache: LDCache): LDFlushResult =
    mutating("this flush") {
      withDex { (ctx, nodeApi) =>
        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        val state = poolState(ctx, poolBox)
        ldCache.setPool(state)

        if (!state.pool.canFlush)
          throw LithosBadRequest(
            "nothing is pending: the pool refuses a flush that moves nothing. Fees accrue on swaps, so " +
              "there is nothing to move until one happens.")

        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        expectVault(request.expectedVaultBoxId, vaultBox)

        val built = fund(LithosDexTransactions.flush(ctx, wallet, poolBox, vaultBox))
        val submission = send(ctx, built)
        LDFlushResult(LDAmounts(built.value.flushedX), LDAmounts(built.value.flushedY),
          submission.txId, outcome = submission.outcome)
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  FEE HISTORY
  // ══════════════════════════════════════════════════════════════════════════
  //
  // The pool NFT is a singleton, so the boxes that have held it ARE the pool's history — one per
  // transaction that ever touched it, each carrying the accumulators and the supply as they stood.

  /** @inheritdoc */
  override def getFeeHistory(from: Option[Int],
                             to: Option[Int],
                             bucket: Option[Int],
                             ldCache: LDCache): LDFeeHistory = withDex { (ctx, nodeApi) =>
    val (snapshots, complete) = lineage(nodeApi, ctx)
    val (lo, hi, size) = range(snapshots, from, to, bucket)

    // A swap advances an accumulator by its fee over the supply live to earn it, so running that
    // backwards over each step recovers the fee exactly: delta * supply / SCALE, with the supply from
    // BEFORE the step, since a swap never changes it.
    val cumulative = runningTotals(snapshots, lo, hi) { (prev, next) =>
      (feeFromAccumulator(next.accX - prev.accX, prev.supply),
        feeFromAccumulator(next.accY - prev.accY, prev.supply))
    }

    LDFeeHistory(bucketed(nodeApi, cumulative, lo, hi, size), partial = !complete)
  }

  /** @inheritdoc */
  override def getProvisionFeeHistory(boxId: String,
                                      from: Option[Int],
                                      to: Option[Int],
                                      bucket: Option[Int],
                                      ldCache: LDCache): LDProvisionFeeHistory = withDex { (ctx, nodeApi) =>
    val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
    val (snapshots, complete) = lineage(nodeApi, ctx)
    val (lo, hi, size) = range(snapshots, from, to, bucket)

    // A provision's curve is its share of the pool accumulator's movement since its own entry. Its
    // size is read as it stands now: a resize rewrites both the size and the entry together so that
    // what is already earned comes out unchanged, and neither the old size nor the height it changed
    // at is recorded anywhere on chain.
    val cumulative = runningTotals(snapshots, lo, hi) { (prev, next) =>
      (share(prov.shares, earnedOver(prev.accX, next.accX, prov.entryX)),
        share(prov.shares, earnedOver(prev.accY, next.accY, prov.entryY)))
    }

    LDProvisionFeeHistory(prov.boxId, bucketed(nodeApi, cumulative, lo, hi, size),
      partial = !complete)
  }

  private type Snapshot = LDBoxes.PoolSnapshot

  /**
   * The lineage, and whether the walk saw all of it.
   *
   * `complete = false` is surfaced on the response rather than raised. The pool's lineage grows by
   * one box per transaction that touches it, so a ceiling reached once is reached forever — turning
   * a truncated read into an error meant these endpoints would begin failing permanently on a busy
   * pool and never recover.
   */
  private def lineage(nodeApi: NodeApi, ctx: BlockchainContext): (Seq[Snapshot], Boolean) =
    LDBoxes.poolHistory(ctx, nodeApi)

  /**
   * The height window and bucket size a request actually covers.
   *
   * Defaults come from the lineage itself rather than from the chain, so a request with no bounds gets
   * the pool's whole life and no empty leading buckets.
   */
  private def range(snapshots: Seq[Snapshot],
                    from: Option[Int],
                    to: Option[Int],
                    bucket: Option[Int]): (Int, Int, Int) = {
    if (snapshots.isEmpty) throw LithosBadRequest(
      "no pool history is indexed: the node needs extraIndex on for this endpoint")

    val lo = from.getOrElse(snapshots.head.height)
    val hi = to.getOrElse(snapshots.last.height)
    if (hi < lo) throw LithosBadRequest(s"'to' ($hi) is below 'from' ($lo)")

    val size = bucket.getOrElse(DefaultBucket)
    if (size <= 0) throw LithosBadRequest(s"'bucket' must be positive, got $size")
    (lo, hi, size)
  }

  /**
   * Fold the lineage into a cumulative series over the requested window.
   *
   * Only steps landing inside the window are counted,
   * so the series starts at zero at `lo` and reads as "since the start of the range".
   */
  private def runningTotals(snapshots: Seq[Snapshot], lo: Int, hi: Int)
                           (step: (Snapshot, Snapshot) => (Long, Long)): Seq[(Int, Long, Long)] = {
    var totalX = 0L
    var totalY = 0L
    snapshots.sliding(2).collect { case Seq(prev, next) if next.height >= lo && next.height <= hi =>
      val (dx, dy) = step(prev, next)
      totalX += dx
      totalY += dy
      (next.height, totalX, totalY)
    }.toSeq
  }

  /**
   * Collapse a per-transaction series into fixed-width height buckets.
   *
   * The last reading inside a bucket is that bucket's cumulative total, and its period is the distance
   * from the previous bucket — so the periods sum back to the cumulative, exactly.
   */
  private def bucketed(nodeApi: NodeApi,
                       series: Seq[(Int, Long, Long)],
                       lo: Int,
                       hi: Int,
                       size: Int): Seq[LDFeeHistoryPoint] = {
    val buckets = series
      .groupBy { case (height, _, _) => (height - lo) / size }
      .toSeq
      .sortBy(_._1)
      .map { case (index, points) =>
        // The LAST reading in the bucket
        val (_, cumX, cumY) = points.last
        (math.min(lo + (index + 1) * size - 1, hi), cumX, cumY)
      }

    val timestamps = LDBoxes.timestampsAt(nodeApi, buckets.map(_._1))

    buckets.zip((0L, 0L) +: buckets.map { case (_, x, y) => (x, y) }).map {
      case ((height, cumX, cumY), (prevX, prevY)) =>
        LDFeeHistoryPoint(
          height = height,
          timestamp = timestamps.get(height),
          periodX = LDAmounts(cumX - prevX),
          periodY = LDAmounts(cumY - prevY),
          cumulativeX = LDAmounts(cumX),
          cumulativeY = LDAmounts(cumY))
    }
  }

  /** Invert one step of the pool's accumulator: `delta * supply / SCALE` is the fee that moved it. */
  private def feeFromAccumulator(delta: BigInt, supply: Long): Long =
    if (delta <= 0 || supply <= 0) 0L else (delta * BigInt(supply) / LDHelpers.SCALE).toLong

  /**
   * How much of one accumulator step a provision was actually present for.
   *
   * A step that finished before the provision entered earns it nothing, and the step it entered
   * during earns it only the part above its entry — which is what stops the series from crediting a
   * provision with fees that accrued before it existed.
   */
  private def earnedOver(before: BigInt, after: BigInt, entry: BigInt): BigInt =
    (after - before.max(entry)).max(0)

  private def share(shares: Long, delta: BigInt): Long =
    if (delta <= 0) 0L else (BigInt(shares) * delta / LDHelpers.SCALE).toLong

  // ══════════════════════════════════════════════════════════════════════════
  //  PRICE HISTORY
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def getPriceHistory(range: Option[String],
                               bucket: Option[Int],
                               ldCache: LDCache): LDPriceHistory = withDex { (ctx, nodeApi) =>
    val name = range.map(_.trim.toUpperCase).getOrElse(LDPriceHistory.Default)
    val blocks = LDPriceHistory.Ranges.getOrElse(name, throw LithosBadRequest(
      s"'range' must be one of ${LDPriceHistory.Ranges.keys.toSeq.sorted.mkString(", ")}, got '$name'"))
    val size = bucket.getOrElse(math.max(1, blocks / LDPriceHistory.TargetPoints))
    if (size <= 0) throw LithosBadRequest(s"'bucket' must be positive, got $size")
    // A bucket small enough to make thousands of points is refused rather than served. Nothing
    // renders that many, only the first 250 could carry a timestamp, and the response would be
    // megabytes — the caller asked for something specific, so say what would work instead.
    if (blocks / size > LDPriceHistory.MaxPoints)
      throw LithosBadRequest(
        s"'bucket' of $size over $name would return more than ${LDPriceHistory.MaxPoints} points; " +
          s"use at least ${blocks / LDPriceHistory.MaxPoints + 1}")

    // The live box, not the newest indexed one. The chart is drawn beside a stat strip fed from the
    // same live read, and the index lags the mempool — ending the series on a stale point makes the
    // two visibly disagree about the current price.
    val livePool = LDBoxes.poolBox(ctx, nodeApi)
    val liveState = poolState(ctx, livePool)
    ldCache.setPool(liveState)
    val live = liveState.pool

    val decimals = tokenInfo(nodeApi, live.tokenY).map(_.decimals).getOrElse(0)
    val (snapshots, complete) = lineage(nodeApi, ctx)

    val lo = math.max(0, ctx.getHeight - blocks)
    val inRange = snapshots.filter(_.height >= lo)
    // `partial` covers both ways the window can be short: a lineage the walk could not reach the
    // start of, and one whose earliest point is already inside the range because the pool is younger
    // than the range asked for.
    val reachedBack = snapshots.headOption.exists(_.height <= lo)

    // One point per bucket, the last reading in it, so a bucket holding several transitions reports
    // the price it ended at rather than the first one it saw.
    val bucketed = inRange
      .groupBy(s => (s.height - lo) / size)
      .toSeq.sortBy(_._1)
      .map { case (_, points) => points.maxBy(s => (s.height, s.globalIndex)) }

    val current = LDPricePoint(
      height = ctx.getHeight,
      timestamp = None,
      price = spotPrice(live.reservesX, live.reservesY, decimals),
      reservesX = LDAmounts(live.reservesX),
      reservesY = LDAmounts(live.reservesY))

    val timestamps = LDBoxes.timestampsAt(nodeApi, bucketed.map(_.height))
    val points = bucketed.map { s =>
      LDPricePoint(
        height = s.height,
        timestamp = timestamps.get(s.height),
        price = spotPrice(s.reservesX, s.reservesY, decimals),
        reservesX = LDAmounts(s.reservesX),
        reservesY = LDAmounts(s.reservesY))
    }
    // Replace rather than append when the last bucket is already at the current height, so the
    // series never carries two points for one height.
    val series = (if (points.lastOption.exists(_.height >= current.height)) points.init else points) :+ current

    val first = series.headOption.map(_.price).getOrElse(0.0)
    val changePct = if (first <= 0) 0.0 else (current.price - first) / first

    LDPriceHistory(name, changePct, partial = !complete || !reachedBack, history = series)
  }

  /**
   * Token per ERG with the token's decimals applied, for display.
   *
   * A double is right here and nowhere else in this tag: it is a number to draw, not one to spend.
   * The raw reserves travel with every point so a client can redo this exactly.
   */
  private def spotPrice(reservesX: Long, reservesY: Long, decimals: Int): Double =
    if (reservesX <= 0 || reservesY <= 0) 0.0
    else {
      val tokens = BigDecimal(reservesY) / BigDecimal(10).pow(decimals)
      val ergs = BigDecimal(reservesX) / BigDecimal(10).pow(9)
      (tokens / ergs).toDouble
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  RECENT ACTIVITY
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def getRecentActivity(limit: Option[Int]): LDRecentActivity =
    withDex { (ctx, nodeApi) =>
      val want = limit.getOrElse(LDRecentActivity.DefaultLimit)
      if (want <= 0) throw LithosBadRequest(s"'limit' must be positive, got $want")

      // Every confirmed box, not just the newest: a mempool transaction can spend a box the index
      // has not caught up to being the tip, and chaining by id resolves that without ordering.
      val (snapshots, _) = lineage(nodeApi, ctx)
      val newest = LDBoxes.recentTransitions(ctx, nodeApi, snapshots, math.min(want, LDRecentActivity.MaxLimit))
      val timestamps = LDBoxes.timestampsAt(nodeApi, newest.flatMap(_.height))
      LDRecentActivity(newest.map(activityEntry(_, timestamps)))
    }

  private def activityEntry(t: LDBoxes.PoolTransition, timestamps: Map[Int, Long]): LDActivityEntry = {
    val liquidity = t.kind != LDBoxes.TransitionKind.Swap
    LDActivityEntry(
      txId = t.txId,
      `type` = t.kind.name,
      via = if (t.orderBoxId.isDefined) "ORDER" else "DIRECT",
      orderBoxId = t.orderBoxId,
      status = if (t.height.isDefined) "CONFIRMED" else "MEMPOOL",
      height = t.height,
      timestamp = t.height.flatMap(timestamps.get),
      ergIn = t.swap.map(_.ergIn),
      amountIn = t.swap.map(s => LDAmounts(s.amountIn)),
      amountOut = t.swap.map(s => LDAmounts(s.amountOut)),
      amountX = if (liquidity) Some(LDAmounts(t.amountX)) else None,
      amountY = if (liquidity) Some(LDAmounts(t.amountY)) else None,
      shares = if (liquidity && t.kind != LDBoxes.TransitionKind.Flush) Some(LDAmounts(t.shares)) else None)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  ORDERS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def listOrders(ldCache: LDCache): LDOrderList = withDex { (ctx, nodeApi) =>
    val owned = LDOrderBook.owned(ctx, nodeApi, wallet.signableTrees, mempoolSnapshot(), heldPlacements())
    if (owned.isEmpty) LDOrderList(Seq.empty)
    else {
      val liquidity = pool(ctx, nodeApi, ldCache)
      val provisions = redeemProvisions(ctx, nodeApi, owned.map(_.order))
      LDOrderList(owned.map(o => describeOrder(ctx, o, liquidity, provisions)))
    }
  }

  /** @inheritdoc */
  override def checkSwapOrder(request: LDSwapOrderRequest, ldCache: LDCache): LDSwapOrderQuote =
    withDex { (ctx, nodeApi) =>
      val amountIn = positive("amountIn", LDAmounts.parseLong("amountIn", request.amountIn))
      val fees = orderFees(request.executorFee, request.maxMinerFee)
      val q = pool(ctx, nodeApi, ldCache).simSwap(amountIn, request.ergIn)
      LDSwapOrderQuote(
        swap = LDSwapQuote(q),
        executorFee = LDAmounts(fees.executorFee),
        maxMinerFee = LDAmounts(fees.maxMinerFee),
        // The executor fee comes out of the ERG a token sale releases, and on top of an ERG sale
        netOutput = LDAmounts(if (request.ergIn) q.amountOut else q.amountOut - fees.executorFee),
        totalErgRequired = LDAmounts(exact(LDOrderTransactions.swapValue(amountIn, request.ergIn, fees.executorFee))),
        ergReturned = LDAmounts(LDOrderTransactions.RETURNED),
        networkFee = LDAmounts(LithosDexTransactions.TX_FEE))
    }

  /** @inheritdoc */
  override def placeSwapOrder(request: LDSwapOrderExecuteRequest, ldCache: LDCache): LDOrderPlacementResult =
    withDex { (ctx, nodeApi) =>
      val amountIn = positive("amountIn", LDAmounts.parseLong("amountIn", request.amountIn))
      // A zero floor fills at whatever price the pool offers when the order executes
      val minOutput = positive("minOutput", LDAmounts.parseLong("minOutput", request.minOutput))
      val fees = orderFees(request.executorFee, request.maxMinerFee)
      val liquidity = pool(ctx, nodeApi, ldCache)
      placed(ctx, liquidity, LithosDexExecution.NoProvisions, exact(LDOrderTransactions.swap(
        ctx, wallet, liquidity, amountIn, request.ergIn, minOutput, fees.executorFee, fees.maxMinerFee)))
    }

  /** @inheritdoc */
  override def checkDepositOrder(request: LDDepositOrderRequest, ldCache: LDCache): LDDepositOrderQuote =
    withDex { (ctx, nodeApi) =>
      val amountX = positive("amountX", LDAmounts.parseLong("amountX", request.amountX))
      val amountY = positive("amountY", LDAmounts.parseLong("amountY", request.amountY))
      val fees = orderFees(request.executorFee, request.maxMinerFee)
      val liquidity = pool(ctx, nodeApi, ldCache)
      // The same split the executor applies, so the quote is the fill the pool would give right now
      val split = LithosDexExecution.depositSplit(liquidity, amountX, amountY)
      val shares = split.shares.max(0)
      LDDepositOrderQuote(
        shares = LDAmounts(shares),
        amountX = LDAmounts(split.takenX),
        amountY = LDAmounts(split.takenY),
        excessX = LDAmounts(split.excessX),
        excessY = LDAmounts(split.excessY),
        shareOfSupply = if (shares <= 0) 0.0 else (shares.toDouble / (BigInt(liquidity.supply) + shares).toDouble),
        provisionBoxValue = LDAmounts(LDHelpers.PROVISION_MIN),
        executorFee = LDAmounts(fees.executorFee),
        maxMinerFee = LDAmounts(fees.maxMinerFee),
        totalErgRequired = LDAmounts(exact(LDOrderTransactions.depositValue(amountX, fees.executorFee))),
        ergReturned = LDAmounts(LDOrderTransactions.RETURNED),
        networkFee = LDAmounts(LithosDexTransactions.TX_FEE))
    }

  /** @inheritdoc */
  override def placeDepositOrder(request: LDDepositOrderExecuteRequest, ldCache: LDCache): LDOrderPlacementResult =
    withDex { (ctx, nodeApi) =>
      val amountX = positive("amountX", LDAmounts.parseLong("amountX", request.amountX))
      val amountY = positive("amountY", LDAmounts.parseLong("amountY", request.amountY))
      // The only protection a deposit order has against a pool moved earlier in the same block
      val minShares = positive("minShares", LDAmounts.parseLong("minShares", request.minShares))
      val fees = orderFees(request.executorFee, request.maxMinerFee)
      val liquidity = pool(ctx, nodeApi, ldCache)
      if (!liquidity.canDeposit)
        throw LithosUnprocessable("the pool has no provision tokens left, so no deposit can fill")
      placed(ctx, liquidity, LithosDexExecution.NoProvisions, exact(LDOrderTransactions.deposit(
        ctx, wallet, liquidity, amountX, amountY, minShares, fees.executorFee, fees.maxMinerFee)))
    }

  /** @inheritdoc */
  override def checkRedeemOrder(request: LDRedeemOrderRequest, ldCache: LDCache): LDRedeemOrderQuote =
    withDex { (ctx, nodeApi) =>
      val fees = orderFees(request.executorFee, request.maxMinerFee)
      val liquidity = pool(ctx, nodeApi, ldCache)
      val vault = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
      val prov = LDBoxes.provisionById(ctx, nodeApi, request.provisionBoxId)
      redeemOrderQuote(liquidity, vault, prov, fees)
    }

  /** @inheritdoc */
  override def placeRedeemOrder(request: LDRedeemOrderRequest, ldCache: LDCache): LDOrderPlacementResult =
    // A placement that claims spends the vault, a singleton every claim and flush also spends
    mutating("this redeem order") {
      withDex { (ctx, nodeApi) =>
        val fees = orderFees(request.executorFee, request.maxMinerFee)
        val liquidity = pool(ctx, nodeApi, ldCache)
        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        val vault = vaultState(ctx, vaultBox)
        ldCache.observeVault(vault, ctx.getHeight)
        val prov = LDBoxes.provisionById(ctx, nodeApi, request.provisionBoxId)
        if (!liquidity.simRedeem(prov.shares).withinMinSupply)
          throw LithosUnprocessable(
            s"closing provision ${prov.boxId} would take supply under ${LDHelpers.MIN_SUPPLY}, which the pool refuses")

        val claim = vault.canClaim(prov.entryX, prov.entryY)
        val (owedX, owedY) = vault.owed(prov.shares, prov.entryX, prov.entryY)
        // Placing without the claim would forfeit these fees, so a vault that cannot pay them refuses the order
        if (claim && (vault.payableX < owedX || vault.payableY < owedY))
          throw LithosUnprocessable(
            s"the vault can pay ${vault.payableX} nanoERG and ${vault.payableY} tokens of the $owedX and $owedY " +
              s"provision ${prov.boxId} is owed, which this order's placement would claim")
        val provisions: LithosDexExecution.Provisions = nft => if (nft == prov.ownerNFT.toString) Some(prov) else None
        placed(ctx, liquidity, provisions, LDOrderTransactions.redeem(
          ctx, wallet, liquidity, prov, if (claim) Some(vaultBox) else None, fees.executorFee, fees.maxMinerFee))
      }
    }

  /** @inheritdoc */
  override def cancelOrder(boxId: String, ldCache: LDCache): LDOrderCancelResult = withDex { (ctx, nodeApi) =>
    val snapshot = mempoolSnapshot()
    val box = nodeApi.boxesWithPoolByIds(Seq(boxId)) match {
      case Success(found) => found.find(_.boxId == boxId)
      case Failure(ex) => throw LithosUnavailable(s"could not read order $boxId from the node: ${ex.getMessage}")
    }
    val order = box.flatMap(LithosDexOrder.parse)
      .filter(o => wallet.signableTrees.contains(o.terms.redeemerTree))
      .getOrElse(throw LithosNotFound(s"no outstanding order owned by this wallet has id $boxId"))
    BatchingMempool.spenders(snapshot).get(boxId).flatMap(_.headOption).foreach { tx =>
      throw LithosStateChanged(
        s"order $boxId is already being filled or cancelled by unconfirmed transaction ${tx.id}: re-read the order list")
    }

    val built = fund(LDOrderTransactions.cancel(ctx, wallet, order.box.toInputUTXO(ctx)))
    val submission = send(ctx, built)
    LDOrderCancelResult(
      outcome = submission.outcome,
      txId = submission.txId,
      returnedNanoErgs = LDAmounts(built.value.returnedValue),
      returnedTokens = built.value.returnedTokens.map(t => LDTokenAmount(t.id.toString, LDAmounts(t.amount))),
      networkFee = LDAmounts(LithosDexTransactions.TX_FEE))
  }

  /** Fees a request names, or the configured defaults, refused unless the cap sits under the fee. */
  private case class OrderFees(executorFee: Long, maxMinerFee: Long)

  private def orderFees(executorFee: Option[String], maxMinerFee: Option[String]): OrderFees = {
    val fee = LDAmounts.parseLong("executorFee", executorFee).getOrElse(orderDefaults.executorFeeNanoErg)
    val cap = LDAmounts.parseLong("maxMinerFee", maxMinerFee).getOrElse(orderDefaults.maxMinerFeeNanoErg)
    if (cap < 0) throw LithosBadRequest(s"'maxMinerFee' cannot be negative, got $cap")
    // A cap at or above the fee lets a broadcast fill spend all of it, so no executor would take the order
    if (cap >= fee) throw LithosBadRequest(
      s"'maxMinerFee' ($cap) must be below 'executorFee' ($fee); omitted fields come from lithosdex.orders")
    OrderFees(fee, cap)
  }

  /** A request whose amounts overflow a 64-bit total is refused rather than failing as a defect. */
  private def exact[A](build: => A): A =
    try build catch {
      case _: ArithmeticException =>
        throw LithosBadRequest("the amounts in this request overflow a 64-bit nanoERG total")
    }

  private def redeemOrderQuote(liquidity: LDLiquidityPool, vault: LDFeeValue, prov: Provision,
                               fees: OrderFees): LDRedeemOrderQuote = {
    val q = liquidity.simRedeem(prov.shares)
    // Claimed by the placement only when the vault accepts a claim; otherwise nothing settles and every
    // earned fee is lost to the fill
    val (claimableX, claimableY) =
      if (vault.canClaim(prov.entryX, prov.entryY)) vault.owed(prov.shares, prov.entryX, prov.entryY) else (0L, 0L)
    val (accruedX, accruedY) = liquidity.feesAccrued(prov.shares, prov.entryX, prov.entryY)
    LDRedeemOrderQuote(
      shares = LDAmounts(q.shares),
      amountX = LDAmounts(q.amountX),
      amountY = LDAmounts(q.amountY),
      provisionValue = LDAmounts(prov.value),
      receivedX = LDAmounts(BigInt(q.amountX) + prov.value + LDOrderTransactions.RETURNED - fees.executorFee),
      claimableX = LDAmounts(claimableX),
      claimableY = LDAmounts(claimableY),
      unflushedX = LDAmounts(math.max(0L, accruedX - claimableX)),
      unflushedY = LDAmounts(math.max(0L, accruedY - claimableY)),
      withinMinSupply = q.withinMinSupply,
      executorFee = LDAmounts(fees.executorFee),
      maxMinerFee = LDAmounts(fees.maxMinerFee),
      totalErgRequired = LDAmounts(LDOrderTransactions.RETURNED),
      networkFee = LDAmounts(LithosDexTransactions.TX_FEE))
  }

  /**
   * Signs a placement, refusing before the send an order box that does not read back as this wallet's
   * order on this pool: no executor would ever find it, and its funds would wait for a cancel.
   */
  private def placed(ctx: BlockchainContext, liquidity: LDLiquidityPool, provisions: LithosDexExecution.Provisions,
                     plan: transactions.batching.lithosdex.DexPlan[LDOrderTx]): LDOrderPlacementResult = {
    var parsed = Option.empty[LithosDexOrder]
    val built = fund(plan, (tx: LDOrderTx) => {
      parsed = LithosDexOrder.parse(transactions.engine.wallet.WalletInventory.nodeBox(tx.order))
        .filter(o => o.poolNft == liquidity.poolNFT.toString && wallet.signableTrees.contains(o.terms.redeemerTree))
      // A defect in this client rather than in the request, so it must not surface as a 400
      if (parsed.isEmpty)
        throw new IllegalStateException(s"the order box ${tx.order.id} does not read back as this wallet's order on this pool")
    })
    val submission = send(ctx, built)
    val owned = LDOrderBook.Owned(parsed.get, None, LDOrderBook.Status.Pending, None, submission.txId)
    val described = describeOrder(ctx, owned, liquidity, provisions)
    LDOrderPlacementResult(
      outcome = submission.outcome,
      txId = submission.txId,
      order = built.value.provisionSuccessor.fold(described)(id => described.copy(provisionBoxId = Some(id))),
      claimedX = parsed.collect { case _: LithosDexOrder.Redeem => LDAmounts(built.value.claimedX) },
      claimedY = parsed.collect { case _: LithosDexOrder.Redeem => LDAmounts(built.value.claimedY) })
  }

  private def describeOrder(ctx: BlockchainContext, owned: LDOrderBook.Owned, liquidity: LDLiquidityPool,
                            provisions: LithosDexExecution.Provisions): LDOrder = {
    val order = owned.order
    val base = LDOrder(
      boxId = order.boxId,
      `type` = "",
      status = owned.status.name,
      placementTxId = owned.placementTxId,
      placedHeight = owned.placedHeight,
      spendingTxId = owned.spendingTxId,
      owner = Try(Address.fromErgoTree(ErgoTree.fromHex(order.terms.redeemerTree), ctx.getNetworkType).toString)
        .getOrElse(order.terms.redeemerTree),
      value = LDAmounts(order.box.value),
      executorFee = LDAmounts(order.terms.executorFee),
      maxMinerFee = LDAmounts(order.terms.maxMinerFee),
      fillableNow = owned.status match {
        // The pool is read at its mempool tip, which is what a filling order's own fill leaves, so pricing it
        // there would call it unfillable. The node accepted that fill, so the order met its terms.
        case LDOrderBook.Status.Filling => true
        case LDOrderBook.Status.Cancelling => false
        case _ => LithosDexExecution.price(order, liquidity, 0L, provisions, fundsItsOwnBox = false).nonEmpty
      })
    order match {
      case sell: LithosDexOrder.SwapSell =>
        base.copy(`type` = "SWAP", ergIn = Some(true), amountIn = Some(LDAmounts(sell.baseAmount)),
          minOutput = Some(LDAmounts(sell.minQuote)))
      case buy: LithosDexOrder.SwapBuy =>
        base.copy(`type` = "SWAP", ergIn = Some(false), amountIn = Some(LDAmounts(buy.amount)),
          minOutput = Some(LDAmounts(buy.minQuote)))
      case deposit: LithosDexOrder.Deposit =>
        base.copy(`type` = "DEPOSIT", amountX = Some(LDAmounts(deposit.depositX)),
          amountY = Some(LDAmounts(deposit.amount)), minShares = Some(LDAmounts(deposit.minShares)))
      case redeem: LithosDexOrder.Redeem =>
        base.copy(`type` = "REDEEM", ownerNFT = Some(redeem.ownerNft),
          provisionBoxId = provisions(redeem.ownerNft).map(_.boxId))
    }
  }

  /** The current provision each redeem order among `orders` closes, found by its ownership NFT. */
  private def redeemProvisions(ctx: BlockchainContext, nodeApi: NodeApi,
                               orders: Seq[LithosDexOrder]): LithosDexExecution.Provisions = {
    val nfts = orders.collect { case redeem: LithosDexOrder.Redeem => redeem.ownerNft }.toSet
    if (nfts.isEmpty) LithosDexExecution.NoProvisions
    else {
      val (found, _) = LDBoxes.provisionsOwnedBy(ctx, nodeApi, nfts, MempoolOptions.WithMempool)
      found.map(p => p.ownerNFT.toString -> p).toMap.get
    }
  }

  /**
   * A complete, fresh mempool observation from the engine. Order statuses are read from it, so a stale or
   * partial one would report a filling order as open.
   */
  protected def mempoolSnapshot(): CompleteMempool.Snapshot = {
    val wait = transactions.batching.Batcher.ObservationTimeout
    val observed = Try(Await.result((walletSelector.walletRef ? CompleteMempool.Refresh)(Timeout(wait))
      .mapTo[CompleteMempool.Observation], wait)).getOrElse(throw LithosUnavailable(
      "no mempool observation arrived from the transaction engine"))
    if (!observed.fresh) throw LithosUnavailable(
      s"no fresh mempool observation: ${observed.failure.getOrElse("the last one is too old")}")
    observed.snapshot.get
  }

  // Wallet balances moved to `WalletApiImpl`. LithosDex grew its own read for the swap card and the
  // collateral market grew a second one, and the two disagreed whenever a join landed between them.
  // One definition now, in the surface that is only about the wallet.

  // ══════════════════════════════════════════════════════════════════════════
  //  PLUMBING
  // ══════════════════════════════════════════════════════════════════════════

  private def withDex[A](f: (BlockchainContext, NodeApi) => A): A = {
    val nodeApi = executionNode
    nodeContext.getClient.execute(ctx => f(ctx, nodeApi))
  }

  /**
   * One mutation at a time, for the whole of LithosDex.
   *
   * Every mutating endpoint spends a singleton — the pool, the vault, or both — so two local requests
   * running together read the same box and build two transactions against it. One lands and the other
   * is rejected by the node for double spending, after it has already reserved wallet inputs and
   * signed. The `expected...BoxId` fields cannot close this on their own: both requests see the same
   * id, because the loser reads its box before the winner has sent anything.
   *
   * Serialised rather than retried. This is one miner's local API answering their own site, so the
   * throughput given up is a request or two per block, and a queued request re-reads the boxes when
   * it gets the lock rather than executing against what it saw before waiting.
   *
   * A caller that cannot get in within the wait is told the state changed, not made to wait longer:
   * whatever it was quoted against is by then a box another mutation has spent.
   */
  private def mutating[A](what: String)(f: => A): A = {
    if (!DexAPIExecution.dexMutationLock.tryLock(mutationWaitMs, TimeUnit.MILLISECONDS)) {
      // Which status this is depends on WHY the lock could not be taken, and the two want opposite
      // things from the caller. A mutation that is merely ahead in the queue finishes in well under
      // a second, so a 409 telling them to re-quote and resend is right. One that has been holding
      // for longer than a node round trip should take is stuck on the node — and a 409 there invites
      // an immediate retry, so a queue of callers hammers a node that is already not answering.
      val heldFor = DexAPIExecution.heldForMillis()
      if (heldFor > DexAPIExecution.StuckHolderMs)
        throw LithosUnavailable(
          s"a LithosDex mutation has held the lock for ${heldFor}ms, which is longer than a node " +
            s"round trip should take, so $what was not attempted: the node may not be answering")
      throw LithosStateChanged(
        s"another LithosDex mutation is still running, so $what was not attempted: re-read the pool " +
          "and vault and quote again")
    }
    DexAPIExecution.markAcquired()
    try f finally {
      DexAPIExecution.markReleased()
      DexAPIExecution.dexMutationLock.unlock()
    }
  }

  /** Refuse a request quoted against a box that has since been spent, and say what replaced it. */
  private def expectPool(expected: Option[String], box: InputUTXO): Unit =
    expected.filter(_ != box.id.toString).foreach { stale =>
      throw LithosStateChanged(
        s"the pool has moved: quoted against $stale, now ${box.id}", poolBoxId = Some(box.id.toString))
    }

  private def expectVault(expected: Option[String], box: InputUTXO): Unit =
    expected.filter(_ != box.id.toString).foreach { stale =>
      throw LithosStateChanged(
        s"the vault has moved: quoted against $stale, now ${box.id}", vaultBoxId = Some(box.id.toString))
    }

  private def expectProvision(expected: Option[String], prov: Provision): Unit =
    expected.filter(_ != prov.boxId).foreach { stale =>
      throw LithosStateChanged(
        s"provision $stale has been spent; its successor is ${prov.boxId}",
        provisionBoxId = Some(prov.boxId))
    }

  /**
   * An execute-side bound the current pool cannot satisfy.
   *
   * 422 rather than 409: the boxes may not have moved at all. A bound can fail because the caller
   * quoted against a different pool, or simply because they asked for a price the pool never had.
   */
  private def atMost(field: String, bound: Option[Long], actual: Long, what: String): Unit =
    bound.filter(actual > _).foreach { limit =>
      throw LithosUnprocessable(s"$what is $actual, above the '$field' ceiling of $limit")
    }

  private def atLeast(field: String, bound: Option[Long], actual: Long, what: String): Unit =
    bound.filter(actual < _).foreach { limit =>
      throw LithosUnprocessable(s"$what is $actual, below the '$field' floor of $limit")
    }

  private def nonNegative(field: String, value: Long): Long =
    if (value >= 0) value else throw LithosBadRequest(s"'$field' cannot be negative, got $value")

  private def wallet: NodeWallet = nodeContext.getNodeWallet

  /** The live pool, cached on the way past so a later reader has a last-known state. */
  private def pool(ctx: BlockchainContext, nodeApi: NodeApi, ldCache: LDCache): LDLiquidityPool = {
    val state = poolState(ctx, LDBoxes.poolBox(ctx, nodeApi))
    ldCache.setPool(state)
    state.pool
  }

  private def poolState(ctx: BlockchainContext, box: InputUTXO): LDLiquidityState =
    LDLiquidityState.fromBox(box, box.input.getCreationHeight, ctx.getHeight)

  private def vaultState(ctx: BlockchainContext, box: InputUTXO): LDFeeValue =
    LDFeeValue.fromBox(box, box.input.getCreationHeight, ctx.getHeight)

  private def tokenInfo(nodeApi: NodeApi, tokenId: ErgoId): Option[node.model.TokenInfo] =
    Try(nodeApi.tokenById(tokenId.toString).getOrElse(None)).getOrElse(None)

  /**
   * Broadcast a signed DEX transaction and expose only its real, accepted signable outputs to the
   * shared wallet. A thrown send is ambiguous (the node may have accepted before the connection
   * failed), so its inputs deliberately remain reserved until a complete mempool-aware refresh
   * resolves whether the node kept the transaction.
   */
  private case class Submission(txId: String, outcome: String)

  def refreshProvision(boxId: String): DexIntent.Refreshed = withDex { (ctx, nodeApi) =>
    val provision = LDBoxes.provisionById(ctx, nodeApi, boxId)
    val built = fund(LithosDexTransactions.refresh(ctx, wallet, provision))
    val submission = send(ctx, built)
    DexIntent.Refreshed(built.value.boxId, submission.txId, submission.outcome)
  }
  /** @param reservation the wallet inputs funding the transaction, or None when it spends none */
  private case class Funded[A <: LDFundedTx](value: A, reservation: Option[transactions.engine.wallet.FundingAllocation])

  /**
   * Reserves what `plan` needs, builds, signs and runs `verify` on the result, releasing the reservation
   * if any step throws. A plan asking for nothing reserves nothing.
   */
  private def fund[A <: LDFundedTx](plan: transactions.batching.lithosdex.DexPlan[A],
                                    verify: A => Unit = (_: A) => ()): Funded[A] = {
    require(alive(), "engine attempt was superseded")
    val reservation =
      if (plan.value == 0L && plan.tokens.isEmpty) None else Some(walletSelector.reserve(plan.value, plan.tokens))
    try {
      val unsigned = plan.build(reservation.map(_.inputs).getOrElse(Seq.empty))
      work.lithos.mutations.Eip27Adjustment.validate(unsigned.tx, nodeContext.getNetwork)
      require(alive(), "engine attempt was superseded before signing")
      val signed = wallet.sign(unsigned.tx)
      val described = unsigned.describe(signed)
      verify(described)
      Funded(described, reservation)
    } catch {
      case NonFatal(ex) => reservation.foreach(_.release()); throw ex
    }
  }

  private def send(ctx: BlockchainContext, built: Funded[_ <: LDFundedTx]): Submission = {
    val result = new EngineBroadcast(walletSelector.walletRef, executionNode)(walletSelector.executionContext)
      .send(built.value.tx, built.reservation.toSeq, "dex:" + built.value.tx.getId, alive)
    Submission(result.txId, result.outcome)
  }
  private def positive(field: String, value: Long): Long =
    if (value > 0) value else throw LithosBadRequest(s"'$field' must be positive, got $value")

  /**
   * How long a mutation waits for the lock. Long enough for one node round trip and a signature —
   * an ordinary mutation is well under a second — and short enough that a caller learns its quote is
   * stale rather than sitting on an HTTP connection.
   *
   * Overridable so a test can assert the refusal without waiting fifteen seconds for it.
   */
  protected def mutationWaitMs: Long = 15000L
}

object DexAPIExecution {

  /**
   * Process-wide, not per-instance: it guards chain singletons, so it has to hold across however many
   * copies of this class Guice builds.
   */
  private val dexMutationLock = new ReentrantLock()

  /**
   * When the current holder took the lock, or 0 when nobody holds it.
   *
   * Written only by the thread inside the lock and read by threads that failed to take it, so it has
   * to be atomic — but it is advisory either way: a reader that sees a stale 0 gets the 409, which is
   * the safe direction. Its only job is to tell "queued behind a fast mutation" from "queued behind
   * one wedged on the node".
   */
  private val holderSince = new AtomicLong(0L)

  /**
   * Past this, the holder is assumed stuck rather than busy. An ordinary mutation is one index read,
   * a signature and one broadcast; the node's own read timeout is 30 seconds, so anything past ten
   * is waiting on something that is not going to answer quickly.
   */
  private final val StuckHolderMs: Long = 10000L

  private def markAcquired(): Unit = holderSince.set(System.currentTimeMillis())

  private def markReleased(): Unit = holderSince.set(0L)

  private def heldForMillis(): Long = holderSince.get() match {
    case 0L => 0L
    case since => System.currentTimeMillis() - since
  }
}
