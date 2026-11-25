package transactions.batching.sigmafi.zc

import mutations.NodeWallet
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoType, ErgoValue, Parameters, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket}
import transactions.batching.sigmafi.SFPlan
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * The ZC transactions this wallet makes, at the input and output positions and context variables of the reference
 * CLI. Protocol inputs come first; wallet boxes follow only when those inputs do not cover the outputs and the fee.
 * When no output carries a script a posted box names, INPUTS(0) discloses it in context variable 64.
 *
 * {{{
 *   post order, offer, ask, lot,     IN  wallet...                 OUT the box, [change], fee
 *     share or wrap request
 *   cancel                           IN  box, auth box             OUT everything less the fee, to the creator
 *   fill order                       IN  order, wallet...          OUT unit, bond, principal, [change], fee
 *   buy from ask                     IN  ask, wallet...            OUT payout, [rest], [change], fee
 *   repay                            IN  bond, [auth], wallet...   OUT settlement, collateral back, [change], fee
 *   extinguish                       IN  bond, auth, [wallet...]   OUT one sweep, fee              (3 units burned)
 *   recollateralize                  IN  bond, auth, wallet...     OUT successor, [change], fee
 *   liquidate                        IN  bond, [wallet...]         OUT settlement, [change], fee
 *   claim a unit                     IN  settlement..., wallet...  OUT one sweep, fee              (3 units burned)
 *   claim shares                     IN  vault, wallet...          OUT payout, [vault], [change], fee
 *   mint shares                      IN  request, [wallet...]      OUT shares, vault, [change], fee
 *   pull                             IN  vault, settlement, [w...] OUT vault, [change], fee        (3 units burned)
 *   buy from a vault                 IN  vault, wallet...          OUT vault, [change], fee
 *   buy a lot                        IN  lot, wallet...            OUT payout, [change], fee
 *   mint a wrap                      IN  request                   OUT receipt, vault, [change], fee
 *   unwrap                           IN  vault, wallet...          OUT one sweep, fee              (3 receipts burned)
 * }}}
 */
object ZcUserTransactions {

  final val TxFee: Long = Parameters.MinFee
  private final val Headroom: Long = UTXO.MIN_CHANGE
  private final val MinBox: Long = ZcBoxes.MinBox

  /** zcs1 script disclosure's context variable. */
  final val DisclosureVar: Byte = 64.toByte

  def hashOf(tree: Array[Byte]): String = Hex.toHexString(Blake2b256.hash(tree))

  private def bytes(hex: String): ErgoValue[_] = ErgoValue.of(Colls.fromArray(Hex.decode(hex)), scalaByteType)
  private def raw(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
  private def longs(xs: Seq[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(xs.toArray), scalaLongType)
  private def int(i: Int): ErgoValue[_] = ErgoValue.of(i)
  private def byte(b: Byte): ErgoValue[_] = ErgoValue.of(b)
  private def long(l: Long): ErgoValue[_] = ErgoValue.of(l)
  private def utf8(texts: Seq[String]): Seq[ErgoValue[_]] = texts.map(t => raw(t.getBytes(UTF_8)))

  def bytesList(items: Seq[Array[Byte]]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(items.map(i => Colls.fromArray(i)).toArray)(sigma.collRType(sigma.ByteType)),
      ErgoType.collType(scalaByteType))

  private def tokenPairs(ts: Seq[(String, Long)]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(ts.map { case (t, n) => Colls.fromArray(Hex.decode(t)) -> n }.toArray)(
      sigma.data.RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType)),
      ErgoType.pairType(ErgoType.collType(scalaByteType), scalaLongType))

  private def tokens(r: ZcRecord): Seq[Token] = r.box.assets.map(a => Token(ErgoId.create(a.tokenId), a.amount))

  private def token(id: String, n: Long): Token = Token(ErgoId.create(id), n)

  private def contract(r: ZcRecord): Contract = r.script.contract

  private def script(kind: ZcKind, market: Option[ZcMarket]): Contract = ZcContracts.script(kind, market).contract

  // ─── spends ────────────────────────────────────────────────────────────────

  /**
   * A ZC spend: protocol inputs at the positions their contracts read, the outputs they name, burns, the token the
   * spend mints and data inputs. `alone` refuses wallet boxes, for a wrap mint, whose request must be the only input.
   * `sweep` sends everything the inputs hold, less burns and the fee, to one box at that script in place of `outputs`.
   * `named` are scripts a box names by hash that no output carries: INPUTS(0) discloses them in variable 64.
   */
  final case class ZcSpend(inputs: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token] = Seq.empty,
                           minted: Option[String] = None, dataInputs: Seq[InputUTXO] = Seq.empty,
                           alone: Boolean = false, sweep: Option[Contract] = None, named: Seq[Contract] = Seq.empty)

