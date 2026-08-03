package transactions

import akka.actor.{Actor, Cancellable}
import akka.pattern.pipe
import configs.NodeConfig
import node.MutationConversions._
import node.NodeApi
import node.model.{ConfirmationRange, Paging}
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.concurrent.InjectedActorSupport
import transactions.WalletManager._
import transactions.WalletMessages._
import utils.Globals
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import javax.inject.Inject
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * Actor that manages a bounded pool of wallet UTXOs for on-chain transaction building.
 *
 * The pool is capped at MAX_WALLET_BOXES (200) entries. On every RefreshBoxes tick
 * (every 10 minutes, and immediately at startup) it fetches fresh unspent wallet
 * boxes from the node, filtering out anything already in usedInputs.
 *
 * When an actor sends RetrieveInputs, WalletManager selects a minimal irredundant
 * set of UTXOs covering the requested ERG and tokens, then optionally records the
 * selection in usedInputs to prevent double-spend errors. usedInputs is cleared
 * every 30 minutes by ResetUsedInputs.
 *
 * === Selection strategy ===
 *
 * All selections are irredundant by construction — every returned UTXO is necessary,
 * satisfying Ergo's UnexpectedSelectedBoxes rule.
 *
 * The primary algorithm is dust-first irredundant selection:
 *   1. Sort available boxes ascending by value.
 *      2. Accumulate from the left (smallest first) until the prefix sum >= required.
 *      Call this prefix [b₁,...,bₖ] with slack = sum(prefix) - required.
 *      3. Trim redundant boxes from the left: find the leftmost j where bⱼ > slack.
 *      Every element before j has value <= slack and would be covered by the
 *      remaining selection, making it redundant.
 *      4. If [bⱼ,...,bₖ] is still sufficient, return it. Irredundancy holds because
 *      every bᵢ >= bⱼ > slack >= new_slack (the new slack of the trimmed set),
 *      so removing any element drops the sum below required.
 *      5. If trimming over-pruned (sum of candidate < required), fall back to the
 *      minimum suffix: scan from the right (largest first) until covered.
 *      This subset is also irredundant by the same argument applied in reverse.
 *
 * By preferring the smallest boxes when possible this algorithm cleans up wallet
 * dust (tiny UTXOs that accumulate over time without contributing significant ERG).
 *
 * ERG + token selection:
 * Token-bearing boxes are collected greedily (highest relevant token volume first,
 * stopping the moment all token needs are met — each selected box is therefore
 * necessary for at least one token requirement). Any remaining ERG shortfall is
 * covered by the dust-first algorithm on the non-token remainder.
 *
 * Change box check:
 * If the selected boxes contain any token in excess of what was requested
 * (including unrequested token IDs, where the required amount is zero) and the
 * ERG excess above requiredErg is less than UTXO.MIN_FEE, those surplus tokens
 * cannot be returned to the wallet without a dedicated change output. One extra
 * ERG-only UTXO (>= UTXO.MIN_FEE) is appended in that case. It stays irredundant
 * because without it the excess is insufficient to form the change output.
 *
 * All selections are capped at MAX_TX_INPUTS (50).
 */
class WalletManager @Inject()() extends Actor with InjectedActorSupport {

  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("WalletManager")

  val nodeConfig: NodeConfig = Globals.getNodeConfig
  val client: ErgoClient = nodeConfig.getClient
  val nodeApi: NodeApi = nodeConfig.getNodeApi

  // ─── mutable state ────────────────────────────────────────────────────────

  /** Current pool of available wallet UTXOs, capped at MAX_WALLET_BOXES. */
  private var walletBoxes: Set[InputUTXO] = Set.empty

  /**
   * UTXOs handed out with excludeInputs = true. Prevents double-spend errors
   * until the next ResetUsedInputs tick.
   */
  private var usedInputs: Set[InputUTXO] = Set.empty

  /**
   * UTXOs marked for exclusion by the ExcludeInputs message. Unlike usedInputs, this
   * set can be replaced every time if needed, though it can also be additive
   */
  private var externalExclusions: Set[InputUTXO] = Set.empty

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

    case RefreshBoxes =>
      val excluded = usedInputs ++ externalExclusions

      client.execute { ctx =>
        nodeApi.walletUnspentBoxes(ConfirmationRange.Default, Paging(0, MAX_WALLET_BOXES)) match {
          case Success(boxes) =>
            val apiBoxes = boxes.map(_.toInputUTXO(ctx)).filterNot(excluded.contains(_))
            self ! BoxesRefreshed(apiBoxes.toSet)
          case Failure(ex) =>
            logger.error(s"Failed to refresh wallet boxes: ${ex.getMessage}", ex)
        }
      }

    case BoxesRefreshed(boxes) =>
      walletBoxes = boxes
      logger.info(s"Wallet boxes refreshed - ${walletBoxes.size} available, ${usedInputs.size} tracked as used," +
        s" and ${externalExclusions.size} have been excluded from collection")

    case RetrieveInputs(erg, tokens, trackUsed) =>
      val replyTo = sender()
      Try(selectBoxes(erg, tokens)) match {
        case Failure(ex) =>
          logger.error(s"Failed to select wallet inputs (needed $erg nanoERG): ${ex.getMessage}", ex)
          replyTo ! WalletInputs(Seq.empty)
        case Success(selected) =>
          if (trackUsed) usedInputs = usedInputs ++ selected
          replyTo ! WalletInputs(selected)
      }

    case ResetUsedInputs =>
      logger.info(s"Resetting usedInputs set (was tracking ${usedInputs.size} UTXOs)")
      usedInputs = Set.empty

