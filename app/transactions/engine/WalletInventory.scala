package transactions.engine

import configs.NodeContext
import mutations.NodeWallet.MINER_REWARD_DELAY
import node.MutationConversions._
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.{InputUTXO, MainnetEip27Constants, Token, UTXO}

/**
 * One wallet box as the engine retains it: identifiers and amounts only, no registers, hydrated
 * ErgoTree or AppKit object. `reward` marks a coinbase box, which is timelocked and is found by
 * ErgoTree because no wallet reports it.
 */
private[engine] case class WalletDescriptor(id: String, value: Long, creationHeight: Int,
                                            tree: String, tokens: Vector[Token], reward: Boolean) {
  /** Approximate retained size, used to bound the descriptor cache by bytes rather than count. */
  def retainedBytes: Long = 128L + id.length * 2L + tree.length * 2L + tokens.size * 96L
}

private[engine] object WalletInventory {
  final val MaxDescriptors = 2048
  final val MaxDescriptorBytes = 1024L * 1024L
  final val MaxInputBytes = 4096
  /** Inputs one selection may return, which also caps a consolidation transaction. */
  final val MaxInputs = 75
  final val PageSize = 100
  final val MaxWalkNanos = 120L * 1000000000L

  /**
   * @param complete   the walk reached the end of every page
   * @param truncated  boxes were counted in the totals but dropped from `boxes` at a cache ceiling
   * @param spendable  unreserved value, excluding timelocked rewards and EIP-27 obligations
   */
  case class Snapshot(boxes: Vector[WalletDescriptor], complete: Boolean, truncated: Boolean,
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
private[engine] class WalletInventory(node: NodeContext, api: _root_.node.NodeApi) {
  import WalletInventory._
  private val wallet = node.getNodeWallet
  private val mainnet = node.getNetwork == org.ergoplatform.appkit.NetworkType.MAINNET

  /**
   * Value this box can actually contribute, after the nanoERG that spending its re-emission tokens
   * obliges the transaction to pay away. Treating the gross value as spendable overstates a mainnet
   * reward box by exactly the token amount it carries.
   */
  private def spendableValue(box: NodeBox): Long = {
    val reemissionDue = if (!mainnet) 0L
      else box.assets.filter(_.tokenId == MainnetEip27Constants.TokenId)
        .foldLeft(0L)((sum, asset) => Math.addExact(sum, asset.amount))
    Math.subtractExact(box.value, reemissionDue)
  }

  private def matured(box: NodeBox, height: Int): Boolean =
    height.toLong > box.creationHeight.toLong + MINER_REWARD_DELAY

  /**
   * Page through the wallet's boxes, offering each signable one to `consume`. Rewards come first and
   * only when the node is indexed, since they sit at ErgoTrees no wallet endpoint reports.
   * `consume` returns true to stop the walk, which is how selection avoids paging the whole wallet.
   */
  private def walk(rewardsOnly: Boolean, p2pkOnly: Boolean)
                  (consume: (NodeBox, Boolean) => Boolean): Unit = {
    val startedAt = System.nanoTime()
    var stop = false
    def pages(fetch: Paging => Seq[NodeBox], reward: Boolean): Unit = {
      var paging = Paging(0, PageSize)
      var exhausted = false
      while (!stop && !exhausted) {
        require(System.nanoTime() - startedAt < MaxWalkNanos, "wallet inventory walk exceeded its budget")
        val page = fetch(paging)
        require(page.size <= PageSize, "wallet page exceeded the requested limit")
        val boxes = page.iterator
        while (boxes.hasNext && !stop) {
          val box = boxes.next()
          val signable = if (reward) wallet.rewardTrees.contains(box.ergoTree)
            else wallet.signableTrees.contains(box.ergoTree)
          if (signable) stop = consume(box, reward)
        }
        exhausted = page.size < PageSize
        paging = paging.next
      }
    }
    if (!p2pkOnly && api.indexerEnabled) wallet.rewardTrees.keysIterator.foreach { tree =>
      if (!stop) pages(paging => api.unspentBoxesByErgoTree(tree, paging, SortDirection.Asc,
        MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)).get.map(_.box), reward = true)
    }
    if (!rewardsOnly && !stop)
      pages(paging => api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging).get.map(_.box),
        reward = false)
  }

  /**
   * Count and total the whole wallet while retaining only as many descriptors as the cache ceilings
   * allow. Totals stay accurate past that point; `truncated` says the descriptor list does not.
   */
  def snapshot(height: Int, excluded: Set[String]): Snapshot = {
    var boxes = Vector.empty[WalletDescriptor]
    var retainedBytes = 0L
    var truncated = false
    var spendable, locked, unlocked = BigInt(0)
    var lockedCount, unlockedCount = 0
    var nextUnlock = Option.empty[Int]
    walk(rewardsOnly = false, p2pkOnly = false) { (box, reward) =>
      val entry = descriptor(box, reward)
      if (boxes.size < MaxDescriptors && retainedBytes + entry.retainedBytes <= MaxDescriptorBytes) {
        boxes :+= entry
        retainedBytes += entry.retainedBytes
      } else truncated = true
      if (!excluded.contains(box.boxId)) {
        val value = BigInt(spendableValue(box)).max(BigInt(0))
        if (!reward || matured(box, height)) spendable += value
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
    Snapshot(boxes, complete = true, truncated, height, spendable, locked, unlocked,
      lockedCount, unlockedCount, nextUnlock)
  }

  /**
   * Find inputs covering `erg` and `required`, stopping at the first sufficient set rather than
   * materialising the address's whole box population. `known` holds locally built change whose
   * parent the node may not report yet, so it is offered before any page is fetched.
   */
  def select(ctx: BlockchainContext, erg: Long, required: Seq[Token], excluded: Set[String],
             single: Boolean, p2pkOnly: Boolean, rewardsOnly: Boolean,
             known: Vector[NodeBox] = Vector.empty): Vector[InputUTXO] = {
    require(erg >= 0 && required.forall(_.amount > 0), "invalid wallet requirement")
    require(!mainnet || !required.exists(_.id.toString == MainnetEip27Constants.TokenId),
      "re-emission tokens cannot be transferred")
    val requiredTokens = required.groupBy(_.id.toString)
      .map { case (id, tokens) => id -> tokens.map(token => BigInt(token.amount)).sum }
    var selected = Vector.empty[NodeBox]
    var covered = false

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

    def consume(box: NodeBox, reward: Boolean): Boolean = {
      if (!excluded.contains(box.boxId) && !selected.exists(_.boxId == box.boxId) &&
        (!reward || matured(box, ctx.getHeight)) && spendableValue(box) > 0) {
        if (single) { if (covers(Vector(box))) { selected = Vector(box); covered = true } }
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

    known.iterator.takeWhile(_ => !covered).foreach(box => consume(box, reward = false))
    if (!covered) walk(rewardsOnly, p2pkOnly)(consume)
    require(covered, "wallet cannot cover this request within the input budget")

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
