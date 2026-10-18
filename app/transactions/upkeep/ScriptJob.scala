package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import transactions.rent.StorageRent
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/**
 * What a job's rule produces for one due box: the outputs its successor transaction makes, the boxes
 * it reads without spending, and which of the outputs are this miner's.
 *
 * A plan rather than a transaction, so the job states only what its protocol decides and
 * [[ScriptJob]] does the assembly every such job would otherwise repeat. The outputs must spend the
 * box's value to the nanoERG: [[ScriptJob]] refuses a plan that leaves change, since the builder cannot
 * place sub-minimum change without a fee output and a block transaction carries none.
 *
 * @param outputs    every output, in the order the protocol's script reads them
 * @param dataInputs boxes the script reads, such as an oracle; read by the job through the context
 *                   it is handed, never spent
 * @param revenue    indices into `outputs` of the ones paid to `payTo`, declared to the package as
 *                   capital its final top-up may aggregate
 */
final case class Successor(outputs: Seq[UTXO],
                           dataInputs: Seq[InputUTXO] = Seq.empty,
                           revenue: Seq[Int] = Seq.empty)

/**
 * An upkeep job whose boxes all sit at one script and whose successor is a fixed function of the
 * box. Such a job is its rule: the script, when a box is due, the successor, and which boxes go
 * first. Everything around the rule is written once here rather than in every job:
 *
 *  - Discovery. On an indexed node, the first [[ScriptJob.MaxPages]] pages of [[ScriptJob.PageSize]]
 *    confirmed boxes at the script, newest first (1,000 boxes), then lowest [[priority]] first among
 *    those. Newest first, because a beat gives a box a new id at the newest end: a box that is kept
 *    alive stays in the window, and crowding it out takes a stream of newer boxes rather than a
 *    thousand old ones made once. Boxes beyond that window are not seen on that pass; the
 *    configured list below is how an operator makes sure particular boxes are seen, until their
 *    next beat gives them new ids.
 *    On every node, indexed or not, the ids under `jobs.<name>.boxIds`, each read from the UTXO set
 *    with one `boxById` call, so an unconfirmed box is never spent without its parent. That list is
 *    not a fallback: its boxes come first and are never cut. A node error reading any listed id
 *    fails the job's whole pass, and the source keeps the last one; a listed id the UTXO set no
 *    longer holds (spent, typically by a beat) is dropped without a log line. A box is kept only if
 *    it sits at the script, the job [[maintains]] it, and EIP-27 does not block it.
 *  - Assembly: the box as the only input, the plan's data inputs, no fee, outputs spending the box
 *    to the nanoERG.
 *  - Signing with a prover that holds no secret. The preHeader carries only the height, so a script
 *    reading the miner's key, votes or timestamp would sign here and be refused by the node.
 *
 * A job that does not fit, such as one spending several protocol boxes at once, implements
 * [[UpkeepJob]] directly.
 *
 * @param boxIds boxes the operator lists for this job, read on every discovery pass
 */
abstract class ScriptJob(boxIds: Seq[String]) extends UpkeepJob {

  private val listed: Seq[String] = boxIds.map(_.toLowerCase).distinct

  final override def configured: Set[String] = listed.toSet

  /**
   * The script every box of this job sits at. Asked on every discovery pass, so an implementation
   * should compile once per network, which [[ScriptJob.PerNetwork]] does, or pin the tree.
   */
  def contract(network: NetworkType): Contract

  /**
   * Whether a box at the script is one this job could ever advance: its registers are of the types
   * the script reads, and its terms make sense. Asked at discovery, so a malformed box is never held.
   */
  def maintains(box: InputUTXO): Boolean = true

  /**
   * The order discovery keeps boxes in, lowest first, so that when the source's cap cuts the list it
   * cuts the boxes that can wait. A job with a due height returns it.
   */
  def priority(box: InputUTXO): Long = 0L

  /**
   * The successor plan for a due box, or nothing when the box cannot be advanced, which the source
   * treats as the box being unable to pay. A throw is treated as a transient refusal.
   */
  def plan(box: InputUTXO, build: BuildContext): Option[Successor]

  /** Named after the job so a log line from a shared base still says whose box it is about. */
  private lazy val logger: Logger = LoggerFactory.getLogger(s"UpkeepJob.$name")

