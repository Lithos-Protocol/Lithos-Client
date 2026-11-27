package transactions.batching.sigmafi.zc

import configs.SigmaFiBatchingConfig
import mutations.NodeWallet
import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.LoggerFactory
import sigma.ast.ErgoTree
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.candidate.CandidateBudget
import work.lithos.mutations.{Contract, InputUTXO}

import scala.collection.mutable
import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/**
 * Planned and signed ZC batching work for one build pass (candidate block or broadcast pass).
 *
 * @param txs          built transactions in dependency order, with any same-pass parent tx ids
 * @param failedIds    single boxes that failed to sign and should enter the skip list
 */
final case class ZcBatchBuild(txs: Vector[(ZcBatchTx, Seq[String])], failedIds: Set[String])

/**
 * Off-critical-path precomputed zcs1 offer-match runs for one confirmed book revision and resolver generation.
 * Handed into [[ZcBatchPlanner.build]] on the candidate critical path so a 10,000-box book skips `ZcMarketBook.of`
 * and `ZcMatcher.batch` completely.
 */
final case class ZcPrecomputedMatches(headerId: String, height: Int, resolverGen: Long, marketBook: ZcMarketBook,
                                      runs: Map[ZcMarket, ZcBatchRun], ordersById: Map[String, ZcOrder],
                                      asksById: Map[String, ZcAsk], offersById: Map[String, ZcOffer],
                                      askBondIds: Map[String, String] = Map.empty, oracleBoxId: Option[String] = None) {
  def matches(book: ZcBook, matcherHeight: Int, gen: Long, claimed: scala.collection.Set[String]): Boolean =
    headerId == book.headerId && height == matcherHeight && resolverGen == gen &&
      oracleBoxId == book.oracle.map(_.box.boxId) &&
      runs.values.forall(r => !r.taken.keys.exists(claimed.contains) && !r.takenBy.keys.exists(claimed.contains)) &&
      !askBondIds.values.exists(claimed.contains)
}

/**
 * Pure planner and builder for SigmaFi ZC Standard batching over a confirmed [[ZcBook]].
 *
 * Handles every zcs1 batchable action — bond liquidations (maturity and early oracle), offer matches against orders
 * and asks (chaining through continuations), share and wrap mints, vault pulls, expiry refunds and paid storage-rent
 * refreshes — with strict spam bounds: separate fee floors (0.05 ERG liquidations, 0.002 ERG other actions), highest
 * reward first, bounded resolver reads, capped unbuildable failures, binary bisection of failing packs and exact
 * budget enforcement.
 */
object ZcBatchPlanner {

  private val logger = LoggerFactory.getLogger("ZcBatchPlanner")

  /** Most resolver node reads during a latency-sensitive candidate build; background ticks warm caches first. */
  final val CandidateResolverReads = 32
  /** Most resolver node reads during a background cache-warm or broadcast pass. */
  final val BackgroundResolverReads = 128
  /** Most offer-match chains attempted per build pass. */
  final val MaxMatchesPerBuild = 16
  /** Most consecutive signing failures tolerated per build pass before stopping, capping spam CPU cost. */
  final val MaxUnbuildablePerRun = 16
  /** Most packable pieces considered per build pass, highest reward first. */
  final val MaxPackablePieces = 80
  /** Most standalone actions (mints, pulls, wrap refreshes) considered per build pass. */
  final val MaxStandaloneActions = 32

  /** A candidate packable piece with its eligibility height (for `broadcastAfterBlocks`) and reward for ranking. */
  private final case class PackItem(boxId: String, reward: Long, eligibleAt: Long, aged: Boolean,
                                    build: BlockchainContext => Option[ZcPiece])

  /** A candidate standalone action (share mint, pull, wrap mint, wrap refresh). None means skipped (undisclosed/unwarmed). */
  private final case class StandaloneItem(ids: Set[String], reward: Long, eligibleAt: Long,
                                          build: (BlockchainContext, Contract, Long, Int) => Option[Try[ZcBatchTx]])

  /** Decodes an ErgoTree hex string into a `Contract`. */
  private def contractOf(treeHex: String): Option[Contract] = Try {
    sigma.VersionContext.withVersions(sigma.VersionContext.V6SoftForkVersion, sigma.VersionContext.V6SoftForkVersion) {
      Contract(ErgoTree.fromHex(treeHex))
    }
  }.toOption

  private def inputOf(box: NodeBox, ctx: BlockchainContext): Option[InputUTXO] =
    SigmaFiBoxes.input(box, ctx).filter(_.id.toString == box.boxId)

