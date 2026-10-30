package api.models

import play.api.libs.json._

/**
 * A new pool, holding the creator's liquidity at the price their two amounts set.
 *
 * @param tokenX    "ERG" for an ERG/token pool, or the id of a token/token pool's first token
 * @param amountX   units of X the pool starts with; nanoERG for ERG
 * @param feeParams the pool's fees; the genesis default when absent
 */
case class LDPoolCreateRequest(tokenX: String, tokenY: String, amountX: String, amountY: String,
                               feeParams: Option[LDFeeParams] = None)

object LDPoolCreateRequest {
  implicit lazy val jsonFormat: Format[LDPoolCreateRequest] = Json.format[LDPoolCreateRequest]
}

/** @param acknowledgeLocked must be true: the pool's own boxes and its locked supply can never be taken back */
case class LDPoolCreateExecuteRequest(tokenX: String, tokenY: String, amountX: String, amountY: String,
                                      feeParams: Option[LDFeeParams] = None, acknowledgeLocked: Option[Boolean] = None)

object LDPoolCreateExecuteRequest {
  implicit lazy val jsonFormat: Format[LDPoolCreateExecuteRequest] = Json.format[LDPoolCreateExecuteRequest]
}

/**
 * What creating the pool would do.
 *
 * @param shares           the creator's provision: the whole genesis supply but `lockedShares`
 * @param lockedShares     supply no provision may represent, so its share of the reserves is locked for good
 * @param lockedShare      that supply's fraction of the pool
 * @param lockedErg        nanoERG nobody can ever take back: the vault box, and a token/token pool box
 * @param lockedTokens     tokens nobody can ever take back beyond the locked share: a token/token vault's one of each
 * @param totalErgRequired nanoERG that leaves the wallet across the four transactions
 * @param tokensRequired   tokens that leave the wallet
 * @param networkFee       the four transactions' fees together
 * @param warnings         pools already trading this pair, or why none were looked for
 */
case class LDPoolCreateQuote(kind: String, tokenX: String, tokenY: String, feeParams: LDFeeParams, reserveX: String,
                             reserveY: String, shares: String, lockedShares: String, lockedShare: Double,
                             lockedErg: String, lockedTokens: Seq[LDTokenAmount], poolBoxValue: String,
                             vaultBoxValue: String, provisionBoxValue: String, totalErgRequired: String,
                             tokensRequired: Seq[LDTokenAmount], networkFee: String, warnings: Seq[String])

object LDPoolCreateQuote {
  implicit lazy val jsonFormat: Format[LDPoolCreateQuote] = Json.format[LDPoolCreateQuote]
}

/**
 * One of the four transactions.
 *
 * @param step    POOL_NFT, VAULT_NFT, OWNER_NFT or GENESIS
 * @param outcome accepted, rejected, uncertain, or unsent when an earlier one was not accepted
 */
case class LDPoolCreateStep(step: String, txId: String, outcome: String, reason: Option[String])

object LDPoolCreateStep {
  implicit lazy val jsonFormat: Format[LDPoolCreateStep] = Json.format[LDPoolCreateStep]
}

/**
 * @param outcome        accepted when all four were, else the first step's that was not
 * @param ownerNFT       the NFT in this wallet that owns the creator's provision
 * @param provisionBoxId the creator's provision, which genesis creates
 */
case class LDPoolCreated(outcome: String, kind: String, poolNFT: String, vaultNFT: String, provToken: String,
                         ownerNFT: String, provisionBoxId: String, steps: Seq[LDPoolCreateStep])

object LDPoolCreated {
  implicit lazy val jsonFormat: Format[LDPoolCreated] = Json.format[LDPoolCreated]
}
