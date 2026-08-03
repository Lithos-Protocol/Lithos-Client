package node

sealed abstract class NodeError(message: String, cause: Throwable) extends Exception(message, cause)

object NodeError {

  case class Http(status: Int, reason: String, detail: Option[String])
    extends NodeError(s"node returned $status ($reason)" + detail.map(": " + _).getOrElse(""), null)

  case class Unauthorized(endpoint: String)
    extends NodeError(s"$endpoint requires a valid api_key", null)

  case class WalletLocked(endpoint: String)
    extends NodeError(s"$endpoint requires an unlocked wallet", null)

  case class IndexerDisabled(endpoint: String)
    extends NodeError(s"$endpoint requires a node started with extraIndex = true", null)

  case class NotFound(what: String, id: String)
    extends NodeError(s"$what $id not found", null)

  case class Transport(endpoint: String, cause: Throwable)
    extends NodeError(s"transport failure calling $endpoint: ${cause.getMessage}", cause)

  case class Decoding(endpoint: String, cause: Throwable)
    extends NodeError(s"could not decode response from $endpoint: ${cause.getMessage}", cause)

  case class Rejected(reason: String)
    extends NodeError(s"node rejected the request: $reason", null)
}
