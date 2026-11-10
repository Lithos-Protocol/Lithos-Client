package transactions.batching.arb

import mutations.NodeWallet
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import transactions.batching.ergodex.MachinaOrder
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage}
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO}

import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/**
 * Builds this miner's arb work for one block. Each pool is taken at the last successor the DEX batchers'
 * bundles leave, or at its confirmed box when they leave it alone, and never where a batcher spent its box
 * without leaving one. Machina orders handed over by the ErgoDEX batcher are filled first, needing no stake, so a
 * cycle through the same pool is priced on what the fills left. Cycles then run best first on one stake: each
 * returns the stake whole to the script it came from, which the next cycle spends, and sends its profit to an
 * output the holding top-up folds into the pool. Every carry between legs sits at that script too, so no wallet
 * box is spent or created.
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
   * One bundle per Machina fill, then one per cycle in the order they spend the stake.
   *
   * @param stake       the arb stake, as last found unspent, at a script this wallet signs for; None builds fills only
   * @param machina     orders to fill, each routed through the pool it names
   * @param minFill     the least ERG a fill must leave; at least what its takings output needs
   * @param maxIn       the most ERG one cycle puts in
   * @param minProfit   the least profit a cycle must leave; at least what an output of its own needs
   * @param profitTo    the contract each fill's takings and each cycle's profit output carries
   * @param selectUntil when pricing stops taking new cycles
   * @param buildUntil  when signing stops; a fill or cycle not signed by then is not offered
   * @param reserve     notes a cycle's stake output as the next stake, and says whether it did. A cycle whose
   *                    output is not noted is not offered, and no later cycle is built on it
   * @param refuse      told each order whose fill priced but could not be signed
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, index: ArbIndex, basis: ArbBasis, stake: Option[NodeBox],
            machina: Seq[MachinaOrder], minFill: Long, blockHeight: Int, slots: Int, maxIn: Long, minProfit: Long,
            profitTo: Contract, selectUntil: Deadline, buildUntil: Deadline,
            reserve: String => Boolean, refuse: String => Unit = _ => ()): Vector[CandidateBundle] = {
    val fillPools = machina.map(_.poolNft).toSet
    val (initial, places) = standing(ctx, index.pools ++ index.parsed.filterKeys(fillPools).toMap, basis)
    var states = initial
    var at = places
    var bundles = Vector.empty[CandidateBundle]

    // Best gap first, each priced again on the pool the fills before it left
    val ranked = machina.groupBy(_.boxId).values.map(_.head).toVector
      .filterNot(order => basis.spent.contains(order.boxId))
      .flatMap(order => states.get(order.poolNft).flatMap(ArbMachina.best(order, _)).map(order -> _.gap))
      .sortBy { case (order, gap) => (-gap, order.boxId) }
    ranked.foreach { case (order, _) =>
      if (bundles.size < slots && !buildUntil.isOverdue()) {
        // A pool box that does not read back is the pool's fault, so only a fill that fails to sign is refused
        val built = Try(input(ctx, at(order.poolNft))).map { poolBox =>
          ArbMachina.best(order, states(order.poolNft).withBox(poolBox.id.toString)).filter(_.gap >= minFill)
            .map(fill => fill -> Try(ArbMachina.build(ctx, wallet, fill, poolBox, profitTo, blockHeight)))
        }
        built match {
          case Failure(ex) =>
            logger.warn(s"Pool ${order.poolNft.take(12)} does not read back for block $blockHeight; Machina order " +
              s"${order.boxId} is not filled through it: ${ex.getMessage}")
          case Success(Some((_, Failure(ex)))) =>
            logger.warn(s"Could not fill Machina order ${order.boxId} through pool ${order.poolNft.take(12)} for " +
              s"block $blockHeight, leaving it out for a while: ${ex.getMessage}")
            refuse(order.boxId)
          case Success(Some((fill, Success(tx)))) =>
            val id = CandidateTx.txId(tx)
            bundles :+= CandidateBundle(Vector(CandidateTx.signed(tx, ArbLeg.Kind)),
              at(order.poolNft).createdBy.toSeq.map(ChainFromPackage),
              Seq(CapitalEntry(CapitalOrigin.ExecutorReward, InputUTXO(tx.getOutputsToSpend.get(2)), id)))
            val successor = InputUTXO(tx.getOutputsToSpend.get(0))
            states += order.poolNft -> fill.swap.after.withBox(successor.id.toString)
            at += order.poolNft -> At(Right(successor), Some(id))
          case Success(None) => ()
        }
      }
    }
    if (bundles.nonEmpty)
      logger.info(s"Arb fills ${bundles.size} Machina order(s) for block $blockHeight through LithosDex, " +
        s"${bundles.flatMap(_.capital.map(_.value)).sum} nanoERG")

    bundles ++ stake.toVector.flatMap(box =>
      cycles(ctx, wallet, index, basis, box, states, at, blockHeight, slots - bundles.size, maxIn, minProfit,
        profitTo, selectUntil, buildUntil, reserve))
  }

  /** Cycles on `stake`, from the pool states and boxes `states` and `places` hold, in the order they spend it. */
  private def cycles(ctx: BlockchainContext, wallet: NodeWallet, index: ArbIndex, basis: ArbBasis, stake: NodeBox,
                     states: Map[String, ArbPool], places: Map[String, At], blockHeight: Int, slots: Int,
                     maxIn: Long, minProfit: Long, profitTo: Contract, selectUntil: Deadline, buildUntil: Deadline,
                     reserve: String => Boolean): Vector[CandidateBundle] = {
    val stakeInput = stake.toInputUTXO(ctx)
    if (basis.spent.contains(stake.boxId) || stakeInput.id.toString != stake.boxId) {
      logger.warn(s"The arb stake ${stake.boxId} is spent by a batcher's bundle or does not read back; no arb " +
        s"for block $blockHeight")
      Vector.empty
    } else {
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
              logger.warn(s"${stakeOut.id} could not be noted as the next arb stake; no further cycles for " +
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
   * The state and box of every pool `pools` names, for this build. A box the basis creates that reads as one of
   * them is that pool's latest state; a pool with no such box keeps its confirmed box, unless the basis spent it.
   * A pool two basis boxes claim is left out, since only one can be its state.
   */
  private def standing(ctx: BlockchainContext, pools: Map[String, ArbPoolEntry], basis: ArbBasis)
  : (Map[String, ArbPool], Map[String, At]) = {
    val successors = basis.created.flatMap { c =>
      c.box.assets.headOption.flatMap(a => pools.get(a.tokenId))
        .flatMap(entry => entry.read(ctx, c.box).map(state => entry.nft -> (state, At(Left(c.box), Some(c.txId)))))
    }.groupBy(_._1).collect { case (nft, Seq(only)) => nft -> only._2 }
    val confirmed = pools.collect {
      case (nft, entry) if !successors.contains(nft) && !basis.spent.contains(entry.box.boxId) &&
        !basis.created.exists(_.box.assets.headOption.exists(_.tokenId == nft)) =>
        nft -> (entry.pool, At(Left(entry.box), None))
    }
    val all = successors ++ confirmed
    all.map { case (nft, (state, _)) => nft -> state } -> all.map { case (nft, (_, place)) => nft -> place }
  }

  /** The box `place` names as an input, a confirmed one only when it reads back as its own id. */
  private def input(ctx: BlockchainContext, place: At): InputUTXO = place.box.fold(box => {
    val input = box.toInputUTXO(ctx)
    require(input.id.toString == box.boxId, s"pool box ${box.boxId} reads back as ${input.id}")
    input
  }, identity)

  /**
   * `cycle`'s legs signed in order, each pool at the box `at` names and the carry passing from leg to leg at
   * the stake's own script; the last leg returns the carry's ERG less the profit to that script as the next
   * stake, and the profit to `profitTo`. Fails when a pool box does not read back as the state the cycle was
   * priced on.
   */
  private def signed(ctx: BlockchainContext, wallet: NodeWallet, cycle: ArbCycle, at: Map[String, At],
                     stake: InputUTXO, blockHeight: Int, profitTo: Contract): Try[Vector[SignedTransaction]] = Try {
    var carry = stake
    cycle.route.hops.zip(cycle.swaps).zipWithIndex.map { case ((hop, planned), i) =>
      val poolBox = input(ctx, at(hop.nft))
      val swap = planned.pool.withBox(poolBox.id.toString).swap(planned.amountIn, planned.xIn)
        .filter(_.amountOut == planned.amountOut)
        .getOrElse(throw new IllegalStateException(s"pool ${hop.nft} no longer fills leg ${i + 1} as priced"))
      val last = i == cycle.legs - 1
      val leg = ArbLeg.build(ctx, wallet, swap, poolBox, Seq(carry), stake.contract, blockHeight,
        profit = if (last) Some(profitTo -> cycle.profit) else None)
      carry = InputUTXO(leg.getOutputsToSpend.get(1))
      if (last) require(carry.value == stake.value && carry.tokens.isEmpty &&
        carry.contract.ergoTreeHex == stake.contract.ergoTreeHex,
        s"the last leg returns ${carry.value} nanoERG and ${carry.tokens.size} token(s) where the stake was ${stake.value}")
      leg
    }
  }
}