  private def tally(ts: Seq[Token]): Map[String, Long] =
    ts.groupBy(_.id.toString).map { case (id, xs) => id -> xs.map(_.amount).sum }

  /** What `inputs` leave after `s`'s outputs, burns and the fee: nanoERG, and each token's net, the mint left out. */
  private def left(s: ZcSpend, inputs: Seq[InputUTXO]): (Long, Map[String, Long]) = {
    val outs = if (s.sweep.isDefined) Seq.empty else s.outputs
    val erg = inputs.map(_.value).sum - outs.map(_.value).sum - TxFee
    val held = tally(inputs.flatMap(_.tokens))
    val used = tally(outs.flatMap(_.tokens) ++ s.burn)
    val net = (held.keySet ++ used.keySet).map(id => id -> (held.getOrElse(id, 0L) - used.getOrElse(id, 0L))).toMap
    (erg, net -- s.minted.toSeq)
  }

  /**
   * What the wallet adds to `s`, and how the transaction is built once it has. Nothing is asked of the wallet when the
   * protocol inputs cover everything: change of at least a box minimum, or a surplus under it with no token to carry,
   * which goes to the miner.
   */
  def plan(ctx: BlockchainContext, wallet: NodeWallet, s: ZcSpend): SFPlan = {
    val (erg, net) = left(s, s.inputs)
    val short = net.collect { case (id, n) if n < 0L => token(id, -n) }.toSeq
    val carries = net.exists(_._2 > 0L) || s.sweep.isDefined
    val selfFunded = short.isEmpty && (erg >= MinBox || (erg >= 0L && !carries))
    require(selfFunded || !s.alone, "the box's own fee does not cover the miner fee, and nothing may be spent beside it")
    if (selfFunded) SFPlan(0L, Seq.empty, _ => build(ctx, wallet, s, Seq.empty))
    else SFPlan(math.max(0L, MinBox - erg), short, funding => build(ctx, wallet, s, funding),
      avoidTokens = s.burn.map(_.id.toString).toSet -- short.map(_.id.toString))
  }

  /** Whether `plan` needs nothing from the wallet. */
  def selfFunded(plan: SFPlan): Boolean = plan.value == 0L && plan.tokens.isEmpty

  private def build(ctx: BlockchainContext, wallet: NodeWallet, s: ZcSpend, funding: Seq[InputUTXO]): UnsignedTransaction = {
    val inputs = s.inputs ++ funding
    val (erg, net) = left(s, inputs)
    val kept = net.collect { case (id, n) if n > 0L => token(id, n) }.toSeq
    val outputs = s.sweep.fold(s.outputs) { to =>
      require(erg >= MinBox, "the inputs hold too little for the box they sweep into")
      Seq(UTXO(to, erg, kept))
    }
    val dust = if (s.sweep.isEmpty && erg > 0L && erg < MinBox && kept.isEmpty) erg else 0L
    TxBuilder(ctx).setInputs(disclosedAll(inputs, outputs, s.named): _*).setDataInputs(s.dataInputs: _*)
      .setOutputs(outputs: _*)
      .buildTx(TxFee + dust, wallet.p2pk, s.burn)
  }

  // ─── posting ───────────────────────────────────────────────────────────────

  /** The order box a draft order describes, naming `borrower` by hash. A priced order is stamped at `height`. */
  def orderBox(o: ZcOrder, borrowerHash: String, height: Int): UTXO = {
    val core = Seq(longs(o.terms), tokenPairs(o.principalTokens.map(t => t.tokenId -> t.amount)), bytes(borrowerHash),
      bytes(o.liqHash))
    val extra =
      if (!o.priced) o.guardHash.map(bytes).toSeq
      else Seq(bytesList(Seq(Hex.decode(o.pricingHash.getOrElse("")), o.guardHash.map(Hex.decode).getOrElse(Array.emptyByteArray))),
        longs(o.pricingTerms))
    UTXO(contract(o), o.box.value, tokens(o), core ++ extra, if (o.priced) Some(height) else None)
  }

