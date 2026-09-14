package contracts.specs.rollup

import contracts.specs.emission.EmissionSpecBase
import evaluation.NTable
import lfsm.LFSMHelpers
import lfsm.LFSMHelpers.{COLLAT_BOX_MIN, NUM_LVLS_MAX, TX_PROOF_MAX, TX_PROOF_MIN, TX_SIZE_MIN}
import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.exceptions.TestFailedException
import org.scalatest.propspec.AnyPropSpec
import scorex.utils.{Ints, Longs, Shorts}
import work.lithos.mutations.{Contract, Token}

import scala.util.Random

/**
 * The gate invariant of `FraudProofOrderingSpec`, generated rather than posed by hand.
 *
 * That spec states the property the ordering delivers — a NISP the gates decline is one every later
 * proof can read — and tests it with shapes chosen for the proof each one breaks. This one starts from
 * a NISP that is honest to all eight readers, which no other fixture in the suite is (the "clean" NISPs
 * elsewhere make `FP_InvalidDiff` and `FP_MalformedGenesis` fire), and mutates it byte by byte
 * through every region of the layout.
 *
 * Each mutant runs through the readers in the deployed order, stopping at the first that fires, as
 * `Evaluator` does. Two things are asserted about it. Nothing throws before that point: a throw is not
 * a verdict, and an evaluation with one in it ends incomplete. And something does fire: every mutant
 * that changes the bytes is a NISP someone can slash, apart from the shapes recorded at the end that
 * the proofs deliberately read as the same NISP.
 *
 * The two hash-covered regions, the header and the transaction bytes, are sampled by default. With
 * `LITHOS_FP_TOTALITY=full` in the environment the header is covered byte by byte and the transaction
 * bytes at 460 positions (Play forks the test JVM, so `-Dlithos.fp.totality=full` reaches it only
 * through `Test / javaOptions`). Full takes about a minute.
 */
