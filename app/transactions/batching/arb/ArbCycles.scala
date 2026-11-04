package transactions.batching.arb

import mutations.NodeWallet
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage}
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO}

import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/**
 * Builds this miner's arb cycles for one block. Each pool is taken at the last successor the DEX batchers'
 * bundles leave, or at its confirmed box when they leave it alone, and never where a batcher spent its box
 * without leaving one. Cycles run best first on one stake: each returns the stake whole to the wallet, which
 * the next cycle spends, and sends its profit to an output the holding top-up folds into the pool.
 *
 * Nothing here is broadcast, and nothing reads the node.
 */
object ArbCycles {

  private val logger: Logger = LoggerFactory.getLogger("ArbCycles")

  /**
   * Where a pool stands for this build: the box holding its state, and the transaction outside the cycle being
   * built that created it, which that cycle names as a package parent. None for a confirmed box.
   */
  private final case class At(box: Either[NodeBox, InputUTXO], createdBy: Option[String])

  /**
   * One bundle per cycle, in the order they spend the stake.
   *
   * @param stake       the arb stake, as the wallet last found it unspent
   * @param maxIn       the most ERG one cycle puts in
   * @param minProfit   the least profit a cycle must leave; at least what an output of its own needs
   * @param profitTo    the contract each cycle's profit output carries
   * @param selectUntil when pricing stops taking new cycles
   * @param buildUntil  when signing stops; a cycle not signed by then is not offered
   * @param reserve     holds a cycle's stake output in the wallet, and says whether it did. A cycle whose output
   *                    is not held is not offered, and no later cycle is built on it
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, index: ArbIndex, basis: ArbBasis, stake: NodeBox,
            blockHeight: Int, slots: Int, maxIn: Long, minProfit: Long, profitTo: Contract,
            selectUntil: Deadline, buildUntil: Deadline, reserve: String => Boolean): Vector[CandidateBundle] = {
    val stakeInput = stake.toInputUTXO(ctx)
    if (basis.spent.contains(stake.boxId) || stakeInput.id.toString != stake.boxId) {
      logger.warn(s"The arb stake ${stake.boxId} is spent by a batcher's bundle or does not read back; no arb " +
        s"for block $blockHeight")
      Vector.empty
    } else {
      val (states, places) = standing(ctx, index, basis)
      var at = places
      var carry = stakeInput
      var carryCreatedBy = Option.empty[String]
      var bundles = Vector.empty[CandidateBundle]
      var stopped = false

      ArbSearch.select(index.routes, states, maxIn, minProfit, slots, selectUntil) { cycle =>
        if (stopped || buildUntil.isOverdue()) { stopped = true; false }
        else signed(ctx, wallet, cycle, at, carry, blockHeight, profitTo) match {
          case Failure(ex) =>
            logger.warn(s"Could not build arb cycle ${cycle.route.key} for block $blockHeight, dropping it whole: " +
              ex.getMessage)
            false
          case Success(legs) =>
            val stakeOut = InputUTXO(legs.last.getOutputsToSpend.get(1))
            if (!reserve(stakeOut.id.toString)) {
              logger.warn(s"The wallet would not hold ${stakeOut.id} as the next arb stake; no further cycles for " +
                s"block $blockHeight")
              stopped = true
              false
            } else {
              val ids = legs.map(CandidateTx.txId)
              val parents = (cycle.route.hops.flatMap(hop => at(hop.nft).createdBy) ++ carryCreatedBy).distinct
              val profit = InputUTXO(legs.last.getOutputsToSpend.get(2))
              bundles :+= CandidateBundle(legs.map(CandidateTx.signed(_, ArbLeg.Kind)),
                parents.filterNot(ids.contains).map(ChainFromPackage),
                Seq(CapitalEntry(CapitalOrigin.Arbitrage, profit, ids.last)))
              cycle.route.hops.zip(legs).zip(ids).foreach { case ((hop, leg), id) =>
                at += hop.nft -> At(Right(InputUTXO(leg.getOutputsToSpend.get(0))), Some(id))
              }
              carry = stakeOut
              carryCreatedBy = Some(ids.last)
              true
            }
        }
      }
      bundles
    }
  }

  /**
   * Every pool's state and box for this build. A box the basis creates that reads as a discovered pool is that
   * pool's latest state; a pool with no such box keeps its confirmed box, unless the basis spent it. A pool two
   * basis boxes claim is left out, since only one can be its state.
   */
  private def standing(ctx: BlockchainContext, index: ArbIndex, basis: ArbBasis)
  : (Map[String, ArbPool], Map[String, At]) = {
    val successors = basis.created.flatMap { c =>
      c.box.assets.headOption.flatMap(a => index.pools.get(a.tokenId))
        .flatMap(entry => entry.read(ctx, c.box).map(state => entry.nft -> (state, At(Left(c.box), Some(c.txId)))))
    }.groupBy(_._1).collect { case (nft, Seq(only)) => nft -> only._2 }
    val confirmed = index.pools.collect {
      case (nft, entry) if !successors.contains(nft) && !basis.spent.contains(entry.box.boxId) &&
        !basis.created.exists(_.box.assets.headOption.exists(_.tokenId == nft)) =>
        nft -> (entry.pool, At(Left(entry.box), None))
    }
    val all = successors ++ confirmed
    all.map { case (nft, (state, _)) => nft -> state } -> all.map { case (nft, (_, place)) => nft -> place }
  }

  /**
   * `cycle`'s legs signed in order, each pool at the box `at` names and the carry passing from leg to leg; the
   * last leg returns the carry's ERG less the profit to the wallet as the next stake, and the profit to
   * `profitTo`. Fails when a pool box does not read back as the state the cycle was priced on.
   */
  private def signed(ctx: BlockchainContext, wallet: NodeWallet, cycle: ArbCycle, at: Map[String, At],
                     stake: InputUTXO, blockHeight: Int, profitTo: Contract): Try[Vector[SignedTransaction]] = Try {
    var carry = stake
    cycle.route.hops.zip(cycle.swaps).zipWithIndex.map { case ((hop, planned), i) =>
      val poolBox = at(hop.nft).box.fold(box => {
        val input = box.toInputUTXO(ctx)
        require(input.id.toString == box.boxId, s"pool box ${box.boxId} reads back as ${input.id}")
        input
      }, identity)
      val swap = planned.pool.withBox(poolBox.id.toString).swap(planned.amountIn, planned.xIn)
        .filter(_.amountOut == planned.amountOut)
        .getOrElse(throw new IllegalStateException(s"pool ${hop.nft} no longer fills leg ${i + 1} as priced"))
      val last = i == cycle.legs - 1
      val leg = ArbLeg.build(ctx, wallet, swap, poolBox, Seq(carry), wallet.contract, blockHeight,
        profit = if (last) Some(profitTo -> cycle.profit) else None)
      carry = InputUTXO(leg.getOutputsToSpend.get(1))
      if (last) require(carry.value == stake.value && carry.tokens.isEmpty,
        s"the last leg returns ${carry.value} nanoERG and ${carry.tokens.size} token(s) where the stake was ${stake.value}")
      leg
    }
  }
}
