package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import configs.{EmissionConfig, NodeContext}
import lfsm.{CollateralParams, EmissionSchedule, LFSMHelpers}
import node.MutationConversions._
import node.model.IndexedBox
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClientException}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import sigma.SigmaProp
import sigma.ast.ErgoTree
import transactions.ProtocolContracts.{hex, lenderEntry}
import transactions.emissions.EmissionTransactions
import transactions.wallet.WalletMessages._
import transactions.wallet.{WalletReservation, WalletSelector}
import work.lithos.mutations.Token

import javax.inject.{Inject, Named, Singleton}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, blocking}
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Read-side composition over [[EmissionTransactions]]' box scans, plus the wallet-facing sweep and
 * join flows. Every method does blocking node IO and is expected to run off the request pool.
 */
@Singleton
class CollateralMarketApiImpl @Inject()(nodeContext: NodeContext,
                                        config: Configuration,
                                        system: ActorSystem,
                                        @Named("wallet-manager") walletManager: ActorRef)
  extends CollateralMarketApi {

  private val logger: Logger = LoggerFactory.getLogger("CollateralMarketApi")

  private val emissionConfig: EmissionConfig = EmissionConfig(config)

  private implicit val ec: ExecutionContext = system.dispatcher

  private val walletSelector =
    WalletSelector(walletManager, WalletSelector.AskTimeout, ec)

  /** Ask timeout for the fast wallet replies; the claim covers a multi-broadcast sweep. */
  private val QuickAsk = Timeout(5 seconds)
  private val ClaimAsk = Timeout(180 seconds)

  /**
   * Ask the wallet actor and check the reply's type instead of casting it.
   *
   * A bare `asInstanceOf` here turns any other reply into a ClassCastException naming two internal
   * class names, which says nothing a reader can act on. The wallet has at least one message that
   * legitimately answers with a different type, and callers that care handle it themselves rather
   * than reaching this.
   *
   * Still a 500 by way of the controller's catch-all, deliberately: a dead wallet times out and a
   * busy one is late, so a reply of the WRONG type is a defect in this client. Reporting it as 503
   * would tell a caller to retry something no amount of retrying fixes.
   */
  private def askWallet[T](msg: Any, timeout: Timeout)(implicit tag: ClassTag[T]): T =
    askWalletRaw(msg, timeout) match {
      case reply if tag.runtimeClass.isInstance(reply) => reply.asInstanceOf[T]
      case other => throw new IllegalStateException(
        s"the wallet answered $msg with ${other.getClass.getSimpleName}, " +
          s"not ${tag.runtimeClass.getSimpleName}")
    }

  private def askWalletRaw(msg: Any, timeout: Timeout): Any =
    Await.result((walletManager ? msg)(timeout), timeout.duration)

  private lazy val txs: EmissionTransactions =
    new EmissionTransactions(nodeContext.getNodeWallet, nodeContext.getNodeApi,
      emissionConfig, walletSelector)

  private def addressOf(sigmaProp: SigmaProp, ctx: BlockchainContext): Option[String] =
    Try(Address.fromErgoTree(ErgoTree.fromProposition(sigmaProp), ctx.getNetworkType).toString).toOption

  /** The permit is the second token entry when it is present, identified by LIT id like every path in the builders. */
  private def permitOf(b: IndexedBox, litIdStr: String): Long =
    b.box.tokens.lift(1).filter(t => t.id.toString == litIdStr).map(_.amount).getOrElse(0L)

  private def lenderAddressOf(b: IndexedBox, ctx: BlockchainContext): Option[String] =
    Try(b.box.registerValues.apply(1).getValue.asInstanceOf[SigmaProp])
      .toOption.flatMap(addressOf(_, ctx))

  // ─── short-TTL snapshots ──────────────────────────────────────────────────

  /**
   * The slow half of every read here: two token scans paged to exhaustion, plus the node's address
   * list. Nothing in it changes except when an emission spend or a block lands, which is
   * block-paced, while the panel polling it is request-paced — the web UI refreshes on a timer that
   * goes down to two seconds, and each uncached tick is up to forty paged node calls.
   */
  private case class CollateralView(taken: Set[String],
                                    mine: Seq[Address],
                                    queuedByKey: Map[String, IndexedBox],
                                    liveByKey: Map[String, IndexedBox])

  /**
   * Freshness bound on those snapshots. Overridable so a spec can drive a rebuild without waiting.
   *
   * Ten seconds is the web UI's default poll interval. The exact number matters far less than the
   * invalidation: a join that consumed a key must not be followed by a read that still calls it
   * FREE, or the panel offers a position the join then refuses.
   */
  protected val snapshotTtlMs: Long = 10000L

  private var viewGeneration: Long = 0L
  private var cachedView: Option[(Long, Long, CollateralView)] = None
  private var cachedMarket: Option[(Long, Long, CollateralMarketInfo)] = None

  private def isFresh(at: Long, generation: Long): Boolean =
    generation == viewGeneration && System.nanoTime() - at < snapshotTtlMs * 1000000L

  /** Drop both snapshots. Called after anything that moves a position or a reward box. */
  private def invalidateSnapshots(): Unit = synchronized {
    viewGeneration += 1L
    cachedView = None
    cachedMarket = None
  }

  private def collateralView(ctx: BlockchainContext): CollateralView = {
    val (hit, generation) = synchronized {
      (cachedView.collect { case (at, g, v) if isFresh(at, g) => v }, viewGeneration)
    }
    hit.getOrElse {
      // Built outside the lock: this is seconds of node IO, and holding the monitor across it would
      // block the invalidation a concurrent join needs to make. The generation is what keeps that
      // safe — an invalidation during the build discards this result rather than storing a view of
      // the state as it was before the join.
      val built = readCollateralView(ctx)
      synchronized {
        if (generation == viewGeneration) cachedView = Some((System.nanoTime(), generation, built))
      }
      built
    }
  }

  private def readCollateralView(ctx: BlockchainContext): CollateralView = {
    val tx = txs
    val tip = tx.emissionTip(ctx)
    val gateTree = tx.contracts(ctx).gate.ergoTreeHex
    CollateralView(
      taken = tx.takenLenderKeys(ctx, tip),
      mine = tx.walletAddresses(ctx),
      queuedByKey = tx.queueBoxes(ctx, tx.withMempool)
        .filter(b => tx.queuePosition(b.box.registerValues).isDefined)
        .flatMap(b => tx.lenderKeyOf(b).map(k => hex(k) -> b))
        .toMap,
      liveByKey = liveBoxes(ctx, tx, gateTree)
        .flatMap(b => tx.lenderKeyOf(b).map(k => hex(k) -> b))
        .toMap)
  }

  // ─── market snapshot ──────────────────────────────────────────────────────

  override def getMarketInfo: CollateralMarketInfo = {
    val (hit, generation) = synchronized {
      (cachedMarket.collect { case (at, g, v) if isFresh(at, g) => v }, viewGeneration)
    }
    hit.getOrElse {
      val built = readMarketInfo
      synchronized {
        if (generation == viewGeneration) cachedMarket = Some((System.nanoTime(), generation, built))
      }
      built
    }
  }

  private def readMarketInfo: CollateralMarketInfo =
    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val tip = tx.emissionTip(ctx)
      val st = tx.readEmission(tip.box)
      val cfg = tx.readConfig(tx.configBox(ctx))
      val height = ctx.getHeight

      val usableRetirements = tx.proofOfSpendBoxes(ctx, tx.liveOnly).count { b =>
        b.box.creationHeight < height &&
          tx.retiringKey(b).exists(r => st.lenderSet.exists(_.sameElements(r)))
      }

      val litIdStr = st.lit.map(_.id).getOrElse(LFSMHelpers.LIT_ID).toString
      var permitLocked = BigInt(0)
      tx.scanByToken(ctx, LFSMHelpers.QUEUE_TOKEN, tx.withMempool) { page =>
        permitLocked += page
          .filter(b => tx.carriesOne(b, LFSMHelpers.QUEUE_TOKEN))
          .map(b => BigInt(permitOf(b, litIdStr))).sum
      }

      val (emittedTotal, _, founders) = EmissionSchedule.emissionAt(st.currentBlock, st.litHeld)
      val epoch = st.currentBlock / EmissionSchedule.EPOCH_LENGTH

      CollateralMarketInfo(
        syncHeight = height,
        activationCounter = st.currentBlock,
        queueHead = st.head,
        queueTail = st.tail,
        queueLength = st.backlog,
        activeSetSize = st.lenderSet.size,
        activeSetMax = EmissionSchedule.MAX_ACTIVE,
        bootstrapping = st.bootstrapping,
        proofsOfSpendAvailable = usableRetirements,
        queuedNanoErgs = (BigInt(st.backlog) * BigInt(CollateralParams.PRINCIPAL_FLOOR)).toString,
        activeNanoErgs =
          (BigInt(st.lenderSet.size) * BigInt(CollateralParams.PRINCIPAL_FLOOR)).toString,
        permitLitLocked = permitLocked.toString,
        principalNanoErgs = CollateralParams.PRINCIPAL_FLOOR.toString,
        currentPermitLit = cfg.permitAt(st.backlog).toString,
        permitFloorLit = cfg.permitParams(0).toString,
        permitCeilingLit = cfg.permitParams(1).toString,
        permitSlopeLitPerBox = cfg.permitParams(2).toString,
        emissionPhase = EmissionSchedule.phaseAt(st.currentBlock),
        foundersActive = founders.nonEmpty,
        currentEpoch = epoch,
        nextPhaseChangeHeight = EmissionSchedule.nextChangeAt(st.currentBlock),
        emissionRatePublicLit = EmissionSchedule.pubRateAt(st.currentBlock).toString,
        emissionRatePrivateLit = EmissionSchedule.privRateAt(st.currentBlock).toString,
        emissionRateTotalLit = emittedTotal.toString,
        scheduleEndsAtHeight = EmissionSchedule.FINAL_BLOCK
      )
    }

  // ─── listings ─────────────────────────────────────────────────────────────

  override def getQueue(limit: Option[Int], offset: Option[Int]): Seq[CollateralQueueEntry] =
    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val gateTree = tx.contracts(ctx).gate.ergoTreeHex
      val (from, count) = paged(limit, offset)
      val litIdStr = LFSMHelpers.LIT_ID.toString
      tx.queueBoxes(ctx, tx.withMempool)
        .filter(b => b.ergoTree == gateTree && tx.queuePosition(b.box.registerValues).isDefined)
        .sortBy(b => tx.queuePosition(b.box.registerValues).get)
        .slice(from, from + count)
        .flatMap(b => for {
          position <- tx.queuePosition(b.box.registerValues)
          lender <- lenderAddressOf(b, ctx)
        } yield CollateralQueueEntry(
          boxId = b.boxId,
          position = position,
          lenderAddress = lender,
          principalNanoErgs = b.value.toString,
          permitLit = permitOf(b, litIdStr).toString,
          creationHeight = b.inclusionHeight
        ))
    }

  override def getActiveSet(limit: Option[Int], offset: Option[Int]): Seq[CollateralActiveEntry] =
    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val gateTree = tx.contracts(ctx).gate.ergoTreeHex
      val (from, count) = paged(limit, offset)
      val litIdStr = LFSMHelpers.LIT_ID.toString
      liveBoxes(ctx, tx, gateTree)
        .sortBy(-_.box.creationHeight)
        .slice(from, from + count)
        .flatMap(b => lenderAddressOf(b, ctx).map(a => CollateralActiveEntry(
          boxId = b.boxId,
          lenderAddress = a,
          valueNanoErgs = b.value.toString,
          carriedLit = litOnBox(b).toString,
          creationHeight = b.box.creationHeight
        )))
    }

  /** A live collateral box carries its block's emission LIT and the lender's permit together. */
  private def litOnBox(b: IndexedBox): Long =
    b.box.tokens.lift(1).map(_.amount).getOrElse(0L)

  /** Live collateral boxes only - the same scan also returns proof-of-spend boxes at the gate, which carry one register. */
  private def liveBoxes(ctx: BlockchainContext,
                        tx: EmissionTransactions,
                        gateTree: String): Seq[IndexedBox] = {
    val all = mutable.ArrayBuffer.empty[IndexedBox]
    tx.scanByToken(ctx, LFSMHelpers.COLLAT_TOKEN, tx.withMempool)(all ++= _)
    all.filter(b => tx.carriesOne(b, LFSMHelpers.COLLAT_TOKEN) &&
      b.ergoTree != gateTree &&
      b.box.additionalRegisters.ordered.size >= 5).toSeq
  }

  // ─── this wallet ──────────────────────────────────────────────────────────

  override def getWalletStatus: WalletCollateralStatus = {
    val rewards = askWallet[RewardSummary](GetUnlockedRewards, QuickAsk)

    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val view = collateralView(ctx)
      val mineEntries = view.mine.map(a => hex(lenderEntry(a))).toSet
      val litIdStr = LFSMHelpers.LIT_ID.toString

      val keyStates = view.mine.map { a =>
        val entry = hex(lenderEntry(a))
        if (view.liveByKey.contains(entry))
          CollateralLenderKeyStatus(a.toString, CollateralLenderKeyStatus.Active, None)
        else view.queuedByKey.get(entry) match {
          case Some(b) => CollateralLenderKeyStatus(a.toString, CollateralLenderKeyStatus.Queued,
            tx.queuePosition(b.box.registerValues))
          // Taken with no box of ours behind it: a proof of spend still names the key, so `join`
          // will not use it. Called FREE, this was the count that told the panel a join could go
          // ahead and then produced a 422 from the endpoint that filters on exactly this set.
          case None if view.taken.contains(entry) =>
            CollateralLenderKeyStatus(a.toString, CollateralLenderKeyStatus.Retiring, None)
          case None => CollateralLenderKeyStatus(a.toString, CollateralLenderKeyStatus.Free, None)
        }
      }

      val positions =
        view.queuedByKey.collect {
          case (entry, b) if mineEntries.contains(entry) =>
            CollateralPositionSummary("QUEUED", b.boxId, tx.queuePosition(b.box.registerValues),
              b.value.toString, Some(permitOf(b, litIdStr).toString), None, b.inclusionHeight)
        } ++
        view.liveByKey.collect {
          case (entry, b) if mineEntries.contains(entry) =>
            CollateralPositionSummary("LIVE", b.boxId, None,
              b.value.toString, None, Some(litOnBox(b).toString), b.box.creationHeight)
        }

      val ourLive = view.mine.count(a => view.taken.contains(hex(lenderEntry(a))))

      WalletCollateralStatus(
        autoCollateralize = emissionConfig.autoCollateralize,
        maxOwnCollateral = emissionConfig.maxOwnCollateral,
        maxJoinsPerRun = emissionConfig.maxJoinsPerRun,
        maxLenderKeys = emissionConfig.maxLenderKeys,
        maxPermitPerJoinLit = emissionConfig.maxPermitPerJoin.toString,
        ownPositionsQueued = positions.count(_.kind == "QUEUED"),
        ownPositionsLive = positions.count(_.kind == "LIVE"),
        ownBudgetRemaining = math.max(0, emissionConfig.maxOwnCollateral - ourLive),
        freeLenderKeys = keyStates.count(_.status == CollateralLenderKeyStatus.Free),
        // How many MORE keys may be derived. Separate from the free count because they gate a join
        // differently: no free key with room to derive is not blocked, at the ceiling it is.
        keysDerivable = math.max(0, emissionConfig.maxLenderKeys - view.mine.size),
        lenderKeys = keyStates,
        positions = positions.toSeq,
        rewards = CollateralRewardSummary(rewards.lockedBoxes, rewards.unlockedBoxes,
          rewards.lockedNanoErgs.toString, rewards.unlockedNanoErgs.toString,
          rewards.blocksUntilFirstUnlock)
      )
    }
  }

  // ─── rewards ──────────────────────────────────────────────────────────────

  override def getRewardSummary: CollateralRewardSummary = {
    val rewards = askWallet[RewardSummary](GetUnlockedRewards, QuickAsk)
    CollateralRewardSummary(rewards.lockedBoxes, rewards.unlockedBoxes,
      rewards.lockedNanoErgs.toString, rewards.unlockedNanoErgs.toString,
      rewards.blocksUntilFirstUnlock)
  }

  override def claimUnlockedRewards: CollateralRewardClaimResult = {
    val summary = askWallet[RewardSummary](GetUnlockedRewards, QuickAsk)
    if (summary.unlockedBoxes == 0)
      throw new CollateralMarketApi.NothingToClaim("no unlocked reward boxes to claim")
    val reply = askWalletRaw(ClaimUnlockedRewards, ClaimAsk)
    // Nothing in either snapshot is derived from reward boxes today - the counts and the spendable
    // balance are asked of the wallet actor per request - so this is insurance against a later field
    // that is, not a correction. Kept because a sweep is rare and a rebuild is one scan.
    invalidateSnapshots()

    reply match {
      case RewardsClaimed(chunks) if chunks.isEmpty =>
        // The pre-check above passed, so another caller is holding the leases right now.
        throw new CollateralMarketApi.NothingToClaim(
          "another claim is already sweeping these boxes - try again shortly")
      case RewardsClaimed(chunks) =>
        CollateralRewardClaimResult(
          claims = chunks.map(c => CollateralRewardClaimedChunk(c.txId, c.boxes, c.nanoErgs.toString)),
          claimedNanoErgs = chunks.map(_.nanoErgs).sum.toString)
      // A real reply the wallet sends and this used to cast away: the sweep raised before finishing.
      // Cast, it became a ClassCastException naming two class names, so the one message carrying why
      // the sweep failed was the one thing the caller could not see.
      case RewardClaimFailed(reason) =>
        throw LithosApiErrors.LithosUnprocessable(s"the reward sweep could not complete: $reason")
      case other =>
        // Both replies the wallet actually sends are handled above, so this is a defect here.
        throw new IllegalStateException(
          s"the wallet answered a claim with ${other.getClass.getSimpleName}")
    }
  }

  /** GET /collateral/permits/history */
  override def getPermitHistory(limit: Option[Int]): CollateralPermitHistory =
    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val gateTree = tx.contracts(ctx).gate.ergoTreeHex
      val litIdStr = LFSMHelpers.LIT_ID.toString
      val st = tx.readEmission(tx.emissionTip(ctx).box)
      val cap = math.max(1, math.min(2000, limit.getOrElse(500)))
      val joined =
        tx.queueBoxes(ctx, tx.withMempool)
          .filter(b => b.ergoTree == gateTree && tx.queuePosition(b.box.registerValues).isDefined)
          .map(b => (tx.queuePosition(b.box.registerValues).get, b.inclusionHeight,
            permitOf(b, litIdStr)))
          .sortBy(_._1)

      // Sample evenly when the queue holds more points than were asked for; the newest join is
      // always kept so the series ends at the price a new position would pay alongside.
      val stride = math.max(1, (joined.length + cap - 1) / cap)
      val keep = (0 until joined.length by stride).toSet + (joined.length - 1)
      val points = joined.zipWithIndex.collect {
        case ((position, height, permit), i) if keep.contains(i) =>
          CollateralPermitHistoryPoint(position, height, permit.toString)
      }.toSeq

      CollateralPermitHistory(points, st.head, st.tail, truncated = joined.size < st.backlog)
    }

  // ─── joining ──────────────────────────────────────────────────────────────

  private val MaxJoinCount = 25

  override def checkJoin(request: CollateralJoinCheckRequest): CollateralJoinQuote =
    nodeContext.getClient.execute { ctx =>
      val tx = txs
      val count = joinCount(request.count)
      val tip = tx.emissionTip(ctx)
      val cs = tx.readConfig(tx.configBox(ctx))
      // Read once. Parsing R5 rebuilds up to a hundred hashed lender keys, and this was doing it
      // twice for two fields of the same box.
      val em = tx.readEmission(tip.box)
      val permits = (0 until count).map(i => cs.permitAt(em.backlog + i))

      val spendable = blocking(askWallet[SpendableBalance](GetSpendableBalance, QuickAsk).nanoErgs)
      val total = count.toLong * (CollateralParams.PRINCIPAL_FLOOR + emissionConfig.txFee)

      // `takenLenderKeys` used to sit INSIDE this count, so it ran once per wallet address: with
      // the shipped 32 keys, sixty-four exhaustive paged token scans for one unauthenticated
      // request. It is one derivation over the whole address list, not a per-address predicate.
      val view = collateralView(ctx)
      val freeAddresses = view.mine.filterNot(a => view.taken.contains(hex(lenderEntry(a))))
      val ourLive = view.mine.size - freeAddresses.size
      val roomLeft = math.max(0, emissionConfig.maxOwnCollateral - ourLive)
      val derivable = math.max(0, emissionConfig.maxLenderKeys - view.mine.size)

      // Ordered by what the reader has to change first: a config toggle, then a config number, then
      // a key budget, then money. Reporting the last of these when the first also holds sends
      // somebody to top up a wallet that was never the reason.
      val blocked =
        if (emissionConfig.autoCollateralize) Some(CollateralJoinBlock.AutoCollateralize)
        else if (roomLeft < count) Some(CollateralJoinBlock.BudgetExhausted)
        else if (freeAddresses.size + derivable < count) Some(CollateralJoinBlock.NoLenderKeys)
        else if (spendable < total) Some(CollateralJoinBlock.InsufficientFunds)
        else None

      CollateralJoinQuote(
        count = count,
        principalEachNanoErgs = CollateralParams.PRINCIPAL_FLOOR.toString,
        txFeeEachNanoErgs = emissionConfig.txFee.toString,
        permitsLit = permits.map(_.toString),
        permitTotalLit = permits.sum.toString,
        totalNanoErgs = total.toString,
        firstPosition = em.tail,
        emissionTipBoxId = tip.box.id.toString,
        affordNow = spendable >= total,
        shortfallNanoErgs = math.max(0L, total - spendable).toString,
        ownRoomLeft = math.max(0, roomLeft - count),
        manualJoinAllowed = blocked.isEmpty,
        blockedReason = blocked,
        // Only the keys that already exist. Naming all `count` of them would mean deriving the
        // shortfall here, and a derivation writes to the node's wallet - a quote must not.
        lenderAddresses =
          if (blocked.contains(CollateralJoinBlock.NoLenderKeys)) Seq.empty
          else freeAddresses.take(count).map(_.toString),
        freeLenderKeys = freeAddresses.size,
        keysRequired = count
      )
    }

  override def join(request: CollateralJoinExecuteRequest): CollateralJoinResult = {
    val count = joinCount(Some(request.count))

    // Queued principal and permit come back only when a block spends the box. There is no early
    // exit, so the acknowledgement is carried in the request rather than inferred from one arriving
    // - the same guard /dex/redeem puts on its own irreversible spend.
    if (!request.acknowledgeNoWithdrawal.contains(true))
      throw LithosApiErrors.LithosBadRequest(
        "acknowledgeNoWithdrawal must be true: a queued position cannot be withdrawn or cancelled, " +
          "and its principal and permit are returned only when a block spends the box")

    // Enforced here regardless of what the quote said. The automated pass owns key selection, and a
    // hand-made join racing it can post a second box under a key the pass has already used - the
    // protocol allows one live box per key, so the later position is forfeit at the head of the
    // queue. A quote is a snapshot and the config can change under it; this cannot.
    if (emissionConfig.autoCollateralize)
      throw LithosApiErrors.LithosStateChanged(
        "autoCollateralize is set, so this client's automated pass owns lender-key selection. " +
          "A manual join can race it onto the same key, and the second position under one key is " +
          "forfeited rather than activated. Turn emission.autoCollateralize off to join by hand")

    val permitCap = request.maxPermitEachLit.map(s =>
      Try(s.trim.toLong).getOrElse(throw new IllegalArgumentException(
        s"maxPermitEachLit '$s' is not an integer")))

    try nodeContext.getClient.execute { ctx =>
      val tx = txs
      val tip = tx.emissionTip(ctx)
      request.expectedEmissionBoxId.foreach { expected =>
        if (tip.box.id.toString != expected)
          throw LithosApiErrors.LithosStateChanged("the emission box moved between quote and send")
      }
      val cfgBox = tx.configBox(ctx)
      val cs = tx.readConfig(cfgBox)
      val tipState = tx.readEmission(tip.box)

      // Priced up front so a cap breach sends nothing, matching what /join/check showed.
      val plannedPermits = (0 until count).map(i => cs.permitAt(tipState.backlog + i))
      permitCap.foreach { cap =>
        plannedPermits.find(_ > cap).foreach { p =>
          throw LithosApiErrors.LithosUnprocessable(
            s"a permit of $p LIT exceeds the $cap LIT cap - nothing was sent")
        }
      }

      // Read fresh, never from the snapshot: this decides which keys real money goes to, and a key
      // that stopped being free ten seconds ago produces a box that can only be cleared.
      val mine = tx.walletAddresses(ctx)
      val taken = tx.takenLenderKeys(ctx, tip)
      val ourLive = mine.count(a => taken.contains(hex(lenderEntry(a))))
      val room = math.max(0, emissionConfig.maxOwnCollateral - ourLive)
      val keys = tx.freeLenderKeys(ctx, taken, math.min(count, room))
      if (keys.size < count)
        throw LithosApiErrors.LithosUnprocessable(
          s"only ${keys.size} of $count position(s) fit within the configured exposure budget " +
            s"(${emissionConfig.maxOwnCollateral}) and free lender keys")

      val funding = new tx.FundingSource
      val litId = tipState.lit.map(_.id).getOrElse(LFSMHelpers.LIT_ID)
      var em = tip.box
      var remaining = keys
      var sent = Vector.empty[CollateralJoinEntry]
      // The cause, not just its text: when nothing was sent it is rethrown as-is so the controller
      // classifies it the way it would have without the run - a wallet shortfall stays a 422 and a
      // defect in this client stays a 500, rather than every failure collapsing into one status.
      var stopped = Option.empty[Throwable]

      try {
        while (remaining.nonEmpty && stopped.isEmpty) {
          val lender = remaining.head
          remaining = remaining.tail
          val state = tx.readEmission(em)
          val permit = cs.permitAt(state.backlog)
          val position = state.tail
          var reservations = Seq.empty[WalletReservation]
          var submissionStarted = false
          Try {
            val inputs = funding.take(CollateralParams.PRINCIPAL_FLOOR + emissionConfig.txFee * 2,
              if (permit > 0) Some(Token(litId, permit)) else None)
            val sTx = tx.genJoin(ctx, em, cfgBox, lender, inputs)
            val chained = tx.chainOn(sTx, funding)
            reservations = funding.pendingReservations
            WalletReservation.beginAll(reservations)
            submissionStarted = true
            val txId = ctx.sendTransaction(sTx).replace("\"", "")
            reservations.foreach(_.commit())
            funding.clearPending()
            chained.walletChange.foreach(funding.markAccepted)
            em = chained.emission
            txId
          } match {
            case Success(txId) =>
              sent :+= CollateralJoinEntry(txId, position, lender.toString,
                CollateralParams.PRINCIPAL_FLOOR.toString, permit.toString)
            case Failure(ex) =>
              // An acknowledged lease has no TTL and `release` does not touch one, so a send that
              // threw after `beginAll` left its inputs held until the process restarted - a wallet
              // the rollup and emission paths draw from, losing 2.9 ERG of capacity per attempt.
              if (submissionStarted) reservations.foreach(_.uncertain())
              // Stop rather than continue: each join chains off the previous one's emission
              // successor, so everything after a failure would be built against state that does
              // not exist.
              stopped = Some(ex)
              logger.warn(s"Stopping join run after ${sent.size} position(s): ${ex.getMessage}", ex)
          }
        }
      } finally
        // Every chained box came from a join the node accepted, so its change is real.
        funding.finish(returnChange = sent.nonEmpty)

      // Nothing sent means the caller needs a status, not a 200 with an empty list - that reads the
      // same as a deliberate no-op. Anything sent is spent principal and must be reported, with the
      // reason the run stopped short.
      if (sent.isEmpty) stopped.foreach(ex => throw asStatus(ex))

      CollateralJoinResult(sent,
        (sent.size.toLong * (CollateralParams.PRINCIPAL_FLOOR + emissionConfig.txFee)).toString,
        stopped.map(ex => Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))
    } finally
      // Unconditional: a run that stopped part way still consumed keys and wallet boxes, and the
      // next read must not call those keys FREE.
      invalidateSnapshots()
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  /**
   * The status a failed join run should carry, when it created nothing.
   *
   * Only two node rejections are worth reclassifying, and both mean the same thing to a caller: the
   * emission box this run was built against has moved, so re-read and quote again. Everything else
   * keeps its own type so the controller's existing mapping decides - a wallet shortfall stays 422,
   * a defect in this client stays 500 and is not disguised as something the caller can retry.
   */
  private def asStatus(ex: Throwable): Throwable = ex match {
    case node: ErgoClientException
      if Option(node.getMessage).exists(m =>
        m.contains("Double spending") || m.contains("Every input of the transaction should be in UTXO")) =>
      LithosApiErrors.LithosStateChanged(
        s"the emission box was spent by someone else before this join reached the node, so nothing " +
          s"was created - re-quote and resend (${node.getMessage})")
    case other => other
  }

  private def joinCount(raw: Option[Int]): Int = {
    val count = raw.getOrElse(1)
    if (count < 1 || count > MaxJoinCount)
      throw new IllegalArgumentException(s"count must be between 1 and $MaxJoinCount")
    count
  }

  private def paged(limit: Option[Int], offset: Option[Int]): (Int, Int) =
    (math.max(0, offset.getOrElse(0)), math.max(1, math.min(100, limit.getOrElse(50))))
}
