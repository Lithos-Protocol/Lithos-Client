package transactions.batching.tracking

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import node.model.{NodeBox, NodeTransaction}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import scorex.crypto.hash.Blake2b256
import transactions.batching.ergodex.{ErgoDexContracts, ErgoDexPool}
import ergodexcl.ErgoDexClContracts
import sigmausd.SigmaUsdContracts
import transactions.batching.lithosdex.{DexContracts, LDDeployments}

import scala.util.Try

/**
 * The contract a tracked pool box sits at; a LithosDex one carries the deployment its own ids build. ErgoDEX CL
 * positions and their config box, and the SigmaUSD bank and the oracle pool box it reads, are followed beside the pools.
 */
sealed trait PoolDeployment

object PoolDeployment {
  case object ErgoDexNative extends PoolDeployment
  case object ErgoDexToken extends PoolDeployment
  final case class LithosDexNative(contracts: LDContracts) extends PoolDeployment
  final case class LithosDexToken(contracts: LDT2TContracts) extends PoolDeployment
  case object ErgoDexCl extends PoolDeployment
  case object ErgoDexClConfig extends PoolDeployment
  case object SigmaUsdBank extends PoolDeployment
  case object SigmaUsdOracle extends PoolDeployment

  /** Whether `deployment` is a DEX pool or CL position, which a swap moves, rather than a box one reads or the bank. */
  def dex(deployment: PoolDeployment): Boolean = deployment match {
    case ErgoDexClConfig | SigmaUsdBank | SigmaUsdOracle => false
    case _ => true
  }

  /** Protocol singletons (CL config, SigmaUSD bank and oracle) that must never be evicted by pool box caps. */
  def singleton(deployment: PoolDeployment): Boolean = !dex(deployment)
}

/** A confirmed pool box, the height of the block that created it, and its deployment. */
final case class TrackedBox(box: NodeBox, inclusionHeight: Int, deployment: PoolDeployment) {
  def boxId: String = box.boxId
  /** The token that identifies the box: the first, except the bank's NFT, which it holds third. */
  def nft: String = if (deployment == PoolDeployment.SigmaUsdBank) box.assets(2).tokenId else box.assets.head.tokenId
}

/**
 * Says whether a box is a pool this client tracks: at an ErgoDEX pool script and parsing as that shape, or at a
 * LithosDex pool template and verified as the deployment its own ids build. On mainnet also ErgoDEX CL positions, at
 * the pinned position script with their NFT first at one unit, and the box holding the CL config token, whatever script
 * it is at; the SigmaUSD bank, at its pinned script holding its NFT, and the box holding its oracle pool's NFT, whatever
 * script it is at. No node reads; a vault's standing is the consumer's to check. ErgoDEX boxes kept share one copy of
 * their script's tree.
 */
final class PoolShapes(val network: NetworkType) {

  private val canonical = DexContracts(network)
  private val n2tTemplate = canonical.liquidityPool.ergoTree.template
  // Every token/token deployment shares one template, so any ids give it
  private val t2tTemplate =
    LDT2TContracts(canonical.poolNFT, canonical.poolNFT, canonical.poolNFT).liquidityPool.ergoTree.template
  private val n2tTemplateHex = Hex.toHexString(n2tTemplate)
  private val t2tTemplateHex = Hex.toHexString(t2tTemplate)