  final override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    val network = ctx.getNetworkType
    val tree = contract(network).ergoTreeHex
    val indexed = if (api.indexerEnabled) ScriptJob.byTree(api, tree, name) else Seq.empty[NodeBox]
    val read = listed.flatMap { id =>
      api.boxById(id) match {
        case Success(box) => box
        case Failure(ex) =>
          throw new IllegalStateException(s"could not read configured $name box $id: ${ex.getMessage}", ex)
      }
    }
    // Parsed once, here: a register that does not decode makes the box as foreign as a wrong script.
    def ours(box: NodeBox): Option[Long] =
      if (box.ergoTree != tree) None
      else Try {
        val input = box.toInputUTXO(ctx)
        if (maintains(input) && !StorageRent.blockedByReEmission(input, network)) Some(priority(input)) else None
      }.toOption.flatten
    // A configured box that is not this job's is the operator's mistake, so it is named; an indexed
    // box that is not this job's is just someone else's.
    val kept = read.filter { box =>
      val mine = ours(box).isDefined
      if (!mine) logger.warn(s"Configured $name box ${box.boxId} is not one this job maintains; ignoring it")
      mine
    }.map(_.boxId)
    val found = indexed.flatMap(box => ours(box).map(box.boxId -> _)).sortBy(_._2).map(_._1)
    (kept ++ found).distinct
  }

  final override def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] =
    plan(box, bc).map(successor => ScriptJob.signed(name, box, successor, bc))
}

object ScriptJob {

  /** Boxes per index page, and the pages one pass will read; the source keeps fewer boxes anyway. */
  private[upkeep] final val PageSize = 100
  private[upkeep] final val MaxPages = 10

  /** A contract compiled once per network, since discovery compares every box against it every pass. */
  final class PerNetwork(compile: NetworkType => Contract) {
    private val compiled = new ConcurrentHashMap[NetworkType, Contract]()

    def apply(network: NetworkType): Contract =
      compiled.computeIfAbsent(network, (nt: NetworkType) => compile(nt))
  }

  /**
   * The least value `out` may carry: the node's price per byte times the box's serialized length,
   * reference to its transaction included. For a job's [[ScriptJob.plan]] to size its outputs.
   */
  def minimumValue(out: UTXO, bc: BuildContext): Long =
    bc.params.getMinValuePerByte.toLong *
      out.toInput(bc.ctx, ErgoId.create("00" * 32), 0.toShort).bytes.length.toLong

  /**
   * The plan assembled and signed: `box` the only input, no fee, the block's height in the
   * preHeader, an empty proof from a prover with no secret, and the revenue declared as capital.
   * Every output must sit at the box's own script or at `bc.payTo`, revenue at `bc.payTo` only, and
   * none at the fee proposition: refused here, so a script job cannot pay a fee or send value, this
   * miner's revenue included, anywhere else.
   */
  private def signed(job: String, box: InputUTXO, successor: Successor, bc: BuildContext): UpkeepJob.Built = {
    require(successor.revenue.forall(i => i >= 0 && i < successor.outputs.size),
      s"$job's revenue ${successor.revenue.mkString(", ")} names an output the plan does not have " +
        s"(${successor.outputs.size} outputs)")
    require(!successor.outputs.exists(_.contract.ergoTreeHex == Contract.FEE.ergoTreeHex),
      s"$job's plan has an output at the fee proposition; block transactions pay no fee")
    require(successor.revenue.forall(i => successor.outputs(i).contract.ergoTreeHex == bc.payTo.ergoTreeHex),
      s"$job's plan declares revenue at an output that is not this miner's collection contract")
    require(successor.outputs.forall(o => o.contract.ergoTreeHex == box.contract.ergoTreeHex ||
      o.contract.ergoTreeHex == bc.payTo.ergoTreeHex),
      s"$job's plan has an output at neither the box's own script nor this miner's collection contract")
    val spent = successor.outputs.map(_.value).sum
    require(spent == box.value,
      s"$job's plan spends $spent of the box's ${box.value} nanoERG; it must spend all of it")
    val unsigned = TxBuilder(bc.ctx)
      .setInputs(box)
      .setDataInputs(successor.dataInputs: _*)
      .setOutputs(successor.outputs: _*)
      // HEIGHT is the block the successor lands in, which is what a due rule is written against.
      .setPreHeader(bc.ctx.createPreHeader().height(bc.height).build())
      .buildTx(0L, bc.payTo.address(bc.ctx))
    val tx = bc.ctx.newProverBuilder().build().sign(unsigned)
    val capital = successor.revenue.distinct.map(i => CapitalEntry(CapitalOrigin.ExecutorReward,
      InputUTXO(tx.getOutputsToSpend.get(i)), parentTxId = tx.getId))
    UpkeepJob.Built(tx, capital)
  }

  /**
   * Every unspent box at `tree` the index reports, confirmed only. Throws on a failed page so the
   * source keeps its last pass rather than a partial one.
   */
  private def byTree(api: NodeApi, tree: String, job: String): Seq[NodeBox] = {
    var found = Vector.empty[NodeBox]
    var paging = Paging(0, PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxPages) {
      val page = api.unspentBoxesByErgoTree(tree, paging, SortDirection.Desc, MempoolOptions.ConfirmedOnly) match {
        case Success(boxes) => boxes
        case Failure(ex) =>
          throw new IllegalStateException(s"the node index could not list $job boxes (page $pages): ${ex.getMessage}", ex)
      }
      found ++= page.map(_.box)
      exhausted = page.size < paging.limit
      paging = paging.next
      pages += 1
    }
    found
  }
}
