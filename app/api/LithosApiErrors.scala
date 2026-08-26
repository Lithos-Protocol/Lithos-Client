package api

/**
 * The failures this client's endpoints classify for themselves, and the status each carries.
 *
 * They exist so a request fails early and legibly instead of building a transaction the contract
 * would reject — the node's rejection names a script position, which tells a UI nothing it can show
 * a user. Anything not named here is a defect in this client and stays a 500.
 *
 */
object LithosApiErrors {

  /**
   * 400. The request is well-formed but the chain state makes it meaningless: nothing pending to
   * flush, nothing owed to settle, a resize to zero.
   */
  case class LithosBadRequest(reason: String) extends RuntimeException(reason)

  /**
   * 422. The request is valid and the pool simply cannot do it — asking for more output than a reserve
   * can give, which the curve only approaches and never reaches, or a bound the current state cannot
   * satisfy.
   */
  case class LithosUnprocessable(reason: String) extends RuntimeException(reason)

  /**
   * 409. Something this request was quoted against is no longer what it was.
   *
   * Raised when a caller supplied an `expected...BoxId` that no longer matches; when a DEX mutation
   * cannot take its lock in time, since a request that waited out another mutation was quoted
   * against a box that mutation has since spent; and when a collateral join is refused because the
   * client's own automated pass owns lender-key selection.
   *
   * The three box ids are the DEX's, carried so a caller can re-quote against them rather than
   * guess. They are absent everywhere else, and only the DEX controller renders them — the other
   * controllers answer 409 with an ordinary `ApiError`.
   */
  case class LithosStateChanged(reason: String,
                                poolBoxId: Option[String] = None,
                                vaultBoxId: Option[String] = None,
                                provisionBoxId: Option[String] = None) extends RuntimeException(reason)

  /**
   * 503. The node, its index or the wallet could not answer, so the request is not decidable right
   * now. Distinct from 500: nothing about the request or this client is wrong.
   */
  case class LithosUnavailable(reason: String, cause: Throwable = null)
    extends RuntimeException(reason, cause)

  /**
   * 404. A box this request named is not on chain.
   *
   * A type of its own rather than `NoSuchElementException`, which is what the controller used to
   * match on. That caught every accidental `.get` on a `None` and every `.head` on an empty
   * collection anywhere under the API and reported this client's own defects as "not found" — the
   * one status that tells a caller to stop trying.
   */
  case class LithosNotFound(reason: String) extends RuntimeException(reason)
}
