package transactions.engine.execution

import akka.pattern.ask
import akka.util.Timeout
import api.{BondSettlement, SigmaFiApi}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.models._
import configs.{NodeContext, SigmaFiBatchingConfig, SigmaFiBondsConfig}
import mutations.NodeWallet
import node.NodeApi
import node.model.{IndexedBox, IndexedTransaction, MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.ast.ErgoTree
import sigmafi.contracts._
import state.synchronization.CompleteMempool
import transactions.batching.sigmafi._
import transactions.batching.{Batcher, BatchingMempool}
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.{Contract, InputUTXO, Token}

import scala.concurrent.Await
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * The SigmaFi V2 endpoints, over the live boxes.
 *
 * Reads and checks run wherever they are called and sign nothing. Sends run on the transaction engine's worker,
 * which owns the wallet: each reserves its funding there, signs with the node wallet and broadcasts through
 * [[EngineBroadcast]], paying the network fee.
 *
 * @param bonds the lender-funded reserve used on fills when a request names none
 * @param alive whether the engine still wants this attempt; once false, nothing is signed or sent
 */
class SigmaFiAPIExecution(nodeContext: NodeContext,
                          walletSelector: EngineFunding,
                          bonds: SigmaFiBondsConfig = SigmaFiBondsConfig.Default,
                          alive: () => Boolean = () => true) extends SigmaFiApi {

  import SigmaFiAPIExecution._

  protected def executionNode: NodeApi = nodeContext.getNodeApi

  private def wallet: NodeWallet = nodeContext.getNodeWallet

  /** Blake2b256 of each tree this wallet signs for, to its contract: what orders and bonds commit to. */
  private lazy val walletHashes: Map[String, Contract] = wallet.signableTrees.toSeq.map { hex =>
    val c = Contract(ErgoTree.fromHex(hex))
    Hex.toHexString(c.hashedPropBytes) -> c
  }.toMap

  private def isMine(hash: Array[Byte]): Boolean = walletHashes.contains(Hex.toHexString(hash))

  // ══════════════════════════════════════════════════════════════════════════
  //  READS
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def listOrders(offset: Option[Int], limit: Option[Int], collateral: Option[String],
                          loan: Option[String]): SFOrderBook = withCtx { (ctx, nodeApi) =>
    val (from, size) = page(offset, limit)
    val (orders, cut) = scanOrders(nodeApi)
    val shown = orders.filter(o => holds(o.box.assets, o.box.value, collateral) && lends(o.terms.principalTokens, loan))
      .sortBy(o => (-o.box.creationHeight, o.boxId))
    SFOrderBook(ctx.getHeight, shown.slice(from, from + size).map(orderView(ctx, _)), shown.size, cut)
  }

  /** @inheritdoc */
  override def listBonds(offset: Option[Int], limit: Option[Int], all: Option[Boolean]): SFBondList =
    withCtx { (ctx, nodeApi) =>
      val (from, size) = page(offset, limit)
      val (live, cut) = scanBonds(nodeApi)
      val views = live.sortBy(b => (b.maturity, b.boxId)).map(bondView(ctx, nodeApi, _))
      val shown = if (all.contains(true)) views else views.filter(_.verified)
      SFBondList(ctx.getHeight, shown.slice(from, from + size), shown.size, views.count(!_.verified), cut)
    }

  /** @inheritdoc */
  override def lookupAssets(request: SFAssetLookupRequest): Seq[SFAssetLookup] = withCtx { (ctx, nodeApi) =>
    val ids = request.tokenIds.map(_.trim.toLowerCase).distinct
    if (ids.size > MaxLookup) throw LithosBadRequest(s"at most $MaxLookup token ids per request, got ${ids.size}")
    ids.foreach(id => if (!id.matches("[0-9a-f]{64}")) throw LithosBadRequest(s"'$id' is not a token id"))
    if (ids.isEmpty) Seq.empty
    else {
      val vaults = scanVault(nodeApi)._1.groupBy(_.asset.toString)
      // One transaction can settle many requested assets. Read that immutable evidence once per request.
      val transactions = scala.collection.mutable.Map.empty[String, Try[Option[IndexedTransaction]]]
      def transaction(id: String): Try[Option[IndexedTransaction]] =
        transactions.getOrElseUpdate(id, nodeApi.indexedTransactionById(id))
      ids.map { id =>
        val origin = assetOrigin(nodeApi, id)
        val state = origin.fold(_ => BondSettlement.State("Unverified"),
          o => BondSettlement.read(nodeApi, o.bondId, id, ctx.getHeight, transaction))
        SFAssetLookup(id, origin.isRight || origin.left.exists(_.minted), origin.isRight,
          origin.left.toOption.map(_.reason),
          state.liveBond.map(bondView(ctx, nodeApi, _)),
          vaults.getOrElse(id, Seq.empty).map(vaultView), state.status, state.settlement, state.reason)
      }
    }
  }

  /** @inheritdoc */
  override def getPortfolio: SFPortfolio = withCtx { (ctx, nodeApi) =>
    val (orders, ordersCut) = scanOrders(nodeApi)
    val (live, bondsCut) = scanBonds(nodeApi)
    val (vaults, vaultCut) = scanVault(nodeApi)
    val held = nodeApi.walletBalances() match {
      case Success(b) => b.assets.filter(_.amount == 1L).map(_.tokenId).toSet
      case Failure(ex) => throw LithosUnavailable(s"could not read the wallet's balances: ${ex.getMessage}", ex)
    }
    val byAsset = live.groupBy(_.asset.toString)
    val vaultByAsset = vaults.groupBy(_.asset.toString)
    val holdings = held.toSeq.filter(id => byAsset.contains(id) || vaultByAsset.contains(id)).sorted.map { id =>
      val vault = vaultByAsset.getOrElse(id, Seq.empty)
      val bond = byAsset.get(id).flatMap(_.headOption)
      SFHolding(id, bond.map(bondView(ctx, nodeApi, _)), vault.map(vaultView),
        claimable = vault.nonEmpty && bond.isEmpty && !vaultCut && unsettled(nodeApi, id).isEmpty)
    }
    SFPortfolio(ctx.getHeight,
      orders.filter(o => isMine(o.terms.borrowerHash)).map(orderView(ctx, _)),
      live.filter(b => isMine(b.borrowerHash)).sortBy(_.maturity).map(bondView(ctx, nodeApi, _)),
      holdings, ordersCut || bondsCut || vaultCut)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  CREATING
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def createOrder(request: SFOrderRequest, send: Boolean): Either[SFQuote, SFResult] = withCtx { (ctx, _) =>
    val tip = ctx.getHeight
    val loan = loanOf(request.principalNanoErgs, request.principalToken)
    val collateralErg = amount("collateralNanoErgs", request.collateralNanoErgs)
    val collateral = tokens("collateralTokens", request.collateralTokens.getOrElse(Seq.empty))
    if (request.batchingFee.isDefined) throw LithosBadRequest("batchingFee is chosen by the lender on fill, not on order creation")
    val liq = liquidationNamed(request.liquidation)
    val repayment = amount("repayment", request.repayment)
    val schedule = (request.maturityHeight, request.durationBlocks) match {
      case (Some(h), None) => checkMaturity(h, tip); FixedMaturity(h)
      case (None, Some(d)) =>
        if (d < SigmaFiContracts.MinOnCloseDuration)
          throw LithosBadRequest(s"'durationBlocks' must be at least ${SigmaFiContracts.MinOnCloseDuration}, got $d")
        if (BigInt(tip) + d > Long.MaxValue)
          throw LithosBadRequest("'durationBlocks' would overflow the bond's maturity height")
        OnClose(d)
      case _ => throw LithosBadRequest("give exactly one of 'maturityHeight' and 'durationBlocks'")
    }
    checkCollateral(collateralErg, collateral, loan.token)
    val terms = ZCOrderTerms(SigmaFiContracts.bond(loan.repayToken).hashedPropBytes, wallet.contract.hashedPropBytes,
      loan.erg, loan.token.map(t => t.id -> t.amount).toSeq, repayment, schedule, liq.hash)
    val plan = SigmaFiUserTransactions.createOrder(ctx, wallet, terms, collateralErg, collateral)
    val quote = SFQuote("createOrder", None, Some(orderTerms(terms, collateralErg, collateral)),
      (collateralErg + TxFee).toString, collateral.map(amountOf), "0", Seq.empty, TxFee.toString,
      Seq("Locks the collateral under a new order. Any lender can fill it, sending the principal to this wallet; " +
        "cancel it any time before then."),
      interestWarnings(loan, repayment))
    if (!send) Left(quote)
    else {
      val (signed, sent) = fundAndSend(ctx, plan)
      Right(SFResult(sent.txId, sent.outcome, Some(outputId(signed, 0)), None))
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  CANCELLING
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def cancelOrder(boxId: String, send: Boolean): Either[SFQuote, SFResult] = withCtx { (ctx, nodeApi) =>
    val order = read(nodeApi, boxId, "SigmaFi order")(SigmaFiBoxes.order)
    cancelBox(ctx, nodeApi, "cancelOrder", order.box, order.terms.borrowerHash,
      Some(orderTerms(order.terms, order.box.value, tokensOf(order.box))), send)
  }

  /**
   * Returns `box` to its creator, authorized by a wallet box at the creator's own tree: the contracts accept a
   * cancel only with such a box spent alongside.
   */
  private def cancelBox(ctx: BlockchainContext, nodeApi: NodeApi, action: String, box: NodeBox, creatorHash: Array[Byte],
                        terms: Option[SFTerms], send: Boolean): Either[SFQuote, SFResult] = {
    val creator = walletHashes.getOrElse(Hex.toHexString(creatorHash),
      throw LithosNotFound(s"box ${box.boxId} was not made by this wallet"))
    val input = inputOf(ctx, box)
    val quote = SFQuote(action, Some(box.boxId), terms, TxFee.toString, Seq.empty, (box.value - TxFee).toString,
      box.assets.map(a => SFTokenAmount(a.tokenId, a.amount.toString)), TxFee.toString,
      Seq(s"Returns everything the box holds to ${addressOf(creator)}, with one box of yours there spent alongside " +
        "to show it is yours."), Seq.empty)
    if (!send) {
      if (authCandidates(nodeApi, creator, Set.empty).isEmpty)
        throw LithosUnprocessable(s"this wallet holds no box at ${addressOf(creator)}, which a cancel must spend; " +
          "send any amount there first")
      Left(quote)
    } else {
      val snapshot = mempoolSnapshot()
      unclaimed(snapshot, box.boxId)
      val auth = reserveAuth(nodeApi, creator, snapshot)
      try {
        val signed = sign(ctx, SigmaFiUserTransactions.cancel(ctx, wallet, input, auth.inputs.head, creator))
        val sent = broadcast(signed, Seq(auth))
        Right(SFResult(sent.txId, sent.outcome, Some(outputId(signed, 0)), None))
      } catch { case NonFatal(ex) => auth.release(); throw ex }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FILLING
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def fillOrder(boxId: String, send: Boolean, reserve: Option[String] = None): Either[SFQuote, SFResult] = withCtx { (ctx, nodeApi) =>
    val order = read(nodeApi, boxId, "SigmaFi order")(SigmaFiBoxes.order)
    fillRefusal(ctx, order).foreach(reason => throw LithosUnprocessable(reason))
    val t = order.terms
    val repayToken = SigmaFiBoxes.pairedBond(t.bondHash, t.principalTokens).get
    val borrower = treeFor(nodeApi, t.borrowerHash, order.box, SigmaFiBoxes.hintTrees(order.box)).getOrElse(
      throw LithosUnprocessable("cannot find the borrower's address: the order carries no hint, and no box in the " +
        "transaction that created it is at the address its terms name"))
    val fee = batchingFee(reserve)
    val maturity = SigmaFiUserTransactions.fillMaturity(ctx, order)
    val principalErg = math.max(t.principalErg, MinBox)
    val unitValue = SigmaFiUserTransactions.lenderUnit(ctx, wallet, order, repayToken, maturity, fee).value
    if (BigInt(order.box.value) + fee + principalErg + unitValue + TxFee + work.lithos.mutations.UTXO.MIN_CHANGE > Long.MaxValue)
      throw LithosBadRequest("the order, principal and reserve exceed the supported nanoERG amount")
    val plan = SigmaFiUserTransactions.fillOrder(ctx, wallet, order, inputOf(ctx, order.box), repayToken, borrower, fee)
    val quote = SFQuote("fillOrder", Some(boxId), Some(orderTerms(t, order.box.value, tokensOf(order.box), Some(maturity), fee)),
      Math.addExact(fee, Math.addExact(principalErg, Math.addExact(unitValue, TxFee))).toString,
      t.principalTokens.map { case (id, n) => SFTokenAmount(id.toString, n.toString) },
      unitValue.toString, Seq(SFTokenAmount(boxId, "1")), TxFee.toString,
      Seq(s"Lends to ${addressOf(borrower)} and funds a $fee nanoERG reserve above the collateral, refunded on repayment. " +
        s"Mints bond asset $boxId to this wallet: whoever holds it is paid the " +
        s"repayment, or the collateral if the bond is not repaid by height $maturity."),
      lowFeeWarning(fee) ++ interestWarnings(Loan(t.principalErg, t.principalTokens.headOption.map {
        case (id, n) => Token(id, n) }), t.repayment))
    if (!send) Left(quote)
    else {
      unclaimed(mempoolSnapshot(), boxId)
      val (signed, sent) = fundAndSend(ctx, plan)
      Right(SFResult(sent.txId, sent.outcome, Some(outputId(signed, 1)), Some(boxId)))
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SETTLING
  // ══════════════════════════════════════════════════════════════════════════

  /** @inheritdoc */
  override def repay(boxId: String, send: Boolean): Either[SFQuote, SFResult] = withCtx { (ctx, nodeApi) =>
    val bond = read(nodeApi, boxId, "SigmaFi bond")(SigmaFiBoxes.bond)
    if (ctx.getHeight + 1 >= bond.maturity)
      throw LithosUnprocessable(s"bond $boxId matured at height ${bond.maturity}: repayment is closed")
    val borrower = walletHashes.get(Hex.toHexString(bond.borrowerHash)).orElse {
      val origin = Try(nodeApi.indexedBoxById(bond.asset.toString).toOption.flatten).toOption.flatten
      treeFor(nodeApi, bond.borrowerHash, bond.box, origin.toSeq.flatMap(b => SigmaFiBoxes.hintTrees(b.box)))
    }
      .getOrElse(throw LithosUnprocessable("cannot find the borrower's address, which the collateral must return to"))
    val plan = SigmaFiUserTransactions.repay(ctx, wallet, bond, inputOf(ctx, bond.box), borrower)
    val (paysErg, paysTokens) = bond.repayToken.fold((math.max(bond.repayment, MinBox), Seq.empty[SFTokenAmount]))(id =>
      (MinBox, Seq(SFTokenAmount(id.toString, bond.repayment.toString))))
    val toMe = isMine(bond.borrowerHash)
    val quote = SFQuote("repay", Some(boxId), Some(bondTerms(bond)), Math.addExact(paysErg, TxFee).toString, paysTokens,
      if (toMe) bond.collateralErg.toString else "0", if (toMe) bond.collateral.map(a => SFTokenAmount(a.tokenId, a.amount.toString)) else Seq.empty, TxFee.toString,
      Seq(s"Pays the repayment into the vault for the holder of ${bond.asset}, and returns the collateral to " +
        s"${addressOf(borrower)}."),
      if (toMe) Seq.empty else Seq("This wallet is not the borrower: the collateral goes to the borrower, not to you."))
    if (!send) Left(quote)
    else {
      unclaimed(mempoolSnapshot(), boxId)
      val (signed, sent) = fundAndSend(ctx, plan)
      Right(SFResult(sent.txId, sent.outcome, Some(outputId(signed, 0)), None))
    }
  }

  /** @inheritdoc */
  override def liquidate(boxId: String, send: Boolean): Either[SFQuote, SFResult] = withCtx { (ctx, nodeApi) =>
    val bond = read(nodeApi, boxId, "SigmaFi bond")(SigmaFiBoxes.bond)
    val height = ctx.getHeight + 1
    if (!SigmaFiBatcher.liquidatable(bond, height, 0L))
      throw LithosUnprocessable(
        if (height < bond.maturity) s"bond $boxId matures at height ${bond.maturity}; it can be liquidated from then on"
        else s"bond $boxId names a liquidation script this client does not run, or its fee leaves its vault box " +
          "under 0.001 ERG")
    if (bond.batchingFee - TxFee < MinBox)
      throw LithosUnprocessable(s"bond $boxId's batching fee, ${bond.batchingFee} nanoERG, cannot pay the network " +
        "fee and a box of its own; it can still be liquidated by anyone who adds their own ERG")
    val quote = SFQuote("liquidate", Some(boxId), Some(bondTerms(bond)), "0", Seq.empty,
      (bond.batchingFee - TxFee).toString, Seq.empty, TxFee.toString,
      Seq(s"Sends the full borrower collateral to the vault for the holder of " +
        s"${bond.asset}. The fee, less the network fee, comes to this wallet."), Seq.empty)
    if (!send) Left(quote)
    else {
      unclaimed(mempoolSnapshot(), boxId)
      stillRunning()
      val built = SigmaFiTransactions.liquidate(ctx, wallet, Seq(bond -> inputOf(ctx, bond.box)), wallet.contract,
        TxFee, height)
      val sent = broadcast(built.signed, Seq.empty)
      Right(SFResult(sent.txId, sent.outcome, Some(outputId(built.signed, 0)), None))
    }
  }

  /** @inheritdoc */
  override def claim(tokenId: String, send: Boolean): Either[SFQuote, SFResult] = withCtx { (ctx, nodeApi) =>
    val id = tokenId.trim.toLowerCase
    if (!id.matches("[0-9a-f]{64}")) throw LithosBadRequest(s"'$tokenId' is not a token id")
    val asset = ErgoId.create(id)
    val (scanned, cut) = scanVault(nodeApi, MempoolOptions.ConfirmedOnly)
    // The burn is final, so a vault box past the scan's cap would be left behind for good
    if (cut)
      throw LithosUnprocessable(s"the vault holds more than ${Batcher.MaxScannedPerTemplate} boxes, so this client " +
        s"cannot tell it has found every one for $id; a claim now could leave one unclaimable for good")
    val vaults = scanned.filter(_.asset == asset)
    if (vaults.isEmpty)
      throw LithosUnprocessable(s"the vault holds nothing for $id: its bond is not repaid or liquidated yet")
    val holds = nodeApi.walletBalances().toOption.exists(_.assets.exists(a => a.tokenId == id && a.amount >= 1))
    if (!holds) throw LithosNotFound(s"this wallet does not hold bond asset $id")
    unsettled(nodeApi, id).foreach(reason => throw LithosUnprocessable(reason))
    val inputs = vaults.map(v => inputOf(ctx, v.box))
    val plan = SigmaFiUserTransactions.claim(ctx, wallet, asset, inputs)
    val claimed = inputs.flatMap(_.tokens).groupBy(_.id).map { case (t, ts) => SFTokenAmount(t.toString, ts.map(_.amount).sum.toString) }
    val quote = SFQuote("claim", None, None, TxFee.toString, Seq(SFTokenAmount(id, "1")),
      inputs.map(_.value).sum.toString, claimed.toSeq, TxFee.toString,
      Seq(s"Claims ${vaults.size} vault box(es) and burns bond asset $id, which has nothing left to claim after."),
      Seq.empty)
    if (!send) Left(quote)
    else {
      val snapshot = mempoolSnapshot()
      vaults.foreach(v => unclaimed(snapshot, v.boxId))
      // A vault box still pending would be locked for good once the asset is burned
      if (snapshot.transactions.exists(_.body.outputs.exists(o => SigmaFiBoxes.vault(o).exists(_.asset == asset))))
        throw LithosStateChanged(s"a pending transaction still pays into the vault for $id; claim after it confirms")
      val (signed, sent) = fundAndSend(ctx, plan)
      Right(SFResult(sent.txId, sent.outcome, Some(outputId(signed, 0)), None))
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  CHECKS
  // ══════════════════════════════════════════════════════════════════════════

  /** Why this client would not fill `order`, or None. */
  private def fillRefusal(ctx: BlockchainContext, order: SigmaFiOrder): Option[String] = {
    val t = order.terms
    val tip = ctx.getHeight
    if (isMine(t.borrowerHash)) Some("this wallet made the order; cancel it rather than filling it")
    else if (order.liquidation.isEmpty) Some("the order names a liquidation script this client does not know")
    else if (SigmaFiBoxes.pairedBond(t.bondHash, t.principalTokens).isEmpty)
      Some("the order's bond does not match its loan, so its bond could not be repaid as asked")
    else if (order.box.value < MinBox) Some("the order collateral is under 0.001 ERG")
    else if (t.repayment <= 0 || t.principalErg < MinBox || t.principalTokens.exists(_._2 <= 0))
      Some("the order's principal or repayment is not a positive supported amount")
    else t.schedule match {
      case FixedMaturity(h) if h <= tip + 2 => Some(s"the order matures at $h, too close to height $tip to fill")
      case OnClose(d) if d < SigmaFiContracts.MinOnCloseDuration => Some(s"the order's duration of $d blocks is under 30")
      case OnClose(d) if tip.toLong + d > Int.MaxValue => Some("the order's duration would overflow its bond's maturity height")
      case _ => None
    }
  }

  /**
   * Why a claim of bond asset `id` has to wait, or None. A bond still holding the asset as its singleton can later
   * repay or liquidate into the vault for it, and a claim burns the only other unit, so that payment would be lost.
   * Read confirmed only: a settlement still pending leaves the bond holding it.
   */
  private def unsettled(nodeApi: NodeApi, id: String): Option[String] =
    nodeApi.unspentBoxesByTokenId(id, Paging(0, Batcher.PageSize), SortDirection.Desc, MempoolOptions.ConfirmedOnly) match {
      case Failure(ex) => throw LithosUnavailable(s"could not read which boxes hold $id: ${ex.getMessage}", ex)
      case Success(holders) =>
        holders.map(_.box).find(b => b.assets.headOption.exists(_.tokenId.equalsIgnoreCase(id)) &&
          SigmaFiBoxes.parse(b.ergoTree).exists(t => SigmaFiContracts.bondRepayToken(t).isDefined))
          .map(bond => s"bond ${bond.boxId} still holds the other unit of $id, so its repayment or collateral is not " +
            "in the vault yet; claiming now would burn your unit and leave that payment unclaimable for good")
          .orElse(if (holders.size < Batcher.PageSize) None
          else Some(s"more boxes hold $id than one read covers, so this client cannot rule out a bond still to settle"))
    }

  private def checkMaturity(h: Long, tip: Int): Unit =
    if (h <= tip + 2) throw LithosBadRequest(s"maturity $h is not far enough above the current height $tip")

  private def checkCollateral(erg: Long, collateral: Seq[Token], principal: Option[Token]): Unit = {
    if (erg < MinBox)
      throw LithosBadRequest("collateral must hold at least 0.001 ERG")
    if (collateral.map(_.id).distinct.size != collateral.size) throw LithosBadRequest("list each collateral token once")
    if (principal.exists(p => collateral.exists(_.id == p.id)))
      throw LithosBadRequest("the token lent cannot also be collateral")
  }

  private def batchingFee(raw: Option[String]): Long = {
    val fee = raw.map(amount("batchingFee", _, allowZero = true)).getOrElse(bonds.batchingFeeNanoErg)
    if (fee < 0) throw LithosBadRequest("the configured batching reserve must be nonnegative")
    fee
  }

  private def liquidationNamed(name: Option[String]): LiquidationScript = {
    val wanted = name.getOrElse(SigmaFiContracts.MaturityLiquidation.name)
    SigmaFiContracts.liquidation(wanted).getOrElse(throw LithosBadRequest(
      s"unknown liquidation script '$wanted'; known: ${SigmaFiContracts.liquidations.map(_.name).mkString(", ")}"))
  }

  private def lowFeeWarning(fee: Long): Seq[String] =
    if (fee >= SigmaFiBatchingConfig.ShippedMinBatchingFee) Seq.empty
    else Seq(s"Its batching fee is under ${SigmaFiBatchingConfig.ShippedMinBatchingFee} nanoERG, so clients on their " +
      "shipped settings will not liquidate it. If it is not repaid, liquidate it yourself.")

  private def interestWarnings(loan: Loan, repayment: Long): Seq[String] =
    if (loan.token.fold(repayment < loan.erg)(t => repayment < t.amount))
      Seq("The repayment is less than the principal: the lender loses on this loan if it is repaid.")
    else Seq.empty

  // ══════════════════════════════════════════════════════════════════════════
  //  VIEWS
  // ══════════════════════════════════════════════════════════════════════════

  private def orderTerms(t: ZCOrderTerms, collateralErg: Long, collateral: Seq[SFTokenAmount],
                         maturity: Option[Long] = None, reserve: Long = 0L): SFTerms =
    SFTerms(Kind, Some(t.principalErg.toString), t.principalTokens.headOption.map { case (id, n) =>
      SFTokenAmount(id.toString, n.toString) }, t.repayment.toString, t.principalTokens.headOption.map(_._1.toString),
      collateralErg.toString, collateral,
      maturity.orElse(Some(t.schedule).collect { case FixedMaturity(h) => h }),
      Some(t.schedule).collect { case OnClose(d) => d }, reserve.toString,
      SigmaFiContracts.liquidationOf(t.liqScriptHash).map(_.name), Hex.toHexString(t.liqScriptHash))

  private def orderTerms(t: ZCOrderTerms, collateralErg: Long, collateral: Seq[Token]): SFTerms =
    orderTerms(t, collateralErg, collateral.map(amountOf))

  /** Principal and collateral amounts are carried directly by the bond. */
  private def bondTerms(b: SigmaFiBond): SFTerms =
    SFTerms(Kind, Some(b.principalErg.toString), b.principalTokens.headOption.map { case (id, n) => SFTokenAmount(id.toString, n.toString) }, b.repayment.toString,
      b.repayToken.map(_.toString), b.collateralErg.toString, b.collateral.map(a => SFTokenAmount(a.tokenId, a.amount.toString)), Some(b.maturity), None,
      b.batchingFee.toString, b.liquidation.map(_.name), Hex.toHexString(b.liqScriptHash))

  private def orderView(ctx: BlockchainContext, o: SigmaFiOrder): SFOrderView =
    SFOrderView(o.boxId, orderTerms(o.terms, o.box.value, tokensOf(o.box)),
      hintAddress(o.box, o.terms.borrowerHash), isMine(o.terms.borrowerHash), fillRefusal(ctx, o).filterNot(_ =>
        isMine(o.terms.borrowerHash)), o.box.creationHeight)

  private def bondView(ctx: BlockchainContext, nodeApi: NodeApi, b: SigmaFiBond): SFBondView = {
    val origin = assetOrigin(nodeApi, b.asset.toString)
    val verified = origin.exists(_.bondId == b.boxId)
    val left = math.max(0, b.maturity - (ctx.getHeight + 1))
    SFBondView(b.boxId, b.asset.toString, bondTerms(b), verified,
      if (verified) None else Some(origin.left.toOption.map(_.reason).getOrElse(NotItsBond)),
      if (left > 0) "open" else "matured", left, isMine(b.borrowerHash), b.box.creationHeight)
  }

  private def vaultView(v: SigmaFiVaultBox): SFVaultView =
    SFVaultView(v.boxId, v.bondId, v.asset.toString, v.box.value.toString, tokensOf(v.box))

  private def hintAddress(box: NodeBox, hash: Array[Byte]): Option[String] =
    SigmaFiBoxes.hintTrees(box).find(t => SigmaFiBoxes.hashOf(t).sameElements(hash))
      .orElse(walletHashes.get(Hex.toHexString(hash)).map(_.ergoTree)).map(t => addressOf(Contract(t)))

  // ══════════════════════════════════════════════════════════════════════════
  //  VERIFICATION
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Where bond asset `id` came from, read from the chain: its genesis box must be a genuine order, and
   * the transaction spending it must have created a bond naming `id` at output 1. Cached by asset once known,
   * since neither can change after; a node failure is not cached.
   */
  private def assetOrigin(nodeApi: NodeApi, id: String): Either[Unverified, Origin] =
    Verifications.get(id).getOrElse {
      val read: Try[Either[Unverified, Origin]] = Try {
        nodeApi.indexedBoxById(id).get match {
          case None => Left(Unverified("the node's index has no genesis box for this asset", minted = false, settled = false))
          case Some(genesis) =>
            val tree = SigmaFiBoxes.parse(genesis.ergoTree)
            if (genesis.boxId != id || !tree.exists(SigmaFiContracts.isMinter))
              Left(Unverified("it was not minted by a SigmaFi order", minted = false, settled = true))
            else genesis.spentTransactionId match {
              case None => Left(Unverified("its order is still open", minted = false, settled = false))
              case Some(txId) =>
                nodeApi.indexedTransactionById(txId).get match {
                  case None => Left(Unverified("the node's index has not returned its mint transaction", minted = false, settled = false))
                  case Some(tx) =>
                    val bond = tx.outputs.lift(1).flatMap(out => SigmaFiBoxes.bond(out.box))
                    val supply = tx.outputs.flatMap(_.box.assets).filter(_.tokenId == id).map(a => BigInt(a.amount)).sum
                    val lenderUnit = tx.outputs.headOption.flatMap(_.box.assets.headOption)
                      .exists(a => a.tokenId == id && a.amount == 1L)
                    val order = SigmaFiBoxes.order(genesis.box).get
                    val principalPaid = tx.outputs.lift(2).exists { output =>
                      output.value >= order.terms.principalErg &&
                        output.assets.map(a => ErgoId.create(a.tokenId) -> a.amount) == order.terms.principalTokens &&
                        SigmaFiBoxes.parse(output.ergoTree).exists(t =>
                          SigmaFiBoxes.hashOf(t).sameElements(order.terms.borrowerHash))
                    }
                    val valid = tx.id == txId && tx.inputs.headOption.exists(_.boxId == id) && principalPaid &&
                      supply == 2 && lenderUnit && bond.exists(b => b.asset.toString == id &&
                        SigmaFiBoxes.verify(b, Some(genesis.copy(spendingHeight = Some(tx.inclusionHeight)))).isRight)
                    if (valid) Right(Origin(bond.get.boxId))
                    else Left(Unverified(NotItsBond, minted = false, settled = true))
                }
            }
        }
      }
      read.foreach(Verifications.put(id, _))
      read.getOrElse(Left(Unverified("the node's index did not answer", minted = false, settled = false)))
    }

  // ══════════════════════════════════════════════════════════════════════════
  //  PLUMBING
  // ══════════════════════════════════════════════════════════════════════════

  private def withCtx[A](f: (BlockchainContext, NodeApi) => A): A = {
    val nodeApi = executionNode
    nodeContext.getClient.execute(ctx => f(ctx, nodeApi))
  }

  private def page(offset: Option[Int], limit: Option[Int]): (Int, Int) = {
    val from = offset.getOrElse(0)
    val size = limit.getOrElse(DefaultPage)
    if (from < 0) throw LithosBadRequest(s"'offset' cannot be negative, got $from")
    if (size < 1 || size > MaxPage) throw LithosBadRequest(s"'limit' must be between 1 and $MaxPage, got $size")
    (from, size)
  }

  private def scanOrders(nodeApi: NodeApi): (Vector[SigmaFiOrder], Boolean) = {
    val (fixed, a) = scanTemplate(nodeApi, SigmaFiTemplateKind.Order)(SigmaFiBoxes.order)
    val (onClose, b) = scanTemplate(nodeApi, SigmaFiTemplateKind.OrderOnClose)(SigmaFiBoxes.order)
    (fixed ++ onClose, a || b)
  }

  private def scanBonds(nodeApi: NodeApi): (Vector[SigmaFiBond], Boolean) = {
    val (erg, a) = scanTree(nodeApi, SigmaFiContracts.ergBond.ergoTreeHex)(SigmaFiBoxes.bond)
    val (token, b) = scanTemplate(nodeApi, SigmaFiTemplateKind.TokenBond)(SigmaFiBoxes.bond)
    (erg ++ token, a || b)
  }

  private def scanVault(nodeApi: NodeApi, mempool: MempoolOptions = MempoolOptions.WithMempool)
  : (Vector[SigmaFiVaultBox], Boolean) =
    scanTree(nodeApi, SigmaFiContracts.vault.ergoTreeHex, mempool)(SigmaFiBoxes.vault)

  /** Every box at `tree` that `parse` accepts, with whether the scan stopped at its cap. Pages are dropped. */
  private def scanTree[A](nodeApi: NodeApi, tree: String, mempool: MempoolOptions = MempoolOptions.WithMempool)
                         (parse: NodeBox => Option[A]): (Vector[A], Boolean) =
    paged(offset => nodeApi.unspentBoxesByErgoTree(tree, Paging(offset, Batcher.PageSize), SortDirection.Desc, mempool))(parse)

  private def scanTemplate[A](nodeApi: NodeApi, kind: SigmaFiTemplateKind)(parse: NodeBox => Option[A]): (Vector[A], Boolean) =
    paged(offset => nodeApi.unspentBoxesByTemplateHash(SigmaFiContracts.template(kind).templateHashHex,
      Paging(offset, Batcher.PageSize), SortDirection.Desc, MempoolOptions.WithMempool))(parse)

  private def paged[A](fetch: Int => Try[Seq[IndexedBox]])(parse: NodeBox => Option[A]): (Vector[A], Boolean) = {
    var offset = 0
    var exhausted = false
    val found = Vector.newBuilder[A]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = fetch(offset) match {
        case Success(boxes) => boxes
        case Failure(ex) => throw LithosUnavailable(s"the node's index did not answer: ${ex.getMessage}", ex)
      }
      found ++= page.flatMap(indexed => parse(indexed.box))
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    (found.result(), !exhausted)
  }

  /** The unspent box `boxId` as `what`, unconfirmed ones included. */
  private def read[A](nodeApi: NodeApi, boxId: String, what: String)(parse: NodeBox => Option[A]): A = {
    if (!boxId.matches("[0-9a-fA-F]{64}")) throw LithosBadRequest(s"'$boxId' is not a box id")
    val box = nodeApi.boxesWithPoolByIds(Seq(boxId)) match {
      case Success(found) => found.find(_.boxId.equalsIgnoreCase(boxId))
        .getOrElse(throw LithosNotFound(s"no unspent box has id $boxId"))
      case Failure(ex) => throw LithosUnavailable(s"could not read box $boxId from the node: ${ex.getMessage}", ex)
    }
    parse(box).getOrElse(throw LithosNotFound(s"box $boxId is not a $what"))
  }

  private def inputOf(ctx: BlockchainContext, box: NodeBox): InputUTXO =
    SigmaFiBoxes.input(box, ctx).getOrElse(throw LithosUnavailable(s"box ${box.boxId} could not be rebuilt for spending"))

  /**
   * The tree `hash` commits to: from `hints`, this wallet's own trees, or every box in the transaction that
   * created `box`, which is how a box made by another client is resolved.
   */
  private def treeFor(nodeApi: NodeApi, hash: Array[Byte], box: NodeBox, hints: Seq[ErgoTree]): Option[Contract] = {
    def matching(trees: Seq[ErgoTree]) = trees.find(t => SigmaFiBoxes.hashOf(t).sameElements(hash)).map(Contract(_))
    matching(hints)
      .orElse(walletHashes.get(Hex.toHexString(hash)))
      .orElse(Try(nodeApi.indexedTransactionById(box.transactionId).get).toOption.flatten.flatMap(tx =>
        matching((tx.inputs ++ tx.outputs).flatMap(b => SigmaFiBoxes.parse(b.ergoTree)))))
  }

  /** Confirmed wallet boxes at `creator`'s tree, smallest first, leaving out those in `spent`. */
  private def authCandidates(nodeApi: NodeApi, creator: Contract, spent: Set[String]): Seq[NodeBox] =
    nodeApi.unspentBoxesByErgoTree(creator.ergoTreeHex, Paging(0, Batcher.PageSize), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly) match {
      case Success(boxes) => boxes.map(_.box).filterNot(b => spent(b.boxId)).sortBy(_.value)
      case Failure(ex) => throw LithosUnavailable(s"could not read this wallet's boxes: ${ex.getMessage}", ex)
    }

  /** Reserves one wallet box at `creator`'s tree to authorize a cancel, trying the smallest few. */
  private def reserveAuth(nodeApi: NodeApi, creator: Contract, snapshot: CompleteMempool.Snapshot): FundingAllocation = {
    stillRunning()
    withCtx { (ctx, _) =>
      authCandidates(nodeApi, creator, snapshot.spent).take(MaxAuthTries).iterator
        .flatMap(box => SigmaFiBoxes.input(box, ctx))
        .map(input => Try(walletSelector.reserveKnown(Seq(input))))
        .collectFirst { case Success(allocation) => allocation }
        .getOrElse(throw LithosUnprocessable(s"no box of this wallet at ${addressOf(creator)} is free to authorize " +
          "the cancel; wait for a pending transaction to confirm, or send any amount there"))
    }
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

  /** Refuses a box an unconfirmed transaction already spends. */
  private def unclaimed(snapshot: CompleteMempool.Snapshot, boxId: String): Unit =
    BatchingMempool.spenders(snapshot).get(boxId).flatMap(_.headOption).foreach { tx =>
      throw LithosStateChanged(s"box $boxId is already spent by unconfirmed transaction ${tx.id}")
    }

  /** Reserves what `plan` needs, signs and sends, releasing the reservation if any step before the send throws. */
  private def fundAndSend(ctx: BlockchainContext, plan: SFPlan): (SignedTransaction, EngineBroadcast.Result) = {
    stillRunning()
    val reservation =
      if (plan.value == 0L && plan.tokens.isEmpty) None
      else Some(walletSelector.reserveAvoiding(plan.value, plan.tokens, plan.avoidTokens))
    val signed = try sign(ctx, plan.build(reservation.map(_.inputs).getOrElse(Seq.empty)))
    catch { case NonFatal(ex) => reservation.foreach(_.release()); throw ex }
    (signed, broadcast(signed, reservation.toSeq))
  }

  /** Signing runs every protocol input's script, so a transaction a contract refuses fails here, not at the node. */
  private def sign(ctx: BlockchainContext, unsigned: org.ergoplatform.appkit.UnsignedTransaction): SignedTransaction = {
    stillRunning()
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

  /** Sends `signed`. A throw from here comes before the node call, so the engine's stop is reported as nothing sent. */
  private def broadcast(signed: SignedTransaction, funding: Seq[FundingAllocation]): EngineBroadcast.Result =
    try new EngineBroadcast(walletSelector.walletRef, executionNode)(walletSelector.executionContext)
      .send(signed, funding, operation(signed), alive)
    catch { case _: IllegalArgumentException if !alive() => throw stopped }

  private def stillRunning(): Unit = if (!alive()) throw stopped

  private def addressOf(contract: Contract): String =
    Try(contract.address(nodeContext.getNetwork).toString).getOrElse(contract.ergoTreeHex)
}

object SigmaFiAPIExecution {

  final val TxFee: Long = SigmaFiUserTransactions.TxFee
  final val MinBox: Long = SigmaFiContracts.MinBox
  final val Kind = "zero-coupon"
  final val DefaultPage = 10
  final val MaxPage = 100
  final val MaxLookup = 50
  private final val MaxAuthTries = 5
  private final val NotItsBond = "it is not the bond its asset's order created"

  /** The engine operation a SigmaFi send is recorded under, keyed by its transaction id. */
  def operation(signed: SignedTransaction): String =
    "sigmafi:" + _root_.transactions.candidate.BlockTxMessages.CandidateTx.txId(signed)

  def stopped: LithosUnavailable = LithosUnavailable("the transaction engine stopped this request before sending " +
    "it, so nothing was sent")

  /** The box id of `signed`'s output `index`. */
  private def outputId(signed: SignedTransaction, index: Int): String = signed.getOutputsToSpend.get(index).getId.toString

  /** One side of a new loan: nanoERG for an ERG loan, or the token lent with the principal box's floor. */
  private final case class Loan(erg: Long, token: Option[Token]) {
    def repayToken: Option[ErgoId] = token.map(_.id)
  }

  private def loanOf(erg: Option[String], token: Option[SFTokenAmount]): Loan = (erg, token) match {
    case (Some(e), None) =>
      val n = amount("principalNanoErgs", e)
      if (n < MinBox) throw LithosBadRequest("an ERG loan must lend at least 0.001 ERG")
      Loan(n, None)
    case (None, Some(t)) => Loan(MinBox, Some(tokens("principalToken", Seq(t)).head))
    case _ => throw LithosBadRequest("give exactly one of 'principalNanoErgs' and 'principalToken'")
  }

  /** Why a bond asset is not verified; `minted` when a genuine order did mint it, `settled` when no later block can change that. */
  private final case class Unverified(reason: String, minted: Boolean, settled: Boolean)

  /** A verified asset and the bond its mint created. Principal is carried by the bond itself. */
  private final case class Origin(bondId: String)

  /** Verification outcomes by asset id. Bounded: the oldest is dropped past [[MaxVerifications]]. */
  private object Verifications {
    private val MaxVerifications = 4096
    private val held = new java.util.LinkedHashMap[String, Either[Unverified, Origin]](256, 0.75f, true) {
      override def removeEldestEntry(e: java.util.Map.Entry[String, Either[Unverified, Origin]]): Boolean =
        size() > MaxVerifications
    }
    def get(id: String): Option[Either[Unverified, Origin]] = held.synchronized(Option(held.get(id)))
    /** An open order may still mint later, so only settled outcomes are kept. */
    def put(id: String, outcome: Either[Unverified, Origin]): Unit =
      if (outcome.fold(_.settled, _ => true)) held.synchronized(held.put(id, outcome))
  }

  private def amountOf(t: Token): SFTokenAmount = SFTokenAmount(t.id.toString, t.amount.toString)

  private def tokensOf(box: NodeBox): Seq[SFTokenAmount] = box.assets.map(a => SFTokenAmount(a.tokenId, a.amount.toString))

  private def amount(field: String, raw: String, allowZero: Boolean = false): Long =
    Try(BigInt(raw.trim)).toOption.filter(_.isValidLong).map(_.toLong).filter(n => if (allowZero) n >= 0 else n > 0)
      .getOrElse(throw LithosBadRequest(s"'$field' must be a ${if (allowZero) "non-negative" else "positive"} whole " +
        s"number, got '$raw'"))

  private def tokens(field: String, ts: Seq[SFTokenAmount]): Seq[Token] = ts.map { t =>
    val id = t.tokenId.trim.toLowerCase
    if (!id.matches("[0-9a-f]{64}")) throw LithosBadRequest(s"'$field' names '${t.tokenId}', which is not a token id")
    Token(ErgoId.create(id), amount(s"$field amount", t.amount))
  }

  /** Whether a box holding `assets` and `erg` is collateral of `asset`: "erg" for ERG, or a token id. */
  private def holds(assets: Seq[node.model.NodeAsset], erg: Long, asset: Option[String]): Boolean =
    asset.map(_.trim.toLowerCase).forall(a => if (a == "erg") erg > 0 else assets.exists(_.tokenId == a))

  /** Whether a loan of `principal` tokens lends `asset`: "erg" for an ERG loan, or a token id. */
  private def lends(principal: Seq[(ErgoId, Long)], asset: Option[String]): Boolean =
    asset.map(_.trim.toLowerCase).forall(a => if (a == "erg") principal.isEmpty else principal.exists(_._1.toString == a))
}
