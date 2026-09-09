package transactions.candidate

/**
 * Why one offered bundle did not make it into a package.
 *
 * Admission is a pure choice over what the sources offered and does no logging of its own, so the
 * reason travels back as data. Without it a bundle that overran the budget looks exactly like a
 * source that had nothing to give.
 */
final case class CandidateRefusal(bundle: CandidateBundle, reason: String) {
  /** Names the bundle by its members, since a bundle has no id of its own. */
  def label: String = bundle.members.map(t => s"${t.kind}:${t.id.take(8)}").mkString(", ")

  override def toString: String = s"[$label] $reason"
}