  /** `offer` as its first fill leaves it: R6 grows from the policy hash to the policy hash and a 32-byte box id. */
  def offerAfterFirstFill(offer: UTXO, policyHash: String): UTXO =
    offer.withRegNum(6, raw(Hex.decode(policyHash) ++ new Array[Byte](32)))

  /**
   * The offer box a draft offer describes, naming `owner` by hash. One that delivers shares carries their zcs1
   * metadata, without the bond, which it does not know yet.
   */
  def offerBox(o: ZcOffer, ownerHash: String): UTXO = {
    val metadata = if (o.fractional.size < 8) Seq.empty[Array[Byte]]
      else ZcMetadata.shares(o.market, Some(ZcMetadata.Auction(o.fractional(3), o.fractional(4), o.fractional(5),
        o.fractional(6), o.fractional(7))), None).map(_.getBytes(UTF_8))
    UTXO(contract(o), o.box.value, tokens(o), Seq(bytes(ownerHash), longs(o.terms), bytes(o.policyHash),
      longs(o.fractional), bytesList(metadata), raw(o.mintHash)))
  }

  /** The ask box a draft ask describes, naming `seller` by hash. */
  def askBox(a: ZcAsk, sellerHash: String): UTXO = {
    val core = Seq(bytes(sellerHash), longs(a.terms))
    val priced = if (!a.priced) Seq.empty else Seq(bytes(a.pricingHash.getOrElse("")), bytes(a.pricedAsset.getOrElse("")))
    UTXO(contract(a), a.box.value, tokens(a), core ++ priced)
  }

  /**
   * A lot auction selling `items`, and for a token market `erg` nanoERG beside them, named for `seller`. The refund fee
   * sits above the box minimum, so an expiry refund still leaves the seller a valid box.
   */
  def lotBox(market: ZcMarket, items: Seq[Token], erg: Long, terms: Seq[Long], sellerHash: String): UTXO =
    UTXO(script(ZcKind.LotAuction, Some(market)), Math.addExact(math.max(erg, MinBox), terms(5)), items,
      Seq(bytes(sellerHash), longs(terms)))

  /**
   * A share mint request holding one bond unit: a plain `FractionalMint`, or with `auction` the market's
   * `DutchAuctionMint`. R4 to R6 repeat the metadata so R7 to R9 can be set; it funds both box minimums and both fees.
   */
  def shareRequestBox(asset: String, market: ZcMarket, terms: Seq[Long], auction: Boolean, metadata: Seq[String],
                      ownerHash: String): UTXO = {
    val kind = if (auction) script(ZcKind.DutchAuctionMint, Some(market)) else script(ZcKind.FractionalMint, None)
    UTXO(kind, 2L * MinBox + terms(1) + terms(2), Seq(token(asset, 1L)),
      utf8(metadata) ++ Seq(bytes(ownerHash), longs(terms), bytesList(metadata.map(_.getBytes(UTF_8)))))
  }

  /** A wrap request for `amount` of `tokenId`, funding the receipt, the vault with its refresh reserve, and the fee. */
  def wrapRequestBox(tokenId: String, amount: Long, mintFee: Long, refreshReserve: Long, ownerHash: String): UTXO =
    UTXO(script(ZcKind.WrapMint, None), Math.addExact(Math.addExact(2L * MinBox, refreshReserve), mintFee),
      Seq(token(tokenId, amount)), Seq(bytes(ownerHash), long(mintFee),
        bytesList(ZcMetadata.receipt(tokenId, amount).map(_.getBytes(UTF_8)))))

  /**
   * Posts `box` from this wallet. INPUTS(0) carries `named`, the script the box names, in variable 64: a batcher then
   * finds it in one lookup of this transaction, pending or not.
   */
  def post(ctx: BlockchainContext, wallet: NodeWallet, box: UTXO, named: Contract): SFPlan =
    SFPlan(Math.addExact(box.value, TxFee + Headroom), box.tokens, funding => {
      require(funding.nonEmpty, "posting needs at least one wallet box")
      TxBuilder(ctx).setInputs(disclosed(funding, Seq(box), named): _*).setOutputs(box).buildTx(TxFee, wallet.p2pk)
    })

  /** `inputs` with `named` disclosed on INPUTS(0) unless a listed output carries it. */
  def disclosed(inputs: Seq[InputUTXO], outputs: Seq[UTXO], named: Contract): Seq[InputUTXO] =
    disclosedAll(inputs, outputs, Seq(named))

