package transactions.engine

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import configs.NodeContext
import node.MutationConversions._
import node.NodeApi
import mutations.NodeWallet.MINER_REWARD_DELAY
import node.model.{ConfirmationRange, MempoolOptions, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, Parameters}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.concurrent.InjectedActorSupport
import transactions.engine.EngineWalletState._
import transactions.engine.EngineWalletMessages._
import work.lithos.mutations.{Eip27Adjustment, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

import java.util.UUID
import javax.inject.Inject
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.concurrent.blocking
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/** Wallet ownership and compact inventory state mixed into the transaction engine actor. */
class EngineWalletState @Inject()(nodeContext: NodeContext) extends Actor with InjectedActorSupport {

  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("EngineWalletState")

  val client: ErgoClient = nodeContext.getClient
  def nodeApi: NodeApi = nodeContext.getNodeApi

  private val wallet = nodeContext.getNodeWallet
  private lazy val inventory = new WalletInventory(nodeContext, nodeApi)
  private lazy val walletWorker = context.system.dispatchers.lookup("lithos-contexts.wallet-io-dispatcher")
  private var selecting: Option[UUID] = None
  private var criticalSelecting: Option[UUID] = None
  private var criticalMessage = false
  private lazy val criticalWalletWorker = context.system.dispatchers.lookup("lithos-contexts.critical-wallet-dispatcher")
  private var waitingSelections = Vector.empty[SelectionRequest]
  private var knownOutputs = Vector.empty[node.model.NodeBox]
  private var summary: Option[(Long, WalletInventory.Snapshot)] = None
  private var sweeping = false
  private val walletIncarnation = UUID.randomUUID()
  private val walletAlive = new java.util.concurrent.atomic.AtomicBoolean(true)

  private def remember(outputs: Seq[InputUTXO]): Unit = {
    val accepted = outputs.take(WalletInventory.MaxInputs).filter(_.bytes.length <= WalletInventory.MaxInputBytes)
      .map(WalletInventory.nodeBox)
    knownOutputs = (accepted ++ knownOutputs).groupBy(_.boxId).valuesIterator.map(_.head)
      .take(WalletInventory.MaxInputs).toVector
  }

  private def trimInventory(): Unit = {
    var bytes = 0L
    val kept = (walletBoxes.valuesIterator ++ rewardBoxes.valuesIterator)
      .take(WalletInventory.MaxDescriptors).filter { box =>
        bytes += box.retainedBytes
        bytes <= WalletInventory.MaxDescriptorBytes
      }.map(_.id).toSet
    walletBoxes = walletBoxes.filter { case (id, _) => kept.contains(id) }
    rewardBoxes = rewardBoxes.filter { case (id, _) => kept.contains(id) }
  }

  private def descriptorValue(box: WalletDescriptor): Long = {
    val due = if (nodeContext.getNetwork != org.ergoplatform.appkit.NetworkType.MAINNET) 0L
      else box.tokens.filter(_.id.toString == MainnetEip27Constants.TokenId)
        .foldLeft(0L)((n, t) => Math.addExact(n, t.amount))
    Math.subtractExact(box.value, due)
  }

  private def valueOf(boxes: Seq[WalletDescriptor]): BigInt = boxes.map(b => BigInt(descriptorValue(b))).sum

  private def selectWallet(erg: Long, tokens: Seq[Token], track: Boolean, id: String,
                           deadline: Long, single: Boolean, p2pkOnly: Boolean,
                           reply: ActorRef = sender(), critical: Boolean = criticalMessage): Unit = {
    val busy = if (critical) criticalSelecting.nonEmpty else selecting.nonEmpty
    val limit = if (critical) MAX_ENGINE_INPUTS else MAX_OPTIONAL_INPUTS
    if (busy && now() < deadline && waitingSelections.count(_.critical == critical) < 8 && tokens.size <= 512)
      waitingSelections :+= SelectionRequest(erg, tokens, track, id, deadline, single, p2pkOnly, reply, critical)
    else if (busy || now() >= deadline || tokens.size > 512 ||
      usedInputs.size + (if (single) 1 else WalletInventory.MaxInputs) > limit)
      reply ! WalletInputs(Seq.empty, id)
    else {
      val attempt = UUID.randomUUID()
      if (critical) criticalSelecting = Some(attempt) else selecting = Some(attempt)
      val excluded = usedInputs.keySet
      val known = knownOutputs
      Try(Future(client.execute { ctx =>
        inventory.select(ctx, erg, tokens, excluded, single, p2pkOnly, rewardsOnly = false, known)
      })(if (critical) criticalWalletWorker else walletWorker)
        .onComplete(r => self ! SelectionFinished(attempt, id, deadline, track, reply, r, critical)))
        .failed.foreach(ex => self ! SelectionFinished(attempt, id, deadline, track, reply, Failure(ex), critical))
    }
  }

  private def drainSelections(): Unit = {
    var ready = waitingSelections.indexWhere(r => if (r.critical) criticalSelecting.isEmpty else selecting.isEmpty)
    while (ready >= 0) {
      val r = waitingSelections(ready)
      waitingSelections = waitingSelections.patch(ready, Nil, 1)
      selectWallet(r.erg, r.tokens, r.track, r.id, r.deadline, r.single, r.p2pkOnly, r.reply, r.critical)
      ready = waitingSelections.indexWhere(r => if (r.critical) criticalSelecting.isEmpty else selecting.isEmpty)
    }
  }

  private def hydrateRewards(boxes: Seq[WalletDescriptor]): Seq[InputUTXO] = client.execute { ctx =>
    boxes.map { descriptor =>
      val box = nodeApi.boxById(descriptor.id).get.getOrElse(throw new IllegalStateException("reward input is unavailable"))
      val input = box.toInputUTXO(ctx)
      require(input.id.toString == descriptor.id && input.bytes.length <= WalletInventory.MaxInputBytes,
        "reward identity or hydration budget changed")
      input
    }
  }

  // ─── mutable state, all keyed by box id ───────────────────────────────────

  /** Known wallet UTXOs, including change handed back by ReturnInputs. */
  private var walletBoxes: Map[String, WalletDescriptor] = Map.empty

  /** Reserved by RetrieveInputs and not yet known to be spent, with lease identity and age. */
  private var usedInputs: Map[String, ReservationState] = Map.empty

  /** Coinbase boxes at this client's addresses, found by ergoTree since no wallet reports them. */
  private var rewardBoxes: Map[String, WalletDescriptor] = Map.empty

  /** Chain height as of the last refresh, used to tell which reward boxes have unlocked. */
  private var chainHeight: Int = 0

  /** Overridable so a test can age a reservation without waiting out the TTL. */
  protected def now(): Long = System.currentTimeMillis()

  private def reserve(ids: Seq[String],
                      reservationId: String,
                      status: ReservationStatus = ReservationSelected): Unit = {
    val at = now()
    usedInputs ++= ids.map(_ -> ReservationState(reservationId, at, status))
    walletRevision += 1L
  }

  private def unreserved(boxes: Map[String, WalletDescriptor]): Seq[WalletDescriptor] =
    (boxes -- usedInputs.keys).values.toSeq

  /** What a selection may draw from. */
  private def available: Seq[WalletDescriptor] = unreserved(walletBoxes)

  /** Reward boxes past their timelock. Uses the last refresh's height, so it errs to holding back. */
  private def spendableRewards: Seq[WalletDescriptor] =
    unreserved(rewardBoxes).filter(b => chainHeight > b.creationHeight + MINER_REWARD_DELAY)

  private var warnedNoIndexer: Boolean = false

  private var refreshTicker: Option[Cancellable] = None
  private var resetTicker: Option[Cancellable] = None
  private var nextRefreshGeneration: Long = 0L
  private var lastAppliedRefreshGeneration: Long = 0L
  private var refreshInFlight: Boolean = false
  private var refreshAgain: Boolean = false
  private var walletRevision: Long = 0L

  /** Start one off-mailbox refresh. A tick that arrives meanwhile is coalesced into one rerun. */
  private def beginRefresh(): Unit = {
    refreshInFlight = true
    nextRefreshGeneration += 1L
    val generation = nextRefreshGeneration
    val snapshotRevision = walletRevision
    val excluded = usedInputs.keySet
    Try(Future(client.execute { ctx =>
      val indexed = nodeApi.indexerEnabled
      val snapshot = inventory.snapshot(ctx.getHeight, excluded)
      self ! WalletWorkerResult(walletIncarnation, TotalsRefreshed(snapshotRevision, snapshot.copy(boxes = Vector.empty)))
      val boxes = snapshot.boxes.filterNot(_.reward)
      val rewards = snapshot.boxes.filter(_.reward)
      BoxesRefreshed(generation, snapshotRevision, boxes, rewards, ctx.getHeight,
        snapshot.complete && !snapshot.truncated, indexed)
    })(walletWorker).onComplete {
      case Success(msg) => self ! WalletWorkerResult(walletIncarnation, msg)
      case Failure(ex) => self ! WalletWorkerResult(walletIncarnation, RefreshFailed(generation, ex))
    }).failed.foreach(ex => self ! WalletWorkerResult(walletIncarnation, RefreshFailed(generation, ex)))
  }

  private def finishRefresh(): Unit = {
    refreshInFlight = false
    if (refreshAgain) {
      refreshAgain = false
      beginRefresh()
    }
  }

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    refreshTicker = Some(
      context.system.scheduler.scheduleWithFixedDelay(5.seconds, 10.minutes, self, RefreshBoxes)(context.dispatcher)
    )
    resetTicker = Some(
      context.system.scheduler.scheduleWithFixedDelay(15.minutes, 15.minutes, self, ResetUsedInputs)(context.dispatcher)
    )
  }

  override def postStop(): Unit = {
    walletAlive.set(false)
    refreshTicker.foreach(_.cancel())
    resetTicker.foreach(_.cancel())
  }

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {
    case CriticalWalletRequest(request @ (_: RetrieveInputs | _: RetrieveCoveringInput | _: RetrieveCoveringP2PKInput | _: ReserveKnownInputs)) =>
      criticalMessage = true
      try receive(request) finally criticalMessage = false
    case WalletWorkerResult(incarnation, result) if incarnation == walletIncarnation => receive(result)
    case _: WalletWorkerResult => ()
    case TotalsRefreshed(revision, totals) if revision == walletRevision => summary = Some(revision -> totals)
    case _: TotalsRefreshed => ()
    case SweepFinished => sweeping = false
    case SelectionFinished(attempt, id, deadline, track, reply, result, critical)
      if selecting.contains(attempt) || criticalSelecting.contains(attempt) =>
      if (critical) criticalSelecting = None else selecting = None
      val selected = result.toOption.filter(_ => now() < deadline)
        .filter(_.forall(b => !usedInputs.contains(b.id.toString))).getOrElse(Vector.empty)
        .filter(_ => usedInputs.size + result.toOption.map(_.size).getOrElse(0) <=
          (if (critical) MAX_ENGINE_INPUTS else MAX_OPTIONAL_INPUTS))
      result.failed.foreach(ex => logger.warn(s"Wallet selection deferred: ${ex.getMessage}"))
      if (track && selected.nonEmpty) reserve(selected.map(_.id.toString), id)
      reply ! WalletInputs(selected, id)
      drainSelections()
    case _: SelectionFinished => ()
    case PinEngineInputs(hold, previousTxId) =>
      val matching = usedInputs.filter(_._2.id == hold.reservationId)
      val heldCount = usedInputs.values.count(_.status.isInstanceOf[ReservationEngine])
      val accepted = matching.nonEmpty && matching.keySet == hold.walletInputIds &&
        matching.values.forall(_.status match {
          case ReservationSelected | ReservationKnown => previousTxId.isEmpty
          case ReservationEngine(old) => old.sendFinished && previousTxId.contains(old.txId) &&
            old.operation == hold.operation && old.signedInputIds == hold.signedInputIds
          case _ => false
        }) && heldCount + (if (previousTxId.isEmpty) matching.size else 0) <= MAX_ENGINE_INPUTS
      if (accepted) {
        usedInputs ++= matching.map { case (id, state) => id -> state.copy(status = ReservationEngine(hold)) }
        walletRevision += 1L
      }
      sender() ! accepted

    case CancelEngineInputs(id, txId, previous) =>
      // Only the engine's worker sends this, after proving it never called the node.
      usedInputs = usedInputs.flatMap {
        case (boxId, state @ ReservationState(`id`, _, ReservationEngine(hold))) if hold.txId == txId && !hold.sendFinished =>
          previous.map(old => boxId -> state.copy(status = ReservationEngine(old)))
        case entry => Some(entry)
      }
      walletRevision += 1L

    case EngineSendFinished(id, txId, accepted) =>
      usedInputs = usedInputs.map {
        case (boxId, state @ ReservationState(`id`, _, ReservationEngine(hold))) if hold.txId == txId =>
          boxId -> state.copy(status = ReservationEngine(hold.copy(sendFinished = true, accepted = accepted)))
        case entry => entry
      }

    case GetEngineHolds =>
      val held = usedInputs.toVector.collect {
        case (boxId, ReservationState(_, _, ReservationEngine(hold))) => hold -> boxId
      }.groupBy(_._1.reservationId).values.map { entries =>
        entries.head._1.copy(walletInputIds = entries.map(_._2).toSet)
      }.toVector
      sender() ! EngineHolds(held)

    case ResolveEngineInputs(id, txId, spent, free) =>
      val allowed = usedInputs.collect {
        case (boxId, ReservationState(`id`, _, ReservationEngine(hold)))
          if hold.txId == txId && hold.sendFinished => boxId
      }.toSet
      val resolved = (spent ++ free).intersect(allowed)
      usedInputs --= resolved
      walletBoxes --= spent.intersect(allowed)
      rewardBoxes --= spent.intersect(allowed)
      if (resolved.nonEmpty) walletRevision += 1L
      sender() ! (resolved == (spent ++ free))

    // Off the actor thread: this pages the wallet and then does one lookup per address, and callers
    // Await on this mailbox with a 5-second budget. Blocking here fails their transactions.
    case RefreshBoxes if refreshInFlight => refreshAgain = true

    case RefreshBoxes => beginRefresh()

    case RefreshFailed(_, ex) =>
      logger.error(s"Failed to refresh wallet boxes: ${ex.getMessage}", ex)
      finishRefresh()

    case BoxesRefreshed(generation, _, _, _, _, _, _) if generation < lastAppliedRefreshGeneration =>
      logger.debug(s"Ignoring stale wallet refresh $generation; $lastAppliedRefreshGeneration already applied")
      finishRefresh()

    case BoxesRefreshed(_, snapshotRevision, _, _, _, _, _) if snapshotRevision != walletRevision =>
      // A commit/returned output/ambiguous transition happened after this read began. Applying its
      // older box set could resurrect an accepted parent or erase accepted change.
      refreshAgain = true
      finishRefresh()

    case BoxesRefreshed(generation, _, boxes, rewards, height, complete, indexed) =>
      lastAppliedRefreshGeneration = generation
      if (!indexed && !warnedNoIndexer) {
        warnedNoIndexer = true
        logger.warn("Node is not indexed (extraIndex = false), so mined coinbases cannot be found. " +
          "They accrue at addresses no wallet reports and stay unspendable until it is enabled")
      }
      val fresh = boxes.map(b => b.id.toString -> b).toMap
      val freshRewards = rewards.map(b => b.id.toString -> b).toMap
      if (complete) {
        // A reserved box neither read still reports as unspent HAS been spent, so the box and its
        // reservation go together. Keeping the reservation was what made a spent box selectable
        // again: it is retained here BECAUSE it is reserved, so it survived every refresh and came
        // back the moment the reservation was cleared.
        //
        // BOTH sets, not just the wallet. Coinbases are tracked separately because no wallet reports
        // them, so comparing against the wallet alone declared every reserved coinbase spent on
        // sight — and `coveringReward` hands one out for most ordinary fees.
        val reported = fresh.keySet ++ freshRewards.keySet
        // An ambiguous broadcast is resolved in either direction by a complete mempool-aware read:
        // present means rejected/evicted and safe again; absent means accepted/spent and gone.
        val resolvedUncertain = usedInputs.collect {
          case (id, reservation) if reservation.status == ReservationUncertain => id
        }.toSet
        usedInputs = usedInputs -- resolvedUncertain
        // A send already in progress is not resolved by a snapshot: it may have been captured just
        // before the node received the transaction. Keep its lease record so the known-accepted
        // response can still commit, and so nobody can reselect its parents in the meantime.
        val invalidReservations = usedInputs.collect {
          case (id, reservation)
            if reservation.status == ReservationSelected && !reported.contains(id) => reservation.id
        }.toSet
        if (invalidReservations.nonEmpty) {
          val settled = usedInputs.count { case (_, reservation) =>
            invalidReservations.contains(reservation.id)
          }
          logger.info(s"$settled reserved box(es) are no longer a complete unspent lease - collecting them")
          usedInputs = usedInputs.filterNot { case (_, reservation) =>
            invalidReservations.contains(reservation.id)
          }
        }
        walletBoxes = fresh
        rewardBoxes = freshRewards
      } else {
        // A run that stopped part way cannot tell "spent" from "not fetched", and guessing wrong
        // there hands out a box sitting in an unconfirmed transaction. Collect nothing, and keep
        // reserved boxes of either kind alive as before.
        walletBoxes = walletBoxes.filter { case (id, _) => usedInputs.contains(id) } ++ fresh
        rewardBoxes = rewardBoxes.filter { case (id, _) => usedInputs.contains(id) } ++ freshRewards
      }
      chainHeight = height
      trimInventory()
      val locked = rewardBoxes.size - spendableRewards.size
      logger.info(s"Wallet refreshed - ${available.size} available, ${usedInputs.size} reserved, " +
        s"${spendableRewards.size} reward box(es) spendable " +
        s"($locked still locked)" + (if (complete) "" else ", PARTIAL"))
      finishRefresh()

    case RetrieveInputs(_, _, _, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case RetrieveInputs(erg, tokens, trackUsed, reservationId, deadline) =>
      selectWallet(erg, tokens, trackUsed, reservationId, deadline, single = false, p2pkOnly = false)
    case RetrieveCoveringInput(erg, trackUsed, reservationId, deadline) =>
      selectWallet(erg, Seq.empty, trackUsed, reservationId, deadline, single = true, p2pkOnly = false)
    case RetrieveCoveringP2PKInput(erg, trackUsed, reservationId, deadline) =>
      selectWallet(erg, Seq.empty, trackUsed, reservationId, deadline, single = true, p2pkOnly = true)
    case ReserveKnownInputs(_, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case ReserveKnownInputs(inputs, reservationId, _) =>
      val ids = inputs.map(_.id.toString)
      val accepted = inputs.nonEmpty && inputs.size <= MAX_TX_INPUTS &&
        ids.distinct.size == ids.size &&
        inputs.forall(box => box.bytes.length <= WalletInventory.MaxInputBytes && wallet.signableTrees.contains(box.contract.ergoTreeHex)) &&
        ids.forall(id => !usedInputs.contains(id)) &&
        !usedInputs.valuesIterator.exists(_.id == reservationId) &&
        usedInputs.size + ids.size <=
          (if (criticalMessage) MAX_ENGINE_INPUTS else MAX_OPTIONAL_INPUTS)
      if (accepted) reserve(ids, reservationId, ReservationKnown)
      sender() ! WalletInputs(if (accepted) inputs else Seq.empty, reservationId)

    case ReturnInputs(inputs) =>
      val signable = inputs.filter(b => wallet.signableTrees.contains(b.contract.ergoTreeHex))
      remember(signable)
      walletBoxes ++= signable.map(b => b.id.toString -> WalletInventory.descriptor(WalletInventory.nodeBox(b), reward = false))
      trimInventory()
      walletRevision += 1L

    case ReleaseInputs(reservationId) =>
      usedInputs = usedInputs.filterNot { case (_, reservation) =>
        reservation.id == reservationId &&
          (reservation.status == ReservationSelected || reservation.status == ReservationKnown)
      }

    case HoldReservationForCandidate(reservationId) =>
      val replyTo = sender()
      val matching = usedInputs.collect {
        case (boxId, reservation) if reservation.id == reservationId => boxId -> reservation
      }
      val accepted = matching.nonEmpty &&
        matching.values.forall(_.status == ReservationSelected)
      if (accepted) {
        usedInputs = usedInputs.map { case (boxId, reservation) =>
          if (reservation.id == reservationId)
            boxId -> reservation.copy(status = ReservationCandidate)
          else boxId -> reservation
        }
      }
      replyTo ! ReservationHeldForCandidate(reservationId, accepted)

    case MarkReservationUncertain(reservationId) =>
      usedInputs = usedInputs.map { case (boxId, reservation) =>
        if (reservation.id == reservationId &&
          (reservation.status == ReservationSelected ||
            reservation.status == ReservationCandidate ||
            reservation.status == ReservationKnown))
          boxId -> reservation.copy(status = ReservationUncertain)
        else boxId -> reservation
      }
      walletRevision += 1L
      self ! RefreshBoxes

    // By age, never wholesale. A reservation normally ends at the refresh above or with
    // ReleaseInputs; what reaches this tick is a selection abandoned without either, which is a
    // caller that died between reserving and sending. Clearing the whole set instead handed out
    // boxes that were sitting in unconfirmed transactions, once every fifteen minutes.
    case ResetUsedInputs =>
      val cutoff = now() - ReservationTtlMs
      val stale = usedInputs.collect {
        case (id, reservation)
          if (reservation.status == ReservationSelected || reservation.status == ReservationKnown)
            && reservation.reservedAtMillis < cutoff => id
      }.toSet
      if (stale.nonEmpty) {
        logger.warn(s"Releasing ${stale.size} reservation(s) never spent or released within " +
          s"${ReservationTtlMs / 60000} minutes; ${usedInputs.size - stale.size} still held")
        usedInputs = usedInputs -- stale
      }

    // ─── reward-box sweep ──────────────────────────────────────────────────

    case GetUnlockedRewards =>
      val unlocked = spendableRewards
      val unlockedIds = unlocked.map(_.id.toString).toSet
      val locked = rewardBoxes.values.toSeq.filterNot(b => unlockedIds.contains(b.id.toString))
      val blocksUntilFirstUnlock =
        if (locked.isEmpty) None
        else Some(locked.map(b => math.max(0,
          b.creationHeight + MINER_REWARD_DELAY - chainHeight)).min)
      sender() ! RewardSummary(
        lockedBoxes = locked.size,
        unlockedBoxes = unlocked.size,
        lockedNanoErgs = saturate(valueOf(locked)),
        unlockedNanoErgs = saturate(valueOf(unlocked)),
        blocksUntilFirstUnlock)

    case GetSpendableBalance =>
      sender() ! SpendableBalance(saturate(summary.filter(_._1 == walletRevision)
        .map(_._2.spendable).getOrElse(valueOf(available ++ spendableRewards))))

    case ClaimUnlockedRewards if sweeping => sender() ! RewardClaimFailed("a reward sweep is already running")

    case ClaimUnlockedRewards =>
      val replyTo = sender()
      val rewards = spendableRewards.sortBy(_.value).take(math.min(MAX_TX_INPUTS, MAX_OPTIONAL_INPUTS - usedInputs.size))
      if (rewards.isEmpty) replyTo ! RewardsClaimed(Seq.empty)
      else {
        sweeping = true
        // Reserved on the actor thread BEFORE anything leaves the mailbox, so no other selection
        // can take these boxes while the sweep runs. Each batch carries its own lease identity so
        // one failed batch never blocks committing the ones around it.
        val chunks = rewards.grouped(MAX_TX_INPUTS).toVector
        val leaseIds = chunks.map(_ => UUID.randomUUID().toString)
        chunks.zip(leaseIds).foreach { case (chunk, id) =>
          reserve(chunk.map(_.id.toString), id)
        }
        val done = Promise[RewardsClaimed]()
        Try(Future(runRewardSweep(chunks.iterator.map(hydrateRewards), leaseIds, done))(walletWorker))
          .failed.foreach { ex => leaseIds.foreach(id => self ! ReleaseInputs(id)); done.tryFailure(ex) }
        done.future.onComplete {
          case Success(claimed) => replyTo ! claimed; self ! WalletWorkerResult(walletIncarnation, SweepFinished)
          case Failure(ex) => replyTo ! RewardClaimFailed(ex.getMessage); self ! WalletWorkerResult(walletIncarnation, SweepFinished)
        }(context.dispatcher)
      }

  }

  // ─── reward sweep ─────────────────────────────────────────────────────────

  /**
   * Move every batch's coinboxes to the primary address, one broadcast per batch. Runs on a pool
   * thread; every state change goes back through messages so the actor keeps sole ownership of
   * `usedInputs`. A batch that cannot be built never crossed the send boundary, so its lease is
   * CANCELLED — an acknowledged Submitting lease has no TTL, and ReleaseInputs would not touch it.
   * One whose SEND fails ambiguously stays withheld until a complete refresh reconciles it, exactly
   * like an external caller's uncertain broadcast.
   */
  private def runRewardSweep(chunks: Iterator[Seq[InputUTXO]],
                             leaseIds: Seq[String],
                             done: Promise[RewardsClaimed]): Unit = {
    var claimed = Vector.empty[RewardClaimChunk]
    try {
      chunks.zip(leaseIds.iterator).foreach { case (chunk, id) =>
        require(walletAlive.get(), "wallet engine attempt was superseded")
        val inputIds = chunk.map(_.id.toString).toSet
        val gross = chunk.foldLeft(0L)((n, b) => Math.addExact(n, b.value))
        val obligation = Eip27Adjustment.obligation(chunk, nodeContext.getNetwork)
        val net = Math.subtractExact(Math.subtractExact(gross, obligation), RewardSweepFee)
        if (net < Parameters.MinChangeValue) self ! ReleaseInputs(id)
        else {
          val payoutTokens = chunk.flatMap(_.tokens)
            .filterNot(t => obligation > 0 && t.id.toString == MainnetEip27Constants.TokenId)
            .groupBy(_.id).toSeq.map { case (tokenId, entries) =>
              Token(tokenId, entries.foldLeft(0L)((n, t) => Math.addExact(n, t.amount)))
            }
          val signed = client.execute { ctx =>
            val unsigned = TxBuilder(ctx).setInputs(chunk: _*)
              .setOutputs(UTXO(wallet.contract, net, payoutTokens), UTXO.feeBox(RewardSweepFee))
              .buildTx(0, wallet.p2pk)
            require(walletAlive.get(), "wallet engine attempt was superseded before signing")
            wallet.sign(unsigned)
          }
          val payout = InputUTXO(signed.getOutputsToSpend.get(0))
          val result = new EngineBroadcast(self, nodeApi)
            .sendOwned(signed, Seq(EngineBroadcast.Funding(id, inputIds)), "rewards:" + id, () => walletAlive.get())
          if (result.outcome == "accepted") self ! ReturnInputs(Seq(payout))
          claimed :+= RewardClaimChunk(result.txId, chunk.size, net, result.outcome)
          logger.info(s"Reward sweep ${result.txId}: ${result.outcome}, ${chunk.size} boxes, $net nanoERG")
        }
      }
      done.trySuccess(RewardsClaimed(claimed))
    } catch {
      case scala.util.control.NonFatal(ex) =>
        leaseIds.foreach(id => self ! ReleaseInputs(id))
        done.tryFailure(ex)
    }
  }
  private def saturate(amount: BigInt): Long =
    amount.min(BigInt(Long.MaxValue)).toLong

  private def spendableValue(box: WalletDescriptor): Long = descriptorValue(box)

  // ─── box selection ────────────────────────────────────────────────────────

  /**
   * Entry point. Prefers a reward box, then delegates to the ERG-only or mixed path, applies the
   * change-box check and enforces the input cap.
   */
}

object EngineWalletState {
  private sealed trait ReservationStatus
  private case object ReservationSelected extends ReservationStatus
  /** A locally built signable output whose parent may not be node-visible yet. */
  private case object ReservationKnown extends ReservationStatus
  /**
   * Held by a signed transaction offered into this miner's own block candidate. It never reaches
   * the node, so no send boundary resolves it and no TTL may release it: the input is unavailable
   * until the height it was built for is over and reconciliation says whether the block took it.
   */
  private case object ReservationCandidate extends ReservationStatus
  private case object ReservationUncertain extends ReservationStatus
  private case class ReservationEngine(hold: EngineHold) extends ReservationStatus
  private[transactions] final val MAX_ENGINE_INPUTS = 512
  private[transactions] final val MAX_OPTIONAL_INPUTS = MAX_ENGINE_INPUTS - 2 * WalletInventory.MaxInputs
  private case class ReservationState(id: String,
                                      reservedAtMillis: Long,
                                      status: ReservationStatus)

  /** Maximum number of inputs returned in a single RetrieveInputs response. */
  final val MAX_TX_INPUTS: Int = 75


  /** Page size for the wallet refresh, which pages to exhaustion. */
  final val MAX_WALLET_BOXES: Int = WalletInventory.PageSize

  /** Reward boxes pulled per address. One Lithos block pays one, so this is years of them. */
  final val MAX_REWARD_BOXES: Int = WalletInventory.PageSize

  /** Fee on each reward-sweep broadcast. */
  final val RewardSweepFee: Long = Parameters.MinFee

  /** How long a sweep batch's send-boundary lease stays valid, build through broadcast. */
  final val RewardSweepLeaseMs: Long = 30000L

  /**
   * Batch the unlocked coinbases for sweeping, smallest first so dust consolidates into early
   * batches instead of stranding a tail of sub-fee boxes.
   *
   * Batches are capped at `maxInputs`, so dust beyond that boundary waits for the next claim
   * rather than joining a fuller batch - the input cap is hard. A batch whose gross still cannot
   * cover one fee is cancelled by the sweep and becomes selectable again; nothing here drops it.
   * Pure; unit-tested.
   */
  def planRewardChunks(rewards: Seq[InputUTXO], txFee: Long, maxInputs: Int): Seq[Seq[InputUTXO]] =
    rewards.sortBy(_.value).grouped(math.max(1, maxInputs)).filter(_.nonEmpty).toSeq

  /**
   * How long a reservation survives with no sign of being spent or released. Only reached by a
   * caller that abandoned a selection without saying so, so it is generous: releasing early is a
   * double spend, releasing late costs one delayed pass.
   */
  final val ReservationTtlMs: Long = 15 * 60 * 1000L

  private case class BoxesRefreshed(generation: Long,
                                    snapshotRevision: Long,
                                    boxes: Seq[WalletDescriptor],
                                    rewards: Seq[WalletDescriptor],
                                    height: Int,
                                    complete: Boolean,
                                    indexed: Boolean)
  private case class RefreshFailed(generation: Long, cause: Throwable)
  private case class WalletWorkerResult(incarnation: UUID, result: Any)
  private case class SelectionFinished(attempt: UUID, reservationId: String, deadline: Long,
                                       track: Boolean, reply: ActorRef, result: Try[Vector[InputUTXO]], critical: Boolean)
  private case class TotalsRefreshed(revision: Long, snapshot: WalletInventory.Snapshot)
  private case object SweepFinished
  private case class SelectionRequest(erg: Long, tokens: Seq[Token], track: Boolean, id: String,
                                      deadline: Long, single: Boolean, p2pkOnly: Boolean, reply: ActorRef, critical: Boolean)
}
