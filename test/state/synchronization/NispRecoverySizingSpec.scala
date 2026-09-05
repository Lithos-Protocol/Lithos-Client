package state.synchronization

import lfsm.LFSMHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{NispHistoryFixtures, ReducerFixtures}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

class NispRecoverySizingSpec extends AnyFlatSpec with Matchers {
  "A 500-miner compact rollup" should "recover maximum-size raw payloads with one bounded walk" in {
    val c = NispHistoryFixtures.chain(500, LFSMHelpers.NISP_MAX - 1)
    val result = new RollupNispResolver(c.api, ReducerFixtures.protocol(), 501)
      .resolve(c.target, c.origin, 460, c.requested)
    withClue(result.historyError.toString) { result.complete shouldBe true }
    result.indexRequests shouldBe 1004
    result.resolved.payloads.size shouldBe 500
    result.resolved.retainedBytes shouldBe 14814000L
    val compactBytes = c.requested.valuesIterator.map(_.bytes.length).sum
    compactBytes shouldBe 20000
    val csv = "evidence,miners,payload_bytes,compact_value_bytes,index_requests,recovery_ms\n" +
      s"offline_fake_index,500,${result.resolved.retainedBytes},$compactBytes,${result.indexRequests},${result.elapsedMillis}\n"
    Files.createDirectories(Paths.get("target"))
    Files.write(Paths.get("target/nisp-recovery-metrics.csv"), csv.getBytes(UTF_8))
    println(s"[nisp-recovery] $csv")
  }
}
