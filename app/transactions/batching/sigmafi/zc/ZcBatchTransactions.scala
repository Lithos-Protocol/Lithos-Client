package transactions.batching.sigmafi.zc

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, InputBox, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import transactions.batching.Batcher
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage, Supersede}
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.JavaConverters._

/**
 * One signed, self-funded ZC batching transaction: never spends a wallet box, pays its reward less any miner fee
 * into `feeBox`, and names every protocol box it spends so the planner never spends a box twice.
 */
final case class ZcBatchTx(signed: SignedTransaction, kind: String, spentIds: Set[String], feeBox: InputUTXO,
                           fees: Long) {
  def txId: String = CandidateTx.txId(signed)
  def candidateTx: CandidateTx = CandidateTx.signed(signed, kind)
  def sizeBytes: Long = CandidateTx.signedSize(signed).toLong
  def cost: Long = signed.getCost.toLong

  /** For this miner's block: supersedes competing mempool spends and credits `feeBox` to the holding top-up. */
  def bundle(competitors: Set[String] = Set.empty, parents: Seq[String] = Seq.empty): CandidateBundle =
    CandidateBundle(Vector(candidateTx),
      parents.distinct.map(ChainFromPackage) ++ (if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors))),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, feeBox, txId)))
}

/**
 * One piece of a packed ZC transaction (liquidation, expiry refund or non-wrap refresh). `aged` marks a bond
 * refresh, whose successor is stamped 60 blocks back (EIP-39) and therefore packs only with other bond refreshes.
 */
final case class ZcPiece(boxId: String, input: Int => InputUTXO, outputs: Seq[UTXO], reward: Long,
                         oracle: Option[InputUTXO] = None, disclose: Seq[Contract] = Seq.empty,
                         aged: Boolean = false)

/** Result of matching an offer against an order: the signed fill and the offer's live continuation for chaining. */
final case class ZcOrderMatchBuilt(tx: ZcBatchTx, nextOffer: ZcOffer, nextOfferInput: InputUTXO)

/** Result of matching an offer against an ask: the signed fill, the offer's continuation and any ask continuation. */
final case class ZcAskMatchBuilt(tx: ZcBatchTx, nextOffer: ZcOffer, nextOfferInput: InputUTXO,
                                 nextAsk: Option[(ZcAsk, InputUTXO)])

/**
 * Self-funded transaction builders for SigmaFi ZC Standard batching: offer matches (against orders and asks,
 * chaining through continuations), packed liquidations/refunds/refreshes (up to 20 pieces per transaction),
 * share mints, pulls, wrap mints and wrap vault refreshes.
 *
 * Every builder balances protocol inputs to protocol outputs + `feeBox` + `minerFee` with zero change and zero
 * wallet boxes, signs with `preHeader.height(height)`, discloses any named output script not present among input
 * or output trees in context variable 64 of `INPUTS(0)` (zcs1), and checks node output minimums.
 */
object ZcBatchTransactions {

  final val KindPack = "sigmafi-zc-pack"
  final val KindBondRefresh = "sigmafi-zc-refresh"
  final val KindMatchOrder = "sigmafi-zc-match-order"
  final val KindMatchAsk = "sigmafi-zc-match-ask"
  final val KindMintShares = "sigmafi-zc-mint-shares"
  final val KindPull = "sigmafi-zc-pull"
  final val KindMintWrap = "sigmafi-zc-mint-wrap"
  final val KindWrapRefresh = "sigmafi-zc-wrap-refresh"

  /** Most pieces packed into one transaction, matching zcs1 (`StandardBatch.PackLimit`). */
  final val PackLimit = 20

  private final val MinBox: Long = ZcBoxes.MinBox

