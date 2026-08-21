package transactions.dex

import lithosdex.LDHelpers
import node.MutationConversions._
import node.NodeApi
import node.model.SortDirection.Asc
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.bouncycastle.util.encoders.Hex
import work.lithos.mutations.InputUTXO

import scala.util.{Failure, Success, Try}

/**
 * Finding the LithosDex boxes on chain, through the node and nothing else.
 * TODO: Change to using state updater in synchronizer eventually?
 */
object LDBoxes {

  /** Node index pages. The guard address holds one box per live provision, so this has to page. */
  private final val PageSize = 200

  /** How many index pages a LISTING will walk before giving up. */
  private final val MaxPages = 50

  /**
   * How many index pages a history walk covers: 100,000 transitions.
   *
   * Far higher than a listing's ceiling because the two bound different things. A listing is over
   * live boxes, and ten thousand live provisions is already implausible; the pool's lineage is every
   * box it has ever occupied and only grows. Hitting this reports a partial series rather than
   * failing, so the ceiling is a memory bound, not a correctness one.
   */
  private final val MaxHistoryPages = 500

  /**
   * Ceiling on the per-height header lookups a history request will make to label its buckets, and
   * the widest span it will fetch as one slice instead.
   */
  private final val MaxTimestampLookups = 250
  private final val MaxSliceSpan = 1024

  /**
   * A provision box, read.
   *
   * @param entryX   R4, the accumulator this provision last settled at
   * @param ownerNFT R6, the token whose holder owns this provision
   * @param shares   R7, the LP shares it represents
   */
  case class Provision(box: InputUTXO, entryX: BigInt, entryY: BigInt, ownerNFT: ErgoId, shares: Long) {
    def id: ErgoId = box.id

    def boxId: String = box.id.toString

    def value: Long = box.value

    def createdHeight: Int = box.input.getCreationHeight
  }

  // ── the singletons ───────────────────────────────────────────────────────

  /**
   * The live pool box.
   *
   * Found by the pool NFT rather than by address, so it is still found if the address is recompiled
   * from different ids — and the ErgoTree is checked afterwards, so a box that merely carries the NFT
   * somewhere else is not mistaken for the pool.
   */
  def poolBox(ctx: BlockchainContext, nodeApi: NodeApi): InputUTXO =
    singletonBox(ctx, nodeApi, LDHelpers.getPoolNFT(ctx.getNetworkType),
      DexContracts(ctx).liquidityPool.ergoTreeHex, "liquidity pool")

  def vaultBox(ctx: BlockchainContext, nodeApi: NodeApi): InputUTXO =
    singletonBox(ctx, nodeApi, LDHelpers.getVaultNFT(ctx.getNetworkType),
      DexContracts(ctx).feeVault.ergoTreeHex, "fee vault")

  private def singletonBox(ctx: BlockchainContext,
                           nodeApi: NodeApi,
                           nft: ErgoId,
                           ergoTreeHex: String,
                           what: String): InputUTXO = {
    val found = nodeApi.unspentBoxesByTokenId(nft.toString, Paging(0, 20), mempool = MempoolOptions.WithMempool) match {
      case Success(boxes) => boxes.filter(_.ergoTree == ergoTreeHex)
      case Failure(ex) => throw indexUnavailable(s"the node index looking for the $what", ex)
    }
    found.headOption.map(_.toInputUTXO(ctx)).getOrElse(
      throw new NoSuchBoxException(
        s"no unspent $what box carrying $nft — has LithosDex been launched on this network?"))
  }

  // ── provisions ───────────────────────────────────────────────────────────

  /**
   * Every live provision box: locked under the guard and holding exactly one provision token.
   *
   * Mempool-aware, matching pool and vault discovery.
   */
  def provisionBoxes(ctx: BlockchainContext, nodeApi: NodeApi): Seq[Provision] = {
    // Hoisted, both of them. `ErgoId.toString` hex-encodes 32 bytes on every call, so leaving it in
    // the predicate throws away one string per box scanned.
    val provTokenId = LDHelpers.getProvToken(ctx.getNetworkType).toString
    val guard = DexContracts(ctx).provisionGuard.ergoTreeHex

    pagedIndex("provisions")(page =>
      nodeApi.unspentBoxesByErgoTree(guard, page, Asc, MempoolOptions.WithMempool))
      .filter(b => isProvision(b.ergoTree, b.assets.map(a => a.tokenId -> a.amount), guard, provTokenId))
      .map(b => readProvision(b.toInputUTXO(ctx)))
  }

