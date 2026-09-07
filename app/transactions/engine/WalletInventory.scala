package transactions.engine

import configs.NodeContext
import mutations.NodeWallet.MINER_REWARD_DELAY
import node.MutationConversions._
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.{InputUTXO, MainnetEip27Constants, Token, UTXO}

/** Compact inventory entries contain no registers, hydrated ErgoTrees or AppKit boxes. */
private[engine] case class WalletDescriptor(id: String, value: Long, creationHeight: Int,
                                           tree: String, tokens: Vector[Token], reward: Boolean) {
  def retainedBytes: Long = 128L + id.length * 2L + tree.length * 2L + tokens.size * 96L
}

private[engine] object WalletInventory {
  final val MaxDescriptors = 2048
  final val MaxDescriptorBytes = 1024L * 1024L
  final val MaxInputBytes = 4096
  final val MaxInputs = 75
  final val PageSize = 100
  final val MaxWalkNanos = 120L * 1000000000L

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
    input.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
    NodeRegisters(input.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
}

/** One worker streams pages and hydrates only the bounded final selection. */
private[engine] class WalletInventory(node: NodeContext, api: _root_.node.NodeApi) {
  import WalletInventory._
  private val wallet = node.getNodeWallet
  private val mainnet = node.getNetwork == org.ergoplatform.appkit.NetworkType.MAINNET

  private def net(box: NodeBox): Long = {
    val due = if (!mainnet) 0L else box.assets.filter(_.tokenId == MainnetEip27Constants.TokenId)
      .foldLeft(0L)((n, t) => Math.addExact(n, t.amount))
    Math.subtractExact(box.value, due)
  }

  private def matured(box: NodeBox, height: Int): Boolean =
    height.toLong > box.creationHeight.toLong + MINER_REWARD_DELAY

  private def walk(height: Int, rewardsOnly: Boolean, p2pkOnly: Boolean)
                  (consume: (NodeBox, Boolean) => Boolean): Unit = {
    val started = System.nanoTime()
    var stop = false
    def pages(fetch: Paging => Seq[NodeBox], reward: Boolean): Unit = {
      var paging = Paging(0, PageSize)
      var exhausted = false
      while (!stop && !exhausted) {
        require(System.nanoTime() - started < MaxWalkNanos, "wallet inventory walk exceeded its budget")
        val page = fetch(paging)
        require(page.size <= PageSize, "wallet page exceeded the requested limit")
        val iterator = page.iterator
        while (iterator.hasNext && !stop) {
          val box = iterator.next()
          val signable = if (reward) wallet.rewardTrees.contains(box.ergoTree) else wallet.signableTrees.contains(box.ergoTree)
          if (signable) stop = consume(box, reward)
        }
        exhausted = page.size < PageSize
        paging = paging.next
      }
    }
    if (!p2pkOnly && api.indexerEnabled) wallet.rewardTrees.keysIterator.foreach { tree =>
      if (!stop) pages(p => api.unspentBoxesByErgoTree(tree, p, SortDirection.Asc,
        MempoolOptions(includeUnconfirmed = false, excludeMempoolSpent = true)).get.map(_.box), reward = true)
    }
    if (!rewardsOnly && !stop) pages(p => api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, p).get.map(_.box), reward = false)
  }

  def snapshot(height: Int, excluded: Set[String]): Snapshot = {
    var boxes = Vector.empty[WalletDescriptor]
    var bytes = 0L
    var truncated = false
    var spendable, locked, unlocked = BigInt(0)
    var lockedCount, unlockedCount = 0
    var nextUnlock = Option.empty[Int]
    walk(height, rewardsOnly = false, p2pkOnly = false) { (box, reward) =>
      val entry = descriptor(box, reward)
      if (boxes.size < MaxDescriptors && bytes + entry.retainedBytes <= MaxDescriptorBytes) {
        boxes :+= entry
        bytes += entry.retainedBytes
      } else truncated = true
      if (!excluded.contains(box.boxId)) {
        val value = BigInt(net(box)).max(BigInt(0))
        if (!reward || matured(box, height)) spendable += value
        if (reward && matured(box, height)) { unlocked += value; unlockedCount += 1 }
        else if (reward) {
          locked += value
          lockedCount += 1
          val delay = math.max(0, box.creationHeight + MINER_REWARD_DELAY + 1 - height)
          nextUnlock = Some(nextUnlock.fold(delay)(math.min(_, delay)))
        }
      }
      false
    }
    Snapshot(boxes, complete = true, truncated, height, spendable, locked, unlocked,
      lockedCount, unlockedCount, nextUnlock)
  }

  def select(ctx: BlockchainContext, erg: Long, required: Seq[Token], excluded: Set[String],
             single: Boolean, p2pkOnly: Boolean, rewardsOnly: Boolean,
             known: Vector[NodeBox] = Vector.empty): Vector[InputUTXO] = {
    require(erg >= 0 && required.forall(_.amount > 0), "invalid wallet requirement")
    require(!mainnet || !required.exists(_.id.toString == MainnetEip27Constants.TokenId),
      "re-emission tokens cannot be transferred")
    val needs = required.groupBy(_.id.toString).map { case (id, ts) => id -> ts.map(t => BigInt(t.amount)).sum }
    var selected = Vector.empty[NodeBox]
    var found = false
    def tokens(boxes: Vector[NodeBox]): Map[String, BigInt] = boxes.flatMap(_.assets)
      .filterNot(t => mainnet && t.tokenId == MainnetEip27Constants.TokenId)
      .groupBy(_.tokenId).map { case (id, ts) => id -> ts.map(t => BigInt(t.amount)).sum }
    def covers(boxes: Vector[NodeBox]): Boolean = {
      val held = tokens(boxes)
      val value = boxes.map(b => BigInt(net(b))).sum
      val surplus = held.exists { case (id, n) => n > needs.getOrElse(id, BigInt(0)) }
      value >= BigInt(erg) + (if (surplus) UTXO.MIN_CHANGE else 0L) &&
        needs.forall { case (id, n) => held.getOrElse(id, BigInt(0)) >= n }
    }
    def consume(box: NodeBox, reward: Boolean): Boolean = {
      if (!excluded.contains(box.boxId) && !selected.exists(_.boxId == box.boxId) &&
        (!reward || matured(box, ctx.getHeight)) && net(box) > 0) {
        if (single) { if (covers(Vector(box))) { selected = Vector(box); found = true } }
        else {
          val held = tokens(selected)
          val advancesTokens = box.assets.exists(t =>
            held.getOrElse(t.tokenId, BigInt(0)) < needs.getOrElse(t.tokenId, BigInt(0)))
          val advancesValue = selected.map(b => BigInt(net(b))).sum < BigInt(erg) + UTXO.MIN_CHANGE
          if (advancesTokens || advancesValue) selected :+= box
          if (selected.size > MaxInputs) {
            val held = tokens(selected)
            def score(b: NodeBox): (BigInt, Long) = {
              val needed = b.assets.map { t =>
                val n = needs.getOrElse(t.tokenId, BigInt(0))
                (n - (held.getOrElse(t.tokenId, BigInt(0)) - t.amount)).max(BigInt(0))
              }.sum
              needed -> net(b)
            }
            val discard = selected.indices.minBy(i => score(selected(i)))
            selected = selected.patch(discard, Nil, 1)
          }
          found = covers(selected)
        }
      }
      found
    }
    known.iterator.takeWhile(_ => !found).foreach(b => consume(b, reward = false))
    if (!found) walk(ctx.getHeight, rewardsOnly, p2pkOnly)(consume)
    require(found, "wallet cannot cover this request within the input budget")
    var i = 0
    while (i < selected.size) {
      val without = selected.patch(i, Nil, 1)
      if (covers(without)) selected = without else i += 1
    }
    selected.map { box =>
      val input = box.toInputUTXO(ctx)
      require(input.id.toString == box.boxId, "wallet box identity differs from its contents")
      require(input.bytes.length <= MaxInputBytes, "wallet input exceeds hydration byte budget")
      input
    }
  }
}
