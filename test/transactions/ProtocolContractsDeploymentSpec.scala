package transactions

import lfsm.{Deployment, DeploymentSpec}
import org.ergoplatform.appkit.NetworkType
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import work.lithos.mutations.Contract

import scala.io.Source
import scala.util.Try

/**
 * The deployment override as `ProtocolContracts` sees it.
 *
 * Two failures this guards against. An override that does not reach a contract compiles it against
 * the public ids while its neighbours use the devnet's, and the pair never validates together. And a
 * refactor of how ids reach the contracts that moves a mainnet tree by one byte strands every live
 * box under the old one, which is what the pinned hashes catch: they are the trees as they were
 * before the override existed, one per contract per network.
 */
class ProtocolContractsDeploymentSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = Deployment.clear()

  /** Every contract `ProtocolContracts` compiles, by a stable name. */
  private def named(c: CompiledContracts): Seq[(String, Contract)] = Seq(
    "payout" -> c.payout,
    "eval" -> c.eval,
    "holding" -> c.holding,
    "holdingLogic" -> c.holdingLogic,
    "gate" -> c.gate,
    "collateral" -> c.collateral,
    "emission" -> c.emission,
    "guard" -> c.guard,
    "enforcer" -> c.enforcer,
    "minerDictionary" -> c.minerDictionary,
    "minerData" -> c.minerData,
    "minerDataLogic" -> c.minerDataLogic,
    "fp.nonMatchingCommitment" -> c.fraudProofs.nonMatchingCommitment,
    "fp.invalidFormat" -> c.fraudProofs.invalidFormat,
    "fp.malformedGE" -> c.fraudProofs.malformedGE,
    "fp.notInWindow" -> c.fraudProofs.notInWindow,
    "fp.nonUniqueHeaders" -> c.fraudProofs.nonUniqueHeaders,
    "fp.incorrectN" -> c.fraudProofs.incorrectN,
    "fp.invalidDiff" -> c.fraudProofs.invalidDiff,
    "fp.transactionNotIncluded" -> c.fraudProofs.transactionNotIncluded,
    "fp.malformedGenesis" -> c.fraudProofs.malformedGenesis)

  private def hashes(network: NetworkType): Seq[(String, String)] =
    named(ProtocolContracts.forNetwork(network)).map { case (n, c) => n -> c.hashedPropBytesHex }

  /**
   * `NETWORK name hash` per line, '#' for comments. Kept as a resource so recording the trees is a
   * file write, and so a reviewer sees a moved tree as a one-line diff naming the contract.
   */
  private val PinFile = "deployment/contract-pins.txt"

  private def pins: Map[(String, String), String] =
    Try {
      val src = Source.fromResource(PinFile)
      try src.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { l =>
        val Array(network, name, hash) = l.split("\\s+")
        (network, name) -> hash
      }.toMap finally src.close()
    }.getOrElse(Map.empty)

  // ─── no override ──────────────────────────────────────────────────────────

  "With no override, every tree" should "be the one pinned for its network" in {
    val actual = for {
      network <- Seq(NetworkType.MAINNET, NetworkType.TESTNET)
      (name, hash) <- hashes(network)
    } yield (network.toString, name, hash)
    val pinned = pins
    val wrong = actual.filterNot { case (n, name, hash) => pinned.get(n -> name).contains(hash) }
    withClue(
      s"${wrong.size} tree(s) differ from or are missing in test/resources/$PinFile. If the change is " +
        "intended, record these lines there:\n" +
        actual.map { case (n, name, hash) => s"$n $name $hash" }.mkString("\n") + "\n") {
      wrong shouldBe empty
    }
  }

  // ─── an override ──────────────────────────────────────────────────────────

  "An override" should "reach every contract that compiles an id in" in {
    val n = NetworkType.TESTNET
    val before = named(ProtocolContracts.forNetwork(n)).toMap
    Deployment.install(n, DeploymentSpec.fakeIds(n))
    val after = named(ProtocolContracts.forNetwork(n)).toMap

    // Each of these carries a token id directly, or the hash of a contract that does.
    Seq("eval", "holding", "holdingLogic", "gate", "collateral", "emission", "guard", "enforcer",
      "minerDictionary", "minerData", "minerDataLogic", "fp.nonMatchingCommitment", "fp.malformedGenesis")
      .foreach { name =>
        withClue(s"$name compiled the same under the override: ") {
          after(name).ergoTreeHex should not equal before(name).ergoTreeHex
        }
      }
    withClue("payout carries no id, so the override must leave it alone: ") {
      after("payout").ergoTreeHex shouldEqual before("payout").ergoTreeHex
    }
  }

  it should "not be served from a cache filled before it was installed, nor outlive its removal" in {
    val n = NetworkType.TESTNET
    val constants = ProtocolContracts.forNetwork(n).guard.ergoTreeHex
    Deployment.install(n, DeploymentSpec.fakeIds(n))
    val overridden = ProtocolContracts.forNetwork(n).guard.ergoTreeHex
    overridden should not equal constants
    Deployment.clear()
    ProtocolContracts.forNetwork(n).guard.ergoTreeHex shouldEqual constants
  }

  it should "leave the other network's contracts on its constants" in {
    val mainnet = ProtocolContracts.forNetwork(NetworkType.MAINNET).collateral.ergoTreeHex
    Deployment.install(NetworkType.TESTNET, DeploymentSpec.fakeIds(NetworkType.TESTNET))
    ProtocolContracts.forNetwork(NetworkType.MAINNET).collateral.ergoTreeHex shouldEqual mainnet
  }
}
