package contracts.specs.rollup

import evaluation.NTable
import lfsm.contracts.FraudProofContracts
import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token}

/**
 * The order the proofs run in, asserted as a property rather than left as a comment.
 *
 * A fraud proof has two acceptable answers, fires or declines. A third thing can happen — it throws
 * while evaluating — and that is not an answer: `Evaluator` cannot tell a throw from a proof it never
 * reached, so a NISP that throws in every proof is a miner nobody can slash. Since the accused writes
 * the NISP, that is a fraud that pays.
 *
 * The set avoids it by ordering rather than by defending every proof separately. Each proof assumes
 * the ones before it declined, and reads bytes on that assumption:
 *
 *   `FP_NonMatchingCommitment`  reads eight score bytes and nothing else, so it depends on nothing
 *                               and leads.
 *   `FP_InvalidFormat`          parses nothing. Validates the VLQ fields, the sizes and the version,
 *                               so every later proof's index arithmetic lands inside the share.
 *   `FP_MalformedGE`            reads raw bytes only. Establishes the header's miner key is a real
 *                               point, which `deserializeTo[Header]` would otherwise throw on.
 *   everything after           may parse headers. `FP_IncorrectN` additionally has to precede
 *                               `FP_InvalidDiff`, which feeds the share's `N` into `powHit`.
 *
 * Two properties hold the whole thing: a NISP the gates decline is one every later proof can read,
 * and each shape that breaks a later proof is caught by the gate that claims it.
 */
class FraudProofOrderingSpec extends AnyPropSpec with FraudProofSpecBase {

  private val score = 100000L

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  /** Well formed, real miner key, transaction proved — the shape the gates are meant to admit. */
  private def cleanNisp(ctx: BlockchainContext): Array[Byte] =
    NispFixtures.nisp(score,
      NispFixtures.includedShares(rollupBlock(ctx).toInt, pk(ctx)))