  /** R4 entryX, R5 entryY, R6 ownerNFT, R7 shares. */
  def readProvision(box: InputUTXO): Provision = Provision(
    box = box,
    entryX = bigIntOf(box, 0),
    entryY = bigIntOf(box, 1),
    ownerNFT = ErgoId.create(Hex.toHexString(box.parseReg[sigma.Coll[Byte]](2).toArray)),
    shares = box.parseReg[Long](3))

  def bigIntOf(box: InputUTXO, idx: Int): BigInt =
    BigInt(box.registers(idx).getValue.asInstanceOf[sigma.data.CBigInt].wrappedValue)

  /**
   * Provisions this wallet can act on, the ones whose ownership NFT it holds.
   */
  def ownedProvisions(ctx: BlockchainContext, nodeApi: NodeApi): Seq[Provision] = {
    val held = walletTokenIds(nodeApi)
    provisionBoxes(ctx, nodeApi).filter(p => held.contains(p.ownerNFT.toString))
  }

  /**
   * One provision, resolved by its own id rather than by scanning every live provision.
   *
   * The shape of the UTXO is verified in case of an incorrect boxId being given.
   */
  def provisionById(ctx: BlockchainContext, nodeApi: NodeApi, boxId: String): Provision = {
    val guard = DexContracts(ctx).provisionGuard.ergoTreeHex
    val provTokenId = LDHelpers.getProvToken(ctx.getNetworkType).toString

    // A box being spent in the mempool is NOT missing — it has a successor the caller can act on.
    // Reported as its own condition so the API can answer "it moved, read again" rather than the
    // 404 that tells a UI the position is gone.
    //
    // Checked first and separately from the read below, which leaves a one-round-trip window in
    // which a spend can arrive after this returns. That window cannot be closed from here: the node
    // has no endpoint that reads a box and its mempool status atomically. Narrowing it is all this
    // does; the builder still has to survive losing the race.
    nodeApi.unconfirmedInputByBoxId(boxId) match {
      case Success(Some(tx)) =>
        throw new BoxBeingSpentException(
          s"provision $boxId is already being spent by unconfirmed transaction ${tx.id} — " +
            "read it again to find its successor")
      case Failure(ex) => throw indexUnavailable("the mempool", ex)
      case Success(None) => ()
    }

    val confirmed = nodeApi.indexedBoxById(boxId) match {
      case Success(found) => found.filterNot(_.isSpent)
      case Failure(ex) => throw indexUnavailable("the node index", ex)
    }

    val box = confirmed.map(_.toInputUTXO(ctx)).orElse {
      nodeApi.unconfirmedOutputByBoxId(boxId) match {
        case Success(found) => found.map(_.toInputUTXO(ctx))
        case Failure(ex) => throw indexUnavailable("the mempool", ex)
      }
    }.getOrElse(throw new NoSuchBoxException(
      s"no live provision box $boxId — it may have been spent, or it was never a provision"))

    if (!isProvision(box.contract.ergoTreeHex, box.tokens.map(t => t.id.toString -> t.amount),
      guard, provTokenId))
      throw new NoSuchBoxException(
        s"box $boxId is not a LithosDex provision — it is not under the provision guard, or it does " +
          "not hold exactly one provision token")

    readProvision(box)
  }

  /** A provision is its guard script plus exactly one provision token in the first slot. */
  private def isProvision(ergoTreeHex: String,
                          assets: Seq[(String, Long)],
                          guard: String,
                          provTokenId: String): Boolean =
    ergoTreeHex == guard &&
      assets.headOption.exists { case (id, amount) => id == provTokenId && amount == 1L }

  /** Token ids the wallet holds any amount of. */
  def walletTokenIds(nodeApi: NodeApi): Set[String] =
    nodeApi.walletBalances() match {
      case Success(b) => b.assets.filter(_.amount > 0).map(_.tokenId).toSet
      case Failure(ex) => throw indexUnavailable("the node wallet — is it unlocked?", ex)
    }

