package transactions.batching.arb

import arbitrage.contracts.ArbStakeContract
import configs.{ArbConfig, NodeContext}
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.NetworkType
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import work.lithos.mutations.Contract

import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/**
 * The arb stake: the box at this wallet's arb address that cycles in this miner's own blocks start from and
 * return to. The address is the wallet's first key under a script of its own, so the node wallet never lists the
 * box and no wallet selection, this client's or another program's, can take it.
 *
 * The stake is followed by box id: each cycle's output is known before its block, so once a block spends the
 * stake the output that replaced it is found with one read that does not wait on the node's index. The address
 * is read only when no id is known, which is at start and after a stake is lost. Every method here reads the
 * node, so none may run on an actor's thread.
 *
 * @param target `batching.arb.stakeNanoErg` while arb is active, else 0, which hands out no stake and reads nothing
 */
@Singleton
class ArbStake(api: () => NodeApi, val contract: Contract, network: NetworkType, val target: Long) {

  @Inject def this(nodeContext: NodeContext, config: Configuration) =
    this(() => nodeContext.getNodeApi, ArbStakeContract.forKey(nodeContext.getNodeWallet.p2pk.getPublicKey),
      nodeContext.getNetwork, ArbConfig.stakeTarget(config))

  import ArbStake._

  private val logger: Logger = LoggerFactory.getLogger("ArbStake")

  val tree: String = contract.ergoTreeHex
  lazy val address: String = contract.address(network).toString

  /** Guards every write below, and holds one stake check at a time so two builds never read for the same block. */
  private val lock = new Object
  @volatile private var held: Option[Held] = None
  /** The height and parent block the stake was last checked for. */
  @volatile private var checked: Option[(Int, String)] = None
  @volatile private var stopped = false
  private var warnedEmpty = false
  private var warnedNoIndex = false

  /** Whether cycles may run on the stake: arb is active and the stake was not reclaimed in this process. */
  def active: Boolean = target > 0L && !stopped

  /** Whether a reclaim stopped arb for the rest of this process. */
  def paused: Boolean = stopped

  /** The stake as last found unspent, for display. */
  def current: Option[NodeBox] = held.map(_.box)

  /**
   * The stake a block at `height` on `parentId` may spend: found in the confirmed UTXO set once that parent was
   * the tip. Checked once per height and parent, so a new parent at the same height is read again. None when
   * there is no stake, arb is off or paused, or the node read failed, which changes nothing held.
   */
  def at(height: Int, parentId: String): Option[NodeBox] =
    if (!active) None
    else lock.synchronized {
      if (!active) None
      else if (checked.contains(height -> parentId)) held.map(_.box)
      else Try(check(height)) match {
        case Success(next) =>
          held = next
          checked = Some(height -> parentId)
          if (next.isEmpty && !warnedEmpty) {
            warnedEmpty = true
            logger.warn(s"No box of at least $MinStakeNanoErg nanoERG without tokens is at the arb address $address, " +
              "so no arb runs. batching.arb.autoReserve sends one from this wallet when it holds twice " +
              "batching.arb.stakeNanoErg; ERG sent to that address by hand is used as well")
          }
          next.map(_.box)
        case Failure(ex) =>
          logger.warn(s"Could not read the arb stake for block $height, offering no cycles: ${ex.getMessage}")
          None
      }
    }

  /**
   * Notes `successor`, the output of a cycle built for `height` that returns the stake, as the box to look for
   * once a block spends `replaces`. Answers whether it was noted: a cycle whose output is not followed must not
   * be offered, since the stake would be lost track of in the block that carried it.
   */
  def handOn(replaces: String, successor: String, height: Int): Boolean = lock.synchronized {
    held match {
      case Some(h) if active && h.box.boxId == replaces && checked.exists(_._1 >= height) &&
        successor.matches("[0-9a-f]{64}") =>
        val known = h.successors.indexWhere(_._1 == successor)
        if (known >= 0) {
          val builtFor = math.max(height, h.successors(known)._2)
          held = Some(h.copy(successors = h.successors.updated(known, successor -> builtFor)))
          true
        } else if (h.successors.size < MaxSuccessors) {
          held = Some(h.copy(successors = h.successors :+ (successor -> height)))
          true
        } else false
      case _ => false
    }
  }

  /** Stops handing out the stake and forgets it, so a reclaim can spend it. Lasts until `resume` or a restart. */
  def pause(): Unit = lock.synchronized {
    stopped = true
    held = None
    checked = None
  }

  /** Undoes `pause` for a reclaim that found nothing to spend. */
  def resume(): Unit = lock.synchronized { stopped = false }