    case ExcludeInputs(inputsExcluded, dueToUsage) =>
      if (dueToUsage)
        usedInputs = usedInputs ++ inputsExcluded.toSet
      else
        externalExclusions = inputsExcluded.toSet
  }

  // ─── box selection ────────────────────────────────────────────────────────

  /**
   * Entry point. Delegates to the ERG-only or mixed path, applies the change-box
   * check, then enforces the MAX_TX_INPUTS cap.
   */
  private def selectBoxes(requiredErg: Long, requiredTokens: Seq[Token]): Seq[InputUTXO] = {
    val available = walletBoxes.toSeq

    val initial =
      if (requiredTokens.isEmpty) selectErgBoxes(available, requiredErg)
      else selectMixedBoxes(available, requiredErg, requiredTokens)

    val withChange = applyChangeBoxCheck(initial, available, requiredErg, requiredTokens)

    if (withChange.size > MAX_TX_INPUTS + 1)
      throw new InsufficientWalletFundsException(
        s"Selection requires ${withChange.size} inputs but MAX_TX_INPUTS is $MAX_TX_INPUTS - " +
          s"wallet may be too fragmented"
      )

    withChange
  }

  /**
   * ERG-only selection: sort ascending and apply the dust-first irredundant
   * algorithm.
   */
  private def selectErgBoxes(available: Seq[InputUTXO], requiredErg: Long): Seq[InputUTXO] =
    dustFirstIrredundant(available.sortBy(_.value), requiredErg)


  /**
   * ERG + token selection.
   *
   * Collects token-bearing boxes greedily (sorted by relevant token volume
   * descending) until all token needs are met, then covers any remaining ERG
   * shortfall using the dust-first algorithm on the non-token remainder.
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
   * Dust-first irredundant selection over a pre-sorted ascending sequence.
   *
   * Phase 1 — dust-first:
   * Accumulates boxes from the left (smallest first) to find the minimum
   * sufficient prefix [b₁,...,bₖ]. Trims any boxes from the left whose value
   * is <= slack (= sum − required), producing candidate [bⱼ,...,bₖ]. Returns
   * the candidate if it remains sufficient — it is provably irredundant because
   * every retained element exceeds the slack of the trimmed set.
   *
   * Phase 2 — minimum suffix fallback:
   * If trimming left too many boxes caused the candidate to become insufficient,
   * falls back to scanning from the right (largest first) until covered. The
   * resulting suffix is irredundant by the symmetric argument: removing any
   * element bᵢ drops the sum by at least the minimum element, which was chosen
   * because the remaining suffix sum was still below required.
   */
  private def dustFirstIrredundant(sortedAsc: Seq[InputUTXO], required: Long): Seq[InputUTXO] = {
    val indexed = sortedAsc.toIndexedSeq

    // Phase 1: find minimum sufficient prefix
    var prefixSum = 0L
    var k = -1
    for (i <- indexed.indices if k == -1) {
      prefixSum += indexed(i).value
      if (prefixSum >= required) k = i
    }

    if (k == -1)
      throw new InsufficientWalletFundsException(
        s"Insufficient wallet funds: need $required nanoERG, pool only has $prefixSum nanoERG " +
          s"across ${walletBoxes.size} UTXOs"
      )

    // Phase 1: trim redundant prefix
    val slack = prefixSum - required
    val j = indexed.indices.find(i => indexed(i).value > slack).getOrElse(k)
    val candidate = indexed.slice(j, k + 1)

    if (candidate.map(_.value).sum >= required && candidate.size <= MAX_TX_INPUTS)
      candidate // dust-first irredundant selection
    else
      minimumSuffix(indexed, required) // Phase 2 fallback
  }

  /**
   * Minimum-suffix fallback: scans a pre-sorted ascending sequence from the
   * right (largest first) and collects boxes until the running total >= required.
   * The resulting subset is irredundant: for any retained element bᵢ,
   * sum − bᵢ ≤ sum − bₖ (the smallest retained element), and sum − bₖ equals
   * the suffix without bₖ which was below required by the minimality condition.
   */
  private def minimumSuffix(sortedAsc: IndexedSeq[InputUTXO], required: Long): Seq[InputUTXO] = {
    var acc = 0L
    val result = mutable.Buffer.empty[InputUTXO]
    for (box <- sortedAsc.reverseIterator if acc < required) {
      result += box
      acc += box.value
    }
    // acc is guaranteed >= required here because dustFirstIrredundant verified
    // the total prefix sum >= required before calling this method.
    result.toSeq
  }

  /**
   * Checks whether an extra input is needed to fund a change box for surplus tokens.
   *
   * For each token present in the selected boxes, the total amount across all
   * selected inputs is compared against the requested amount (0 if the token was
   * not requested at all). If any token total exceeds its required amount AND the
   * ERG excess (sum − requiredErg) is less than UTXO.MIN_FEE, we cannot form a
   * valid change output. In that case the smallest available ERG-only UTXO with
   * value >= UTXO.MIN_FEE is appended. ERG-only boxes are preferred so the extra
   * input does not itself carry further surplus tokens. The change-box input is
   * always appended even if it pushes the selection past MAX_TX_INPUTS, since
   * without it the surplus tokens cannot be returned to the wallet at all.
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

    // Need one extra input to fund the change box for surplus tokens
    val selectedIds = selected.map(_.id).toSet
    val remaining = available.filterNot(b => selectedIds.contains(b.id))
    val ergOnlyRemaining = remaining.filter(_.tokens.isEmpty)
    // Prefer ERG-only boxes to avoid introducing further surplus tokens
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

  /** Maximum number of unspent wallet boxes pulled per refresh. */
  final val MAX_WALLET_BOXES: Int = 500

  // ─── internal messages ────────────────────────────────────────────────────

  private case class BoxesRefreshed(boxes: Set[InputUTXO])
}
