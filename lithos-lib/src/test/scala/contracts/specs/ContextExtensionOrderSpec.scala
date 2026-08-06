package contracts.specs

import org.ergoplatform.appkit.{ErgoValue, Parameters}
import org.scalatest.propspec.AnyPropSpec
import sigma.interpreter.ContextExtension
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.JavaConverters._

/**
 * A transaction's context extension is part of `messageToSign`, and it is serialized in map ITERATION
 * order — `ContextExtension.serializer` does not sort. Three different maps see it on the way to a
 * node, and they do not agree:
 *
 *   signing  appkit's `isoContextVarsToContextExtension` folds the vars into a Scala `Map`, which for
 *            four or fewer entries iterates in INSERTION order
 *   sending  `ScalaBridge.isoSpendingProof.from` copies them into a `java.util.HashMap[String, String]`
 *            keyed by the DECIMAL STRING of the id; Gson writes the JSON in that hash order
 *   node     `cursor.as[Map[Byte, EvaluatedValue[SType]]]` — circe folds in document order
 *
 * Attach `[0, 64, 65, 2]` and appkit signs one byte string while the node verifies another. Anything
 * reducing to a constant `true` still passes, so a contract-only input looks fine; every real
 * signature on the same transaction fails with `Success((false, <cost>))`.
 *
 * These properties pin the orders so the trap cannot come back silently. Nothing here needs a node —
 * the mismatch is entirely in the serialization.
 */
class ContextExtensionOrderSpec extends AnyPropSpec with ContractSpecBase {

  /** The order appkit's Scala map will iterate — what actually gets signed. */
  private def signingOrder(ids: Seq[Byte]): Seq[Byte] = {
    var m = Map.empty[Byte, String]
    ids.foreach(id => m += (id -> ""))
    m.keys.toSeq
  }

  /** The order appkit's `java.util.HashMap` will iterate — what lands in the JSON, and so in the node. */
  private def wireOrder(ids: Seq[Byte]): Seq[Byte] = {
    val m = new java.util.HashMap[String, String](ids.size)
    ids.foreach(id => m.put(id.toString, ""))
    m.keySet().asScala.toSeq.map(_.toByte)
  }

  /** Straight through appkit's own path: this is the extension that would actually be signed. */
  private def extensionOf(box: InputUTXO): ContextExtension =
    box.toFullInput.asInstanceOf[org.ergoplatform.appkit.impl.InputBoxImpl].getExtension

  /**
   * The whole question, end to end: does the extension appkit signs serialize to the same bytes as the
   * one the node reconstructs? Mirrors both hops — appkit's `HashMap` on the way out, circe's
   * document-order fold on the way in.
   */
  private def survivesRoundTrip(ids: Seq[Byte]): Boolean = {
    var signing = Map.empty[Byte, String]
    ids.foreach(id => signing += (id -> s"v$id"))

    var node = Map.empty[Byte, String]
    wireOrder(ids).foreach(id => node += (id -> s"v$id"))

    signing.toSeq == node.toSeq
  }

  private def serialized(ext: ContextExtension): Array[Byte] =
    ContextExtension.serializer.toBytes(ext)

  /** The Lithos emission spend: op byte, the two executed scripts, and one of slot index / group element. */
  private val joinIds: Seq[Byte] = Seq(0, 64, 65, 2)
  private val activateIds: Seq[Byte] = Seq(0, 64, 65, 1)

  property("the naive attachment order does NOT survive the trip to a node") {
    signingOrder(joinIds) shouldBe Seq[Byte](0, 64, 65, 2)
    wireOrder(joinIds) shouldBe Seq[Byte](0, 2, 64, 65)
    signingOrder(joinIds) should not be wireOrder(joinIds)

    signingOrder(activateIds) shouldBe Seq[Byte](0, 64, 65, 1)
    wireOrder(activateIds) shouldBe Seq[Byte](0, 1, 64, 65)
    signingOrder(activateIds) should not be wireOrder(activateIds)
  }

  property("attaching in wire order makes the signing and node maps agree") {
    Seq(joinIds, activateIds).foreach { ids =>
      val ordered = wireOrder(ids)
      signingOrder(ordered) shouldBe wireOrder(ordered)
      signingOrder(ordered) shouldBe ordered
    }
  }

  property("the extension bytes differ under the naive order and match under wire order") {
    withCtx { ctx =>
      val values: Map[Byte, ErgoValue[_]] = Map(
        0.toByte -> ErgoValue.of(0.toByte),
        2.toByte -> ErgoValue.of(7),
        64.toByte -> ErgoValue.of(64L),
        65.toByte -> ErgoValue.of(65L))

      def attach(order: Seq[Byte]): ContextExtension = {
        val box = inputAt(UTXO(contractOf(miner(ctx)), Parameters.OneErg), ctx, 0)
        extensionOf(order.foldLeft(box)((acc, id) => acc.withCtxVar(id, values(id))))
      }

      // Same four variables, two attachment orders, two different signed messages.
      serialized(attach(joinIds)) should not equal serialized(attach(wireOrder(joinIds)))
      serialized(attach(wireOrder(joinIds))) shouldBe serialized(attach(Seq[Byte](0, 2, 64, 65)))
    }
  }

  /**
   * Scala keeps insertion order only up to `Map4`. At five entries it becomes a hash-ordered `HashMap`
   * and no attachment order can line it up with the JSON, so five context vars on one input cannot be
   * made to sign correctly this way at all.
   */
  property("the round trip is what actually decides it, and only 2-4 vars are at risk") {
    // Broken: attached in an order that is not the wire order.
    survivesRoundTrip(Seq[Byte](0, 64, 65, 2)) shouldBe false
    survivesRoundTrip(Seq[Byte](0, 64, 65, 1)) shouldBe false
    survivesRoundTrip(Seq[Byte](1, 0)) shouldBe false

    // Fixed: attached in wire order.
    survivesRoundTrip(Seq[Byte](0, 2, 64, 65)) shouldBe true
    survivesRoundTrip(Seq[Byte](0, 1, 64, 65)) shouldBe true

    // One var has no order to get wrong.
    survivesRoundTrip(Seq[Byte](0)) shouldBe true

    /**
     * Five or more is safe in ANY attachment order, which is the counter-intuitive part. Scala drops
     * insertion order at five entries, so both appkit's signing map and the node's parsed map are
     * hash-ordered `HashMap`s over the same keys — and that order depends only on the keys. The two
     * sides converge precisely because neither one can honour insertion order any more.
     */
    Seq(
      Seq[Byte](0, 1, 2, 64, 65),
      Seq[Byte](65, 64, 2, 1, 0),
      Seq[Byte](2, 65, 0, 64, 1),
      Seq[Byte](0, 1, 2, 3, 64, 65)
    ).foreach(ids => withClue(s"ids ${ids.mkString("[", ",", "]")}: ") {
      survivesRoundTrip(ids) shouldBe true
    })
  }

  /**
   * The fraud proofs and the PlasmaDex mutators attach small ids in ascending order, which is already
   * the wire order for those sets — so they are safe, but by coincidence rather than by design. If a
   * new variable is ever added at a two-digit id, or the attachment order is shuffled, this fails.
   */
  property("the id sets production already uses are safe as attached") {
    Seq[Seq[Byte]](Seq(0), Seq(0, 1), Seq(0, 1, 2), Seq(0, 1, 2, 3)).foreach { ids =>
      withClue(s"ids ${ids.mkString("[", ",", "]")}: ") {
        signingOrder(ids) shouldBe wireOrder(ids)
      }
    }
  }
}