  /**
   * `inputs` with every `named` script that no listed output carries disclosed in variable 64 of INPUTS(0). Inputs do
   * not count: the node's unconfirmed transactions leave out input scripts, so only an output shows one while pending.
   */
  def disclosedAll(inputs: Seq[InputUTXO], outputs: Seq[UTXO], named: Seq[Contract]): Seq[InputUTXO] = {
    val present = outputs.map(_.contract.ergoTreeHex).toSet
    val missing = named.foldLeft(Vector.empty[Contract]) { (acc, c) =>
      if (present.contains(c.ergoTreeHex) || acc.exists(_.ergoTreeHex == c.ergoTreeHex)) acc else acc :+ c
    }
    if (missing.isEmpty || inputs.isEmpty) inputs
    else inputs.head.withCtxVar(DisclosureVar, bytesList(missing.map(_.ergoTree.bytes))) +: inputs.tail
  }

  /** Takes `box` back: `auth`, a box at the creator's script, authorizes it, and both go to `refundTo` less the fee. */
  def cancel(ctx: BlockchainContext, wallet: NodeWallet, box: InputUTXO, auth: InputUTXO, refundTo: Contract): UnsignedTransaction = {
    val held = (box.tokens ++ auth.tokens).groupBy(_.id).map { case (id, ts) => Token(id, ts.map(_.amount).sum) }.toSeq
    TxBuilder(ctx).setInputs(box, auth).setOutputs(UTXO(refundTo, Math.addExact(box.value, auth.value) - TxFee, held))
      .buildTx(TxFee, wallet.p2pk)
  }

  // ─── lending ───────────────────────────────────────────────────────────────

  /**
   * Fills `order`, whose principal is already the amount this fill pays, as INPUTS(0), so the fill mints the bond asset:
   * one unit to `lender` at OUTPUTS(0) with zcs1 metadata, two locked in the bond at OUTPUTS(1), and the principal with
   * the unused expiry fee to `borrower` at OUTPUTS(2), tagged with the order. The bond is stamped at `start`, which
   * starts its recollateralize wait. A guarded order reads `oracle` as a data input.
   */
  def fillOrder(order: ZcOrder, input: InputUTXO, borrower: Contract, lender: Contract, start: Long, maturity: Long,
                feeReserve: Long, oracle: Option[InputUTXO]): ZcSpend = {
    require(feeReserve >= 0L, "the fee reserve cannot be negative")
    val m = order.market
    val asset = order.boxId
    val rule = ZcRules.rule(order.liqHash, m)
    val metadata = ZcMetadata.bond(ZcMetadata.BondFacts(m, asset, order.principal, order.repayment, start, maturity,
      ZcMetadata.ruleFields(rule, order.payload, order.liqHash)))
    val unit = UTXO(lender, MinBox, Seq(token(asset, 1L)), utf8(metadata))
    val collateralErg = order.box.value - order.expiryFee
    val bond = UTXO(script(ZcKind.Bond, Some(m)), Math.addExact(collateralErg, feeReserve),
      token(asset, 2L) +: tokens(order),
      Seq(bytes(asset), bytes(order.borrowerHash), longs(order.terms), tokenPairs(order.principalTokens.map(t => t.tokenId -> t.amount)),
        bytes(order.liqHash), longs(Seq(feeReserve, start, maturity))), Some(start.toInt))
    val principal = UTXO(borrower, Math.addExact(order.expiryFee, order.principalErg),
      order.principalTokens.map(t => token(t.tokenId, t.amount)), Seq(bytes(asset)))
    val guarded = if (order.guardHash.isEmpty) input
      else input.withCtxVar(1.toByte, raw(ZcContracts.expression(sigmafi.zc.ZcExpression.FillGuard, Some(m)).bytes))
    val priced = order.pricingHash.filter(_ => order.priced).flatMap(ZcContracts.expressionByHash)
      .fold(guarded)(e => guarded.withCtxVar(2.toByte, raw(e.bytes)))
    ZcSpend(Seq(priced), Seq(unit, bond, principal), minted = Some(asset),
      dataInputs = if (order.guardHash.isEmpty) Seq.empty else oracle.toSeq)
  }

  /** `order` with its principal set to `amount`, as a priced order's fill stores what it actually pays. */
  def resolved(order: ZcOrder, amount: Long): ZcOrder =
    if (!order.priced) order
    else order.market.tokenId match {
      case None => order.copy(terms = order.terms.updated(1, amount))
      case Some(id) => order.copy(principalTokens = Seq(node.model.NodeAsset(id, amount)))
    }

