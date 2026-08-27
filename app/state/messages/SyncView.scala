package state.messages

import lfsm.states.{MinerTree, NISPTree}
import org.ergoplatform.sdk.ErgoId
import state.messages.SyncMessages.{Starting, SyncCursor, SyncStatus}

/** Whether one synchronized capability can be used, and why not when it cannot. */
sealed trait Capability {
  def available: Boolean
  def reason: Option[String]
}

object Capability {
  case object Available extends Capability {
    override val available: Boolean = true
    override val reason: Option[String] = None
  }

  final case class Unavailable(why: String) extends Capability {
    override val available: Boolean = false
    override val reason: Option[String] = Some(why)
  }
}

/**
 * The total overall synchronization status, containing rollups, miner dictionary,
 * and synchronization capabilities (pre-requisites for certain client actions
 * like mempool chaining rollups)
 */
final case class SyncView(status: SyncStatus,
                          version: Long,
                          rollups: Seq[(String, NISPTree)],
                          minerTree: Option[MinerTree],
                          dataBoxToken: Option[ErgoId],
                          canonical: Capability,
                          minerDictionary: Capability,
                          mempool: Capability) {

  def cursor: Option[SyncCursor] = status.cursor

  /** Rollups this client may act on. Empty whenever canonical state cannot be trusted. */
  def actionableRollups: Seq[(String, NISPTree)] = if (canonical.available) rollups else Seq.empty
}

object SyncView {
  private val NotStarted = Capability.Unavailable("synchronization has not started")

  val initial: SyncView =
    SyncView(Starting, 0L, Seq.empty, None, None, NotStarted, NotStarted, NotStarted)
}
