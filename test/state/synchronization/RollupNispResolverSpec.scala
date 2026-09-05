package state.synchronization

import nisp.NispCommitment
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{NispHistoryFixtures, ReducerFixtures, SyncFixtures}

import scala.util.Success

class RollupNispResolverSpec extends AnyFlatSpec with Matchers {
  private def resolve(c: NispHistoryFixtures.Chain, limit: Int = 10,
                       requests: Option[Map[String, NispCommitment]] = None): NispResolution =
    new RollupNispResolver(c.api, ReducerFixtures.protocol(), limit)
      .resolve(c.target, c.origin, 460, requests.getOrElse(c.requested))

  "RollupNispResolver" should "recover multiple raw malformed submissions in one compact replay" in {
    val c = NispHistoryFixtures.chain()
    val result = resolve(c)
    result.complete shouldBe true
    result.transforms shouldBe 3
    result.resolved.payloads.foreach { case (key, value) => value.bytes shouldEqual c.raw(key) }
    c.transactions.foreach(tx => verify(c.api, times(1)).indexedTransactionById(tx.id))
    result.resolved.retainedBytes shouldBe 16000L
    val raw = result.resolved.payloads.values.head.bytes
    raw(0) = (raw(0) + 1).toByte
    result.resolved.payloads.values.head.commitment.matchesPayload(
      result.resolved.payloads.values.head.bytes) shouldBe true
  }

  it should "recover only requested surviving entries" in {
    val c = NispHistoryFixtures.chain()
    val request = c.requested.take(1)
    val result = resolve(c, requests = Some(request))
    result.complete shouldBe true
    result.resolved.payloads.keySet shouldBe request.keySet
  }

  it should "defer an unconfirmed boundary and reject backwards inclusion heights" in {
    val c = NispHistoryFixtures.chain()
    new RollupNispResolver(c.api, ReducerFixtures.protocol(), 10)
      .resolve(c.target, c.origin, 459, c.requested).complete shouldBe false
    val tx = c.transactions(2)
    when(c.api.indexedTransactionById(tx.id)).thenReturn(Success(Some(tx.copy(inclusionHeight = 100))))
    resolve(c).historyError.get should include(tx.id)
  }

  it should "report a missing indexed box without publishing a partial history" in {
    val c = NispHistoryFixtures.chain()
    val missing = c.transactions(2).inputs.head.boxId
    when(c.api.indexedBoxById(missing)).thenReturn(Success(None))
    val result = resolve(c)
    result.complete shouldBe false
    result.historyError.get should include(missing)
    result.resolved.payloads shouldBe empty
    result.unavailable.keySet shouldBe c.requested.keySet
  }

  it should "report missing transactions and truncated spend history" in {
    Seq(true, false).foreach { missingTransaction =>
      val c = NispHistoryFixtures.chain()
      val tx = c.transactions(2)
      val input = tx.inputs.head.boxId
      if (missingTransaction) when(c.api.indexedTransactionById(tx.id)).thenReturn(Success(None))
      else when(c.api.indexedBoxById(input)).thenReturn(Success(Some(c.boxes(input).copy(spentTransactionId = None))))
      val result = resolve(c)
      result.complete shouldBe false
      result.historyError.get should include(if (missingTransaction) tx.id else input)
    }
  }

  it should "reject a spend of a different box and a successor with a different singleton" in {
    Seq(true, false).foreach { wrongInput =>
      val c = NispHistoryFixtures.chain()
      val tx = c.transactions(1)
      val wrong = if (wrongInput) tx.copy(inputs = tx.inputs.map(in =>
        in.copy(box = in.box.copy(boxId = SyncFixtures.id(999991)))))
      else tx.copy(outputs = tx.outputs.map(out => out.copy(box = out.box.copy(assets =
        out.assets.map(_.copy(tokenId = SyncFixtures.id(999992)))))))
      when(c.api.indexedTransactionById(tx.id)).thenReturn(Success(Some(wrong)))
      resolve(c).complete shouldBe false
    }
  }

  it should "reject cycles and accept exactly the configured number of transforms" in {
    val c = NispHistoryFixtures.chain()
    resolve(c, 3).complete shouldBe true
    resolve(c, 2).historyError.get should include("maxTransforms")
    val tx = c.transactions(2)
    val ancestor = c.transactions.head.outputs.head.boxId
    when(c.api.indexedTransactionById(tx.id)).thenReturn(Success(Some(tx.copy(outputs =
      tx.outputs.map(out => out.copy(box = out.box.copy(boxId = ancestor)))))))
    resolve(c).historyError.get should include("cycle")
  }

  it should "refuse an unauthenticated genesis and a different boundary digest" in {
    val c = NispHistoryFixtures.chain()
    val wrongTarget = c.target.copy(dictionaryDigest = "00" * 33)
    val resolver = new RollupNispResolver(c.api, ReducerFixtures.protocol(), 10)
    resolver.resolve(wrongTarget, c.origin, 460, c.requested).complete shouldBe false
    val origin = c.boxes(c.origin)
    when(c.api.indexedBoxById(c.origin)).thenReturn(Success(Some(origin.copy(box =
      origin.box.copy(ergoTree = "untrusted-collateral")))))
    resolve(c).complete shouldBe false
  }

  it should "never return bytes for a requested hash or score that differs from the submitted bytes" in {
    val c = NispHistoryFixtures.chain()
    val (key, entry) = c.requested.head
    Seq(entry.copy(hash = "00" * 32), entry.copy(score = entry.score + 1)).foreach { wrong =>
      val result = resolve(c, requests = Some(c.requested.updated(key, wrong)))
      result.complete shouldBe false
      result.historyError shouldBe None
      result.unavailable.keySet shouldBe Set(key)
      result.resolved.payloads should not contain key
    }
  }
}
