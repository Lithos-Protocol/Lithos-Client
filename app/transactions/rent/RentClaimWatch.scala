package transactions.rent

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.NodeContext
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import state.messages.BlockInfo
import state.messages.StateFrameMessages.NewBlock
import state.synchronization.AutoSubscribable

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Reports a storage-rent claim the node built into a Lithos block this miner found.
 *
 * A node running its own rent collection adds a claim to every candidate and pays it to the
 * candidate's key, which in a Lithos block is the lender's. This client's own sweep goes ahead of
 * that claim when it is in the package; when it is not, the rent leaves the pool. Only the node's
 * config can stop that, so this says what to change. Runs whether this client's rent source is on
 * or not.
 */
class RentClaimWatch(nodeContext: NodeContext, stateFrame: ActorRef) extends Actor {

  import RentClaimWatch._

  private val logger: Logger = LoggerFactory.getLogger("RentClaimWatch")

  private case object Tick

  // Re-sent every tick so a restarted publisher cannot silently drop this subscriber.
  private val ticker: Cancellable = context.system.scheduler.scheduleWithFixedDelay(
    Duration.Zero, SubscribeInterval, self, Tick)(context.dispatcher)

  /** This miner's holding script and R8, read once both are known; no node call. */
  private var identity: Option[Identity] = None

  override def postStop(): Unit = ticker.cancel()

  private def ownIdentity: Option[Identity] = identity.orElse {
    identity = Try(Identity(
      transactions.ProtocolContracts.forNetwork(nodeContext.getNetwork).holding.ergoTreeHex,
      HashRegisterPrefix + Hex.toHexString(nodeContext.getNodeWallet.contract.hashedPropBytes))).toOption
    identity
  }

  override def receive: Receive = {
    case Tick => stateFrame ! AutoSubscribable.AutoSubscribe(self)

    case NewBlock(block) =>
      Try(ownIdentity.toSeq.flatMap(nodeClaims(block, _))) match {
        case Success(claims) => claims.foreach(report)
        case Failure(ex) => logger.debug(s"Could not read block ${block.height} for rent claims: ${ex.getMessage}")
      }
  }

  private def report(claim: NodeClaim): Unit =
    logger.error(s"Block ${claim.height} (${claim.blockId}), found by this miner, carries a storage-rent " +
      s"claim the node built: ${claim.boxes} boxes, ${claim.nanoErg} nanoERG of rent paid to the lender's key " +
      s"instead of the pool (tx ${claim.txId}). Set ergo.node.storageRentCollection = false in the node's " +
      "config: the node pays its own rent claims to the candidate's key, and in a Lithos block that key is " +
      "the lender's")
}

object RentClaimWatch {

  final val SubscribeInterval: FiniteDuration = 30.seconds

  /** A serialized `Coll[Byte]` of 32 bytes, as holding R8 carries the finder's hash. */
  private final val HashRegisterPrefix = "0e20"

  /** A serialized `SigmaProp` register holding one public key: `08cd` and 33 bytes. */
  private final val ProveDlogRegisterLength = 70

  /** The holding script, and the R8 a holding box carries when this miner found its block. */
  final case class Identity(holdingTree: String, finderR8: String)

  /** A rent claim paying the lender's key in a block this miner found. */
  final case class NodeClaim(height: Int, blockId: String, txId: String, nanoErg: Long, boxes: Int)

  /**
   * Rent claims in `block` that pay the lender rather than the pool, when `self` found it.
   *
   * The block is this miner's when its genesis output 0 is a holding box carrying `self`'s R8; the
   * lender's key is the collateral box's R5. A claim is a transaction with a var-127 input whose
   * last output sits at that key and is named by no input. This client's sweep never ends on an
   * unnamed output, so its own collection is never reported. Missed: a node claim with no
   * recreation, which carries no unnamed output.
   */
  def nodeClaims(block: BlockInfo, self: Identity): Seq[NodeClaim] = {
    val genesis = block.txs.find(_.outputs.headOption.exists(out =>
      out.ergoTree.equalsIgnoreCase(self.holdingTree) &&
        out.registers.lift(4).exists(_.equalsIgnoreCase(self.finderR8))))
    for {
      g <- genesis.toSeq
      lenderTree <- g.inputs.headOption.flatMap(in => block.inputBox(in.id)).flatMap(_.registers.lift(1))
        .filter(r5 => r5.length == ProveDlogRegisterLength && r5.toLowerCase.startsWith("08cd"))
        .map("00" + _.toLowerCase).toSeq
      tx <- block.txs if tx.id != g.id
      rentInputs = tx.inputs.flatMap(_.spendingProof.flatMap(_.ext.get(StorageIndexVar)))
      if rentInputs.nonEmpty
      last <- tx.outputs.lastOption.toSeq
      if last.ergoTree.equalsIgnoreCase(lenderTree) &&
        !rentInputs.flatMap(storageIndex).contains(tx.outputs.size - 1)
    } yield NodeClaim(block.height, block.id, tx.id, last.value, rentInputs.size)
  }

  /** The context variable an input spent under the storage-rent rule names its output in. */
  private final val StorageIndexVar = "127"

  /** The output index var 127 names: a serialized `Short`, type code 3 then a zigzag VLQ. */
  def storageIndex(hex: String): Option[Int] = Try {
    val bytes = Hex.decode(hex)
    require(bytes.length >= 2 && bytes(0) == 3)
    var value = 0L
    var shift = 0
    var i = 1
    var more = true
    while (more) {
      require(i < bytes.length && shift < 21)
      val b = bytes(i) & 0xff
      value |= (b & 0x7fL) << shift
      more = (b & 0x80) != 0
      shift += 7
      i += 1
    }
    ((value >>> 1) ^ -(value & 1)).toInt
  }.toOption
}
