package transactions.batching.forwards

import forwards.contracts.{DFContracts, DFTerms, DifficultyForwardContracts}
import forwards.{DFHelpers, DFMath}
import mutations.NodeWallet
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.{BlockchainContext, Parameters, UnsignedTransaction}
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

/**
 * What a user transaction needs from the wallet, and how to build it once the wallet has supplied that.
 *
 * @param value  nanoERG the wallet inputs must cover, change headroom included
 * @param tokens tokens they must cover
 */
final case class DFPlan(value: Long, tokens: Seq[Token], build: Seq[InputUTXO] => UnsignedTransaction)

/**
 * The epochs a fill's lead check reads. The node validates the next block with the tip as `CONTEXT.headers(0)`;
 * appkit signs with the header below the tip there. The two differ only when the tip is an epoch's first block.
 *
 * @param tip         the newest block, which the fill is mined after
 * @param nodeEpoch   the tip's epoch, which the node checks the window against
 * @param signerEpoch the epoch of the block below the tip, which a signature here checks it against
 */
final case class DFFillTiming(tip: Int, nodeEpoch: Int, signerEpoch: Int)

/**
 * What a settlement pays.
 *
 * @param records   whether it records the current epoch too, which pays the builder 5 LIT more
 * @param refund    whether the window has left the accumulator, so each side gets its margin back
 * @param owedLong  LIT to the long side, margin included
 * @param owedShort LIT to the short side
 * @param rewardLit LIT left to whoever builds it
 */
final case class DFSettlement(records: Boolean, refund: Boolean, owedLong: Long, owedShort: Long, rewardLit: Long)

/**
 * The difficulty forward transactions a user asks for: creating, filling and cancelling an offer, and
 * settling a position by hand. Each is a [[DFPlan]], built once the wallet has reserved its inputs, and
 * pays the broadcast fee, since none of these go into a block of this miner's own.
 *
 * {{{
 *   create   IN  wallet...                 OUT offer, [change], fee
 *   fill     IN  offer, wallet...          OUT position, [change], fee
 *   cancel   IN  offer, [wallet...]        OUT refund, [change], fee
 *   settle   IN  accumulator, position     OUT accumulator, long, short, reward, fee
 * }}}
 */
object ForwardsUserTransactions {

  final val TxFee: Long = Parameters.MinFee

  /** nanoERG an offer holds: the maker's half of what a position needs. */
  final val OfferErg: Long = DFHelpers.POSITION_ERG / 2

  /** Asked of the wallet beyond the exact need, so change carrying leftover tokens clears the minimum. */
  private final val Headroom: Long = UTXO.MIN_CHANGE

  /** The fill timing a transaction signed in `ctx` sees, or None for a context that does not list its headers. */
  def timing(ctx: BlockchainContext): Option[DFFillTiming] = ctx match {
    case impl: BlockchainContextImpl if impl.getHeaders.size >= 2 =>
      val tip = impl.getHeaders.get(0).getHeight
      Some(DFFillTiming(tip, DFMath.epochOf(tip), DFMath.epochOf(impl.getHeaders.get(1).getHeight)))
    case _ => None
  }

  /** The last height of the epoch the next block falls in, which is when a new offer expires by default. */
  def defaultExpiry(tip: Int): Int = (DFMath.epochOf(tip + 1) + 1) * DFHelpers.EPOCH_LENGTH

  /**
   * The last height a fill of a window starting at `windowStart` can be mined at. Past it, the block before
   * the fill is in an epoch less than 2 before the window.
   */
  def lastFillHeight(windowStart: Int): Int = (windowStart - 1) * DFHelpers.EPOCH_LENGTH + 1

  /** Whether a window starting at `windowStart` passes the lead check against a last block in `epoch`. */
  def leadHolds(windowStart: Int, epoch: Int): Boolean =
    windowStart >= epoch + DFHelpers.MIN_LEAD && windowStart <= epoch + DFHelpers.MAX_LEAD

