package mining

import mining.MiningMessages.CandidateIdentity
import node.model.{MerkleLevel, NodeMerkleProof}
import org.json.JSONObject
import transactions.candidate.CandidateTopUp
import transactions.candidate.BlockTxMessages.CandidateTx

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
                                       unprovenInclusions: Set[String],
                                       knownBytes: Long,
                                       knownCost: Long) {

  /** Whether every requested transaction was matched to a proof leaf. */
  def fullyProven: Boolean = unproven.isEmpty

  /** Whether the members the rest of the package hangs off were matched. */
  def inclusionProven: Boolean = unprovenInclusions.isEmpty
}

object CandidateMaterialized {

  /**
   * Read the returned proofs and work out which requested transactions they account for.
   *
   * A leaf is the digest a block's transaction tree carries for a transaction, which is its id, and
   * that is what the builders record on each `CandidateTx`. A member with no recorded leaf can
   * never be matched and is reported unproven rather than treated as absent.
   *
   * Correspondence is computed here and acted on by the caller; [[requireCorrespondence]] says
   * whether a mismatch rejects the candidate.
   */
  def apply(identity: CandidateIdentity, attempt: UUID, workMessage: String,
            proof: JSONObject, requested: Seq[CandidateTx]): CandidateMaterialized = {
    val proofs = parseProofs(proof)
    val leaves = proofs.map(_.leaf.toLowerCase).toSet
    val (proven, missing) = requested.partition(tx =>
      tx.leaf.nonEmpty && leaves.contains(tx.leaf.toLowerCase))
    CandidateMaterialized(identity, attempt, workMessage, proofs,
      proven.map(_.id).toSet, missing.map(_.id).toSet,
      missing.filter(tx => LoadBearing.contains(tx.kind)).map(_.id).toSet,
      requested.map(_.sizeBytes.toLong).sum, requested.map(_.cost).sum)
  }

  /**
   * The members the rest of the package hangs off.
   *
   * The genesis creates the holding box, and the top-up folds every unspent candidate output into
   * it, so a block proving the top-up has to carry both the genesis and whatever produced the
   * outputs it spends. An extra that is unproven on its own is a transaction the node chose not to
   * take; these two unproven mean the package did not land.
   */
  private val LoadBearing: Set[String] = Set(CandidateTx.Genesis, CandidateTopUp.Kind)

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
   * On: a proof that does not account for the load-bearing members is taken at face value, so the
   * extras are dropped and the package is mined as genesis. This was off while the supported node
   * could answer with proofs that did not correspond to what it was given (ergoplatform/ergo#2463).
   */
  final val requireCorrespondence: Boolean = true
}
