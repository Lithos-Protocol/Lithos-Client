package mining

import mining.MiningMessages.CandidateIdentity
import node.model.{MerkleLevel, NodeMerkleProof}
import org.json.JSONObject
import transactions.BlockTxMessages.CandidateTx

import java.util.UUID

/**
 * What the node returned for one candidate request, kept with the identity it was requested under.
 *
 * The node supplies proofs for the transactions that were requested, not an inventory of the block
 * it built. It selects a remainder this client never sees, so `knownBytes` and `knownCost` describe
 * the supplied package only and no count of proofs can establish that nothing else is present.
 */
final case class CandidateMaterialized(identity: CandidateIdentity,
                                       attempt: UUID,
                                       workMessage: String,
                                       proofs: Seq[NodeMerkleProof],
                                       included: Set[String],
                                       unproven: Set[String],
                                       knownBytes: Long,
                                       knownCost: Long) {

  /** Whether every requested transaction was matched to a proof leaf. */
  def fullyProven: Boolean = unproven.isEmpty
}

object CandidateMaterialized {

  /**
   * Read the returned proofs and work out which requested transactions they account for.
   *
   * A leaf is the Blake2b256 of a transaction's serialized bytes, which is what the builders record
   * on each `CandidateTx`. A member with no recorded leaf — an unconfirmed ancestor arrives as a
   * node body, not something this client signed — can never be matched and is reported unproven
   * rather than treated as absent.
   *
   * Correspondence is computed, never enforced here. The supported node can return proofs that do
   * not correspond to the transactions it was given (ergoplatform/ergo#2463), so acting on a
   * mismatch would reject candidates that are in fact fine. [[requireCorrespondence]] is the single
   * place that changes once the node is fixed.
   */
  def apply(identity: CandidateIdentity, attempt: UUID, workMessage: String,
            proof: JSONObject, requested: Seq[CandidateTx]): CandidateMaterialized = {
    val proofs = parseProofs(proof)
    val leaves = proofs.map(_.leaf.toLowerCase).toSet
    val (proven, missing) = requested.partition(tx =>
      tx.leaf.nonEmpty && leaves.contains(tx.leaf.toLowerCase))
    CandidateMaterialized(identity, attempt, workMessage, proofs,
      proven.map(_.id).toSet, missing.map(_.id).toSet,
      requested.map(_.sizeBytes.toLong).sum, requested.map(_.cost).sum)
  }

  /**
   * Every entry of `proof.txProofs`, in response order, with `leaf` and `levels` preserved as
   * returned. Evidence about transactions this client did not request is kept rather than filtered:
   * it is not membership of the requested package, but it is still what the node said.
   */
  private def parseProofs(proof: JSONObject): Seq[NodeMerkleProof] = {
    val entries = proof.getJSONArray("txProofs")
    (0 until entries.length()).map { index =>
      val entry = entries.getJSONObject(index)
      val levels = entry.getJSONArray("levels")
      NodeMerkleProof(entry.getString("leaf"),
        (0 until levels.length()).map(level => MerkleLevel.fromEncoded(levels.getString(level))))
    }
  }

  /**
   * Whether unproven correspondence should reject an augmented candidate.
   *
   * Off while the supported node can return proofs that do not correspond to the transactions it was
   * given (ergoplatform/ergo#2463): enforcing it today would drop good candidates. Ordinary mining
   * keeps its existing compatibility behaviour either way; this switch is what a dependent revenue
   * bundle will need before it can be published, and turning it on is the whole change.
   */
  final val requireCorrespondence: Boolean = false
}
