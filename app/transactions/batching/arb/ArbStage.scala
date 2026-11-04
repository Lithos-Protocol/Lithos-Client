package transactions.batching.arb

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import api.IndexedPool
import configs.{ArbConfig, BatchingConfig, NodeContext}
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestArbTxs}
import transactions.candidate.{CandidateBundle, CandidateCapital}
import transactions.engine.wallet.EngineWalletMessages.{ArbStake, GetArbStake, ReserveStakeSuccessor}
import work.lithos.mutations.UTXO

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * The arb stage: asked by the candidate builder once both DEX batchers have answered, it builds cycles on what
 * they admitted and answers with one bundle per cycle. Started only while `batching.arb` is active, so with arb
 * off none of this runs.
 *
 * Discovery reads the pools once per indexed block, on a worker. A build waits for nothing on the node: the
 * stake comes from the wallet, which checked it for the height, and pools come from the last discovery, which
 * must have read the block the candidate builds on. Either missing means no arb for that request; a later
 * refresh at the same height tries again.
 *
 * @param ldPools        the LithosDex pools `LDPoolIndex` verified; reading it starts its own rebuild per tip
 * @param buildBudgetMs  how long one build may run; selection stops at half of it
 * @param tickMs         how often discovery is retried while it is behind the height being built
 */
