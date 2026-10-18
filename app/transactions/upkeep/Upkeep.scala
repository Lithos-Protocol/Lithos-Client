package transactions.upkeep

import org.ergoplatform.appkit.impl.SignedTransactionImpl
import org.ergoplatform.appkit.{BlockchainParameters, SignedTransaction}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBudget, CandidateBundle, CapitalEntry}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.InputUTXO

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

}