  /** `box` as a tracked pool created at `height`, or None when it is not one. */
  def read(box: NodeBox, height: Int): Option[TrackedBox] = Try {
    val tree = box.ergoTree
    if (tree == ErgoDexContracts.NativePoolErgoTree)
      ErgoDexPool.native(box).map(_ => TrackedBox(box.copy(ergoTree = ErgoDexContracts.NativePoolErgoTree), height,
        PoolDeployment.ErgoDexNative))
    else if (tree == ErgoDexContracts.TokenPoolErgoTree)
      ErgoDexPool.token(box).map(_ => TrackedBox(box.copy(ergoTree = ErgoDexContracts.TokenPoolErgoTree), height,
        PoolDeployment.ErgoDexToken))
    else if (tree.endsWith(n2tTemplateHex)) {
      val assets = box.assets.map(a => a.tokenId -> a.amount)
      val contracts =
        if (box.assets.headOption.exists(_.tokenId == canonical.poolNFT.toString) &&
          tree.equalsIgnoreCase(canonical.liquidityPool.ergoTreeHex)) Some(canonical)
        else LDDeployments.verify(network, tree, assets)
      contracts.map(c => TrackedBox(box, height, PoolDeployment.LithosDexNative(c)))
    } else if (tree.endsWith(t2tTemplateHex))
      LDDeployments.verifyT2T(tree, box.assets.map(a => a.tokenId -> a.amount))
        .map(c => TrackedBox(box, height, PoolDeployment.LithosDexToken(c)))
    else if (network == NetworkType.MAINNET && tree.equalsIgnoreCase(ErgoDexClContracts.PositionErgoTree) &&
      transactions.batching.arb.ErgoDexClArbPool.validPositionBox(box))
      Some(TrackedBox(box, height, PoolDeployment.ErgoDexCl))
    else if (network == NetworkType.MAINNET &&
      box.assets.headOption.exists(a => a.tokenId == ErgoDexClContracts.ConfigToken && a.amount == 1L))
      Some(TrackedBox(box, height, PoolDeployment.ErgoDexClConfig))
    else if (network == NetworkType.MAINNET && tree.equalsIgnoreCase(SigmaUsdContracts.BankErgoTree) &&
      box.assets.lift(2).exists(a => a.tokenId == SigmaUsdContracts.BankNft && a.amount == 1L))
      Some(TrackedBox(box, height, PoolDeployment.SigmaUsdBank))
    else if (network == NetworkType.MAINNET &&
      box.assets.headOption.exists(a => a.tokenId == SigmaUsdContracts.OracleNft && a.amount == 1L))
      Some(TrackedBox(box, height, PoolDeployment.SigmaUsdOracle))
    else None
  }.toOption.flatten

  /** Blake2b256 of each LithosDex pool template, which the node's index scans by. */
  val n2tTemplateHash: String = Hex.toHexString(Blake2b256.hash(n2tTemplate))
  val t2tTemplateHash: String = Hex.toHexString(Blake2b256.hash(t2tTemplate))
}

/**
 * What applying one block changed: the tracked boxes its transactions spent and the ids of those it added, so
 * the block can be undone after a fork. `parentId` is the block it was applied on.
 */
final case class AppliedBlock(height: Int, headerId: String, parentId: String, removed: Vector[TrackedBox],
                              added: Vector[String])

/**
 * Every unspent pool box as of block `headerId` at `height`, kept from blocks rather than scans. `byNft` lists
 * the boxes carrying each first token; a pool is served only while its NFT names one box. `history` holds the
 * newest blocks applied, at most [[TrackedPools.MaxHistory]], so a short fork rolls back instead of rescanning.
 * `cut` says a cap left a box out, so a pool missing here may still exist on chain. `scannedAt` is the height
 * of the scan this state grew from.
 */
