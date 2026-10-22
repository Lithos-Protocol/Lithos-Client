package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import node.rest.{NodeHttpConfig, RestNodeApi}
import state.synchronization.CompleteMempool
import transactions.engine.execution.ConsolidationExecution
import transactions.engine.wallet.EngineWalletState
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{Eip27Adjustment, InputUTXO, TxBuilder, UTXO}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

object EngineReconciler {
  /** Cancellations one pass sends, so a backlog of stale sends cannot hold the optional lane long. */
  final val MaxCancelsPerPass = 4
  /** Cancellations tried per transaction before only the hold ceiling can settle it. */
  final val MaxCancelAttempts = 3
  /** Outputs remembered as never going to exist, oldest dropped first. */
  final val MaxDeadOutputs = 65536
  /** Walks one observation may take: ordinary churn races many of them on a busy node. */
  final val ObservationAttempts = 3

  /** What one pass concluded about one held transaction. */
  private sealed trait Verdict
  /**
   * Mined, provably never minable, or past the hold ceiling. Each owned input is spent, free (on
   * chain, unspent and unclaimed) or gone (not on chain at all); one a mempool transaction claims
   * is in none of them and stays owned. `ceiling` marks a settlement made without proof that the
   * transaction is dead, which is worth a warning.
   */
  private final case class Settled(txId: String, holds: Vector[EngineHold], spent: Set[String],
                                   free: Set[String], gone: Set[String], deadOutputs: Set[String],
                                   reason: String, ceiling: Boolean = false) extends Verdict
  /** Out of the mempool too long with nothing spent. `boxes` are what a cancellation may spend. */
  private final case class Stale(txId: String, holds: Vector[EngineHold], boxes: Vector[NodeBox]) extends Verdict
}

/**
 * Resolves outstanding sends for every engine operation, not one kind of transaction: rollup,
 * emission, DEX, consolidation and reward-sweep holds are all reconciled the same way, because what
 * makes an input safe to reuse does not depend on what created it.
 *
 * A send ends when the chain mines it, mines something that conflicts with it, or mines a
 * cancellation this reconciler sends once the send has been out of the mempool for
 * `cancelAfterBlocks`; rollup sends are never cancelled. Held past `holdCeilingMs` and out of the
 * mempool, its inputs' own chain state settles it. Mempool absence alone never frees an input.
 *
 * @param nodeApi  the engine's own client, so reconciliation reads the node under the operator's
 *                 `wallet` limits. Absent, it builds a client with fixed limits.
 * @param settings when to cancel a stale send, and the hold ceiling
 */