  private def bytes(hex: String): ErgoValue[_] = ErgoValue.of(Colls.fromArray(Hex.decode(hex)), scalaByteType)
  private def raw(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
  private def longs(xs: Seq[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(xs.toArray), scalaLongType)
  private def int(i: Int): ErgoValue[_] = ErgoValue.of(i)
  private def byte(b: Byte): ErgoValue[_] = ErgoValue.of(b)
  private def long(l: Long): ErgoValue[_] = ErgoValue.of(l)
  private def utf8(texts: Seq[String]): Seq[ErgoValue[_]] = texts.map(t => raw(t.getBytes(UTF_8)))
  private def token(id: String, n: Long): Token = Token(ErgoId.create(id), n)
  private def script(kind: ZcKind, market: Option[ZcMarket]): Contract = ZcContracts.script(kind, market).contract

  private def tokenPairs(ts: Seq[(String, Long)]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(ts.map { case (t, n) => Colls.fromArray(Hex.decode(t)) -> n }.toArray)(
      sigma.data.RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType)),
      org.ergoplatform.appkit.ErgoType.pairType(org.ergoplatform.appkit.ErgoType.collType(scalaByteType), scalaLongType))

  /** The value left in a refreshed box: at most `RefreshBounty` less, never below `floor` or its current value. */
  def refreshedValue(value: Long, floor: Long): Long = {
    val kept = math.min(value, floor)
    math.max(value - ZcPricing.RefreshBounty, kept)
  }

  /** Whether a box made at `created` is old enough to refresh at signing height `height`. */
  def refreshable(created: Long, height: Long): Boolean = created < height - ZcPricing.RefreshAge

  /** `inputs` with any `named` script not already carried by an input or output disclosed in var 64 of `INPUTS(0)`. */
  private def discloseAll(inputs: Seq[InputUTXO], outputs: Seq[UTXO], named: Seq[Contract]): Seq[InputUTXO] = {
    val present = (inputs.map(_.contract.ergoTreeHex) ++ outputs.map(_.contract.ergoTreeHex)).toSet
    val missing = named.foldLeft(Vector.empty[Contract]) { (acc, c) =>
      if (present.contains(c.ergoTreeHex) || acc.exists(_.ergoTreeHex == c.ergoTreeHex)) acc else acc :+ c
    }
    if (missing.isEmpty) inputs
    else inputs.head.withCtxVar(ZcUserTransactions.DisclosureVar,
      ZcUserTransactions.bytesList(missing.map(_.ergoTree.bytes))) +: inputs.tail
  }

  /** Signs a self-funded transaction at `height`, asserting zero change and node output minimums. */
  private def signSelfFunded(ctx: BlockchainContext, wallet: NodeWallet, inputs: Seq[InputUTXO], outputs: Seq[UTXO],
                             feeTo: Contract, reward: Long, minerFee: Long, height: Int, kind: String,
                             spentIds: Set[String], dataInputs: Seq[InputUTXO] = Seq.empty,
                             burn: Seq[Token] = Seq.empty, named: Seq[Contract] = Seq.empty): ZcBatchTx = {
    require(minerFee >= 0L, "miner fee cannot be negative")
    val net = reward - minerFee
    require(net >= MinBox, s"reward $reward does not cover miner fee $minerFee and a minimum output")
    val feeOut = UTXO(feeTo, net, creationHeight = Some(height))
    val allOutputs = outputs :+ feeOut
    val finalInputs = discloseAll(inputs, allOutputs, named)
    val unsigned = TxBuilder(ctx)
      .setInputs(finalInputs: _*)
      .setDataInputs(dataInputs: _*)
      .setOutputs(allOutputs: _*)
      .setPreHeader(ctx.createPreHeader().height(height).build())
      .buildTx(minerFee, wallet.p2pk, burn)
    val signed = Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
    val expectedOutputs = allOutputs.size + (if (minerFee > 0L) 1 else 0)
    require(signed.getOutputsToSpend.size == expectedOutputs,
      s"$kind produced ${signed.getOutputsToSpend.size} outputs, expected $expectedOutputs (no change allowed)")
    val feeBox = InputUTXO(signed.getOutputsToSpend.get(outputs.size))
    require(feeBox.value == net && feeBox.tokens.isEmpty,
      s"$kind reward output holds ${feeBox.value} nanoERG and ${feeBox.tokens.size} token(s), expected $net")
    ZcBatchTx(signed, kind, spentIds, feeBox, reward)
  }

  /** Converts a signed output back to a `NodeBox` so a chained fill can read it as a live ZC record. */
  def nodeBoxOf(out: InputBox, txId: String, index: Int): NodeBox =
    NodeBox(out.getId.toString, txId, out.getValue, index, out.getCreationHeight, Hex.toHexString(out.getErgoTree.bytes),
      out.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)),
      NodeRegisters(out.getRegisters.asScala.zipWithIndex.map { case (v, k) => s"R${k + 4}" -> v.toHex }.toMap))

  // ─── packable pieces ───────────────────────────────────────────────────────

  /** Liquidating `bond` into `BondVault` at `height` (maturity or early oracle). */
  def liquidationPiece(bond: ZcBond, input: InputUTXO, exprBytes: Array[Byte],
                       oracle: Option[InputUTXO]): ZcPiece = {
    require(input.id.toString == bond.boxId, "bond input does not match its id")
    require(bond.feeReserve >= 0L && bond.box.value - bond.feeReserve >= MinBox, "bond fee reserve out of bounds")
    val settlement = UTXO(script(ZcKind.BondVault, None), bond.box.value - bond.feeReserve, input.tokens,
      Seq(bytes(bond.boxId), byte(1.toByte)))
    ZcPiece(bond.boxId,
      i => input.withCtxVar(0.toByte, raw(exprBytes)).withCtxVar(1.toByte, int(i)),
      Seq(settlement), bond.feeReserve, oracle = oracle)
  }

  /** Refunding an expired order to `borrower` for its expiry fee. */
  def orderRefundPiece(order: ZcOrder, input: InputUTXO, borrower: Contract): ZcPiece = {
    require(input.id.toString == order.boxId, "order input does not match its id")
    require(order.expiryFee >= ZcPricing.MinBatchingFee && order.expiryFee <= order.box.value - MinBox,
      "order expiry fee out of bounds")
    val refund = UTXO(borrower, order.box.value - order.expiryFee, input.tokens, Seq(bytes(order.boxId)))
    ZcPiece(order.boxId, i => input.withCtxVar(0.toByte, int(i)), Seq(refund), order.expiryFee)
  }

  /** Refunding an expired offer to `owner` for its batcher fee. */
  def offerRefundPiece(offer: ZcOffer, input: InputUTXO, owner: Contract): ZcPiece = {
    require(input.id.toString == offer.boxId, "offer input does not match its id")
    val fee = ZcRules.batcherFee(offer)
    require(fee > 0L && fee <= offer.box.value - MinBox, "offer refund fee out of bounds")
    val refund = UTXO(owner, offer.box.value - fee, input.tokens, Seq(bytes(offer.boxId)))
    ZcPiece(offer.boxId, i => input.withCtxVar(0.toByte, int(i)), Seq(refund), fee)
  }

  /** Refunding an expired ask to `seller` for its nanoERG above `MinBox`. */
  def askRefundPiece(ask: ZcAsk, input: InputUTXO, seller: Contract): ZcPiece = {
    require(input.id.toString == ask.boxId, "ask input does not match its id")
    val fee = ask.box.value - MinBox
    require(fee > 0L, "ask carries no refund fee above MinBox")
    val refund = UTXO(seller, MinBox, input.tokens, Seq(bytes(ask.boxId)))
    ZcPiece(ask.boxId, i => input.withCtxVar(0.toByte, int(i)), Seq(refund), fee)
  }

  /** Refunding an expired lot auction to `seller` for its refund fee. */
  def lotRefundPiece(lot: ZcLot, input: InputUTXO, seller: Contract): ZcPiece = {
    require(input.id.toString == lot.boxId, "lot input does not match its id")
    val fee = ZcRules.lotRefundFee(lot)
    require(fee > 0L && lot.box.value - fee >= MinBox, "lot refund fee out of bounds")
    val refund = UTXO(seller, lot.box.value - fee, input.tokens, Seq(bytes(lot.boxId)))
    ZcPiece(lot.boxId, i => input.withCtxVar(0.toByte, int(i)), Seq(refund), fee)
  }

  /**
   * Paid refresh of an aged bond: stamped 60 blocks back so the borrower can recollateralize immediately, and
   * disclosing `borrower` in var 64 so later batchers still resolve it. Packs only with other bond refreshes.
   */
  def bondRefreshPiece(bond: ZcBond, input: InputUTXO, borrower: Contract, height: Int): ZcPiece = {
    require(input.id.toString == bond.boxId, "bond input does not match its id")
    require(refreshable(input.input.getCreationHeight.toLong, height.toLong), "bond is not old enough to refresh")
    require(height.toLong >= bond.maturity || height.toLong < bond.maturity - ZcPricing.RefreshMaturityGap,
      "bond is inside the 720-block pre-maturity refresh guard")
    val nextValue = refreshedValue(bond.box.value, bond.feeReserve + MinBox)
    val bounty = bond.box.value - nextValue
    require(bounty > 0L, "bond pays no refresh bounty")
    val stamp = (height.toLong - ZcPricing.RecollateralizeWait).toInt
    require(input.input.getCreationHeight <= stamp, "bond input is newer than its refresh stamp")
    val successor = UTXO(input.contract, nextValue, input.tokens, input.registers, Some(stamp))
    ZcPiece(bond.boxId, i => input.withCtxVar(9.toByte, int(i)), Seq(successor), bounty,
      disclose = Seq(borrower), aged = true)
  }

  /** Paid refresh of an aged bond vault (`BondVault`). */
  def settlementRefreshPiece(s: ZcSettlement, input: InputUTXO, height: Int): ZcPiece = {
    require(input.id.toString == s.boxId, "settlement input does not match its id")
    require(refreshable(input.input.getCreationHeight.toLong, height.toLong), "settlement is not old enough to refresh")
    val nextValue = refreshedValue(s.box.value, MinBox)
    val bounty = s.box.value - nextValue
    require(bounty > 0L, "settlement pays no refresh bounty")
    val successor = UTXO(input.contract, nextValue, input.tokens, input.registers, Some(height))
    ZcPiece(s.boxId, i => input.withCtxVar(9.toByte, int(i)), Seq(successor), bounty)
  }

  /** Paid refresh of an aged fractional or Dutch share vault. */
  def shareVaultRefreshPiece(v: ZcShareVault, input: InputUTXO, height: Int): ZcPiece = {
    require(input.id.toString == v.boxId, "share vault input does not match its id")
    require(refreshable(input.input.getCreationHeight.toLong, height.toLong), "share vault is not old enough to refresh")
    val floor = if (v.holding) MinBox + v.pullFee else MinBox
    val nextValue = refreshedValue(v.box.value, floor)
    val bounty = v.box.value - nextValue
    require(bounty > 0L, "share vault pays no refresh bounty")
    val successor = UTXO(input.contract, nextValue, input.tokens, input.registers, Some(height))
    ZcPiece(v.boxId, i => input.withCtxVar(9.toByte, int(i)), Seq(successor), bounty)
  }

  /**
   * Packs up to [[PackLimit]] independent pieces into one self-funded transaction. Aged bond refreshes must not be
   * mixed with non-aged pieces (EIP-39).
   */
  def pack(ctx: BlockchainContext, wallet: NodeWallet, pieces: Seq[ZcPiece], feeTo: Contract, minerFee: Long,
           height: Int): ZcBatchTx = {
    require(pieces.nonEmpty && pieces.size <= PackLimit, s"pack takes 1..$PackLimit pieces, got ${pieces.size}")
    require(pieces.map(_.boxId).distinct.size == pieces.size, "duplicate box in pack")
    val aged = pieces.head.aged
    require(pieces.forall(_.aged == aged), "bond refreshes cannot be packed with non-bond pieces (EIP-39)")
    val starts = pieces.scanLeft(0)(_ + _.outputs.size)
    val inputs = pieces.zip(starts).map { case (p, at) => p.input(at) }
    val outputs = pieces.flatMap(_.outputs)
    val totalReward = pieces.map(_.reward).foldLeft(0L)(Math.addExact)
    val oracle = pieces.flatMap(_.oracle).headOption.toSeq
    val named = pieces.flatMap(_.disclose)
    signSelfFunded(ctx, wallet, inputs, outputs, feeTo, totalReward, minerFee, height,
      if (aged) KindBondRefresh else KindPack, pieces.map(_.boxId).toSet, dataInputs = oracle, named = named)
  }

  // ─── offer matches ─────────────────────────────────────────────────────────

  /**
   * Builds the delivery output for a bond unit bought by `offer`: either straight to `owner` (tagged with R7 = offerId)
   * or into the share mint request `offer` names (R7 = ownerHash ++ offerId, R8 = fractional, R9 = metadata).
   */
  private def unitDelivery(offer: ZcOffer, owner: Contract, asset: String,
                           eip4: Seq[ErgoValue[_]]): (UTXO, Long) = {
    if (!ZcRules.delivers(offer)) {
      val front = if (eip4.nonEmpty) eip4 else Seq.fill(3)(raw(Array.emptyByteArray))
      (UTXO(owner, MinBox, Seq(token(asset, 1L)), front :+ bytes(offer.boxId)), MinBox)
    } else {
      val fracHash = ZcContracts.script(ZcKind.FractionalMint, None).treeHash
      val dutchHash = ZcContracts.script(ZcKind.DutchAuctionMint, Some(offer.market)).treeHash
      val targetHex = Hex.toHexString(offer.mintHash)
      val mintContract =
        if (targetHex == fracHash) script(ZcKind.FractionalMint, None)
        else if (targetHex == dutchHash) script(ZcKind.DutchAuctionMint, Some(offer.market))
        else throw new IllegalArgumentException(s"offer ${offer.boxId} names an unknown mint script $targetHex")
      val funding = ZcRules.deliveryErg(offer, shares = false)
      val front = if (eip4.nonEmpty) eip4 else offer.metadata.map(raw)
      val ownerBytes = Hex.decode(offer.ownerHash) ++ Hex.decode(offer.boxId)
      (UTXO(mintContract, funding, Seq(token(asset, 1L)),
        front ++ Seq(raw(ownerBytes), longs(offer.fractional), ZcUserTransactions.bytesList(offer.metadata))), funding)
    }
  }

  /** Offer continuation box after paying `price` and `ergOut`, naming `offer.boxId` as predecessor in R6's suffix. */
  private def offerContinuation(offer: ZcOffer, offerInput: InputUTXO, price: Long, ergOut: Long): UTXO = {
    val leftCap = offer.capital - price
    val tokens = offer.market.tokenId match {
      case None => Seq.empty[Token]
      case Some(id) => if (leftCap > 0L) Seq(token(id, leftCap)) else Seq.empty
    }
    val value = offer.box.value - ergOut
    require(value >= MinBox, s"offer ${offer.boxId} leaves $value nanoERG under MinBox after $ergOut")
    UTXO(offer.script.contract, value, tokens, offerInput.registers)
      .withRegNum(6, raw(Hex.decode(offer.policyHash) ++ Hex.decode(offer.boxId)))
  }

  /**
   * Matches `offer` against `originalOrder`, minting the bond asset from `INPUTS(0)` = order and paying the batcher
   * fee to `feeTo`. Returns the signed transaction plus the offer's live continuation for same-block chaining.
   */
  def matchOrder(ctx: BlockchainContext, wallet: NodeWallet, offer: ZcOffer, offerInput: InputUTXO,
                 originalOrder: ZcOrder, orderInput: InputUTXO, borrower: Contract, owner: Contract,
                 maturity: Long, resolvedPrincipal: Long, oracle: InputUTXO, feeTo: Contract, minerFee: Long,
                 height: Int): ZcOrderMatchBuilt = {
    require(offerInput.id.toString == offer.boxId && orderInput.id.toString == originalOrder.boxId,
      "matchOrder inputs do not match their records")
    val order = ZcUserTransactions.resolved(originalOrder, resolvedPrincipal)
    val m = order.market
    val asset = order.boxId
    val start = height.toLong
    val feeReserve = ZcRules.feeReserve(offer)
    val rule = ZcRules.rule(order.liqHash, m)
    val metadata = ZcMetadata.bond(ZcMetadata.BondFacts(m, asset, order.principal, order.repayment, start, maturity,
      ZcMetadata.ruleFields(rule, order.payload, order.liqHash)))
    val (delivery, deliveryFunding) = unitDelivery(offer, owner, asset, utf8(metadata))
    val collateralErg = order.box.value - order.expiryFee
    val bondOut = UTXO(script(ZcKind.Bond, Some(m)), Math.addExact(collateralErg, feeReserve),
      token(asset, 2L) +: order.box.assets.map(a => token(a.tokenId, a.amount)),
      Seq(bytes(asset), bytes(order.borrowerHash), longs(order.terms),
        tokenPairs(order.principalTokens.map(t => t.tokenId -> t.amount)),
        bytes(order.liqHash), longs(Seq(feeReserve, start, maturity))), Some(height))
    val principalOut = UTXO(borrower, Math.addExact(order.expiryFee, order.principalErg),
      order.principalTokens.map(t => token(t.tokenId, t.amount)), Seq(bytes(asset)))
    val fee = ZcRules.fillFee(ZcOfferState.of(offer), order.principal)
    val ergOut = Math.addExact(Math.addExact(fee, deliveryFunding), Math.addExact(feeReserve, order.principalErg))
    val rest = offerContinuation(offer, offerInput, order.principal, ergOut)

    val guardedOrder = if (order.guardHash.isEmpty) orderInput
      else orderInput.withCtxVar(1.toByte, raw(ZcContracts.expression(ZcExpression.FillGuard, Some(m)).bytes))
    val pricedOrder = order.pricingHash.filter(_ => order.priced).flatMap(ZcContracts.expressionByHash)
      .fold(guardedOrder)(e => guardedOrder.withCtxVar(2.toByte, raw(e.bytes)))
    val policy = ZcContracts.expression(ZcExpression.CollateralPolicy, Some(m)).bytes
    val offerIn = offerInput.withCtxVar(0.toByte, int(3)).withCtxVar(1.toByte, raw(policy))

    val built = signSelfFunded(ctx, wallet, Seq(pricedOrder, offerIn), Seq(delivery, bondOut, principalOut, rest),
      feeTo, fee, minerFee, height, KindMatchOrder, Set(order.boxId, offer.boxId),
      dataInputs = Seq(oracle), named = Seq(owner, borrower))
    val nextBox = built.signed.getOutputsToSpend.get(3)
    val nextNode = nodeBoxOf(nextBox, built.txId, 3)
    val nextOffer = ZcBoxes.read(nextNode, offer.script).collect { case o: ZcOffer => o }
      .getOrElse(throw new IllegalStateException("offer continuation did not read back as a ZcOffer"))
    ZcOrderMatchBuilt(built, nextOffer, InputUTXO(nextBox))
  }

  /**
   * Matches `offer` against `ask` (a bond unit or Selective/Dutch shares). Inputs: 0 ask, 1 offer. Data inputs:
   * 0 live bond, 1 oracle. Outputs: 0 seller payout, 1 delivery, 2 offer continuation, [3 ask continuation], reward.
   */
  def matchAsk(ctx: BlockchainContext, wallet: NodeWallet, offer: ZcOffer, offerInput: InputUTXO, ask: ZcAsk,
               askInput: InputUTXO, seller: Contract, owner: Contract, units: Long, price: Long,
               bondInput: InputUTXO, oracle: InputUTXO, originBytes: Array[Byte], requestBytes: Option[Array[Byte]],
               feeTo: Contract, minerFee: Long, height: Int): ZcAskMatchBuilt = {
    require(offerInput.id.toString == offer.boxId && askInput.id.toString == ask.boxId,
      "matchAsk inputs do not match their records")
    val m = offer.market
    val shares = requestBytes.isDefined
    val paidErg = if (m.tokenId.isEmpty) math.max(price, MinBox) else MinBox
    val tag = Seq(bytes(ask.boxId), long(units))
    val payout = m.tokenId match {
      case None => UTXO(seller, paidErg, Seq.empty, tag)
      case Some(id) => UTXO(seller, MinBox, Seq(token(id, price)), tag)
    }
    val (delivery, deliveryFunding) =
      if (shares) (UTXO(owner, MinBox, Seq(token(ask.token.tokenId, units)),
        Seq.fill(3)(raw(Array.emptyByteArray)) :+ bytes(offer.boxId)), MinBox)
      else unitDelivery(offer, owner, ask.token.tokenId, Seq.empty)
    val fee = ZcRules.fillFee(ZcOfferState.of(offer), price)
    val ergOut = Math.addExact(Math.addExact(fee, deliveryFunding), paidErg)
    val offerRest = offerContinuation(offer, offerInput, price, ergOut)
    val remaining = ask.token.amount - units
    val askRest = if (remaining <= 0L) None
      else Some(UTXO(ask.script.contract, ask.box.value, Seq(token(ask.token.tokenId, remaining)), askInput.registers)
        .withRegNum(if (ask.priced) 8 else 6, bytes(ask.boxId)))
    val totalReward = if (askRest.isDefined) fee else Math.addExact(fee, ask.box.value)

    val askBase = askRest.foldLeft(askInput.withCtxVar(0.toByte, int(0)))((b, _) => b.withCtxVar(1.toByte, int(3)))
    val askIn = if (!ask.priced) askBase
      else ask.pricingHash.flatMap(ZcContracts.expressionByHash).fold(askBase)(e =>
        askBase.withCtxVar(2.toByte, raw(e.bytes)).withCtxVar(3.toByte, int(0)))
    val policy = ZcContracts.expression(ZcExpression.CollateralPolicy, Some(m)).bytes
    val offerBase = offerInput
      .withCtxVar(0.toByte, int(2))
      .withCtxVar(1.toByte, raw(policy))
      .withCtxVar(2.toByte, int(0))
      .withCtxVar(3.toByte, int(0))
      .withCtxVar(4.toByte, raw(originBytes))
      .withCtxVar(5.toByte, int(1))
      .withCtxVar(7.toByte, int(0))
    val offerIn = requestBytes.fold(offerBase)(bs => offerBase.withCtxVar(6.toByte, raw(bs)))

    val outputs = Seq(payout, delivery, offerRest) ++ askRest.toSeq
    val built = signSelfFunded(ctx, wallet, Seq(askIn, offerIn), outputs, feeTo, totalReward, minerFee, height,
      KindMatchAsk, Set(ask.boxId, offer.boxId), dataInputs = Seq(bondInput, oracle), named = Seq(owner, seller))
    val nextOfferBox = built.signed.getOutputsToSpend.get(2)
    val nextOffer = ZcBoxes.read(nodeBoxOf(nextOfferBox, built.txId, 2), offer.script).collect { case o: ZcOffer => o }
      .getOrElse(throw new IllegalStateException("offer continuation did not read back as a ZcOffer"))
    val nextAsk = askRest.map { _ =>
      val b = built.signed.getOutputsToSpend.get(3)
      val rec = ZcBoxes.read(nodeBoxOf(b, built.txId, 3), ask.script).collect { case a: ZcAsk => a }
        .getOrElse(throw new IllegalStateException("ask continuation did not read back as a ZcAsk"))
      rec -> InputUTXO(b)
    }
    ZcAskMatchBuilt(built, nextOffer, InputUTXO(nextOfferBox), nextAsk)
  }

  // ─── standalone actions: mints, pulls, wrap refresh ────────────────────────

  /** Mints `req`'s shares: N to `owner`, N+1 + bond unit into the share vault, and `mintFee - minerFee` to `feeTo`. */
  def mintShares(ctx: BlockchainContext, wallet: NodeWallet, req: ZcShareRequest, input: InputUTXO, owner: Contract,
                 state: Option[(InputUTXO, Long, Option[Array[Byte]])], feeTo: Contract, minerFee: Long,
                 height: Int): ZcBatchTx = {
    require(input.id.toString == req.boxId, "share request input does not match its id")
    val spend = ZcUserTransactions.mintShares(req, input, owner, state)
    val mintFee = req.terms(2)
    signSelfFunded(ctx, wallet, spend.inputs, spend.outputs, feeTo, mintFee, minerFee, height, KindMintShares,
      Set(req.boxId), dataInputs = spend.dataInputs)
  }

  /** Pulls `source` settlement into `vault`, burning all 3 bond units and sending `pullFee - minerFee` to `feeTo`. */
  def pull(ctx: BlockchainContext, wallet: NodeWallet, vault: ZcShareVault, vaultInput: InputUTXO,
           source: ZcSettlement, sourceInput: InputUTXO, feeTo: Contract, minerFee: Long, height: Int): ZcBatchTx = {
    require(vaultInput.id.toString == vault.boxId && sourceInput.id.toString == source.boxId,
      "pull inputs do not match their records")
    val spend = ZcUserTransactions.pull(vault, vaultInput, source, sourceInput, height.toLong)
    signSelfFunded(ctx, wallet, spend.inputs, spend.outputs, feeTo, vault.pullFee, minerFee, height, KindPull,
      Set(vault.boxId, source.boxId), burn = spend.burn)
  }

  /** Mints `req`'s wrap receipt as the transaction's ONLY input, keeping `mintFee - minerFee`. */
  def mintWrap(ctx: BlockchainContext, wallet: NodeWallet, req: ZcWrapRequest, input: InputUTXO, owner: Contract,
               feeTo: Contract, minerFee: Long, height: Int): ZcBatchTx = {
    require(input.id.toString == req.boxId, "wrap request input does not match its id")
    val spend = ZcUserTransactions.mintWrap(req, input, owner)
    signSelfFunded(ctx, wallet, spend.inputs, spend.outputs, feeTo, req.mintFee, minerFee, height, KindMintWrap,
      Set(req.boxId))
  }

  /** Refreshes an aged wrap vault as the transaction's ONLY input, keeping its bounty less `minerFee`. */
  def refreshWrapVault(ctx: BlockchainContext, wallet: NodeWallet, vault: ZcWrapVault, input: InputUTXO,
                       feeTo: Contract, minerFee: Long, height: Int): ZcBatchTx = {
    require(input.id.toString == vault.boxId, "wrap vault input does not match its id")
    require(refreshable(input.input.getCreationHeight.toLong, height.toLong), "wrap vault is not old enough to refresh")
    val nextValue = refreshedValue(vault.box.value, MinBox)
    val bounty = vault.box.value - nextValue
    require(bounty > 0L, "wrap vault pays no refresh bounty")
    val successor = UTXO(input.contract, nextValue, input.tokens, Seq.empty, Some(height))
    signSelfFunded(ctx, wallet, Seq(input.withCtxVar(9.toByte, int(0))), Seq(successor), feeTo, bounty, minerFee,
      height, KindWrapRefresh, Set(vault.boxId))
  }
}