final case class TrackedPools(height: Int, headerId: String, boxes: Map[String, TrackedBox],
                              byNft: Map[String, Set[String]], history: Vector[AppliedBlock], cut: Boolean,
                              scannedAt: Int) extends BlockKept[TrackedPools] {

  /** The one box carrying `nft`, if exactly one does. */
  def pool(nft: String): Option[TrackedBox] = byNft.get(nft) match {
    case Some(ids) if ids.size == 1 => boxes.get(ids.head)
    case _ => None
  }

  /** Every pool whose NFT names one box. */
  def pools: Iterator[TrackedBox] = byNft.valuesIterator.collect { case ids if ids.size == 1 => boxes(ids.head) }

  /** The block id this state holds for `at`, when `at` is the tip or a retained block or its parent. */
  def idAt(at: Int): Option[String] =
    if (at == height) Some(headerId)
    else history.find(_.height == at).map(_.headerId)
      .orElse(history.find(_.height == at + 1).map(_.parentId))

  /** The oldest height this state can roll back to. */
  def base: Int = history.headOption.map(_.height - 1).getOrElse(height)

  /**
   * This state with block `headerId` applied on it: each transaction in order drops the tracked boxes it spends,
   * then adds its outputs `shapes` reads as pools, so a pool spent twice in one block ends at its last box. Past
   * `cap` boxes a new pool box is left out and `cut` set; a successor is never refused, since its pool's box left
   * first, and protocol singletons are never refused by `cap`.
   */
  def applied(blockHeight: Int, blockId: String, parentId: String, txs: Seq[NodeTransaction], shapes: PoolShapes,
              cap: Int): TrackedPools = {
    require(parentId == headerId && blockHeight == height + 1,
      s"block $blockId at $blockHeight does not follow $headerId at $height")
    var kept = boxes
    var nonSingletons = boxes.valuesIterator.count(b => !PoolDeployment.singleton(b.deployment))
    var nfts = byNft
    var removed = Vector.empty[TrackedBox]
    var added = Vector.empty[String]
    var short = cut
    txs.foreach { tx =>
      tx.inputs.foreach(input => kept.get(input.boxId).foreach { gone =>
        kept -= gone.boxId
        if (!PoolDeployment.singleton(gone.deployment)) nonSingletons -= 1
        nfts = TrackedPools.without(nfts, gone)
        removed :+= gone
      })
      tx.outputs.foreach(output => shapes.read(output, blockHeight).foreach { found =>
        val isSingleton = PoolDeployment.singleton(found.deployment)
        // Protocol singletons bypass the cap; non-singleton pools are bounded by `cap` (excluding singletons) and
        // any non-singleton NFT already held by 2+ boxes (proven fungible) stops consuming cap slots.
        if (!isSingleton && (nonSingletons >= cap || nfts.getOrElse(found.nft, Set.empty).size >= 2)) short = true
        else if (!kept.contains(found.boxId)) {
          kept += found.boxId -> found
          if (!isSingleton) nonSingletons += 1
          nfts = TrackedPools.plus(nfts, found)
          added :+= found.boxId
        }
      })
    }
    TrackedPools(blockHeight, blockId, kept, nfts,
      (history :+ AppliedBlock(blockHeight, blockId, parentId, removed, added)).takeRight(TrackedPools.MaxHistory), short,
      scannedAt)
  }

  /** This state as it stood at `at`, undoing retained blocks above it, or None when `at` is below [[base]]. */
  def rolledBackTo(at: Int): Option[TrackedPools] =
    if (at < base || at > height) None
    else Some(history.reverseIterator.takeWhile(_.height > at).foldLeft(this) { (state, block) =>
      val added = block.added.toSet
      var kept = state.boxes
      var nfts = state.byNft
      block.added.foreach(id => kept.get(id).foreach { box =>
        kept -= id
        nfts = TrackedPools.without(nfts, box)
      })
      // A box the block both added and spent was never tracked before it
      block.removed.filterNot(box => added.contains(box.boxId)).foreach { box =>
        kept += box.boxId -> box
        nfts = TrackedPools.plus(nfts, box)
      }
      TrackedPools(block.height - 1, block.parentId, kept, nfts, state.history.dropRight(1), state.cut, scannedAt)
    })
}

object TrackedPools {

  /** Blocks kept for undoing a fork. Deeper forks rescan. */
  final val MaxHistory = 16

  /** A state read from a scan at `height`, with no history. */
  def seeded(height: Int, headerId: String, found: Seq[TrackedBox], cut: Boolean): TrackedPools = {
    val boxes = found.map(box => box.boxId -> box).toMap
    TrackedPools(height, headerId, boxes, boxes.values.foldLeft(Map.empty[String, Set[String]])(plus),
      Vector.empty, cut, height)
  }

  private def plus(nfts: Map[String, Set[String]], box: TrackedBox): Map[String, Set[String]] =
    nfts.updated(box.nft, nfts.getOrElse(box.nft, Set.empty) + box.boxId)

  private def without(nfts: Map[String, Set[String]], box: TrackedBox): Map[String, Set[String]] = {
    val left = nfts.getOrElse(box.nft, Set.empty) - box.boxId
    if (left.isEmpty) nfts - box.nft else nfts.updated(box.nft, left)
  }
}