  /**
   * Pre-warms `resolver` caches (disclosed scripts, origins, serialized box bytes) for batchable boxes in `book`,
   * under a bounded read budget. Called on background ticks so candidate builds make zero node reads.
   */
  def warmCaches(book: ZcBook, ctx: BlockchainContext, resolver: ZcResolver, settings: SigmaFiBatchingConfig,
                 height: Long, budget: ZcBudget = new ZcBudget(BackgroundResolverReads)): Unit = {
    val marketBook = ZcMarketBook.of(ZcView(book, None, 0L))
    def notUndisclosed(r: ZcRecord): Boolean = !resolver.knownDisclosed(r).contains(false)
    // Scripts for active offers, orders, asks, lots, mint requests and aged bonds (skipping known-undisclosed spam)
    marketBook.offers.iterator.filter(o => notUndisclosed(o.record)).take(ZcMatcher.MaxOffers)
      .foreach(o => resolver.script(o.record, pending = false, budget))
    marketBook.orders.iterator.filter(o => notUndisclosed(o.record)).take(ZcMatcher.MaxSources)
      .foreach(o => resolver.script(o.record, pending = false, budget))
    marketBook.asks.iterator.filter(a => notUndisclosed(a.record)).take(ZcMatcher.MaxSources).foreach { a =>
      resolver.script(a.record, pending = false, budget)
      val token = a.record.token.tokenId
      val origin = resolver.origin(token, budget)
      origin.foreach { o =>
        if (o.kind == ZcKind.Order || o.kind == ZcKind.PricedOrder) {
          resolver.boxBytes(token, ctx, budget)
        } else if (o.kind == ZcKind.DutchAuctionMint) {
          resolver.boxBytes(token, ctx, budget)
          marketBook.shareVaults.get(token).foreach { v =>
            resolver.origin(v.record.bondAsset, budget)
            resolver.boxBytes(v.record.bondAsset, ctx, budget)
          }
        }
      }
    }
    marketBook.lots.iterator.filter(l => notUndisclosed(l.record)).take(MaxPackablePieces)
      .foreach(l => resolver.script(l.record, pending = false, budget))
    marketBook.shareRequests.iterator.filter(r => notUndisclosed(r.record)).take(MaxStandaloneActions).foreach { r =>
      resolver.script(r.record, pending = false, budget)
      if (r.record.dutch.isDefined) {
        book.stateOf(r.record.bondAsset).map(_.record).collect { case s: ZcSettlement => s }
          .foreach(s => resolver.settledBond(s.bondBoxId, ctx, budget))
      }
    }
    marketBook.wrapRequests.iterator.filter(r => notUndisclosed(r.record)).take(MaxStandaloneActions)
      .foreach(r => resolver.script(r.record, pending = false, budget))
    marketBook.bonds.valuesIterator
      .filter(b => notUndisclosed(b.record) && ZcBatchTransactions.refreshable(b.record.box.creationHeight.toLong, height))
      .take(MaxPackablePieces).foreach(b => resolver.script(b.record, pending = false, budget))
  }

  /**
   * Precomputes zcs1 offer matches across both markets for `book` off the candidate critical path, using only
   * cached origins and known-disclosure flags (zero node reads).
   */
  def precomputeMatches(book: ZcBook, resolver: ZcResolver): ZcPrecomputedMatches = {
    val gen = resolver.generation
    val marketBook = ZcMarketBook.of(ZcView(book, None, 0L))
    val matcher = new ZcMatcher(marketBook, resolver.knownOrigin,
      excluded = r => resolver.knownDisclosed(r).contains(false))
    val runs = ZcMarket.all.map(m => m -> matcher.batch(m, rounds = math.min(ZcMatcher.Rounds, 5))).toMap
    val ordersById = marketBook.orders.map(o => o.boxId -> o.record).toMap
    val asksById = marketBook.asks.map(a => a.boxId -> a.record).toMap
    val offersById = marketBook.offers.map(o => o.boxId -> o.record).toMap
    val askBondIds = runs.valuesIterator.flatMap(_.takenBy.keysIterator).flatMap { id =>
      asksById.get(id).flatMap(a => matcher.askSource(ZcOpen(a, pending = false)).toOption.map(s => id -> s.bond.boxId))
    }.toMap
    ZcPrecomputedMatches(book.headerId, book.height, gen, marketBook, runs, ordersById, asksById, offersById, askBondIds,
      book.oracle.map(_.box.boxId))
  }

