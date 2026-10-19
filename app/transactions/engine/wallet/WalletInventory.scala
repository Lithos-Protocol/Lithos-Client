package transactions.engine.wallet

import configs.NodeContext
import mutations.NodeWallet.MINER_REWARD_DELAY
import mutations.NotEnoughInputsException
import node.MutationConversions._
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.LoggerFactory
import work.lithos.mutations.{InputUTXO, MainnetEip27Constants, Token, UTXO}

/**
 * One wallet box as the engine retains it: identifiers and amounts only, no registers, hydrated
 * ErgoTree or AppKit object. `reward` marks a timelocked coinbase, including master-key rewards
 * reported by the wallet API and derived-key rewards discovered through the indexer.
 */
private[engine] case class WalletDescriptor(id: String, value: Long, creationHeight: Int,
                                            tree: String, tokens: Vector[Token], reward: Boolean) {
  /** Approximate retained size, used to bound the descriptor cache by bytes rather than count. */
  def retainedBytes: Long = 128L + id.length * 2L + tree.length * 2L + tokens.size * 96L
}

private[engine] object WalletInventory {

  /**
   * A box's value less the nanoERG its re-emission tokens oblige a spender to pay away: their total
   * amount on mainnet, nothing elsewhere. The gross value overstates a mainnet reward box by that much.
   */
  def netOfReemission(value: Long, tokens: Iterator[(String, Long)], mainnet: Boolean): Long =
    if (!mainnet) value
    else Math.subtractExact(value, tokens.collect { case (id, amount) if id == MainnetEip27Constants.TokenId => amount }
      .foldLeft(0L)((sum, amount) => Math.addExact(sum, amount)))

  /**
   * Page through signable boxes: reward trees from the indexer first when rewards are wanted, then the
   * wallet's own list, mempool-aware so a box a pooled transaction spends is left out. `consume` gets
   * each box, whether it is a reward and whether it is in a block, and returns true to stop the walk.
   */
  def walk(api: _root_.node.NodeApi, signableTrees: Set[String], rewardTrees: Set[String],
           limits: configs.WalletConfig, rewardsOnly: Boolean, p2pkOnly: Boolean,
           deadlineMillis: Long = Long.MaxValue, includeRewards: Boolean = false, confirmedOnly: Boolean = false)
          (consume: (NodeBox, Boolean, Boolean) => Boolean): Unit = {
    val startedAt = System.nanoTime()
    val budgetNanos = limits.inventoryTimeoutMs * 1000000L
    var stop = false
    val seenRewards = scala.collection.mutable.HashSet.empty[String]
    // Each box comes with whether it is in a block and whether it is spent, filtered after the fetch so
    // a filtered page is not mistaken for the last one
    def pages(fetch: Paging => Seq[(NodeBox, Boolean, Boolean)], reward: Boolean): Unit = {
      var paging = Paging(0, limits.pageSize)
      var exhausted = false
      while (!stop && !exhausted) {
        require(System.nanoTime() - startedAt < budgetNanos, "wallet inventory walk exceeded its budget")
        require(System.currentTimeMillis() < deadlineMillis, "wallet funding request expired during selection")
        val page = fetch(paging)
        require(page.size <= limits.pageSize, "wallet page exceeded the requested limit")
        val boxes = page.iterator
        while (boxes.hasNext && !stop) {
          val (box, onchain, spent) = boxes.next()
          val isReward = rewardTrees.contains(box.ergoTree)
          val signable = if (isReward) !p2pkOnly && (includeRewards || rewardsOnly)
            else !reward && !rewardsOnly && signableTrees.contains(box.ergoTree)
          if (signable && !spent && (onchain || !confirmedOnly) && (!isReward || seenRewards.add(box.boxId)))
            stop = consume(box, isReward, onchain)
        }
        exhausted = page.size < limits.pageSize
        paging = paging.next
      }
    }
    if ((includeRewards || rewardsOnly) && !p2pkOnly && api.indexerEnabled) rewardTrees.iterator.foreach { tree =>
      if (!stop) pages(paging => api.unspentBoxesByErgoTree(tree, paging, SortDirection.Asc,
        MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)).get
        .map(indexed => (indexed.box, true, false)), reward = true)
    }
    if (!stop)
      pages(paging => api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging).get
        .map(boxed => (boxed.box, boxed.onchain, boxed.spent)), reward = false)
  }

  /**
   * Every page was read: a walk that stops part way throws instead of returning a snapshot.
   *
   * @param truncated  boxes were counted in the totals but dropped from `boxes` at a cache ceiling
   * @param seen       the watched ids the walk reported unspent, exact even when `boxes` is truncated
   * @param spendable  unreserved plain wallet value, excluding rewards and EIP-27 obligations
   */
  case class Snapshot(boxes: Vector[WalletDescriptor], truncated: Boolean, seen: Set[String],
                      height: Int, spendable: BigInt, locked: BigInt, unlocked: BigInt,
                      lockedCount: Int, unlockedCount: Int, nextUnlock: Option[Int])

  def descriptor(box: NodeBox, reward: Boolean): WalletDescriptor = {
    require(box.boxId.matches("[0-9a-f]{64}") && box.value > 0 && box.creationHeight >= 0,
      "invalid wallet descriptor")
    WalletDescriptor(box.boxId, box.value, box.creationHeight, box.ergoTree,
      box.assets.map(_.toToken).toVector, reward)
  }

  def nodeBox(input: InputUTXO): NodeBox = NodeBox(input.id.toString, input.input.getTransactionId,
    input.value, input.input.getTransactionIndex, input.input.getCreationHeight, input.contract.ergoTreeHex,
    input.tokens.map(token => NodeAsset(token.id.toString, token.amount)),
    NodeRegisters(input.registers.zipWithIndex.map { case (value, i) => s"R${i + 4}" -> value.toHex }.toMap))
}

