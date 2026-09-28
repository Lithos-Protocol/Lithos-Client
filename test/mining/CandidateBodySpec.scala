package mining

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The body of a candidateWithTxsAndPk request.
 *
 * The node rebuilds each context extension in the order the JSON lists it. The body used to be
 * re-serialized through `org.json`, whose map reordered keys: a holding transform signed with
 * (64, 3) reached the node as (3, 64), landed in block 564574 under an id this client never
 * computed, and was logged as left out. The mempool path posts signed JSON untouched, so this has
 * to carry exactly the same text.
 */
class CandidateBodySpec extends AnyFlatSpec with Matchers {

  private val transform =
    """{"id":"aa","inputs":[{"boxId":"b1","spendingProof":{"proofBytes":"","extension":{"64":"0e01ff","3":"0201"}}}],"dataInputs":[],"outputs":[]}"""
  private val genesis =
    """{"id":"bb","inputs":[{"boxId":"b2","spendingProof":{"proofBytes":"cafe","extension":{}}}],"dataInputs":[],"outputs":[]}"""

  "A candidate body" should "carry every transaction's JSON exactly as it was signed, in order" in {
    val body = MiningNodeInterface.candidateBody(Seq(genesis, transform), "02" + "11" * 32)
    body shouldBe s"""{"txs":[$genesis,$transform],"pk":"02${"11" * 32}"}"""
  }

  it should "be JSON the node can read" in {
    val body = new org.json.JSONObject(MiningNodeInterface.candidateBody(Seq(genesis), "02"))
    body.getJSONArray("txs").length() shouldBe 1
    body.getString("pk") shouldBe "02"
  }

  it should "refuse something that is not a transaction object" in {
    an[IllegalArgumentException] should be thrownBy MiningNodeInterface.candidateBody(Seq("[1,2]"), "02")
  }
}
