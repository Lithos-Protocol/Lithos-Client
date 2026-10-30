package transactions.engine.execution

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.{IndexedPool, LDPoolIndex}
import configs.{LithosDexOrdersConfig, NodeContext}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.states.{LDFeeValue, LDT2TFeeValue}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.ErgoId
import transactions.batching.lithosdex._
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation, WalletInventory}
import work.lithos.mutations.{Eip27Adjustment, Token}

import scala.util.Try
import scala.util.control.NonFatal

/**
 * Orders on any LithosDex pool, ERG/token or token/token, for the `/dex/pools/{poolNft}/orders` routes. The
 * pool comes from [[LDPoolIndex]], which says which deployment it is and whether its genesis is sound; its
 * box, vault and provisions are read live for every quote.
 *
 * A quote is given for any pool. A swap or deposit is refused on a pool whose genesis has problems, which can
 * be drained, and on an unverified pool needs the caller's acknowledgement. A redeem is open on every pool, so
 * liquidity can always leave. A redeem that claims spends the pool's vault, so it takes the one LithosDex
 * mutation lock every vault spend takes.
 */
class DexPoolExecution(nodeContext: NodeContext, walletSelector: EngineFunding, index: LDPoolIndex,
                       orderDefaults: LithosDexOrdersConfig = LithosDexOrdersConfig.Default,
                       alive: () => Boolean = () => true) {

  private def nodeApi: NodeApi = nodeContext.getNodeApi
  private def wallet: NodeWallet = nodeContext.getNodeWallet

  /** The same wait as the canonical pool's mutations. */
  protected def mutationWaitMs: Long = 15000L

  // ── swap ──────────────────────────────────────────────────────────────────

  def checkSwap(poolNft: String, request: LDPoolSwapRequest): LDPoolSwapQuote = withPool(poolNft) { (ctx, live) =>
    swapQuote(live, request.tokenIn, positive("amountIn", request.amountIn), orderFees(request.executorFee,
      request.maxMinerFee))
  }

  def placeSwap(poolNft: String, request: LDPoolSwapExecuteRequest): LDPoolOrderPlaced = withPool(poolNft) { (ctx, live) =>
    tradable(live.indexed, request.acknowledgeUnverified.contains(true))
    val amountIn = positive("amountIn", request.amountIn)
    // A zero floor fills at whatever price the pool offers when the order executes
    val minOutput = positive("minOutput", request.minOutput)
    val fees = orderFees(request.executorFee, request.maxMinerFee)
    val plan = live match {
      case n: LiveN2T => exact(LDOrderTransactions.swap(ctx, wallet, n.pool, amountIn, ergIn(n, request.tokenIn),
        minOutput, fees.executorFee, fees.maxMinerFee))
      case t: LiveT2T => LDOrderTransactions.t2tSwap(ctx, wallet, t.pool, sideOf(t, request.tokenIn), amountIn,
        minOutput, fees.executorFee, fees.maxMinerFee)
    }
    placed(ctx, live, "SWAP", plan)
  }

  private def swapQuote(live: Live, tokenIn: String, amountIn: Long, fees: Fees): LDPoolSwapQuote = live match {
    case n: LiveN2T =>
      val sellsErg = ergIn(n, tokenIn)
      val q = n.pool.simSwap(amountIn, sellsErg)
      LDPoolSwapQuote(n.indexed.nft, tokenIn, if (sellsErg) n.pool.tokenY.toString else "ERG", LDAmounts(amountIn),
        LDAmounts(q.amountOut), LDAmounts(if (sellsErg) q.amountOut else q.amountOut - fees.executorFee),
        q.isExecutable, LDAmounts(fees.executorFee), LDAmounts(fees.maxMinerFee),
        LDAmounts(exact(LDOrderTransactions.swapValue(amountIn, sellsErg, fees.executorFee))),
        if (sellsErg) Seq.empty else Seq(LDTokenAmount(n.pool.tokenY.toString, LDAmounts(amountIn))),
        LDAmounts(LDOrderTransactions.RETURNED), LDAmounts(LithosDexTransactions.TX_FEE))
    case t: LiveT2T =>
      val sold = sideOf(t, tokenIn)
      val xIn = sold == t.pool.tokenX
      val q = t.pool.simSwap(amountIn, xIn)
      LDPoolSwapQuote(t.indexed.nft, sold.toString, (if (xIn) t.pool.tokenY else t.pool.tokenX).toString,
        LDAmounts(amountIn), LDAmounts(q.amountOut), LDAmounts(q.amountOut), q.isExecutable,
        LDAmounts(fees.executorFee), LDAmounts(fees.maxMinerFee),
        LDAmounts(exact(LDOrderTransactions.t2tOrderValue(fees.executorFee))),
        Seq(LDTokenAmount(sold.toString, LDAmounts(amountIn))),
        LDAmounts(LDOrderTransactions.RETURNED), LDAmounts(LithosDexTransactions.TX_FEE))
  }

  // ── deposit ───────────────────────────────────────────────────────────────

  def checkDeposit(poolNft: String, request: LDPoolDepositRequest): LDPoolDepositQuote = withPool(poolNft) { (_, live) =>
    val amountX = positive("amountX", request.amountX)
    val amountY = positive("amountY", request.amountY)
    val fees = orderFees(request.executorFee, request.maxMinerFee)
    val (shares, takenX, takenY, excessX, excessY, supply) = live match {
      case n: LiveN2T =>
        val s = LithosDexExecution.depositSplit(n.pool, amountX, amountY)
        (s.shares, s.takenX, s.takenY, s.excessX, s.excessY, n.pool.supply)
      case t: LiveT2T =>
        val s = LDT2TExecution.depositSplit(t.pool, amountX, amountY)
        (s.shares, s.takenX, s.takenY, s.excessX, s.excessY, t.pool.supply)
    }
    val issued = shares.max(0)
    val (ergNeeded, tokens) = live match {
      case n: LiveN2T => (exact(LDOrderTransactions.depositValue(amountX, fees.executorFee)),
        Seq(LDTokenAmount(n.pool.tokenY.toString, LDAmounts(amountY))))
      case t: LiveT2T => (exact(LDOrderTransactions.t2tDepositValue(fees.executorFee)),
        Seq(LDTokenAmount(t.pool.tokenX.toString, LDAmounts(amountX)), LDTokenAmount(t.pool.tokenY.toString, LDAmounts(amountY))))
    }
    LDPoolDepositQuote(live.indexed.nft, LDAmounts(issued), LDAmounts(takenX), LDAmounts(takenY), LDAmounts(excessX),
      LDAmounts(excessY), if (issued <= 0) 0.0 else issued.toDouble / (BigInt(supply) + issued).toDouble,
      LDAmounts(LDHelpers.PROVISION_MIN), LDAmounts(fees.executorFee), LDAmounts(fees.maxMinerFee), LDAmounts(ergNeeded),
      tokens, LDAmounts(LDOrderTransactions.RETURNED), LDAmounts(LithosDexTransactions.TX_FEE))
  }

  def placeDeposit(poolNft: String, request: LDPoolDepositExecuteRequest): LDPoolOrderPlaced = withPool(poolNft) { (ctx, live) =>
    tradable(live.indexed, request.acknowledgeUnverified.contains(true))
    val amountX = positive("amountX", request.amountX)
    val amountY = positive("amountY", request.amountY)
    // The only protection a deposit order has against a pool moved earlier in the same block
    val minShares = positive("minShares", request.minShares)
    val fees = orderFees(request.executorFee, request.maxMinerFee)
    val plan = live match {
      case n: LiveN2T =>
        if (!n.pool.canDeposit) throw LithosUnprocessable("the pool has no provision tokens left, so no deposit can fill")
        exact(LDOrderTransactions.deposit(ctx, wallet, n.pool, amountX, amountY, minShares, fees.executorFee, fees.maxMinerFee))
      case t: LiveT2T =>
        if (!t.pool.canDeposit) throw LithosUnprocessable("the pool has no provision tokens left, so no deposit can fill")
        LDOrderTransactions.t2tDeposit(ctx, wallet, t.pool, amountX, amountY, minShares, fees.executorFee, fees.maxMinerFee)
    }
    placed(ctx, live, "DEPOSIT", plan)
  }

  // ── redeem ────────────────────────────────────────────────────────────────

  def checkRedeem(poolNft: String, request: LDPoolRedeemRequest): LDPoolRedeemQuote = withPool(poolNft) { (ctx, live) =>
    val fees = orderFees(request.executorFee, request.maxMinerFee)
    val prov = provision(ctx, live, request.provisionBoxId)
    val (amountX, amountY, within) = redeemShare(live, prov)
    val (claimX, claimY) = claimable(ctx, live, prov)
    val (ergBack, ergNeeded) = live match {
      // An ERG/token order pays its fee out of the reserves it releases
      case _: LiveN2T => (BigInt(prov.value) + LDOrderTransactions.RETURNED - fees.executorFee, LDOrderTransactions.RETURNED)
      case _: LiveT2T => (BigInt(prov.value) + LDOrderTransactions.RETURNED, LDOrderTransactions.t2tOrderValue(fees.executorFee))
    }
    LDPoolRedeemQuote(live.indexed.nft, prov.boxId, LDAmounts(prov.shares), LDAmounts(amountX), LDAmounts(amountY),
      LDAmounts(claimX), LDAmounts(claimY), LDAmounts(prov.value), LDAmounts(ergBack), within,
      LDAmounts(fees.executorFee), LDAmounts(fees.maxMinerFee), LDAmounts(ergNeeded),
      LDAmounts(LithosDexTransactions.TX_FEE))
  }

  /** Open on every pool, whatever its genesis: a provider must always be able to take their liquidity out. */
  def placeRedeem(poolNft: String, request: LDPoolRedeemRequest): LDPoolOrderPlaced =
    DexAPIExecution.mutating("this redeem order", mutationWaitMs) {
      withPool(poolNft) { (ctx, live) =>
        val fees = orderFees(request.executorFee, request.maxMinerFee)
        val prov = provision(ctx, live, request.provisionBoxId)
        val (_, _, within) = redeemShare(live, prov)
        if (!within) throw LithosUnprocessable(
          s"closing provision ${prov.boxId} would take supply under ${LDHelpers.MIN_SUPPLY}, which the pool refuses")
        val vaultBox = vault(ctx, live)
        val plan = live match {
          case n: LiveN2T =>
            val v = LDFeeValue.fromBox(vaultBox, 0, ctx.getHeight)
            val claim = v.canClaim(prov.entryX, prov.entryY)
            val (owedX, owedY) = v.owed(prov.shares, prov.entryX, prov.entryY)
            // Placing without the claim would forfeit these fees, so a vault that cannot pay them refuses the order
            if (claim && (v.payableX < owedX || v.payableY < owedY)) throw LithosUnprocessable(
              s"the vault can pay ${v.payableX} and ${v.payableY} of the $owedX and $owedY provision ${prov.boxId} is owed")
            LDOrderTransactions.redeem(ctx, wallet, n.contracts, n.pool, prov, if (claim) Some(vaultBox) else None,
              fees.executorFee, fees.maxMinerFee)
          case t: LiveT2T =>
            val v = LDT2TFeeValue.fromBox(vaultBox, 0, ctx.getHeight)
            val claim = v.canClaim(prov.entryX, prov.entryY)
            val (owedX, owedY) = v.owed(prov.shares, prov.entryX, prov.entryY)
            if (claim && (v.payableX < owedX || v.payableY < owedY)) throw LithosUnprocessable(
              s"the vault can pay ${v.payableX} and ${v.payableY} of the $owedX and $owedY provision ${prov.boxId} is owed")
            LDOrderTransactions.t2tRedeem(ctx, wallet, t.contracts, t.pool, prov, if (claim) Some(vaultBox) else None,
              fees.executorFee, fees.maxMinerFee)
        }
        placed(ctx, live, "REDEEM", plan)
      }
    }

  private def redeemShare(live: Live, prov: LDBoxes.Provision): (Long, Long, Boolean) = live match {
    case n: LiveN2T => val q = n.pool.simRedeem(prov.shares); (q.amountX, q.amountY, q.withinMinSupply)
    case t: LiveT2T => val q = t.pool.simRedeem(prov.shares); (q.amountX, q.amountY, q.withinMinSupply)
  }

  /** What the vault owes `prov` now, which a redeem placement claims; nothing when it accepts no claim. */
  private def claimable(ctx: BlockchainContext, live: Live, prov: LDBoxes.Provision): (Long, Long) = {
    val box = vault(ctx, live)
    live match {
      case _: LiveN2T =>
        val v = LDFeeValue.fromBox(box, 0, ctx.getHeight)
        if (v.canClaim(prov.entryX, prov.entryY)) v.owed(prov.shares, prov.entryX, prov.entryY) else (0L, 0L)
      case _: LiveT2T =>
        val v = LDT2TFeeValue.fromBox(box, 0, ctx.getHeight)
        if (v.canClaim(prov.entryX, prov.entryY)) v.owed(prov.shares, prov.entryX, prov.entryY) else (0L, 0L)
    }
  }

  // ── the pool ──────────────────────────────────────────────────────────────

  /** A pool the index lists, with its box read live. */
  private sealed trait Live { def indexed: IndexedPool }
  private final case class LiveN2T(indexed: IndexedPool, contracts: LDContracts, pool: LDLiquidityPool) extends Live
  private final case class LiveT2T(indexed: IndexedPool, contracts: LDT2TContracts, pool: LDT2TLiquidityPool) extends Live

  private def withPool[A](poolNft: String)(f: (BlockchainContext, Live) => A): A = {
    val snapshot = index.snapshot()
    val indexed = snapshot.pools.find(_.nft.equalsIgnoreCase(poolNft)).getOrElse {
      if (snapshot.status != "ready") throw LithosUnavailable("the pool index is still building; retry shortly")
      throw LithosNotFound(s"no LithosDex pool with NFT $poolNft")
    }
    nodeContext.getClient.execute { ctx =>
      val live = (indexed.n2t, indexed.t2t) match {
        case (Some(c), _) =>
          LiveN2T(indexed, c, LDLiquidityPool(LDBoxes.singletonBox(ctx, nodeApi, c.poolNFT, c.liquidityPool.ergoTreeHex,
            "liquidity pool")))
        case (_, Some(c)) =>
          LiveT2T(indexed, c, LDT2TLiquidityPool(LDBoxes.singletonBox(ctx, nodeApi, c.poolNFT,
            c.liquidityPool.ergoTreeHex, "liquidity pool")))
        case _ => throw new IllegalStateException(s"pool $poolNft was indexed without a deployment")
      }
      f(ctx, live)
    }
  }

  private def vault(ctx: BlockchainContext, live: Live) = live match {
    case n: LiveN2T => LDBoxes.singletonBox(ctx, nodeApi, n.contracts.vaultNFT, n.contracts.feeVault.ergoTreeHex, "fee vault")
    case t: LiveT2T => LDBoxes.singletonBox(ctx, nodeApi, t.contracts.vaultNFT, t.contracts.feeVault.ergoTreeHex, "fee vault")
  }

  private def provision(ctx: BlockchainContext, live: Live, boxId: String): LDBoxes.Provision = live match {
    case n: LiveN2T => LDBoxes.provisionById(ctx, nodeApi, boxId, n.contracts.provisionGuard.ergoTreeHex,
      n.contracts.provToken.toString)
    case t: LiveT2T => LDBoxes.provisionById(ctx, nodeApi, boxId, t.contracts.provisionGuard.ergoTreeHex,
      t.contracts.provToken.toString)
  }

  /**
   * Whether a swap or deposit may go into `pool`. A verified pool always may. An unverified one needs its genesis
   * sound, checked now if no index rebuild has checked it yet, and the caller's acknowledgement that it trades
   * with a pool nobody vetted. A pool whose genesis has problems can be drained, so no new funds go into it.
   */
  private def tradable(pool: IndexedPool, acknowledged: Boolean): Unit =
    if (!pool.verified) index.genesisNow(pool) match {
      case None => throw LithosUnavailable(s"pool ${pool.nft}'s genesis could not be read from the node; retry shortly")
      case Some(v) if v.problems.nonEmpty => throw LithosUnprocessable(
        s"pool ${pool.nft} can be drained, so no order is placed on it: ${v.problems.mkString("; ")}")
      case _ if !acknowledged => throw LithosBadRequest(
        s"pool ${pool.nft} is not on the verified list: set 'acknowledgeUnverified' to true to trade with it")
      case _ => ()
    }

  private def ergIn(live: LiveN2T, tokenIn: String): Boolean =
    if (tokenIn.equalsIgnoreCase("ERG")) true
    else if (tokenIn.equalsIgnoreCase(live.pool.tokenY.toString)) false
    else throw LithosBadRequest(s"'tokenIn' must be ERG or ${live.pool.tokenY}, the pool's two sides; got $tokenIn")

  private def sideOf(live: LiveT2T, tokenIn: String): ErgoId =
    Seq(live.pool.tokenX, live.pool.tokenY).find(_.toString.equalsIgnoreCase(tokenIn)).getOrElse(
      throw LithosBadRequest(s"'tokenIn' must be ${live.pool.tokenX} or ${live.pool.tokenY}, the pool's two tokens; got $tokenIn"))

  // ── placement ─────────────────────────────────────────────────────────────

  /**
   * Signs a placement, refusing before the send an order box that does not read back as this wallet's order
   * on this pool: no executor would find it, and its funds would wait for a cancel.
   */
  private def placed(ctx: BlockchainContext, live: Live, kind: String, plan: DexPlan[LDOrderTx]): LDPoolOrderPlaced = {
    val owners = wallet.signableTrees
    val built = fund(plan, (tx: LDOrderTx) => {
      val box = WalletInventory.nodeBox(tx.order)
      val reads = live match {
        case _: LiveN2T => LithosDexOrder.parse(box).exists(o => o.poolNft == live.indexed.nft &&
          owners.contains(o.terms.redeemerTree))
        case _: LiveT2T => LDT2TOrder.parse(box).exists(o => o.poolNft == live.indexed.nft &&
          owners.contains(o.terms.redeemerTree))
      }
      if (!reads) throw new IllegalStateException(
        s"the order box ${tx.order.id} does not read back as this wallet's order on pool ${live.indexed.nft}")
    })
    val operation = "dex:" + transactions.candidate.BlockTxMessages.CandidateTx.txId(built.value.tx)
    val result = new EngineBroadcast(walletSelector.walletRef, nodeApi)(walletSelector.executionContext)
      .send(built.value.tx, built.reservation.toSeq, operation, alive)
    val redeem = kind == "REDEEM"
    LDPoolOrderPlaced(result.outcome, result.txId, live.indexed.nft, kind, built.value.order.id.toString,
      if (redeem) Some(LDAmounts(built.value.claimedX)) else None,
      if (redeem) Some(LDAmounts(built.value.claimedY)) else None,
      built.value.provisionSuccessor)
  }

  private final case class Funded(value: LDOrderTx, reservation: Option[FundingAllocation])

  /** Reserves what `plan` needs, builds, signs and checks it, releasing the reservation if any step throws. */
  private def fund(plan: DexPlan[LDOrderTx], verify: LDOrderTx => Unit): Funded = {
    require(alive(), "engine attempt was superseded")
    val reservation =
      if (plan.value == 0L && plan.tokens.isEmpty) None else Some(walletSelector.reserve(plan.value, plan.tokens))
    try {
      val unsigned = plan.build(reservation.map(_.inputs).getOrElse(Seq.empty))
      Eip27Adjustment.validate(unsigned.tx, nodeContext.getNetwork)
      require(alive(), "engine attempt was superseded before signing")
      val described = unsigned.describe(wallet.sign(unsigned.tx))
      verify(described)
      Funded(described, reservation)
    } catch {
      case NonFatal(ex) => reservation.foreach(_.release()); throw ex
    }
  }

  // ── request fields ────────────────────────────────────────────────────────

  private case class Fees(executorFee: Long, maxMinerFee: Long)

  /** Fees a request names, or the configured defaults, refused unless the cap sits under the fee. */
  private def orderFees(executorFee: Option[String], maxMinerFee: Option[String]): Fees = {
    val fee = LDAmounts.parseLong("executorFee", executorFee).getOrElse(orderDefaults.executorFeeNanoErg)
    val cap = LDAmounts.parseLong("maxMinerFee", maxMinerFee).getOrElse(orderDefaults.maxMinerFeeNanoErg)
    if (cap < 0) throw LithosBadRequest(s"'maxMinerFee' cannot be negative, got $cap")
    if (cap >= fee) throw LithosBadRequest(
      s"'maxMinerFee' ($cap) must be below 'executorFee' ($fee); omitted fields come from lithosdex.orders")
    Fees(fee, cap)
  }

  private def positive(field: String, raw: String): Long = {
    val value = LDAmounts.parseLong(field, raw)
    if (value > 0) value else throw LithosBadRequest(s"'$field' must be positive, got $value")
  }

  /** A request whose amounts overflow a 64-bit total is refused rather than failing as a defect. */
  private def exact[A](build: => A): A =
    try build catch {
      case _: ArithmeticException => throw LithosBadRequest("the amounts in this request overflow a 64-bit total")
    }
}
