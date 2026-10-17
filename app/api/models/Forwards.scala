package api.models

import play.api.libs.json._

/** One epoch's difficulty as the accumulator holds it. */
case class DFEpochDifficulty(epoch: Int, difficulty: String)

/**
 * The difficulty the next epoch will have, computed from block timestamps with Ergo's retarget rule.
 *
 * @param exact      true once the current epoch has ended, so this is the value the next blocks carry;
 *                   otherwise the current epoch's end is projected at its pace so far
 * @param fromHeight the newest block it was computed from
 * @param change     the move from the current difficulty, as a fraction
 */
case class DFNextDifficulty(epoch: Int, difficulty: String, exact: Boolean, fromHeight: Int, change: Double)

/**
 * What an offer must satisfy to be created or filled by this client right now.
 *
 * @param minWindowStart         earliest window start a fill in the next block accepts
 * @param maxWindowStart         latest
 * @param maxWindowLength        longest window, in epochs
 * @param defaultExpiry          the expiry a new offer takes when none is given: the end of the current epoch
 * @param shortWindowEpochs      windows shorter than this are size-limited
 * @param shortWindowMaxNotional largest notional for such a window, in LIT base units
 */
case class DFOfferLimits(minWindowStart: Int, maxWindowStart: Int, maxWindowLength: Int, defaultExpiry: Int,
                         shortWindowEpochs: Int, shortWindowMaxNotional: String)

/**
 * The difficulty accumulator and the chain around it.
 *
 * @param deployed                  whether this network has an accumulator; without one, nothing can be
 *                                  offered, filled or settled
 * @param currentEpoch              the epoch of the newest block
 * @param blocksLeftInEpoch         blocks after the newest one before the epoch ends
 * @param currentDifficulty         the newest block's difficulty, which the current epoch keeps
 * @param nextDifficulty            absent while the difficulty table cannot supply 8 complete epochs
 * @param nextDifficultyUnavailable why `nextDifficulty` is absent
 * @param boxId                     the accumulator box, the newest one including unconfirmed spends
 * @param lastEpoch                 the newest epoch it has recorded
 * @param balance                   LIT left to pay 1 LIT per recorded epoch
 * @param recent                    the 128 epochs it holds, oldest first
 * @param accumulatorUnavailable    why the accumulator could not be read, when deployed
 */
case class DFAccumulatorInfo(deployed: Boolean,
                             height: Int,
                             currentEpoch: Int,
                             blocksLeftInEpoch: Int,
                             currentDifficulty: String,
                             nextDifficulty: Option[DFNextDifficulty],
                             nextDifficultyUnavailable: Option[String],
                             accumulatorNFT: Option[String],
                             boxId: Option[String],
                             lastEpoch: Option[Int],
                             balance: Option[String],
                             recent: Seq[DFEpochDifficulty],
                             accumulatorUnavailable: Option[String],
                             limits: DFOfferLimits)

/**
 * An offer waiting for a taker.
 *
 * @param maker           address the maker is paid to
 * @param makerSide       `long` (paid when difficulty rises) or `short`
 * @param margin          each side's margin, a fifth of the notional; the most either side can lose
 * @param windowEnd       the window's last epoch
 * @param lastFillHeight  the last height a fill can be mined at: the expiry, or earlier when the window would
 *                        start less than 2 epochs after the fill
 * @param lit             LIT the offer holds
 * @param nanoErgs        nanoERG the offer holds
 * @param takerLit        LIT a fill takes from the taker
 * @param takerNanoErgs   nanoERG a fill takes from the taker, network fee included
 * @param impliedMove     the strike against the current difficulty, as a fraction
 * @param impliedMoveNext the strike against the next epoch's computed difficulty, when known
 * @param mine            whether this wallet made it
 * @param fillable        whether a fill in the next block would be accepted
 * @param refusal         why not, when it would not
 */
case class DFOfferView(boxId: String,
                       maker: String,
                       makerSide: String,
                       strike: String,
                       notional: String,
                       margin: String,
                       windowStart: Int,
                       windowLength: Int,
                       windowEnd: Int,
                       expiry: Int,
                       lastFillHeight: Int,
                       lit: String,
                       nanoErgs: String,
                       takerLit: String,
                       takerNanoErgs: String,
                       impliedMove: Option[Double],
                       impliedMoveNext: Option[Double],
                       mine: Boolean,
                       fillable: Boolean,
                       refusal: Option[String])

/**
 * Open offers, ordered by window start, then length, then strike.
 *
 * @param total     offers listed in all, before `offset` and `limit`
 * @param truncated whether the scan stopped at its cap, so older offers may be missing
 */
case class DFOfferBook(height: Int, currentEpoch: Int, offers: Seq[DFOfferView], total: Int, truncated: Boolean)

/**
 * An offer to create, this wallet as the maker.
 *
 * @param makerSide   `long` or `short`
 * @param strike      difficulty the window's mean is measured against
 * @param notional    LIT base units; each side's margin is a fifth of it
 * @param windowStart first epoch of the window
 * @param expiry      last height a fill can be mined at; the end of the current epoch when omitted
 */
