package transactions.batching.arb

import akka.actor.{Actor, ActorRef}
import configs.{ArbConfig, BatchingConfig, NodeContext}
import node.model.NodeBox
import org.slf4j.{Logger, LoggerFactory}
import transactions.batching.lithosdex.LDDeployments
import transactions.batching.tracking.{PoolDeployment, PoolTracker}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestArbTxs}
import transactions.candidate.{CandidateBundle, CandidateCapital}
import work.lithos.mutations.UTXO

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * The arb stage: asked by the candidate builder once both DEX batchers have answered, it builds cycles on what
 * they admitted and answers with one bundle per cycle. Started only while `batching.arb` is active, so with arb
 * off none of this runs.
 *
 * Pools come from the pool tracker, which reads each block the candidate builds on as it arrives; a build waits
 * for it at most a quarter of its budget. The stake is the box at the arb address, checked once for the block
 * being built on. Without the pools nothing is offered, and without the stake no cycle; a later refresh at the
 * same height tries again.
 * A build whose inputs all match the last one's answers with that build's cycles, their stake outputs noted
 * again, rather than signing anew. ERG/token Machina orders the ErgoDEX batcher hands over for the block are filled
 * first, with or without a stake; token/token grid orders it hands over run on the stake, before the cycles.
 *
 * @param buildBudgetMs how long one build may run; selection stops at half of it
 * @param machina       where the ErgoDEX batcher hands over the Machina orders a LithosDex pool pays more for, and
 *                      token/token grids
 */
