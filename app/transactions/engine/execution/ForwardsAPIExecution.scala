package transactions.engine.execution

import akka.pattern.ask
import akka.util.Timeout
import api.ForwardsApi
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import configs.{ForwardsOffersConfig, NodeContext}
import forwards.contracts.{DFContracts, DFTerms}
import forwards.{DFHelpers, DFMath}
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedTransaction, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import sigma.util.NBitsUtils
import state.synchronization.CompleteMempool
import stats.NextDifficultyEstimate
import transactions.batching.forwards._
import transactions.batching.{Batcher, BatchingMempool}
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.concurrent.Await
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The difficulty forward endpoints, over the live boxes.
 *
 * Reads and quotes run wherever they are called. Mutations run on the transaction engine's worker, which owns
 * the wallet: each reserves its funding there, signs with the node wallet, and broadcasts through
 * [[EngineBroadcast]], paying the network fee, since none of these goes into this miner's own block.
 *
 * @param deployment    this network's contracts, or None while no accumulator exists, when every mutation
 *                      is refused
 * @param offerLimits   the size limit on short windows this client applies to offers it creates or fills
 * @param alive         whether the engine still wants this attempt; once false, nothing is signed or sent
 * @param nextDifficulty the next epoch's difficulty, computed from the difficulty table
 */
