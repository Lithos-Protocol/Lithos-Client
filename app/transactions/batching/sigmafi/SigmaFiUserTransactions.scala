package transactions.batching.sigmafi

import mutations.NodeWallet
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters, UnsignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.bouncycastle.util.encoders.Hex
import play.api.libs.json.{JsObject, Json}
import sigma.Colls
import sigmafi.contracts.{FixedMaturity, OnClose, SigmaFiContracts, ZCOrderTerms}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

/**
 * What a user transaction needs from the wallet, and how to build it once the wallet has supplied that.
 *
 * @param value  nanoERG the wallet inputs must cover, the fee and change headroom included
 * @param tokens tokens they must cover
 */
final case class SFPlan(value: Long, tokens: Seq[Token], build: Seq[InputUTXO] => UnsignedTransaction,
                        avoidTokens: Set[String] = Set.empty)

/**
 * The SigmaFi V2 transactions a user asks for, shaped as SigmaFi's own client shapes them: protocol inputs at
 * the positions their contracts read, wallet boxes after them, change to this wallet. Each pays the broadcast
 * fee, since none of these goes into a block of this miner's own.
 *
 * {{{
 *   create order         IN  wallet...                OUT order, [change], fee
 *   fill order           IN  order, wallet...         OUT lender unit, bond, principal, [change], fee
 *   cancel               IN  order, auth box          OUT refund, fee
 *   repay                IN  bond, wallet...          OUT vault, collateral back, [change], fee (singleton burned)
 *   claim                IN  vault..., wallet...      OUT one per vault box, [change], fee   (bond asset burned)
 * }}}
 */
object SigmaFiUserTransactions {

  final val TxFee: Long = Parameters.MinFee

  /** Asked of the wallet beyond the exact need, so change carrying leftover tokens clears the minimum. */
  private final val Headroom: Long = UTXO.MIN_CHANGE

  private final val MinBox: Long = SigmaFiContracts.MinBox
  private final val DefaultReserve: Long = configs.SigmaFiBondsConfig.Default.batchingFeeNanoErg

  /** The EIP-4 name every bond asset this client mints carries. A name proves nothing; the chain does. */
  def bondAssetName(asset: ErgoId): String = s"SigmaFi-ZC-Bond-${asset.toString.take(6)}"

  /**
   * EIP-4 display snapshot, matching SigmaFi's generated metadata. Amounts remain exact integer JSON values.
   * The height is the build context, not an inclusion height. Verification never trusts this description.
   */
  def bondAssetDescription(order: SigmaFiOrder, repayToken: Option[ErgoId], maturity: Long, height: Int,
                           batchingFee: Long = DefaultReserve): String = {
    def contents(value: Long, ts: Seq[(ErgoId, Long)]): JsObject = Json.obj(
      "nanoErgs" -> value, "tokens" -> ts.map { case (id, n) => Json.obj("id" -> id.toString, "amount" -> n) })
    val t = order.terms
    val description = Json.obj(
      "type" -> "SigmaFi ZC-Bond",
      "bondAsset" -> order.boxId,
      "borrowerHash" -> Hex.toHexString(t.borrowerHash),
      "principal" -> contents(t.principalErg, t.principalTokens),
      "repayment" -> t.repayment, "repayToken" -> repayToken.fold("ERG")(_.toString),
      "maturity" -> maturity,
      "collateral" -> contents(order.box.value, order.box.assets.map(a => ErgoId.create(a.tokenId) -> a.amount)),
      "batchingFee" -> batchingFee,
      "liquidation" -> order.liquidation.fold("unknown")(_.name),
      "liquidationHash" -> Hex.toHexString(t.liqScriptHash), "height" -> height)
    val duration = t.schedule match {
      case OnClose(d) => Json.obj("durationBlocks" -> d)
      case FixedMaturity(_) => Json.obj()
    }
    Json.stringify(description ++ duration)
  }

