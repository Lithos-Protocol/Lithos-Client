package api.models

import play.api.libs.json._

/** A token and an amount in its base units, as a string so a large one keeps every digit. */
case class SFTokenAmount(tokenId: String, amount: String)

/**
 * A bond's terms, as an order states them and the bond then carries.
 *
 * @param kind              the bond's kind: "zero-coupon" for every SigmaFi V2 bond today
 * @param principalNanoErgs agreed nanoERG lent; for a token loan, the principal box's floor, read directly from the bond
 * @param principalToken    the token lent, absent for an ERG loan
 * @param repayment         what the borrower pays back: nanoERG, or base units of `repayToken`
 * @param repayToken        the token a token loan is repaid in, absent when repaid in ERG
 * @param maturityHeight    repayment is open strictly below it; absent for an on-close order until it is filled
 * @param durationBlocks    an on-close order's term, counted from its fill
 * @param batchingFee       lender-funded reserve; zero on an unfilled order, selected on fill
 * @param liquidation       the liquidation script's name, absent when this client does not know it
 */
case class SFTerms(kind: String, principalNanoErgs: Option[String], principalToken: Option[SFTokenAmount], repayment: String,
                   repayToken: Option[String], collateralNanoErgs: String, collateralTokens: Seq[SFTokenAmount],
                   maturityHeight: Option[Long], durationBlocks: Option[Long], batchingFee: String,
                   liquidation: Option[String], liquidationHash: String)

/**
 * A borrower's order waiting for a lender.
 *
 * @param borrower address the principal goes to, when this client can tell
 * @param mine     whether this wallet made it, so it can cancel it
 * @param refusal  why this client would not fill it, absent when it would
 */
case class SFOrderView(boxId: String, terms: SFTerms, borrower: Option[String], mine: Boolean,
                       refusal: Option[String], creationHeight: Int)

/**
 * A live bond.
 *
 * @param assetId      the bond asset whose holder is paid the repayment or the collateral
 * @param verified     whether this bond is the one its asset's genesis order created
 * @param verification why it is not verified, when it is not
 * @param status       "open" while it can be repaid, "matured" once it can only be liquidated
 * @param blocksLeft   blocks before repayment closes, 0 once matured
 */
case class SFBondView(boxId: String, assetId: String, terms: SFTerms, verified: Boolean, verification: Option[String],
                      status: String, blocksLeft: Long, borrowerIsMe: Boolean, creationHeight: Int)

case class SFOrderBook(height: Int, orders: Seq[SFOrderView], total: Int, truncated: Boolean)
case class SFBondList(height: Int, bonds: Seq[SFBondView], total: Int, unverified: Int, truncated: Boolean)

/** A vault box: one settled bond's repayment or collateral, claimed by the holder of `assetId`. */
case class SFVaultView(boxId: String, bondId: Option[String], assetId: String, nanoErgs: String,
                       tokens: Seq[SFTokenAmount])

/**
 * A bond asset this wallet holds: the bond it claims while that is live, and what the vault holds for it once
 * the bond is repaid or liquidated.
 *
 * @param claimable whether a claim goes through now: the bond's own settlement output is known and unspent
 */
case class SFHolding(assetId: String, bond: Option[SFBondView], vault: Seq[SFVaultView], claimable: Boolean)

/**
 * This wallet in SigmaFi.
 *
 * @param borrowing bonds whose collateral returns to this wallet on repayment
 * @param lending   bond assets this wallet holds
 */
case class SFPortfolio(height: Int, orders: Seq[SFOrderView], borrowing: Seq[SFBondView],
                       lending: Seq[SFHolding], truncated: Boolean)

/** Token ids to look up as bond assets. */
case class SFAssetLookupRequest(tokenIds: Seq[String])

/**
 * One token as a bond asset, read from the chain, never from its name or description.
 *
 * @param bondAsset whether a SigmaFi order minted it
 * @param bond      the live bond naming it, absent once settled
 * @param vault     what the vault holds for it
 */