  /**
   * Builds ZC batching transactions for `signingHeight` from `book`, within `slots`, `budget` and `deadline`.
   *
   * @param book           confirmed ZC book
   * @param signingHeight  `blockHeight` for a candidate block, or `tip` for a broadcast pass
   * @param minerFee       0 for a candidate block, `Parameters.MinFee` for a broadcast pass
   * @param feeTo          recipient of collected batching fees
   * @param excluded       box ids to skip (skip list, mempool-spent boxes on broadcast, refused boxes)
   * @param oracleUsable   false while a mempool refresh spends the oracle box (oracle-dependent paths wait)
   * @param broadcastWait  `Some(broadcastAfterBlocks)` on broadcast passes; `None` on candidate builds
   * @param precomputed    optional off-critical-path match runs for `book` (reused when revision and resolver match)
   * @param onComputed     optional callback receiving freshly computed clean match runs for later refreshes
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, book: ZcBook, resolver: ZcResolver,
            settings: SigmaFiBatchingConfig, signingHeight: Int, minerFee: Long, feeTo: Contract,
            slots: Int, allowance: CandidateBudget, deadline: Option[Deadline],
            excluded: Set[String] = Set.empty, oracleUsable: Boolean = true,
            broadcastWait: Option[Int] = None,
            resolverReads: Int = CandidateResolverReads,
            precomputed: Option[ZcPrecomputedMatches] = None,
            onComputed: ZcPrecomputedMatches => Unit = _ => ()): ZcBatchBuild = {
    if (slots <= 0) ZcBatchBuild(Vector.empty, Set.empty)
    else {
      val readBudget = new ZcBudget(resolverReads)
      val h = signingHeight.toLong
      val next = if (minerFee == 0L) h else h + 1L
      val oracle = if (oracleUsable) book.oracle else None
      val oracleIn = oracle.flatMap(o => inputOf(o.box, ctx))
      val minLiq = math.max(settings.minBatchingFeeNanoErg, minerFee + ZcBoxes.MinBox)
      val minAct = math.max(settings.minActionFeeNanoErg, minerFee + ZcBoxes.MinBox)

      def waitCleared(eligibleAt: Long): Boolean =
        broadcastWait.forall(wait => next >= eligibleAt + wait.toLong)

      def scriptFor(r: ZcRecord): Option[Contract] =
        resolver.script(r, pending = false, readBudget).flatMap(contractOf)

      def knownUndisclosed(r: ZcRecord): Boolean =
        resolver.knownDisclosed(r).contains(false)

      def overdue: Boolean = deadline.exists(_.isOverdue())

      val claimed = mutable.HashSet.empty[String] ++ excluded
      val failed = mutable.HashSet.empty[String]
      val built = Vector.newBuilder[(ZcBatchTx, Seq[String])]
      var usedSlots = 0
      var remainingBudget = allowance
      var unbuildable = 0

      def fits(tx: ZcBatchTx): Boolean =
        usedSlots < slots && tx.sizeBytes <= remainingBudget.maxBytes && tx.cost <= remainingBudget.maxCost

      def recordTx(tx: ZcBatchTx, parents: Seq[String] = Seq.empty): Boolean =
        if (!fits(tx)) false
        else {
          built += (tx -> parents)
          claimed ++= tx.spentIds
          usedSlots += 1
          remainingBudget = remainingBudget.less(tx.sizeBytes, tx.cost)
          true
        }

      // ─── 1. Gather packable items (liquidations, refunds, refreshes) ────────
      val packable = Vector.newBuilder[PackItem]

      book.boxes.valuesIterator.filterNot(e => claimed.contains(e.boxId)).foreach { entry =>
        entry.record match {
          case b: ZcBond =>
            val rule = ZcRules.rule(b.liqHash, b.market)
            val matured = h >= b.maturity
            val payload = b.terms.drop(7)
            val earlyOracle = !matured && rule == ZcRule.Oracle && oracle.isDefined && payload.size >= 2 &&
              oracle.exists(o => ZcRules.fresh(o, payload(1), h - (if (minerFee == 0L) 1L else 0L)) &&
                ZcRules.belowOwnRatio(ZcBondTerms.of(b), o.price))
            val canLiq = rule != ZcRule.Other && (matured || earlyOracle) &&
              b.feeReserve >= minLiq && b.box.value - b.feeReserve >= ZcBoxes.MinBox
            if (canLiq) {
              val elig = if (matured) b.maturity else math.max(entry.height.toLong, oracle.get.box.creationHeight.toLong)
              if (waitCleared(elig)) {
                val expr = ZcContracts.expression(
                  if (rule == ZcRule.Maturity) ZcExpression.MaturityLiquidation else ZcExpression.OracleLiquidation,
                  if (rule == ZcRule.Maturity) None else Some(b.market)).bytes
                val needsOracle = !matured && rule == ZcRule.Oracle
                packable += PackItem(b.boxId, b.feeReserve, elig, aged = false, c =>
                  if (needsOracle && oracleIn.isEmpty) None
                  else inputOf(b.box, c).map(in =>
                    ZcBatchTransactions.liquidationPiece(b, in, expr, if (needsOracle) oracleIn else None)))
              }
            } else if (!knownUndisclosed(b) && ZcBatchTransactions.refreshable(b.box.creationHeight.toLong, h) &&
              (h >= b.maturity || next < b.maturity - ZcPricing.RefreshMaturityGap)) {
              val bounty = b.box.value - ZcBatchTransactions.refreshedValue(b.box.value, b.feeReserve + ZcBoxes.MinBox)
              val elig = b.box.creationHeight.toLong + ZcPricing.RefreshAge + 1L
              if (bounty >= settings.minActionFeeNanoErg && waitCleared(elig)) {
                packable += PackItem(b.boxId, bounty, elig, aged = true, c =>
                  for (in <- inputOf(b.box, c); borrower <- scriptFor(b))
                    yield ZcBatchTransactions.bondRefreshPiece(c, b, in, borrower, signingHeight))
              }
            }

          case o: ZcOrder if !knownUndisclosed(o) && h >= o.expiry && o.expiryFee >= settings.minActionFeeNanoErg &&
            o.expiryFee >= ZcPricing.MinBatchingFee && o.expiryFee <= o.box.value - ZcBoxes.MinBox &&
            waitCleared(o.expiry) =>
            packable += PackItem(o.boxId, o.expiryFee, o.expiry, aged = false, c =>
              for (in <- inputOf(o.box, c); borrower <- scriptFor(o))
                yield ZcBatchTransactions.orderRefundPiece(o, in, borrower))

          case o: ZcOffer if !knownUndisclosed(o) && h >= o.expiry && ZcRules.batcherFee(o) >= settings.minActionFeeNanoErg &&
            ZcRules.batcherFee(o) <= o.box.value - ZcBoxes.MinBox && waitCleared(o.expiry) =>
            val fee = ZcRules.batcherFee(o)
            packable += PackItem(o.boxId, fee, o.expiry, aged = false, c =>
              for (in <- inputOf(o.box, c); owner <- scriptFor(o))
                yield ZcBatchTransactions.offerRefundPiece(o, in, owner))

          case a: ZcAsk if !knownUndisclosed(a) && h >= a.expiry && a.box.value - ZcBoxes.MinBox >= settings.minActionFeeNanoErg &&
            waitCleared(a.expiry) =>
            val fee = a.box.value - ZcBoxes.MinBox
            packable += PackItem(a.boxId, fee, a.expiry, aged = false, c =>
              for (in <- inputOf(a.box, c); seller <- scriptFor(a))
                yield ZcBatchTransactions.askRefundPiece(a, in, seller))

          case l: ZcLot if !knownUndisclosed(l) && h >= l.expiry && ZcRules.lotRefundFee(l) >= settings.minActionFeeNanoErg &&
            l.box.value - ZcRules.lotRefundFee(l) >= ZcBoxes.MinBox && waitCleared(l.expiry) =>
            val fee = ZcRules.lotRefundFee(l)
            packable += PackItem(l.boxId, fee, l.expiry, aged = false, c =>
              for (in <- inputOf(l.box, c); seller <- scriptFor(l))
                yield ZcBatchTransactions.lotRefundPiece(l, in, seller))

          case s: ZcSettlement if ZcBatchTransactions.refreshable(s.box.creationHeight.toLong, h) =>
            val bounty = s.box.value - ZcBatchTransactions.refreshedValue(s.box.value, ZcBoxes.MinBox)
            val elig = s.box.creationHeight.toLong + ZcPricing.RefreshAge + 1L
            if (bounty >= settings.minActionFeeNanoErg && waitCleared(elig)) {
              packable += PackItem(s.boxId, bounty, elig, aged = false, c =>
                inputOf(s.box, c).map(ZcBatchTransactions.settlementRefreshPiece(c, s, _, signingHeight)))
            }

          case v: ZcShareVault if ZcBatchTransactions.refreshable(v.box.creationHeight.toLong, h) =>
            val floor = if (v.holding) ZcBoxes.MinBox + v.pullFee else ZcBoxes.MinBox
            val bounty = v.box.value - ZcBatchTransactions.refreshedValue(v.box.value, floor)
            val elig = v.box.creationHeight.toLong + ZcPricing.RefreshAge + 1L
            if (bounty >= settings.minActionFeeNanoErg && waitCleared(elig)) {
              packable += PackItem(v.boxId, bounty, elig, aged = false, c =>
                inputOf(v.box, c).map(ZcBatchTransactions.shareVaultRefreshPiece(c, v, _, signingHeight)))
            }

          case _ => ()
        }
      }

      val (agedItems, regularItems) = packable.result().sortBy(i => (-i.reward, i.boxId))
        .take(MaxPackablePieces).partition(_.aged)

      /** `item`'s piece, or None when it cannot be built here, such as a refresh whose box cannot pay its own size. */
      def pieceOf(item: PackItem): Option[ZcPiece] =
        if (overdue) None
        else Try(item.build(ctx)) match {
          case Success(piece) => piece
          case Failure(ex) =>
            unbuildable += 1
            failed += item.boxId
            logger.warn(s"Could not build ZC pack piece ${item.boxId} at $signingHeight: ${ex.getMessage}")
            None
        }