class EngineReconciler(nodeContext: NodeContext, wallet: ActorRef, mempool: ActorRef,
                       nodeApi: Option[NodeApi] = None, alive: () => Boolean = () => true,
                       settings: configs.ReconcileConfig = configs.ReconcileConfig.Default)
                      (implicit ec: ExecutionContext) {
  import EngineReconciler._
  private implicit val timeout: Timeout = Timeout(40.seconds)
  private val logger = org.slf4j.LoggerFactory.getLogger("TransactionEngine")
  protected lazy val node: NodeApi = nodeApi.getOrElse(RestNodeApi(NodeHttpConfig(nodeContext.getNodeUrl,
    Some(nodeContext.getNodeKey), maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L)))

  /** Overridable so a test can age a hold without waiting out the ceiling. */
  protected def now(): Long = System.currentTimeMillis()

  // Passes never overlap: the engine starts one only after the previous one has reported back.
  /** When each held transaction was first seen. A cancellation inherits the age of what it cancels. */
  private val firstSeen = mutable.Map.empty[String, Long]
  /** The height each held transaction was first missing from a fresh mempool, until it returns. */
  private val absentSince = mutable.Map.empty[String, Int]
  private val cancelAttempts = mutable.Map.empty[String, Int]
  /** Transactions already warned about, so one stuck send is not reported every pass. */
  private val reported = mutable.Set.empty[(String, String)]
  /** Outputs of transactions proven never minable, in insertion order so the oldest go first. */
  private val deadOutputs = mutable.LinkedHashSet.empty[String]

  private def engineHolds(): Vector[EngineHold] =
    Await.result((wallet ? GetEngineHolds).mapTo[EngineHolds], timeout.duration).holds

  @tailrec private def observation(attempt: Int = 1): CompleteMempool.Snapshot = {
    val observed = Await.result(
      (mempool ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    if (observed.fresh) observed.snapshot.get
    else if (attempt < ObservationAttempts) observation(attempt + 1)
    else throw new IllegalStateException(observed.failure.getOrElse("complete mempool observation expired"))
  }

  /**
   * One pass over every hold whose send has finished, bounded by a single walk budget. A hold that
   * cannot be resolved keeps its inputs and is retried on the next pass.
   */
  def reconcile(): Unit = {
    val byTx = engineHolds().filter(_.sendFinished).groupBy(_.txId)
    firstSeen.keys.filterNot(byTx.contains).toVector.foreach(forget)
    if (byTx.isEmpty) return
    val startedAt = System.nanoTime()
    val clock = now()
    byTx.keys.foreach(txId => firstSeen.getOrElseUpdate(txId, clock))
    // One opening and one closing observation for the whole pass. A walk per hold put the entire
    // mempool on the latency path once per outstanding send, and on a busy node most of them raced.
    val opening = observation()
    val height = node.info().get.fullHeight.getOrElse(throw new IllegalStateException("node has no full height"))
    var proven = Set.empty[String]
    val verdicts = Vector.newBuilder[Verdict]
    parentsFirst(byTx).foreach { case (txId, holds) =>
      if (System.nanoTime() - startedAt < CompleteMempool.MaxWalkNanos) {
        try judge(txId, holds, opening, height, clock, proven).foreach { verdict =>
          verdicts += verdict
          verdict match {
            case settled: Settled => proven ++= settled.deadOutputs
            case _ => ()
          }
        } catch { case NonFatal(ex) => logger.warn(s"Keeping input ownership for $txId: ${ex.getMessage}") }
      }
    }
    val found = verdicts.result()
    settle(found.collect { case settled: Settled => settled }, opening, proven)
    found.collect { case stale: Stale => stale }.take(MaxCancelsPerPass).foreach(cancel(_, height))
  }

  private def forget(txId: String): Unit = {
    firstSeen -= txId; absentSince -= txId; cancelAttempts -= txId; reported.retain(_._1 != txId)
  }

  /** Held transactions ordered so each comes after any held transaction whose output it spends. */
  private def parentsFirst(byTx: Map[String, Vector[EngineHold]]): Vector[(String, Vector[EngineHold])] = {
    val producer = byTx.iterator.flatMap { case (txId, holds) => holds.flatMap(_.outputIds).map(_ -> txId) }.toMap
    val parents = byTx.map { case (txId, holds) =>
      txId -> (holds.flatMap(_.signedInputIds).flatMap(producer.get).toSet - txId)
    }
    @tailrec def order(left: Vector[String], placed: Vector[String]): Vector[String] = {
      val (ready, waiting) = left.partition(txId => parents(txId).forall(placed.contains))
      if (ready.isEmpty || waiting.isEmpty) placed ++ ready ++ waiting
      else order(waiting, placed ++ ready)
    }
    order(byTx.keys.toVector.sortBy(firstSeen), Vector.empty).map(txId => txId -> byTx(txId))
  }

  /**
   * What the chain says about one transaction, from every input it signed and every box its holds
   * own. Mined or dead settles it. Otherwise it waits in the mempool, waits out `cancelAfterBlocks`
   * out of it, is cancelled, or past the hold ceiling is settled from its inputs' own state.
   */
  private def judge(txId: String, holds: Vector[EngineHold], observed: CompleteMempool.Snapshot,
                    height: Int, clock: Long, proven: Set[String]): Option[Verdict] = {
    val ageHours = (clock - firstSeen(txId)) / 3600000L
    val aged = clock - firstSeen(txId) >= settings.holdCeilingMs
    if (observed.ids.contains(txId)) {
      absentSince -= txId
      // Its inputs stay owned: freeing a box the node counts as spent would gain nothing anyway.
      if (aged && reported.add(txId -> "pending"))
        logger.warn(s"$txId (${holds.head.operation}) has been held for $ageHours hours and is still in " +
          "the mempool; its inputs stay owned until it is mined or leaves it")
      return None
    }
    val signed = holds.flatMap(_.signedInputIds).toSet
    val owned = holds.flatMap(_.walletInputIds).toSet
    val spenders = (signed ++ owned).iterator.flatMap(id => confirmedSpender(id).map(id -> _)).toMap
    lazy val onChain: Map[String, NodeBox] = (owned -- spenders.keySet).iterator.flatMap(id =>
      node.boxById(id).get.filter(_.boxId == id).map(id -> _)).toMap
    def settled(reason: String, dead: Set[String]): Settled =
      Settled(txId, holds, owned.intersect(spenders.keySet), onChain.keySet -- observed.spent,
        owned -- spenders.keySet -- onChain.keySet, dead, reason)
    // This transaction and those it cancels: whichever was mined, every other one never can be.
    val candidates = holds.flatMap(_.supersedes).toMap + (txId -> holds.flatMap(_.outputIds).toSet)
    val minedBy = spenders.values.toSet

    if (minedBy.nonEmpty) {
      val reason = if (minedBy.contains(txId)) "mined" else s"an input was spent by ${minedBy.head}"
      Some(settled(reason, (candidates -- minedBy).values.flatten.toSet))
    } else if (signed.exists(id => deadOutputs.contains(id) || proven.contains(id)))
      Some(settled("it spends an output of a transaction that can never be mined",
        holds.flatMap(_.outputIds).toSet))
    else {
      val absentFor = height - absentSince.getOrElseUpdate(txId, height)
      if (absentFor < settings.cancelAfterBlocks) None
      else {
        val cancellable = onChain.values.filter(box => !observed.spent.contains(box.boxId) && signable(box, height))
          .toVector
        // Past the ceiling a cancellation that itself went stale is not cancelled again, which is
        // what bounds a send's whole lineage by the ceiling.
        if (settings.cancelStaleSends && cancellable.nonEmpty && cancelAttempts.getOrElse(txId, 0) < MaxCancelAttempts &&
          !(aged && holds.exists(_.supersedes.nonEmpty)) && !neverCancelled(holds))
          Some(Stale(txId, holds, cancellable))
        else if (aged)
          Some(settled(s"held $ageHours hours, out of the mempool for $absentFor blocks with no input spent",
            Set.empty).copy(ceiling = true))
        else {
          if (reported.add(txId -> "stuck"))
            logger.warn(s"$txId (${holds.head.operation}) has been out of the mempool for $absentFor blocks with " +
              "no input spent and " +
              (if (settings.cancelStaleSends) "cannot be cancelled on chain"
              else "wallet.reconcile.cancel-stale-sends is off") +
              s"; it settles when an input is spent, or after ${settings.holdCeilingMs / 60000L} minutes held")
          None
        }
      }
    }
  }

  /**
   * A NISP submission or fraud proof is never cancelled: a peer may still mine it, and it is worth
   * far more than the fee box it holds. It settles when the rollup moves on, or at the ceiling.
   */
  private def neverCancelled(holds: Vector[EngineHold]): Boolean = holds.exists(_.operation.startsWith("rollup:"))

  /** A box this client can sign for now, so a cancellation spending it cannot fail at signing. */
  private def signable(box: NodeBox, height: Int): Boolean = {
    val keys = nodeContext.getNodeWallet
    keys.signableTrees.contains(box.ergoTree) || (keys.rewardTrees.contains(box.ergoTree) &&
      height.toLong > box.creationHeight.toLong + mutations.NodeWallet.MINER_REWARD_DELAY)
  }

  /**
   * Apply settlements against a closing observation. Nothing settles if a block arrived during the
   * pass, and a settlement waits if its transaction reappeared or a free input was claimed since.
   */
  private def settle(found: Seq[Settled], opening: CompleteMempool.Snapshot, proven: Set[String]): Unit =
    if (found.nonEmpty) {
      val closing = observation()
      if (closing.anchor != opening.anchor)
        logger.info(s"A block arrived during engine reconciliation; ${found.size} settlement(s) wait a pass")
      else {
        proven.foreach(deadOutputs += _)
        while (deadOutputs.size > MaxDeadOutputs) deadOutputs -= deadOutputs.head
        found.foreach { s =>
          if (closing.ids.contains(s.txId) || s.free.exists(closing.spent.contains))
            logger.info(s"${s.txId} or one of its free inputs is in the mempool again; it waits a pass")
          else try {
            s.holds.foreach { hold =>
              val spent = s.spent.intersect(hold.walletInputIds)
              val free = s.free.intersect(hold.walletInputIds)
              val gone = s.gone.intersect(hold.walletInputIds)
              if (spent.nonEmpty || free.nonEmpty || gone.nonEmpty)
                require(Await.result((wallet ? ResolveEngineInputs(hold.reservationId, hold.txId, spent, free, gone))
                  .mapTo[Boolean], timeout.duration), "engine input ownership changed during reconciliation")
            }
            if (s.spent.nonEmpty || s.free.nonEmpty || s.gone.nonEmpty) {
              val line = s"Settled ${s.txId} (${s.holds.head.operation}), ${s.reason}: ${s.spent.size} input(s) " +
                s"retired, ${s.free.size} released, ${s.gone.size} never on chain"
              if (s.ceiling) logger.warn(line) else logger.info(line)
            }
          } catch { case NonFatal(ex) => logger.warn(s"Keeping input ownership for ${s.txId}: ${ex.getMessage}") }
        }
      }
    }

  /**
   * Spend one of a stale transaction's wallet inputs back to this wallet, so the two conflict and the
   * stale one can never be mined. The cancellation takes over every box the stale holds owned until
   * one of the two is mined. One input is enough; all of them only if no single one covers the fee.
   */
  private def cancel(stale: Stale, height: Int): Unit = {
    cancelAttempts(stale.txId) = cancelAttempts.getOrElse(stale.txId, 0) + 1
    try {
      val result = nodeContext.getClient.execute { ctx =>
        val keys = nodeContext.getNodeWallet
        val fee = EngineWalletState.MaintenanceFee
        def outputs(inputs: Seq[InputUTXO]): Seq[UTXO] =
          ConsolidationExecution.outputPlan(inputs, fee, Eip27Adjustment.obligation(inputs, nodeContext.getNetwork))
            .map { case (tokens, value) => UTXO(keys.contract, value, tokens) }
        val converted = stale.boxes.sortBy(box => (box.assets.nonEmpty, box.value)).map { box =>
          val input = box.toInputUTXO(ctx)
          require(input.id.toString == box.boxId, "cancellation input identity changed")
          input
        }
        val (inputs, planned) = converted.iterator.map(input => Try(Seq(input) -> outputs(Seq(input))))
          .collectFirst { case scala.util.Success(plan) => plan }
          .getOrElse(converted -> outputs(converted))
        val unsigned = TxBuilder(ctx).setInputs(inputs: _*)
          .setOutputs((planned :+ UTXO.feeBox(fee)): _*)
          .buildTx(0, keys.p2pk)
        require(alive(), "engine stopped before the cancellation was signed")
        new EngineBroadcast(wallet, node, timeout.duration).cancel(keys.sign(unsigned), stale.holds, alive)
      }
      if (result.outcome != EngineBroadcast.Rejected) firstSeen(result.txId) = firstSeen(stale.txId)
      logger.warn(s"Cancelling ${stale.txId} (${stale.holds.head.operation}): out of the mempool for " +
        s"${height - absentSince.getOrElse(stale.txId, height)} blocks with no input spent, so ${result.txId} " +
        s"spends its wallet input back to this wallet: ${result.outcome}" + result.reason.map(": " + _).getOrElse(""))
    } catch {
      case NonFatal(ex) => logger.warn(s"Could not cancel ${stale.txId}; its inputs stay owned: ${ex.getMessage}")
    }
  }

  /**
   * The transaction the chain consumed this box in: it exists, is at a height whose header is still
   * on the current chain, and the box itself is gone. A reorg fails the header check, so a returned
   * transaction cannot retire an input. An index that cannot answer is no evidence either way.
   */
  private def confirmedSpender(id: String): Option[String] = Try {
    node.indexedBoxById(id).get.flatMap(_.spentTransactionId).filter { txId =>
      node.indexedTransactionById(txId).get.exists { tx =>
        tx.inputs.exists(_.boxId == id) && tx.inclusionHeight > 0 && node.boxById(id).get.isEmpty &&
          node.chainSlice(Some(tx.inclusionHeight - 1), Some(tx.inclusionHeight + 1)).get
            .exists(header => header.id == tx.blockId && header.height == tx.inclusionHeight)
      }
    }
  }.toOption.flatten
}
