package transactions.batching.sigmafi

import mutations.NodeWallet
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigmafi.contracts.{FixedMaturity, OnClose, SigmaFiContracts, ZCOfferTerms, ZCOrderTerms}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

/**
 * What a user transaction needs from the wallet, and how to build it once the wallet has supplied that.
 *
 * @param value  nanoERG the wallet inputs must cover, the fee and change headroom included
 * @param tokens tokens they must cover
 */
final case class SFPlan(value: Long, tokens: Seq[Token], build: Seq[InputUTXO] => UnsignedTransaction)

/**
 * The SigmaFi V2 transactions a user asks for, shaped as SigmaFi's own client shapes them: protocol inputs at
 * the positions their contracts read, wallet boxes after them, change to this wallet. Each pays the broadcast
 * fee, since none of these goes into a block of this miner's own.
 *
 * {{{
 *   create order/offer   IN  wallet...                OUT order|offer, [change], fee
 *   fill order           IN  order, wallet...         OUT lender unit, bond, principal, [change], fee
 *   close offer          IN  offer, wallet...         OUT lender unit, bond, principal, [change], fee
 *   cancel               IN  order|offer, auth box    OUT refund, fee
 *   repay                IN  bond, wallet...          OUT vault, collateral back, [change], fee
 *   claim                IN  vault..., wallet...      OUT claimed, [change], fee        (bond asset burned)
 * }}}
 */
object SigmaFiUserTransactions {

  final val TxFee: Long = Parameters.MinFee

  /** Asked of the wallet beyond the exact need, so change carrying leftover tokens clears the minimum. */
  private final val Headroom: Long = UTXO.MIN_CHANGE

  private final val MinBox: Long = SigmaFiContracts.MinBox

  /** The EIP-4 name every bond asset this client mints carries. A name proves nothing; the chain does. */
  final val BondAssetName: String = "SigmaFi ZC-Bond"

  /**
   * The bond asset's EIP-4 description: its terms as compact JSON, so a wallet can show them. Every value is a
   * hex id, a number or a fixed word, so nothing needs escaping. Never trusted on read: terms come from the chain.
   */
  def bondAssetDescription(principalErg: Long, principalTokens: Seq[(ErgoId, Long)], repayment: Long,
                           repayToken: Option[ErgoId], maturity: Either[Int, Int], collateralErg: Long,
                           collateralTokens: Seq[(ErgoId, Long)], batchingFee: Long, liquidation: String): String = {
    def tokens(ts: Seq[(ErgoId, Long)]) = ts.map { case (id, n) => s"""{"id":"$id","amount":$n}""" }.mkString("[", ",", "]")
    Seq(
      s""""type":"SigmaFi ZC-Bond"""",
      s""""principal":{"nanoErgs":$principalErg,"tokens":${tokens(principalTokens)}}""",
      s""""repayment":$repayment""",
      s""""repayToken":"${repayToken.fold("ERG")(_.toString)}"""",
      maturity.fold(h => s""""maturity":$h""", d => s""""durationBlocks":$d"""),
      s""""collateral":{"nanoErgs":$collateralErg,"tokens":${tokens(collateralTokens)}}""",
      s""""batchingFee":$batchingFee""",
      s""""liquidation":"$liquidation""""
    ).mkString("{", ",", "}")
  }

  /** An order of this wallet's: the collateral locked under the order, R4 hinting this wallet's tree. */
  def createOrder(ctx: BlockchainContext, wallet: NodeWallet, terms: ZCOrderTerms, collateralErg: Long,
                  collateralTokens: Seq[Token]): SFPlan = {
    val order = UTXO(SigmaFiContracts.order(terms), collateralErg, collateralTokens,
      Seq(SigmaFiBoxes.hintValue(wallet.contract.ergoTree)))
    SFPlan(Math.addExact(collateralErg, TxFee + Headroom), collateralTokens, funding =>
      TxBuilder(ctx).setInputs(funding: _*).setOutputs(order).buildTx(TxFee, wallet.p2pk))
  }

