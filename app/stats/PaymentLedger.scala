package stats

/** A rollup transition that concerns this client, stamped with the block that confirmed it. */
final case class LedgerEvent(height: Int, timestamp: Long, activity: RollupActivity)

/**
 * A rollup this client submitted a NISP to and has not been paid from yet.
 *
 * `phase` is the furthest transition seen: `holding` until evaluation starts, then `evaluation`,
 * then `payout` once the rollup's reward is fixed. `slashed` means a fraud proof removed this
 * client's entry and its bond went to the prover, so it will never pay.
 *
 * `rewardBasis` says how far to trust the reward: `exact` once a payout is ready, since the share
 * is fixed; `projected` during evaluation, where a fraud proof against another miner still raises
 * it. Holding has no projection, because more miners and top-ups can still arrive.
 *
 * `payoutReadyFrom` is the first height the payout transition can land at. It is exact during
 * evaluation, whose period starts at the block the transition landed in, and a floor during
 * holding, which assumes the evaluation transition lands the moment holding allows it.
 */
final case class PaymentClaim(rollupNft: String, minedBlockId: String, minedHeight: Int, phase: String,
                              submittedHeight: Int, submittedTimestamp: Long, submissionTransactionId: String,
                              score: String, bondNanoErg: String, phaseHeight: Int, phaseTimestamp: Long,
                              rollupMiners: Option[Int] = None, rollupScore: Option[String] = None,
                              rewardNanoErg: Option[String] = None, rewardLit: Option[String] = None,
                              rewardBasis: Option[String] = None, slashedTransactionId: Option[String] = None,
                              payoutReadyFrom: Option[Int] = None)

/** A fraud proof this client submitted. The slashed entry's bond is paid to the prover. */
final case class FraudBounty(transactionId: String, rollupNft: String, minedHeight: Int, height: Int,
                             timestamp: Long, bondNanoErg: String, claimedScore: String, minerHash: Option[String])

final case class PaymentLedgerPage(status: String, source: MiningCursor, retainedFromHeight: Int,
                                   payments: Vector[MiningPaymentRecord], offset: Int, total: Int,
                                   sort: String, ascending: Boolean,
                                   claims: Vector[PaymentClaim], bounties: Vector[FraudBounty],
                                   holdingBlocks: Int = PaymentLedger.HoldingBlocks,
                                   evaluationBlocks: Int = PaymentLedger.EvaluationBlocks)

object PaymentLedger {
  val DefaultLimit: Int = 100
  val MaxLimit: Int = 500
  val HoldingBlocks: Int = lfsm.LFSMHelpers.HOLDING_PERIOD.toInt
  val EvaluationBlocks: Int = lfsm.LFSMHelpers.EVAL_PERIOD.toInt

  /** Kinds kept for a tracked rollup. Top-ups move the holding value but never fix a share. */
  private val Transitions = Set("evaluation", "fraudProof", "payoutReady")

  /** Payout orderings a page can be read in: by the block that paid it, or by the rollup's own block. */
  val Sorts: Set[String] = Set("paid", "mined")

  /** Both heights sit in the key, so every ordering is decided without decoding a single record. */
  def paymentKey(p: MiningPaymentRecord): String =
    f"ledger/payment/${p.height}%010d/${p.minedHeight}%010d/${p.rollupNft}/${p.outputId}"
  def claimKey(height: Int, a: RollupActivity): String = f"ledger/claim/${a.rollupNft}/$height%010d/${a.transactionId}"
  def bountyKey(height: Int, a: RollupActivity): String = f"ledger/bounty/$height%010d/${a.transactionId}"

  final case class PaymentKey(key: String, paidHeight: Int, minedHeight: Int, nft: String)

  def paymentKeyParts(key: String): PaymentKey = key.split('/') match {
    case Array("ledger", "payment", paid, mined, nft, _) => PaymentKey(key, paid.toInt, mined.toInt, nft)
    case _ => throw new IllegalStateException(s"malformed ledger payment key $key")
  }
  def claimKeyNft(key: String): String = key.split('/') match {
    case Array("ledger", "claim", nft, _, _) => nft
    case _ => throw new IllegalStateException(s"malformed ledger claim key $key")
  }

  /** Whether a transition belongs in the claim index: a local submission, or a later step of a tracked rollup. */
  def tracked(a: RollupActivity, isTracked: String => Boolean): Boolean =
    (a.kind == "submission" && a.local) || (Transitions.contains(a.kind) && isTracked(a.rollupNft))

