package transactions.batching.arb

import node.model.{NodeBox, NodeTransaction}
import node.rest.NodeCodecs
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle

import scala.util.Try

/**
 * What the DEX batchers' admitted bundles leave for the arb stage: every box they spend, which a cycle must
 * never spend, and every box they create that none of them spends, which a cycle may build on as a pool's
 * latest state. `txIds` are the transactions those bundles carry, which a cycle building on one of their
 * outputs names as its package parent.
 */
final case class ArbBasis(spent: Set[String], created: Vector[ArbBasis.Created], txIds: Set[String])

object ArbBasis {

  /** A box a basis transaction creates and no basis transaction spends. */
  final case class Created(txId: String, box: NodeBox)

  val Empty: ArbBasis = ArbBasis(Set.empty, Vector.empty, Set.empty)

  /**
   * The basis `bundles` leave. A transaction carried by two bundles counts once. One whose body does not
   * decode, or decodes to another id, still counts its inputs as spent, but none of its outputs is offered.
   */
  def of(bundles: Seq[CandidateBundle]): ArbBasis = {
    val members = bundles.iterator.flatMap(_.members).foldLeft(Vector.empty[CandidateTx] -> Set.empty[String]) {
      case ((kept, seen), tx) => if (seen.contains(tx.id)) kept -> seen else (kept :+ tx) -> (seen + tx.id)
    }._1
    val spent = members.iterator.flatMap(_.inputIds).toSet
    val created = members.flatMap(tx => decoded(tx).toSeq.flatMap(_.outputs.map(Created(tx.id, _))))
      .filterNot(c => c.box.boxId.isEmpty || spent.contains(c.box.boxId))
    ArbBasis(spent, created, members.iterator.map(_.id).toSet)
  }

  private def decoded(tx: CandidateTx): Option[NodeTransaction] =
    Try(NodeCodecs.transaction(new com.google.gson.JsonParser().parse(tx.json).getAsJsonObject)).toOption
      .filter(_.id == tx.id)
}
