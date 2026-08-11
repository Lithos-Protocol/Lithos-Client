package transactions

import akka.actor.{Actor, Cancellable}
import akka.pattern.pipe
import configs.NodeContext
import node.MutationConversions._
import node.NodeApi
import mutations.NodeWallet.MINER_REWARD_DELAY
import node.model.{ConfirmationRange, MempoolOptions, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.concurrent.InjectedActorSupport
import transactions.WalletManager._
import transactions.WalletMessages._
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import javax.inject.Inject
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * The single source of wallet UTXOs for every transaction this client builds.
 *
 * Everything that spends wallet funds goes through here — rollup transactions, fraud proofs and the
 * emission queue. Selecting boxes anywhere else means two components can pick the same UTXO, and the
 * loser of that race sees a double spend it cannot distinguish from anyone else's.
 *
 * On every RefreshBoxes tick (10 minutes, and at startup) it pages the node's unspent wallet boxes to
 * exhaustion and keeps those the prover can sign for. Reservations made by RetrieveInputs are held in
 * usedInputs and subtracted at SELECTION rather than only at refresh — otherwise two selections
 * inside one refresh window can return the same box.
 *
 * A reservation ends in one of three ways, and the distinction is what stops a double spend: the box
 * is SPENT, which a complete refresh detects because the node stops reporting it; it is RELEASED by
 * a caller that abandoned the selection; or it goes STALE, meaning neither of those happened within
 * ReservationTtlMs and the caller died in between.
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

  /** Reserved by RetrieveInputs and not yet known to be spent, with when each was taken. */
  private var usedInputs: Map[String, Long] = Map.empty

  /** Excluded by ExcludeInputs. Replaced wholesale rather than accumulated. */
  private var externalExclusions: Set[String] = Set.empty

  /** Coinbase boxes at this client's addresses, found by ergoTree since no wallet reports them. */
  private var rewardBoxes: Map[String, InputUTXO] = Map.empty

  /** Chain height as of the last refresh, used to tell which reward boxes have unlocked. */
  private var chainHeight: Int = 0

  /** Overridable so a test can age a reservation without waiting out the TTL. */
  protected def now(): Long = System.currentTimeMillis()

  private def reserve(ids: Seq[String]): Unit = {
    val at = now()
    usedInputs ++= ids.map(_ -> at)
  }

  private def unreserved(boxes: Map[String, InputUTXO]): Seq[InputUTXO] =
    (boxes -- usedInputs.keys -- externalExclusions).values.toSeq

  /** What a selection may draw from. */
  private def available: Seq[InputUTXO] = unreserved(walletBoxes)

  /** Reward boxes past their timelock. Uses the last refresh's height, so it errs to holding back. */
  private def spendableRewards: Seq[InputUTXO] =
    unreserved(rewardBoxes).filter(b => chainHeight > b.input.getCreationHeight + MINER_REWARD_DELAY)

  private var warnedNoIndexer: Boolean = false

  private var refreshTicker: Option[Cancellable] = None
  private var resetTicker: Option[Cancellable] = None

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
    case RefreshBoxes =>
      Future(client.execute { ctx =>
        val (boxes, walletComplete) = loadAll(ctx)
        val (rewards, rewardsComplete) = loadRewards(ctx)
        // One flag over both reads. The collector below infers "spent" from absence, so it may only
        // run when everything that could report a box actually did.
        BoxesRefreshed(boxes, rewards, ctx.getHeight, walletComplete && rewardsComplete)
      }).onComplete {
        case Success(msg) => self ! msg
        case Failure(ex) => logger.error(s"Failed to refresh wallet boxes: ${ex.getMessage}", ex)
      }

    case BoxesRefreshed(boxes, rewards, height, complete) =>
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
        val settled = usedInputs.keySet.diff(reported)
        if (settled.nonEmpty) {
          logger.info(s"${settled.size} reserved box(es) are no longer unspent - collecting them")
          usedInputs = usedInputs -- settled
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
        s"${externalExclusions.size} excluded, ${spendableRewards.size} reward box(es) spendable " +
        s"($locked still locked)" + (if (complete) "" else ", PARTIAL"))

    case RetrieveInputs(erg, tokens, trackUsed) =>
      val replyTo = sender()
      Try(selectBoxes(erg, tokens)) match {
        case Failure(ex) =>
          logger.error(s"Failed to select wallet inputs (needed $erg nanoERG): ${ex.getMessage}", ex)
          replyTo ! WalletInputs(Seq.empty)
        case Success(selected) =>
          if (trackUsed) reserve(selected.map(_.id.toString))
          replyTo ! WalletInputs(selected)
      }

    case RetrieveCoveringInput(erg, trackUsed) =>
      val replyTo = sender()
      Try(selectLargest(erg)) match {
        case Failure(ex) =>
          logger.error(s"Failed to select wallet inputs (needed $erg nanoERG): ${ex.getMessage}", ex)
          replyTo ! WalletInputs(Seq.empty)
        case Success(selected) =>
          if (selected.isDefined) {
            if (trackUsed) reserve(selected.toSeq.map(_.id.toString))
            replyTo ! WalletInputs(selected.toSeq)
          } else {
            logger.error(s"Failed to find large singular wallet UTXO (needed minimum of $erg nanoERG)")
            replyTo ! WalletInputs(Seq.empty)
          }
      }

    case ReturnInputs(inputs) =>
      val signable = inputs.filter(b => wallet.signableTrees.contains(b.contract.ergoTreeHex))
      walletBoxes ++= signable.map(b => b.id.toString -> b)

    case ReleaseInputs(inputs) =>
      usedInputs --= inputs.map(_.id.toString)

    // By age, never wholesale. A reservation normally ends at the refresh above or with
    // ReleaseInputs; what reaches this tick is a selection abandoned without either, which is a
    // caller that died between reserving and sending. Clearing the whole set instead handed out
    // boxes that were sitting in unconfirmed transactions, once every fifteen minutes.
    case ResetUsedInputs =>
      val cutoff = now() - ReservationTtlMs
      val stale = usedInputs.filter(_._2 < cutoff).keySet
      if (stale.nonEmpty) {
        logger.warn(s"Releasing ${stale.size} reservation(s) never spent or released within " +
          s"${ReservationTtlMs / 60000} minutes; ${usedInputs.size - stale.size} still held")
        usedInputs = usedInputs -- stale
      }

    case ExcludeInputs(inputsExcluded, dueToUsage) =>
      if (dueToUsage)
        reserve(inputsExcluded.map(_.id.toString))
      else
        externalExclusions = inputsExcluded.map(_.id.toString).toSet
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
  private def loadRewards(ctx: BlockchainContext): (Seq[InputUTXO], Boolean) =
    if (!nodeApi.indexerEnabled) {
      if (!warnedNoIndexer) {
        warnedNoIndexer = true
        logger.warn("Node is not indexed (extraIndex = false), so mined coinbases cannot be found. " +
          "They accrue at addresses no wallet reports and stay unspendable until it is enabled")
      }
      (Seq.empty, true)
    } else {
      var complete = true
      val loaded = wallet.rewardTrees.keys.toSeq.flatMap { tree =>
        nodeApi.unspentBoxesByErgoTree(tree, Paging(0, MAX_REWARD_BOXES), SortDirection.Asc,
          MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)) match {
          case Success(boxes) => boxes.map(_.toInputUTXO(ctx))
          case Failure(ex) =>
            logger.warn(s"Could not read reward boxes for one address: ${ex.getMessage}")
            complete = false
            Seq.empty[InputUTXO]
        }
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
      nodeApi.walletUnspentBoxes(ConfirmationRange.Default, paging) match {
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

  // ─── box selection ────────────────────────────────────────────────────────

  /**
   * Entry point. Prefers a reward box, then delegates to the ERG-only or mixed path, applies the
   * change-box check and enforces the input cap.
   */
  private def selectBoxes(requiredErg: Long, requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val rewards = spendableRewards
    val pool = available ++ rewards

    val initial = coveringReward(rewards, requiredErg, requiredTokens) match {
      case Some(reward) => Seq(reward)
      case None if requiredTokens.isEmpty => selectErgBoxes(pool, requiredErg)
      case None => selectMixedBoxes(pool, requiredErg, requiredTokens)
    }

    val withChange = applyChangeBoxCheck(initial, pool, requiredErg, requiredTokens)

    if (withChange.size > MAX_TX_INPUTS + 1)
      throw new InsufficientWalletFundsException(
        s"Selection requires ${withChange.size} inputs but MAX_TX_INPUTS is $MAX_TX_INPUTS - " +
          s"wallet may be too fragmented"
      )

    withChange
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
   * Select largest singular box which covers the required amount of ERG.
   * If requiredERG is equal to the box's value, the box is skipped
   * @param requiredErg Required amount of ERG to cover
   * @return Singular UTXO covering the required amount of ERG
   */
  private def selectLargest(requiredErg: Long): Option[InputUTXO] =
    (spendableRewards ++ available).sortBy(-_.value)
      .find(b => b.value > requiredErg || (b.value == requiredErg && b.tokens.isEmpty))

  /** ERG-only selection: sort ascending and apply the dust-first algorithm. */
  private def selectErgBoxes(available: Seq[InputUTXO], requiredErg: Long): Seq[InputUTXO] =
    dustFirstIrredundant(available.sortBy(_.value), requiredErg)

  /**
   * ERG + token selection. Token-bearing boxes greedily by relevant volume until the token needs are
   * met, then the dust-first algorithm covers any ERG shortfall from the non-token remainder.
   */
  private def selectMixedBoxes(available: Seq[InputUTXO],
                               requiredErg: Long,
                               requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val tokenIdSet = requiredTokens.map(_.id).toSet
    val needed = mutable.Map(requiredTokens.map(t => t.id -> t.amount): _*)

    val tokenBoxes = available
      .filter(b => b.tokens.exists(t => tokenIdSet.contains(t.id)))
      .sortBy(b => -b.tokens.filter(t => tokenIdSet.contains(t.id)).map(_.amount).sum)

    val otherBoxes = available
      .filterNot(b => b.tokens.exists(t => tokenIdSet.contains(t.id)))
      .sortBy(_.value)

    val selected = mutable.Buffer.empty[InputUTXO]
    var coveredErg = 0L

    for (box <- tokenBoxes if needed.values.exists(_ > 0)) {
      selected += box
      coveredErg += box.value
      box.tokens.filter(t => tokenIdSet.contains(t.id)).foreach { t =>
        needed(t.id) = (needed(t.id) - t.amount) max 0L
      }
    }

    if (coveredErg < requiredErg) {
      val selectedIds = selected.map(_.id).toSet
      val remainingBoxes = otherBoxes.filterNot(b => selectedIds.contains(b.id))
      selected ++= dustFirstIrredundant(remainingBoxes, requiredErg - coveredErg)
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

    var prefixSum = 0L
    var k = -1
    for (i <- indexed.indices if k == -1) {
      prefixSum += indexed(i).value
      if (prefixSum >= required) k = i
    }

    if (k == -1)
      throw new InsufficientWalletFundsException(
        s"Insufficient wallet funds: need $required nanoERG, pool only has $prefixSum nanoERG " +
          s"across ${available.size} available UTXOs"
      )

    val slack = prefixSum - required
    val j = indexed.indices.find(i => indexed(i).value > slack).getOrElse(k)
    val candidate = indexed.slice(j, k + 1)

    if (candidate.map(_.value).sum >= required && candidate.size <= MAX_TX_INPUTS) candidate
    else minimumSuffix(indexed, required)
  }

  /**
   * Fallback for when trimming left too little: scan from the largest end until covered. Guaranteed
   * to succeed, since the caller already checked the total.
   */
  private def minimumSuffix(sortedAsc: IndexedSeq[InputUTXO], required: Long): Seq[InputUTXO] = {
    var acc = 0L
    val result = mutable.Buffer.empty[InputUTXO]
    for (box <- sortedAsc.reverseIterator if acc < required) {
      result += box
      acc += box.value
    }
    result.toSeq
  }

  /**
   * Adds one input when surplus tokens need a change box and the ERG excess cannot fund one.
   *
   * Surplus means any token the selection carries beyond what was asked for, including tokens nobody
   * asked for at all. ERG-only boxes are preferred so the extra input brings no further surplus, and
   * it is appended even past the input cap, since otherwise those tokens cannot be returned.
   */
  private def applyChangeBoxCheck(selected: Seq[InputUTXO],
                                  available: Seq[InputUTXO],
                                  requiredErg: Long,
                                  requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val selectedErg = selected.map(_.value).sum
    val excess = selectedErg - requiredErg

    val totalTokensSelected = selected
      .flatMap(_.tokens)
      .groupBy(_.id)
      .map { case (id, tokens) => id -> tokens.map(_.amount).sum }

    val needsChangeBox = totalTokensSelected.exists { case (id, totalAmount) =>
      val requiredAmount = requiredTokens.find(_.id == id).map(_.amount).getOrElse(0L)
      totalAmount > requiredAmount
    }

    if (!needsChangeBox || excess >= UTXO.MIN_FEE) return selected

    val selectedIds = selected.map(_.id).toSet
    val remaining = available.filterNot(b => selectedIds.contains(b.id))
    val ergOnlyRemaining = remaining.filter(_.tokens.isEmpty)
    val candidates = if (ergOnlyRemaining.nonEmpty) ergOnlyRemaining else remaining

    candidates.filter(_.value >= UTXO.MIN_FEE).sortBy(_.value).headOption match {
      case Some(changeBox) =>
        selected :+ changeBox
      case None =>
        logger.warn("No suitable box found to fund change output for surplus tokens")
        selected
    }
  }
}

object WalletManager {
  /** Maximum number of inputs returned in a single RetrieveInputs response. */
  final val MAX_TX_INPUTS: Int = 75

  /** Page size for the wallet refresh, which pages to exhaustion. */
  final val MAX_WALLET_BOXES: Int = 500

  /** Reward boxes pulled per address. One Lithos block pays one, so this is years of them. */
  final val MAX_REWARD_BOXES: Int = 200

  /**
   * How long a reservation survives with no sign of being spent or released. Only reached by a
   * caller that abandoned a selection without saying so, so it is generous: releasing early is a
   * double spend, releasing late costs one delayed pass.
   */
  final val ReservationTtlMs: Long = 15 * 60 * 1000L

  private case class BoxesRefreshed(boxes: Seq[InputUTXO], rewards: Seq[InputUTXO], height: Int,
                                    complete: Boolean)
}