  /**
   * Buys `units` from `ask` for `paid`: the payout to `seller` at OUTPUTS(0), tagged R4 the ask's id and R5 the units,
   * and what is left continuing at OUTPUTS(1), naming the ask it continues. A yield ask runs its expression from
   * variable 2 and reads `bond` as data input 0; a guarded one reads `oracle` after it. The units reach this wallet as
   * change.
   */
  def buyAsk(ask: ZcAsk, input: InputUTXO, seller: Contract, units: Long, paid: Long, bond: Option[InputUTXO],
             oracle: Option[InputUTXO]): ZcSpend = {
    val tag = Seq(bytes(ask.boxId), long(units))
    val payout = ask.market.tokenId match {
      case None => UTXO(seller, math.max(paid, MinBox), Seq.empty, tag)
      case Some(id) => UTXO(seller, MinBox, Seq(token(id, paid)), tag)
    }
    val rest = ask.token.amount - units
    val continuation = if (rest <= 0L) None
      else Some(UTXO(contract(ask), ask.box.value, Seq(token(ask.token.tokenId, rest)), input.registers)
        .withRegNum(if (ask.priced) 8 else 6, bytes(ask.boxId)))
    val base = continuation.foldLeft(input.withCtxVar(0.toByte, int(0)))((b, _) => b.withCtxVar(1.toByte, int(1)))
    val withPricing = if (!ask.priced) base
      else ask.pricingHash.flatMap(ZcContracts.expressionByHash).fold(base)(e =>
        base.withCtxVar(2.toByte, raw(e.bytes)).withCtxVar(3.toByte, int(0)))
    ZcSpend(Seq(withPricing), payout +: continuation.toSeq, dataInputs = bond.toSeq ++ oracle.toSeq)
  }

  /**
   * Buys `lot` at `price`: the payout to `seller`, tagged with the lot. An ERG lot's own nanoERG return to the seller
   * with the payment; a token lot's nanoERG come to this wallet with its tokens, as change.
   */
  def buyLot(lot: ZcLot, input: InputUTXO, seller: Contract, price: Long): ZcSpend = {
    val tag = Seq(bytes(lot.boxId))
    val payout = lot.market.tokenId match {
      case None => UTXO(seller, Math.addExact(lot.box.value, price), Seq.empty, tag)
      case Some(id) => UTXO(seller, MinBox, Seq(token(id, price)), tag)
    }
    ZcSpend(Seq(input.withCtxVar(0.toByte, int(0))), Seq(payout))
  }

  // ─── borrowing ─────────────────────────────────────────────────────────────

  /**
   * Repays `bond` into the bond vault at OUTPUTS(0), with its fee reserve, tagged R4 the bond and R5 0, and returns the
   * collateral to `borrower` at OUTPUTS(1), tagged with the bond. `kept` of the repayment asset stays back from the
   * collateral to pay part of the repayment, which needs `auth`, a box at the borrower's script.
   */
  def repay(bond: ZcBond, input: InputUTXO, borrower: Contract, kept: Long, auth: Option[InputUTXO]): ZcSpend = {
    require(kept == 0L || auth.isDefined, "repaying from collateral needs the borrower's box")
    val tag = Seq(bytes(bond.boxId), byte(0.toByte))
    val vault = script(ZcKind.BondVault, None)
    val settlement = bond.market.tokenId match {
      case None => UTXO(vault, Math.addExact(bond.feeReserve, bond.repayment), Seq(token(bond.asset, 2L)), tag)
      case Some(id) => UTXO(vault, math.max(MinBox, Math.addExact(bond.feeReserve, bond.terms(2))),
        Seq(token(bond.asset, 2L), token(id, bond.repayment)), tag)
    }
    val (backErg, backTokens) = bond.market.tokenId match {
      case None => (bond.collateralErg - kept, bond.collateral)
      case Some(id) => (bond.collateralErg, bond.collateral.flatMap(t =>
        if (t.tokenId != id) Some(t) else if (t.amount > kept) Some(t.copy(amount = t.amount - kept)) else None))
    }
    val back = UTXO(borrower, backErg, backTokens.map(t => token(t.tokenId, t.amount)), Seq(bytes(bond.boxId)))
    ZcSpend(input.withCtxVar(1.toByte, int(0)).withCtxVar(2.toByte, int(1)) +: auth.toSeq, Seq(settlement, back))
  }

