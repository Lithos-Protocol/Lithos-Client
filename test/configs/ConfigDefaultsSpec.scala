package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/**
 * The `Default` blocks and `conf/application.conf` have to agree.
 *
 * Any construction path without a `Configuration` — an API route, a test, a manually built actor —
 * gets the `Default`, so a divergence silently gives that path different behaviour from the shipped
 * one. It has happened twice: `mempoolRefreshMs` at 2000 against a shipped 10000, which opens the
 * window where a block found on the previous job is rejected five times as often; and
 * `maxLenderKeys` at 120 against a shipped 32, which derives lender keys past the last index the
 * prover holds a secret for, so their permit returns and coinbases land where nothing can spend
 * them. Neither fails a build and neither shows up in a log.
 */
class ConfigDefaultsSpec extends AnyFlatSpec with Matchers {

  private val shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  "CandidateConfig.Default" should "equal what the shipped application.conf parses" in {
    CandidateConfig(shipped) shouldEqual CandidateConfig.Default
  }

  "EmissionConfig.Default" should "equal what the shipped application.conf parses" in {
    EmissionConfig(shipped) shouldEqual EmissionConfig.Default
  }

  // Not a duplicate of the two above: they would both still pass if the conf lowered numAddresses
  // and raised maxLenderKeys together. This is the inequality that decides whether a lender key's
  // funds can be spent at all, and NodeConfig only logs about it at startup.
  "node.numAddresses" should "cover every lender key the shipped config permits" in {
    val numAddresses = shipped.get[Int]("node.numAddresses")
    val maxLenderKeys = shipped.get[Int]("emission.maxLenderKeys")
    withClue(s"numAddresses=$numAddresses must be >= maxLenderKeys=$maxLenderKeys, or keys past " +
      s"index ${numAddresses - 1} receive funds this client cannot sign for: ") {
      numAddresses should be >= maxLenderKeys
    }
  }
}
