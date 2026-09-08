package transactions

import mutations.NodeWallet
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import stratum.CollateralData
import transactions.BlockTxMessages.CandidateTx
import transactions.engine.RollupExecution
import transactions.rollups.RollupTransactions
import work.lithos.mutations.UTXO

import scala.util.{Failure, Success, Try}

/**
 * The last member of a candidate package: one transaction folding every unspent candidate output
 * into the holding box the block's own genesis transaction created.
 *
 * Bound to genesis rather than prepared with the rest, because the box it spends does not exist
 * until genesis is signed. Holding op 1 only permits this while HEIGHT is the rollup's own block,
 * so it is a candidate-only transaction that is never broadcast.
 */
object CandidateTopUp {

  private val logger: Logger = LoggerFactory.getLogger("CandidateTopUp")

  /** Named so a rejected candidate says which builder produced the offending transaction. */
  final val Kind = "holding-topup"

  /**
   * Revenue inputs one top-up may spend, one short of this client's per-transaction ceiling because
   * the holding box takes the first slot. Over the cap the most valuable entries are taken: every
   * input costs about the same bytes and execution, so that is the most ERG the block will carry.
   */
  final val MaxRevenueInputs: Int = engine.EngineWalletState.MAX_TX_INPUTS - 1

  /**
   * Build the top-up for one package, or nothing when there is nothing to add.
   *
   * Fee-less like every other inserted transaction: this miner is the one mining the block, so the
   * whole of what the ledger holds goes into Holding rather than part of it going to a fee box.
   */
  def build(ctx: BlockchainContext,
            wallet: NodeWallet,
            genesis: CollateralData,
            capital: CandidateCapital,
            blockHeight: Int): Option[CandidateTx] = {
    val available = capital.unspent
    if (available.isEmpty) None
    else genesis.holdingOutput match {
      case None =>
        logger.warn(s"Genesis ${genesis.txId.take(8)} carries no holding output, so " +
          s"${capital.availableErg} nanoERG of candidate revenue cannot be topped up")
        None
      case Some(holding) =>
        val revenue = available.sortBy(_.value)(Ordering[Long].reverse)
          .take(MaxRevenueInputs).map(_.box)
        if (revenue.size < available.size)
          logger.info(s"Topping up with ${revenue.size} of ${available.size} candidate outputs; " +
            "the rest stay spendable in a later block")
        val plan = RollupTransactions.planTopUp(revenue, Seq.empty[UTXO])
        if (!plan.isViable) {
          // Ordinary once the residue output costs more than the entries are worth; the revenue
          // stays on outputs this miner can still spend in a later block.
          logger.info(s"Skipping holding top-up for ${genesis.txId.take(8)}: it would add ${plan.added}")
          None
        } else Try(RollupTransactions.genHoldingTopUp(
          ctx, wallet, holding, revenue, Seq.empty[UTXO], blockHeight)) match {
          case Success(sTx) =>
            Some(CandidateTx(sTx.getId.replace("\"", ""), sTx.toJson(false, false), Kind,
              RollupExecution.signedInputIds(sTx), RollupExecution.signedSizeBytes(sTx),
              sTx.getCost.toLong, RollupExecution.signedLeaf(sTx)))
          case Failure(ex) =>
            logger.warn(s"Could not build the holding top-up for ${genesis.txId.take(8)}: ${ex.getMessage}")
            None
        }
    }
  }
}