  /** An offer of this wallet's: the principal plus the lender unit's box, R4 hinting this wallet's tree. */
  def createOffer(ctx: BlockchainContext, wallet: NodeWallet, terms: ZCOfferTerms, principalErg: Long,
                  principalTokens: Seq[Token]): SFPlan = {
    val offer = UTXO(SigmaFiContracts.offer(terms), Math.addExact(principalErg, MinBox), principalTokens,
      Seq(SigmaFiBoxes.hintValue(wallet.contract.ergoTree)))
    SFPlan(Math.addExact(offer.value, TxFee + Headroom), principalTokens, funding =>
      TxBuilder(ctx).setInputs(funding: _*).setOutputs(offer).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Takes an order or offer back, authorized by `auth`, a wallet box at the creator's own tree. Everything both
   * hold, less the fee, returns to `refundTo` in one box.
   */
  def cancel(ctx: BlockchainContext, wallet: NodeWallet, box: InputUTXO, auth: InputUTXO,
             refundTo: Contract): UnsignedTransaction = {
    val tokens = (box.tokens ++ auth.tokens).groupBy(_.id).map { case (id, ts) => Token(id, ts.map(_.amount).sum) }.toSeq
    TxBuilder(ctx).setInputs(box, auth)
      .setOutputs(UTXO(refundTo, Math.addExact(box.value, auth.value) - TxFee, tokens)).buildTx(TxFee, wallet.p2pk)
  }

  /** The bond asset unit: one token, carrying its EIP-4 name, description and "0" decimals in R4 to R6. */
  private def unitBox(to: Contract, asset: ErgoId, name: Array[Byte], description: Array[Byte]): UTXO =
    UTXO(to, MinBox, Seq(Token(asset, 1L)), Seq(bytes(name), bytes(description), bytes("0".getBytes("UTF-8"))))

  private def bondBox(repayToken: Option[ErgoId], value: Long, collateral: Seq[Token], asset: ErgoId,
                      borrowerHash: Array[Byte], repayment: Long, fee: Long, maturity: Int,
                      liqHash: Array[Byte]): UTXO =
    UTXO(SigmaFiContracts.bond(repayToken), value, collateral, Seq(bytes(asset.getBytes), bytes(borrowerHash),
      ErgoValue.of(Array(repayment, fee)), ErgoValue.of(maturity), bytes(liqHash)))

  /**
   * The maturity a fill of `order` stamps, built at the tip of `ctx`. An on-close bond matures its duration after
   * the tip, which the contract accepts while the fill is mined within 8 blocks.
   */
  def fillMaturity(ctx: BlockchainContext, order: SigmaFiOrder): Int = order.terms.schedule match {
    case FixedMaturity(h) => h
    case OnClose(d) => Math.addExact(ctx.getHeight, d)
  }

  /**
   * Fills `order`, this wallet lending. The order is input 0, so the fill mints the bond asset, whose id is the
   * order's: one unit to this wallet at output 0, the bond at output 1, the principal to `borrower` at output 2.
   */
  def fillOrder(ctx: BlockchainContext, wallet: NodeWallet, order: SigmaFiOrder, orderBox: InputUTXO,
                repayToken: Option[ErgoId], borrower: Contract): SFPlan = {
    val t = order.terms
    val asset = orderBox.id
    val maturity = fillMaturity(ctx, order)
    val description = bondAssetDescription(t.principalErg, t.principalTokens, t.repayment, repayToken,
      Left(maturity), orderBox.value, orderBox.tokens.map(tk => tk.id -> tk.amount), t.batchingFee,
      order.liquidation.fold("unknown")(_.name))
    val unit = unitBox(wallet.contract, asset, BondAssetName.getBytes("UTF-8"), description.getBytes("UTF-8"))
    val bond = bondBox(repayToken, orderBox.value, orderBox.tokens, asset, t.borrowerHash, t.repayment,
      t.batchingFee, maturity, t.liqScriptHash)
    val principalTokens = t.principalTokens.map { case (id, n) => Token(id, n) }
    val principal = UTXO(borrower, math.max(t.principalErg, MinBox), principalTokens)
    SFPlan(MinBox + principal.value + TxFee + Headroom, principalTokens, funding =>
      TxBuilder(ctx).setInputs(orderBox +: funding: _*).setOutputs(unit, bond, principal).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Closes `offer`, this wallet borrowing. The offer is input 0, so the close mints the bond asset, whose id is
   * the offer's: one unit to `lender` at output 0 under the offer's name and description, the bond holding this
   * wallet's collateral at output 1, the principal to this wallet at output 2.
   */
  def closeOffer(ctx: BlockchainContext, wallet: NodeWallet, offer: SigmaFiOffer, offerBox: InputUTXO,
                 repayToken: Option[ErgoId], lender: Contract): SFPlan = {
    val t = offer.terms
    val asset = offerBox.id
    val collateral = t.collateralTokens.map { case (id, n) => Token(id, n) }
    val unit = unitBox(lender, asset, t.assetName, t.assetDescription)
    val bond = bondBox(repayToken, t.collateralErg, collateral, asset, wallet.contract.hashedPropBytes, t.repayment,
      t.batchingFee, t.maturity, t.liqScriptHash)
    val principal = UTXO(wallet.contract, offerBox.value - MinBox, offerBox.tokens)
    SFPlan(Math.addExact(t.collateralErg, TxFee + Headroom), collateral, funding =>
      TxBuilder(ctx).setInputs(offerBox +: funding: _*).setOutputs(unit, bond, principal).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Repays `bond`: the repayment into the vault at output 0, tagged with the bond and its asset, and the
   * collateral back to `borrower` at output 1, tagged with the bond. Anyone may repay.
   */
  def repay(ctx: BlockchainContext, wallet: NodeWallet, bond: SigmaFiBond, bondBox: InputUTXO,
            borrower: Contract): SFPlan = {
    val tags = Seq(bytes(ErgoId.create(bond.boxId).getBytes), bytes(bond.asset.getBytes))
    val (settlement, tokens) = bond.repayToken match {
      case None => UTXO(SigmaFiContracts.vault, math.max(bond.repayment, MinBox), Seq.empty, tags) -> Seq.empty[Token]
      case Some(token) =>
        val owed = Token(token, bond.repayment)
        UTXO(SigmaFiContracts.vault, MinBox, Seq(owed), tags) -> Seq(owed)
    }
    val back = UTXO(borrower, bondBox.value, bondBox.tokens, Seq(bytes(ErgoId.create(bond.boxId).getBytes)))
    SFPlan(Math.addExact(settlement.value, TxFee + Headroom), tokens, funding =>
      TxBuilder(ctx).setInputs(bondBox +: funding: _*).setOutputs(settlement, back).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Claims every vault box for `asset` with the unit this wallet holds, which is burned. All of them go in one
   * transaction: once the unit is burned, a vault box left behind can never be claimed.
   */
  def claim(ctx: BlockchainContext, wallet: NodeWallet, asset: ErgoId, vaults: Seq[InputUTXO]): SFPlan = {
    require(vaults.nonEmpty, s"the vault holds nothing for bond asset $asset")
    val unit = Token(asset, 1L)
    val tokens = vaults.flatMap(_.tokens).groupBy(_.id).map { case (id, ts) => Token(id, ts.map(_.amount).sum) }.toSeq
    val claimed = UTXO(wallet.contract, vaults.map(_.value).foldLeft(0L)(Math.addExact), tokens)
    SFPlan(TxFee + Headroom, Seq(unit), funding =>
      TxBuilder(ctx).setInputs(vaults ++ funding: _*).setOutputs(claimed).buildTx(TxFee, wallet.p2pk, Seq(unit)))
  }

  private def bytes(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
}
