package state.synchronization

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.data.AvlTreeFlags
import support.SyncFixtures
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

/** Measures serialized deep-copy work; host-dependent timing is informational only. */
class PlasmaCopyBaselineSpec extends AnyFlatSpec with Matchers {

  "PlasmaMap.copy" should "deep-copy a production-shaped 100-entry dictionary" in {
    val entryCount = 100
    val valueSize = 8000
    val entries = SyncFixtures.plasmaEntries(entryCount, valueSize)
    val source = PlasmaMap[Array[Byte], Array[Byte]](
      AvlTreeFlags.AllOperationsAllowed,
      PlasmaParameters.default)
    val insertion = source.insert(entries: _*)
    insertion.response should have size entryCount
    insertion.response.foreach(_.tryOp.isSuccess shouldBe true)

    val sourceDigest = source.digest.clone()
    val manifest = source.getManifest()
    val manifestBytes = manifest.digest.length + manifest.bytes.length + manifest.subTrees.map(_.length).sum

    source.copy()
    source.copy()
    val timingsNanos = (1 to 5).map { _ =>
      val started = System.nanoTime()
      val copied = source.copy()
      val elapsed = System.nanoTime() - started
      copied.digest.sameElements(sourceDigest) shouldBe true
      elapsed
    }
    val medianNanos = timingsNanos.sorted.apply(timingsNanos.size / 2)

    val independentCopy = source.copy()
    independentCopy.delete(entries.head._1)
    independentCopy.digest.sameElements(sourceDigest) shouldBe false
    source.digest.sameElements(sourceDigest) shouldBe true
    source.lookUp(entries.head._1).response.head.get.sameElements(entries.head._2) shouldBe true

    manifestBytes should be > 0
    info(f"entries=$entryCount valueBytes=${entryCount * valueSize} manifestBytes=$manifestBytes " +
      f"copySamples=${timingsNanos.size} medianCopyMs=${medianNanos / 1000000.0}%.3f")
  }
}