/**
 * Streams wallet pages from the node and hydrates only the boxes a selection actually returns, so
 * idle memory holds descriptors rather than a full AppKit object per wallet box.
 */
private[engine] class WalletInventory(node: NodeContext, api: _root_.node.NodeApi,
                                      limits: configs.WalletConfig = configs.WalletConfig.Default) {
  private val MaxDescriptors = limits.maxDescriptors
  private val MaxDescriptorBytes = limits.maxDescriptorBytes
  private val MaxInputs = limits.maxInputs
  private val MaxInputBytes = limits.maxInputBytes

  import WalletInventory._
  private val wallet = node.getNodeWallet
  private val mainnet = node.getNetwork == org.ergoplatform.appkit.NetworkType.MAINNET

  /** Value this box can actually contribute, after its re-emission obligation. */
  private def spendableValue(box: NodeBox): Long =
    netOfReemission(box.value, box.assets.iterator.map(asset => asset.tokenId -> asset.amount), mainnet)

  private def matured(box: NodeBox, height: Int): Boolean =
    height.toLong > box.creationHeight.toLong + MINER_REWARD_DELAY

  /** Page through this wallet's signable boxes; see the companion's `walk`. */
  private def walk(rewardsOnly: Boolean, p2pkOnly: Boolean, deadlineMillis: Long = Long.MaxValue,
                   includeRewards: Boolean = false, confirmedOnly: Boolean = false)
                  (consume: (NodeBox, Boolean) => Boolean): Unit =
    WalletInventory.walk(api, wallet.signableTrees, wallet.rewardTrees.keySet, limits, rewardsOnly, p2pkOnly,
      deadlineMillis, includeRewards, confirmedOnly)((box, reward, _) => consume(box, reward))

  /**
   * Count and total the whole wallet while retaining only as many descriptors as the cache ceilings
   * allow. Totals and `seen` stay accurate past that point; `truncated` says the descriptor list does
   * not. `watched` is the ids whose presence the caller must know however large the wallet is.
   */
  def snapshot(height: Int, excluded: Set[String], watched: Set[String] = Set.empty): Snapshot = {
    var boxes = Vector.empty[WalletDescriptor]
    var retainedBytes = 0L
    var truncated = false
    var seen = Set.empty[String]
    var spendable, locked, unlocked = BigInt(0)
    var lockedCount, unlockedCount = 0
    var nextUnlock = Option.empty[Int]
    walk(rewardsOnly = false, p2pkOnly = false, includeRewards = true) { (box, reward) =>
      if (watched.contains(box.boxId)) seen += box.boxId
      val entry = descriptor(box, reward)
      if (boxes.size < MaxDescriptors && retainedBytes + entry.retainedBytes <= MaxDescriptorBytes) {
        boxes :+= entry
        retainedBytes += entry.retainedBytes
      } else truncated = true
      if (!excluded.contains(box.boxId)) {
        val value = BigInt(spendableValue(box)).max(BigInt(0))
        if (!reward) spendable += value
        if (reward && matured(box, height)) { unlocked += value; unlockedCount += 1 }
        else if (reward) {
          locked += value
          lockedCount += 1
          val blocksRemaining = math.max(0, box.creationHeight + MINER_REWARD_DELAY + 1 - height)
          nextUnlock = Some(nextUnlock.fold(blocksRemaining)(math.min(_, blocksRemaining)))
        }
      }
      false
    }
    Snapshot(boxes, truncated, seen, height, spendable, locked, unlocked,
      lockedCount, unlockedCount, nextUnlock)
  }

  /**
   * Find inputs covering `erg` and `required`, stopping at the first sufficient set rather than
   * materialising the address's whole box population. `known` holds locally built change whose
   * parent the node may not report yet, so it is offered before any page is fetched.
   *
   * A `single` request takes the smallest covering box, tokenless first, so a small requirement
   * never holds a large box. It reads on past the first match unless one is within twice the request.
   * Throws `NotEnoughInputsException` when the wallet cannot cover the request.
   */
  def select(ctx: BlockchainContext, erg: Long, required: Seq[Token], excluded: Set[String],
             single: Boolean,
             known: Vector[NodeBox] = Vector.empty, deadlineMillis: Long = Long.MaxValue,
             confirmedOnly: Boolean = false): Vector[InputUTXO] = {
    require(erg >= 0 && required.forall(_.amount > 0), "invalid wallet requirement")
    require(!mainnet || !required.exists(_.id.toString == MainnetEip27Constants.TokenId),
      "re-emission tokens cannot be transferred")
    val requiredTokens = required.groupBy(_.id.toString)
      .map { case (id, tokens) => id -> tokens.map(token => BigInt(token.amount)).sum }
    var selected = Vector.empty[NodeBox]
    var covered = false
    var smallest = Option.empty[NodeBox]

    // Re-emission tokens are excluded everywhere: they are burned by the build, never delivered, so
    // counting them as held would let a request for them appear satisfiable.
    def tokenTotals(boxes: Vector[NodeBox]): Map[String, BigInt] = boxes.flatMap(_.assets)
      .filterNot(asset => mainnet && asset.tokenId == MainnetEip27Constants.TokenId)
      .groupBy(_.tokenId).map { case (id, assets) => id -> assets.map(a => BigInt(a.amount)).sum }

    def covers(boxes: Vector[NodeBox]): Boolean = {
      val heldTokens = tokenTotals(boxes)
      val value = boxes.map(box => BigInt(spendableValue(box))).sum
      // Any token beyond what was asked for has to leave in a change box, so the selection must
      // cover that box's minimum value on top of the request.
      val needsTokenChange = heldTokens.exists {
        case (id, amount) => amount > requiredTokens.getOrElse(id, BigInt(0))
      }
      value >= BigInt(erg) + (if (needsTokenChange) UTXO.MIN_CHANGE else 0L) &&
        requiredTokens.forall { case (id, amount) => heldTokens.getOrElse(id, BigInt(0)) >= amount }
    }

    // Tokenless before token-bearing, then by value: the order a single-box request prefers.
    def rank(box: NodeBox): (Boolean, Long) = (box.assets.nonEmpty, spendableValue(box))

    def consume(box: NodeBox, reward: Boolean): Boolean = {
      if (!excluded.contains(box.boxId) && !selected.exists(_.boxId == box.boxId) &&
        (!reward || matured(box, ctx.getHeight)) && spendableValue(box) > 0) {
        if (single) {
          if (covers(Vector(box)) && smallest.forall(best => Ordering[(Boolean, Long)].lt(rank(box), rank(best)))) {
            smallest = Some(box)
            covered = box.assets.isEmpty && spendableValue(box) - erg <= erg
          }
        }
        else {
          val heldTokens = tokenTotals(selected)
          val advancesTokens = box.assets.exists(asset =>
            heldTokens.getOrElse(asset.tokenId, BigInt(0)) < requiredTokens.getOrElse(asset.tokenId, BigInt(0)))
          val advancesValue = selected.map(b => BigInt(spendableValue(b))).sum < BigInt(erg) + UTXO.MIN_CHANGE
          if (advancesTokens || advancesValue) selected :+= box
          if (selected.size > MaxInputs) {
            val heldTokens = tokenTotals(selected)
            // How much of the requirement would go unmet without this box, then its value. The
            // lowest pair contributes least, so dropping it keeps the set within the input cap
            // while losing the least progress.
            def contribution(box: NodeBox): (BigInt, Long) = {
              val stillNeeded = box.assets.map { asset =>
                val need = requiredTokens.getOrElse(asset.tokenId, BigInt(0))
                (need - (heldTokens.getOrElse(asset.tokenId, BigInt(0)) - asset.amount)).max(BigInt(0))
              }.sum
              stillNeeded -> spendableValue(box)
            }
            val leastUseful = selected.indices.minBy(index => contribution(selected(index)))
            selected = selected.patch(leastUseful, Nil, 1)
          }
          covered = covers(selected)
        }
      }
      covered
    }

    // Locally built change is unconfirmed by definition
    known.iterator.filter(box => !confirmedOnly && wallet.signableTrees.contains(box.ergoTree))
      .takeWhile(_ => !covered).foreach(box => consume(box, reward = false))
    if (!covered) walk(rewardsOnly = false, p2pkOnly = false, deadlineMillis, confirmedOnly = confirmedOnly)(consume)
    if (single) { selected = smallest.toVector; covered = smallest.nonEmpty }
    if (!covered) throw new NotEnoughInputsException("wallet cannot cover this request within the input budget")

    // Boxes added while the set was still short can turn out to be unnecessary once a later box
    // completed the requirement. Dropping them keeps the transaction small and the wallet unfragmented.
    var index = 0
    while (index < selected.size) {
      val without = selected.patch(index, Nil, 1)
      if (covers(without)) selected = without else index += 1
    }

    selected.map { box =>
      val input = box.toInputUTXO(ctx)
      require(input.id.toString == box.boxId, "wallet box identity differs from its contents")
      require(input.bytes.length <= MaxInputBytes, "wallet input exceeds hydration byte budget")
      input
    }
  }
}
