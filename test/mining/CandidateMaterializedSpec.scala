package mining

import mining.MiningMessages.CandidateIdentity
import org.json.{JSONArray, JSONObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import transactions.BlockTxMessages.CandidateTx

import java.util.UUID

/**
 * What the node said about the package it was handed, kept as returned.
 *
 * Correspondence is worked out but never enforced here: the supported node can answer with proofs
 * that do not match the transactions it was given, so a mismatch is not evidence of absence.
 */
class CandidateMaterializedSpec extends AnyFlatSpec with Matchers {
  private val identity = CandidateIdentity(500, "aa" * 32, "bb" * 32, 1)
  private val attempt = UUID.randomUUID()
  private val work = "cc" * 32

  private def proofOf(leaves: Seq[String], levels: Seq[String] = Seq("00" + "dd" * 32)): JSONObject = {
    val entries = new JSONArray()
    leaves.foreach { leaf =>
      val levelArray = new JSONArray()
      levels.foreach(levelArray.put)
      entries.put(new JSONObject().put("leaf", leaf).put("levels", levelArray))
    }
    new JSONObject().put("txProofs", entries)
  }

  private def tx(id: String, leaf: String, bytes: Int = 10, cost: Long = 5L) =
    CandidateTx(id, "{}", CandidateTx.Payout, sizeBytes = bytes, cost = cost, leaf = leaf)

  "Materialization" should "match a requested transaction to its proof leaf" in {
    val result = CandidateMaterialized(identity, attempt, work,
      proofOf(Seq("ab" * 32)), Seq(tx("first", "ab" * 32)))

    result.included shouldBe Set("first")
    result.unproven shouldBe empty
    result.fullyProven shouldBe true
  }

  it should "report a requested transaction with no matching leaf as unproven" in {
    val result = CandidateMaterialized(identity, attempt, work,
      proofOf(Seq("ab" * 32)), Seq(tx("first", "ab" * 32), tx("second", "ef" * 32)))

    result.included shouldBe Set("first")
    result.unproven shouldBe Set("second")
    result.fullyProven shouldBe false
  }

  /** An unconfirmed ancestor is a node body this client never signed, so it has no leaf to match. */
  it should "report a member with no recorded leaf as unproven rather than absent" in {
    val result = CandidateMaterialized(identity, attempt, work,
      proofOf(Seq("ab" * 32)), Seq(tx("ancestor", "")))

    result.unproven shouldBe Set("ancestor")
    result.included shouldBe empty
  }

  /**
   * The node returns proofs for what was requested, not an inventory of its block, and it selects a
   * remainder this client never sees. Evidence about anything else is kept rather than filtered.
   */
  it should "retain every returned proof in response order" in {
    val leaves = Seq("ab" * 32, "cd" * 32, "ef" * 32)
    val result = CandidateMaterialized(identity, attempt, work, proofOf(leaves), Seq(tx("one", "cd" * 32)))

    result.proofs.map(_.leaf) shouldBe leaves
    result.included shouldBe Set("one")
  }

  it should "preserve the levels of each proof as returned" in {
    val result = CandidateMaterialized(identity, attempt, work,
      proofOf(Seq("ab" * 32), Seq("00" + "11" * 32, "01" + "22" * 32)), Seq.empty)

    result.proofs.head.levels.map(_.side) shouldBe Seq(0, 1)
    result.proofs.head.levels.map(_.digest) shouldBe Seq("11" * 32, "22" * 32)
  }

  /** Totals describe the supplied package; nothing here claims to know the node's own selection. */
  it should "total only what was supplied" in {
    val result = CandidateMaterialized(identity, attempt, work, proofOf(Seq.empty),
      Seq(tx("a", "ab" * 32, bytes = 100, cost = 7L), tx("b", "cd" * 32, bytes = 250, cost = 11L)))

    result.knownBytes shouldBe 350L
    result.knownCost shouldBe 18L
  }

  it should "carry the identity and work message it was requested under" in {
    val result = CandidateMaterialized(identity, attempt, work, proofOf(Seq.empty), Seq.empty)

    result.identity shouldBe identity
    result.attempt shouldBe attempt
    result.workMessage shouldBe work
  }

  /**
   * Off while the supported node can return proofs that do not correspond to what it was given
   * (ergoplatform/ergo#2463). Turning it on is what a dependent revenue bundle will require.
   */
  it should "not enforce correspondence yet" in {
    CandidateMaterialized.requireCorrespondence shouldBe false
  }
}
