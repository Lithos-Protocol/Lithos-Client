package transactions.wallet

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
import transactions.wallet.WalletManager._
import transactions.wallet.WalletMessages._
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

import java.util.UUID
import javax.inject.Inject
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.concurrent.blocking
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * The single wallet-UTXO state owner for the reorganized transaction services and LithosDex.
 *
 * LithosDex, rollup transactions, fraud proofs and the emission queue all select through the
 * standalone [[WalletSelector]] facade backed by this actor. Selecting boxes elsewhere in those
 * paths means two components can pick the same UTXO, and the loser of that race sees a double spend
 * it cannot distinguish from anyone else's. Legacy API paths outside `app/transactions` have not
 * been folded into this ownership boundary yet.
 *
 * On every RefreshBoxes tick (10 minutes, and at startup) it pages the node's unspent wallet boxes to
 * exhaustion and keeps those the prover can sign for. Reservations made by RetrieveInputs are held in
 * usedInputs and subtracted at SELECTION rather than only at refresh — otherwise two selections
 * inside one refresh window can return the same box.
 *
 * A selected reservation can be released after a local abandonment or age out before submission.
 * Crossing the node boundary requires an acknowledged transition to Submitting. Node acceptance
 * atomically retires its parents and publishes real signable outputs; an ambiguous result becomes
 * Uncertain and remains withheld until an exhaustive, mempool-aware refresh reconciles it.
 *
 * It also tracks MINER REWARD boxes, which no wallet reports because they sit under a reward script
 * rather than a plain key. They are preferred when one covers a request outright, since spending them
 * here is the only thing that returns that ERG to an address ordinary wallets can see.
 *
 * Every selection is irredundant: no returned box could be dropped and still cover the request.
 */
class WalletManager @Inject()(nodeContext: NodeContext) extends Actor with InjectedActorSupport {

  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("WalletManager")

  val client: ErgoClient = nodeContext.getClient
  val nodeApi: NodeApi = nodeContext.getNodeApi

  private val wallet = nodeContext.getNodeWallet

  // ─── mutable state, all keyed by box id ───────────────────────────────────

  /** Known wallet UTXOs, including change handed back by ReturnInputs. */
  private var walletBoxes: Map[String, InputUTXO] = Map.empty

  /** Reserved by RetrieveInputs and not yet known to be spent, with lease identity and age. */
  private var usedInputs: Map[String, ReservationState] = Map.empty

  /** Coinbase boxes at this client's addresses, found by ergoTree since no wallet reports them. */
  private var rewardBoxes: Map[String, InputUTXO] = Map.empty

  /** Chain height as of the last refresh, used to tell which reward boxes have unlocked. */
  private var chainHeight: Int = 0

  /** Overridable so a test can age a reservation without waiting out the TTL. */
  protected def now(): Long = System.currentTimeMillis()

  private def reserve(ids: Seq[String],
                      reservationId: String,
                      status: ReservationStatus = ReservationSelected): Unit = {
    val at = now()
    usedInputs ++= ids.map(_ -> ReservationState(reservationId, at, status))
  }

  private def unreserved(boxes: Map[String, InputUTXO]): Seq[InputUTXO] =
    (boxes -- usedInputs.keys).values.toSeq

  /** What a selection may draw from. */
  private def available: Seq[InputUTXO] = unreserved(walletBoxes)

