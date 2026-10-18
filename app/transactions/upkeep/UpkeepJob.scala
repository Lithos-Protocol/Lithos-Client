package transactions.upkeep

import node.NodeApi
import org.ergoplatform.appkit.{BlockchainContext, BlockchainParameters, NetworkType, SignedTransaction}
import transactions.candidate.CapitalEntry
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * One kind of keyless, fee-less maintenance this miner may carry in its own block: which boxes of
 * one protocol it maintains, when one is due, and what its successor is.
 *
 * The source owns the timer, the read-back, the budget and the refusals, so a new protocol is one
 * implementation, one entry in [[UpkeepRegistry.all]], and one config block. A job whose boxes sit
 * at one script extends [[ScriptJob]]; this trait is for one that does not fit that shape.
 *
 * A job must spend only the boxes it discovered, with no wallet input and no fee output, and its
 * successor must be a fixed function of the box and the height. The source enforces the first
 * against what the job reported and read back, and refuses an input at this wallet's keys;
 * [[ScriptJob]] also makes the box the only input, the fee zero, and every output the box's own
 * script or this miner's. The source does not check a direct `UpkeepJob` for fee or foreign
 * outputs, and nothing checks that the successor is fixed by the box and the height: review does.
 * "Not yet" belongs in [[due]] and "cannot pay" in [[build]], since a box `build` declines is held
 * until a scan stops finding it. A job need not count what it builds, because the source sizes and
 * fits every successor; it is reviewed before it is registered, because the rest is taken on trust.
 */
trait UpkeepJob {

  /**
   * The config key under `stratum.candidate.sources.upkeep.jobs`, and what a candidate's `kind`
   * names, so a refused block says which job built the transaction.
   */
  def name: String

  /**
   * The ids of every box this job maintains right now. Called on the scan timer, so it may page the
   * node, and it may throw, which keeps the last pass. Ids only, because the box is read back fresh
   * before anything is built.
   */
  def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String]

  /**
   * The box ids the operator listed for this job. The source keeps every one [[discover]] returns
   * and applies its per-job cap only to the rest, so a box someone asked for by name is never cut.
   */
  def configured: Set[String] = Set.empty

  /** Whether `box` may be advanced at `height`. Pure, because it is asked of every box every block. */
  def due(box: InputUTXO, height: Int): Boolean

  /**
   * The signed successor for one due box, or nothing when the box cannot pay for it. Signed by the
   * job because only the job knows what satisfies its script. Outputs are created at `bc.height`
   * and revenue sits at `bc.payTo`. The preHeader at signing carries only that height: a script
   * reading the miner's key, votes or timestamp would sign here and be refused by the node.
   */
  def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built]
}

object UpkeepJob {

  /** The signed successor and its revenue outputs. The source sizes it, which leaves out script cost. */
  final case class Built(tx: SignedTransaction, capital: Seq[CapitalEntry] = Seq.empty)
}

/**
 * Everything a build may need beyond the box, gathered once per block: one value, so a field a later
 * job needs is added here rather than to every job's signature, and once, because the node's
 * parameters are the same for every box in a block.
 *
 * @param ctx     the appkit context the build runs in, through which a job may read a data input
 * @param params  the node's parameters for this block
 * @param height  the block the successor lands in
 * @param payTo   the collection contract revenue is paid to
 * @param network the network this client runs on
 */
final case class BuildContext(ctx: BlockchainContext, params: BlockchainParameters, height: Int,
                              payTo: Contract, network: NetworkType)

object BuildContext {
  def apply(ctx: BlockchainContext, height: Int, payTo: Contract): BuildContext =
    BuildContext(ctx, ctx.getDataSource.getParameters, height, payTo, ctx.getNetworkType)
}
