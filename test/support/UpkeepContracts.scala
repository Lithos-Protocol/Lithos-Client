package support

import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, NetworkType}
import work.lithos.mutations.Contract

import scala.io.Source

/**
 * The reference upkeep contract, compiled from `test/resources/upkeep/DueJob.ergo` for the specs
 * that hold the client's pinned tree to it. The client itself never compiles it: `HeartbeatJob`
 * carries the tree, and the source is kept with the tests as the record of what that tree is.
 *
 * `DueJob` takes no compile-time constants: a box under it is found by its tree alone, so the tree is the same
 * for every network and deployment.
 */
object UpkeepContracts {

  def dueJobSource: String = {
    val src = Source.fromResource("upkeep/DueJob.ergo")
    try src.mkString finally src.close()
  }

  def mkDueJobContract(ctx: BlockchainContext): Contract = mkDueJobContract(ctx.getNetworkType)

  def mkDueJobContract(networkType: NetworkType): Contract =
    Contract.fromErgoScript(networkType, ConstantsBuilder.empty(), dueJobSource, Seq.empty)
}