  /**
   * Extinguishes `bond` directly: burns its two locked units together with the circulating unit the wallet adds
   * (from `auth` or funding), with `auth` at the borrower's script authorizing it and no context variables set.
   * Valid at any height; sweeps collateral and fee reserve straight to `to`.
   */
  def extinguish(bond: ZcBond, input: InputUTXO, auth: InputUTXO, to: Contract): ZcSpend = {
    val locked = input.tokens.filter(_.id.toString == bond.asset).map(_.amount).sum
    require(locked == 2L, s"bond ${bond.boxId} must hold 2 reserve units of ${bond.asset}, found $locked")
    ZcSpend(Seq(input, auth), Seq.empty, burn = Seq(token(bond.asset, 3L)), sweep = Some(to))
  }

  /** The most of the repayment the bond's own collateral can pay: nanoERG above a box minimum, or the repayment token. */
  def repayableFromCollateral(bond: ZcBond): Long = bond.market.tokenId match {
    case None => math.max(0L, math.min(bond.repayment, bond.collateralErg - MinBox))
    case Some(id) => math.min(bond.repayment, bond.collateral.filter(_.tokenId == id).map(_.amount).sum)
  }

  /**
   * Adds `erg` and `added` tokens to `bond`: its successor at OUTPUTS(0) keeps every register and is stamped at
   * `height`, which restarts the 60-block wait; `auth`, a box at the borrower's script, is INPUTS(1). A token the bond
   * does not hold yet goes last.
   */
  def recollateralize(bond: ZcBond, input: InputUTXO, auth: InputUTXO, erg: Long, added: Seq[Token], height: Int): ZcSpend = {
    val grown = input.tokens.map(t => added.find(_.id == t.id).fold(t)(a => Token(t.id, Math.addExact(t.amount, a.amount))))
    val appended = added.filterNot(a => input.tokens.exists(_.id == a.id))
    val successor = UTXO(contract(bond), Math.addExact(bond.box.value, erg), grown ++ appended, input.registers, Some(height))
    ZcSpend(Seq(input.withCtxVar(3.toByte, int(0)), auth), Seq(successor), named = Seq(auth.contract))
  }

  /**
   * Liquidates `bond`: its rule's expression in variable 0, its settlement at OUTPUTS(0) named in variable 1, tagged R4
   * the bond and R5 1. The fee reserve pays whoever runs it. An oracle rule before maturity reads `oracle`.
   */
  def liquidate(bond: ZcBond, input: InputUTXO, expression: Array[Byte], oracle: Option[InputUTXO]): ZcSpend = {
    val settlement = UTXO(script(ZcKind.BondVault, None), bond.box.value - bond.feeReserve, input.tokens,
      Seq(bytes(bond.boxId), byte(1.toByte)))
    ZcSpend(Seq(input.withCtxVar(0.toByte, raw(expression)).withCtxVar(1.toByte, int(0))), Seq(settlement),
      dataInputs = oracle.toSeq)
  }

  // ─── claims ────────────────────────────────────────────────────────────────

  /** Claims every settlement box of `asset` with the unit the wallet adds; all of the asset burns, everything else to `to`. */
  def claimUnit(asset: String, settlements: Seq[InputUTXO], to: Contract): ZcSpend = {
    val locked = settlements.flatMap(_.tokens).filter(_.id.toString == asset).map(_.amount).sum
    ZcSpend(settlements, Seq.empty, burn = Seq(token(asset, locked + 1L)), sweep = Some(to))
  }