class FraudProofTotalitySpec extends AnyPropSpec with EmissionSpecBase with FraudProofSpecBase
  with GenesisFixtures {

  import GenesisFixtures.HonestNisp

  private val Score: Long = FPInvalidDiffSpec.MinedScore
  private val Threshold: BigInt = FPInvalidDiffSpec.Threshold

  private val Full: Boolean =
    sys.props.get("lithos.fp.totality").orElse(sys.env.get("LITHOS_FP_TOTALITY")).contains("full")

  /** Every real evaluation box carries the rollup NFT, so the sweep runs against one that does. */
  private def nft: Seq[Token] = Seq(Token(fpTokenId, 1L))

  /**
   * The proofs that read a NISP, in the order they run: `FraudProofOrderingSpec.readers`, with
   * `FP_MalformedGenesis` compiled against this suite's holding contract so the genesis it walks is
   * under the script it expects. `FP_NonMatchingCommitment` reads only the score, which no mutant
   * touches, and needs three data inputs, so it is left out as it is there.
   */
  private def readers(ctx: BlockchainContext): Seq[(String, Contract, Seq[ContextVar])] = Seq(
    ("InvalidFormat", fpInvalidFormat(ctx), Seq.empty),
    ("MalformedGE", fpMalformedGE(ctx), Seq.empty),
    ("NotInWindow", fpNotInWindow(ctx), Seq.empty),
    ("NonUniqueHeaders", fpNonUniqueHeaders(ctx), Seq.empty),
    ("IncorrectN", fpIncorrectN(ctx), Seq(ContextVar.of(3.toByte, NTable.ergoValue))),
    ("InvalidDiff", fpInvalidDiff(ctx), Seq.empty),
    ("TransactionNotIncluded", fpTransactionNotIncluded(ctx), Seq.empty),
    ("MalformedGenesis",
      FPMalformedGenesisSpec.compiled(ctx, collateralContract(ctx), holdingContract(ctx)), Seq.empty))

  /**
   * The proofs in `order`, stopping at the first that fires, as `Evaluator` does. The proof that fired,
   * or None when every one declined. A throw fails the property, naming the mutant and the proof.
   */
  private def slashedBy(ctx: BlockchainContext,
                        nisp: Array[Byte],
                        tag: String,
                        order: Seq[(String, Contract, Seq[ContextVar])]): Option[String] =
    order.iterator.map { case (name, script, extra) =>
      val f = fraud(ctx, script, nisp, Score, extraVars = extra, tokens = nft)
      name -> withClue(s"$tag, $name: ") { evaluatesCleanly(f.prover, fraudTx(f)()) }
    }.collectFirst { case (name, "fires") => name }

  private def slashedBy(ctx: BlockchainContext, nisp: Array[Byte], tag: String): Option[String] =
    slashedBy(ctx, nisp, tag, readers(ctx))

  // ─── byte geometry of the honest NISP ─────────────────────────────────────
  //
  // `[score: 8][share 0]...[share 9]`, each share `[N: 4][header][txProofSize: 2][numLevels: 1]
  // [txProof][levels: 33n][yCoord: 32]`. Mutants patch the serialized bytes and never re-balance the
  // size fields around an edit: an attacker writes whatever bytes they like.

  private def patch(bytes: Array[Byte], at: Int, replacement: Array[Byte]): Array[Byte] =
    bytes.slice(0, at) ++ replacement ++ bytes.drop(at + replacement.length)

  private def splice(bytes: Array[Byte], at: Int, deleted: Int, inserted: Array[Byte]): Array[Byte] =
    bytes.slice(0, at) ++ inserted ++ bytes.drop(at + deleted)

  private def flipped(bytes: Array[Byte], at: Int): Array[Byte] = {
    val m = bytes.clone(); m(at) = (m(at) ^ 0xFF).toByte; m
  }

  private def shareOffsets(h: HonestNisp): Seq[Int] =
    h.shares.map(_.serialize.length).scanLeft(8)(_ + _).init

  /** Where share `i`'s fields start inside the NISP. */
  private case class Geo(start: Int, headerLen: Int, txProofLen: Int, levelsLen: Int) {
    val header: Int = start + 4
    val txProofSize: Int = header + headerLen
    val numLevels: Int = txProofSize + 2
    val txProof: Int = numLevels + 1
    val levels: Int = txProof + txProofLen
    val yCoord: Int = levels + levelsLen
    /** The header's last eight bytes. */
    val nonce: Int = header + headerLen - 8
  }

  private def geo(h: HonestNisp, i: Int): Geo = {
    val s = h.shares(i)
    Geo(shareOffsets(h)(i), s.headerBytes.length, s.txProof.serialize.length, s.levels.flatten.length)
  }

  /** Share `i` with its header replaced by `header`, and nothing else re-balanced. */
  private def withHeader(h: HonestNisp, i: Int, header: Array[Byte]): Array[Byte] = {
    val g = geo(h, i)
    splice(h.nisp, g.header, g.headerLen, header)
  }

  /** Shares 0 and 9: where the offset arithmetic starts, and where it ends. */
  private val Ends: Seq[Int] = Seq(0, 9)

  // ─── the fixture ──────────────────────────────────────────────────────────

  property("honest: every proof declines the honest NISP") {
    withCtx { ctx =>
      slashedBy(ctx, honestNisp(ctx).nisp, "honest") shouldBe None
    }
  }

  /** The shares are really mined, so `FP_InvalidDiff` declining above is a verdict, not a vacuity. */
  property("honest: a nonce that misses the threshold is slashed by InvalidDiff") {
    withCtx { ctx =>
      val h = honestNisp(ctx)
      val g = geo(h, 0)
      val len = h.shares(0).serialize.length
      val missed = (1L to 400L).iterator
        .map(k => patch(h.nisp, g.nonce, Longs.toByteArray(k * 7919L)))
        .find(m => SuperShare.deserialize(m.slice(g.start, g.start + len)).powHit > Threshold)
        .get
      slashedBy(ctx, missed, "nonce") shouldBe Some("InvalidDiff")
    }
  }

  /**
   * The check can fail. With the format gate taken out of the order, the height past `Int.MaxValue`
   * that `FraudProofOrderingSpec` uses to show what that gate holds reaches `FP_NotInWindow`, which
   * throws on it, and the sweep's check reports the throw rather than a verdict.
   */
  property("self-check: without the format gate, a height past Int.MaxValue fails the check at NotInWindow") {
    withCtx { ctx =>
      val h = honestNisp(ctx)
      val vlq = Array(0x80.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte, 0x08.toByte)
      val overflow = withHeader(h, 0, NispFixtures.withHeightVlq(h.shares(0).headerBytes, vlq))
      slashedBy(ctx, overflow, "overflow") shouldBe Some("InvalidFormat")
      val thrown = intercept[TestFailedException] {
        slashedBy(ctx, overflow, "overflow", readers(ctx).filterNot(_._1 == "InvalidFormat"))
      }
      thrown.getMessage should include("NotInWindow")
    }
  }

  /**
   * The dependency the ordering carries beyond the two gates. Neither gate reads the declared N, and
   * `powHit` requires N >= 16, so on a share declaring N = 0 `FP_InvalidDiff` on its own throws; in
   * the deployed order `FP_IncorrectN` fires first and `Evaluator` never gets there.
   */
  property("dependency: N = 0 makes InvalidDiff alone throw, and IncorrectN fires before it is reached") {
    withCtx { ctx =>
      val h = honestNisp(ctx)
      val tampered = patch(h.nisp, geo(h, 0).start, Ints.toByteArray(0))
      val alone = fraud(ctx, fpInvalidDiff(ctx), tampered, Score, tokens = nft)
      failsToEvaluate(alone.prover, fraudTx(alone)())
      slashedBy(ctx, tampered, "N = 0") shouldBe Some("IncorrectN")
    }
  }

  // ─── the sweep ────────────────────────────────────────────────────────────

  private def sweep(label: String)
                   (mutants: (BlockchainContext, HonestNisp) => Seq[(String, Array[Byte])]): Unit =
    property(s"$label: every mutant is slashed, and nothing throws on the way to the proof that does") {
      withCtx { ctx =>
        val h = honestNisp(ctx)
        val (same, changed) = mutants(ctx, h).partition { case (_, m) => java.util.Arrays.equals(m, h.nisp) }
        val (admitted, oversize) = changed.partition { case (_, m) =>
          m.length >= LFSMHelpers.NISP_MIN && m.length < LFSMHelpers.NISP_MAX
        }
        val verdicts = admitted.map { case (tag, m) => tag -> slashedBy(ctx, m, s"$label, $tag") }
        val counts = verdicts.flatMap(_._2).groupBy(identity).map { case (n, v) => s"$n=${v.size}" }.toSeq.sorted
        println(s"[totality] $label: ${admitted.size} mutants, slashed by ${counts.mkString(", ")}" +
          (if (same.nonEmpty) s"; ${same.size} left the bytes unchanged and were skipped" else "") +
          (if (oversize.nonEmpty) s"; ${oversize.size} outside Holding's size bounds and were skipped" else ""))
        withClue(s"$label: mutants no proof fires on: ") {
          verdicts.collect { case (tag, None) => tag } shouldBe empty
        }
      }
    }

  private def flip(x: Byte): Byte = (x ^ 0xFF).toByte
  private def zero(x: Byte): Byte = 0.toByte

  /**
   * The header. Any of its bytes feeds the PoW message, so `FP_InvalidDiff` fires on nearly all of
   * them; what the region tests is that nothing before it throws. Every fourth byte flipped by default;
   * every byte, flipped and zeroed, in full.
   */
  sweep("header") { (_, h) =>
    for {
      i <- Ends
      positions = 0 until h.shares(i).headerBytes.length
      p <- if (Full) positions else positions.by(4)
      (op, f) <- if (Full) Seq("flip" -> flip _, "zero" -> zero _) else Seq("flip" -> flip _)
    } yield {
      val header = h.shares(i).headerBytes.clone(); header(p) = f(header(p))
      (s"share $i, $op header[$p]", withHeader(h, i, header))
    }
  }

  /**
   * The two VLQ fields `FP_InvalidFormat` bounds: the honest value in non-minimal encodings, five-byte
   * heights and nine-byte timestamps around the edges of an Int and a Long, and a run of continuation
   * bytes that never ends.
   */
  sweep("vlq") { (_, h) =>
    def field(i: Int, which: String): (Array[Byte], Int, Array[Byte]) = {
      val header = h.shares(i).headerBytes
      val at = if (which == "height") NispFixtures.heightVlqOffset(header) else 130
      (header, at, header.slice(at, at + NispFixtures.vlqLength(header, at)))
    }
    def reencoded(i: Int, which: String, vlq: Array[Byte]): Array[Byte] = {
      val (header, at, original) = field(i, which)
      withHeader(h, i, header.slice(0, at) ++ vlq ++ header.drop(at + original.length))
    }
    /** The same value with `k` redundant groups appended. */
    def padded(v: Array[Byte], k: Int): Array[Byte] =
      (v.init :+ (v.last | 0x80).toByte) ++ Array.fill(k - 1)(0x80.toByte) :+ 0.toByte
    for {
      i <- Ends
      which <- Seq("height", "timestamp")
      (tag, vlq) <- {
        val original = field(i, which)._3
        val edges =
          if (which == "height")
            Seq(0, 1, 7, 8, 15, 127).map(b =>
              s"five-byte height, last byte $b" -> (Array.fill(4)(0x80.toByte) :+ b.toByte))
          else
            Seq(0, 1, 127).flatMap(b => Seq(
              s"nine-byte timestamp over 0xFF, last byte $b" -> (Array.fill(8)(0xFF.toByte) :+ b.toByte),
              s"nine-byte timestamp over 0x80, last byte $b" -> (Array.fill(8)(0x80.toByte) :+ b.toByte)))
        (1 to 3).map(k => s"$which padded by $k" -> padded(original, k)) ++ edges :+
          (s"$which as eleven continuation bytes" -> Array.fill(11)(0x80.toByte))
      }
    } yield (s"share $i, $tag", reencoded(i, which, vlq))
  }

  /**
   * The small fixed fields, each at the edges of the range the gate allows and just past them, taken
   * from the constants the gate is compiled with. The size fields around a moved one stay as they were.
   */
  sweep("fields") { (ctx, h) =>
    for {
      i <- Ends
      (tag, m) <- {
        val g = geo(h, i)
        val header = h.shares(i).headerBytes
        val n = NispFixtures.calcN(rollupBlock(ctx).toInt - i)
        Seq(0, 1, -1, Int.MaxValue, Int.MinValue, n - 1, n + 1).map(v =>
          s"N = $v" -> patch(h.nisp, g.start, Ints.toByteArray(v))) ++
          Seq(0, -1, TX_PROOF_MIN - 1, TX_PROOF_MIN, TX_PROOF_MAX - 1, TX_PROOF_MAX, 32767).map(v =>
            s"txProofSize = $v" -> patch(h.nisp, g.txProofSize, Shorts.toByteArray(v.toShort))) ++
          Seq(0, 1, NUM_LVLS_MAX, NUM_LVLS_MAX + 1, -1, 127).map(v =>
            s"numLevels = $v" -> patch(h.nisp, g.numLevels, Array(v.toByte))) ++
          Seq(0, -1, TX_SIZE_MIN - 1, TX_SIZE_MIN, g.txProofLen - 2 - COLLAT_BOX_MIN,
            g.txProofLen - 2 - COLLAT_BOX_MIN + 1, g.txProofLen).map(v =>
            s"txSize = $v" -> patch(h.nisp, g.txProof, Shorts.toByteArray(v.toShort))) ++
          Seq(1, -1).map(v =>
            s"unparsed = $v" -> withHeader(h, i, NispFixtures.withUnparsedSize(header, v.toByte))) ++
          Seq(0, 1, 2, 3, 5, 6, 7, 8, 127, -1).map(v =>
            s"version = $v" -> withHeader(h, i, NispFixtures.withVersion(header, v.toByte)))
      }
    } yield (s"share $i, $tag", m)
  }

  /**
   * The genesis transaction and collateral box that `FP_MalformedGenesis` walks and
   * `FP_TransactionNotIncluded` hashes. The first 32 bytes and a seeded sample of 32 more by default;
   * the first 160 and 300 more in full.
   */
  sweep("transaction proof") { (_, h) =>
    val g = geo(h, 0)
    val (head, sampled) = if (Full) (160, 300) else (32, 32)
    val rest = new Random(20260913L).shuffle((head until g.txProofLen).toVector).take(sampled).sorted
    ((0 until math.min(head, g.txProofLen)) ++ rest).map(p =>
      (s"share 0, flip txProof[$p]", flipped(h.nisp, g.txProof + p)))
  }

  /**
   * The Merkle levels' digests and the stored y. A level's leading side byte is left to the
   * malleability property below, since the contract only asks whether it is zero.
   */
  sweep("levels and yCoord") { (_, h) =>
    val g = geo(h, 9)
    val digests = h.shares(9).levels.indices.flatMap(l =>
      (1 until NispFixtures.LevelSize).map(b =>
        (s"level $l digest[${b - 1}]", g.levels + l * NispFixtures.LevelSize + b)))
    val y = (0 until 32).map(b => (s"yCoord[$b]", g.yCoord + b))
    (digests ++ y).map { case (tag, p) => (s"share 9, flip $tag", flipped(h.nisp, p)) }
  }

  /**
   * Bytes inserted or deleted where the levels and the y coordinate start, so every field after the
   * edit is read from the wrong place; and one share's bytes written over another's.
   */
  sweep("framing") { (_, h) =>
    val g = geo(h, 9)
    val edits = for {
      (region, at) <- Seq("levels" -> g.levels, "yCoord" -> g.yCoord)
      k <- Seq(1, 32, 33)
      (op, m) <- Seq(
        s"delete $k" -> splice(h.nisp, at, k, Array.emptyByteArray),
        s"insert $k" -> splice(h.nisp, at, 0, Array.fill(k)(0x5A.toByte)))
    } yield (s"share 9, $op at $region", m)
    val offsets = shareOffsets(h)
    val lengths = h.shares.map(_.serialize.length)
    val share0 = h.nisp.slice(offsets(0), offsets(0) + lengths(0))
    edits :+ ("share 0 written over share 1",
      h.nisp.slice(0, offsets(1)) ++ share0 ++ h.nisp.drop(offsets(1) + lengths(1)))
  }

  // ─── shapes the proofs read as the same NISP ──────────────────────────────

  /**
   * Two things the sweeps leave out, because no proof fires on them by design. A level's side byte is
   * read as zero or not, so `FP_TransactionNotIncluded` folds the sibling the same way for any nonzero
   * value; and every proof treats the ten shares as a set, so their order is free. Recorded so the
   * sweeps leave them out knowingly: the same work has more than one byte string, and the commitment
   * hash is not a canonical name for it.
   */
  property("malleability: a nonzero side byte and the share order are not what any proof checks") {
    withCtx { ctx =>
      val h = honestNisp(ctx)
      val g = geo(h, 9)
      Seq(1, 0x7F, 0xFF).foreach { side =>
        val m = h.nisp.clone(); m(g.levels) = side.toByte
        slashedBy(ctx, m, s"side byte $side") shouldBe None
      }
      val zeroed = h.nisp.clone(); zeroed(g.levels) = 0.toByte
      slashedBy(ctx, zeroed, "side byte 0") shouldBe Some("TransactionNotIncluded")

      val offsets = shareOffsets(h)
      val lengths = h.shares.map(_.serialize.length)
      val parts = (0 until 10).map(k => h.nisp.slice(offsets(k), offsets(k) + lengths(k)))
      Seq("reversed" -> (9 to 0 by -1),
        "rotated by one" -> ((1 to 9) :+ 0),
        "ends swapped" -> (9 +: (1 to 8) :+ 0)).foreach { case (tag, order) =>
        slashedBy(ctx, h.nisp.slice(0, 8) ++ order.flatMap(parts(_)), tag) shouldBe None
      }
    }
  }
}