  /** An order of this wallet's: the collateral locked under the order, R4 hinting this wallet's tree. */
  def createOrder(ctx: BlockchainContext, wallet: NodeWallet, terms: ZCOrderTerms, collateralErg: Long,
                  collateralTokens: Seq[Token]): SFPlan = {
    val order = UTXO(SigmaFiContracts.order(terms), collateralErg, collateralTokens,
      Seq(SigmaFiBoxes.hintValue(wallet.contract.ergoTree)))
    SFPlan(Math.addExact(collateralErg, TxFee + Headroom), collateralTokens, funding =>
      TxBuilder(ctx).setInputs(funding: _*).setOutputs(order).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Takes an order back, authorized by `auth`, a wallet box at the creator's own tree. Everything both
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

  /** Rich issuance metadata can exceed the size covered by 0.001 ERG; fund the node's actual minimum. */
  def lenderUnit(ctx: BlockchainContext, wallet: NodeWallet, order: SigmaFiOrder,
                 repayToken: Option[ErgoId], maturity: Long, batchingFee: Long = DefaultReserve): UTXO = {
    val asset = ErgoId.create(order.boxId)
    val description = bondAssetDescription(order, repayToken, maturity, ctx.getHeight, batchingFee)
    var unit = unitBox(wallet.contract, asset, bondAssetName(asset).getBytes("UTF-8"), description.getBytes("UTF-8"))
    val perByte = ctx.getDataSource.getParameters.getMinValuePerByte.toLong
    def minimum: Long = Math.multiplyExact(unit.toInput(ctx, asset, 0).bytes.length.toLong, perByte)
    // Increasing the value can itself increase its serialized length, so check again after each increase.
    var needed = minimum
    while (unit.value < needed) {
      unit = unit.setValue(needed)
      needed = minimum
    }
    unit
  }

  private def bondBox(repayToken: Option[ErgoId], value: Long, collateral: Seq[Token], asset: ErgoId,
                      borrowerHash: Array[Byte], repayment: Long, fee: Long, maturity: Long,
                      liqHash: Array[Byte], principalErg: Long, principalTokens: Seq[(ErgoId, Long)]): UTXO =
    UTXO(SigmaFiContracts.bond(repayToken), value, Token(asset, 1L) +: collateral, Seq(bytes(asset.getBytes), bytes(borrowerHash),
      ErgoValue.of(Array(repayment, fee, principalErg, maturity)), principalValue(principalTokens), bytes(liqHash)))

  /**
   * The maturity a fill of `order` stamps, built at the tip of `ctx`. An on-close bond matures its duration after
   * the tip, which the contract accepts while the fill is mined within 8 blocks.
   */
  def fillMaturity(ctx: BlockchainContext, order: SigmaFiOrder): Long = order.terms.schedule match {
    case FixedMaturity(h) => h
    case OnClose(d) => Math.addExact(ctx.getHeight.toLong, d)
  }

  /**
   * Fills `order`, this wallet lending. The order is input 0, so the fill mints the bond asset, whose id is the
   * order's: one unit to this wallet at output 0, the bond at output 1, the principal to `borrower` at output 2.
   */
  def fillOrder(ctx: BlockchainContext, wallet: NodeWallet, order: SigmaFiOrder, orderBox: InputUTXO,
                repayToken: Option[ErgoId], borrower: Contract, batchingFee: Long = DefaultReserve): SFPlan = {
    require(batchingFee >= 0, "batching reserve cannot be negative")
    val t = order.terms
    val asset = orderBox.id
    val maturity = fillMaturity(ctx, order)
    val unit = lenderUnit(ctx, wallet, order, repayToken, maturity, batchingFee)
    val bond = bondBox(repayToken, Math.addExact(orderBox.value, batchingFee), orderBox.tokens, asset, t.borrowerHash, t.repayment,
      batchingFee, maturity, t.liqScriptHash, t.principalErg, t.principalTokens)
    val principalTokens = t.principalTokens.map { case (id, n) => Token(id, n) }
    val principal = UTXO(borrower, math.max(t.principalErg, MinBox), principalTokens)
    SFPlan(Math.addExact(batchingFee, Math.addExact(principal.value, Math.addExact(unit.value, TxFee + Headroom))), principalTokens, funding =>
      TxBuilder(ctx).setInputs(orderBox.withCtxVar(0.toByte, ErgoValue.of(batchingFee)) +: funding: _*).setOutputs(unit, bond, principal).buildTx(TxFee, wallet.p2pk))
  }

  /**
   * Repays `bond`: the repayment into the vault at output 0, tagged with the bond and its asset, and the
   * collateral back to `borrower` at output 1, tagged with the bond. Anyone may repay.
   */
  def repay(ctx: BlockchainContext, wallet: NodeWallet, bond: SigmaFiBond, bondBox: InputUTXO,
            borrower: Contract): SFPlan = {
    val tags = Seq(bytes(ErgoId.create(bond.boxId).getBytes), bytes(bond.asset.getBytes), ErgoValue.of(0.toByte))
    val (settlement, tokens) = bond.repayToken match {
      case None => UTXO(SigmaFiContracts.vault, Math.addExact(math.max(bond.repayment, MinBox), bond.batchingFee), Seq.empty, tags) -> Seq.empty[Token]
      case Some(token) =>
        val owed = Token(token, bond.repayment)
        UTXO(SigmaFiContracts.vault, Math.addExact(MinBox, bond.batchingFee), Seq(owed), tags) -> Seq(owed)
    }
    val back = UTXO(borrower, bond.collateralErg, bondBox.tokens.drop(1), Seq(bytes(ErgoId.create(bond.boxId).getBytes)))
    SFPlan(Math.addExact(settlement.value - bond.batchingFee, TxFee + Headroom), tokens, funding => {
      require(!funding.exists(_.tokens.exists(_.id == bond.asset)), "repayment funding must preserve the lender's circulating unit")
      TxBuilder(ctx).setInputs(bondBox.withCtxVar(1.toByte, ErgoValue.of(0)).withCtxVar(2.toByte, ErgoValue.of(1)) +: funding: _*).setOutputs(settlement, back)
        .buildTx(TxFee, wallet.p2pk, Seq(Token(bond.asset, 1L)))
    }, avoidTokens = Set(bond.asset.toString))
  }

  /**
   * Claims `vaults` with the unit this wallet holds, which is burned, so a vault box for `asset` not among them can
   * never be claimed after. Each box's contents come back in an output of their own, smaller than the vault box, so a
   * stranger's box cannot oversize the claim.
   */
  def claim(ctx: BlockchainContext, wallet: NodeWallet, asset: ErgoId, vaults: Seq[InputUTXO]): SFPlan = {
    require(vaults.nonEmpty, s"the vault holds nothing for bond asset $asset")
    val unit = Token(asset, 1L)
    val claimed = vaults.map(v => UTXO(wallet.contract, v.value, v.tokens))
    SFPlan(TxFee + Headroom, Seq(unit), funding =>
      TxBuilder(ctx).setInputs(vaults ++ funding: _*).setOutputs(claimed: _*).buildTx(TxFee, wallet.p2pk, Seq(unit)))
  }

  def principalValue(tokens: Seq[(ErgoId, Long)]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(tokens.map { case (id, n) => Colls.fromArray(id.getBytes) -> n }.toArray)(
      sigma.data.RType.pairRType(sigma.collRType(sigma.ByteType), sigma.LongType)),
      org.ergoplatform.appkit.ErgoType.pairType(org.ergoplatform.appkit.ErgoType.collType(scalaByteType),
        scalaLongType))

  private def bytes(b: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(b), scalaByteType)
}