  // ── history ──────────────────────────────────────────────────────────────

  /**
   * Every box the pool NFT has ever sat in, oldest first.
   */
  /**
   * One pool box reduced to what a history point needs.
   *
   * Decoded straight off the indexed box. Building an `InputUTXO` for each would construct a full
   * appkit box — ErgoTree, tokens, every register — to extract a handful of numbers, and the pool's
   * lineage is one box per transaction that has ever touched it.
   *
   * @param txId the transaction that CREATED this box, which is the transition that produced it
   */
  case class PoolSnapshot(height: Int,
                          globalIndex: Long,
                          boxId: String,
                          txId: String,
                          reservesX: Long,
                          reservesY: Long,
                          pendingX: Long,
                          pendingY: Long,
                          accX: BigInt,
                          accY: BigInt,
                          supply: Long)

  /**
   * What moved between two consecutive pool boxes.
   *
   * Only a swap leaves the supply alone while moving the reserves: a deposit and a redemption both
   * change supply, a flush zeroes pending without touching reserves, and a resize does both. So
   * "supply unchanged and reserves moved" identifies a swap without having to read the spending
   * transaction.
   *
   * `amountIn` is what the trader handed over, which is the reserve increase PLUS the fee that went
   * to pending on the same side — the pool splits one input between the two.
   */
  case class SwapTransition(txId: String,
                            height: Option[Int],
                            ergIn: Boolean,
                            amountIn: Long,
                            amountOut: Long)

  def classifySwap(prev: PoolSnapshot, next: PoolSnapshot, height: Option[Int]): Option[SwapTransition] = {
    val dX = next.reservesX - prev.reservesX
    val dY = next.reservesY - prev.reservesY
    // Two independent tests, and it is worth knowing that either alone catches the operations the
    // pool actually performs. A deposit, a redemption and a resize all move BOTH reserves the same
    // way, so the direction test below excludes them; the supply test excludes them a second time,
    // and additionally excludes a shape no legitimate operation produces — supply moving while the
    // reserves move against each other. That shape can only reach here from a box read off chain,
    // which is exactly the input this client does not get to trust.
    if (next.supply != prev.supply) None
    else if (dX > 0 && dY < 0)
      Some(SwapTransition(next.txId, height, ergIn = true,
        amountIn = dX + (next.pendingX - prev.pendingX), amountOut = -dY))
    else if (dY > 0 && dX < 0)
      Some(SwapTransition(next.txId, height, ergIn = false,
        amountIn = dY + (next.pendingY - prev.pendingY), amountOut = -dX))
    // A flush moves pending to the vault and leaves both reserves alone, so it lands here.
    else None
  }

  /**
   * The pool's box lineage as history points, and whether the walk saw all of it.
   *
   * The pool NFT is a singleton, so the boxes that have held it ARE the pool's history — each one
   * carrying the accumulators and the supply as they stood when it was included.
   *
   * Two things make this survive a long-lived pool, and both were defects before:
   *
   *  - **It folds.** Each page is decoded to a `PoolSnapshot` and dropped, so what is retained is
   *    four numbers per transition rather than a materialised box.
   *  - **It reports truncation instead of failing.** The walk has a page ceiling, and hitting one
   *    used to raise a 503 — which meant every history request would begin failing permanently once
   *    the pool had seen enough trades, and never recover. A partial series with `complete = false`
   *    is a usable answer; a permanent error is not.
   *
   * Ordering is by `(inclusionHeight, globalIndex)`. Several transitions land in one block routinely
   * — a swap and the flush behind it — and reading them in the wrong order makes the accumulator
   * step backwards, which the fee inversion floors to zero and loses.
   */
  def poolHistory(ctx: BlockchainContext, nodeApi: NodeApi): (Seq[PoolSnapshot], Boolean) = {
    val nft = LDHelpers.getPoolNFT(ctx.getNetworkType).toString
    val ergoTreeHex = DexContracts(ctx).liquidityPool.ergoTreeHex

    var loaded = Vector.empty[PoolSnapshot]
    var paging = Paging(0, PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxHistoryPages) {
      nodeApi.boxesByTokenId(nft, paging).map(_.items) match {
        case Success(page) =>
          loaded ++= page.filter(_.ergoTree == ergoTreeHex).flatMap(readSnapshot)
          exhausted = page.size < paging.limit
          paging = paging.next
        case Failure(ex) =>
          throw indexUnavailable(s"the node index reading pool history " +
            s"(stopped after ${loaded.size} transition(s))", ex)
      }
      pages += 1
    }
    (loaded.sortBy(s => (s.height, s.globalIndex)), exhausted)
  }