  /**
   * Unspent boxes at the arb address, newest first, up to [[MaxBoxes]]. Read from the node's index, which can
   * trail the tip by a block, so a box to be spent is confirmed by id first.
   */
  def boxes(mempool: MempoolOptions = MempoolOptions.ConfirmedOnly): Try[Vector[NodeBox]] = Try {
    require(api().indexerEnabled, "the node's extra index is off (ergo.node.extraIndex)")
    var found = Vector.empty[NodeBox]
    var paging = Paging(0, PageSize)
    var done = false
    while (!done) {
      val page = api().unspentBoxesByErgoTree(tree, paging, SortDirection.Desc, mempool).get
      require(page.size <= PageSize, "the node returned more boxes than the page asked for")
      found ++= page.map(_.box).filter(_.ergoTree == tree)
      done = page.size < PageSize || found.size >= MaxBoxes
      paging = paging.next
    }
    if (found.size >= MaxBoxes) {
      // The oldest page as well, so boxes sent after a stake cannot be the only ones read
      val oldest = api().unspentBoxesByErgoTree(tree, Paging(0, PageSize), SortDirection.Asc, mempool).get
      found = found.take(MaxBoxes) ++ oldest.map(_.box).filter(b => b.ergoTree == tree && !found.exists(_.boxId == b.boxId))
      logger.warn(s"The arb address $address holds $MaxBoxes boxes or more; only the newest $MaxBoxes and the oldest " +
        s"$PageSize are read")
    }
    found
  }

  /**
   * Where the stake stands after the block before `height`: still unspent, replaced by the newest cycle output
   * that exists, back at the box before it when a reorg undid the block that spent it, or whatever the address
   * holds. Throws when a node read fails.
   */
  private def check(height: Int): Option[Held] = {
    def unspent(id: String): Option[NodeBox] = api().boxById(id).get.filter(box => box.boxId == id && usable(box, tree))
    held match {
      case None => find()
      case Some(h) =>
        // No block spent the stake before `height`, so outputs of cycles built for earlier heights never existed
        unspent(h.box.boxId).map(box => h.copy(box = box, successors = h.successors.filter(_._2 >= height)))
          .orElse(h.successors.reverse.sortBy(-_._2).iterator.map(s => unspent(s._1)).collectFirst {
            case Some(box) =>
              logger.info(s"Arb stake ${h.box.boxId} was spent by a block; ${box.boxId} (${box.value} nanoERG) is " +
                "the stake now")
              Held(box, Vector.empty, Some(h.box.boxId))
          })
          .orElse(h.previous.flatMap(unspent).map { box =>
            logger.warn(s"Arb stake ${h.box.boxId} is gone and ${box.boxId}, the stake before it, is unspent again " +
              "after a reorg; cycles start from it")
            Held(box, Vector.empty, None)
          })
          .orElse {
            logger.warn(s"Arb stake ${h.box.boxId} is no longer unspent and no cycle output replaced it; reading " +
              s"the arb address $address for another")
            find()
          }
    }
  }

  /**
   * The stake in use, when the confirmed UTXO set still holds it. Needs no index, so it stands when the
   * address read cannot reach the stake.
   */
  def known(): Try[Option[NodeBox]] = Try {
    held.map(_.box.boxId)
      .flatMap(id => api().boxById(id).get.filter(box => box.boxId == id && usable(box, tree)))
  }

  /** The largest usable box at the arb address that the confirmed UTXO set still holds. */
  private def find(): Option[Held] =
    if (!api().indexerEnabled) {
      if (!warnedNoIndex) {
        warnedNoIndex = true
        logger.warn("The node's extra index is off (ergo.node.extraIndex), so the arb address cannot be read and " +
          "no arb runs")
      }
      None
    } else boxes().get.filter(usable(_, tree)).sortBy(-_.value).iterator.take(MaxVerified)
      .map(box => api().boxById(box.boxId).get.filter(_.boxId == box.boxId))
      .collectFirst { case Some(box) =>
        warnedEmpty = false
        logger.info(s"Arb cycles in this miner's own blocks start from ${box.boxId} (${box.value} nanoERG) at the " +
          s"arb address $address")
        Held(box, Vector.empty, None)
      }
}

object ArbStake {

  /** The least ERG a box at the arb address must hold to be the stake, so dust sent there is never taken for it. */
  final val MinStakeNanoErg: Long = 100000000L

  /** Cycle outputs followed at once. A cycle past it is refused, since its output would not be looked for. */
  private[arb] final val MaxSuccessors = 256

  private final val PageSize = 100

  /** Boxes one read of the arb address returns. */
  final val MaxBoxes = 1000

  /** Boxes from an address read confirmed by id before giving up on finding a stake among them. */
  private final val MaxVerified = 8

  /**
   * @param successors outputs of cycles built on `box` and the height each was built for, oldest first
   * @param previous   the stake a block replaced with `box`, which a reorg undoing that block brings back
   */
  private final case class Held(box: NodeBox, successors: Vector[(String, Int)], previous: Option[String])

  /** A box cycles can start from: at the stake script, ERG only, and not dust. */
  def usable(box: NodeBox, tree: String): Boolean =
    box.ergoTree == tree && box.assets.isEmpty && box.value >= MinStakeNanoErg
}
