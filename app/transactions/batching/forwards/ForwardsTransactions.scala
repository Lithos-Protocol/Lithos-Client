package transactions.batching.forwards

import forwards.contracts.{DFContracts, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import mutations.NodeWallet
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction, UnsignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import sigma.util.NBitsUtils
import transactions.batching.Batcher
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/** An epoch a spend can record, and the difficulty it writes for it. */
final case class DFRecording(epoch: Int, difficulty: Long)

/** The accumulator a spend takes as input 0: its box and the state its registers and balance hold. */
final case class DFAccState(box: InputUTXO, lastEpoch: Int, ring: IndexedSeq[Long], lit: Long)

object DFAccState {
  def apply(acc: DFAccumulator, box: InputUTXO): DFAccState = DFAccState(box, acc.lastEpoch, acc.ring, acc.lit)
}

/**
 * One signed spend of the accumulator.
 *
 * @param position the position it settled, or None for a recording on its own
 * @param recorded whether it recorded an epoch, and so took the recording reward
 * @param spent    the accumulator it spent
 * @param next     the accumulator it leaves at output 0
 */
final case class DFStep(signed: SignedTransaction, position: Option[DFPosition], recorded: Boolean,
                        spent: DFAccState, next: DFAccState) {

  def candidateTx: CandidateTx = CandidateTx.signed(signed, ForwardsTransactions.Kind)
}

/**
 * What one pass over the due positions produced.
 *
 * @param steps         signed settlements in spend order, each on the accumulator the one before left
 * @param unbuildable   box ids of positions that were due but could not be built
 * @param recordingLeft the recording no settlement carried, still to be made on its own
 * @param cutShort      whether the pass stopped at its limit or deadline with due positions left
 */
final case class DFRun(steps: Vector[DFStep], unbuildable: Vector[String], recordingLeft: Option[DFRecording],
                       cutShort: Boolean) {
  def last: Option[DFStep] = steps.lastOption
}

/**
 * Builds spends of the difficulty accumulator: a recording on its own, and settlements of positions, which
 * record in the same spend when an epoch is due. The accumulator is input 0 and output 0 of each.
 *
 * Fees are a parameter: 0 for this miner's own block, the broadcast fee otherwise. Rewards are outputs to this
 * miner's wallet, never the fee, since fees in a Lithos block go to its lender.
 */
object ForwardsTransactions {

  private val logger: Logger = LoggerFactory.getLogger("ForwardsTransactions")

  /** Names the builder, so a refused candidate says where the transaction came from. */
  final val Kind = "difficulty-forward"

  /**
   * nanoERG each payout box holds, or the node's minimum for its size when a large key needs more. At 360
   * nanoERG a byte a genuine position's 0.004 ERG covers both payouts, the fee and the reward box whatever its
   * keys, since both have to fit in one 4,096-byte position.
   */
  final val PayoutErg: Long = UTXO.MIN_CHANGE

  /** What a box gains when it becomes an output and counts toward the node's minimum: a transaction id and index. */
  private final val RefBytes = 34

  /**
   * The recording a spend signed in `ctx` can make, valid in the block after the tip.
   *
   * The contract reads the epoch from `CONTEXT.headers(0)`. The node validates the next block with the tip
   * there, but appkit signs with the tip as pre-header and the header below it as `headers(0)`. Both agree
   * only when the tip is not an epoch's first block; there, None, and the recording waits one block.
   */
  def recordingAt(ctx: BlockchainContext): Option[DFRecording] = ctx match {
    case impl: BlockchainContextImpl if impl.getHeaders.size >= 2 =>
      val headers = impl.getHeaders
      val tip = headers.get(0)
      val signer = headers.get(1)
      val epoch = DFMath.epochOf(tip.getHeight)
      val difficulty = NBitsUtils.decodeCompactBits(signer.getNBits)
      if (signer.getHeight != tip.getHeight - 1 || DFMath.epochOf(signer.getHeight) != epoch ||
        difficulty <= 0 || !difficulty.isValidLong) None
      else Some(DFRecording(epoch, difficulty.toLong))
    // Only appkit's own context lists headers; any other cannot say which header a signature would read
    case _ => None
  }

  /** Whether recording `epoch` in a spend that settles `p` would turn its settlement into a refund. */
  def overwrittenBy(p: DFPosition, acc: DFAccState, epoch: Int): Boolean = {
    val t = p.terms
    DFMath.windowComplete(acc.lastEpoch, t.windowStart, t.windowLength) &&
      !DFMath.overwritten(acc.lastEpoch, t.windowStart) && DFMath.overwritten(epoch, t.windowStart)
  }

  /**
   * Records `recording` on its own. `funding` pays `fee` and the box the reward and change go to, since the
   * accumulator's own value is fixed. The reward is 5 LIT, or the whole balance when less is left.
   */
  def record(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, acc: DFAccState,
             recording: DFRecording, funding: Seq[InputUTXO], fee: Long): DFStep = {
    require(recording.epoch > acc.lastEpoch,
      s"epoch ${recording.epoch} is not after the accumulator's last, ${acc.lastEpoch}")
    require(funding.nonEmpty, "a recording on its own needs a wallet input for its change box")
    val ring = DFMath.recordedRing(acc.ring, acc.lastEpoch, recording.epoch, recording.difficulty)
    val lit = acc.lit - math.min(DFHelpers.RECORD_REWARD, acc.lit)
    val unsigned = TxBuilder(ctx)
      .setInputs(acc.box +: funding: _*)
      .setOutputs(successor(contracts, acc, recording.epoch, ring, lit))
      .buildTx(fee, wallet.p2pk)
    signedStep(ctx, wallet, unsigned, None, recorded = true, acc, recording.epoch, ring, lit)
  }

  /**
   * Settles `position`, recording `recording` in the same spend when one is given.
   *
   * Outputs: the accumulator, the long payout, the short payout, then this miner's reward. A side owed nothing
   * gets no output, and when that is the long side the reward takes output 1, which the position leaves free.
   */
  def settle(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, acc: DFAccState,
             position: DFPosition, positionBox: InputUTXO, recording: Option[DFRecording], fee: Long): DFStep = {
    val t = position.terms
    recording.foreach(r => require(r.epoch > acc.lastEpoch,
      s"epoch ${r.epoch} is not after the accumulator's last, ${acc.lastEpoch}"))
    val (lastEpoch, ring) = recording.fold((acc.lastEpoch, acc.ring))(r =>
      (r.epoch, DFMath.recordedRing(acc.ring, acc.lastEpoch, r.epoch, r.difficulty)))
    require(DFMath.windowComplete(lastEpoch, t.windowStart, t.windowLength),
      s"epoch ${position.windowEnd} is not recorded yet")

    val (longOwed, shortOwed) = DFMath.owed(ring, lastEpoch, t.windowStart, t.windowLength, t.strike, t.notional)
    val taken = if (recording.isDefined) DFHelpers.RECORD_REWARD else 0L
    val lit = Math.addExact(acc.lit, DFHelpers.ACC_DEPOSIT - taken)
    // Both sides' rewards less the deposit, plus the recording reward. Never negative for a position DFBoxes reads
    val rewardLit = position.lit - longOwed - shortOwed - DFHelpers.ACC_DEPOSIT + taken
    require(rewardLit >= 0, s"position ${position.boxId} holds ${position.lit} LIT, short of what it owes")

    val litId = contracts.litId
    // Measured at the position's whole value, which no output here exceeds, so the length is an upper bound
    def atLeastMinimum(box: UTXO, floor: Long): UTXO =
      box.setValue(math.max(floor, nodeMinimum(ctx, box.setValue(positionBox.value))))
    def payout(owed: Long, to: Contract): Option[UTXO] =
      if (owed > 0) Some(atLeastMinimum(UTXO(to, PayoutErg, Seq(Token(litId, owed))), PayoutErg)) else None
    val longOut = payout(longOwed, position.long)
    val shortOut = payout(shortOwed, position.short)
    val rewardErg = positionBox.value - fee - longOut.map(_.value).sum - shortOut.map(_.value).sum
    val reward = UTXO(wallet.contract, rewardErg, if (rewardLit > 0) Seq(Token(litId, rewardLit)) else Seq.empty)
    require(rewardErg >= atLeastMinimum(reward, 0L).value,
      s"position ${position.boxId} holds ${positionBox.value} nanoERG, short of its payouts, the fee and a reward box")
    val paid = if (longOut.isDefined) longOut.toSeq ++ shortOut.toSeq :+ reward else reward +: shortOut.toSeq

    val unsigned = TxBuilder(ctx)
      .setInputs(acc.box, positionBox)
      .setOutputs(successor(contracts, acc, lastEpoch, ring, lit) +: paid: _*)
      .buildTx(fee, wallet.p2pk)
    signedStep(ctx, wallet, unsigned, Some(position), recording.isDefined, acc, lastEpoch, ring, lit)
  }

  /**
   * Settles every due position in `positions`, each on the accumulator the one before left, and carries
   * `recording` in the first settlement it does not turn into a refund. Positions that recording would
   * overwrite go first and settle without it, so a late settlement still pays out rather than refunds.
   *
   * A position that cannot be built is named and passed over. Stops after `limit` settlements or at `deadline`.
   */
  def settleAll(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, opening: DFAccState,
                positions: Seq[(DFPosition, InputUTXO)], recording: Option[DFRecording], fee: Long, limit: Int,
                deadline: Deadline): DFRun = {
    var acc = opening
    var pending = recording.filter(_.epoch > opening.lastEpoch)
    var steps = Vector.empty[DFStep]
    var unbuildable = Vector.empty[String]
    val ordered = positions.sortBy { case (p, _) =>
      (!pending.exists(r => overwrittenBy(p, opening, r.epoch)), p.windowEnd, p.boxId)
    }
    val due = ordered.filter { case (p, _) =>
      DFMath.windowComplete(pending.fold(opening.lastEpoch)(_.epoch), p.terms.windowStart, p.terms.windowLength)
    }
    val left = due.iterator
    while (left.hasNext && steps.size < limit && !deadline.isOverdue()) {
      val (position, box) = left.next()
      val carried = pending.filterNot(r => overwrittenBy(position, acc, r.epoch))
      val lastAfter = carried.fold(acc.lastEpoch)(_.epoch)
      if (DFMath.windowComplete(lastAfter, position.terms.windowStart, position.terms.windowLength))
        Try(settle(ctx, wallet, contracts, acc, position, box, carried, fee)) match {
          case Success(step) =>
            steps :+= step
            acc = step.next
            if (carried.isDefined) pending = None
          case Failure(ex) =>
            logger.warn(s"Could not settle difficulty forward ${position.boxId}, passing over it: ${ex.getMessage}")
            unbuildable :+= position.boxId
        }
    }
    DFRun(steps, unbuildable, pending, cutShort = left.hasNext)
  }

  /** The node's minimum value for `box` as an output, from its serialized length. */
  private def nodeMinimum(ctx: BlockchainContext, box: UTXO): Long =
    (box.toOutBox(ctx).getBytesWithNoRef.length + RefBytes) * ctx.getDataSource.getParameters.getMinValuePerByte.toLong

  /** The accumulator after a spend: its own script, value and NFT, with the new registers and balance. */
  private def successor(contracts: DFContracts, acc: DFAccState, lastEpoch: Int, ring: IndexedSeq[Long],
                        lit: Long): UTXO =
    UTXO(acc.box.contract, acc.box.value,
      acc.box.tokens.head +: (if (lit > 0) Seq(Token(contracts.litId, lit)) else Seq.empty[Token]),
      DifficultyForwardContracts.accumulatorRegisters(lastEpoch, ring))

  private def signedStep(ctx: BlockchainContext, wallet: NodeWallet, unsigned: UnsignedTransaction,
                         position: Option[DFPosition], recorded: Boolean, acc: DFAccState, lastEpoch: Int,
                         ring: IndexedSeq[Long], lit: Long): DFStep = {
    // Signing runs both contracts, so a spend either contract refuses fails here rather than at the node
    val signed = Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
    val next = InputUTXO(signed.getOutputsToSpend.get(0))
    DFStep(signed, position, recorded, acc, DFAccState(next, lastEpoch, ring, lit))
  }
}