case class DFOfferRequest(makerSide: String,
                          strike: String,
                          notional: String,
                          windowStart: Int,
                          windowLength: Int,
                          expiry: Option[Int] = None)

/**
 * What creating an offer locks, and what it is exposed to.
 *
 * @param lit           LIT locked in the offer: the margin plus 1.5 LIT of rewards
 * @param nanoErgs      nanoERG locked in the offer
 * @param networkFee    nanoERG paid to create it
 * @param maxLoss       the most this wallet can lose if the offer is filled: its margin plus 1.5 LIT
 * @param expiresInBlocks blocks after the next one until the offer expires
 * @param warnings      conditions worth reading before sending
 */
case class DFOfferQuote(makerSide: String,
                        strike: String,
                        notional: String,
                        margin: String,
                        windowStart: Int,
                        windowLength: Int,
                        windowEnd: Int,
                        expiry: Int,
                        expiresInBlocks: Int,
                        lastFillHeight: Int,
                        lit: String,
                        nanoErgs: String,
                        networkFee: String,
                        maxLoss: String,
                        impliedMove: Option[Double],
                        impliedMoveNext: Option[Double],
                        warnings: Seq[String])

/** @param offerBoxId the offer box the transaction creates */
case class DFOfferResult(txId: String, outcome: String, offerBoxId: String)

/**
 * What filling an offer costs this wallet, and the side it takes.
 *
 * @param takerSide the side this wallet takes, opposite the maker's
 * @param maxLoss   the most this wallet can lose: its margin plus 1.5 LIT
 */
case class DFFillQuote(offer: DFOfferView,
                       takerSide: String,
                       takerLit: String,
                       takerNanoErgs: String,
                       networkFee: String,
                       maxLoss: String,
                       warnings: Seq[String])

/** @param positionBoxId the position box the fill creates */
case class DFFillResult(txId: String, outcome: String, positionBoxId: String)

/**
 * What cancelling an offer returns.
 *
 * @param feeFromOffer whether the network fee comes out of the offer's own ERG, rather than the wallet
 */
case class DFCancelQuote(boxId: String, returnedLit: String, returnedNanoErgs: String, networkFee: String,
                         feeFromOffer: Boolean)

case class DFCancelResult(txId: String, outcome: String, returnedLit: String, returnedNanoErgs: String,
                          networkFee: String)

/**
 * A position this wallet holds a side of.
 *
 * @param side           `long`, `short`, or `both` when this wallet holds both keys
 * @param status         `waiting` before the window starts, `in_window` until its last epoch is recorded,
 *                       `settleable` once it is, and `refundable` once its first epoch has left the
 *                       accumulator, when each side gets its margin back
 * @param epochsKnown    window epochs whose difficulty is known: recorded, or the current one
 * @param markDifficulty the window's mean with every unknown epoch at the newest difficulty known
 * @param markMove       `markDifficulty` against the strike, as a fraction
 * @param markPayout     LIT the long side would receive on top of its margin at the mark, negative when the
 *                       short side is paid; clamped to the margin
 * @param pnl            the same from this wallet's side: zero when it holds both
 * @param owedLong       LIT a settlement now pays the long side, margin included
 * @param owedShort      the same for the short side
 */
case class DFPositionView(boxId: String,
                          side: String,
                          long: String,
                          short: String,
                          strike: String,
                          notional: String,
                          margin: String,
                          windowStart: Int,
                          windowLength: Int,
                          windowEnd: Int,
                          status: String,
                          epochsKnown: Int,
                          markDifficulty: Option[String],
                          markMove: Option[Double],
                          markPayout: Option[String],
                          pnl: Option[String],
                          owedLong: Option[String],
                          owedShort: Option[String])

/**
 * This wallet's positions, and the offers it made that are still unspent, open or not.
 *
 * @param truncated whether a scan stopped at its cap, so older boxes may be missing
 */
case class DFPositionList(height: Int, currentEpoch: Int, positions: Seq[DFPositionView], offers: Seq[DFOfferView],
                          truncated: Boolean)

/**
 * What settling a position now pays.
 *
 * @param refund    whether the window has left the accumulator, so each side gets its margin back
 * @param records   whether the settlement also records the current epoch, which adds 1 LIT to the reward
 * @param rewardLit LIT this wallet receives for building the settlement
 */
case class DFSettleQuote(boxId: String, refund: Boolean, records: Boolean, owedLong: String, owedShort: String,
                         rewardLit: String, networkFee: String)

case class DFSettleResult(txId: String, outcome: String, refund: Boolean, owedLong: String, owedShort: String,
                          rewardLit: String)

/**
 * A position this wallet held a side of, after it was settled or refunded.
 *
 * @param settledHeight the block that confirmed the settlement
 * @param refund        whether each side got its margin back because the window had left the accumulator
 * @param windowMean    the window's mean difficulty as the settlement read it; absent for a refund
 * @param payout        LIT the long side received on top of its margin, negative when the short side was paid
 * @param received      LIT this wallet's side or sides received, margins included
 * @param net           `received` less what this wallet put in: each side's margin and its 1.5 LIT of rewards
 */
