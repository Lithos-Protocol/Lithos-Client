package transactions.engine.wallet

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
import transactions.engine.wallet.EngineWalletState._
import transactions.engine.wallet.EngineWalletMessages._
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
import transactions.engine.{EngineBroadcast, EngineJoinGuard}

/**
 * Wallet ownership, mixed into the transaction engine actor so one mailbox serialises every change
 * to it. Holds compact descriptors rather than hydrated boxes, and hands hydrated inputs out only
 * for the duration of a build.
 *
 * The invariant everything here protects: an input owned by a nonterminal transaction is never
 * offered to another request. Releasing early is a double spend, so every ambiguous case withholds.
 */
class EngineWalletState @Inject()(nodeContext: NodeContext,
                                  walletLimits: configs.WalletConfig = configs.WalletConfig.Default)
  extends Actor with InjectedActorSupport {

  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("EngineWalletState")

  val client: ErgoClient = nodeContext.getClient
  def nodeApi: NodeApi = nodeContext.getNodeApi

  private val wallet = nodeContext.getNodeWallet

  // The engine passes the operator's `wallet` block. On a wallet of thousands of boxes the page
  // size decides how many node reads one selection costs, so it must not fall back to a default.
  private lazy val inventory = new WalletInventory(nodeContext, nodeApi, walletLimits)
  private lazy val walletWorker = context.system.dispatchers.lookup("lithos-contexts.wallet-io-dispatcher")
  // Inventory scans and reward sweeps cannot occupy the funding selection worker.
  private lazy val maintenanceWorker = context.system.dispatchers.lookup("lithos-contexts.wallet-maintenance-dispatcher")
  private var selecting: Option[UUID] = None
  private var criticalSelecting: Option[UUID] = None
  /** When the in-flight selection began, so a slow one is reported as a measurement. */
  private var selectingSince = 0L
  private var criticalSelectingSince = 0L
  /** Above this a selection is worth a line: it is the queue every other caller waits behind. */
  private final val SlowSelectionMillis = 5000L
  private var criticalMessage = false
  private lazy val criticalWalletWorker = context.system.dispatchers.lookup("lithos-contexts.critical-wallet-dispatcher")
  private var waitingSelections = Vector.empty[SelectionRequest]
  private var knownOutputs = Vector.empty[node.model.NodeBox]
  private var summary: Option[(Long, WalletInventory.Snapshot)] = None
  private var sweeping = false
  private var joinKeys = Map.empty[String, EngineJoinGuard.Ownership]
  private val walletIncarnation = UUID.randomUUID()
  private val walletAlive = new java.util.concurrent.atomic.AtomicBoolean(true)

  /**
   * Record change from a just-built transaction so the next transaction in a chain can spend it
   * before the node reports its parent. Newest wins, and the set is capped like any other cache.
   */
  private def remember(outputs: Seq[InputUTXO]): Unit = {
    val accepted = outputs.take(WalletInventory.MaxInputs)
      .filter(_.bytes.length <= WalletInventory.MaxInputBytes)
      .map(WalletInventory.nodeBox)
    knownOutputs = (accepted ++ knownOutputs).groupBy(_.boxId).valuesIterator.map(_.head)
      .take(WalletInventory.MaxInputs).toVector
  }

  /** Drop descriptors past the count or byte ceiling. Both sets share one budget. */
  private def trimInventory(): Unit = {
    var retainedBytes = 0L
    val kept = (walletBoxes.valuesIterator ++ rewardBoxes.valuesIterator)
      .take(WalletInventory.MaxDescriptors).filter { box =>
        retainedBytes += box.retainedBytes
        retainedBytes <= WalletInventory.MaxDescriptorBytes
      }.map(_.id).toSet
    walletBoxes = walletBoxes.filter { case (id, _) => kept.contains(id) }
    rewardBoxes = rewardBoxes.filter { case (id, _) => kept.contains(id) }
  }

  /**
   * Value this descriptor can contribute, after the nanoERG that spending its re-emission tokens
   * obliges the transaction to pay away. Reporting the gross value would overstate a mainnet reward
   * box by exactly the token amount it carries.
   */
  private def descriptorValue(box: WalletDescriptor): Long = {
    val reemissionDue = if (nodeContext.getNetwork != org.ergoplatform.appkit.NetworkType.MAINNET) 0L
      else box.tokens.filter(_.id.toString == MainnetEip27Constants.TokenId)
        .foldLeft(0L)((sum, token) => Math.addExact(sum, token.amount))
    Math.subtractExact(box.value, reemissionDue)
  }

  private def valueOf(boxes: Seq[WalletDescriptor]): BigInt =
    boxes.map(box => BigInt(descriptorValue(box))).sum

  /**
   * Run one selection off the mailbox, or queue it behind the selection already running in its lane.
   * Critical and optional selections have separate workers and separate input budgets, so a slow
   * optional page walk cannot delay a NISP submission or leave it without funding.
   *
   * The reservation is taken when the result comes back, not here, so the actor never has to decide
   * ownership from a snapshot taken before the walk finished.
   */
  private def selectWallet(erg: Long, tokens: Seq[Token], track: Boolean, reservationId: String,
                           deadline: Long, single: Boolean, p2pkOnly: Boolean,
                           reply: ActorRef = sender(), critical: Boolean = criticalMessage): Unit = {
    val busy = if (critical) criticalSelecting.nonEmpty else selecting.nonEmpty
    val ownershipLimit = if (critical) MAX_ENGINE_INPUTS else MAX_OPTIONAL_INPUTS
    // Worst-case ownership, not the eventual selection size: a request that could not be honoured
    // within the budget is refused before it walks anything.
    val worstCaseInputs = if (single) 1 else WalletInventory.MaxInputs
    if (busy && now() < deadline && waitingSelections.count(_.critical == critical) < MaxQueuedSelections &&
      tokens.size <= MaxRequestedTokens)
      waitingSelections :+= SelectionRequest(erg, tokens, track, reservationId, deadline, single, p2pkOnly, reply, critical)
    else if (busy || now() >= deadline || tokens.size > MaxRequestedTokens ||
      usedInputs.size + worstCaseInputs > ownershipLimit)
      reply ! WalletInputs(Seq.empty, reservationId)
    else {
      val attempt = UUID.randomUUID()
      if (critical) { criticalSelecting = Some(attempt); criticalSelectingSince = now() }
      else { selecting = Some(attempt); selectingSince = now() }
      val excluded = usedInputs.keySet
      val chainedChange = knownOutputs
      Try(Future(client.execute { ctx =>
        inventory.select(ctx, erg, tokens, excluded, single, p2pkOnly, rewardsOnly = false, chainedChange,
          deadlineMillis = deadline)
      })(if (critical) criticalWalletWorker else walletWorker)
        .onComplete(result => self ! SelectionFinished(attempt, reservationId, deadline, track, reply, result, critical)))
        .failed.foreach(ex =>
          self ! SelectionFinished(attempt, reservationId, deadline, track, reply, Failure(ex), critical))
    }
  }

  /** Start whichever queued selections now have a free lane, oldest first. */
  private def drainSelections(): Unit = {
    def nextReady: Int = waitingSelections.indexWhere(request =>
      if (request.critical) criticalSelecting.isEmpty else selecting.isEmpty)
    var ready = nextReady
    while (ready >= 0) {
      val request = waitingSelections(ready)
      waitingSelections = waitingSelections.patch(ready, Nil, 1)
      selectWallet(request.erg, request.tokens, request.track, request.id, request.deadline,
        request.single, request.p2pkOnly, request.reply, request.critical)
      ready = nextReady
    }
  }

  /** Load full boxes for reward descriptors, rechecking identity so a stale descriptor cannot sign. */
  private def hydrateRewards(boxes: Seq[WalletDescriptor]): Seq[InputUTXO] = client.execute { ctx =>
    boxes.map { descriptor =>
      val box = nodeApi.boxById(descriptor.id).get.getOrElse(
        throw new IllegalStateException("reward input is unavailable"))
      val input = box.toInputUTXO(ctx)
      require(input.id.toString == descriptor.id && input.bytes.length <= WalletInventory.MaxInputBytes,
        "reward identity or hydration budget changed")
      input
    }
  }

  // ─── mutable state, all keyed by box id ───────────────────────────────────

  /** Known wallet UTXOs, including change handed back by ReturnInputs. */
  private var walletBoxes: Map[String, WalletDescriptor] = Map.empty

  /** Reserved by SelectInputs and not yet known to be spent, with lease identity and age. */
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
    })(maintenanceWorker).onComplete {
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
    case CriticalWalletRequest(request @ (_: SelectInputs | _: ReserveKnownInputs)) =>
      criticalMessage = true
      try receive(request) finally criticalMessage = false
    case WalletWorkerResult(incarnation, result) if incarnation == walletIncarnation => receive(result)
    case _: WalletWorkerResult => ()
    case TotalsRefreshed(revision, totals) if revision == walletRevision => summary = Some(revision -> totals)
    case _: TotalsRefreshed => ()
    case SweepFinished => sweeping = false
    // The walk ran off the mailbox, so its result is only usable if nothing it assumed has changed:
    // the request is still inside its deadline, no box it chose was reserved meanwhile, and taking
    // them all still fits the lane's ownership budget. Any of those failing yields no inputs rather
    // than a partial selection the caller cannot fund a transaction with.
    case SelectionFinished(attempt, reservationId, deadline, track, reply, result, critical)
      if selecting.contains(attempt) || criticalSelecting.contains(attempt) =>
      val startedAt = if (critical) criticalSelectingSince else selectingSince
      if (critical) criticalSelecting = None else selecting = None
      val elapsed = now() - startedAt
      val ownershipLimit = if (critical) MAX_ENGINE_INPUTS else MAX_OPTIONAL_INPUTS
      val selected = result.toOption.filter { boxes =>
        now() < deadline &&
          boxes.forall(box => !usedInputs.contains(box.id.toString)) &&
          usedInputs.size + boxes.size <= ownershipLimit
      }.getOrElse(Vector.empty)
      result.failed.foreach(ex => logger.warn(s"Wallet selection deferred after ${elapsed}ms: ${ex.getMessage}"))
      // A selection pages the wallet from the node, so on a large wallet this is the cost every
      // other caller queues behind. Naming the page size makes the lever obvious.
      if (elapsed >= SlowSelectionMillis)
        logger.warn(s"Wallet selection took ${elapsed}ms and returned ${selected.size} input(s) " +
          s"(critical=$critical, wallet.page-size=${walletLimits.pageSize}); every other funding " +
          "request waits behind this one")
      if (track && selected.nonEmpty) reserve(selected.map(_.id.toString), reservationId)
      reply ! WalletInputs(selected, reservationId)
      drainSelections()
    case _: SelectionFinished => ()
    // Bind a reservation to the exact signed transaction about to be sent. A fresh pin requires an
    // ordinary reservation; a rebuild requires the previous attempt to have finished its send and to
    // have covered the same operation and inputs, so one attempt can never take over another's boxes.
    case PinEngineInputs(hold, previousTxId) =>
      val reserved = usedInputs.filter { case (_, state) => state.id == hold.reservationId }
      val alreadyPinned = usedInputs.values.count(_.status.isInstanceOf[ReservationEngine])
      val accepted = reserved.nonEmpty && reserved.keySet == hold.walletInputIds &&
        reserved.values.forall(_.status match {
          case ReservationSelected | ReservationKnown => previousTxId.isEmpty
          case ReservationEngine(previous) => previous.sendFinished && previousTxId.contains(previous.txId) &&
            previous.operation == hold.operation && previous.signedInputIds == hold.signedInputIds
          case _ => false
        }) && alreadyPinned + (if (previousTxId.isEmpty) reserved.size else 0) <= MAX_ENGINE_INPUTS
      if (accepted) {
        usedInputs ++= reserved.map { case (boxId, state) => boxId -> state.copy(status = ReservationEngine(hold)) }
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

    case EngineJoinGuard.Keys => sender() ! joinKeys.keySet
    case EngineJoinGuard.Acquire(key, lease) =>
      val allowed = key.matches("[0-9a-f]{64}") && !joinKeys.contains(key) && joinKeys.size < 512
      if (allowed) joinKeys += key -> EngineJoinGuard.Ownership(lease)
      sender() ! allowed
    case EngineJoinGuard.Pin(key, lease, txId, funding) =>
      val allowed = joinKeys.get(key).exists(o => o.lease == lease && o.txId.isEmpty) &&
        funding.nonEmpty && funding.forall(id => usedInputs.valuesIterator.exists(_.id == id))
      if (allowed) joinKeys += key -> EngineJoinGuard.Ownership(lease, Some(txId), funding)
      sender() ! allowed
    case EngineJoinGuard.Cancel(key, lease) =>
      joinKeys.get(key).filter(_.lease == lease).foreach { owned =>
        val pinned = usedInputs.valuesIterator.exists {
          case ReservationState(id, _, ReservationEngine(hold)) => owned.funding.contains(id) && owned.txId.contains(hold.txId)
          case _ => false
        }
        if (!pinned) joinKeys -= key
      }
    case GetOwnedInputIds => sender() ! usedInputs.keySet
    case GetEngineHolds =>
      val held = usedInputs.toVector.collect {
        case (boxId, ReservationState(_, _, ReservationEngine(hold))) => hold -> boxId
      }.groupBy(_._1.reservationId).values.map { entries =>
        entries.head._1.copy(walletInputIds = entries.map(_._2).toSet)
      }.toVector
      sender() ! EngineHolds(held)

    // Retire inputs the chain consumed and free those a reconciler proved survived. Only boxes this
    // exact finished send still owns are touched, so a stale reconciliation cannot resolve a newer
    // attempt's inputs. A lender key is let go only once none of its funding remains owned.
    case ResolveEngineInputs(reservationId, txId, spent, free) =>
      val ownedByThisSend = usedInputs.collect {
        case (boxId, ReservationState(`reservationId`, _, ReservationEngine(hold)))
          if hold.txId == txId && hold.sendFinished => boxId
      }.toSet
      val resolved = (spent ++ free).intersect(ownedByThisSend)
      val consumed = spent.intersect(ownedByThisSend)
      usedInputs --= resolved
      walletBoxes --= consumed
      rewardBoxes --= consumed
      // Chained change goes too. It is offered ahead of anything the node reports, so a spent entry
      // left here is handed to the next build and produces a transaction whose inputs do not exist.
      knownOutputs = knownOutputs.filterNot(box => consumed.contains(box.boxId))
      if (resolved.nonEmpty) {
        walletRevision += 1L
        joinKeys = joinKeys.filterNot { case (_, owned) =>
          owned.txId.contains(txId) && owned.funding.contains(reservationId) &&
            !usedInputs.valuesIterator.exists(state => owned.funding.contains(state.id))
        }
      }
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
      val freshWallet = boxes.map(box => box.id.toString -> box).toMap
      val freshRewards = rewards.map(box => box.id.toString -> box).toMap
      if (complete) {
        // A reserved box neither read still reports as unspent HAS been spent, so the box and its
        // reservation go together. Keeping the reservation was what made a spent box selectable
        // again: it is retained here BECAUSE it is reserved, so it survived every refresh and came
        // back the moment the reservation was cleared.
        //
        // BOTH sets, not just the wallet. Coinbases are tracked separately because no wallet reports
        // them, so comparing against the wallet alone declared every reserved coinbase spent on
        // sight — and `coveringReward` hands one out for most ordinary fees.
        val reportedUnspent = freshWallet.keySet ++ freshRewards.keySet
        // An ambiguous broadcast is resolved in either direction by a complete mempool-aware read:
        // present means rejected/evicted and safe again; absent means accepted/spent and gone.
        val settledUncertain = usedInputs.collect {
          case (boxId, reservation) if reservation.status == ReservationUncertain => boxId
        }.toSet
        usedInputs = usedInputs -- settledUncertain
        // A reserved box no read still reports as unspent HAS been spent, so the whole lease goes.
        // Only plain selections are judged this way: a send already in progress may have been
        // captured just before the node received it, and its lease has to survive so the accepted
        // response can still commit and nobody can reselect its parents meanwhile.
        val spentReservationIds = usedInputs.collect {
          case (boxId, reservation)
            if reservation.status == ReservationSelected && !reportedUnspent.contains(boxId) => reservation.id
        }.toSet
        if (spentReservationIds.nonEmpty) {
          val retiredInputs = usedInputs.count {
            case (_, reservation) => spentReservationIds.contains(reservation.id)
          }
          logger.info(s"$retiredInputs reserved box(es) are no longer a complete unspent lease - collecting them")
          usedInputs = usedInputs.filterNot {
            case (_, reservation) => spentReservationIds.contains(reservation.id)
          }
        }
        // Chained change is kept only while it is still real. The read includes unconfirmed outputs,
        // so a box whose parent is merely unsent-to-chain is still reported; one that is neither
        // reported nor reserved has been spent, and offering it again builds on an input that is
        // gone. Only a complete read can conclude this, which is why it is not done above.
        knownOutputs = knownOutputs.filter(box =>
          reportedUnspent.contains(box.boxId) || usedInputs.contains(box.boxId))
        walletBoxes = freshWallet
        rewardBoxes = freshRewards
      } else {
        // A run that stopped part way cannot tell "spent" from "not fetched", and guessing wrong
        // there hands out a box sitting in an unconfirmed transaction. Collect nothing, and keep
        // reserved boxes of either kind alive as before.
        walletBoxes = walletBoxes.filter { case (boxId, _) => usedInputs.contains(boxId) } ++ freshWallet
        rewardBoxes = rewardBoxes.filter { case (boxId, _) => usedInputs.contains(boxId) } ++ freshRewards
      }
      chainHeight = height
      trimInventory()
      val locked = rewardBoxes.size - spendableRewards.size
      logger.info(s"Wallet refreshed - ${available.size} available, ${usedInputs.size} reserved, " +
        s"${spendableRewards.size} reward box(es) spendable " +
        s"($locked still locked)" + (if (complete) "" else ", PARTIAL"))
      finishRefresh()

    case SelectInputs(_, _, _, reservationId, deadlineMillis, _, _) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case SelectInputs(erg, tokens, trackUsed, reservationId, deadline, single, p2pkOnly) =>
      selectWallet(erg, tokens, trackUsed, reservationId, deadline, single, p2pkOnly)
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
      // Smallest first, so dust consolidates into the early batches instead of stranding a tail of
      // sub-fee boxes, and never more than the remaining optional ownership budget.
      val matured = spendableRewards.sortBy(_.value)
        .take(math.min(MAX_TX_INPUTS, MAX_OPTIONAL_INPUTS - usedInputs.size))
      if (matured.isEmpty) replyTo ! RewardsClaimed(Seq.empty)
      else {
        sweeping = true
        // Reserved on the actor thread BEFORE anything leaves the mailbox, so no other selection
        // can take these boxes while the sweep runs. Each batch carries its own lease identity so
        // one failed batch never blocks committing the ones around it.
        val batches = matured.grouped(MAX_TX_INPUTS).toVector
        val leaseIds = batches.map(_ => UUID.randomUUID().toString)
        batches.zip(leaseIds).foreach { case (batch, leaseId) =>
          reserve(batch.map(_.id.toString), leaseId)
        }
        val sweep = Promise[RewardsClaimed]()
        Try(Future(runRewardSweep(batches.iterator.map(hydrateRewards), leaseIds, sweep))(maintenanceWorker))
          .failed.foreach { ex =>
            leaseIds.foreach(leaseId => self ! ReleaseInputs(leaseId))
            sweep.tryFailure(ex)
          }
        sweep.future.onComplete { result =>
          replyTo ! result.fold(ex => RewardClaimFailed(ex.getMessage), identity)
          self ! WalletWorkerResult(walletIncarnation, SweepFinished)
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
  private def runRewardSweep(batches: Iterator[Seq[InputUTXO]],
                             leaseIds: Seq[String],
                             sweep: Promise[RewardsClaimed]): Unit = {
    var claimed = Vector.empty[RewardClaimChunk]
    try {
      batches.zip(leaseIds.iterator).foreach { case (batch, leaseId) =>
        require(walletAlive.get(), "wallet engine attempt was superseded")
        val inputIds = batch.map(_.id.toString).toSet
        val grossValue = batch.foldLeft(0L)((sum, box) => Math.addExact(sum, box.value))
        // On mainnet the batch's re-emission tokens must be burned and their nanoERG paid to the
        // proxy, so that amount is not available to the payout. TxBuilder appends that output.
        val reemissionObligation = Eip27Adjustment.obligation(batch, nodeContext.getNetwork)
        val payoutValue = Math.subtractExact(
          Math.subtractExact(grossValue, reemissionObligation), RewardSweepFee)
        if (payoutValue < Parameters.MinChangeValue) self ! ReleaseInputs(leaseId)
        else {
          // Everything except the re-emission tokens is forwarded; copying those into the payout
          // would break the burn the same builder is about to enforce.
          val payoutTokens = batch.flatMap(_.tokens)
            .filterNot(token => reemissionObligation > 0 && token.id.toString == MainnetEip27Constants.TokenId)
            .groupBy(_.id).toSeq.map { case (tokenId, tokens) =>
              Token(tokenId, tokens.foldLeft(0L)((sum, token) => Math.addExact(sum, token.amount)))
            }
          val signed = client.execute { ctx =>
            val unsigned = TxBuilder(ctx).setInputs(batch: _*)
              .setOutputs(UTXO(wallet.contract, payoutValue, payoutTokens), UTXO.feeBox(RewardSweepFee))
              .buildTx(0, wallet.p2pk)
            require(walletAlive.get(), "wallet engine attempt was superseded before signing")
            wallet.sign(unsigned)
          }
          val payout = InputUTXO(signed.getOutputsToSpend.get(0))
          val result = new EngineBroadcast(self, nodeApi).sendOwned(signed,
            Seq(EngineBroadcast.Funding(leaseId, inputIds)), "rewards:" + leaseId, () => walletAlive.get())
          // Hand the payout back only on acceptance, so a later batch can chain onto a box the node
          // has actually taken rather than one that may never exist.
          if (result.outcome == EngineBroadcast.Accepted) self ! ReturnInputs(Seq(payout))
          claimed :+= RewardClaimChunk(result.txId, batch.size, payoutValue, result.outcome)
          logger.info(s"Reward sweep ${result.txId}: ${result.outcome}, ${batch.size} boxes, $payoutValue nanoERG")
        }
      }
      sweep.trySuccess(RewardsClaimed(claimed))
    } catch {
      // Only reachable before a send: an ambiguous broadcast is caught inside EngineBroadcast and
      // leaves its lease owned, so releasing every lease here cannot free an in-flight input.
      case scala.util.control.NonFatal(ex) =>
        leaseIds.foreach(leaseId => self ! ReleaseInputs(leaseId))
        sweep.tryFailure(ex)
    }
  }

  /** Clamp a wallet total to Long for reporting; balances are summed as BigInt. */
  private def saturate(amount: BigInt): Long =
    amount.min(BigInt(Long.MaxValue)).toLong
}

object EngineWalletState {

  /**
   * Why an input is unavailable, which decides what may end that unavailability. One lifecycle in
   * three stages, and the distinctions are what keep a spent box from being handed out twice:
   *
   *  - before a send, `ReservationSelected` and `ReservationKnown` differ only in whether absence
   *    from a complete refresh proves the box was spent. It does for a selected box, and does not
   *    for locally built change whose parent the node may not report yet;
   *  - after a send, `ReservationEngine` is bound to an exact transaction and only per-input
   *    evidence from [[EngineReconciler]] can end it;
   *  - `ReservationCandidate` and `ReservationUncertain` cover the paths with no send outcome at
   *    all: a transaction offered into this miner's own block, and an ambiguous broadcast.
   *
   * Only `ReservationSelected` and `ReservationKnown` can be released outright, and only
   * `ReservationSelected` is collected by a complete refresh.
   */
  private sealed trait ReservationStatus
  /** Chosen for a build that has not reached the node. A complete refresh or a release ends it. */
  private case object ReservationSelected extends ReservationStatus
  /** A locally built signable output whose parent may not be node-visible yet. */
  private case object ReservationKnown extends ReservationStatus
  /**
   * Held by a signed transaction offered into this miner's own block candidate. It never reaches
   * the node, so no send boundary resolves it and no TTL may release it: the input is unavailable
   * until the height it was built for is over and reconciliation says whether the block took it.
   */
  private case object ReservationCandidate extends ReservationStatus
  /** Broadcast with an unknown outcome. Only fresh per-input evidence may end it. */
  private case object ReservationUncertain extends ReservationStatus
  /** Bound to an exact signed transaction, which is the only thing that can resolve it. */
  private case class ReservationEngine(hold: EngineHold) extends ReservationStatus
  /** Owned inputs across every transaction the engine has in flight. */
  private[transactions] final val MAX_ENGINE_INPUTS = 512

  /**
   * The ceiling optional work selects against. It stops short of the engine limit by two full
   * transactions' worth of inputs, so a saturated optional queue always leaves a NISP submission
   * or fraud proof room to fund itself.
   */
  private[transactions] final val MAX_OPTIONAL_INPUTS = MAX_ENGINE_INPUTS - 2 * WalletInventory.MaxInputs

  /** Selections that may wait per lane before further requests are refused outright. */
  private final val MaxQueuedSelections = 8

  /** Distinct tokens one request may ask for, bounding the work a single selection can create. */
  private final val MaxRequestedTokens = 512
  /**
   * One reserved input. `id` is the lease shared by every box in the same selection, so a release
   * or a resolution acts on the whole transaction's inputs rather than one box of it.
   */
  private case class ReservationState(id: String,
                                      reservedAtMillis: Long,
                                      status: ReservationStatus)

  /** Maximum number of inputs returned in a single SelectInputs response. */
  final val MAX_TX_INPUTS: Int = 75

  /** Page size for the wallet refresh, which pages to exhaustion. */
  final val MAX_WALLET_BOXES: Int = WalletInventory.PageSize

  /** Fee on each reward-sweep broadcast. */
  final val RewardSweepFee: Long = Parameters.MinFee

  /**
   * Batch the unlocked coinbases for sweeping, smallest first so dust consolidates into early
   * batches instead of stranding a tail of sub-fee boxes.
   *
   * Batches are capped at `maxInputs`, so dust beyond that boundary waits for the next claim
   * rather than joining a fuller batch - the input cap is hard. A batch whose gross still cannot
   * cover one fee is cancelled by the sweep and becomes selectable again; nothing here drops it.
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