class ForwardsAPIExecution(nodeContext: NodeContext,
                           walletSelector: EngineFunding,
                           deployment: Option[DFContracts],
                           offerLimits: ForwardsOffersConfig = ForwardsOffersConfig.Default,
                           alive: () => Boolean = () => true,
                           nextDifficulty: () => Either[String, NextDifficultyEstimate] =
                             ForwardsAPIExecution.NotComputed) extends ForwardsApi {

  import ForwardsAPIExecution._

  protected def executionNode: NodeApi = nodeContext.getNodeApi

  private def wallet: NodeWallet = nodeContext.getNodeWallet

  // ══════════════════════════════════════════════════════════════════════════
  //  READS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def getAccumulator: DFAccumulatorInfo = withCtx { (ctx, nodeApi) =>
    val chain = chainOf(ctx)
    val read = deployment.map(accumulatorOf(ctx, nodeApi, _))
    val acc = read.flatMap(_.toOption)
    DFAccumulatorInfo(
      deployed = deployment.isDefined,
      height = chain.tip,
      currentEpoch = chain.epoch,
      blocksLeftInEpoch = (chain.epoch + 1) * DFHelpers.EPOCH_LENGTH - chain.tip,
      currentDifficulty = chain.difficulty.toString,
      nextDifficulty = chain.next.toOption.map(n =>
        DFNextDifficulty(n.epoch, n.difficulty.toString, n.exact, n.fromHeight, ratio(n.difficulty, chain.difficulty))),
      nextDifficultyUnavailable = chain.next.left.toOption,
      accumulatorNFT = deployment.map(_.accNFT.toString),
      boxId = acc.map(_.box.id.toString),
      lastEpoch = acc.map(_.lastEpoch),
      balance = acc.map(_.lit.toString),
      recent = acc.toSeq.flatMap(a => (math.max(0, a.lastEpoch - DFHelpers.RING_SIZE + 1) to a.lastEpoch)
        .map(e => DFEpochDifficulty(e, a.ring(e % DFHelpers.RING_SIZE).toString))),
      accumulatorUnavailable = read.flatMap(_.left.toOption),
      limits = limitsAt(chain))
  }

  /** @inheritdoc */
  override def listOffers(offset: Option[Int], limit: Option[Int]): DFOfferBook = withCtx { (ctx, nodeApi) =>
    val from = offset.getOrElse(0)
    val size = limit.getOrElse(DefaultPage)
    if (from < 0) throw LithosBadRequest(s"'offset' cannot be negative, got $from")
    if (size < 1 || size > MaxPage) throw LithosBadRequest(s"'limit' must be between 1 and $MaxPage, got $size")
    val chain = chainOf(ctx)
    deployment.fold(DFOfferBook(chain.tip, chain.epoch, Seq.empty, 0, truncated = false)) { contracts =>
      val (offers, truncated) = scan(nodeApi, contracts.offer.ergoTreeHex)(DFBoxes.offer(_, contracts))
      val open = offers.filter(openOffer(_, chain))
        .sortBy(o => (o.terms.windowStart, o.terms.windowLength, o.terms.strike, o.boxId))
      val timing = ForwardsUserTransactions.timing(ctx)
      DFOfferBook(chain.tip, chain.epoch, open.slice(from, from + size).map(offerView(_, chain, timing)), open.size,
        truncated)
    }
  }

  /** @inheritdoc */
  override def listPositions: DFPositionList = withCtx { (ctx, nodeApi) =>
    val chain = chainOf(ctx)
    deployment.fold(DFPositionList(chain.tip, chain.epoch, Seq.empty, Seq.empty, truncated = false)) { contracts =>
      val mine = wallet.signableTrees
      val (positions, positionsCut) = scan(nodeApi, contracts.position.ergoTreeHex)(box =>
        DFBoxes.position(box, contracts).filter(p => mine(p.long.ergoTreeHex) || mine(p.short.ergoTreeHex)))
      val (offers, offersCut) = scan(nodeApi, contracts.offer.ergoTreeHex)(box =>
        DFBoxes.offer(box, contracts).filter(o => mine(o.maker.ergoTreeHex)))
      val acc = if (positions.isEmpty) None else accumulatorOf(ctx, nodeApi, contracts).toOption
      val recording = ForwardsTransactions.recordingAt(ctx)
      val timing = ForwardsUserTransactions.timing(ctx)
      DFPositionList(chain.tip, chain.epoch,
        positions.sortBy(p => (p.windowEnd, p.boxId)).map(positionView(_, chain, acc, recording)),
        offers.sortBy(o => (o.expiry, o.boxId)).map(offerView(_, chain, timing)),
        positionsCut || offersCut)
    }
  }

  /** @inheritdoc */
  override def getMarket: DFMarket = withCtx { (ctx, nodeApi) =>
    val chain = chainOf(ctx)
    val none = DFBookSide(0, "0")
    deployment.fold(DFMarket(chain.tip, chain.epoch, none, none, 0, "0", 0, 0, 0, 0, None, truncated = false)) {
      contracts =>
        val (offers, offersCut) = scan(nodeApi, contracts.offer.ergoTreeHex)(DFBoxes.offer(_, contracts))
        val open = offers.filter(openOffer(_, chain))
        val (positions, positionsCut) = scan(nodeApi, contracts.position.ergoTreeHex)(DFBoxes.position(_, contracts))
        val acc = accumulatorOf(ctx, nodeApi, contracts).toOption
        val recording = ForwardsTransactions.recordingAt(ctx)
        val statuses = positions.map(p => statusOf(p, chain, acc.flatMap(ForwardsUserTransactions.settlement(_, p, recording))))
        def side(makerLong: Boolean): DFBookSide = {
          val taken = open.filter(_.makerLong == makerLong)
          DFBookSide(taken.size, taken.map(o => BigInt(o.terms.notional)).sum.toString)
        }
        DFMarket(chain.tip, chain.epoch, side(makerLong = true), side(makerLong = false), positions.size,
          positions.map(p => BigInt(p.terms.notional)).sum.toString, statuses.count(_ == Waiting),
          statuses.count(_ == InWindow), statuses.count(_ == Settleable), statuses.count(_ == Refundable),
          acc.map(_.lit / DFHelpers.RECORD_REWARD), offersCut || positionsCut)
    }
  }

  /** @inheritdoc */
  override def getHistory: DFHistory = withCtx { (ctx, nodeApi) =>
    val chain = chainOf(ctx)
    deployment.fold(DFHistory(chain.tip, Seq.empty, Seq.empty, "0", 0, 0, truncated = false)) { contracts =>
      val mine = wallet.signableTrees
      val (positions, positionsCut) = scanSpent(nodeApi, contracts.position.ergoTreeHex)(box =>
        DFBoxes.position(box, contracts).filter(p => mine(p.long.ergoTreeHex) || mine(p.short.ergoTreeHex)))
      val (offers, offersCut) = scanSpent(nodeApi, contracts.offer.ergoTreeHex)(box =>
        DFBoxes.offer(box, contracts).filter(o => mine(o.maker.ergoTreeHex)))
      val settled = positions.flatMap { case (p, txId) => settledView(nodeApi, contracts, p, txId) }
        .sortBy(v => (-v.settledHeight, v.boxId))
      val closed = offers.flatMap { case (o, txId) => pastOffer(nodeApi, contracts, o, txId) }
        .sortBy(o => (-o.height, o.boxId))
      val nets = settled.map(v => BigInt(v.net))
      DFHistory(chain.tip, settled, closed, nets.sum.toString, nets.count(_ > 0), nets.count(_ < 0),
        positionsCut || offersCut)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  OFFERS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkOffer(request: DFOfferRequest): DFOfferQuote = withCtx { (ctx, _) =>
    deployed()
    quoteOf(newOffer(request, chainOf(ctx)))
  }

  /** @inheritdoc */
  override def createOffer(request: DFOfferRequest): DFOfferResult = withCtx { (ctx, _) =>
    val contracts = deployed()
    val offer = newOffer(request, chainOf(ctx))
    val built = fund(ctx, ForwardsUserTransactions.create(ctx, wallet, contracts, offer.makerLong, offer.terms,
      offer.expiry))
    val sent = send(built)
    DFOfferResult(sent.txId, sent.outcome, built.signed.getOutputsToSpend.get(0).getId.toString)
  }

  /** @inheritdoc */
  override def checkFill(boxId: String): DFFillQuote = withCtx { (ctx, nodeApi) =>
    val contracts = deployed()
    val chain = chainOf(ctx)
    val (offer, _) = offerById(ctx, nodeApi, contracts, boxId)
    val view = offerView(offer, chain, ForwardsUserTransactions.timing(ctx))
    val lastFill = fillableUntil(offer)
    val warnings =
      if (view.fillable && lastFill <= chain.tip + LateFillBlocks)
        Seq(s"A fill is valid only until height $lastFill. If it is not mined by then, the node drops it and " +
          "nothing is spent.")
      else Seq.empty
    DFFillQuote(view, sideName(!offer.makerLong), view.takerLit, view.takerNanoErgs,
      ForwardsUserTransactions.TxFee.toString, maxLoss(offer.terms).toString, warnings)
  }

  /** @inheritdoc */
  override def fill(boxId: String): DFFillResult = withCtx { (ctx, nodeApi) =>
    val contracts = deployed()
    usableLimits()
    val snapshot = mempoolSnapshot()
    val (offer, box) = offerById(ctx, nodeApi, contracts, boxId)
    unclaimed(snapshot, boxId, "offer")
    if (wallet.signableTrees.contains(offer.maker.ergoTreeHex))
      throw LithosUnprocessable(s"offer $boxId was made by this wallet; cancel it rather than filling it")
    sizeRefusal(offer.terms).foreach(reason => throw LithosUnprocessable(s"offer $boxId: $reason"))
    ForwardsUserTransactions.timing(ctx).flatMap(ForwardsUserTransactions.fillRefusal(offer, _))
      .foreach(reason => throw LithosUnprocessable(reason))
    val built = fund(ctx, ForwardsUserTransactions.fill(ctx, wallet, contracts, offer, box))
    val sent = send(built)
    DFFillResult(sent.txId, sent.outcome, built.signed.getOutputsToSpend.get(0).getId.toString)
  }

  /** @inheritdoc */
  override def checkCancel(boxId: String): DFCancelQuote = withCtx { (ctx, nodeApi) =>
    val (offer, box) = ownOffer(ctx, nodeApi, deployed(), boxId)
    val plan = ForwardsUserTransactions.cancel(ctx, wallet, offer, box)
    val feeFromOffer = plan.value == 0L
    DFCancelQuote(boxId, offer.lit.toString, refunded(box, feeFromOffer).toString,
      ForwardsUserTransactions.TxFee.toString, feeFromOffer)
  }

  /** @inheritdoc */
  override def cancel(boxId: String): DFCancelResult = withCtx { (ctx, nodeApi) =>
    val contracts = deployed()
    val snapshot = mempoolSnapshot()
    val (offer, box) = ownOffer(ctx, nodeApi, contracts, boxId)
    unclaimed(snapshot, boxId, "offer")
    val plan = ForwardsUserTransactions.cancel(ctx, wallet, offer, box)
    val built = fund(ctx, plan)
    val sent = send(built)
    DFCancelResult(sent.txId, sent.outcome, offer.lit.toString, refunded(box, plan.value == 0L).toString,
      ForwardsUserTransactions.TxFee.toString)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SETTLEMENT
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def checkSettle(boxId: String): DFSettleQuote = withCtx { (ctx, nodeApi) =>
    val contracts = deployed()
    val (position, _) = positionById(ctx, nodeApi, contracts, boxId)
    val acc = accumulatorOf(ctx, nodeApi, contracts).fold(reason => throw LithosUnavailable(reason), identity)
    val s = settlementOf(ctx, acc, position)
    DFSettleQuote(boxId, s.refund, s.records, s.owedLong.toString, s.owedShort.toString, s.rewardLit.toString,
      ForwardsUserTransactions.TxFee.toString)
  }

  /** @inheritdoc */
  override def settle(boxId: String): DFSettleResult = withCtx { (ctx, nodeApi) =>
    val contracts = deployed()
    val snapshot = mempoolSnapshot()
    val (position, box) = positionById(ctx, nodeApi, contracts, boxId)
    unclaimed(snapshot, boxId, "position")
    val acc = accumulatorTip(ctx, nodeApi, contracts, snapshot)
    val s = settlementOf(ctx, acc, position)
    stillRunning()
    // Signs inside; the fee comes out of the position, so no wallet input is reserved
    val step = ForwardsUserTransactions.settle(ctx, wallet, contracts, acc, position, box)
    val sent = broadcast(step.signed, Seq.empty)
    DFSettleResult(sent.txId, sent.outcome, s.refund, s.owedLong.toString, s.owedShort.toString,
      s.rewardLit.toString)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  VIEWS
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * The chain a request is answered against.
   *
   * @param difficulty the tip's, which its whole epoch carries
   * @param next       the next epoch's computed difficulty, or why it is not known
   */
  private case class Chain(tip: Int, epoch: Int, difficulty: BigInt, next: Either[String, NextDifficultyEstimate]) {

    /** The newest difficulty known: the next epoch's when computed, else the current one. */
    def latest: BigInt = next.fold(_ => difficulty, _.difficulty)
  }

  private def chainOf(ctx: BlockchainContext): Chain = ctx match {
    case impl: BlockchainContextImpl if !impl.getHeaders.isEmpty =>
      val tip = impl.getHeaders.get(0)
      val epoch = DFMath.epochOf(tip.getHeight)
      // The table can trail the tip by a block, when its estimate is of the epoch already under way
      val next = nextDifficulty().flatMap(n =>
        if (n.epoch == epoch + 1) Right(n) else Left(s"the difficulty table has not reached epoch $epoch yet"))
      Chain(tip.getHeight, epoch, NBitsUtils.decodeCompactBits(tip.getNBits), next)
    case _ => throw LithosUnavailable("the node context lists no headers")
  }

  private def limitsAt(chain: Chain): DFOfferLimits =
    DFOfferLimits(chain.epoch + DFHelpers.MIN_LEAD, chain.epoch + DFHelpers.MAX_LEAD, DFHelpers.MAX_WINDOW,
      ForwardsUserTransactions.defaultExpiry(chain.tip), offerLimits.shortWindowEpochs,
      (BigInt(offerLimits.shortWindowMaxNotionalLit) * ForwardsOffersConfig.LitUnit).toString)

  private def offerView(o: DFOffer, chain: Chain, timing: Option[DFFillTiming]): DFOfferView = {
    val t = o.terms
    val mine = wallet.signableTrees.contains(o.maker.ergoTreeHex)
    val refusal =
      if (mine) Some("this wallet made this offer")
      else timing.fold(Option(NoTiming))(ForwardsUserTransactions.fillRefusal(o, _)).orElse(sizeRefusal(t))
    DFOfferView(o.boxId, addressOf(o.maker), sideName(o.makerLong), t.strike.toString, t.notional.toString,
      DFMath.margin(t.notional).toString, t.windowStart, t.windowLength, o.windowEnd, o.expiry, fillableUntil(o),
      o.lit.toString, o.box.value.toString, ForwardsUserTransactions.takerLit(o).toString,
      ForwardsUserTransactions.takerErg(o.box.value).toString, move(t.strike, chain.difficulty),
      chain.next.toOption.flatMap(n => move(t.strike, n.difficulty)), mine, refusal.isEmpty, refusal)
  }

  /**
   * A position from this wallet's side. Settleable and refundable positions show what a settlement now pays;
   * the rest are marked with every unknown window epoch at the newest difficulty known.
   */
  private def positionView(p: DFPosition, chain: Chain, acc: Option[DFAccState],
                           recording: Option[DFRecording]): DFPositionView = {
    val t = p.terms
    val side = sideOf(p)
    val margin = DFMath.margin(t.notional)
    val settlement = acc.flatMap(ForwardsUserTransactions.settlement(_, p, recording))
    val status = statusOf(p, chain, settlement)
    // The window's difficulties known now: recorded, or the current epoch's if a settlement could record it
    val (lastEpoch, ring) = acc.fold((-1, IndexedSeq.empty[Long])) { a =>
      recording.filter(_.epoch > a.lastEpoch)
        .fold((a.lastEpoch, a.ring))(r => (r.epoch, DFMath.recordedRing(a.ring, a.lastEpoch, r.epoch, r.difficulty)))
    }
    val known = if (settlement.exists(_.refund)) Seq.empty
      else (t.windowStart to math.min(p.windowEnd, lastEpoch)).filter(e => lastEpoch - e < DFHelpers.RING_SIZE)
    val total = known.map(e => BigInt(ring(e % DFHelpers.RING_SIZE))).sum + chain.latest * (t.windowLength - known.size)
    val payout = settlement.map(s => BigInt(s.owedLong - margin)).getOrElse(payoutAt(total, t))
    val marked = !settlement.exists(_.refund)
    DFPositionView(p.boxId, side, addressOf(p.long), addressOf(p.short), t.strike.toString, t.notional.toString,
      margin.toString, t.windowStart, t.windowLength, p.windowEnd, status, known.size,
      if (marked) Some((total / t.windowLength).toString) else None,
      if (marked) Some(ratio(total, BigInt(t.strike) * t.windowLength)) else None,
      Some(payout.toString),
      Some((side match { case "long" => payout; case "short" => -payout; case _ => BigInt(0) }).toString),
      settlement.map(_.owedLong.toString), settlement.map(_.owedShort.toString))
  }

  /** Where `p` stands, given what settling it now would pay, if anything. */
  private def statusOf(p: DFPosition, chain: Chain, settlement: Option[DFSettlement]): String = settlement match {
    case Some(s) if s.refund => Refundable
    case Some(_) => Settleable
    case None if chain.epoch < p.terms.windowStart => Waiting
    case None => InWindow
  }

  /** This wallet's side of `p`: `both` when it holds both keys. */
  private def sideOf(p: DFPosition): String = {
    val mine = wallet.signableTrees
    (mine(p.long.ergoTreeHex), mine(p.short.ergoTreeHex)) match {
      case (true, true) => "both"
      case (true, false) => "long"
      case _ => "short"
    }
  }

  /**
   * A position of this wallet's as its settlement `txId` left it. What each side was owed is recomputed from
   * the accumulator the settlement created, which is what the position's script read, so it is exact whatever
   * order the settlement put its outputs in.
   */
  private def settledView(nodeApi: NodeApi, contracts: DFContracts, p: DFPosition, txId: String): Option[DFSettledView] =
    transaction(nodeApi, txId).flatMap { tx =>
      tx.outputs.headOption.flatMap(out => DFBoxes.accumulator(out.box, contracts)).map { acc =>
        val t = p.terms
        val (long, short) = DFMath.owed(acc.ring, acc.lastEpoch, t.windowStart, t.windowLength, t.strike, t.notional)
        val margin = DFMath.margin(t.notional)
        val refund = DFMath.overwritten(acc.lastEpoch, t.windowStart)
        val side = sideOf(p)
        val received = BigInt(if (side != "short") long else 0L) + (if (side != "long") short else 0L)
        val putIn = BigInt(DFMath.sideLit(t.notional)) * (if (side == "both") 2 else 1)
        DFSettledView(p.boxId, side, t.strike.toString, t.notional.toString, margin.toString, t.windowStart,
          t.windowLength, p.windowEnd, tx.id, tx.inclusionHeight, tx.timestamp, refund,
          if (refund) None else Some((DFMath.windowTotal(acc.ring, t.windowStart, t.windowLength) / t.windowLength).toString),
          (long - margin).toString, received.toString, (received - putIn).toString)
      }
    }

  /** An offer of this wallet's as the transaction `txId` closed it: a fill when it created a position. */
  private def pastOffer(nodeApi: NodeApi, contracts: DFContracts, o: DFOffer, txId: String): Option[DFPastOffer] =
    transaction(nodeApi, txId).map { tx =>
      val position = tx.outputs.headOption.filter(_.box.ergoTree.equalsIgnoreCase(contracts.position.ergoTreeHex))
      val t = o.terms
      DFPastOffer(o.boxId, sideName(o.makerLong), t.strike.toString, t.notional.toString, t.windowStart,
        t.windowLength, o.expiry, if (position.isDefined) "filled" else "cancelled", tx.id, tx.inclusionHeight,
        tx.timestamp, position.map(_.boxId))
    }

  private def quoteOf(o: NewOffer): DFOfferQuote = {
    val t = o.terms
    DFOfferQuote(sideName(o.makerLong), t.strike.toString, t.notional.toString, DFMath.margin(t.notional).toString,
      t.windowStart, t.windowLength, t.windowStart + t.windowLength - 1, o.expiry, o.expiry - (o.chain.tip + 1),
      o.lastFill, DFMath.sideLit(t.notional).toString, ForwardsUserTransactions.OfferErg.toString,
      ForwardsUserTransactions.TxFee.toString, maxLoss(t).toString, move(t.strike, o.chain.difficulty),
      o.chain.next.toOption.flatMap(n => move(t.strike, n.difficulty)), o.warnings)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REQUEST RULES
  // ══════════════════════════════════════════════════════════════════════════

  /** An offer request, checked against the chain it would be posted on. */
  private case class NewOffer(makerLong: Boolean, terms: DFTerms, expiry: Int, lastFill: Int, chain: Chain,
                              warnings: Seq[String])

  private def newOffer(request: DFOfferRequest, chain: Chain): NewOffer = {
    usableLimits()
    val makerLong = request.makerSide match {
      case "long" => true
      case "short" => false
      case other => throw LithosBadRequest(s"'makerSide' must be 'long' or 'short', got '$other'")
    }
    val terms = DFTerms(amount("strike", request.strike), amount("notional", request.notional),
      request.windowStart, request.windowLength)
    ForwardsUserTransactions.termsRefusal(terms).foreach(reason => throw LithosBadRequest(reason))
    sizeRefusal(terms).foreach(reason => throw LithosUnprocessable(s"this offer: $reason"))

    // The offer is mined in the next block at the earliest, and filled after it
    val posting = DFMath.epochOf(chain.tip + 1)
    if (terms.windowStart > posting + DFHelpers.MAX_LEAD)
      throw LithosUnprocessable(s"a window starting at epoch ${terms.windowStart} is more than " +
        s"${DFHelpers.MAX_LEAD} epochs after epoch $posting, so no fill would accept it")
    val default = ForwardsUserTransactions.defaultExpiry(chain.tip)
    val expiry = request.expiry.getOrElse(default)
    if (expiry < chain.tip + 2)
      throw LithosBadRequest(s"'expiry' $expiry leaves no block after the offer's own to fill it; " +
        s"the earliest is ${chain.tip + 2}")
    val lastFill = math.min(expiry, ForwardsUserTransactions.lastFillHeight(terms.windowStart))
    if (lastFill < chain.tip + 2)
      throw LithosUnprocessable(s"a window starting at epoch ${terms.windowStart} must start at least " +
        s"${DFHelpers.MIN_LEAD} epochs after the block before its fill, so no block after this offer's could fill " +
        s"it; the earliest start is ${posting + DFHelpers.MIN_LEAD}")

    val warnings = Seq(
      if (lastFill < expiry) Some(s"Fills stop at height $lastFill, before the expiry: after it, the window would " +
        s"start less than ${DFHelpers.MIN_LEAD} epochs after the fill.") else None,
      if (expiry > default) Some(s"The offer stays open past the end of epoch $posting, so a taker may fill it " +
        "using difficulty data from later epochs than your strike was set with.") else None,
      if (request.expiry.isEmpty && expiry - (chain.tip + 1) < ShortExpiryBlocks)
        Some(s"Posted late in epoch $posting, the offer expires in ${expiry - (chain.tip + 1)} blocks. Set " +
          "'expiry' to keep it open longer.") else None
    ).flatten
    NewOffer(makerLong, terms, expiry, lastFill, chain, warnings)
  }

  /** Why this client will not take on `terms`, or None: the short-window size limit. */
  private def sizeRefusal(terms: DFTerms): Option[String] =
    offerLimits.maxNotional(terms.windowLength).filter(terms.notional > _).map(max =>
      s"a notional of ${terms.notional} is above the $max base units this client takes on for windows under " +
        s"${offerLimits.shortWindowEpochs} epochs (forwards.offers.shortWindowMaxNotionalLit)")

  private def usableLimits(): Unit = offerLimits.invalid.foreach(reason => throw LithosUnavailable(reason))

  private def deployed(): DFContracts = deployment.getOrElse(throw LithosUnprocessable(
    s"no difficulty accumulator is deployed on ${nodeContext.getNetwork} yet, so no forward can be offered, " +
      "filled or settled"))

  /** Whether some coming block could still fill `offer`. The rest are left to their makers to cancel. */
  private def openOffer(offer: DFOffer, chain: Chain): Boolean =
    offer.funded && fillableUntil(offer) > chain.tip &&
      ForwardsUserTransactions.leadHolds(offer.terms.windowStart, chain.epoch)

  /** The last height `offer` can be filled at: its expiry, or earlier when its window would start too soon. */
  private def fillableUntil(offer: DFOffer): Int =
    math.min(offer.expiry, ForwardsUserTransactions.lastFillHeight(offer.terms.windowStart))

  /** What settling `position` on `acc` pays now, or a 422 naming the epoch it waits for. */
  private def settlementOf(ctx: BlockchainContext, acc: DFAccState, position: DFPosition): DFSettlement =
    ForwardsUserTransactions.settlement(acc, position, ForwardsTransactions.recordingAt(ctx)).getOrElse(
      throw LithosUnprocessable(s"position ${position.boxId} settles once epoch ${position.windowEnd} is recorded; " +
        s"the accumulator holds epochs up to ${acc.lastEpoch}"))

  // ══════════════════════════════════════════════════════════════════════════
  //  BOXES
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * The accumulator as the index lists it with unconfirmed spends applied, or the confirmed one when that
   * listing is not a single box; Left saying why when neither reads.
   */
  private def accumulatorOf(ctx: BlockchainContext, nodeApi: NodeApi,
                            contracts: DFContracts): Either[String, DFAccState] = {
    def listed(mempool: MempoolOptions): Seq[NodeBox] =
      nodeApi.unspentBoxesByTokenId(contracts.accNFT.toString, Paging(0, 2), SortDirection.Desc, mempool) match {
        case Success(boxes) => boxes.map(_.box)
        case Failure(ex) => throw LithosUnavailable(s"the node's index did not answer: ${ex.getMessage}", ex)
      }
    val single = Some(listed(MempoolOptions.WithMempool)).filter(_.size == 1)
      .orElse(Some(listed(MempoolOptions.ConfirmedOnly)).filter(_.size == 1)).map(_.head)
    single match {
      case None => Left(s"no single unspent box carries the accumulator NFT ${contracts.accNFT}")
      case Some(box) =>
        (for {
          acc <- DFBoxes.accumulator(box, contracts)
          input <- DFBoxes.input(box, ctx)
        } yield DFAccState(acc, input)).toRight(s"box ${box.boxId} carries the accumulator NFT but is not the accumulator")
    }
  }

  /**
   * The accumulator a settlement chains onto: the confirmed one, followed through the mempool. Refused while two
   * spends claim it, or while its newest spend records an epoch that is over, which the node will drop.
   */
  private def accumulatorTip(ctx: BlockchainContext, nodeApi: NodeApi, contracts: DFContracts,
                             snapshot: CompleteMempool.Snapshot): DFAccState = {
    val listed = nodeApi.unspentBoxesByTokenId(contracts.accNFT.toString, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly) match {
      case Success(boxes) => boxes
      case Failure(ex) => throw LithosUnavailable(s"the node's index did not answer: ${ex.getMessage}", ex)
    }
    val confirmed = listed match {
      case Seq(one) => DFBoxes.accumulator(one.box, contracts).getOrElse(throw LithosUnavailable(
        s"box ${one.boxId} carries the accumulator NFT but is not the accumulator"))
      case _ => throw LithosUnavailable(s"the index lists ${listed.size} confirmed boxes with the accumulator NFT")
    }
    val tip = BatchingMempool.poolTip(confirmed.box, contracts.accNFT.toString, BatchingMempool.spenders(snapshot))
      .getOrElse(throw LithosStateChanged("two unconfirmed transactions spend the accumulator; try again after the " +
        "next block"))
    val acc = (for {
      a <- DFBoxes.accumulator(tip, contracts)
      input <- DFBoxes.input(tip, ctx)
    } yield DFAccState(a, input)).getOrElse(throw LithosUnavailable(s"the accumulator's newest box ${tip.boxId} " +
      "does not read as the accumulator"))
    val epoch = DFMath.epochOf(ctx.getHeight)
    if (acc.lastEpoch != confirmed.lastEpoch && acc.lastEpoch != epoch)
      throw LithosStateChanged(s"the accumulator's unconfirmed spend records epoch ${acc.lastEpoch}, which is over, " +
        "so the node drops it and anything built on it; try again after the next block")
    acc
  }

  private def offerById(ctx: BlockchainContext, nodeApi: NodeApi, contracts: DFContracts,
                        boxId: String): (DFOffer, InputUTXO) = {
    val box = unspent(nodeApi, boxId)
    (for {
      offer <- DFBoxes.offer(box, contracts)
      input <- DFBoxes.input(box, ctx)
    } yield offer -> input).getOrElse(throw LithosNotFound(s"box $boxId is not a difficulty forward offer"))
  }

  private def ownOffer(ctx: BlockchainContext, nodeApi: NodeApi, contracts: DFContracts,
                       boxId: String): (DFOffer, InputUTXO) = {
    val found = offerById(ctx, nodeApi, contracts, boxId)
    if (!wallet.signableTrees.contains(found._1.maker.ergoTreeHex))
      throw LithosNotFound(s"no offer made by this wallet has id $boxId")
    found
  }

  private def positionById(ctx: BlockchainContext, nodeApi: NodeApi, contracts: DFContracts,
                           boxId: String): (DFPosition, InputUTXO) = {
    val box = unspent(nodeApi, boxId)
    (for {
      position <- DFBoxes.position(box, contracts)
      input <- DFBoxes.input(box, ctx)
    } yield position -> input).getOrElse(throw LithosNotFound(s"box $boxId is not a difficulty forward position"))
  }

  /** The unspent box `boxId`, unconfirmed ones included. */
  private def unspent(nodeApi: NodeApi, boxId: String): NodeBox = {
    if (!boxId.matches("[0-9a-fA-F]{64}")) throw LithosBadRequest(s"'$boxId' is not a box id")
    nodeApi.boxesWithPoolByIds(Seq(boxId)) match {
      case Success(found) => found.find(_.boxId.equalsIgnoreCase(boxId))
        .getOrElse(throw LithosNotFound(s"no unspent box has id $boxId"))
      case Failure(ex) => throw LithosUnavailable(s"could not read box $boxId from the node: ${ex.getMessage}", ex)
    }
  }

  /** Refuses a box an unconfirmed transaction already spends. */
  private def unclaimed(snapshot: CompleteMempool.Snapshot, boxId: String, what: String): Unit =
    BatchingMempool.spenders(snapshot).get(boxId).flatMap(_.headOption).foreach { tx =>
      throw LithosStateChanged(s"$what $boxId is already spent by unconfirmed transaction ${tx.id}")
    }

  /**
   * Every box at `tree` that `read` accepts, newest first, with whether the scan stopped at its cap. Pages are
   * read and dropped, keeping only what `read` returns.
   */
  private def scan[A](nodeApi: NodeApi, tree: String)(read: NodeBox => Option[A]): (Vector[A], Boolean) = {
    var offset = 0
    var exhausted = false
    val found = Vector.newBuilder[A]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByErgoTree(tree, Paging(offset, Batcher.PageSize), SortDirection.Desc,
        MempoolOptions.WithMempool) match {
        case Success(boxes) => boxes
        case Failure(ex) => throw LithosUnavailable(s"the node's index did not answer: ${ex.getMessage}", ex)
      }
      found ++= page.flatMap(indexed => read(indexed.box))
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    (found.result(), !exhausted)
  }

  /**
   * Spent boxes at `tree` that `read` accepts, with the transaction that spent each, most recently spent first
   * and at most [[MaxHistory]] of them. The index lists every box the tree ever held, so the scan is capped like
   * [[scan]], and whether either cap cut it short comes back with it.
   */
  private def scanSpent[A](nodeApi: NodeApi, tree: String)(read: NodeBox => Option[A]): (Vector[(A, String)], Boolean) = {
    var offset = 0
    var total = Int.MaxValue
    val found = Vector.newBuilder[(A, String, Int)]
    while (offset < total && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.boxesByErgoTree(tree, Paging(offset, Batcher.PageSize)) match {
        case Success(p) => p
        case Failure(ex) => throw LithosUnavailable(s"the node's index did not answer: ${ex.getMessage}", ex)
      }
      total = page.total
      found ++= page.items.flatMap(b => b.spentTransactionId.flatMap(txId =>
        read(b.box).map(a => (a, txId, b.spendingHeight.getOrElse(0)))))
      offset = if (page.items.isEmpty) total else offset + page.items.size
    }
    val all = found.result().sortBy(-_._3)
    (all.take(MaxHistory).map { case (a, txId, _) => a -> txId }, offset < total || all.size > MaxHistory)
  }

  /** The confirmed transaction `txId`, or None when the index does not hold it. */
  private def transaction(nodeApi: NodeApi, txId: String): Option[IndexedTransaction] =
    nodeApi.indexedTransactionById(txId) match {
      case Success(tx) => tx
      case Failure(ex) => throw LithosUnavailable(s"could not read transaction $txId from the node: ${ex.getMessage}", ex)
    }

  /**
   * A complete, fresh mempool observation from the engine. A box it shows spent is refused, rather than
   * signing a transaction the node would reject as a double spend.
   */
  protected def mempoolSnapshot(): CompleteMempool.Snapshot = {
    val wait = Batcher.ObservationTimeout
    val observed = Try(Await.result((walletSelector.walletRef ? CompleteMempool.Refresh)(Timeout(wait))
      .mapTo[CompleteMempool.Observation], wait)).getOrElse(throw LithosUnavailable(
      "no mempool observation arrived from the transaction engine"))
    if (!observed.fresh) throw LithosUnavailable(
      s"no fresh mempool observation: ${observed.failure.getOrElse("the last one is too old")}")
    observed.snapshot.get
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  PLUMBING
  // ══════════════════════════════════════════════════════════════════════════

  private def withCtx[A](f: (BlockchainContext, NodeApi) => A): A = {
    val nodeApi = executionNode
    nodeContext.getClient.execute(ctx => f(ctx, nodeApi))
  }

  /** @param reservation the wallet inputs funding the transaction, or None when it spends none */
  private case class Funded(signed: SignedTransaction, reservation: Option[FundingAllocation])

  /**
   * Reserves what `plan` needs, builds and signs, releasing the reservation if any step throws. Signing runs
   * the offer's script on a fill, so a fill the contract refuses fails here rather than at the node.
   */
  private def fund(ctx: BlockchainContext, plan: DFPlan): Funded = {
    stillRunning()
    val reservation =
      if (plan.value == 0L && plan.tokens.isEmpty) None else Some(walletSelector.reserve(plan.value, plan.tokens))
    try {
      val unsigned = plan.build(reservation.map(_.inputs).getOrElse(Seq.empty))
      stillRunning()
      Funded(Batcher.withNodeMinimums(ctx, wallet.sign(unsigned)), reservation)
    } catch {
      case NonFatal(ex) => reservation.foreach(_.release()); throw ex
    }
  }

  private def send(built: Funded): EngineBroadcast.Result = broadcast(built.signed, built.reservation.toSeq)

  /**
   * Sends `signed`. A throw from here always comes before the node call, so the one the engine's stop causes
   * is reported as nothing sent.
   */
  private def broadcast(signed: SignedTransaction, funding: Seq[FundingAllocation]): EngineBroadcast.Result =
    try new EngineBroadcast(walletSelector.walletRef, executionNode)(walletSelector.executionContext)
      .send(signed, funding, ForwardsAPIExecution.operation(signed), alive)
    catch { case _: IllegalArgumentException if !alive() => throw stopped }

  /** Goes no further once the engine has stopped this attempt, because its time ran out or the engine restarted. */
  private def stillRunning(): Unit = if (!alive()) throw stopped

  private def addressOf(contract: work.lithos.mutations.Contract): String =
    Try(contract.address(nodeContext.getNetwork).toString).getOrElse(contract.ergoTreeHex)

  /** nanoERG a cancel returns: the offer's whole value, less the fee when the offer pays it. */
  private def refunded(box: InputUTXO, feeFromOffer: Boolean): Long =
    if (feeFromOffer) box.value - ForwardsUserTransactions.TxFee else box.value
}

object ForwardsAPIExecution {

  /** The engine operation a forwards mutation is sent under, keyed by its transaction id. */
  def operation(signed: SignedTransaction): String =
    "forwards:" + _root_.transactions.candidate.BlockTxMessages.CandidateTx.txId(signed)

  final val NotComputed: () => Either[String, NextDifficultyEstimate] =
    () => Left("the next epoch's difficulty is not computed for this request")

  /** Why a mutation stopped before anything reached the node. */
  def stopped: LithosUnavailable = LithosUnavailable("the transaction engine stopped this request before sending " +
    "it, because its time ran out or the engine restarted, so nothing was sent")

  final val DefaultPage: Int = 50
  final val MaxPage: Int = 200

  /** Most settled positions, and most closed offers, a history lists. Each costs one transaction read. */
  final val MaxHistory: Int = 200

  final val Waiting = "waiting"
  final val InWindow = "in_window"
  final val Settleable = "settleable"
  final val Refundable = "refundable"

  /** A fill whose last valid height is this close is warned about, since a slow block would void it. */
  private final val LateFillBlocks = 3

  /** A default expiry this close is warned about, since the offer barely outlives its own confirmation. */
  private final val ShortExpiryBlocks = 16

  private final val NoTiming = "the node context lists no headers, so a fill's timing cannot be checked"

  /** The most this side of `terms` can lose: its margin and its rewards. */
  def maxLoss(terms: DFTerms): Long = DFMath.sideLit(terms.notional)

  def sideName(long: Boolean): String = if (long) "long" else "short"

  /** `strike` against `difficulty`, as a fraction. */
  def move(strike: Long, difficulty: BigInt): Option[Double] =
    if (difficulty <= 0) None else Some(ratio(BigInt(strike), difficulty))

  /** `a / b - 1`. */
  def ratio(a: BigInt, b: BigInt): Double = (BigDecimal(a) / BigDecimal(b) - 1).toDouble

  /** LIT the long side receives above its margin when the window sums to `total`, as the position computes it. */
  def payoutAt(total: BigInt, t: DFTerms): BigInt = {
    val base = BigInt(t.windowLength) * t.strike
    val cap = BigInt(DFMath.margin(t.notional))
    (BigInt(t.notional) * (total - base) / base).max(-cap).min(cap)
  }

  /** A request amount, refused as a bad request when it is not a decimal integer. */
  private def amount(field: String, raw: String): Long =
    Try(LDAmounts.parseLong(field, raw)).getOrElse(
      throw LithosBadRequest(s"'$field' must be a decimal integer string within 64 bits, got '$raw'"))
}
