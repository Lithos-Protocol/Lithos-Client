package transactions.rent

import mutations.NodeWallet
import org.ergoplatform.appkit.impl.{BlockchainContextBase, InputBoxImpl, SignedTransactionImpl}
import org.ergoplatform.appkit.{BlockchainContext, BlockchainParameters, NetworkType, SignedTransaction}
import org.ergoplatform.wallet.protocol.Constants
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import org.slf4j.{Logger, LoggerFactory}
import sigma.ast.ShortConstant
import sigma.interpreter.{ContextExtension, ProverResult}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{Contract, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

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
  /**
   * The box is recreated `taken` lighter, and only `taken` is ours. That is the whole storage fee,
   * or less when the fee would leave the recreation under the minimum a box of its size may hold.
   */
  final case class Collect(taken: Long) extends RentAction

  /** The box cannot cover its fee, so the rule short-circuits and it is taken whole. */
  case object Claim extends RentAction
}

/**
 * One expired box and the branch it falls on.
 *
 * `proceedsErg` is what a collection actually realizes: what the recreation gives up on the funded
 * branch, the whole box on the underfunded one. Tokens only ever come from the underfunded branch,
 * because a recreation has to preserve the token register exactly.
 */
final case class RentCandidate(box: InputUTXO, action: RentAction) {
  def proceedsErg: Long = action match {
    case RentAction.Collect(taken) => taken
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
 * is taken outright instead, tokens included. No two inputs may name the same output.
 *
 * The sweep is assembled rather than signed: a prover would have to satisfy each box's own script.
 * When its claims need more than one proceeds output, a second, signed transaction folds them back.
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
   * Distinct tokens one residue output carries. Twenty keep it near 900 bytes, well inside both the
   * box size ceiling and what `UTXO.MIN_CHANGE` pays for, whatever the token amounts.
   */
  final val TokensPerResidue: Int = 20

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

  /**
   * The fee exactly as `checkExpiredBox` computes it: `storageFeeFactor * box.bytes.length` in
   * `Int`, which wraps. At mainnet's factor it is negative from 1,718 to 3,435 bytes, and small but
   * positive again from 3,436 bytes up to the 4,096-byte box ceiling.
   */
  def storageFee(sizeBytes: Int, params: BlockchainParameters): Long =
    (params.getStorageFeeFactor * sizeBytes).toLong

  def storageFee(box: InputUTXO, params: BlockchainParameters): Long =
    storageFee(box.bytes.length, params)

  /**
   * Serialized size of `box` recreated for `blockHeight`, at the widest output index a sweep can
   * give it. Only the value and creation height change and the value only shrinks, so the node
   * never prices the real recreation at more than this.
   */
  def successorBytes(box: InputUTXO, blockHeight: Int): Int = {
    val b = box.input.asInstanceOf[InputBoxImpl].getErgoBox
    new ErgoBoxCandidate(b.value, b.ergoTree, blockHeight, b.additionalTokens, b.additionalRegisters)
      .toBox(b.transactionId, Short.MaxValue).bytes.length
  }

  /**
   * Which branch a box falls on, ignoring its age, or nothing when collecting it cannot earn.
   *
   * A recreation keeps the larger of `value - fee` and the consensus minimum for its own size, and
   * the rest is taken. A fee the node wraps negative demands a recreation richer than the box, so
   * collecting it would only cost this miner.
   */
  def decide(value: Long, sizeBytes: Int, successorBytes: Int,
             params: BlockchainParameters): Option[RentAction] = {
    val fee = storageFee(sizeBytes, params)
    if (fee <= 0L) None
    else if (value <= fee) Some(RentAction.Claim)
    else if (successorBytes > ErgoBox.MaxBoxSize) None
    else {
      val kept = math.max(value - fee, params.getMinValuePerByte.toLong * successorBytes)
      if (kept < value) Some(RentAction.Collect(value - kept)) else None
    }
  }

  /**
   * Which branch this box falls on at `blockHeight`, or nothing when it cannot be collected.
   *
   * `creationHeight` is the box's own declared height (R3) as the node reports it, which is what the
   * rule measures age from, not the height the box was included at.
   */
  def plan(box: InputUTXO, creationHeight: Int, blockHeight: Int, params: BlockchainParameters,
           network: NetworkType, protocol: ProtocolBoxes): Option[RentAction] = {
    if (blockHeight.toLong - creationHeight < StoragePeriod.toLong) None
    else if (protocol.owns(box)) None
    else if (blockedByReEmission(box, network)) None
    else decide(box.value, box.bytes.length, successorBytes(box, blockHeight), params)
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

  // ─── layout ───────────────────────────────────────────────────────────────

  /** Outputs holding the tokens the claimed boxes carried, `TokensPerResidue` distinct ids each. */
  private[rent] def residueOutputs(distinctTokens: Int): Int =
    (distinctTokens + TokensPerResidue - 1) / TokensPerResidue

  /**
   * Proceeds outputs a sweep carries. Every input names an output of its own: a recreation names
   * itself, and a claim names a residue output or a proceeds output, so there is one proceeds
   * output per claim the residues cannot absorb, and always at least one.
   */
  private[rent] def proceedsOutputs(claims: Int, residues: Int): Int =
    math.max(1, claims - residues)

  /** The proceeds output count and the token groups, one residue output each, for these boxes. */
  private def layout(candidates: Seq[RentCandidate]): (Int, Seq[Seq[Token]]) = {
    val residues = mergeTokens(candidates.flatMap(_.proceedsTokens)).grouped(TokensPerResidue).toSeq
    proceedsOutputs(candidates.count(!_.recreates), residues.size) -> residues
  }

  /** Whether these boxes realize enough to fund every proceeds output and residue they need. */
  private def funded(candidates: Seq[RentCandidate], floor: Long): Boolean = {
    val (parts, residues) = layout(candidates)
    candidates.map(_.proceedsErg).sum - residues.size * UTXO.MIN_CHANGE >= parts * floor
  }

  /**
   * What each proceeds output past the first holds: the consensus minimum for a box at `contract`,
   * sized at the widest value and index it could serialize with. The merge spends it straight back.
   */
  def proceedsFloor(contract: Contract, blockHeight: Int, params: BlockchainParameters): Long = {
    val probe = new ErgoBoxCandidate(Long.MaxValue, contract.ergoTree, blockHeight)
      .toBox(scorex.util.bytesToId(Array.fill[Byte](32)(0)), Short.MaxValue)
    params.getMinValuePerByte.toLong * probe.bytes.length
  }

  // ─── sizing ───────────────────────────────────────────────────────────────

  /**
   * An input with no proof: thirty-two bytes of box id, an empty proof length, and one context
   * variable naming an output.
   */
  private final val InputBytes = 40L

  /** One output at a P2PK or TrueProp script with no tokens: at most 52 bytes inside a transaction. */
  private final val ProceedsBytes = 52L

  /** A merge input: box id, proof length, a 56-byte Schnorr proof, and an empty extension. */
  private final val MergeInputBytes = 90L

  /**
   * Verifying one P2PK merge input, on top of the per-input cost the node charges every input.
   * Measured at 403; the margin keeps a fitted sweep from building over its budget.
   */
  private[rent] final val MergeInputCost = 500L

  /** Serialized bytes one box adds to a sweep: its input, and its successor when it has one. */
  private def sweptBytes(candidate: RentCandidate): Long =
    InputBytes + (if (candidate.recreates) candidate.box.bytes.length.toLong else 0L)

  /**
   * What one box adds to the sweep's cost, on the same accounting as [[estimatedCost]]: its input,
   * its successor, and its tokens read once going in and once coming out, each counted twice.
   */
  private def sweptCost(candidate: RentCandidate, params: BlockchainParameters): Long =
    params.getInputCost.toLong + Constants.StorageContractCost +
      (if (candidate.recreates) params.getOutputCost.toLong else 0L) +
      4L * candidate.box.tokens.size * params.getTokenAccessCost

  /** A residue token: its 32-byte id, listed once per transaction, and its index and amount. */
  private final val ResidueTokenBytes = 42L

  /** Bytes and cost of everything a layout adds beyond the boxes themselves, merge included. */
  private def layoutBytes(parts: Int, residues: Int, distinctTokens: Int): Long =
    InputBytes + (parts + residues) * ProceedsBytes + distinctTokens * ResidueTokenBytes +
      (if (parts > 1) InputBytes + parts * MergeInputBytes + ProceedsBytes else 0L)

  private def layoutCost(parts: Int, residues: Int, params: BlockchainParameters): Long =
    10000L + (parts + residues) * params.getOutputCost.toLong +
      (if (parts > 1)
        10000L + parts * (params.getInputCost.toLong + MergeInputCost) + params.getOutputCost.toLong
      else 0L)

  /**
   * As many boxes as the budget affords, richest first, merge included, then trimmed from the
   * poorest end until what they realize funds every output they need.
   *
   * Sized here rather than built and then dropped: a sweep over its share of the block would cost a
   * whole assembly to discover, and the boxes it leaves behind stay collectable next block. Funding
   * is decided over the whole set because a token-carrying claim pays its share of a residue only
   * alongside others. `floor` is [[proceedsFloor]] for the script the proceeds will sit at.
   */
  def fitting(candidates: Seq[RentCandidate], budget: transactions.candidate.CandidateBudget,
              params: BlockchainParameters, floor: Long): Seq[RentCandidate] = {
    var bytes = 0L
    var cost = 0L
    var claims = 0
    var tokenIds = Set.empty[String]
    val chosen = candidates.sortBy(candidate => (-candidate.proceedsErg, candidate.box.id.toString))
      .take(MaxBoxes)
      .takeWhile { candidate =>
        val nextClaims = claims + (if (candidate.recreates) 0 else 1)
        val nextTokens = tokenIds ++ candidate.proceedsTokens.map(_.id.toString)
        val residues = residueOutputs(nextTokens.size)
        val parts = proceedsOutputs(nextClaims, residues)
        val nextBytes = bytes + sweptBytes(candidate)
        val nextCost = cost + sweptCost(candidate, params)
        val fits = nextBytes + layoutBytes(parts, residues, nextTokens.size) <= budget.maxBytes &&
          nextCost + layoutCost(parts, residues, params) <= budget.maxCost
        if (fits) {
          bytes = nextBytes
          cost = nextCost
          claims = nextClaims
          tokenIds = nextTokens
        }
        fits
      }.toVector
    trimmed(chosen, floor)
  }

  /** The longest richest-first prefix of `chosen` that funds its own outputs. */
  private def trimmed(chosen: Seq[RentCandidate], floor: Long): Seq[RentCandidate] = {
    var kept = chosen.toVector
    while (kept.nonEmpty && !funded(kept, floor)) kept = kept.init
    kept
  }

  // ─── building ─────────────────────────────────────────────────────────────

  /** A collection for one block, and the boxes the node's own rule refused while building it. */
  final case class Collection(bundle: Option[CandidateBundle], refused: Seq[RentCandidate])

  private val NoCollection = Collection(None, Seq.empty)

  /**
   * Sweep several expired boxes into this miner's own block, as a fee-less sweep and, when its claims
   * needed several proceeds outputs, a merge folding them into one box. That box is the capital.
   * Every input is put through the node's own rule first; see [[verifiedCollection]].
   */
  def collect(ctx: BlockchainContext,
              wallet: NodeWallet,
              candidates: Seq[RentCandidate],
              blockHeight: Int,
              useTrueProp: Boolean): Collection = {
    if (candidates.isEmpty) NoCollection
    else if (candidates.size > MaxBoxes) {
      logger.warn(s"Refusing a rent collection of ${candidates.size} boxes, over the $MaxBoxes limit")
      NoCollection
    } else Try(verifiedCollection(ctx, wallet, candidates, blockHeight, useTrueProp)) match {
      case Success(collection) => collection
      case Failure(ex) =>
        logger.warn(s"Could not build a rent collection of ${candidates.size} boxes: ${ex.getMessage}")
        NoCollection
    }
  }

  /** [[collect]] without the refusals, for callers that only want the bundle. */
  def build(ctx: BlockchainContext,
            wallet: NodeWallet,
            candidates: Seq[RentCandidate],
            blockHeight: Int,
            useTrueProp: Boolean): Option[CandidateBundle] =
    collect(ctx, wallet, candidates, blockHeight, useTrueProp).bundle

  /**
   * Assemble, put every input through [[RentVerifier]], and when some are refused assemble once more
   * without them. The node refuses a whole transaction for one bad input, so this is what keeps one
   * box from costing the rest of the sweep every block.
   *
   * Refused boxes are reported for eviction only when another input in the same sweep passed: a sweep
   * refused whole points at this builder rather than at its boxes. An input the check cannot run on
   * is offered as it is, leaving the node to judge it.
   */
  private def verifiedCollection(ctx: BlockchainContext,
                                 wallet: NodeWallet,
                                 candidates: Seq[RentCandidate],
                                 blockHeight: Int,
                                 useTrueProp: Boolean): Collection = {
    val params = ctx.getDataSource.getParameters
    val floor = proceedsFloor(CandidateCapital.collectionContract(wallet, useTrueProp), blockHeight, params)

    def attempt(chosen: Seq[RentCandidate]): (ErgoLikeTransaction, IndexedSeq[RentVerdict]) = {
      val tx = assembled(ctx, wallet, chosen, blockHeight, useTrueProp)
      val verdicts = RentVerifier.verdicts(tx, chosen.map(ergoBoxOf).toIndexedSeq, blockHeight, params)
      verdicts.collectFirst { case RentVerdict.Unverifiable(reason) => reason }.foreach { reason =>
        logger.error(s"Could not run the node's rent rule on a sweep of ${chosen.size} boxes, so it " +
          s"goes out unchecked: $reason")
      }
      tx -> verdicts
    }

    val (tx, verdicts) = attempt(candidates)
    val refusedAt = verdicts.indices.filter(i => verdicts(i) == RentVerdict.Refused)
    if (refusedAt.isEmpty) Collection(Some(bundled(ctx, wallet, candidates, tx, blockHeight, useTrueProp)), Seq.empty)
    else if (!verdicts.contains(RentVerdict.Accepted)) {
      logger.error(s"The node's rent rule refuses all ${refusedAt.size} checked inputs of a sweep at " +
        s"$blockHeight. Nothing is offered and nothing evicted: this points at the builder, not the boxes")
      NoCollection
    } else {
      val refused = refusedAt.map(candidates)
      refused.foreach(candidate => logger.warn(s"Node's rent rule refuses ${candidate.box.id.toString} " +
        s"(value=${candidate.box.value}, bytes=${candidate.box.bytes.length}, " +
        s"created=${ergoBoxOf(candidate).creationHeight}, action=${candidate.action}); it is no longer offered"))
      val refusedSet = refusedAt.toSet
      val rest = trimmed(candidates.indices.filterNot(refusedSet.contains).map(candidates), floor)
      if (rest.isEmpty) Collection(None, refused)
      else {
        val (retryTx, again) = attempt(rest)
        if (again.contains(RentVerdict.Refused)) {
          logger.warn(s"A rent sweep rebuilt without ${refused.size} refused boxes was refused again; " +
            "nothing is offered this block")
          Collection(None, refused)
        } else Collection(Some(bundled(ctx, wallet, rest, retryTx, blockHeight, useTrueProp)), refused)
      }
    }
  }

  private def ergoBoxOf(candidate: RentCandidate): ErgoBox =
    candidate.box.input.asInstanceOf[InputBoxImpl].getErgoBox

  private def bundled(ctx: BlockchainContext,
                      wallet: NodeWallet,
                      candidates: Seq[RentCandidate],
                      tx: ErgoLikeTransaction,
                      blockHeight: Int,
                      useTrueProp: Boolean): CandidateBundle = {
    val sweep = new SignedTransactionImpl(ctx.asInstanceOf[BlockchainContextBase], tx,
      estimatedCost(candidates.size, tx.outputCandidates.size,
        candidates.map(_.box.tokens.size).sum + tx.outputCandidates.map(_.additionalTokens.length).sum,
        ctx))

    val (parts, _) = layout(candidates)
    if (parts == 1) {
      val entry = CapitalEntry(CapitalOrigin.StorageRent,
        InputUTXO(new InputBoxImpl(tx.outputs.head)), parentTxId = sweep.getId)
      CandidateBundle(Vector(member(sweep)), capital = Seq(entry))
    } else {
      val merge = merged(ctx, wallet, tx, parts, blockHeight, useTrueProp)
      val entry = CapitalEntry(CapitalOrigin.StorageRent,
        InputUTXO(merge.getOutputsToSpend.get(0)), parentTxId = merge.getId)
      CandidateBundle(Vector(member(sweep), member(merge)), capital = Seq(entry))
    }
  }

  /**
   * The sweep itself, before it is wrapped for the candidate path.
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

    val params = ctx.getDataSource.getParameters
    val contract = CandidateCapital.collectionContract(wallet, useTrueProp)
    val floor = proceedsFloor(contract, blockHeight, params)
    val (parts, residueTokens) = layout(candidates)
    val proceeds = candidates.map(_.proceedsErg).sum - residueTokens.size * UTXO.MIN_CHANGE
    require(proceeds >= parts * floor,
      s"a rent collection realizing $proceeds nanoERG cannot fund $parts proceeds outputs of $floor")

    // Proceeds first, the first holding everything the others do not; then recreations, in the
    // order their boxes are swept; then the token residue at this miner's own key.
    val proceedsOuts = (0 until parts).map { i =>
      UTXO(contract, if (i == 0) proceeds - (parts - 1) * floor else floor).setCreationHeight(blockHeight)
    }
    val recreations = candidates.filter(_.recreates).map { candidate =>
      val taken = candidate.action.asInstanceOf[RentAction.Collect].taken
      // Everything but value and creation height carries through, which is what the rule compares.
      UTXO(candidate.box.contract, candidate.box.value - taken, candidate.box.tokens,
        candidate.box.registers).setCreationHeight(blockHeight)
    }
    val residues = residueTokens.map(tokens =>
      UTXO(wallet.contract, UTXO.MIN_CHANGE, tokens).setCreationHeight(blockHeight))
    val outputs = (proceedsOuts ++ recreations ++ residues).map(candidateOf(ctx, _))

    // A recreation names itself; claims take the proceeds outputs, then the residues, one each.
    val claimSlots = (0 until parts) ++ residues.indices.map(parts + recreations.size + _)
    var nextRecreation = parts
    var nextClaim = 0
    val named = candidates.map { candidate =>
      if (candidate.recreates) { nextRecreation += 1; nextRecreation - 1 }
      else { nextClaim += 1; claimSlots(nextClaim - 1) }
    }
    // The node refuses two inputs naming one output, and an index past the outputs falls through
    // to the box's own script, which an empty proof cannot satisfy.
    require(named.distinct.size == named.size && named.forall(i => i >= 0 && i < outputs.size),
      "every rent input has to name an output of its own")

    val inputs = candidates.zip(named).map { case (candidate, i) =>
      Input(candidate.box.input.asInstanceOf[InputBoxImpl].getErgoBox.id,
        ProverResult(Array.emptyByteArray,
          ContextExtension(Map(Constants.StorageIndexVarId -> ShortConstant(i.toShort)))))
    }

    // One line per input, because the node reports a refusal by input index and says nothing about
    // which box it was or which branch it took. Without this, `#6 => false` cannot be read at all.
    if (logger.isDebugEnabled) {
      candidates.zip(named).zipWithIndex.foreach { case ((candidate, n), i) =>
        // Full id and creation height, so a refusal can be taken straight to the node and asked
        // about: the two things that decide the branch are the box's size and its age.
        logger.debug(f"rent sweep #$i%-3d ${candidate.box.id.toString} " +
          f"value=${candidate.box.value}%16d bytes=${candidate.box.bytes.length}%5d " +
          f"fee=${storageFee(candidate.box, params)}%14d taken=${candidate.proceedsErg}%14d " +
          f"created=${candidate.box.input.asInstanceOf[InputBoxImpl].getCreationHeight}%8d " +
          f"age=${blockHeight - candidate.box.input.asInstanceOf[InputBoxImpl].getCreationHeight}%8d " +
          f"${if (candidate.recreates) "collect" else "claim  "} names=$n " +
          f"tokens=${candidate.box.tokens.size}")
      }
      logger.debug(s"rent sweep: ${candidates.size} inputs, ${outputs.size} outputs, " +
        s"$parts proceeds, ${recreations.size} recreation(s), ${residues.size} residue(s), proceeds=$proceeds")
    }

    val tx = new ErgoLikeTransaction(inputs.toIndexedSeq, IndexedSeq.empty, outputs.toIndexedSeq)
    // Nothing signs this, so the node's dust rule is checked here against the real serialization.
    val perByte = params.getMinValuePerByte.toLong
    tx.outputs.zipWithIndex.foreach { case (out, i) =>
      require(out.value >= out.bytes.length * perByte,
        s"rent output $i holds ${out.value} nanoERG, under the ${out.bytes.length * perByte} minimum")
    }
    tx
  }

  /**
   * Folds the sweep's proceeds outputs into one box at the same script, so the package's capital
   * is one input to the holding top-up however many claims the sweep carried. Fee-less, and it
   * balances to zero change.
   */
  private def merged(ctx: BlockchainContext, wallet: NodeWallet, sweep: ErgoLikeTransaction,
                     parts: Int, blockHeight: Int, useTrueProp: Boolean): SignedTransaction = {
    val contract = CandidateCapital.collectionContract(wallet, useTrueProp)
    val pieces = (0 until parts).map(i => InputUTXO(new InputBoxImpl(sweep.outputs(i))))
    // Proceeds outputs sit at this script and carry no tokens, which rules out every residue and a
    // recreation of anyone else's box.
    require(pieces.forall(p => p.contract.ergoTreeHex == contract.ergoTreeHex && p.tokens.isEmpty),
      "a rent merge may only spend the sweep's proceeds outputs")
    val total = pieces.foldLeft(0L)((n, piece) => Math.addExact(n, piece.value))
    val unsigned = TxBuilder(ctx)
      .setInputs(pieces: _*)
      .setOutputs(UTXO(contract, total).setCreationHeight(blockHeight))
      .buildTx(0L, wallet.p2pk)
    wallet.sign(unsigned)
  }

  private def member(signed: SignedTransaction): CandidateTx =
    CandidateTx(signed.getId, CandidateTx.signedJson(signed), Kind,
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      signed.getCost.toLong, RollupExecution.signedLeaf(signed))

  /**
   * What the block will be charged for the sweep, on the node's own accounting.
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
