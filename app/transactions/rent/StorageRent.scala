package transactions.rent

import mutations.NodeWallet
import org.ergoplatform.appkit.impl.{BlockchainContextBase, InputBoxImpl, SignedTransactionImpl}
import org.ergoplatform.appkit.{BlockchainContext, BlockchainParameters, NetworkType}
import org.ergoplatform.wallet.protocol.Constants
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import org.slf4j.{Logger, LoggerFactory}
import sigma.ast.ShortConstant
import sigma.interpreter.{ContextExtension, ProverResult}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{InputUTXO, MainnetEip27Constants, Token, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * What may be done with one box whose storage rent is due.
 *
 * The two branches are Ergo's, not a policy: past `StoragePeriod` the interpreter accepts an input
 * carrying an empty proof, and which branch applies is decided by whether the box can pay its own
 * fee.
 */
sealed trait RentAction

object RentAction {
  /** The box pays its fee and is recreated one fee lighter. Only the fee is ours. */
  final case class Collect(fee: Long) extends RentAction

  /** The box cannot cover its fee, so the rule short-circuits and it is taken whole. */
  case object Claim extends RentAction
}

/**
 * One expired box and the branch it falls on.
 *
 * `proceedsErg` is what a collection actually realizes: the fee on the funded branch, the whole box
 * on the underfunded one. Tokens only ever come from the underfunded branch, because a recreation
 * has to preserve the token register exactly.
 */
final case class RentCandidate(box: InputUTXO, action: RentAction) {
  def proceedsErg: Long = action match {
    case RentAction.Collect(fee) => fee
    case RentAction.Claim => box.value
  }

  def proceedsTokens: Seq[Token] = action match {
    case RentAction.Collect(_) => Seq.empty[Token]
    case RentAction.Claim => box.tokens
  }

  def recreates: Boolean = action.isInstanceOf[RentAction.Collect]
}

/**
 * Storage-rent collection: the one spend Ergo allows with no script and no signature.
 *
 * Past `StoragePeriod` blocks an input carrying an empty proof and context variable 127 is accepted
 * if the output that variable names recreates the box — same script, same tokens, same registers,
 * a new creation height, and at most the storage fee removed. A box that cannot cover its own fee
 * is taken outright instead, tokens included.
 *
 * Assembled rather than signed. A prover would have to satisfy the box's own script, which is the
 * one thing this client cannot do for a stranger's box, so the empty proof is placed directly and
 * the result is wrapped to give the same surface every other candidate transaction has.
 */
object StorageRent {

  private val logger: Logger = LoggerFactory.getLogger("StorageRent")

  /** Named so a rejected candidate says which builder produced the offending transaction. */
  final val Kind = "storage-rent"

  /**
   * Inputs one collection may sweep.
   */
  final val MaxBoxes: Int = 1000

  /**
   * Blocks a box must live before its rent can be collected, which is four years of them.
   */
  final val StoragePeriod: Int = Constants.StoragePeriod

  /**
   * Whether this box is eligible by age but unspendable while EIP-27 and the rent rule contradict
   * each other.
   */
  def blockedByReEmission(box: InputUTXO, network: NetworkType): Boolean =
    network == NetworkType.MAINNET &&
      isReEmission(box.contract.ergoTreeHex, box.tokens.map(_.id.toString))

  /**
   * Ergo's own emission and re-emission boxes, which a rent collection has no business touching.
   *
   * Wider than the token alone: the boxes EIP-27 pays into sit at the proxy script and hold no
   * re-emission tokens at all, so a token-only test walks straight past them. They accumulate ERG
   * and are never spent, which is exactly the shape a rent scan is looking for.
   */
  private def isReEmission(ergoTree: String, tokenIds: Seq[String]): Boolean =
    ergoTree == MainnetEip27Constants.Proxy.ergoTreeHex ||
      tokenIds.exists(id => id == MainnetEip27Constants.TokenId ||
        id == MainnetEip27Constants.ReemissionNft ||
        id == MainnetEip27Constants.EmissionNft)

  def storageFee(box: InputUTXO, params: BlockchainParameters): Long =
    params.getStorageFeeFactor.toLong * box.bytes.length

  /**
   * Which branch a box of this value and size falls on, ignoring its age.
   *
   * Nothing is collectable when the recreation would hold less than consensus allows a box of that
   * size to hold: the fee is payable, but there is no legal successor to pay it out of.
   */
  def decide(value: Long, sizeBytes: Int, params: BlockchainParameters): Option[RentAction] = {
    val fee = params.getStorageFeeFactor.toLong * sizeBytes
    if (overflowsNodeFee(sizeBytes, params)) None
    else if (value <= fee) Some(RentAction.Claim)
    else if (value - fee >= params.getMinValuePerByte.toLong * sizeBytes) Some(RentAction.Collect(fee))
    else None
  }

  /**
   * Whether the fee for a box this size overflows the arithmetic the rule itself uses.
   *
   * `checkExpiredBox` computes `storageFeeFactor * box.bytes.length` in `Int`, so past
   * `Int.MaxValue / factor` bytes the product wraps negative. A negative fee makes the box look
   * funded however little it holds, and then demands a successor richer than the box itself — which
   * nothing can satisfy. Such a box is uncollectable by either branch, so it is never offered.
   */
  def overflowsNodeFee(sizeBytes: Int, params: BlockchainParameters): Boolean =
    params.getStorageFeeFactor.toLong * sizeBytes > Int.MaxValue.toLong

  /**
   * Which branch this box falls on at `blockHeight`, or nothing when it cannot be collected.
   *
   * `creationHeight` comes from the indexer rather than the box, because discovery is what knows the
   * box's age.
   */
  def plan(box: InputUTXO, creationHeight: Int, blockHeight: Int, params: BlockchainParameters,
           network: NetworkType, protocol: ProtocolBoxes): Option[RentAction] = {
    if (blockHeight.toLong - creationHeight < StoragePeriod.toLong) None
    else if (protocol.owns(box)) None
    else if (blockedByReEmission(box, network)) None
    else decide(box.value, box.bytes.length, params)
  }

  /**
   * Sort the outputs of a scanned block into what may be collected and what must wait.
   *
   * `threshold` is the newest creation height a box may declare and still be due, so a box at or
   * below it is old enough. Boxes carrying re-emission tokens are kept apart rather than dropped:
   * they are eligible and will stay eligible, and only the node's own gap stops them today.
   *
   * Says nothing about whether a box is still unspent — that is one batch read, made against the
   * ids this returns.
   */
  def sortByAge(boxes: Seq[node.model.NodeBox], threshold: Int, network: NetworkType,
                protocol: ProtocolBoxes): (Set[String], Set[String]) = {
    // Dropped before anything else looks at them: this protocol's own boxes are not revenue, and
    // taking one costs state rather than earning ERG.
    val due = boxes.filter(box => box.creationHeight <= threshold && !protocol.owns(box))
    val (blocked, open) = due.partition(box => network == NetworkType.MAINNET &&
      isReEmission(box.ergoTree, box.assets.map(_.tokenId)))
    open.map(_.boxId).toSet -> blocked.map(_.boxId).toSet
  }

  /** Serialized bytes one box adds to a sweep: its input, and its successor when it has one. */
  private def sweptBytes(candidate: RentCandidate): Long =
    InputBytes + (if (candidate.recreates) candidate.box.bytes.length.toLong else 0L)

  /** What one box adds to the block's cost, on the same accounting as [[estimatedCost]]. */
  private def sweptCost(candidate: RentCandidate, params: BlockchainParameters): Long =
    params.getInputCost.toLong + Constants.StorageContractCost +
      (if (candidate.recreates) params.getOutputCost.toLong else 0L)

  /**
   * An input with no proof: thirty-two bytes of box id, an empty proof length, and one context
   * variable naming an output. Measured at fifty-four bytes a box including a successor.
   */
  private final val InputBytes = 40L

  /**
   * As many boxes as the budget affords, richest first.
   *
   * Sized here rather than built and then dropped: a sweep over its share of the block would cost a
   * whole assembly to discover, and the boxes it leaves behind stay collectable next block.
   */
  def fitting(candidates: Seq[RentCandidate], budget: transactions.candidate.CandidateBudget,
              params: BlockchainParameters): Seq[RentCandidate] = {
    var bytes = InputBytes * 2
    var cost = 10000L + params.getOutputCost.toLong
    candidates.sortBy(candidate => (-candidate.proceedsErg, candidate.box.id.toString))
      .take(MaxBoxes)
      .takeWhile { candidate =>
        val fits = bytes + sweptBytes(candidate) <= budget.maxBytes &&
          cost + sweptCost(candidate, params) <= budget.maxCost
        if (fits) {
          bytes += sweptBytes(candidate)
          cost += sweptCost(candidate, params)
        }
        fits
      }
  }

  /**
   * Sweep several expired boxes into one fee-less transaction for this miner's own block.
   *
   * Outputs are laid out collection first, then one recreation per funded box, then the tokens the
   * underfunded ones carried. A funded input names its own recreation in variable 127; an
   * underfunded one may name any output, because its branch never looks at what it names.
   */
  def build(ctx: BlockchainContext,
            wallet: NodeWallet,
            candidates: Seq[RentCandidate],
            blockHeight: Int,
            useTrueProp: Boolean): Option[CandidateBundle] = {
    if (candidates.isEmpty) None
    else if (candidates.size > MaxBoxes) {
      logger.warn(s"Refusing a rent collection of ${candidates.size} boxes, over the $MaxBoxes limit")
      None
    } else Try(bundled(ctx, wallet, candidates, blockHeight, useTrueProp)) match {
      case Success(bundle) => Some(bundle)
      case Failure(ex) =>
        logger.warn(s"Could not build a rent collection of ${candidates.size} boxes: ${ex.getMessage}")
        None
    }
  }

  private def bundled(ctx: BlockchainContext,
                      wallet: NodeWallet,
                      candidates: Seq[RentCandidate],
                      blockHeight: Int,
                      useTrueProp: Boolean): CandidateBundle = {
    val tx = assembled(ctx, wallet, candidates, blockHeight, useTrueProp)
    val signed = new SignedTransactionImpl(ctx.asInstanceOf[BlockchainContextBase], tx,
      estimatedCost(candidates.size, tx.outputCandidates.size,
        candidates.map(_.box.tokens.size).sum + tx.outputCandidates.map(_.additionalTokens.length).sum,
        ctx))

    val entry = CapitalEntry(CapitalOrigin.StorageRent,
      InputUTXO(new InputBoxImpl(tx.outputs.head)), parentTxId = signed.getId)
    CandidateBundle(
      Vector(CandidateTx(signed.getId, signed.toJson(false, false), Kind,
        RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
        signed.getCost.toLong, RollupExecution.signedLeaf(signed))),
      capital = Seq(entry))
  }

  /**
   * The transaction itself, before it is wrapped for the candidate path.
   *
   * Separate so a spec can put every input through Ergo's own rule: nothing rejects a malformed
   * rent collection on the way out, because nothing signs it.
   */
  private[transactions] def assembled(ctx: BlockchainContext,
                                      wallet: NodeWallet,
                                      candidates: Seq[RentCandidate],
                                      blockHeight: Int,
                                      useTrueProp: Boolean): ErgoLikeTransaction = {
    // Defended here as well as in the plan: a candidate can be constructed without going through
    // the plan at all, and either of these costs more than the sweep is worth.
    require(!candidates.exists(c => blockedByReEmission(c.box, ctx.getNetworkType)),
      "a re-emission box cannot be spent through the storage-rent rule")
    val protocol = ProtocolBoxes(ctx)
    require(!candidates.exists(c => protocol.owns(c.box)),
      "a rent collection cannot take one of this protocol's own boxes")

    val tokens = mergeTokens(candidates.flatMap(_.proceedsTokens))
    val tokenCost = if (tokens.isEmpty) 0L else UTXO.MIN_CHANGE
    val proceeds = candidates.map(_.proceedsErg).sum - tokenCost
    require(proceeds > 0L, s"a rent collection realizing $proceeds nanoERG is not worth building")

    // Collection first so an underfunded input has a stable output to name; recreations follow in
    // the order their boxes are swept, which is how each one finds its own index.
    val collection = UTXO(CandidateCapital.collectionContract(wallet, useTrueProp), proceeds)
      .setCreationHeight(blockHeight)
    val recreations = candidates.filter(_.recreates).map { candidate =>
      val fee = candidate.action.asInstanceOf[RentAction.Collect].fee
      // Everything but value and creation height carries through, which is what the rule compares.
      UTXO(candidate.box.contract, candidate.box.value - fee, candidate.box.tokens,
        candidate.box.registers).setCreationHeight(blockHeight)
    }
    val tokenBox =
      if (tokens.isEmpty) Seq.empty[UTXO]
      else Seq(UTXO(wallet.contract, tokenCost, tokens).setCreationHeight(blockHeight))

    val outputs = (Seq(collection) ++ recreations ++ tokenBox).map(candidateOf(ctx, _))
    var nextRecreation = 1
    val inputs = candidates.map { candidate =>
      val named = if (candidate.recreates) {
        val idx = nextRecreation
        nextRecreation += 1
        idx
      } else 0
      Input(candidate.box.input.asInstanceOf[InputBoxImpl].getErgoBox.id,
        ProverResult(Array.emptyByteArray,
          ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(named.toShort)))))
    }

    // One line per input, because the node reports a refusal by input index and says nothing about
    // which box it was or which branch it took. Without this, `#6 => false` cannot be read at all.
    if (logger.isDebugEnabled) {
      val params = ctx.getDataSource.getParameters
      var recreation = 1
      candidates.zipWithIndex.foreach { case (candidate, i) =>
        val named = if (candidate.recreates) { val n = recreation; recreation += 1; n } else 0
        // Full id and creation height, so a refusal can be taken straight to the node and asked
        // about: the two things that decide the branch are the box's size and its age.
        logger.debug(f"rent sweep #$i%-3d ${candidate.box.id.toString} " +
          f"value=${candidate.box.value}%16d bytes=${candidate.box.bytes.length}%5d " +
          f"fee=${storageFee(candidate.box, params)}%14d " +
          f"created=${candidate.box.input.asInstanceOf[InputBoxImpl].getCreationHeight}%8d " +
          f"age=${blockHeight - candidate.box.input.asInstanceOf[InputBoxImpl].getCreationHeight}%8d " +
          f"${if (candidate.recreates) "collect" else "claim  "} names=$named " +
          f"tokens=${candidate.box.tokens.size}")
      }
      logger.debug(s"rent sweep: ${candidates.size} inputs, ${outputs.size} outputs, " +
        s"${recreation - 1} recreation(s), tokenBox=${tokenBox.nonEmpty}, proceeds=$proceeds")
    }

    new ErgoLikeTransaction(inputs.toIndexedSeq, IndexedSeq.empty, outputs.toIndexedSeq)
  }

  /**
   * What the block will be charged for this transaction, on the node's own accounting.
   *
   * Computed rather than measured: nothing here runs a script, so there is no reduction to read a
   * cost off, and the interpreter charges a flat `StorageContractCost` for an input it accepts this
   * way. Everything else is the per-transaction, per-input, per-output and per-asset cost the node
   * adds before validation starts, so this is exact for a token-free sweep and slightly over for
   * one carrying tokens — and overstating only costs package space.
   */
  private def estimatedCost(inputs: Int, outputs: Int, assets: Int, ctx: BlockchainContext): Int = {
    val params = ctx.getDataSource.getParameters
    val perInput = params.getInputCost.toLong + Constants.StorageContractCost
    // TODO: Token cost overstated here, maybe fix later
    (10000L + perInput * inputs + params.getOutputCost.toLong * outputs +
      2L * assets * params.getTokenAccessCost).toInt
  }

  private def candidateOf(ctx: BlockchainContext, box: UTXO): ErgoBoxCandidate =
    box.toInput(ctx, org.ergoplatform.sdk.ErgoId.create("00" * 32), 0.toShort)
      .input.asInstanceOf[InputBoxImpl].getErgoBox

  /** Tokens summed per id, since one id may arrive on several claimed boxes. */
  private def mergeTokens(tokens: Seq[Token]): Seq[Token] =
    transactions.rollups.RollupTransactions.mergeTokens(tokens)
}
