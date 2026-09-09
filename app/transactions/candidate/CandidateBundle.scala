package transactions.candidate

import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, MempoolInteraction}

/**
 * Members are admitted together locally, in dependency order supplied by their builder.
 *
 * `interactions` states how the bundle means to meet the mempool. Admission checks the claim rather
 * than trusting it: a bundle that says it chains from a parent has to carry that parent.
 *
 * `capital` names the spendable outputs these members created, which a final holding top-up may
 * aggregate. Declared by the builder because only it knows which of its outputs are revenue rather
 * than protocol state; a bundle dropped during admission takes its entries with it.
 */
final case class CandidateBundle(members: Vector[CandidateTx],
                                 interactions: Seq[MempoolInteraction] = Seq.empty,
                                 capital: Seq[CapitalEntry] = Seq.empty) {
  require(members.nonEmpty && members.size <= 4096, "invalid candidate bundle size")
  require(members.map(_.id).distinct.size == members.size, "duplicate transaction inside candidate bundle")

  /** Whether every parent this bundle claims to chain from travels with it, ahead of its children. */
  def carriesItsParents: Boolean = {
    val ids = members.map(_.id).toSet
    interactions.forall {
      case ChainFromMempool(parentTxId) => ids.contains(parentTxId)
      case _ => true
    }
  }
}

/**
 * How much of a block this client's own package may claim.
 *
 * Deliberately a share rather than the whole limit. The node adds its own emission and
 * fee-collection transactions and selects a remainder this client never sees, and an unconfirmed
 * ancestor carried into the package reports no execution cost at all. Leaving the rest of the block
 * unclaimed is what keeps those unknowns from turning into a rejected candidate.
 */
final case class CandidateBudget(maxBytes: Long, maxCost: Long) {
  /** What is left after something outside the selection has already claimed part of the share. */
  def less(bytes: Long, cost: Long): CandidateBudget =
    CandidateBudget(math.max(0L, maxBytes - bytes), math.max(0L, maxCost - cost))
}

object CandidateBudget {
  /** Fraction of the active block limits one Lithos package may occupy, on either dimension. */
  final val DefaultShare = 0.5

  def of(maxBlockSize: Long, maxBlockCost: Long, share: Double = DefaultShare): CandidateBudget =
    CandidateBudget((maxBlockSize * share).toLong, (maxBlockCost * share).toLong)

  /** Used when the node's parameters could not be read; the count limit still applies. */
  val Unbounded: CandidateBudget = CandidateBudget(Long.MaxValue, Long.MaxValue)
}

object CandidateBundle {
  /**
   * Choose bundles for one candidate package, in the order they are offered.
   *
   * Shared members consume one slot, and a bundle that cannot fit contributes nothing rather than a
   * partial prefix. Four things disqualify one: a member whose body disagrees with a copy already
   * selected, a box already claimed by the package, a declared mempool parent it does not carry, and
   * exceeding the count or either budget dimension. Two sources spending the same box is a double
   * spend the node would refuse the whole candidate over, and sources build independently, so none
   * of them can see the conflict itself.
   */
  def select(bundles: Seq[CandidateBundle], limit: Int,
             budget: CandidateBudget = CandidateBudget.Unbounded): Vector[CandidateTx] =
    admitted(bundles, limit, budget)._2

  /** Reported alone, for a caller that only wants to say what it turned away. */
  def refusals(bundles: Seq[CandidateBundle], limit: Int,
               budget: CandidateBudget = CandidateBudget.Unbounded): Vector[CandidateRefusal] =
    admitted(bundles, limit, budget)._3

  /**
   * The same choice, reported as the bundles that fit rather than their flattened members.
   *
   * Used to bound one source before the package is bounded as a whole: a bundle has to survive both
   * passes intact, so the first cannot be allowed to break it up.
   */
  def fit(bundles: Seq[CandidateBundle], limit: Int,
          budget: CandidateBudget = CandidateBudget.Unbounded): Vector[CandidateBundle] =
    admitted(bundles, limit, budget)._1

  /**
   * The same choice, reported in full: the bundles, their transactions, and why the rest were
   * turned away. A refused bundle is otherwise indistinguishable from one never offered.
   */
  def admit(bundles: Seq[CandidateBundle], limit: Int,
            budget: CandidateBudget = CandidateBudget.Unbounded)
  : (Vector[CandidateBundle], Vector[CandidateTx], Vector[CandidateRefusal]) =
    admitted(bundles, limit, budget)

  private def admitted(bundles: Seq[CandidateBundle], limit: Int, budget: CandidateBudget)
  : (Vector[CandidateBundle], Vector[CandidateTx], Vector[CandidateRefusal]) = {
    var keptBundles = Vector.empty[CandidateBundle]
    var selected = Vector.empty[CandidateTx]
    var refused = Vector.empty[CandidateRefusal]
    var byId = Map.empty[String, CandidateTx]
    var claimed = Set.empty[String]
    var bytes = 0L
    var cost = 0L
    bundles.foreach { bundle =>
      val additions = bundle.members.filterNot(tx => byId.contains(tx.id))
      // A member already selected brought its own inputs and charges with it, so only the additions
      // can conflict or cost anything — with the package, and with each other inside this bundle.
      val addedInputs = additions.flatMap(_.inputIds)
      val addedBytes = additions.map(_.sizeBytes.toLong).sum
      val addedCost = additions.map(_.cost).sum
      val slots = math.max(0, limit - selected.size)
      val consistent = bundle.members.forall(tx => byId.get(tx.id).forall(_ == tx))
      val unconflicted = !addedInputs.exists(claimed.contains) &&
        addedInputs.distinct.size == addedInputs.size
      val affordable = additions.size <= slots &&
        bytes + addedBytes <= budget.maxBytes && cost + addedCost <= budget.maxCost
      if (consistent && unconflicted && affordable && bundle.carriesItsParents) {
        keptBundles :+= bundle
        selected ++= additions
        byId ++= additions.map(tx => tx.id -> tx)
        claimed ++= addedInputs
        bytes += addedBytes
        cost += addedCost
      } else {
        // First failing test in a fixed order, so one refusal reads as one cause.
        val reason =
          if (!consistent) "a member disagrees with a copy already selected"
          else if (!unconflicted) "an input is already claimed by this package"
          else if (!bundle.carriesItsParents) "a declared mempool parent does not travel with it"
          else if (additions.size > slots) s"${additions.size} transactions into $slots free slot(s)"
          else if (bytes + addedBytes > budget.maxBytes)
            s"$addedBytes bytes over the ${budget.maxBytes - bytes} left in the package"
          else s"$addedCost cost over the ${budget.maxCost - cost} left in the package"
        refused :+= CandidateRefusal(bundle, reason)
      }
    }
    (keptBundles, selected, refused)
  }
}