  /**
   * Claims `burned` of `v`'s shares. Burning every outstanding share is a final claim: the vault and its reserve go
   * too, all to `to`. A partial claim pays the burned fraction of each asset, rounded down, at OUTPUTS(0), and the vault
   * continues at OUTPUTS(1); an open auction it ends is marked state 3.
   */
  def claimShares(ctx: BlockchainContext, v: ZcShareVault, input: InputUTXO, burned: Long, to: Contract): ZcSpend = {
    val tag = Seq(bytes(v.boxId))
    val assets = v.box.assets.drop(1)
    if (burned >= v.outstanding)
      ZcSpend(Seq(input.withCtxVar(1.toByte, int(0))),
        Seq(ZcSizing.sized(ctx, UTXO(to, MinBox, assets.map(t => token(t.tokenId, t.amount)), tag), v.box.value)),
        burn = Seq(token(v.shareId, Math.addExact(v.outstanding, v.shares + 1L))))
    else {
      def part(n: Long): Long = (BigInt(n) * burned / v.outstanding).toLong
      val paidErg = part(v.box.value)
      val paid = assets.flatMap(t => Some(part(t.amount)).filter(_ > 0L).map(token(t.tokenId, _)))
      val kept = assets.flatMap(t => Some(t.amount - part(t.amount)).filter(_ > 0L).map(token(t.tokenId, _)))
      val regs = input.registers.take(v.schemaRegisters)
      val state = if (ZcRules.auctionOpen(v)) Some(3.toByte) else v.state
      val next = regs.updated(1, longs(Seq(v.shares, v.outstanding - burned, v.pullFee)))
        .patch(2, state.map(s => byte(s)).toSeq, if (regs.size > 2) 1 else 0)
      // Both outputs only have to hold at least their share, so the wallet tops either up to its size minimum.
      ZcSpend(Seq(input.withCtxVar(0.toByte, int(1)).withCtxVar(1.toByte, int(0))),
        Seq(ZcSizing.sized(ctx, UTXO(to, MinBox, paid, tag), paidErg),
          ZcSizing.sized(ctx, UTXO(contract(v), MinBox, token(v.shareId, v.shares + 1L) +: kept, next),
            v.box.value - paidErg)),
        burn = Seq(token(v.shareId, burned)))
    }
  }

  // ─── shares ────────────────────────────────────────────────────────────────

  /**
   * Mints `req`'s shares: N to `owner` at OUTPUTS(0) with the committed metadata, N+1 locked with the bond unit in the
   * vault at OUTPUTS(1). A Dutch request reads the bond's state box (`state`) as data input 0 for the repayment, and for
   * a settled bond carries the settled bond box's bytes in variable 1. The mint fee, less the miner fee, is change.
   */
  def mintShares(req: ZcShareRequest, input: InputUTXO, owner: Contract, state: Option[(InputUTXO, Long, Option[Array[Byte]])]): ZcSpend = {
    val n = req.terms(0)
    val pullFee = req.terms(1)
    val mintFee = req.terms(2)
    val shares = UTXO(owner, MinBox, Seq(token(req.shareId, n)), req.metadata.map(raw))
    val vaultValue = req.box.value - mintFee - MinBox
    val held = Seq(token(req.shareId, n + 1L), token(req.bondAsset, 1L))
    val fixed = Seq(bytes(req.bondAsset), longs(Seq(n, n, pullFee)))
    req.dutch match {
      case None =>
        ZcSpend(Seq(input), Seq(shares, UTXO(script(ZcKind.FractionalVault, None), vaultValue, held, fixed)),
          minted = Some(req.shareId))
      case Some(m) =>
        val (stateBox, repayment, settledBond) = state.getOrElse(throw new IllegalArgumentException(
          "a Dutch mint reads the bond's state box"))
        val regs = fixed ++ Seq(byte(0.toByte), longs(repayment +: req.terms.drop(3) :+ 0L),
          raw(m.tokenId.map(Hex.decode).getOrElse(Array.emptyByteArray)))
        val in = settledBond.foldLeft(input.withCtxVar(0.toByte, int(0)))((b, bs) => b.withCtxVar(1.toByte, raw(bs)))
        ZcSpend(Seq(in), Seq(shares, UTXO(script(ZcKind.DutchAuctionVault, None), vaultValue, held, regs)),
          minted = Some(req.shareId), dataInputs = Seq(stateBox))
    }
  }

