package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StratumConfig}
import play.api.Configuration
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
class CommitmentExecution(nodeContext: NodeContext, syncHandler: ActorRef, config: Configuration,
                          dataBoxes: DataBoxSource, engineNode: node.NodeApi, funding: EngineFunding,
                          alive: () => Boolean) {
  private implicit val timeout: Timeout = Timeout(30.seconds)
  private val diff = new StratumConfig(config).diff
  private val commitments = new CommitmentTransactions(nodeContext, dataBoxes, alive) {
    override protected def executionNode: node.NodeApi = engineNode
  }

  /** Registers this miner with the configured difficulty as its first commitment. Returns the tx id. */
  def register(): String = {
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

  /** Moves the registered commitment to the configured difficulty when it has to and may. */
  def commit(): CommitmentProgress = {
    require(alive(), "commitment attempt was superseded")
    commitments.commitScore(diff, funding).get
  }
}