  /**
   * R4 liquidity, R6 pending, R7 accX, R8 accY — read from the node's hex registers directly.
   *
   * Reserves are the box's balances LESS what it is holding for the vault, matching how the pool
   * itself reads them: the value covers `reservesX + pendingX` and the token entry
   * `reservesY + pendingY`.
   *
   * `None` rather than a throw for a box that does not decode: the lineage is filtered by ErgoTree,
   * so anything reaching here should be a pool box, but a history request is not the place to fail
   * a whole series over one unreadable entry.
   */
  private def readSnapshot(b: IndexedBox): Option[PoolSnapshot] =
    Try {
      def reg(i: Int): ErgoValue[_] =
        ErgoValue.fromHex(b.box.additionalRegisters.get(i).getOrElse(
          throw new NoSuchBoxException(s"pool box ${b.boxId} has no R$i")))

      val pending = reg(6).getValue.asInstanceOf[sigma.Coll[Long]].toArray
      PoolSnapshot(
        height = b.inclusionHeight,
        globalIndex = b.globalIndex,
        boxId = b.boxId,
        txId = b.box.transactionId,
        reservesX = b.value - pending(0),
        reservesY = b.assets(1).amount - pending(1),
        pendingX = pending(0),
        pendingY = pending(1),
        accX = BigInt(reg(7).getValue.asInstanceOf[sigma.data.CBigInt].wrappedValue),
        accY = BigInt(reg(8).getValue.asInstanceOf[sigma.data.CBigInt].wrappedValue),
        supply = LDHelpers.LOCKED_LP - reg(4).getValue.asInstanceOf[Long])
    }.toOption

  /** The same decode, for a box that is still in the mempool and so has no height or global index. */
  private def readMempoolSnapshot(b: NodeBox): Option[PoolSnapshot] =
    readSnapshot(IndexedBox(box = b, address = "", inclusionHeight = -1, globalIndex = -1L))

  /**
   * Swaps sitting in the mempool, each with the pool box it spends.
   *
   * A mempool pool box has no height, so its predecessor cannot be found by ordering — it is found
   * by id: whichever known pool box this transaction spends. That resolves a chain of any depth
   * without assuming the node returns them in order, and it drops a transaction whose predecessor
   * is neither the confirmed tip nor another mempool output.
   *
   * @param known the confirmed pool boxes to chain onto, keyed by box id
   */
  def mempoolSwaps(ctx: BlockchainContext,
                   nodeApi: NodeApi,
                   known: Map[String, PoolSnapshot]): Seq[SwapTransition] = {
    val nft = LDHelpers.getPoolNFT(ctx.getNetworkType).toString
    val ergoTreeHex = DexContracts(ctx).liquidityPool.ergoTreeHex

    val txs = nodeApi.unconfirmedTransactionsByErgoTree(ergoTreeHex, Paging(0, PageSize)) match {
      case Success(found) => found
      case Failure(ex) => throw indexUnavailable("the mempool reading pool transactions", ex)
    }

    // Every pool box the mempool creates, so a chained swap can find its predecessor among them.
    val produced = txs.flatMap { tx =>
      tx.outputs
        .find(o => o.ergoTree == ergoTreeHex && o.assets.headOption.exists(_.tokenId == nft))
        .flatMap(o => readMempoolSnapshot(o).map(s => tx -> s))
    }
    val byId = known ++ produced.map { case (_, s) => s.boxId -> s }.toMap

    // Depth from the confirmed tip, so newest-first ordering survives a chain of several.
    def depth(s: PoolSnapshot, seen: Set[String]): Int =
      produced.find { case (_, out) => out.boxId == s.boxId } match {
        case None => 0
        case Some((tx, _)) =>
          tx.inputs.iterator.map(_.boxId).find(byId.contains) match {
            case Some(prevId) if !seen.contains(prevId) => 1 + depth(byId(prevId), seen + prevId)
            case _ => 1
          }
      }

    produced.flatMap { case (tx, out) =>
      tx.inputs.iterator.map(_.boxId).find(byId.contains)
        .flatMap(prevId => classifySwap(byId(prevId), out, None))
        // `tx.id`, not the output's own `transactionId`. The transaction being iterated IS the one
        // that created this box, so taking the id from anywhere else is an indirection that can only
        // be wrong — and a mempool response is not obliged to populate that field on its outputs.
        .map(swap => depth(out, Set(out.boxId)) -> swap.copy(txId = tx.id))
    }.sortBy(-_._1).map(_._2)
  }

