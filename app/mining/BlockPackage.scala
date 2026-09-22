package mining

import stratum.CollateralData
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBudget

/**
 * Genesis and ordered additions for one candidate. Revenue counts admitted source ERG outputs once.
 *
 * @param sources names of the candidate sources whose transactions `blockTxs` carries
 * @param late    names of the candidate sources that had not answered when `blockTxs` was assembled
 * @param limits  the node's active block size and cost limits; absent when they could not be read
 * @param share   the slice of those limits this package was allowed, which is what admission used
 * @param revenueByTx unspent ERG each member left for the top-up, keyed by the transaction that
 *                    created it; sums to `revenue`
 */
case class BlockPackage(blockHeight: Int,
                        collateral: CollateralData,
                        blockTxs: Seq[CandidateTx] = Seq.empty[CandidateTx],
                        revision: Int = 0,
                        parentId: String = "",
                        revenue: Long = 0L,
                        elapsedTime: Option[String] = None,
                        sources: Set[String] = Set.empty,
                        late: Set[String] = Set.empty,
                        limits: Option[CandidateBudget] = None,
                        share: Option[CandidateBudget] = None,
                        revenueByTx: Map[String, Long] = Map.empty) {

  def identity: MiningMessages.CandidateIdentity =
    MiningMessages.CandidateIdentity(blockHeight, parentId, collateral.txId, revision)

  /** The full package. */
  def allTxs: Seq[String] = collateral.txJSON +: blockTxs.map(_.json)

  /** What to fall back to when the node will not accept the rest. */
  def genesisOnly: Seq[String] = Seq(collateral.txJSON)

  def withBlockTxs(built: Seq[CandidateTx], ergRevenue: Long = 0L, from: Set[String] = Set.empty,
                   lateSources: Set[String] = Set.empty, blockLimits: Option[CandidateBudget] = None,
                   packageShare: Option[CandidateBudget] = None,
                   perTxRevenue: Map[String, Long] = Map.empty): BlockPackage =
    copy(blockTxs = built, revision = revision + 1, revenue = ergRevenue, sources = from, late = lateSources,
      limits = blockLimits.orElse(limits), share = packageShare.orElse(share), revenueByTx = perTxRevenue)

  /** Genesis alone: no additions, and so no sources and no late ones. */
  def withoutBlockTxs: BlockPackage =
    copy(blockTxs = Seq.empty, revenue = 0L, sources = Set.empty, late = Set.empty, revenueByTx = Map.empty)

  def describe: String =
    s"BlockPackage(height=$blockHeight, rev=$revision, collateral=${collateral.collateralId}, " +
      s"txs=[${blockTxs.map(t => s"${t.kind}:${t.id.take(8)}").mkString(", ")}])"
}
