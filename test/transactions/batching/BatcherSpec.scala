package transactions.batching

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/**
 * Whether a batcher answers the stratum. Module starts every batcher whatever this returns, so the
 * answer decides only two things: whether the stratum registers it, and whether its scans have a use
 * when broadcasting is off. A wrong `true` scans the node for nothing; a wrong `false` loses every
 * candidate execution without an error.
 */
class BatcherSpec extends AnyFlatSpec with Matchers {

  private def config(stratum: Option[Boolean], batching: Option[Boolean], source: Option[Boolean],
                     blockTransactions: Option[Boolean] = Some(true)): Configuration =
    Configuration(ConfigFactory.parseString(Seq(
      stratum.map(v => s"lithos-tasks.stratum-server.enabled = $v"),
      blockTransactions.map(v => s"stratum.candidate.blockTransactions = $v"),
      batching.map(v => s"batching.ergodex.enabled = $v"),
      source.map(v => s"stratum.candidate.sources.ergodex.enabled = $v")
    ).flatten.mkString("\n")))

  "Batcher.servesCandidates" should "be true only when the stratum, the batcher and its source are all on" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true)), "ergodex") shouldBe true
  }

  it should "be false when the stratum task is off or absent" in {
    Batcher.servesCandidates(config(Some(false), Some(true), Some(true)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(None, Some(true), Some(true)), "ergodex") shouldBe false
  }

  it should "be false when the stratum inserts no block transactions, which is its default" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true), blockTransactions = Some(false)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true), blockTransactions = None), "ergodex") shouldBe false
  }

  it should "be false when the batcher is disabled" in {
    Batcher.servesCandidates(config(Some(true), Some(false), Some(true)), "ergodex") shouldBe false
  }

  it should "be false when the candidate source is off, which is its default" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(false)), "ergodex") shouldBe false
    Batcher.servesCandidates(config(Some(true), Some(true), None), "ergodex") shouldBe false
  }

  it should "be false for a name with no candidate source" in {
    Batcher.servesCandidates(config(Some(true), Some(true), Some(true)), "no-such-batcher") shouldBe false
  }
}