case class DFSettledView(boxId: String,
                         side: String,
                         strike: String,
                         notional: String,
                         margin: String,
                         windowStart: Int,
                         windowLength: Int,
                         windowEnd: Int,
                         txId: String,
                         settledHeight: Int,
                         settledAt: Long,
                         refund: Boolean,
                         windowMean: Option[String],
                         payout: String,
                         received: String,
                         net: String)

/**
 * An offer this wallet made that is no longer open.
 *
 * @param outcome       `filled` or `cancelled`
 * @param positionBoxId the position a fill created
 */
case class DFPastOffer(boxId: String,
                       makerSide: String,
                       strike: String,
                       notional: String,
                       windowStart: Int,
                       windowLength: Int,
                       expiry: Int,
                       outcome: String,
                       txId: String,
                       height: Int,
                       timestamp: Long,
                       positionBoxId: Option[String])

/**
 * This wallet's settled positions and closed offers, newest first.
 *
 * @param realizedNet the sum of every settled position's `net`
 * @param wins        settled positions with a positive `net`
 * @param losses      with a negative one
 * @param truncated   whether a scan stopped at its cap, so older history may be missing
 */
case class DFHistory(height: Int, settled: Seq[DFSettledView], offers: Seq[DFPastOffer], realizedNet: String,
                     wins: Int, losses: Int, truncated: Boolean)

/** One side of the order book: offers whose maker took that side, and their notional. */
case class DFBookSide(offers: Int, notional: String)

/**
 * The whole market: the order book's two sides and every unsettled position, by status.
 *
 * @param longs         open offers whose maker is long, so a taker goes short
 * @param shorts        open offers whose maker is short
 * @param openInterest  notional of every unsettled position, in LIT base units
 * @param epochsFunded  epochs the accumulator's balance pays a recording for, at 1 LIT each
 * @param truncated     whether a scan stopped at its cap, so the counts are lower bounds
 */
case class DFMarket(height: Int,
                    currentEpoch: Int,
                    longs: DFBookSide,
                    shorts: DFBookSide,
                    positions: Int,
                    openInterest: String,
                    waiting: Int,
                    inWindow: Int,
                    settleable: Int,
                    refundable: Int,
                    epochsFunded: Option[Long],
                    truncated: Boolean)

/**
 * JSON for the `forwards` tag: difficulty forwards, peer-to-peer bets on Ergo's mean difficulty over a window
 * of epochs. LIT amounts are base units and difficulties whole numbers, both as decimal strings like every
 * amount in this API. Epochs are `(height - 1) / 128`.
 */
object Forwards {
  implicit lazy val dfEpochDifficultyFormat: Format[DFEpochDifficulty] = Json.format[DFEpochDifficulty]
  implicit lazy val dfNextDifficultyFormat: Format[DFNextDifficulty] = Json.format[DFNextDifficulty]
  implicit lazy val dfOfferLimitsFormat: Format[DFOfferLimits] = Json.format[DFOfferLimits]
  implicit lazy val dfAccumulatorInfoFormat: Format[DFAccumulatorInfo] = Json.format[DFAccumulatorInfo]
  implicit lazy val dfOfferViewFormat: Format[DFOfferView] = Json.format[DFOfferView]
  implicit lazy val dfOfferBookFormat: Format[DFOfferBook] = Json.format[DFOfferBook]
  implicit lazy val dfOfferRequestFormat: Format[DFOfferRequest] = Json.format[DFOfferRequest]
  implicit lazy val dfOfferQuoteFormat: Format[DFOfferQuote] = Json.format[DFOfferQuote]
  implicit lazy val dfOfferResultFormat: Format[DFOfferResult] = Json.format[DFOfferResult]
  implicit lazy val dfFillQuoteFormat: Format[DFFillQuote] = Json.format[DFFillQuote]
  implicit lazy val dfFillResultFormat: Format[DFFillResult] = Json.format[DFFillResult]
  implicit lazy val dfCancelQuoteFormat: Format[DFCancelQuote] = Json.format[DFCancelQuote]
  implicit lazy val dfCancelResultFormat: Format[DFCancelResult] = Json.format[DFCancelResult]
  implicit lazy val dfPositionViewFormat: Format[DFPositionView] = Json.format[DFPositionView]
  implicit lazy val dfPositionListFormat: Format[DFPositionList] = Json.format[DFPositionList]
  implicit lazy val dfSettleQuoteFormat: Format[DFSettleQuote] = Json.format[DFSettleQuote]
  implicit lazy val dfSettleResultFormat: Format[DFSettleResult] = Json.format[DFSettleResult]
  implicit lazy val dfSettledViewFormat: Format[DFSettledView] = Json.format[DFSettledView]
  implicit lazy val dfPastOfferFormat: Format[DFPastOffer] = Json.format[DFPastOffer]
  implicit lazy val dfHistoryFormat: Format[DFHistory] = Json.format[DFHistory]
  implicit lazy val dfBookSideFormat: Format[DFBookSide] = Json.format[DFBookSide]
  implicit lazy val dfMarketFormat: Format[DFMarket] = Json.format[DFMarket]
}
