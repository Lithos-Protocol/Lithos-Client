package mining

import stratum.CollateralData
import transactions.BlockTxMessages.CandidateTx

/**
 * Everything [[CandidateBuilder]] has ready for the block at `blockHeight`.
 *
 * The genesis transaction is the only mandatory member and is published alone as soon as it is
 * signed; the rest arrive later as a higher-`revision` package.
 *
 * Order is load bearing. The node validates transactions in the order it is handed them, and one
 * that arrives before its unconfirmed parent is invalid, so each source's ordering is preserved.
 * Genesis leads because it is the only one whose loss costs the block its Lithos status.
 */
case class BlockPackage(blockHeight: Int,
                        collateral: CollateralData,
                        blockTxs: Seq[CandidateTx] = Seq.empty[CandidateTx],
                        revision: Int = 0) {

  /** The full package. */
  def allTxs: Seq[String] = collateral.txJSON +: blockTxs.map(_.json)

  /** What to fall back to when the node will not accept the rest. */
  def genesisOnly: Seq[String] = Seq(collateral.txJSON)

  def withBlockTxs(built: Seq[CandidateTx]): BlockPackage =
    copy(blockTxs = built, revision = revision + 1)

  def describe: String =
    s"BlockPackage(height=$blockHeight, rev=$revision, collateral=${collateral.collateralId}, " +
      s"txs=[${blockTxs.map(t => s"${t.kind}:${t.id.take(8)}").mkString(", ")}])"
}