class ArbStage(nodeContext: NodeContext,
               arb: ArbConfig,
               ergoDex: BatchingConfig,
               deniedPools: Set[String],
               tracker: PoolTracker,
               stakes: ArbStake,
               useTrueProp: Boolean,
               buildBudgetMs: Long,
               machina: MachinaHandoff = MachinaHandoff.idle) extends Actor {

  import ArbStage._

  private val logger: Logger = LoggerFactory.getLogger("ArbStage")
  private val worker: ExecutionContext = context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.ArbIo))
  /** Delivers completions only, kept off the worker so a full worker cannot drop one. */
  private val completions: ExecutionContext = context.dispatcher

  /** The last build's pool index, which the next one updates rather than rebuilds. */
  private var index: Option[ArbIndex] = None
  /** The last complete build, which a build with the same inputs answers with. */
  private var last: Option[LastBuild] = None
  private var building = false
  /** Pool and route counts and the route cap last logged, so the index logs only when they change. */
  private var logged: (Int, Int, Boolean) = (-1, -1, false)

  override def preStart(): Unit =
    logger.info(s"ArbStage started: stakeNanoErg=${arb.stakeNanoErg}, minProfitNanoErg=${arb.minProfitNanoErg}, " +
      s"buildBudgetMs=$buildBudgetMs, arb address ${stakes.address}")

  override def receive: Receive = {

    // Starts the tracker's read of the parent before any round asks
    case PrepareBlockTxs(blockHeight, _, parentId) =>
      Try(tracker.follow(blockHeight - 1, parentId)).failed
        .foreach(ex => logger.warn(s"Could not pass block $parentId to pool tracking: ${ex.getMessage}"))

    case RequestArbTxs(blockHeight, limit, _, basis) =>
      val replyTo = sender()
      if (limit <= 0) replyTo ! BlockTxsReady(blockHeight, Seq.empty)
      else if (building) {
        logger.info(s"An arb build is still running; offering no cycles for block $blockHeight")
        replyTo ! BlockTxsReady(blockHeight, Seq.empty)
      } else {
        building = true
        val (previous, previousBuild) = (index, last)
        offMailbox(buildFor(previous, previousBuild, blockHeight, limit, basis))(result =>
          Built(blockHeight, replyTo, result))
      }

    case Built(blockHeight, replyTo, result) =>
      building = false
      val bundles = result match {
        case Success(outcome) =>
          outcome.index.foreach { found =>
            if (index.forall(_.height <= found.height)) index = Some(found)
            if ((found.pools.size, found.routes.size, found.cut) != logged) {
              logged = (found.pools.size, found.routes.size, found.cut)
              logger.info(s"Arb pools at height ${found.height}: ${found.pools.size} pool(s) on " +
                s"${found.routes.size} route(s)")
              if (found.cut) logger.warn(s"Arb found more than ${ArbDiscovery.MaxRoutes} routes; the longest are left out")
            }
          }
          outcome.build.foreach(b => last = Some(b))
          outcome.bundles
        case Failure(ex) =>
          logger.warn(s"Arb build for block $blockHeight failed, offering no cycles: ${ex.getMessage}")
          Seq.empty[CandidateBundle]
      }
      replyTo ! BlockTxsReady(blockHeight, bundles)

    // The tracker follows a new parent through the next PrepareBlockTxs, and a stake output whose block never
    // came is dropped at the next height's stake check
    case CandidateTxsDropped(_, _) => ()
  }

  /** Cycles for `blockHeight`, on the pools as of its parent and the stake found unspent after that parent. */
  private def buildFor(previous: Option[ArbIndex], previousBuild: Option[LastBuild], blockHeight: Int, limit: Int,
                       basisBundles: Seq[CandidateBundle]): Outcome = {
    val started = Deadline.now
    // A quarter of the budget at most, so selection, which stops at half, keeps at least a quarter
    tracker.at(blockHeight - 1, (buildBudgetMs / 4).milliseconds) match {
      case None =>
        logger.debug(s"Pool tracking has not read the parent of block $blockHeight; offering no cycles")
        Outcome(None, None, Seq.empty)
      case Some(tracked) =>
        val nodeApi = nodeContext.getNodeApi
        val found = nodeContext.getClient.execute(ctx => ArbDiscovery.index(ctx, tracked, previous, ergoDex,
          deniedPools, {
            case PoolDeployment.LithosDexNative(c) => LDDeployments.vaultStands(nodeApi, c)
            case PoolDeployment.LithosDexToken(c) => LDDeployments.vaultStands(nodeApi, c)
            case _ => true
          }))
        val basis = ArbBasis.of(basisBundles)
        val orders = machina.at(blockHeight)
        val wallet = nodeContext.getNodeWallet
        val (selectUntil, buildUntil) = (started + (buildBudgetMs / 2).milliseconds, started + buildBudgetMs.milliseconds)
        // A fill's takings need an output of their own, as a cycle's profit does
        val minFill = math.max(ergoDex.minRevenueNanoErg, MinProfitOutput)
        val minProfit = math.max(arb.minProfitNanoErg, MinProfitOutput)
        def built(stake: Option[NodeBox], maxIn: Long, reserve: String => Boolean): Seq[CandidateBundle] =
          nodeContext.getClient.execute(ctx => ArbCycles.build(ctx, wallet, found, basis, stake, orders, minFill,
            blockHeight, limit, maxIn, minProfit, CandidateCapital.collectionContract(wallet, useTrueProp),
            selectUntil, buildUntil, reserve, machina.refuse))
        stakes.at(blockHeight, tracked.headerId) match {
          case Some(stake) =>
            val maxIn = math.min(arb.stakeNanoErg, stake.value - ArbLeg.MinCarryValue)
            val key = BuildKey(blockHeight, tracked.headerId, stake.boxId, basis, limit, maxIn, minProfit,
              orders.map(o => o.boxId -> o.poolNft))
            def reserve(boxId: String): Boolean = stakes.handOn(stake.boxId, boxId, blockHeight)
            previousBuild.filter(b => b.key == key && b.routes.eq(found.routes) && b.reserved.forall(reserve)) match {
              case Some(same) => Outcome(Some(found), Some(same), same.bundles)
              case None =>
                var reserved = Vector.empty[String]
                val bundles = built(Some(stake), maxIn, id => { val held = reserve(id); if (held) reserved :+= id; held })
                if (bundles.nonEmpty)
                  logger.info(s"Arb offers ${bundles.size} bundle(s) for block $blockHeight, " +
                    s"${bundles.map(_.capital.map(_.value).sum).sum} nanoERG of takings and profit")
                // A build a deadline cut short is not reused: a later one with time to spare may take more cycles
                val complete = !selectUntil.isOverdue()
                Outcome(Some(found), if (complete) Some(LastBuild(key, found.routes, reserved, bundles)) else None,
                  bundles)
            }
          case None if orders.nonEmpty =>
            logger.debug(s"No arb stake to build on for block $blockHeight; offering Machina fills only")
            Outcome(Some(found), None, built(None, 0L, _ => false))
          case None =>
            logger.debug(s"No arb stake to build on for block $blockHeight; offering no cycles")
            Outcome(Some(found), None, Seq.empty)
        }
    }
  }

  /** Runs `work` on the worker and delivers its result as `wrap`, including a refused submission. */
  private def offMailbox[T](work: => T)(wrap: Try[T] => Any): Unit =
    Try(Future(work)(worker).onComplete(result => self ! wrap(result))(completions))
      .failed.foreach(ex => self ! wrap(Failure(ex)))
}

object ArbStage {

  /** The least profit a cycle can take, since its profit needs an output of its own. */
  final val MinProfitOutput: Long = UTXO.MIN_CHANGE

  /**
   * Everything a build's fills and cycles follow from besides the pool states, which `headerId` and the basis fix:
   * `machina` is each handed-over order's box id and pool.
   */
  private[arb] final case class BuildKey(blockHeight: Int, headerId: String, stakeId: String, basis: ArbBasis,
                                         limit: Int, maxIn: Long, minProfit: Long,
                                         machina: Vector[(String, String)] = Vector.empty)

  /**
   * A complete build: its key, the routes it priced, the stake outputs it had the wallet hold, in order, and the
   * cycles it offered.
   */
  private[arb] final case class LastBuild(key: BuildKey, routes: Vector[ArbRoute], reserved: Vector[String],
                                          bundles: Seq[CandidateBundle])

  /** A build's result: the index it read, the build to reuse next time, and the cycles it offers. */
  private[arb] final case class Outcome(index: Option[ArbIndex], build: Option[LastBuild], bundles: Seq[CandidateBundle])

  private[arb] final case class Built(blockHeight: Int, replyTo: ActorRef, result: Try[Outcome])

  /** A build's budget: a third of a batcher's, which leaves the arb stage's answer inside the source deadline. */
  def buildBudgetMs(batcherBudgetMs: Long): Long = math.max(1L, batcherBudgetMs / 3L)
}
