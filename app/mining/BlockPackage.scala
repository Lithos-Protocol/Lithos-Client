package mining

import stratum.CollateralData
import transactions.candidate.BlockTxMessages.CandidateTx

/** Genesis and ordered additions for one candidate. Revenue counts admitted source ERG outputs once. */
case class BlockPackage(blockHeight: Int,
                        collateral: CollateralData,
                        blockTxs: Seq[CandidateTx] = Seq.empty[CandidateTx],
                        revision: Int = 0,
                        parentId: String = "",
                        revenue: Long = 0L,
                        elapsedTime: Option[String] = None) {

  def identity: MiningMessages.CandidateIdentity =
    MiningMessages.CandidateIdentity(blockHeight, parentId, collateral.txId, revision)

  /** The full package. */
  def allTxs: Seq[String] = collateral.txJSON +: blockTxs.map(_.json)

  /** What to fall back to when the node will not accept the rest. */
  def genesisOnly: Seq[String] = Seq(collateral.txJSON)

  def withBlockTxs(built: Seq[CandidateTx], ergRevenue: Long = 0L): BlockPackage =
    copy(blockTxs = built, revision = revision + 1, revenue = ergRevenue)

  def describe: String =
    s"BlockPackage(height=$blockHeight, rev=$revision, collateral=${collateral.collateralId}, " +
      s"txs=[${blockTxs.map(t => s"${t.kind}:${t.id.take(8)}").mkString(", ")}])"
}
