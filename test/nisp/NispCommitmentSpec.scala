package nisp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.utils.Longs

class NispCommitmentSpec extends AnyFlatSpec with Matchers {
  "NispCommitment" should "bind exact raw bytes including the score and trailing malformed bytes" in {
    val raw = Longs.toByteArray(123L) ++ Array[Byte](-1, 0, 1, 2)
    val entry = NispCommitment.fromPayload(raw)
    entry.bytes.length shouldBe 40
    entry.score shouldBe 123L
    NispCommitment.decode(entry.bytes) shouldBe entry
    entry.matchesPayload(raw) shouldBe true
    entry.matchesPayload(raw.dropRight(1)) shouldBe false
    entry.matchesPayload(Longs.toByteArray(124L) ++ raw.drop(8)) shouldBe false
    entry.matchesPayload(Array.fill[Byte](7)(0)) shouldBe false
  }

  it should "reject every noncanonical entry length" in {
    Seq(0, 7, 8, 39, 41, 7948).foreach { size =>
      an[IllegalArgumentException] should be thrownBy NispCommitment.decode(Array.fill[Byte](size)(0))
    }
    an[IllegalArgumentException] should be thrownBy NispCommitment.fromPayload(Array.fill[Byte](7)(0))
  }
}