      /** Signs a chunk of pieces, bisecting on signing failure or budget overflow so one bad box costs only itself. */
      def buildPackChunk(pieces: Vector[ZcPiece]): Unit = {
        if (pieces.isEmpty || usedSlots >= slots || overdue || unbuildable >= MaxUnbuildablePerRun) return
        val total = pieces.map(_.reward).sum
        if (total - minerFee < ZcBoxes.MinBox) return
        Try(ZcBatchTransactions.pack(ctx, wallet, pieces, feeTo, minerFee, signingHeight)) match {
          case Success(tx) if fits(tx) => recordTx(tx)
          case Success(_) if pieces.size == 1 => ()
          case Failure(ex) if pieces.size == 1 =>
            unbuildable += 1
            failed += pieces.head.boxId
            logger.warn(s"Could not build ZC pack piece ${pieces.head.boxId} at $signingHeight: ${ex.getMessage}")
          case _ =>
            val (left, right) = pieces.splitAt(pieces.size / 2)
            buildPackChunk(left)
            buildPackChunk(right)
        }
      }

      // Pack regular items (liquidations, refunds, vault refreshes) first — highest revenue per slot
      if (!overdue && regularItems.nonEmpty) {
        val resolvedPieces = regularItems.flatMap(pieceOf)
        resolvedPieces.grouped(ZcBatchTransactions.PackLimit).foreach(chunk => buildPackChunk(chunk.toVector))
      }