  /**
   * Pulls a settled bond into the share vault holding its unit, at `height`: the vault takes the settlement's proceeds,
   * all 3 bond units burn, and the pull fee, less the miner fee, is change. A liquidated Dutch vault opens an auction
   * when some lot item is thinner than its in-kind threshold: the floor auction (state 1) when it holds less currency
   * than the floor, else an excess auction (state 4) when the opening is above zero.
   */
  def pull(v: ZcShareVault, input: InputUTXO, source: ZcSettlement, sourceInput: InputUTXO, height: Long): ZcSpend = {
    val value = Math.addExact(v.box.value, source.box.value) - v.pullFee
    val proceeds = source.proceeds.map(t => token(t.tokenId, t.amount))
    val supply = Seq(bytes(v.bondAsset), longs(Seq(v.shares, v.outstanding, v.pullFee)))
    val regs =
      if (!v.dutch) supply :+ byte((if (source.liquidated) 1 else 0).toByte)
      else {
        val currency = ZcRules.vaultCurrency(v)
        val held = currency.fold(value)(id => source.proceeds.filter(_.tokenId == id).map(_.amount).sum)
        val lotTokens = source.proceeds.filterNot(t => currency.contains(t.tokenId))
        val lotErg = if (currency.isEmpty) 0L else value - MinBox
        val hasLot = lotTokens.nonEmpty || lotErg > 0L
        val threshold = v.auction(5)
        val short = BigInt(held) * 10000 < BigInt(v.auction(0)) * v.auction(2)
        val thin = lotTokens.exists(_.amount < threshold) || (currency.isDefined && lotErg < threshold)
        val sells = source.liquidated && hasLot && thin
        val state: Byte = if (!source.liquidated) 0 else if (sells && short) 1 else if (sells && v.auction(1) > 0L) 4 else 2
        val began = if (state == 1 || state == 4) height else 0L
        supply ++ Seq(byte(state), longs(v.auction.updated(6, began)), raw(v.currency.getOrElse(Array.emptyByteArray)))
      }
    val next = UTXO(contract(v), value, token(v.shareId, v.shares + 1L) +: proceeds, regs)
    ZcSpend(Seq(input.withCtxVar(0.toByte, int(0)), sourceInput), Seq(next), burn = Seq(token(v.bondAsset, 3L)))
  }

  /**
   * Buys a Dutch vault's lot at `height`, marking it sold (state 2). A floor auction: bring the currency up to the
   * target, and take the whole lot. An excess auction: add its price, and take only the thin items. What the buyer
   * takes reaches this wallet as change.
   */
  def buyFromVault(ctx: BlockchainContext, v: ZcShareVault, input: InputUTXO, height: Long): ZcSpend = {
    val before = ZcRules.currencyHeld(v)
    val after =
      if (ZcRules.excessAuction(v)) Math.addExact(before, ZcRules.excessPrice(v, height).getOrElse(
        throw new IllegalArgumentException("the excess auction's price reached zero")))
      else math.max(ZcRules.lotTarget(v, height).getOrElse(
        throw new IllegalArgumentException("the auction's target is past a Long")), before)
    ZcSpend(Seq(input.withCtxVar(0.toByte, int(0))), Seq(soldVault(ctx, v, input, after)))
  }

  /**
   * `v` marked sold (state 2) once its currency stands at `after`, keeping only its five registers. An ERG vault holds
   * `after`; a token vault holds `after` of its currency and only its size minimum in ERG, unless the sale keeps the
   * vault's ERG. What else it held goes to the buyer.
   */
  def soldVault(ctx: BlockchainContext, v: ZcShareVault, input: InputUTXO, after: Long): UTXO = {
    val regs = input.registers.take(v.schemaRegisters).updated(2, byte(2.toByte))
    val kept = ZcRules.keptBySale(v).map(t => token(t.tokenId, t.amount))
    val reserve = token(v.shareId, v.shares + 1L)
    ZcRules.vaultCurrency(v) match {
      case None => ZcSizing.sized(ctx, UTXO(contract(v), MinBox, reserve +: kept, regs), after)
      case Some(id) => ZcSizing.sized(ctx, UTXO(contract(v), MinBox, Seq(reserve, token(id, after)) ++ kept, regs),
        if (ZcRules.saleKeepsErg(v)) v.box.value else MinBox)
    }
  }

  // ─── wraps ─────────────────────────────────────────────────────────────────

  /**
   * Mints `req`'s receipt: one to `owner` with the committed metadata, two and the token into the vault. The request
   * must be the only input, so the mint fee pays the miner and the rest is change.
   */
  def mintWrap(req: ZcWrapRequest, input: InputUTXO, owner: Contract): ZcSpend = {
    val receipt = UTXO(owner, MinBox, Seq(token(req.receiptId, 1L)), req.metadata.map(raw))
    val vault = UTXO(script(ZcKind.WrapVault, None), req.box.value - MinBox - req.mintFee,
      Seq(token(req.receiptId, 2L), token(req.wrapped.tokenId, req.wrapped.amount)))
    ZcSpend(Seq(input), Seq(receipt, vault), minted = Some(req.receiptId), alone = true)
  }

  /** Burns all 3 units of `v`'s receipt, the wallet adding the one it holds, and sweeps the token to `to`. */
  def unwrap(v: ZcWrapVault, input: InputUTXO, to: Contract): ZcSpend =
    ZcSpend(Seq(input), Seq.empty, burn = Seq(token(v.receiptId, 3L)), sweep = Some(to))
}
