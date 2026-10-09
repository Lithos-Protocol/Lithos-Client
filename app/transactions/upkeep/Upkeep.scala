package transactions.upkeep

import node.NodeApi
import node.model.{NodeTransaction, Paging}
import org.ergoplatform.appkit.impl.SignedTransactionImpl
import org.ergoplatform.appkit.{BlockchainParameters, SignedTransaction}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBudget, CandidateBundle, CapitalEntry}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.InputUTXO

import scala.util.Try

/**
 * The pure half of the upkeep source: what a successor costs the block, what a signed successor
 * becomes on the candidate path, and what is left of the source's share. Apart from the actor so a
 * spec can drive it with values alone.
 */
object Upkeep {

  /** An upkeep candidate's `kind`: [[CandidateTx.Upkeep]] and the job's name, so a refusal names the job. */
  def kind(job: String): String = s"${CandidateTx.Upkeep}:$job"

  // ─── sizing ───────────────────────────────────────────────────────────────

  /** What the node charges a transaction before any script runs, read from its interpreter. */
  final val InitCost: Long = ErgoInterpreter.interpreterInitCost.toLong

  /**
   * The fewest bytes an input can take: a 32-byte box id, an empty proof's length byte and an empty
   * extension. It is also the length of the reference a serialized box carries to its transaction.
   */
  final val KeylessInputBytes = 34L

  /**
   * The node's own accounting for a transaction of this shape, before any script runs: the
   * per-transaction, per-input, per-data-input and per-output cost, and the token term.
   *
   * `assets` is the token entries of inputs and outputs together. The factor of two is the node's:
   * it charges `tokenAccessCost` per entry and again per distinct id on each side
   * (`ErgoBoxAssetExtractor.totalAssetsAccessCost`), and distinct ids are charged here as one per
   * entry, which can only overstate.
   */
  def accountedCost(inputs: Int, dataInputs: Int, outputs: Int, assets: Int,
                    params: BlockchainParameters): Long =
    InitCost + inputs * params.getInputCost.toLong + dataInputs * params.getDataInputCost.toLong +
      outputs * params.getOutputCost.toLong + 2L * assets * params.getTokenAccessCost.toLong

  /**
   * The least a successor of `box` adds to the block, before it is built: in bytes, the box's own
   * serialized length, whose [[KeylessInputBytes]] reference to its transaction stands in for the
   * proof-less input; in cost, the node's accounting for one input and one output with the box's
   * tokens going in and coming out. A floor, so a box whose floor does not fit is never signed.
   */
  def floor(box: InputUTXO, params: BlockchainParameters): (Long, Long) =
    (box.bytes.length.toLong,
      accountedCost(inputs = 1, dataInputs = 0, outputs = 1, assets = box.tokens.size + box.tokens.size, params))

  // ─── ordering ─────────────────────────────────────────────────────────────

  /**
   * What a successor earns the block for the space it takes, known before it is built: the job's
   * expected revenue against the box's [[floor]]. Ranked per byte first, because bytes are what a
   * fee-paying transaction would otherwise have used, then per unit of cost.
   */
  final case class Worth(revenue: Long, bytes: Long, cost: Long) {
    def perByte: Double = math.max(0L, revenue).toDouble / math.max(1L, bytes)

    def perCost: Double = math.max(0L, revenue).toDouble / math.max(1L, cost)
  }

  object Worth {
    /** A box that cannot be valued: nothing earned, so it goes after every paying one. */
    val Unknown: Worth = Worth(0L, 1L, 1L)
  }

  /**
   * `xs` with the most valuable first. Stable, so boxes worth the same keep the order they came in,
   * which is the rotation's order. Each worth is computed once, since it may parse a box.
   */
  /**
   * Of `xs`, the entries `fits` accepts, the one `seen` says was seen first; the first of them in
   * `xs` on a tie, which keeps the rotation's order. None when nothing fits.
   */
  def headByAge[A](xs: Seq[A])(seen: A => Int, fits: A => Boolean): Option[A] =
    xs.filter(fits).reduceOption((a, b) => if (seen(b) < seen(a)) b else a)

  def byWorth[A](xs: Seq[A])(worth: A => Worth): Seq[A] = {
    val descending = Ordering.Tuple2(Ordering.Double.reverse, Ordering.Double.reverse)
    xs.map { x => val w = worth(x); x -> (w.perByte, w.perCost) }.sortBy(_._2)(descending).map(_._1)
  }

  // ─── the candidate path ───────────────────────────────────────────────────

  /** One successor ready to be offered, in a bundle of its own so a package can take the others without it. */
  final case class Prepared(job: String, boxId: String, tx: CandidateTx, capital: Seq[CapitalEntry]) {
    def bundle: CandidateBundle = CandidateBundle(Vector(tx), capital = capital)

    /** `job:box`, the way a log line names a successor. */
    def label: String = s"$job:${boxId.take(8)}"
  }