  /** Reward boxes past their timelock. Uses the last refresh's height, so it errs to holding back. */
  private def spendableRewards: Seq[InputUTXO] =
    unreserved(rewardBoxes).filter(b => chainHeight > b.input.getCreationHeight + MINER_REWARD_DELAY)

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
    Future(client.execute { ctx =>
      val indexed = nodeApi.indexerEnabled
      val (boxes, walletComplete) = loadAll(ctx)
      val (rewards, rewardsComplete) = loadRewards(ctx, indexed)
      BoxesRefreshed(generation, snapshotRevision, boxes, rewards, ctx.getHeight,
        walletComplete && rewardsComplete, indexed)
    }).onComplete {
      case Success(msg) => self ! msg
      case Failure(ex) => self ! RefreshFailed(generation, ex)
    }
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
    refreshTicker.foreach(_.cancel())
    resetTicker.foreach(_.cancel())
  }

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

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
      val locked = rewardBoxes.size - spendableRewards.size
      logger.info(s"Wallet refreshed - ${available.size} available, ${usedInputs.size} reserved, " +
        s"${spendableRewards.size} reward box(es) spendable " +
        s"($locked still locked)" + (if (complete) "" else ", PARTIAL"))
      finishRefresh()

    case RetrieveInputs(_, _, _, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case RetrieveInputs(erg, tokens, trackUsed, reservationId, _) =>
      val replyTo = sender()
      Try(selectBoxes(erg, tokens)) match {
        case Failure(ex: InsufficientWalletFundsException) =>
          logger.debug(s"Wallet could not cover $erg nanoERG: ${ex.getMessage}")
          replyTo ! WalletInputs(Seq.empty, reservationId)
        case Failure(ex) =>
          logger.error(s"Failed to select wallet inputs (needed $erg nanoERG): ${ex.getMessage}", ex)
          replyTo ! WalletInputs(Seq.empty, reservationId)
        case Success(selected) =>
          if (trackUsed) reserve(selected.map(_.id.toString), reservationId)
          replyTo ! WalletInputs(selected, reservationId)
      }

    case RetrieveCoveringInput(_, _, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case RetrieveCoveringInput(erg, trackUsed, reservationId, _) =>
      answerCovering(sender(), erg, trackUsed, reservationId, "wallet or reward")(selectLargest)

    case RetrieveCoveringP2PKInput(_, _, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case RetrieveCoveringP2PKInput(erg, trackUsed, reservationId, _) =>
      answerCovering(sender(), erg, trackUsed, reservationId, "P2PK")(selectCoveringP2PK)

    case ReserveKnownInputs(_, reservationId, deadlineMillis) if now() >= deadlineMillis =>
      sender() ! WalletInputs(Seq.empty, reservationId)

    case ReserveKnownInputs(inputs, reservationId, _) =>
      val ids = inputs.map(_.id.toString)
      val knownCount = usedInputs.valuesIterator.count(_.status == ReservationKnown)
      val accepted = inputs.nonEmpty && inputs.size <= MAX_TX_INPUTS &&
        ids.distinct.size == ids.size &&
        inputs.forall(box => wallet.signableTrees.contains(box.contract.ergoTreeHex)) &&
        ids.forall(id => !usedInputs.contains(id)) &&
        !usedInputs.valuesIterator.exists(_.id == reservationId) &&
        knownCount + ids.size <= MAX_KNOWN_INPUTS
      if (accepted) reserve(ids, reservationId, ReservationKnown)
      sender() ! WalletInputs(if (accepted) inputs else Seq.empty, reservationId)

    case ReturnInputs(inputs) =>
      val signable = inputs.filter(b => wallet.signableTrees.contains(b.contract.ergoTreeHex))
      walletBoxes ++= signable.map(b => b.id.toString -> b)
      walletRevision += 1L

    case ReleaseInputs(reservationId) =>
      usedInputs = usedInputs.filterNot { case (_, reservation) =>
        reservation.id == reservationId &&
          (reservation.status == ReservationSelected || reservation.status == ReservationKnown)
      }

    case CommitReservation(reservationId, outputs) =>
      val matching = usedInputs.collect {
        case (boxId, reservation) if reservation.id == reservationId => boxId -> reservation
      }
      val valid = matching.nonEmpty && matching.values.forall { reservation =>
        reservation.status == ReservationSubmitting || reservation.status == ReservationUncertain
      }
      if (valid) {
        val parents = matching.keySet
        val signable = outputs.filter(b => wallet.signableTrees.contains(b.contract.ergoTreeHex))
        walletBoxes = (walletBoxes -- parents) ++ signable.map(b => b.id.toString -> b)
        rewardBoxes = rewardBoxes -- parents
        usedInputs = usedInputs -- parents
        walletRevision += 1L
      } else
        logger.error(s"Ignoring commit for unknown or unsubmitted wallet reservation $reservationId")

    case BeginReservationSubmission(reservationId, expectedInputIds, deadlineMillis) =>
      val replyTo = sender()
      val matching = usedInputs.collect {
        case (boxId, reservation) if reservation.id == reservationId => boxId -> reservation
      }
      val accepted = now() < deadlineMillis && matching.nonEmpty &&
        matching.keySet == expectedInputIds &&
        matching.values.forall(reservation =>
          reservation.status == ReservationSelected || reservation.status == ReservationKnown)
      if (accepted) {
        usedInputs = usedInputs.map { case (boxId, reservation) =>
          if (reservation.id == reservationId)
            boxId -> reservation.copy(status = ReservationSubmitting)
          else boxId -> reservation
        }
      } else if (now() >= deadlineMillis) {
        // A caller that missed its deadline is not allowed to send. Release only this still-selected
        // lease; an id match in any post-send state remains protected.
        usedInputs = usedInputs.filterNot { case (_, reservation) =>
          reservation.id == reservationId &&
            (reservation.status == ReservationSelected || reservation.status == ReservationKnown)
        }
      }
      replyTo ! ReservationSubmissionStarted(reservationId, accepted)

    case CancelReservationSubmission(reservationId, expectedInputIds) =>
      val replyTo = sender()
      val matching = usedInputs.collect {
        case (boxId, reservation) if reservation.id == reservationId => boxId -> reservation
      }
      val accepted = matching.nonEmpty && matching.keySet == expectedInputIds &&
        matching.values.forall(reservation =>
          reservation.status == ReservationSelected ||
            reservation.status == ReservationKnown ||
            reservation.status == ReservationSubmitting)
      if (accepted) usedInputs = usedInputs -- matching.keySet
      replyTo ! ReservationSubmissionCancelled(reservationId, accepted)

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
          (reservation.status == ReservationSubmitting ||
            reservation.status == ReservationSelected ||
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
          b.input.getCreationHeight + MINER_REWARD_DELAY - chainHeight)).min)
      sender() ! RewardSummary(
        lockedBoxes = locked.size,
        unlockedBoxes = unlocked.size,
        lockedNanoErgs = saturate(valueOf(locked)),
        unlockedNanoErgs = saturate(valueOf(unlocked)),
        blocksUntilFirstUnlock)

    case GetSpendableBalance =>
      sender() ! SpendableBalance(saturate(valueOf(available ++ spendableRewards)))

    case ClaimUnlockedRewards =>
      val replyTo = sender()
      val rewards = spendableRewards.sortBy(_.value)
      if (rewards.isEmpty) replyTo ! RewardsClaimed(Seq.empty)
      else {
        // Reserved on the actor thread BEFORE anything leaves the mailbox, so no other selection
        // can take these boxes while the sweep runs. Each batch carries its own lease identity so
        // one failed batch never blocks committing the ones around it.
        val chunks = planRewardChunks(rewards, RewardSweepFee, MAX_TX_INPUTS)
        val leaseIds = chunks.map(_ => UUID.randomUUID().toString)
        chunks.zip(leaseIds).foreach { case (chunk, id) =>
          reserve(chunk.map(_.id.toString), id)
        }
        val done = Promise[RewardsClaimed]()
        Future(runRewardSweep(chunks, leaseIds, done))(context.dispatcher)
        done.future.onComplete {
          case Success(claimed) => replyTo ! claimed
          case Failure(ex) => replyTo ! RewardClaimFailed(ex.getMessage)
        }(context.dispatcher)
      }

  }

  /**
   * Every unspent coinbase paid to one of this client's addresses, and whether every address
   * answered.
   *
   * The completeness flag matters for the same reason `loadAll`'s does: the refresh collects a
   * reservation when nothing reports the box, and a swallowed lookup failure is indistinguishable
   * from "spent". A failed address must not be allowed to look like one.
   *
   * Needs an indexed node; without one they stay invisible and unspendable, which is a slow leak
   * rather than a failure, so it logs once and carries on. Nothing can be reserved in that state
   * either, so an empty answer is a complete one. A per-address lookup is cheap enough at this
   * cadence and avoids a registered scan, which cannot be edited as new keys are derived.
   */
  private def loadRewards(ctx: BlockchainContext, indexed: Boolean): (Seq[InputUTXO], Boolean) =
    if (!indexed) (Seq.empty, true)
    else {
      var complete = true
      val loaded = wallet.rewardTrees.keys.toSeq.flatMap { tree =>
        var treeBoxes = Vector.empty[InputUTXO]
        var paging = Paging(0, MAX_REWARD_BOXES)
        var exhausted = false
        while (!exhausted) {
          nodeApi.unspentBoxesByErgoTree(tree, paging, SortDirection.Asc,
            MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)) match {
            case Success(boxes) =>
              treeBoxes ++= boxes.map(_.toInputUTXO(ctx))
              exhausted = boxes.size < paging.limit
              paging = paging.next
            case Failure(ex) =>
              logger.warn(s"Could not read reward boxes for one address: ${ex.getMessage}")
              complete = false
              exhausted = true
          }
        }
        treeBoxes
      }
      (loaded, complete)
    }

  /**
   * Every unspent wallet box the prover can sign for, paged to exhaustion, and whether it got there.
   *
   * The completeness flag is load bearing: an absent box only means "spent" when the whole wallet
   * was read, and the refresh collects reservations on exactly that inference.
   *
   * The signable filter should never drop anything: `node.numAddresses` is meant to cover every EIP-3
   * index this client derives. If it fires, funds are sitting at addresses that cannot be spent.
   */
  private def loadAll(ctx: BlockchainContext): (Seq[InputUTXO], Boolean) = {
    var loaded = Vector.empty[InputUTXO]
    var paging = Paging(0, MAX_WALLET_BOXES)
    var exhausted = false
    var complete = true
    while (!exhausted) {
      nodeApi.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging) match {
        case Success(page) =>
          loaded ++= page.map(_.toInputUTXO(ctx))
          exhausted = page.size < paging.limit
          paging = paging.next
        case Failure(ex) =>
          logger.error(s"Wallet paging stopped after ${loaded.size} box(es): ${ex.getMessage}")
          exhausted = true
          complete = false
      }
    }
    val signable = loaded.filter(b => wallet.signableTrees.contains(b.contract.ergoTreeHex))
    if (signable.size < loaded.size)
      logger.error(s"${loaded.size - signable.size} wallet box(es) are at addresses this client " +
        "cannot sign for and are unspendable. Raise node.numAddresses to cover every derived key")
    (signable, complete)
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
  private def runRewardSweep(chunks: Seq[Seq[InputUTXO]],
                             leaseIds: Seq[String],
                             done: Promise[RewardsClaimed]): Unit = {
    var claimed = Vector.empty[RewardClaimChunk]
    try {
      chunks.zip(leaseIds).foreach { case (chunk, id) =>
        val inputIds = chunk.map(_.id.toString).toSet
        val net = chunk.map(_.value).sum - RewardSweepFee
        // Permission to cross the send boundary, asked from this thread through the mailbox like
        // any external caller. An unacknowledged lease means it expired or was collected elsewhere.
        val deadline = System.currentTimeMillis() + RewardSweepLeaseMs
        val acked = blocking {
          val ack = Await.result(
            (self ? BeginReservationSubmission(id, inputIds, deadline))(
              Timeout(RewardSweepLeaseMs.milliseconds)),
            RewardSweepLeaseMs.milliseconds)
          ack match {
            case ReservationSubmissionStarted(_, accepted) => accepted
            case _ => false
          }
        }
        if (!acked) {
          logger.warn(s"A reward sweep lease for ${chunk.size} box(es) was not acknowledged - skipping")
        } else if (net <= 0) {
          // Whole-wallet dust below one fee. The planner merges everything it can; what is left here
          // genuinely cannot move yet. Cancelled rather than released: this path is past the
          // acknowledgement, and Cancel is the only transition that may retire a still-Selected
          // lease without treating the outcome as ambiguous.
          logger.warn(s"Skipping ${chunk.size} reward box(es): ${chunk.map(_.value).sum} nanoERG " +
            "does not cover one network fee - they stay available until more accumulate")
          self ! CancelReservationSubmission(id, inputIds)
        } else {
          Try(client.execute { ctx =>
            wallet.sign(TxBuilder(ctx)
              .setInputs(chunk: _*)
              .setOutputs(UTXO(wallet.contract, net), UTXO.feeBox(RewardSweepFee))
              .buildTx(0, wallet.p2pk))
          }) match {
            case Failure(ex) =>
              logger.warn(s"Reward sweep batch could not be signed: ${ex.getMessage}")
              self ! CancelReservationSubmission(id, inputIds)
            case Success(sTx) =>
              Try(client.execute { ctx => ctx.sendTransaction(sTx) }) match {
                case Success(rawTxId) =>
                  val txId = rawTxId.replace("\"", "")
                  // The payout output is first and lands at an ordinary address ordinary wallets see.
                  val payout = InputUTXO(sTx.getOutputsToSpend.get(0))
                  self ! CommitReservation(id, Seq(payout))
                  claimed :+= RewardClaimChunk(txId, chunk.size, net)
                  logger.info(s"Swept ${chunk.size} reward box(es) ($net nanoERG) to the primary " +
                    s"address: $txId")
                case Failure(sendEx) =>
                  logger.warn(s"Reward sweep broadcast failed ambiguously, withholding the batch " +
                    s"until the next refresh reconciles it: ${sendEx.getMessage}")
                  self ! MarkReservationUncertain(id)
              }
          }
        }
      }
      done.success(RewardsClaimed(claimed))
    } catch {
      case ex: Throwable => done.failure(ex)
    }
  }

  private def saturate(amount: BigInt): Long =
    amount.min(BigInt(Long.MaxValue)).toLong

  // ─── box selection ────────────────────────────────────────────────────────

  /**
   * Entry point. Prefers a reward box, then delegates to the ERG-only or mixed path, applies the
   * change-box check and enforces the input cap.
   */
  private def selectBoxes(requiredErg: Long, requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    if (requiredErg < 0)
      throw new IllegalArgumentException(s"Required ERG cannot be negative: $requiredErg")
    if (requiredTokens.exists(_.amount < 0))
      throw new IllegalArgumentException("Required token amounts cannot be negative")

    val tokens = requiredTokens
      .filter(_.amount > 0)
      .groupBy(_.id)
      .map { case (id, entries) =>
        val amount = entries.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
        if (!amount.isValidLong)
          throw new InsufficientWalletFundsException(s"Required amount for token $id exceeds Long.MaxValue")
        Token(id, amount.toLong)
      }
      .toSeq

    if (requiredErg == 0L && tokens.isEmpty) return Seq.empty

    val rewards = spendableRewards
    val pool = available ++ rewards

    val initial = coveringReward(rewards, requiredErg, tokens) match {
      case Some(reward) => Seq(reward)
      case None if tokens.isEmpty => selectErgBoxes(pool, requiredErg)
      case None => selectMixedBoxes(pool, requiredErg, tokens)
    }

    val withChange = applyChangeBoxCheck(initial, pool, requiredErg, tokens)
    val finalSelection = pruneRedundant(withChange, requiredErg, tokens)

    if (finalSelection.size > MAX_TX_INPUTS)
      throw new InsufficientWalletFundsException(
        s"Selection requires ${finalSelection.size} inputs but MAX_TX_INPUTS is $MAX_TX_INPUTS - " +
          s"wallet may be too fragmented"
      )

    if (!validSelection(finalSelection, requiredErg, tokens))
      throw new InsufficientWalletFundsException(
        s"Wallet selection could not cover $requiredErg nanoERG and ${tokens.size} token requirement(s)")

    finalSelection
  }

  /**
   * The smallest unlocked reward box that covers an ERG-only request on its own.
   *
   * Preferred over wallet boxes because nothing else will ever spend these — no wallet reports them,
   * so the client that mined the block is the only thing that can move the ERG, and the change lands
   * at an ordinary address the user can see. Taken alone so the selection stays irredundant, and
   * skipped entirely when tokens are needed, since a coinbase carries none.
   */
  private def coveringReward(rewards: Seq[InputUTXO],
                             requiredErg: Long,
                             requiredTokens: Seq[Token]): Option[InputUTXO] =
    if (requiredTokens.nonEmpty) None
    else rewards.filter(_.value >= requiredErg).sortBy(_.value).headOption

  /**
   * The SMALLEST single box that covers the request on its own.
   *
   * One box, because the fraud-proof path can carry at most two inputs. Smallest rather than largest:
   * this reserves the whole box for the length of the attempt — five tries with five-second sleeps —
   * and a 0.001 ERG rollup fee taking the wallet's largest box out of circulation for that long is
   * contention `SubmissionHandler` and LithosDex then feel for no gain. `coveringReward` on the
   * neighbouring path already chose this way.
   *
   * A token-bearing box has to clear the request by a change output as well, since its tokens need
   * somewhere to go.
   */
  private def selectLargest(requiredErg: Long): Option[InputUTXO] =
    selectCovering(spendableRewards ++ available, requiredErg)

  /**
   * One covering box from the plain P2PK wallet set, never a mining reward.
   */
  private def selectCoveringP2PK(requiredErg: Long): Option[InputUTXO] =
    selectCovering(available, requiredErg)

  private def selectCovering(candidates: Seq[InputUTXO], requiredErg: Long): Option[InputUTXO] = {
    val tokenless = candidates.filter(_.tokens.isEmpty)
    val source = if (tokenless.exists(_.value >= requiredErg)) tokenless else candidates
    source.sortBy(_.value)
      .find { b =>
        if (b.tokens.isEmpty) b.value >= requiredErg
        else BigInt(b.value) >= BigInt(requiredErg) + BigInt(UTXO.MIN_CHANGE)
      }
  }

  /** Both single-box requests answer the same way; only the set they draw from differs. */
  private def answerCovering(replyTo: ActorRef, erg: Long, trackUsed: Boolean,
                             reservationId: String, set: String)
                            (select: Long => Option[InputUTXO]): Unit =
    Try(select(erg)) match {
      case Failure(ex: InsufficientWalletFundsException) =>
        logger.debug(s"Wallet could not provide one $set input covering $erg nanoERG: ${ex.getMessage}")
        replyTo ! WalletInputs(Seq.empty, reservationId)
      case Failure(ex) =>
        logger.error(s"Failed to select wallet inputs (needed $erg nanoERG): ${ex.getMessage}", ex)
        replyTo ! WalletInputs(Seq.empty, reservationId)
      case Success(selected) =>
        if (selected.isDefined) {
          if (trackUsed) reserve(selected.toSeq.map(_.id.toString), reservationId)
          replyTo ! WalletInputs(selected.toSeq, reservationId)
        } else {
          logger.error(s"Failed to find one $set UTXO covering $erg nanoERG")
          replyTo ! WalletInputs(Seq.empty, reservationId)
        }
    }

  /** ERG-only selection prefers tokenless boxes, then falls back to the whole available set. */
  private def selectErgBoxes(available: Seq[InputUTXO], requiredErg: Long): Seq[InputUTXO] = {
    if (requiredErg == 0) return Seq.empty
    val tokenless = available.filter(_.tokens.isEmpty)
    if (valueOf(tokenless) >= BigInt(requiredErg)) {
      val tokenlessSelection = dustFirstIrredundant(tokenless.sortBy(_.value), requiredErg)
      // Tokenless is preferable only while it can produce a legal-size transaction. A fragmented
      // tokenless wallet must not hide one compact token-bearing box that can cover the request.
      if (tokenlessSelection.size <= MAX_TX_INPUTS) tokenlessSelection
      else dustFirstIrredundant(available.sortBy(_.value), requiredErg)
    } else dustFirstIrredundant(available.sortBy(_.value), requiredErg)
  }

  /**
   * ERG + token selection. Token-bearing boxes greedily by relevant volume until the token needs are
   * met, then the dust-first algorithm covers any ERG shortfall from the non-token remainder.
   */
  private def selectMixedBoxes(available: Seq[InputUTXO],
                               requiredErg: Long,
                               requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val tokenIdSet = requiredTokens.map(_.id).toSet
    val needed = mutable.Map(requiredTokens.map(t => t.id -> BigInt(t.amount)): _*)

    val tokenBoxes = mutable.Buffer(available
      .filter(b => b.tokens.exists(t => tokenIdSet.contains(t.id))): _*)

    val selected = mutable.Buffer.empty[InputUTXO]
    var coveredErg = BigInt(0)

    while (needed.values.exists(_ > 0) && tokenBoxes.nonEmpty) {
      // Re-score after every choice. A static ordering keeps valuing surplus of an already-covered
      // token and can select dozens of boxes that contribute only one unit to the remaining token.
      def marginal(box: InputUTXO): (BigInt, Int, Long) = {
        val contributions = box.tokens.flatMap { token =>
          needed.get(token.id).filter(_ > 0).map(need => BigInt(token.amount) min need)
        }
        (contributions.sum, contributions.count(_ > 0), box.value)
      }
      val next = tokenBoxes.maxBy(marginal)
      if (marginal(next)._1 == 0) tokenBoxes.clear()
      else {
        tokenBoxes -= next
        selected += next
        coveredErg += next.value
        next.tokens.filter(t => tokenIdSet.contains(t.id)).foreach { token =>
          needed(token.id) = (needed(token.id) - token.amount) max BigInt(0)
        }
      }
    }

    val missing = needed.collect { case (id, amount) if amount > 0 => s"$id:$amount" }.toSeq
    if (missing.nonEmpty)
      throw new InsufficientWalletFundsException(
        s"Insufficient wallet tokens; missing ${missing.mkString(", ")}")

    if (coveredErg < BigInt(requiredErg)) {
      val selectedIds = selected.map(_.id).toSet
      val remaining = available.filterNot(b => selectedIds.contains(b.id))
      val tokenless = remaining.filter(_.tokens.isEmpty)
      val shortfall = (BigInt(requiredErg) - coveredErg).toLong
      val topUp =
        if (valueOf(tokenless) >= BigInt(shortfall)) {
          val preferred = dustFirstIrredundant(tokenless.sortBy(_.value), shortfall)
          if (selected.size + preferred.size <= MAX_TX_INPUTS) preferred
          else minimumSuffix(remaining.sortBy(_.value).toIndexedSeq, shortfall)
        } else dustFirstIrredundant(remaining.sortBy(_.value), shortfall)
      selected ++= topUp
    }

    selected.toSeq
  }

  /**
   * Smallest-first selection over an ascending sequence, which also clears wallet dust.
   *
   * Takes the shortest sufficient prefix, then drops any leading box worth no more than the slack —
   * those are covered by what remains, so dropping them keeps the set irredundant. If trimming goes
   * too far the fallback scans from the largest end instead, which is irredundant symmetrically.
   */
  private def dustFirstIrredundant(sortedAsc: Seq[InputUTXO], required: Long): Seq[InputUTXO] = {
    val indexed = sortedAsc.toIndexedSeq

    var prefixSum = BigInt(0)
    var k = -1
    for (i <- indexed.indices if k == -1) {
      prefixSum += indexed(i).value
      if (prefixSum >= BigInt(required)) k = i
    }

    if (k == -1)
      throw new InsufficientWalletFundsException(
        s"Insufficient wallet funds: need $required nanoERG, pool only has $prefixSum nanoERG " +
          s"across ${available.size} available UTXOs"
      )

    val slack = prefixSum - BigInt(required)
    val j = indexed.indices.find(i => BigInt(indexed(i).value) > slack).getOrElse(k)
    val candidate = indexed.slice(j, k + 1)

    if (valueOf(candidate) >= BigInt(required) && candidate.size <= MAX_TX_INPUTS) candidate
    else minimumSuffix(indexed, required)
  }

  /**
   * Fallback for when trimming left too little: scan from the largest end until covered. Guaranteed
   * to succeed, since the caller already checked the total.
   */
  private def minimumSuffix(sortedAsc: IndexedSeq[InputUTXO], required: Long): Seq[InputUTXO] = {
    var acc = BigInt(0)
    val result = mutable.Buffer.empty[InputUTXO]
    for (box <- sortedAsc.reverseIterator if acc < BigInt(required)) {
      result += box
      acc += box.value
    }
    result.toSeq
  }

  /**
   * Adds one input when surplus tokens need a change box and the ERG excess cannot fund one.
   *
   * Surplus means any token the selection carries beyond what was asked for, including tokens nobody
   * asked for at all. ERG-only boxes are preferred so the extra input brings no further surplus. The
   * caller applies the transaction input cap after this mandatory funding input is appended.
   */
  private def applyChangeBoxCheck(selected: Seq[InputUTXO],
                                  available: Seq[InputUTXO],
                                  requiredErg: Long,
                                  requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val selectedErg = valueOf(selected)
    val excess = selectedErg - BigInt(requiredErg)

    val totalTokensSelected = selected
      .flatMap(_.tokens)
      .groupBy(_.id)
      .map { case (id, tokens) =>
        id -> tokens.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
      }

    val needsChangeBox = totalTokensSelected.exists { case (id, totalAmount) =>
      val requiredAmount = requiredTokens.find(_.id == id).map(t => BigInt(t.amount)).getOrElse(BigInt(0))
      totalAmount > requiredAmount
    }

    if (!needsChangeBox || excess >= BigInt(UTXO.MIN_CHANGE)) return selected

    val selectedIds = selected.map(_.id).toSet
    val remaining = available.filterNot(b => selectedIds.contains(b.id))
    val deficit = (BigInt(UTXO.MIN_CHANGE) - excess).toLong
    val ergOnlyRemaining = remaining.filter(_.tokens.isEmpty)
    val topUp =
      if (valueOf(ergOnlyRemaining) >= BigInt(deficit)) {
        val preferred = dustFirstIrredundant(ergOnlyRemaining.sortBy(_.value), deficit)
        if (selected.size + preferred.size <= MAX_TX_INPUTS) preferred
        else minimumSuffix(remaining.sortBy(_.value).toIndexedSeq, deficit)
      } else if (valueOf(remaining) >= BigInt(deficit))
        dustFirstIrredundant(remaining.sortBy(_.value), deficit)
      else throw new InsufficientWalletFundsException(
        "Wallet selection carries surplus tokens but cannot fund their change output")
    selected ++ topUp
  }

  private def valueOf(boxes: Seq[InputUTXO]): BigInt =
    boxes.foldLeft(BigInt(0))((sum, box) => sum + box.value)

  private def covers(boxes: Seq[InputUTXO], requiredErg: Long, requiredTokens: Seq[Token]): Boolean = {
    val held = boxes.flatMap(_.tokens).groupBy(_.id).map { case (id, tokens) =>
      id -> tokens.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    valueOf(boxes) >= BigInt(requiredErg) && requiredTokens.forall { token =>
      held.getOrElse(token.id, BigInt(0)) >= BigInt(token.amount)
    }
  }

  /** Coverage plus enough ERG to create a change box whenever any token is surplus. */
  private def validSelection(boxes: Seq[InputUTXO],
                             requiredErg: Long,
                             requiredTokens: Seq[Token]): Boolean = {
    if (!covers(boxes, requiredErg, requiredTokens)) return false
    val required = requiredTokens.groupBy(_.id).map { case (id, tokens) =>
      id -> tokens.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    val held = boxes.flatMap(_.tokens).groupBy(_.id).map { case (id, tokens) =>
      id -> tokens.foldLeft(BigInt(0))((sum, token) => sum + token.amount)
    }
    val hasSurplus = held.exists { case (id, amount) => amount > required.getOrElse(id, BigInt(0)) }
    !hasSurplus || valueOf(boxes) - BigInt(requiredErg) >= BigInt(UTXO.MIN_CHANGE)
  }

  /** Remove every input whose loss still leaves a valid selection. */
  private def pruneRedundant(boxes: Seq[InputUTXO],
                             requiredErg: Long,
                             requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    var result = boxes.toVector
    var changed = true
    while (changed) {
      changed = false
      result.indices.find { idx =>
        validSelection(result.patch(idx, Nil, 1), requiredErg, requiredTokens)
      }.foreach { idx =>
        result = result.patch(idx, Nil, 1)
        changed = true
      }
    }
    result
  }
}

object WalletManager {
  private sealed trait ReservationStatus
  private case object ReservationSelected extends ReservationStatus
  /** A locally built signable output whose parent may not be node-visible yet. */
  private case object ReservationKnown extends ReservationStatus
  private case object ReservationSubmitting extends ReservationStatus
  /**
   * Held by a signed transaction offered into this miner's own block candidate. It never reaches
   * the node, so no send boundary resolves it and no TTL may release it: the input is unavailable
   * until the height it was built for is over and reconciliation says whether the block took it.
   */
  private case object ReservationCandidate extends ReservationStatus
  private case object ReservationUncertain extends ReservationStatus
  private case class ReservationState(id: String,
                                      reservedAtMillis: Long,
                                      status: ReservationStatus)

  /** Maximum number of inputs returned in a single RetrieveInputs response. */
  final val MAX_TX_INPUTS: Int = 75

  /** Hard ceiling for future-output holds, in addition to their normal reservation TTL. */
  final val MAX_KNOWN_INPUTS: Int = 1024

  /** Page size for the wallet refresh, which pages to exhaustion. */
  final val MAX_WALLET_BOXES: Int = 500

  /** Reward boxes pulled per address. One Lithos block pays one, so this is years of them. */
  final val MAX_REWARD_BOXES: Int = 200

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
                                    boxes: Seq[InputUTXO],
                                    rewards: Seq[InputUTXO],
                                    height: Int,
                                    complete: Boolean,
                                    indexed: Boolean)
  private case class RefreshFailed(generation: Long, cause: Throwable)
}
