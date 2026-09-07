package state.synchronization

import node.NodeApi
import node.rest.NodeCodecs

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/**
 * A complete observation of the local node's mempool, built from the transaction-id inventory rather
 * than page offsets so churn cannot silently drop a member. Bodies are decoded to extract spends and
 * lender keys and then discarded; nothing hydrated survives collection.
 */
object CompleteMempool {

  /** Ask the owning actor for the newest observation, refreshing it if none is in flight. */
  case object Refresh

  /**
   * The last observation and why it may be unusable. Consumers that decide whether an input or a
   * lender key is free must check [[fresh]]: a stale view can support diagnostics but not a spend.
   */
  final case class Observation(revision: Long, snapshot: Option[Snapshot], failure: Option[String]) {
    def fresh: Boolean =
      failure.isEmpty && snapshot.exists(s => System.nanoTime() - s.observedAt < MaxAgeNanos)
  }

  /**
   * @param anchor      best full header id when the walk started and finished; a change invalidates it
   * @param ids         every transaction in the mempool at that anchor
   * @param spent       every box consumed by those transactions
   * @param lenderKeys  hashed lender keys claimed by unconfirmed queue and collateral boxes
   */
  final case class Snapshot(anchor: String, ids: Set[String], spent: Set[String], observedAt: Long,
                            lenderKeys: Set[String] = Set.empty)

  final val MaxTransactions = 4096
  final val MaxBytes = 16 * 1024 * 1024L
  /** Shared ceiling for the spend index and the lender-key set. */
  final val MaxInputs = 65536
  /** Past this an observation cannot authorise a spend, however complete it was when taken. */
  final val MaxAgeNanos = 15000000000L
  final val MaxWalkNanos = 30000000000L

  /** The parent an observation is pinned to. Every spend conclusion is only valid against it. */
  def anchor(node: NodeApi): String = {
    val info = node.info().get
    val headerId = info.bestFullHeaderId.getOrElse(
      throw new IllegalStateException("node has no full chain anchor"))
    require(headerId.matches("[0-9a-fA-F]{64}"), "invalid full chain anchor")
    headerId.toLowerCase
  }

  /**
   * Walk the whole mempool once. Fails rather than returning a partial view: an incomplete walk
   * cannot tell an absent transaction from an unfetched one, and callers treat absence as "unspent".
   */
  def collect(node: NodeApi): Try[Snapshot] = Try {
    val startedAt = System.nanoTime()
    def requireWithinDeadline(): Unit =
      require(System.nanoTime() - startedAt < MaxWalkNanos, "mempool refresh deadline exceeded")

    def inventory(): Set[String] = {
      requireWithinDeadline()
      val ids = node.unconfirmedTransactionIds().get
      require(ids.size <= MaxTransactions && ids.distinct.size == ids.size,
        "mempool inventory exceeds limit or contains duplicates")
      require(ids.forall(_.matches("[0-9a-f]{64}")), "invalid mempool transaction id")
      ids.toSet
    }

    val anchorAtStart = anchor(node)
    val memberIds = inventory()
    var decodedBytes = 0L
    var spentBoxIds = Set.empty[String]
    var lenderKeys = Set.empty[String]

    memberIds.toVector.sorted.foreach { txId =>
      requireWithinDeadline()
      // The byIds route on the supported node returns IDs, not transaction bodies.
      val tx = node.unconfirmedTransactionById(txId).get.getOrElse(
        throw new IllegalStateException("mempool member disappeared during collection"))
      require(tx.id == txId, "mempool body does not match requested id")
      decodedBytes += NodeCodecs.encodeTransaction(tx).toString.getBytes(UTF_8).length
      require(decodedBytes <= MaxBytes, "mempool body budget exceeded")

      tx.outputs.foreach(box => lenderKeyOf(box).foreach { key =>
        lenderKeys += key
        require(lenderKeys.size <= MaxInputs, "mempool lender key budget exceeded")
      })

      val inputIds = tx.inputs.map(_.boxId)
      require(inputIds.forall(_.matches("[0-9a-f]{64}")) && inputIds.distinct.size == inputIds.size,
        "invalid or duplicate mempool input")
      require(!inputIds.exists(spentBoxIds.contains), "conflicting mempool input")
      spentBoxIds ++= inputIds
      require(spentBoxIds.size <= MaxInputs, "mempool spend index budget exceeded")
    }

    // Re-read both: a member that arrived or left during the walk, or a new parent, means the spend
    // set above describes a mempool that no longer exists.
    require(inventory() == memberIds && anchor(node) == anchorAtStart,
      "mempool membership or chain anchor changed")
    requireWithinDeadline()
    Snapshot(anchorAtStart, memberIds, spentBoxIds, System.nanoTime(), lenderKeys)
  }

  /**
   * The hashed lender key an unconfirmed queue or collateral output claims, or None for any other
   * box. Keys are taken from every such output, including joins outside the emission chain this
   * client is following, because a duplicate key anywhere produces a position that gets slashed.
   *
   * A protocol-token box whose registers do not match its contract makes the whole observation
   * unavailable, which is the safe direction: it can never imply that a key is free.
   */
  private def lenderKeyOf(box: node.model.NodeBox): Option[String] = {
    import _root_.node.MutationConversions._
    val singleton = box.assets.headOption.filter(_.amount == 1L).map(_.tokenId)
    val queueToken = lfsm.LFSMHelpers.QUEUE_TOKEN.toString
    val collateralToken = lfsm.LFSMHelpers.COLLAT_TOKEN.toString
    singleton.filter(id => id == queueToken || id == collateralToken).map { token =>
      val registers = box.registerValues
      // A live collateral box stores the key directly; a queue position stores the lender's
      // SigmaProp in R5, which has to be hashed the same way the contracts do.
      val key = if (registers.size == 1 && token == collateralToken)
        registers.head.getValue.asInstanceOf[sigma.Coll[Byte]].toArray
      else {
        require(registers.size >= 2, "protocol-token output has no lender register")
        transactions.ProtocolContracts.lenderEntry(registers(1).getValue.asInstanceOf[sigma.SigmaProp])
      }
      require(key.length == 32, "invalid unconfirmed lender key")
      org.bouncycastle.util.encoders.Hex.toHexString(key)
    }
  }
}
