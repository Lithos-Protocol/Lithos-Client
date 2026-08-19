package api

/**
 * The failures the LithosDex endpoints classify for themselves.
 *
 * They exist so a request fails early and legibly instead of building a transaction the contract
 * would reject — the node's rejection names a script position, which tells a UI nothing it can show
 * a user. Anything not named here is a defect in this client and stays a 500.
 */
object LDErrors {

  /**
   * 400. The request is well-formed but the chain state makes it meaningless: nothing pending to
   * flush, nothing owed to settle, a resize to zero.
   */
  case class LDBadRequest(reason: String) extends RuntimeException(reason)

  /**
   * 422. The request is valid and the pool simply cannot do it — asking for more output than a reserve
   * can give, which the curve only approaches and never reaches, or a bound the current state cannot
   * satisfy.
   */
  case class LDUnprocessable(reason: String) extends RuntimeException(reason)

  /**
   * 409. A singleton this request was quoted against has moved.
   *
   * Carries the ids as they now stand so a caller can re-quote against them rather than guess. Raised
   * both when a caller supplied an `expected...BoxId` that no longer matches and when a mutation
   * cannot take the DEX lock in time, since a request that waited out another mutation was quoted
   * against a box that mutation has since spent.
   */
  case class LDStateChanged(reason: String,
                            poolBoxId: Option[String] = None,
                            vaultBoxId: Option[String] = None,
                            provisionBoxId: Option[String] = None) extends RuntimeException(reason)

  /**
   * 503. The node, its index or the wallet could not answer, so the request is not decidable right
   * now. Distinct from 500: nothing about the request or this client is wrong.
   */
  case class LDUnavailable(reason: String, cause: Throwable = null)
    extends RuntimeException(reason, cause)

  /**
   * 404. A box this request named is not on chain.
   *
   * A type of its own rather than `NoSuchElementException`, which is what the controller used to
   * match on. That caught every accidental `.get` on a `None` and every `.head` on an empty
   * collection anywhere under the API and reported this client's own defects as "not found" — the
   * one status that tells a caller to stop trying.
   */
  case class LDNotFound(reason: String) extends RuntimeException(reason)
}
