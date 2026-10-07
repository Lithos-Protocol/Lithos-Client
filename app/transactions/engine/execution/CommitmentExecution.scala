package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import state.messages.SyncMessages.{CurrentMinerDictionary, GetMinerDictionary}
import transactions.engine.wallet.EngineFunding
import transactions.rollups.{CommitmentProgress, CommitmentTransactions, DataBoxSource}
import utils.Globals

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * This miner's registration and difficulty-commitment changes, built for one engine attempt.
 *
 * Both spend wallet inputs, so the engine runs them like any other intent. Neither depends on a
 * rollup existing, which is what lets a miner commit before it has mined anything to submit.
 */
class CommitmentExecution(nodeContext: NodeContext, syncHandler: ActorRef,
                          dataBoxes: DataBoxSource, engineNode: node.NodeApi, funding: EngineFunding,
                          alive: () => Boolean) {
  private implicit val timeout: Timeout = Timeout(30.seconds)
  private val commitments = new CommitmentTransactions(nodeContext, dataBoxes, alive) {
    override protected def executionNode: node.NodeApi = engineNode
  }

  /** Registers this miner with `diff` as its first commitment. */
  def register(diff: String): CommitmentProgress.Sent = {
    require(alive(), "registration attempt was superseded")
    val view = Globals.syncView
    require(view.canonical.available && view.minerDictionary.available &&
      view.minerDictionaryMetadata.exists(!_.hasMiner) && dataBoxes.getDataBoxToken.isEmpty,
      "registration requires a current unregistered dictionary view")
    val dictionary = Await.result(syncHandler ? GetMinerDictionary, timeout.duration) match {
      case CurrentMinerDictionary(value) => value
      case _ => throw new IllegalStateException("Miner Dictionary became unavailable")
    }
    commitments.sendInitialCommitment(diff, dictionary, funding)
  }

  /** Moves the registered commitment to `diff` when it has to and may. */
  def commit(diff: String): CommitmentProgress = {
    require(alive(), "commitment attempt was superseded")
    commitments.commitScore(diff, funding).get
  }
}