  /**
   * A signed successor as the candidate path carries it: its serialized bytes, and the cost signing
   * measured held at no less than the node's accounting for its shape, so a prover that measured
   * nothing cannot push a package over the block's limit. `box` is the input the job advanced.
   */
  def member(signed: SignedTransaction, job: String, box: InputUTXO, params: BlockchainParameters): CandidateTx = {
    val tx = signed.asInstanceOf[SignedTransactionImpl].getTx
    val accounted = accountedCost(tx.inputs.size, tx.dataInputs.size, tx.outputs.size,
      box.tokens.size + tx.outputs.map(_.additionalTokens.length).sum, params)
    CandidateTx(signed.getId, CandidateTx.signedJson(signed), kind(job),
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      math.max(signed.getCost.toLong, accounted), RollupExecution.signedLeaf(signed))
  }

  /**
   * The inputs a job signed that it did not report from discovery. Empty for an acceptable
   * transaction. The check is against what the job reported, so it holds a job to its own word;
   * [[walletInputs]] is the check against this wallet, and [[ScriptJob]] discovers only boxes at
   * the job's script.
   */
  def undiscoveredInputs(signed: SignedTransaction, discovered: Set[String]): Set[String] =
    RollupExecution.signedInputIds(signed) -- discovered

  /**
   * The inputs a job signed that sit at one of this wallet's keys, among the boxes this build read
   * back: `treeOf` is the script of every box read back, by id, and `wallet` the trees of the
   * wallet's keys (its P2PK trees and its miner-reward trees). The build refuses any input it did
   * not read back before asking this, so together the two checks cover every input: whatever a job
   * reports, no upkeep transaction spends a box at this wallet's P2PK or miner-reward scripts.
   * Value the operator holds under other scripts is outside this check.
   */
  def walletInputs(signed: SignedTransaction, treeOf: Map[String, String], wallet: Set[String]): Set[String] =
    RollupExecution.signedInputIds(signed).filter(id => treeOf.get(id).exists(wallet.contains))

  /**
   * The outputs of a signed transaction whose script is not among `allowed` (the scripts of its
   * inputs and this miner's collection contract), by index. Empty for an acceptable transaction:
   * value leaves the maintained boxes only as this miner's revenue, never as a fee or to anyone
   * else. Holds for every job, since it reads the signed transaction, not the plan.
   */
  def strayOutputs(signed: SignedTransaction, allowed: Set[String]): Seq[Int] = {
    import scala.collection.JavaConverters._
    signed.getOutputsToSpend.asScala.zipWithIndex.collect {
      case (out, i) if !allowed.contains(out.getErgoTree.bytesHex) => i
    }
  }

  // ─── fitting ──────────────────────────────────────────────────────────────

  /**
   * What is left of the source's share for one block, and what has been admitted. Immutable, so a
   * build can ask before it signs. A successor that does not fit is passed over, not the end of the
   * fit, and a second one spending an admitted box is left out, since the package would refuse the
   * double spend whole.
   */
  final case class Share(slots: Int, bytes: Long, cost: Long,
                         claimed: Set[String] = Set.empty, chosen: Vector[Prepared] = Vector.empty) {

    /** Nothing more can be admitted, whatever it costs: no building is worth doing for this block. */
    def full: Boolean = slots <= 0 || bytes <= 0L || cost <= 0L

    /** Whether something of this size still has room, before its inputs are known. */
    def affords(sizeBytes: Long, sizeCost: Long): Boolean =
      slots > 0 && sizeBytes <= bytes && sizeCost <= cost

    /** The share with `candidate` admitted, or nothing when it does not fit or re-spends a box. */
    def admit(candidate: Prepared): Option[Share] = {
      val inputs = candidate.tx.inputIds
      if (!affords(candidate.tx.sizeBytes.toLong, candidate.tx.cost) || inputs.exists(claimed.contains)) None
      else Some(copy(slots - 1, bytes - candidate.tx.sizeBytes, cost - candidate.tx.cost,
        claimed ++ inputs, chosen :+ candidate))
    }

    def usedBytes(budget: CandidateBudget): Long = budget.maxBytes - bytes

    def usedCost(budget: CandidateBudget): Long = budget.maxCost - cost
  }

  object Share {
    def of(maxTxs: Int, budget: CandidateBudget): Share = Share(maxTxs, budget.maxBytes, budget.maxCost)
  }

  // ─── space ────────────────────────────────────────────────────────────────

  /** Transactions per page when the mempool's demand is read. */
  final val MempoolPage = 100

  /** Milliseconds after which a mempool read fails at its next page boundary or at its end; a single slow call is not cut short. */
  final val ReadTimeoutMs = 2000L