  /**
   * Why a fill of `offer` built at `timing` would fail to sign or be refused by the node, or None when it
   * would do neither in the next block.
   */
  def fillRefusal(offer: DFOffer, timing: DFFillTiming): Option[String] = {
    val t = offer.terms
    if (!offer.funded)
      Some(s"offer ${offer.boxId} holds ${offer.lit} LIT, short of the maker's ${DFMath.sideLit(t.notional)}; " +
        "a fill would take the difference from the taker")
    else if (timing.tip + 1 > offer.expiry)
      Some(s"offer ${offer.boxId} expired at height ${offer.expiry}")
    else if (!leadHolds(t.windowStart, timing.nodeEpoch))
      Some(s"its window starts at epoch ${t.windowStart}, which a fill in epoch ${timing.nodeEpoch} cannot " +
        s"take: the start must be ${DFHelpers.MIN_LEAD} to ${DFHelpers.MAX_LEAD} epochs after the last block's epoch")
    else if (!leadHolds(t.windowStart, timing.signerEpoch))
      Some(s"block ${timing.tip} is the first of epoch ${timing.nodeEpoch}, where a fill signed here reads " +
        s"epoch ${timing.signerEpoch} and the node reads ${timing.nodeEpoch}; it can be filled from the next block")
    else None
  }

  /** Why no fill could ever accept `terms`, or None. */
  def termsRefusal(terms: DFTerms): Option[String] =
    if (terms.strike <= 0) Some(s"the strike must be positive, got ${terms.strike}")
    else if (DFMath.margin(terms.notional) <= 0)
      Some(s"the notional must be at least ${DFHelpers.MARGIN_DIVISOR} base units, so each margin is positive")
    else if (terms.notional > MaxNotional) Some(s"the notional must be at most $MaxNotional base units")
    else if (terms.windowLength < 1 || terms.windowLength > DFHelpers.MAX_WINDOW)
      Some(s"the window must be 1 to ${DFHelpers.MAX_WINDOW} epochs long, got ${terms.windowLength}")
    else if (terms.windowStart < 0 || terms.windowStart > Int.MaxValue - terms.windowLength)
      Some(s"the window cannot start at epoch ${terms.windowStart}")
    else None

  /** The largest notional, a billion LIT, so a position's LIT, both margins and rewards, cannot overflow a Long. */
  final val MaxNotional: Long = 1000000000000000000L