class ArbStage(nodeContext: NodeContext,
               arb: ArbConfig,
               ergoDex: BatchingConfig,
               deniedPools: Set[String],
               ldPools: () => Seq[IndexedPool],
               engine: ActorRef,
               useTrueProp: Boolean,
               buildBudgetMs: Long,
               tickMs: Long = ArbStage.TickMs) extends Actor {

  import ArbStage._

  private val logger: Logger = LoggerFactory.getLogger("ArbStage")
  private val worker: ExecutionContext = context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.ArbIo))
  /** Delivers completions only, kept off the worker so a full worker cannot drop one. */
  private val completions: ExecutionContext = context.dispatcher

  /** The last discovery's pools and routes. */
  private var index: Option[ArbIndex] = None
  /** The newest height a block has been built on, which discovery has to reach. */
  private var wanted: Int = 0
  private var discovering = false
  private var building = false
  private var ticker: Option[Cancellable] = None
  /** Pool and route counts and the route cap last logged, so discovery logs only when they change. */
  private var logged: (Int, Int, Boolean) = (-1, -1, false)
  /** The height the last failed discovery was reaching for, so a failure repeating each tick warns once. */
  private var failedFor: Int = 0
  /** Counts rebuilding drops; a discovery started before the latest one is thrown away. */
  private var generation: Int = 0

  override def preStart(): Unit = {
    logger.info(s"ArbStage started: stakeNanoErg=${arb.stakeNanoErg}, minProfitNanoErg=${arb.minProfitNanoErg}, " +
      s"buildBudgetMs=$buildBudgetMs")
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
      tickMs.milliseconds, tickMs.milliseconds, self, DiscoveryTick)(context.dispatcher))
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = {

    // Starts the wallet's stake check and discovery for the new height before any round asks.
    case PrepareBlockTxs(blockHeight, _) =>
      engine ! GetArbStake(blockHeight)
      wanted = math.max(wanted, blockHeight - 1)
      discoverIfBehind()

    // Answer to the stake check started above; the build asks again for the one it uses.
    case ArbStake(_) => ()

    case DiscoveryTick => discoverIfBehind()

    // Read before a rebuilding drop, so possibly from the block that drop replaced: read again
    case Discovered(startedIn, _) if startedIn != generation =>
      discovering = false
      discoverIfBehind()

    case Discovered(_, result) =>
      discovering = false
      result match {
        case Success(Some(found)) =>
          if (index.forall(_.height <= found.height)) index = Some(found)
          if ((found.pools.size, found.routes.size, found.cut) != logged) {
            logged = (found.pools.size, found.routes.size, found.cut)
            logger.info(s"Arb discovery at height ${found.height}: ${found.pools.size} pool(s) on " +
              s"${found.routes.size} route(s)")
            if (found.cut) logger.warn(s"Arb discovery found more than ${ArbDiscovery.MaxRoutes} routes; the " +
              "longest are left out")
          }
          // A block that arrived while it read is read now, not at the next tick
          discoverIfBehind()
        case Success(None) => ()
        case Failure(ex) =>
          // Retried every tick while behind, so one warning per height
          if (failedFor != wanted) logger.warn(s"Arb discovery failed, offering no cycles until it reads the " +
            s"block at $wanted: ${ex.getMessage}")
          failedFor = wanted
      }

    case RequestArbTxs(blockHeight, limit, _, basis) =>
      val replyTo = sender()
      wanted = math.max(wanted, blockHeight - 1)
      discoverIfBehind()
      index.filter(_.height == blockHeight - 1) match {
        case _ if limit <= 0 => replyTo ! BlockTxsReady(blockHeight, Seq.empty)
        case _ if building =>
          logger.info(s"An arb build is still running; offering no cycles for block $blockHeight")
          replyTo ! BlockTxsReady(blockHeight, Seq.empty)
        case None =>
          logger.debug(s"No arb discovery for block $blockHeight yet; offering no cycles")
          replyTo ! BlockTxsReady(blockHeight, Seq.empty)
        case Some(found) =>
          building = true
          offMailbox(buildFor(found, blockHeight, limit, basis))(result => Built(blockHeight, replyTo, result))
      }

    case Built(blockHeight, replyTo, result) =>
      building = false
      val bundles = result match {
        case Success(found) => found
        case Failure(ex) =>
          logger.warn(s"Arb build for block $blockHeight failed, offering no cycles: ${ex.getMessage}")
          Seq.empty[CandidateBundle]
      }
      replyTo ! BlockTxsReady(blockHeight, bundles)

    // A new parent at the same height, or a package the node refused: the pools read for that height may be
    // spent, so they are read again. A stake output reserved for a block that never came is the wallet's to drop.
    case CandidateTxsDropped(blockHeight, rebuilding) =>
      if (rebuilding && index.forall(_.height == blockHeight - 1)) {
        index = None
        generation += 1
        discoverIfBehind()
      }
  }

  /** Starts discovery when the last one read an older block than the newest one built on. */
  private def discoverIfBehind(): Unit =
    if (!discovering && wanted > 0 && index.forall(_.height < wanted)) {
      discovering = true
      val (atLeast, startedIn) = (wanted, generation)
      offMailbox(nodeContext.getClient.execute(ctx =>
        ArbDiscovery.discover(nodeContext.getNodeApi, ctx, ergoDex, deniedPools, ldPools(), atLeast, logger)))(
        Discovered(startedIn, _))
    }

  /** Cycles for `blockHeight` on `found`, built on the stake the wallet checked for that height. */
  private def buildFor(found: ArbIndex, blockHeight: Int, limit: Int, basisBundles: Seq[CandidateBundle])
  : Seq[CandidateBundle] = {
    val started = Deadline.now
    Try(Await.result((engine ? GetArbStake(blockHeight))(AskTimeout).mapTo[ArbStake], AskTimeout.duration)) match {
      case Success(ArbStake(Some(stake))) =>
        val basis = ArbBasis.of(basisBundles)
        val wallet = nodeContext.getNodeWallet
        val maxIn = math.min(arb.stakeNanoErg, stake.value - ArbLeg.MinCarryValue)
        def reserve(boxId: String): Boolean = Try(Await.result(
          (engine ? ReserveStakeSuccessor(stake.boxId, boxId, blockHeight))(AskTimeout).mapTo[Boolean],
          AskTimeout.duration)).getOrElse(false)
        val bundles = nodeContext.getClient.execute(ctx => ArbCycles.build(ctx, wallet, found, basis, stake,
          blockHeight, limit, maxIn, math.max(arb.minProfitNanoErg, MinProfitOutput),
          CandidateCapital.collectionContract(wallet, useTrueProp), started + (buildBudgetMs / 2).milliseconds,
          started + buildBudgetMs.milliseconds, reserve))
        if (bundles.nonEmpty)
          logger.info(s"Arb offers ${bundles.size} cycle(s) for block $blockHeight, " +
            s"${bundles.map(_.capital.map(_.value).sum).sum} nanoERG of profit")
        bundles
      case Success(ArbStake(None)) =>
        logger.debug(s"No arb stake checked for block $blockHeight yet; offering no cycles")
        Seq.empty
      case Failure(ex) =>
        logger.warn(s"The wallet did not answer for the arb stake for block $blockHeight: ${ex.getMessage}")
        Seq.empty
    }
  }

  /** Runs `work` on the worker and delivers its result as `wrap`, including a refused submission. */
  private def offMailbox[T](work: => T)(wrap: Try[T] => Any): Unit =
    Try(Future(work)(worker).onComplete(result => self ! wrap(result))(completions))
      .failed.foreach(ex => self ! wrap(Failure(ex)))
}

object ArbStage {

  /** How often discovery is retried while behind. A retry costs one indexed-height read. */
  final val TickMs = 2000L

  /** How long a build waits on the wallet for the stake, or to hold a stake output. */
  private[arb] final val AskTimeout: Timeout = Timeout(2.seconds)

  /** The least profit a cycle can take, since its profit needs an output of its own. */
  final val MinProfitOutput: Long = UTXO.MIN_CHANGE

  private[arb] case object DiscoveryTick

  /** @param generation the stage's drop count when the discovery started */
  private[arb] final case class Discovered(generation: Int, result: Try[Option[ArbIndex]])

  private[arb] final case class Built(blockHeight: Int, replyTo: ActorRef, result: Try[Seq[CandidateBundle]])

  /** A build's budget: a third of a batcher's, which leaves the arb stage's answer inside the source deadline. */
  def buildBudgetMs(batcherBudgetMs: Long): Long = math.max(1L, batcherBudgetMs / 3L)
}