case class SFAssetLookup(tokenId: String, bondAsset: Boolean, verified: Boolean, verification: Option[String],
                         bond: Option[SFBondView], vault: Seq[SFVaultView], status: String = "Unknown",
                         settlement: Option[SFSettlement] = None, statusReason: Option[String] = None)

/** Confirmed settlement evidence persists after the lender claims the vault output. */
case class SFSettlement(txId: String, height: Int, timestamp: Option[Long], bondBoxId: String, vault: SFVaultView,
                         returnedCollateral: Option[SFBoxValue], liquidationHash: Option[String])
case class SFBoxValue(boxId: String, nanoErgs: String, tokens: Seq[SFTokenAmount])

/** A new order: collateral locked now, for a loan a lender fills later. Exactly one of the maturity fields. */
case class SFFillRequest(batchingFee: Option[String])

case class SFOrderRequest(collateralNanoErgs: String, collateralTokens: Option[Seq[SFTokenAmount]],
                          principalNanoErgs: Option[String], principalToken: Option[SFTokenAmount], repayment: String,
                          maturityHeight: Option[Long], durationBlocks: Option[Long], batchingFee: Option[String],
                          liquidation: Option[String])

/**
 * What an action would do, signing nothing.
 *
 * @param pays     what leaves this wallet, the network fee included
 * @param receives what comes back to this wallet
 * @param notes    what the transaction does, in order
 * @param warnings what is worth reading before sending it
 */
case class SFQuote(action: String, boxId: Option[String], terms: Option[SFTerms], paysNanoErgs: String,
                   paysTokens: Seq[SFTokenAmount], receivesNanoErgs: String, receivesTokens: Seq[SFTokenAmount],
                   feeNanoErgs: String, notes: Seq[String], warnings: Seq[String])

/**
 * A sent action.
 *
 * @param boxId    the output that matters: the new order or bond, the vault box a repayment or liquidation pays, or
 *                 the first box a cancel or claim returns to this wallet
 * @param assetId  the bond asset a fill minted
 * @param warnings what the sent transaction left undone, such as vault boxes a claim left locked for good
 */
case class SFResult(txId: String, outcome: String, boxId: Option[String], assetId: Option[String],
                    warnings: Seq[String] = Seq.empty)

object SigmaFi {
  implicit lazy val sfTokenAmountFormat: Format[SFTokenAmount] = Json.format[SFTokenAmount]
  implicit lazy val sfTermsFormat: Format[SFTerms] = Json.format[SFTerms]
  implicit lazy val sfOrderViewFormat: Format[SFOrderView] = Json.format[SFOrderView]
  implicit lazy val sfBondViewFormat: Format[SFBondView] = Json.format[SFBondView]
  implicit lazy val sfOrderBookFormat: Format[SFOrderBook] = Json.format[SFOrderBook]
  implicit lazy val sfBondListFormat: Format[SFBondList] = Json.format[SFBondList]
  implicit lazy val sfVaultViewFormat: Format[SFVaultView] = Json.format[SFVaultView]
  implicit lazy val sfHoldingFormat: Format[SFHolding] = Json.format[SFHolding]
  implicit lazy val sfPortfolioFormat: Format[SFPortfolio] = Json.format[SFPortfolio]
  implicit lazy val sfAssetLookupRequestFormat: Format[SFAssetLookupRequest] = Json.format[SFAssetLookupRequest]
  implicit lazy val sfBoxValueFormat: Format[SFBoxValue] = Json.format[SFBoxValue]
  implicit lazy val sfSettlementFormat: Format[SFSettlement] = Json.format[SFSettlement]
  implicit lazy val sfAssetLookupFormat: Format[SFAssetLookup] = Json.format[SFAssetLookup]
  implicit lazy val sfFillRequestFormat: Format[SFFillRequest] = Json.format[SFFillRequest]
  implicit lazy val sfOrderRequestFormat: Format[SFOrderRequest] = Json.format[SFOrderRequest]
  implicit lazy val sfQuoteFormat: Format[SFQuote] = Json.format[SFQuote]
  implicit lazy val sfResultFormat: Format[SFResult] = Json.format[SFResult]
}