  private def nispWithHeaders(ctx: BlockchainContext, mutate: Array[Byte] => Array[Byte]): Array[Byte] =
    NispFixtures.rawNisp(score,
      NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))
        .map(s => NispFixtures.shareWithHeader(s, mutate(s.headerBytes))))

  /**
   * The proofs that read a NISP, in the order they run.
   *
   * `FP_NonMatchingCommitment` is deliberately absent: it reads only the eight score bytes and needs
   * three data inputs, so it is neither a consumer of the format gate nor buildable from this shape.
   */
  private def readers(ctx: BlockchainContext): Seq[(String, Contract, Seq[ContextVar])] = Seq(
    ("InvalidFormat", fpInvalidFormat(ctx), Seq.empty),
    ("MalformedGE", fpMalformedGE(ctx), Seq.empty),
    ("NotInWindow", fpNotInWindow(ctx), Seq.empty),
    ("NonUniqueHeaders", fpNonUniqueHeaders(ctx), Seq.empty),
    ("IncorrectN", fpIncorrectN(ctx), Seq(ContextVar.of(3.toByte, NTable.ergoValue))),
    ("InvalidDiff", fpInvalidDiff(ctx), Seq.empty),
    ("TransactionNotIncluded", fpTransactionNotIncluded(ctx), Seq.empty),
    ("MalformedGenesis", fpMalformedGenesis(ctx), Seq.empty))

  /** Every real evaluation box carries the rollup NFT, so the sweep runs against one that does. */
  private def nft: Seq[Token] = Seq(Token(fpTokenId, 1L))

  private def verdictsOn(ctx: BlockchainContext, nisp: Array[Byte]): Seq[(String, String)] =
    readers(ctx).map { case (name, script, extra) =>
      val f = fraud(ctx, script, nisp, score, extraVars = extra, tokens = nft)
      name -> evaluatesCleanly(f.prover, fraudTx(f)())
    }

  // ─── the property the ordering delivers ───────────────────────────────────

  /**
   * The one that matters. Every proof reaches a verdict on a NISP the two gates admit, so no NISP a
   * miner can submit leaves them all unable to answer.
   */
  property("gate: every proof reaches a verdict on a well-formed NISP") {
    withCtx { ctx =>
      val verdicts = verdictsOn(ctx, cleanNisp(ctx))
      verdicts.foreach { case (name, v) => println(f"[order] $name%-24s $v") }
      verdicts.map(_._1) should contain("InvalidDiff")
    }
  }

  /** The same, on a NISP that is well formed but stale — a different verdict, still a verdict. */
  property("gate: every proof reaches a verdict on a well-formed NISP outside the window") {
    withCtx { ctx =>
      val nisp = NispFixtures.nisp(score,
        NispFixtures.includedShares(rollupBlock(ctx).toInt + 9000, pk(ctx)))
      verdictsOn(ctx, nisp).foreach { case (name, v) => println(f"[order:stale] $name%-24s $v") }
    }
  }

  // ─── what each gate is holding ────────────────────────────────────────────
  //
  // Each shape below breaks a proof that comes later. The assertion is that the gate claiming it
  // fires, so the shape is slashed rather than left to throw its way past everyone.

  private def gateHolds(ctx: BlockchainContext, gate: Contract, nisp: Array[Byte]): Unit = {
    val f = fraud(ctx, gate, nisp, score)
    provesFraud(f.prover, fraudTx(f)())
  }

  property("InvalidFormat holds: a height VLQ past Int.MaxValue") {
    withCtx { ctx =>
      val vlq = Array(0x80.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte, 0x08.toByte)
      gateHolds(ctx, fpInvalidFormat(ctx),
        nispWithHeaders(ctx, NispFixtures.withHeightVlq(_, vlq)))
    }
  }

  property("InvalidFormat holds: a version 1 header") {
    withCtx { ctx =>
      gateHolds(ctx, fpInvalidFormat(ctx),
        nispWithHeaders(ctx, NispFixtures.withVersion(_, 1.toByte)))
    }
  }

  property("InvalidFormat holds: a header announcing unparsed bytes") {
    withCtx { ctx =>
      gateHolds(ctx, fpInvalidFormat(ctx), nispWithHeaders(ctx,
        h => NispFixtures.withUnparsedSize(NispFixtures.withVersion(h, 5.toByte), 10.toByte)))
    }
  }

  property("MalformedGE holds: a miner key with an unrecognised prefix") {
    withCtx { ctx =>
      val real = NispFixtures.groupElementOf(
        NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx)).head.headerBytes)
      gateHolds(ctx, fpMalformedGE(ctx),
        nispWithHeaders(ctx, NispFixtures.withGroupElement(_, 4.toByte +: real.tail)))
    }
  }

  property("MalformedGE holds: a miner key whose x is not on the curve") {
    withCtx { ctx =>
      val x = Array.fill(31)(0.toByte) :+ 7.toByte
      gateHolds(ctx, fpMalformedGE(ctx),
        nispWithHeaders(ctx, NispFixtures.withGroupElement(_, 2.toByte +: x)))
    }
  }

  /**
   * `FP_IncorrectN` before `FP_InvalidDiff`, stated as a dependency rather than as a convention: the
   * share's `N` decides which table `powHit` sums over, so a tampered one is answering a different
   * puzzle. `IncorrectN` firing is what stops that reaching `InvalidDiff` at all.
   */
  property("IncorrectN holds: a tampered N, which InvalidDiff would otherwise feed to powHit") {
    withCtx { ctx =>
      val shares = NispFixtures.cleanShares(rollupBlock(ctx).toInt, pk(ctx))
      val nisp = NispFixtures.rawNisp(score,
        shares.map(s => NispFixtures.withN(s.serialize, 1024)))
      val f = fraud(ctx, fpIncorrectN(ctx), nisp, score,
        extraVars = Seq(ContextVar.of(3.toByte, NTable.ergoValue)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the sequence itself ──────────────────────────────────────────────────

  /**
   * The deployed order, which `Evaluator` walks by index and `FP_CONTROL` whitelists by hash.
   *
   * Only two things in it are load bearing, and both are stated as dependencies rather than as
   * positions: the gates precede everyone who reads a header, and `FP_IncorrectN` precedes the proof
   * that feeds N to `powHit`. `FP_NonMatchingCommitment` sits ahead of the gates because it reads
   * eight bytes and depends on nothing.
   */
  property("sequence: every proof that reads a NISP runs after the two gates") {
    withCtx { ctx =>
      val deployed = fraudProofs(ctx).map(_.hashedPropBytesHex)
      deployed.distinct.size shouldBe deployed.size
      deployed.size shouldBe 9

      def at(c: Contract): Int = deployed.indexOf(c.hashedPropBytesHex)
      val format = at(fpInvalidFormat(ctx))
      val ge = at(fpMalformedGE(ctx))

      withClue("the format gate precedes the group-element gate: ") { format should be < ge }
      Seq("NotInWindow" -> fpNotInWindow(ctx),
        "NonUniqueHeaders" -> fpNonUniqueHeaders(ctx),
        "IncorrectN" -> fpIncorrectN(ctx),
        "InvalidDiff" -> fpInvalidDiff(ctx),
        "TransactionNotIncluded" -> fpTransactionNotIncluded(ctx)).foreach { case (name, c) =>
        withClue(s"$name deserializes headers, so it must run after both gates: ") {
          at(c) should be > ge
        }
      }
      withClue("MalformedGenesis walks a NISP, so it must run after the format gate: ") {
        at(fpMalformedGenesis(ctx)) should be > format
      }
      withClue("IncorrectN has to pin N before InvalidDiff feeds it to powHit: ") {
        at(fpIncorrectN(ctx)) should be < at(fpInvalidDiff(ctx))
      }
      withClue("NonMatchingCommitment reads only the score, so it leads: ") {
        at(fpNonMatchingCommitment(ctx)) shouldBe 0
      }
    }
  }

  /**
   * Every proof is reachable through the dispatcher. It used to identify one by recompiling each
   * candidate, and a proof with no case fell through to a throw that `Evaluator` swallowed as a
   * skipped attempt — so a whitelisted proof could be silently unbuildable, which is a fraud nobody
   * can prove.
   */
  property("dispatch: every proof in the set maps to a builder") {
    withCtx { ctx =>
      val rollup = lfsm.states.Rollup(FraudProofOrderingSpec.noDictionary,
        1, 0L, lfsm.states.RollupInfoState.evaluation(0L, 0L, 0L), 0L, 0, hasMiner = false,
        evaluated = false, blockId = "", utxoId = "")
      val evalIn = fundingInput(ctx, miner(ctx))
      fraudProofs(ctx).foreach { c =>
        val built = scala.util.Try(evaluation.FraudProof.genFraudProof(
          set(ctx), ctx, c, Array.fill(32)(0.toByte), rollup, evalIn, evalIn,
          Some(evaluation.CommitmentSource(
            FraudProofOrderingSpec.noDictionary, evalIn, Map.empty))))
        withClue(s"${c.hashedPropBytesHex.take(16)} has no builder: ") {
          built.isSuccess shouldBe true
        }
      }
    }
  }
}

object FraudProofOrderingSpec {
  /** A dictionary view the dispatch check never reads, since it only builds the proof objects. */
  def noDictionary: lfsm.states.AuthenticatedDictionaryView = lfsm.states.PlasmaDictionary.empty()
}
