package node.model

sealed trait SortDirection {
  def value: String
}

object SortDirection {
  case object Asc extends SortDirection {
    val value = "asc"
  }

  case object Desc extends SortDirection {
    val value = "desc"
  }

  def fromString(s: String): Option[SortDirection] = s.toLowerCase match {
    case "asc"  => Some(Asc)
    case "desc" => Some(Desc)
    case _      => None
  }
}

case class Paging(offset: Int = 0, limit: Int = 100) {
  require(offset >= 0, "offset cannot be negative")
  require(limit > 0, "limit must be positive")

  def next: Paging = copy(offset = offset + limit)
}

object Paging {
  val Default: Paging = Paging()

  def all(limit: Int): Paging = Paging(0, limit)
}

case class MempoolOptions(includeUnconfirmed: Boolean = false,
                          excludeMempoolSpent: Boolean = false)

object MempoolOptions {
  val ConfirmedOnly: MempoolOptions = MempoolOptions()
  val WithMempool: MempoolOptions   = MempoolOptions(includeUnconfirmed = true, excludeMempoolSpent = true)
}

case class ConfirmationRange(minConfirmations: Int = 0,
                             maxConfirmations: Int = -1,
                             minInclusionHeight: Int = 0,
                             maxInclusionHeight: Int = -1)

object ConfirmationRange {
  val Default: ConfirmationRange      = ConfirmationRange()
  val IncludeMempool: ConfirmationRange = ConfirmationRange(minConfirmations = -1)
}

case class Paged[A](items: Seq[A], total: Int) {
  def map[B](f: A => B): Paged[B] = Paged(items.map(f), total)

  def isEmpty: Boolean = items.isEmpty

  def nonEmpty: Boolean = items.nonEmpty
}

case class NodeAsset(tokenId: String, amount: Long)

case class NodeRegisters(values: Map[String, String]) {
  def get(index: Int): Option[String] = values.get("R" + index)

  def ordered: Seq[String] = {
    val present = (4 to 9).map(i => values.get("R" + i))
    present.takeWhile(_.isDefined).flatten
  }

  def isEmpty: Boolean = values.isEmpty
}

object NodeRegisters {
  val empty: NodeRegisters = NodeRegisters(Map.empty[String, String])

  def fromOrdered(ordered: Seq[String]): NodeRegisters =
    NodeRegisters(ordered.zipWithIndex.map { case (hex, i) => ("R" + (i + 4)) -> hex }.toMap)
}