  /**
   * Bytes and cost kept free beside the package for the node's own emission and fee transactions,
   * which no mempool read counts: the waiting transactions must fit in the rest of the block less
   * this, or the count does not grow. A reward transaction and a fee transaction are a few hundred
   * bytes each; this reserve is generous.
   */
  final val NodeReserve: CandidateBudget = CandidateBudget(maxBytes = 4096L, maxCost = 200000L)

  /**
   * Pages read before the mempool is taken as full. A mempool this deep is not one that leaves a
   * block empty, and reading it all would put the whole mempool on the path of every build.
   */
  final val MaxMempoolPages = 20

  /**
   * The bytes and cost the transactions waiting in the mempool would claim, read once per build so
   * upkeep can grow only when they fit beside it. Reading stops as soon as the demand reaches
   * `budget` on either dimension, since nothing past that changes the answer, and a mempool deeper
   * fills [[MaxMempoolPages]] pages is charged as `budget` in full. Sums saturate at `budget`, so
   * no reported figure can wrap them. Every waiting transaction is counted, fee or not, which
   * overstates the demand: overstated, upkeep grows less often; understated, it would take space a
   * paying transaction wanted. The read can still understate: offset paging over a mempool that
   * changes between pages can skip a transaction, and a page shorter than [[MempoolPage]] is taken
   * as the end. A transaction the node reports without a size or a cost fails the read, as does a
   * page that cannot be read, or a read found past `deadlineMs` (wall clock) before a page is
   * asked for or when the read ends, so the caller falls back to the configured count rather than
   * act on a guess, on part of the mempool, or past the time a build can spare. A single call that
   * hangs is bounded by the node client's own timeout, not by this.
   */
  def demand(api: NodeApi, budget: CandidateBudget, deadlineMs: Long = Long.MaxValue): Try[(Long, Long)] = Try {
    var bytes = 0L
    var cost = 0L
    var paging = Paging(0, MempoolPage)
    var pages = 0
    var ended = false
    def saturating(sum: Long, add: Long, cap: Long): Long = if (add >= cap - sum) cap else sum + add
    def pastDeadline(): Unit =
      if (System.currentTimeMillis() > deadlineMs)
        throw new IllegalStateException(s"the mempool read passed its ${ReadTimeoutMs} ms budget after $pages page(s)")
    while (!ended && bytes < budget.maxBytes && cost < budget.maxCost) {
      pastDeadline()
      if (pages >= MaxMempoolPages) {
        bytes = math.max(bytes, budget.maxBytes)
        cost = math.max(cost, budget.maxCost)
      } else {
        val page = api.unconfirmedTransactions(paging).get
        page.foreach { tx =>
          val (b, c) = weight(tx)
          bytes = saturating(bytes, b, budget.maxBytes)
          cost = saturating(cost, c, budget.maxCost)
        }
        ended = page.size < paging.limit
        paging = paging.next
        pages += 1
      }
    }
    pastDeadline()
    (bytes, cost)
  }

  /**
   * What one waiting transaction claims: the size and the cost the node reports. A transaction
   * reported without either cannot be weighed (an estimate from its bytes would understate a
   * script-heavy one, and understating is the one direction this read must not err in), so it
   * fails the read.
   */
  private[upkeep] def weight(tx: NodeTransaction): (Long, Long) = (tx.size, tx.cost) match {
    case (Some(bytes), Some(cost)) if bytes > 0 && cost > 0L => (bytes.toLong, cost)
    case _ => throw new IllegalStateException(s"the node reports no size or cost for ${tx.id}, so the mempool " +
      "cannot be weighed")
  }

  /**
   * The source's share in opportunistic mode. The block holds this client's package share and,
   * beside it, `rest`, which the node fills from the mempool, less [[NodeReserve]] for the node's
   * own transactions. When the mempool's demand fits in `rest` on both bytes and cost, by the
   * node's figures at the read, the share keeps its configured bytes and cost and takes up to the
   * larger of the configured count and `maxTxs` (the opportunistic cap) transactions in them;
   * otherwise the configured share stands. A demand that reaches `rest` exactly does not fit:
   * [[demand]] saturates there, so that figure means "at least this". The growth is in the count
   * only, so the bytes and cost the operator set bound upkeep as in fixed mode, and the candidate
   * builder's bytes and cost passes stand unchanged; its count for upkeep is the cap in every block
   * (see `UpkeepConfig.allowance`), so only this check ties the larger count to the mempool. A
   * configured count of 0 never grows:
   * it is the operator's sign that the source is not to be asked, and the builder would refuse
   * every successor anyway.
   */
  def opportunistic(configured: Share, rest: CandidateBudget, demandBytes: Long, demandCost: Long,
                    maxTxs: Int): Share =
    if (configured.slots > 0 && demandBytes < rest.maxBytes && demandCost < rest.maxCost)
      configured.copy(slots = math.max(configured.slots, maxTxs))
    else configured

}