  /**
   * Header timestamps for the given heights, used for graphing and statistics.
   */
  def timestampsAt(nodeApi: NodeApi, heights: Seq[Int]): Map[Int, Long] = {
    val wanted = heights.distinct.take(MaxTimestampLookups)
    if (wanted.isEmpty) return Map.empty

    // One call when the whole set fits in a single slice, which is the ordinary case — a chart of a
    // day or two spans a few hundred blocks. Falling back to one call per height for a wide range is
    // deliberate: a slice across a hundred thousand blocks returns a hundred thousand headers to
    // label a few dozen buckets, which costs the node far more than the round trips save.
    val span = wanted.max - wanted.min
    if (span <= MaxSliceSpan) {
      nodeApi.chainSlice(Some(wanted.min - 1), Some(wanted.max)) match {
        case Success(headers) =>
          val byHeight = headers.map(h => h.height -> h.timestamp).toMap
          wanted.flatMap(h => byHeight.get(h).map(h -> _)).toMap
        case Failure(_) => Map.empty
      }
    } else
      wanted.flatMap { h =>
        nodeApi.chainSlice(Some(h - 1), Some(h)) match {
          case Success(headers) => headers.find(_.height == h).map(header => h -> header.timestamp)
          case Failure(_)       => None
        }
      }.toMap
  }

  // ── paging ───────────────────────────────────────────────────────────────

  private def pagedIndex(what: String)(fetch: Paging => Try[Seq[IndexedBox]]): Seq[IndexedBox] =
    paged[IndexedBox](what)(fetch)

  /**
   * Walk an index endpoint to exhaustion, or say so.
   */
  private def paged[A](what: String)(fetch: Paging => Try[Seq[A]]): Seq[A] = {
    var loaded = Seq.empty[A]
    var paging = Paging(0, PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxPages) {
      fetch(paging) match {
        case Success(page) =>
          loaded = loaded ++ page
          exhausted = page.size < paging.limit
          paging = paging.next
        case Failure(ex) =>
          throw indexUnavailable(s"the node index reading $what (stopped after ${loaded.size} box(es))", ex)
      }
      pages += 1
    }
    if (!exhausted)
      throw new IndexTruncatedException(
        s"$what exceeds the ${MaxPages * PageSize}-box scan ceiling, so this answer would be partial")
    loaded
  }

  private def indexUnavailable(what: String, cause: Throwable): IndexUnavailableException =
    new IndexUnavailableException(
      s"could not reach $what — is the node up with extraIndex on? (${cause.getMessage})", cause)

  /** The node, its index or its mempool could not answer. Not a statement about the request. */
  class IndexUnavailableException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

  /** A listing that would have to be truncated to answer. Also not a statement about the request. */
  class IndexTruncatedException(msg: String) extends RuntimeException(msg)

  /**
   * The box a request named is not on chain.
   *
   * Its own type rather than `NoSuchElementException`, so the API's 404 means "this box is not
   * there" and nothing else. Matching the JDK type caught every accidental `.get` on a `None` as
   * well, and reported defects in this client as a missing box.
   */
  class NoSuchBoxException(msg: String) extends RuntimeException(msg)

  /**
   * The box exists and something else is already spending it.
   *
   * Distinct from [[NoSuchBoxException]] because the caller's next move is different: this one has a
   * successor to read, so it is a conflict to re-read rather than a 404 to give up on.
   */
  class BoxBeingSpentException(msg: String) extends RuntimeException(msg)
}