  /** An offer of this wallet's, `makerLong` on its side, holding the maker's margin and rewards. */
  def create(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, makerLong: Boolean,
             terms: DFTerms, expiry: Int): DFPlan = {
    termsRefusal(terms).foreach(reason => throw new IllegalArgumentException(reason))
    require(expiry > ctx.getHeight + 1, s"an offer expiring at $expiry could not be filled after the next block")
    val side = Token(contracts.litId, DFMath.sideLit(terms.notional))
    val offer = UTXO(contracts.offer, OfferErg, Seq(side),
      DifficultyForwardContracts.offerRegisters(wallet.p2pk.getPublicKey, makerLong, terms, expiry))
    DFPlan(Math.addExact(OfferErg, TxFee + Headroom), Seq(side), funding =>
      TxBuilder(ctx).setInputs(funding: _*).setOutputs(offer).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Fills `offer`, this wallet taking the other side. The wallet adds whatever the position needs beyond what
   * the offer holds, and change takes anything the offer holds beyond that.
   */
  def fill(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, offer: DFOffer,
           offerBox: InputUTXO): DFPlan = {
    val refusal = timing(ctx).fold(Option("this context does not list the headers a fill's lead check reads"))(
      fillRefusal(offer, _))
    refusal.foreach(reason => throw new IllegalArgumentException(reason))
    val t = offer.terms
    val taker = wallet.p2pk.getPublicKey
    val (long, short) = if (offer.makerLong) (offer.makerKey, taker) else (taker, offer.makerKey)
    val positionLit = DFMath.positionLit(t.notional)
    val position = UTXO(contracts.position, DFHelpers.POSITION_ERG, Seq(Token(contracts.litId, positionLit)),
      DifficultyForwardContracts.positionRegisters(long, short, t))
    val lit = takerLit(offer)
    DFPlan(takerErg(offerBox.value) + Headroom, if (lit > 0) Seq(Token(contracts.litId, lit)) else Seq.empty,
      funding => TxBuilder(ctx).setInputs(offerBox +: funding: _*).setOutputs(position).buildTx(TxFee, wallet.p2pk))
  }

  /** LIT a taker adds to fill `offer`: the position's LIT less what the offer holds. */
  def takerLit(offer: DFOffer): Long = math.max(0L, DFMath.positionLit(offer.terms.notional) - offer.lit)

  /** nanoERG a taker adds to fill an offer holding `offerErg`, the fee included. */
  def takerErg(offerErg: Long): Long = math.max(0L, DFHelpers.POSITION_ERG + TxFee - offerErg)

  /**
   * Returns `offer` to its maker, which this wallet must sign for. The fee comes out of the offer's own ERG
   * when that leaves the refund at least the minimum, and from the wallet otherwise.
   */
  def cancel(ctx: BlockchainContext, wallet: NodeWallet, offer: DFOffer, offerBox: InputUTXO): DFPlan =
    if (offerBox.value - TxFee >= UTXO.MIN_CHANGE)
      DFPlan(0L, Seq.empty, _ => TxBuilder(ctx).setInputs(offerBox)
        .setOutputs(UTXO(offer.maker, offerBox.value - TxFee, offerBox.tokens)).buildTx(TxFee, wallet.p2pk))
    else
      DFPlan(TxFee + Headroom, Seq.empty, funding => TxBuilder(ctx).setInputs(offerBox +: funding: _*)
        .setOutputs(UTXO(offer.maker, offerBox.value, offerBox.tokens)).buildTx(TxFee, wallet.p2pk))

  /**
   * The recording a settlement of `position` on `acc` carries: the current epoch when one is due, unless
   * recording it would push the window out of the ring and turn the settlement into a refund.
   */
  def carriedRecording(ctx: BlockchainContext, acc: DFAccState, position: DFPosition): Option[DFRecording] =
    carried(ForwardsTransactions.recordingAt(ctx), acc, position)

  private def carried(recording: Option[DFRecording], acc: DFAccState, position: DFPosition): Option[DFRecording] =
    recording.filter(r => r.epoch > acc.lastEpoch && !ForwardsTransactions.overwrittenBy(position, acc, r.epoch))

  /**
   * What settling `position` on `acc` would pay, with `recording` carried as [[settle]] carries it, or None
   * while the window is not recorded to its end. Mirrors the settlement builder, which stays the authority.
   */
  def settlement(acc: DFAccState, position: DFPosition, recording: Option[DFRecording]): Option[DFSettlement] = {
    val t = position.terms
    val record = carried(recording, acc, position)
    val (lastEpoch, ring) = record.fold((acc.lastEpoch, acc.ring))(r =>
      (r.epoch, DFMath.recordedRing(acc.ring, acc.lastEpoch, r.epoch, r.difficulty)))
    if (!DFMath.windowComplete(lastEpoch, t.windowStart, t.windowLength)) None
    else {
      val (long, short) = DFMath.owed(ring, lastEpoch, t.windowStart, t.windowLength, t.strike, t.notional)
      val taken = if (record.isDefined) DFHelpers.RECORD_REWARD else 0L
      Some(DFSettlement(record.isDefined, DFMath.overwritten(lastEpoch, t.windowStart), long, short,
        position.lit - long - short - DFHelpers.ACC_DEPOSIT + taken))
    }
  }

  /**
   * Settles `position` on `acc`, which may be the accumulator's mempool tip, paying the broadcast fee out of the
   * position's ERG. The rewards go to this wallet, both of them when the settlement also records.
   */
  def settle(ctx: BlockchainContext, wallet: NodeWallet, contracts: DFContracts, acc: DFAccState,
             position: DFPosition, positionBox: InputUTXO): DFStep =
    ForwardsTransactions.settle(ctx, wallet, contracts, acc, position, positionBox,
      carriedRecording(ctx, acc, position), TxFee)
}