  /**
   * One page of keys in the chosen order, highest first unless `ascending`. Paid time needs no
   * ordering of its own: block timestamps rise with height, so it always agrees with `paid`.
   *
   * A payout confirmed between two requests shifts later pages by one; the height and output id on
   * each row identify it regardless.
   */
  def page(keys: Vector[String], offset: Int, limit: Int, sort: String = "paid",
           ascending: Boolean = false): Vector[String] = {
    require(limit > 0 && limit <= MaxLimit, s"limit must be between 1 and $MaxLimit")
    require(offset >= 0, "offset must be nonnegative")
    require(Sorts.contains(sort), s"sort must be one of ${Sorts.toVector.sorted.mkString(", ")}")
    // Byte order is already paid height, then mined height; ties in either sort fall back to the key.
    val ordered = if (sort == "paid") keys
      else keys.map(paymentKeyParts).sortBy(k => (k.minedHeight, k.paidHeight, k.key)).map(_.key)
    (if (ascending) ordered else ordered.reverse).slice(offset, offset + limit)
  }

  /**
   * Open claims from the events of rollups that have not paid this client.
   *
   * A rollup whose submission has already been pruned is dropped rather than shown without one:
   * the share and bond it would report come from that event.
   */
  def claims(events: Vector[LedgerEvent], holdingBlocks: Int = HoldingBlocks,
             evaluationBlocks: Int = EvaluationBlocks): Vector[PaymentClaim] =
    events.groupBy(_.activity.rollupNft).toVector.flatMap { case (nft, group) =>
      val ordered = group.sortBy(_.height)
      ordered.find(e => e.activity.kind == "submission" && e.activity.local).map { submitted =>
        val s = submitted.activity
        val score = BigInt(s.claimedScore)
        val later = ordered.filter(_.height >= submitted.height)
        val slash = later.find(e => e.activity.kind == "fraudProof" && e.activity.localTarget)
        val ready = later.reverse.find(_.activity.kind == "payoutReady")
        val evaluation = later.reverse.find(_.activity.kind == "evaluation")
        val base = PaymentClaim(nft, s.minedBlockId, s.minedHeight, "holding", submitted.height,
          submitted.timestamp, s.transactionId, s.claimedScore, s.bondNanoErg, submitted.height, submitted.timestamp,
          payoutReadyFrom = Some(s.minedHeight + holdingBlocks + evaluationBlocks))

        (slash, ready, evaluation) match {
          case (Some(e), _, _) =>
            base.copy(phase = "slashed", phaseHeight = e.height, phaseTimestamp = e.timestamp,
              slashedTransactionId = Some(e.activity.transactionId), payoutReadyFrom = None)
          case (None, Some(e), _) =>
            // The payout state is final: reward and LIT are the whole rollup's, the score is its total.
            val total = BigInt(e.activity.claimedScore)
            base.copy(phase = "payout", phaseHeight = e.height, phaseTimestamp = e.timestamp,
              rollupMiners = Some(e.activity.miners), rollupScore = Some(total.toString),
              rewardNanoErg = share(BigInt(e.activity.valueNanoErg), score, total),
              rewardLit = e.activity.rewardLit.flatMap(lit => share(BigInt(lit), score, total)),
              rewardBasis = Some("exact"), payoutReadyFrom = None)
          case (None, None, Some(e)) =>
            // Each fraud proof since removes a miner, their score, and a bond from value and ledger
            // alike, so the distributable pool (value less bonds) holds while the total score shrinks.
            val proofs = later.filter(p => p.height >= e.height && p.activity.kind == "fraudProof")
            val total = BigInt(e.activity.claimedScore) - proofs.map(p => BigInt(p.activity.claimedScore)).sum
            val pool = BigInt(e.activity.valueNanoErg) - BigInt(e.activity.bondNanoErg)
            base.copy(phase = "evaluation", phaseHeight = e.height, phaseTimestamp = e.timestamp,
              rollupMiners = Some(math.max(0, e.activity.miners - proofs.size)), rollupScore = Some(total.toString),
              rewardNanoErg = share(pool, score, total), rewardBasis = Some("projected"),
              payoutReadyFrom = Some(e.height + evaluationBlocks))
          case _ => base
        }
      }
    }.sortBy(c => (-c.submittedHeight, c.rollupNft))

  private def share(amount: BigInt, score: BigInt, total: BigInt): Option[String] =
    if (amount < 0 || score <= 0 || total <= 0 || score > total) None else Some((amount * score / total).toString)

  def bounty(event: LedgerEvent): FraudBounty = {
    val a = event.activity
    FraudBounty(a.transactionId, a.rollupNft, a.minedHeight, event.height, event.timestamp,
      a.bondNanoErg, a.claimedScore, a.minerHash)
  }
}