      // ─── 2. Offer matches (zcs1 matching order, chaining offer continuations) ─
      if (!overdue && usedSlots < slots && oracleIn.isDefined && unbuildable < MaxUnbuildablePerRun) {
        buildOfferMatches(ctx, wallet, book, resolver, readBudget, settings, signingHeight, minerFee, feeTo,
          oracleIn.get, minAct, broadcastWait, deadline, precomputed, onComputed, claimed, failed,
          () => usedSlots < slots && !overdue && unbuildable < MaxUnbuildablePerRun,
          recordTx, () => { unbuildable += 1 })
      }

      // ─── 3. Standalone actions (pulls, share mints, wrap mints, wrap refreshes) ─
      if (!overdue && usedSlots < slots && unbuildable < MaxUnbuildablePerRun) {
        val standalone = Vector.newBuilder[StandaloneItem]
        val settlementsByAsset = book.boxes.valuesIterator
          .collect { case e @ ZcEntry(s: ZcSettlement, _) if !claimed.contains(e.boxId) => s.asset -> (s, e.height) }.toMap

        book.boxes.valuesIterator.filterNot(e => claimed.contains(e.boxId)).foreach { entry =>
          entry.record match {
            case v: ZcShareVault if v.holding && v.pullFee >= minAct && v.pullFee <= v.box.value =>
              settlementsByAsset.get(v.bondAsset).foreach { case (s, sHeight) =>
                val elig = math.max(entry.height, sHeight).toLong
                if (waitCleared(elig)) {
                  standalone += StandaloneItem(Set(v.boxId, s.boxId), v.pullFee, elig, (c, to, mFee, sh) => Some(Try {
                    val vIn = inputOf(v.box, c).getOrElse(throw new IllegalStateException("share vault input invalid"))
                    val sIn = inputOf(s.box, c).getOrElse(throw new IllegalStateException("settlement input invalid"))
                    ZcBatchTransactions.pull(c, wallet, v, vIn, s, sIn, to, mFee, sh)
                  }))
                }
              }

            case r: ZcShareRequest if !knownUndisclosed(r) && r.terms.size >= 3 =>
              val Seq(n, pullFee, mintFee) = r.terms.take(3)
              val valid = n >= 1L && n <= 1000000000L && pullFee >= ZcPricing.MinBatchingFee &&
                mintFee >= minAct &&
                BigInt(pullFee) + BigInt(mintFee) + BigInt(2L * ZcBoxes.MinBox) <= BigInt(r.box.value) &&
                (r.dutch.isEmpty || ZcRules.auctionTermsValid(r.terms))
              val elig = entry.height.toLong
              if (valid && waitCleared(elig)) {
                standalone += StandaloneItem(Set(r.boxId), mintFee, elig, (c, to, mFee, sh) =>
                  scriptFor(r).flatMap { owner =>
                    val stateOpt: Option[Option[(InputUTXO, Long, Option[Array[Byte]])]] = r.dutch match {
                      case None => Some(None)
                      case Some(_) =>
                        book.stateOf(r.bondAsset).map(_.record) match {
                          case Some(b: ZcBond) if !claimed.contains(b.boxId) =>
                            inputOf(b.box, c).map(bIn => Some((bIn, b.repayment, None)))
                          case Some(s: ZcSettlement) if !claimed.contains(s.boxId) =>
                            for (sIn <- inputOf(s.box, c); (rep, bytes) <- resolver.settledBond(s.bondBoxId, c, readBudget))
                              yield Some((sIn, rep, Some(bytes)))
                          case _ => None
                        }
                    }
                    stateOpt.map { state =>
                      Try {
                        val in = inputOf(r.box, c).getOrElse(throw new IllegalStateException("share request input invalid"))
                        ZcBatchTransactions.mintShares(c, wallet, r, in, owner, state, to, mFee, sh)
                      }
                    }
                  })
              }

            case r: ZcWrapRequest if !knownUndisclosed(r) && r.mintFee >= minAct &&
              r.mintFee <= r.box.value - 2L * ZcBoxes.MinBox && waitCleared(entry.height.toLong) =>
              standalone += StandaloneItem(Set(r.boxId), r.mintFee, entry.height.toLong, (c, to, mFee, sh) =>
                scriptFor(r).map { owner =>
                  Try {
                    val in = inputOf(r.box, c).getOrElse(throw new IllegalStateException("wrap request input invalid"))
                    ZcBatchTransactions.mintWrap(c, wallet, r, in, owner, to, mFee, sh)
                  }
                })

            case v: ZcWrapVault if ZcBatchTransactions.refreshable(v.box.creationHeight.toLong, h) =>
              val bounty = v.box.value - ZcBatchTransactions.refreshedValue(v.box.value, ZcBoxes.MinBox)
              val elig = v.box.creationHeight.toLong + ZcPricing.RefreshAge + 1L
              if (bounty >= minAct && waitCleared(elig)) {
                standalone += StandaloneItem(Set(v.boxId), bounty, elig, (c, to, mFee, sh) => Some(Try {
                  val in = inputOf(v.box, c).getOrElse(throw new IllegalStateException("wrap vault input invalid"))
                  ZcBatchTransactions.refreshWrapVault(c, wallet, v, in, to, mFee, sh)
                }))
              }

            case _ => ()
          }
        }

        standalone.result().sortBy(s => (-s.reward, s.ids.headOption.getOrElse("")))
          .take(MaxStandaloneActions).foreach { item =>
            if (usedSlots < slots && !overdue && unbuildable < MaxUnbuildablePerRun &&
              !item.ids.exists(claimed.contains)) {
              item.build(ctx, feeTo, minerFee, signingHeight).foreach {
                case Success(tx) => recordTx(tx)
                case Failure(ex) =>
                  unbuildable += 1
                  failed ++= item.ids
                  logger.warn(s"Could not build standalone ZC tx for ${item.ids.mkString(",")}: ${ex.getMessage}")
              }
            }
          }
      }

