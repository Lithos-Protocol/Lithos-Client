package stats

import mining.{BlockPackage, CandidateMaterialized}
import play.api.libs.json._

/**
 * Descriptors only: no signed bodies, box bytes or mutable mining candidates leave the pool.
 *
 * `revenueNanoErg` is the unspent value this transaction left for the holding top-up to collect.
 * An output a later member spent was carried forward rather than earned twice, so it is credited
 * to neither, and these therefore sum to the package's expected revenue. A member that creates no
 * spendable output — the genesis, a carried mempool ancestor, the top-up itself — is legitimately
 * zero rather than unknown.
 */
final case class PackageTransactionView(id: String, kind: String, sizeBytes: Int,
                                        cost: Long, proofLeafMatched: Boolean,
                                        revenueNanoErg: Long = 0L)

/**
 * `blockMax*` are the node's active block limits and `packageMax*` the slice of them this client
 * allows one Lithos package. Both are absent when the node's parameters could not be read, which
 * is the only honest answer — the packing then ran unbounded and there is no denominator to give.
 */
final case class BlockPackageView(genesisId: String, collateralBoxId: String, revision: Int,
                                  expectedRevenueNanoErg: Long, sources: Vector[String],
                                  lateSources: Vector[String], transactions: Vector[PackageTransactionView],
                                  blockMaxSizeBytes: Option[Long] = None, blockMaxCost: Option[Long] = None,
                                  packageMaxSizeBytes: Option[Long] = None, packageMaxCost: Option[Long] = None)

object BlockPackageView {
  def from(pkg: BlockPackage, materialized: Option[CandidateMaterialized]): BlockPackageView = {
    val matched = materialized.filter(_.identity == pkg.identity).map(_.included).getOrElse(Set.empty[String])
    val genesis = PackageTransactionView(pkg.collateral.txId, "genesis", pkg.collateral.signedSizeBytes,
      pkg.collateral.cost, matched.contains(pkg.collateral.txId),
      pkg.revenueByTx.getOrElse(pkg.collateral.txId, 0L))
    BlockPackageView(pkg.collateral.txId, pkg.collateral.collateralId, pkg.revision, pkg.revenue,
      pkg.sources.toVector.sorted, pkg.late.toVector.sorted,
      genesis +: pkg.blockTxs.iterator.map(tx =>
        PackageTransactionView(tx.id, tx.kind, tx.sizeBytes, tx.cost, matched.contains(tx.id),
          pkg.revenueByTx.getOrElse(tx.id, 0L))).toVector,
      pkg.limits.map(_.maxBytes), pkg.limits.map(_.maxCost),
      pkg.share.map(_.maxBytes), pkg.share.map(_.maxCost))
  }
}

final case class ActiveStratumJob(jobId: String, height: Int, parentId: String, workMessage: String,
                                  publicationId: String, publishedAt: Long, mode: String,
                                  blockPackage: Option[BlockPackageView])

final case class StratumStatsView(status: String = "waiting", observedAt: Option[Long] = None,
                                  connectedConnections: Int = 0, activeJob: Option[ActiveStratumJob] = None)
final case class LocalStatsView(stratum: StratumStatsView)
final case class StatsView(enabled: Boolean, local: LocalStatsView, dex: DexStatsView = DexStatsView(),
                            storage: StatsStorageView = StatsStorageView(), mining: MiningStatsView = MiningStatsView())

object StatsView {
  import MiningStatsData.viewWrites
  implicit val transactionWrites: OWrites[PackageTransactionView] = OWrites { tx =>
    Json.obj("id" -> tx.id, "kind" -> tx.kind,
      "sizeBytes" -> Option(tx.sizeBytes).filter(_ > 0),
      "cost" -> Option(tx.cost).filter(_ > 0).map(_.toString),
      "proofLeafMatched" -> tx.proofLeafMatched,
      // Always present, including as "0": a member that earns nothing is a fact, not a gap.
      "revenueNanoErg" -> tx.revenueNanoErg.toString)
  }
  implicit val packageWrites: OWrites[BlockPackageView] = OWrites { pkg =>
    Json.obj("genesisId" -> pkg.genesisId, "collateralBoxId" -> pkg.collateralBoxId,
      "revision" -> pkg.revision, "expectedRevenueNanoErg" -> pkg.expectedRevenueNanoErg.toString,
      "sources" -> pkg.sources, "lateSources" -> pkg.lateSources, "transactions" -> pkg.transactions,
      "transactionScope" -> "client-supplied",
      "knownSizeBytes" -> pkg.transactions.map(tx => BigInt(tx.sizeBytes.max(0))).sum.toString,
      "knownCost" -> pkg.transactions.map(tx => BigInt(tx.cost.max(0L))).sum.toString,
      "allSizesKnown" -> pkg.transactions.forall(_.sizeBytes > 0),
      "allCostsKnown" -> pkg.transactions.forall(_.cost > 0),
      "blockMaxSizeBytes" -> pkg.blockMaxSizeBytes.map(_.toString),
      "blockMaxCost" -> pkg.blockMaxCost.map(_.toString),
      "packageMaxSizeBytes" -> pkg.packageMaxSizeBytes.map(_.toString),
      "packageMaxCost" -> pkg.packageMaxCost.map(_.toString))
  }
  implicit val jobWrites: OWrites[ActiveStratumJob] = Json.writes[ActiveStratumJob]
  implicit val stratumWrites: OWrites[StratumStatsView] = Json.writes[StratumStatsView]
  implicit val localWrites: OWrites[LocalStatsView] = Json.writes[LocalStatsView]
  implicit val writes: OWrites[StatsView] = Json.writes[StatsView]
}
