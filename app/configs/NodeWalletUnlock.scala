package configs

import node.NodeApi
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success}

/**
 * Keeps the node's wallet unlocked with `node.pass`, which this client has already checked against
 * the node's own keystore. Lender keys are derived through the node's wallet, and the node refuses a
 * derivation while it is locked, which happens on every node restart.
 *
 * Each change of state is logged once. After a failed unlock the next attempt waits `retryMs`, so
 * an open endpoint asking on every request cannot send the password to the node in a loop.
 */
final class NodeWalletUnlock(api: NodeApi, password: String, retryMs: Long = 60000L,
                             clock: () => Long = () => System.currentTimeMillis()) {
  private val logger: Logger = LoggerFactory.getLogger("NodeWalletUnlock")
  private var unlocked = false
  private var lastProblem = Option.empty[String]
  private var retryAt = 0L

  /** Whether the node wallet is unlocked, unlocking it first if it is locked. */
  def ensure(): Boolean = synchronized {
    if (!unlocked && clock() < retryAt) false
    else {
      val result = api.walletStatus() match {
        case Success(status) if status.isUnlocked => Right(false)
        case Success(status) if !status.isInitialized => Left("the node wallet is not initialized")
        case Success(_) => api.unlockWallet(password) match {
          case Success(_) => Right(true)
          case Failure(ex) => Left(s"the node refused to unlock its wallet: ${ex.getMessage}")
        }
        case Failure(ex) => Left(s"could not read the node wallet's status: ${ex.getMessage}")
      }
      result match {
        case Right(justUnlocked) =>
          if (justUnlocked) logger.info("Unlocked the node wallet with node.pass, so lender keys can be derived")
          unlocked = true
          lastProblem = None
        case Left(problem) =>
          if (!lastProblem.contains(problem))
            logger.warn(s"Lender keys cannot be derived: $problem. Joins can use only keys the wallet already holds")
          unlocked = false
          lastProblem = Some(problem)
          retryAt = clock() + retryMs
      }
      unlocked
    }
  }
}