      // ─── 4. Aged bond refreshes (packed apart per EIP-39) ──────────────────
      if (!overdue && usedSlots < slots && agedItems.nonEmpty && unbuildable < MaxUnbuildablePerRun) {
        val resolvedAged = agedItems.filterNot(i => claimed.contains(i.boxId))
          .flatMap(pieceOf)
        resolvedAged.grouped(ZcBatchTransactions.PackLimit).foreach(chunk => buildPackChunk(chunk.toVector))
      }

      ZcBatchBuild(built.result(), failed.toSet)
    }
  }

  /**
   * Runs zcs1's matcher across both markets and builds signed match transactions, chaining through each offer's
   * live continuation box so one offer can fill multiple orders/asks in the same block.
   */
  private def buildOfferMatches(ctx: BlockchainContext, wallet: NodeWallet, book: ZcBook, resolver: ZcResolver,
                                readBudget: ZcBudget, settings: SigmaFiBatchingConfig, signingHeight: Int,
                                minerFee: Long, feeTo: Contract, oracleIn: InputUTXO, minAct: Long,
                                broadcastWait: Option[Int], deadline: Option[Deadline],
                                precomputed: Option[ZcPrecomputedMatches],
                                onComputed: ZcPrecomputedMatches => Unit,
                                claimed: mutable.HashSet[String],
                                failed: mutable.HashSet[String], canContinue: () => Boolean,
                                recordTx: (ZcBatchTx, Seq[String]) => Boolean, onFailure: () => Unit): Unit = {
    // Matcher operates at `matcherHeight` so `at = matcherHeight + 1` equals the block where the tx lands.
    val matcherHeight = if (minerFee == 0L) signingHeight - 1 else signingHeight
    val startGen = resolver.generation
    val cleanStart = claimed.isEmpty && book.height == matcherHeight
    val cached = precomputed.filter(_.matches(book, matcherHeight, startGen, claimed))
    val marketBook = cached.map(_.marketBook).getOrElse {
      val adjustedBook = if (book.height == matcherHeight) book else book.copy(height = matcherHeight)
      ZcMarketBook.of(ZcView(adjustedBook, None, 0L))
    }
    val originLookup: String => Option[ZcOrigin] = token => resolver.origin(token, readBudget)
    // A broadcast may land in any of the blocks a priced order is quoted over; this miner's block lands next.
    val lateBlocks = if (minerFee == 0L) 0L else ZcPricing.QuoteBlocks - 1L
    val matcher = new ZcMatcher(marketBook, originLookup,
      excluded = r => claimed.contains(r.boxId) || resolver.knownDisclosed(r).contains(false), lateBlocks = lateBlocks)

    val ordersById = cached.map(_.ordersById).getOrElse(marketBook.orders.map(o => o.boxId -> o.record).toMap)
    val asksById = cached.map(_.asksById).getOrElse(marketBook.asks.map(a => a.boxId -> a.record).toMap)
    val offersById = cached.map(_.offersById).getOrElse(marketBook.offers.map(o => o.boxId -> o.record).toMap)
    val parsedContracts = mutable.HashMap.empty[String, Option[Contract]]
    def parsedContract(hex: String): Option[Contract] = parsedContracts.getOrElseUpdate(hex, contractOf(hex))
    val nextHeight = if (minerFee == 0L) signingHeight.toLong else signingHeight.toLong + 1L

    def waitCleared(offerId: String, sourceId: String): Boolean = broadcastWait.forall { wait =>
      val oh = book.entry(offerId).map(_.height.toLong).getOrElse(Long.MaxValue)
      val sh = book.entry(sourceId).map(_.height.toLong).getOrElse(Long.MaxValue)
      nextHeight >= math.max(oh, sh) + wait.toLong
    }

    val computedRuns = mutable.HashMap.empty[ZcMarket, ZcBatchRun]
    var builtCount = 0
    ZcMarket.all.foreach { market =>
      if (canContinue() && builtCount < MaxMatchesPerBuild) {
        val run = cached.flatMap(_.runs.get(market)).getOrElse {
          val r = matcher.batch(market, rounds = math.min(ZcMatcher.Rounds, 5), deadline = deadline)
          if (cached.isEmpty && cleanStart && !deadline.exists(_.isOverdue())) computedRuns += market -> r
          r
        }
        // Process each offer in zcs1 priority order, chaining its continuation across its matched sources
        val orderedOffers = run.taken.keys.toVector.flatMap(offersById.get)
          .sortBy(o => (-ZcRules.fillFee(ZcOfferState.of(o), o.capital), o.boxId))
        orderedOffers.foreach { initialOffer =>
          if (canContinue() && builtCount < MaxMatchesPerBuild && !claimed.contains(initialOffer.boxId)) {
            val matches = run.taken.getOrElse(initialOffer.boxId, Vector.empty)
            val ownerOpt = resolver.script(initialOffer, pending = false, readBudget).flatMap(parsedContract)
            if (ownerOpt.isDefined) {
              var curOffer = initialOffer
              var curOfferIn: Option[InputUTXO] = inputOf(initialOffer.box, ctx)
              var curParent = Option.empty[String]
              var chainAlive = curOfferIn.isDefined
              if (!chainAlive) onFailure()

              matches.foreach { m =>
                if (chainAlive && canContinue() && builtCount < MaxMatchesPerBuild &&
                  !claimed.contains(m.sourceId) && m.fee >= minAct && waitCleared(initialOffer.boxId, m.sourceId)) {
                  val owner = ownerOpt.get
                  val offerInput = curOfferIn.get
                  ordersById.get(m.sourceId) match {
                    case Some(order) =>
                      // Recompute maturity & principal at the exact signing start height so Order/PricedOrder accepts.
                      val start = signingHeight.toLong
                      // The rules' height, whose next block is where the transaction lands: the matcher's height.
                      val ruleHeight = matcherHeight.toLong
                      val offerState = ZcOfferState.of(curOffer)
                      val ready = for {
                        borrower <- resolver.script(order, pending = false, readBudget).flatMap(parsedContract)
                        maturity <- (
                          if (ZcRules.window(order))
                            ZcRules.windowMaturity(offerState, order, book.oracle, ruleHeight, late = lateBlocks)
                              .filter(mat => mat - start >= order.terms(3) && ZcRules.orderWindow(order).exists(w => mat - start <= w._2))
                              .orElse(ZcRules.orderMaturity(order, start).toOption)
                          else ZcRules.orderMaturity(order, start).toOption
                        )
                        principal <- ZcRules.resolvePrincipal(order, ruleHeight, maturity).toOption
                        bond = ZcBondTerms.prospect(order, start, maturity, principal)
                        if ZcRules.offerRefusal(offerState, bond, principal, 1L, 1L, book.oracle, ruleHeight,
                          primary = true, late = lateBlocks).isEmpty
                        if ZcRules.guardRefusal(order, bond, book.oracle, ruleHeight).isEmpty
                        fee = ZcRules.fillFee(offerState, principal)
                        if fee >= minAct
                      } yield (borrower, maturity, principal)

                      // Soft skips (undisclosed/unwarmed script or timing window) leave the offer chain alive and unpenalized.
                      ready.foreach { case (borrower, maturity, principal) =>
                        inputOf(order.box, ctx) match {
                          case None =>
                            onFailure()
                            failed += order.boxId
                            logger.warn(s"Could not match offer ${curOffer.boxId} with order ${order.boxId}: order input invalid")
                          case Some(orderIn) =>
                            Try(ZcBatchTransactions.matchOrder(ctx, wallet, curOffer, offerInput, order, orderIn,
                              borrower, owner, maturity, principal, oracleIn, feeTo, minerFee, signingHeight)) match {
                              case Success(built) =>
                                if (recordTx(built.tx, curParent.toSeq)) {
                                  curOffer = built.nextOffer
                                  curOfferIn = Some(built.nextOfferInput)
                                  curParent = Some(built.tx.txId)
                                  builtCount += 1
                                } else chainAlive = false
                              case Failure(ex) =>
                                onFailure()
                                chainAlive = false
                                if (curParent.isEmpty) failed += initialOffer.boxId else failed += order.boxId
                                logger.warn(s"Could not match offer ${curOffer.boxId} with order ${order.boxId}: ${ex.getMessage}")
                            }
                        }
                      }

                    case None =>
                      asksById.get(m.sourceId).foreach { ask =>
                        val ready = for {
                          seller <- resolver.script(ask, pending = false, readBudget).flatMap(parsedContract)
                          src <- matcher.askSource(ZcOpen(ask, pending = false)).toOption
                          if !claimed.contains(src.bond.boxId)
                          originBytes <- resolver.boxBytes(src.asset, ctx, readBudget)
                          reqBytes <- if (!src.shares) Some(None)
                            else resolver.boxBytes(ask.token.tokenId, ctx, readBudget).map(Some(_))
                        } yield (seller, src, originBytes, reqBytes)

                        ready.foreach { case (seller, src, originBytes, reqBytes) =>
                          (inputOf(ask.box, ctx), inputOf(src.bond.record.box, ctx)) match {
                            case (None, _) =>
                              onFailure()
                              failed += ask.boxId
                              logger.warn(s"Could not match offer ${curOffer.boxId} with ask ${ask.boxId}: ask input invalid")
                            case (_, None) =>
                              onFailure()
                              failed += src.bond.boxId
                              logger.warn(s"Could not match offer ${curOffer.boxId} with ask ${ask.boxId}: bond data input invalid")
                            case (Some(askIn), Some(bondIn)) =>
                              Try(ZcBatchTransactions.matchAsk(ctx, wallet, curOffer, offerInput, ask, askIn,
                                seller, owner, m.units, m.price, bondIn, oracleIn, originBytes, reqBytes, feeTo, minerFee,
                                signingHeight)) match {
                                case Success(built) =>
                                  if (recordTx(built.tx, curParent.toSeq)) {
                                    curOffer = built.nextOffer
                                    curOfferIn = Some(built.nextOfferInput)
                                    curParent = Some(built.tx.txId)
                                    builtCount += 1
                                  } else chainAlive = false
                                case Failure(ex) =>
                                  onFailure()
                                  chainAlive = false
                                  if (curParent.isEmpty) failed += initialOffer.boxId else failed += ask.boxId
                                  logger.warn(s"Could not match offer ${curOffer.boxId} with ask ${ask.boxId}: ${ex.getMessage}")
                              }
                          }
                        }
                      }
                  }
                }
              }
            }
          }
        }
      }
    }

    if (cached.isEmpty && cleanStart && computedRuns.size == ZcMarket.all.size && resolver.generation == startGen &&
      !deadline.exists(_.isOverdue())) {
      val runsMap = computedRuns.toMap
      val askBondIds = runsMap.valuesIterator.flatMap(_.takenBy.keysIterator).flatMap { id =>
        asksById.get(id).flatMap(a => matcher.askSource(ZcOpen(a, pending = false)).toOption.map(s => id -> s.bond.boxId))
      }.toMap
      onComputed(ZcPrecomputedMatches(book.headerId, book.height, startGen, marketBook, runsMap,
        ordersById, asksById, offersById, askBondIds, book.oracle.map(_.box.boxId)))
    }
  }
}
