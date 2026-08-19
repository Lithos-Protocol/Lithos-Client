package api

import api.LDErrors.{LDBadRequest, LDStateChanged, LDUnavailable, LDUnprocessable}
import api.models._
import cache.LDCache
import lithosdex.states.{LDFeeValue, LDLiquidityState}
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import configs.NodeContext
import transactions.wallet.WalletSelector
import transactions.dex.LDBoxes.Provision
import transactions.dex.{LDBoxes, LDFundedTx, LithosDexTransactions}
import work.lithos.mutations.InputUTXO

import javax.inject.Inject

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

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
class LithosDexApiImpl @Inject()(nodeContext: NodeContext) extends LithosDexApi {

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

    val p = state.pool
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
      canFlush = p.canFlush,
      canDeposit = p.canDeposit,
      syncHeight = state.syncHeight)
  }

  /** @inheritdoc */
  override def getVault(ldCache: LDCache): LDVaultInfo = withDex { (ctx, nodeApi) =>
    val v = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(v, ctx.getHeight)

    LDVaultInfo(
      vaultNFT = v.vaultNFT.toString,
      utxoId = v.utxoId,
      accX = LDAmounts(v.accX),
      accY = LDAmounts(v.accY),
      balanceX = LDAmounts(v.balanceX),
      balanceY = LDAmounts(v.balanceY),
      payableX = LDAmounts(v.payableX),
      payableY = LDAmounts(v.payableY),
      tokenY = v.tokenY.map(_.toString),
      vaultMin = LDAmounts(LDHelpers.VAULT_MIN),
      lastFlushHeight = ldCache.getLastFlushHeight,
      syncHeight = v.syncHeight)
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
      val p = pool(ctx, nodeApi, ldCache)

      // An output must stay strictly under the reserve it is drawn from — the curve only approaches it.
      // That is a property of the pool rather than of the request, which is why it is a 422.
      p.simSwapForOutput(amountOut, request.ergIn).map(LDSwapQuote(_)).getOrElse(
        throw LDUnprocessable(
          s"the pool cannot produce $amountOut: it holds only " +
            s"${if (request.ergIn) p.reservesY else p.reservesX} on that side, and an output has to stay " +
            "strictly under the reserve it is drawn from"))
    }

  /** @inheritdoc */
  override def swap(request: LDSwapExecuteRequest, ldCache: LDCache, walletSelector: WalletSelector): LDSwapResult =
    mutating("this swap") {
      withDex { (ctx, nodeApi) =>
        val amountIn = positive("amountIn", LDAmounts.parseLong("amountIn", request.amountIn))
        // A negative floor is no floor: `amountOut >= minOutput` is then true at any price the pool
        // happens to offer, which is exactly what minOutput exists to prevent.
        val minOutput = nonNegative("minOutput", LDAmounts.parseLong("minOutput", request.minOutput))

        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        ldCache.setPool(poolState(ctx, poolBox))

        val built = LithosDexTransactions.swap(
          ctx, wallet, walletSelector, poolBox, amountIn, request.ergIn, minOutput)
        LDSwapResult(LDAmounts(built.quote.amountOut), send(ctx, built))
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  DEPOSIT
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkDeposit(request: LDDepositRequest, ldCache: LDCache): LDDepositQuote =
    withDex { (ctx, nodeApi) =>
      val p = pool(ctx, nodeApi, ldCache)
      val shares = LDAmounts.parseLong("shares", request.shares)
      val availableX = LDAmounts.parseLong("availableX", request.availableX)
      val availableY = LDAmounts.parseLong("availableY", request.availableY)

      val quote = shares match {
        case Some(s) => p.simDepositForShares(positive("shares", s))
        case None =>
          // Both sides or neither: a deposit is balanced by construction, so one side alone says
          // nothing about how many shares the funds can buy.
          (availableX, availableY) match {
            case (Some(x), Some(y)) => p.simDeposit(positive("availableX", x), positive("availableY", y))
            case _ => throw LDBadRequest(
              "supply either 'shares', or both 'availableX' and 'availableY'")
          }
      }
      LDDepositQuote(quote)
    }

  /** @inheritdoc */
  override def deposit(request: LDDepositExecuteRequest, ldCache: LDCache, walletSelector: WalletSelector): LDDepositResult =
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

        val built = LithosDexTransactions.deposit(ctx, wallet, walletSelector, poolBox, shares)
        LDDepositResult(
          provisionBoxId = built.provisionBoxId,
          ownerNFT = built.ownerNFT.toString,
          shares = LDAmounts(built.quote.shares),
          amountX = LDAmounts(built.quote.amountX),
          amountY = LDAmounts(built.quote.amountY),
          txId = send(ctx, built))
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  REDEEM
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkRedeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemQuote =
    withDex { (ctx, nodeApi) =>
      val p = pool(ctx, nodeApi, ldCache)
      val v = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
      val prov = LDBoxes.provisionById(ctx, nodeApi, request.provisionBoxId)

      val q = p.simRedeem(prov.shares)
      // Settled but unclaimed fees are forfeited when the box is spent. The contract permits that on
      // purpose, so it cannot be refused here — but a client that does not show it will lose someone
      // their money silently.
      val (owedX, owedY) = v.owed(prov.shares, prov.entryX, prov.entryY)
      val (unclaimedX, unclaimedY) = (math.max(0L, owedX), math.max(0L, owedY))

      LDRedeemQuote(
        shares = LDAmounts(q.shares),
        amountX = LDAmounts(q.amountX),
        amountY = LDAmounts(q.amountY),
        withinMinSupply = q.withinMinSupply,
        unclaimedX = LDAmounts(unclaimedX),
        unclaimedY = LDAmounts(unclaimedY))
    }

  /** @inheritdoc */
  override def redeem(request: LDRedeemRequest, ldCache: LDCache, walletSelector: WalletSelector): LDRedeemResult =
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
        val v = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
        val (unclaimedX, unclaimedY) = v.owed(prov.shares, prov.entryX, prov.entryY)
        if ((unclaimedX > 0 || unclaimedY > 0) && !request.acknowledgeUnclaimedFees.contains(true))
          throw LDBadRequest(
            s"provision ${prov.boxId} has $unclaimedX nanoERG and $unclaimedY token(s) of settled " +
              "fees that closing it would forfeit to nobody. Claim first, or resend with " +
              "'acknowledgeUnclaimedFees': true.")

        val built = LithosDexTransactions.redeem(
          ctx, wallet, walletSelector, poolBox, prov)
        LDRedeemResult(
          shares = LDAmounts(built.quote.shares),
          amountX = LDAmounts(built.quote.amountX),
          amountY = LDAmounts(built.quote.amountY),
          txId = send(ctx, built))
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  PROVISIONS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def listProvisions(ldCache: LDCache): LDProvisionList = withDex { (ctx, nodeApi) =>
    val p = pool(ctx, nodeApi, ldCache)
    val v = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(v, ctx.getHeight)

    LDProvisionList(LDBoxes.ownedProvisions(ctx, nodeApi).map(describe(p, v, _)))
  }

  /**
   * Both fee figures, and their difference.
   *
   * `accrued` is what the pool says the provision has earned, unflushed fees included. `claimable` is
   * what the vault can actually pay, which is smaller by whatever has not been flushed. Returning one
   * without the other misleads — the gap is precisely what a flush would release.
   */
  private def describe(p: LDLiquidityPool, v: LDFeeValue, prov: Provision): LDProvision = {
    val (accruedX, accruedY) = p.feesAccrued(prov.shares, prov.entryX, prov.entryY)
    val (owedX, owedY) = v.owed(prov.shares, prov.entryX, prov.entryY)

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
      shareOfSupply = p.shareOfSupply(prov.shares),
      accruedX = LDAmounts(accruedX),
      accruedY = LDAmounts(accruedY),
      claimableX = LDAmounts(claimableX),
      claimableY = LDAmounts(claimableY),
      unflushedX = LDAmounts(math.max(0L, accruedX - claimableX)),
      unflushedY = LDAmounts(math.max(0L, accruedY - claimableY)),
      canClaim = v.canClaim(prov.entryX, prov.entryY),
      awaitingFlush = awaitingFlush)
  }

  /** @inheritdoc */
  override def claimProvision(boxId: String, request: LDClaimRequest, ldCache: LDCache,
                              walletSelector: WalletSelector): LDClaimResult =
    mutating("this claim") {
      withDex { (ctx, nodeApi) =>
        val minX = LDAmounts.parseLong("minAmountX", request.minAmountX)
        val minY = LDAmounts.parseLong("minAmountY", request.minAmountY)

        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        expectVault(request.expectedVaultBoxId, vaultBox)
        val v = vaultState(ctx, vaultBox)
        ldCache.observeVault(v, ctx.getHeight)

        val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
        expectProvision(request.expectedProvisionBoxId, prov)
        if (!v.canClaim(prov.entryX, prov.entryY))
          throw LDBadRequest(
            s"provision $boxId has nothing to settle — its entry is level with the vault's accumulator. " +
              "The pool has not been flushed since it last claimed; flush first, or wait for one.")

        val (owedX, owedY) = v.owed(prov.shares, prov.entryX, prov.entryY)
        atLeast("minAmountX", minX, owedX, "the ERG this claim settles")
        atLeast("minAmountY", minY, owedY, "the token amount this claim settles")

        val built = LithosDexTransactions.claim(
          ctx, wallet, walletSelector, vaultBox, Seq(prov))

        // OUTPUTS(1) is this provision's successor: the vault sits at input 0 and each provision at
        // 1..count, each finding its own successor at the matching output index.
        val successor = built.tx.getOutputsToSpend.get(1).getId.toString
        LDClaimResult(LDAmounts(built.claimedX), LDAmounts(built.claimedY), successor,
          send(ctx, built))
      }
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  RESIZE
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkResize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeQuote =
    withDex { (ctx, nodeApi) =>
      val p = pool(ctx, nodeApi, ldCache)
      val prov = LDBoxes.provisionById(ctx, nodeApi, boxId)
      LDResizeQuote(p.simResize(prov.shares, newShares(request, prov), prov.entryX, prov.entryY))
    }

  /** @inheritdoc */
  override def resize(boxId: String, request: LDResizeRequest, ldCache: LDCache,
                      walletSelector: WalletSelector): LDResizeResult =
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

        val built = LithosDexTransactions.resize(
          ctx, wallet, walletSelector, poolBox, vaultBox, prov, next)

        LDResizeResult(
          boxId = built.boxId,
          newShares = LDAmounts(built.quote.newShares),
          amountX = LDAmounts(built.quote.amountX),
          amountY = LDAmounts(built.quote.amountY),
          txId = send(ctx, built))
      }
    }

  /** A resize to zero is a redemption, and a resize to the current size moves nothing: both refused. */
  private def newShares(request: LDResizeRequest, prov: Provision): Long = {
    val next = LDAmounts.parseLong("newShares", request.newShares)
    if (next <= 0) throw LDBadRequest(
      "'newShares' must be positive — a resize to zero is a redemption, use /dex/redeem")
    if (next == prov.shares) throw LDBadRequest(
      s"provision ${prov.boxId} is already ${prov.shares} shares — a resize must change the size")
    next
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FLUSH
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkFlush(ldCache: LDCache): LDFlushCheck = withDex { (ctx, nodeApi) =>
    val p = pool(ctx, nodeApi, ldCache)
    val v = vaultState(ctx, LDBoxes.vaultBox(ctx, nodeApi))
    ldCache.observeVault(v, ctx.getHeight)

    LDFlushCheck(
      pendingX = LDAmounts(p.pendingX),
      pendingY = LDAmounts(p.pendingY),
      canFlush = p.canFlush,
      poolAccX = LDAmounts(p.accX),
      poolAccY = LDAmounts(p.accY),
      vaultAccX = LDAmounts(v.accX),
      vaultAccY = LDAmounts(v.accY),
      vaultBalanceAfterX = LDAmounts(v.balanceX + p.pendingX),
      vaultBalanceAfterY = LDAmounts(v.balanceY + p.pendingY),
      lastFlushHeight = ldCache.getLastFlushHeight,
      currentHeight = ctx.getHeight)
  }

  /** @inheritdoc */
  override def flush(request: LDFlushRequest, ldCache: LDCache, walletSelector: WalletSelector): LDFlushResult =
    mutating("this flush") {
      withDex { (ctx, nodeApi) =>
        val poolBox = LDBoxes.poolBox(ctx, nodeApi)
        expectPool(request.expectedPoolBoxId, poolBox)
        val state = poolState(ctx, poolBox)
        ldCache.setPool(state)

        if (!state.pool.canFlush)
          throw LDBadRequest(
            "nothing is pending — the pool refuses a flush that moves nothing. Fees accrue on swaps, so " +
              "there is nothing to move until one happens.")

        val vaultBox = LDBoxes.vaultBox(ctx, nodeApi)
        expectVault(request.expectedVaultBoxId, vaultBox)

        val built = LithosDexTransactions.flush(ctx, wallet, walletSelector, poolBox, vaultBox)
        LDFlushResult(LDAmounts(built.flushedX), LDAmounts(built.flushedY),
          send(ctx, built))
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
    if (snapshots.isEmpty) throw LDBadRequest(
      "no pool history is indexed — the node needs extraIndex on for this endpoint")

    val lo = from.getOrElse(snapshots.head.height)
    val hi = to.getOrElse(snapshots.last.height)
    if (hi < lo) throw LDBadRequest(s"'to' ($hi) is below 'from' ($lo)")

    val size = bucket.getOrElse(DefaultBucket)
    if (size <= 0) throw LDBadRequest(s"'bucket' must be positive, got $size")
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
  //  WALLET
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def getWalletBalance: WalletBalance = {
    val nodeApi = nodeContext.getNodeApi
    val balances = nodeApi.walletBalances().getOrElse(
      throw new RuntimeException("could not read wallet balances — is the node wallet unlocked?"))

    // One lookup for every token at once. Names and decimals are cosmetic, so a node without the index
    // returns the balances anyway rather than failing the request.
    val info = Try(nodeApi.tokensByIds(balances.assets.map(_.tokenId)).getOrElse(Seq.empty))
      .getOrElse(Seq.empty)
      .map(t => t.id -> t).toMap

    WalletBalance(
      nanoErgs = LDAmounts(balances.balance),
      tokens = balances.assets.map { a =>
        WalletToken(
          tokenId = a.tokenId,
          name = info.get(a.tokenId).map(_.name),
          decimals = info.get(a.tokenId).map(_.decimals),
          amount = LDAmounts(a.amount))
      })
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PLUMBING
  // ══════════════════════════════════════════════════════════════════════════

  private def withDex[A](f: (BlockchainContext, NodeApi) => A): A = {
    val nodeApi = nodeContext.getNodeApi
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
    if (!LithosDexApiImpl.dexMutationLock.tryLock(mutationWaitMs, TimeUnit.MILLISECONDS)) {
      // Which status this is depends on WHY the lock could not be taken, and the two want opposite
      // things from the caller. A mutation that is merely ahead in the queue finishes in well under
      // a second, so a 409 telling them to re-quote and resend is right. One that has been holding
      // for longer than a node round trip should take is stuck on the node — and a 409 there invites
      // an immediate retry, so a queue of callers hammers a node that is already not answering.
      val heldFor = LithosDexApiImpl.heldForMillis()
      if (heldFor > LithosDexApiImpl.StuckHolderMs)
        throw LDUnavailable(
          s"a LithosDex mutation has held the lock for ${heldFor}ms, which is longer than a node " +
            s"round trip should take, so $what was not attempted — the node may not be answering")
      throw LDStateChanged(
        s"another LithosDex mutation is still running, so $what was not attempted — re-read the pool " +
          "and vault and quote again")
    }
    LithosDexApiImpl.markAcquired()
    try f finally {
      LithosDexApiImpl.markReleased()
      LithosDexApiImpl.dexMutationLock.unlock()
    }
  }

  /** Refuse a request quoted against a box that has since been spent, and say what replaced it. */
  private def expectPool(expected: Option[String], box: InputUTXO): Unit =
    expected.filter(_ != box.id.toString).foreach { stale =>
      throw LDStateChanged(
        s"the pool has moved: quoted against $stale, now ${box.id}", poolBoxId = Some(box.id.toString))
    }

  private def expectVault(expected: Option[String], box: InputUTXO): Unit =
    expected.filter(_ != box.id.toString).foreach { stale =>
      throw LDStateChanged(
        s"the vault has moved: quoted against $stale, now ${box.id}", vaultBoxId = Some(box.id.toString))
    }

  private def expectProvision(expected: Option[String], prov: Provision): Unit =
    expected.filter(_ != prov.boxId).foreach { stale =>
      throw LDStateChanged(
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
      throw LDUnprocessable(s"$what is $actual, above the '$field' ceiling of $limit")
    }

  private def atLeast(field: String, bound: Option[Long], actual: Long, what: String): Unit =
    bound.filter(actual < _).foreach { limit =>
      throw LDUnprocessable(s"$what is $actual, below the '$field' floor of $limit")
    }

  private def nonNegative(field: String, value: Long): Long =
    if (value >= 0) value else throw LDBadRequest(s"'$field' cannot be negative, got $value")

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
  private def send(ctx: BlockchainContext, built: LDFundedTx): String = {
    val change =
      try built.tx.getOutputsToSpend.asScala.toSeq
        .map(InputUTXO(_))
        .filter(box => wallet.signableTrees.contains(box.contract.ergoTreeHex))
      catch {
        case NonFatal(ex) =>
          built.reservation.release()
          throw ex
      }

    built.reservation.beginSubmission()
    try {
      val txId = ctx.sendTransaction(built.tx).replace("\"", "")
      built.reservation.commit(change)
      txId
    } catch {
      case NonFatal(ex) =>
        built.reservation.uncertain()
        throw ex
    }
  }

  private def positive(field: String, value: Long): Long =
    if (value > 0) value else throw LDBadRequest(s"'$field' must be positive, got $value")

  /**
   * How long a mutation waits for the lock. Long enough for one node round trip and a signature —
   * an ordinary mutation is well under a second — and short enough that a caller learns its quote is
   * stale rather than sitting on an HTTP connection.
   *
   * Overridable so a test can assert the refusal without waiting fifteen seconds for it.
   */
  protected def mutationWaitMs: Long = 15000L
}

object LithosDexApiImpl {

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
