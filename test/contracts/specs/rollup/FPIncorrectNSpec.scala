package contracts.specs.rollup

import evaluation.NTable
import nisp.SuperShare
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec

/**
 * `FP_IncorrectN.ergo` — the share's declared `N` has to be the one Autolykos2 uses at its height.
 *
 * `N` sizes the lookup table the PoW hash is computed over, and it is the one field of a super-share
 * that the header does not carry. `FP_InvalidDiff` feeds it straight into `powHit`, so a share
 * declaring a small `N` would be checked against a cheaper puzzle than the one the network solves.
 * That is why this proof runs before that one.
 *
 * `N` is a step function of height with an awkward derivation, so the mapping arrives as a context
 * variable and the contract pins its hash. Supplying the wrong table therefore makes the proof decline
 * rather than misjudge — a wrong table is the prover's mistake, and declining is the safe direction.
 */
class FPIncorrectNSpec extends AnyPropSpec with FraudProofSpecBase with FraudProofTransitionChecks {

  private val score = 100000L

  /** The first two rows of the table: N changes at 614,400. */
  private val genesisN = NTable.lookUp(0)
  private val secondN = NTable.lookUp(614400)

  private def pk(ctx: BlockchainContext) = NispFixtures.pkOf(miner(ctx))

  private def tableVar: ContextVar = ContextVar.of(3.toByte, NTable.ergoValue)

  /** Ten shares at `at`, each carrying whatever `N` the fixture is told to declare. */
  private def shares(ctx: BlockchainContext, at: Int): Seq[SuperShare] =
    (0 until 10).map(i => NispFixtures.superShare(at - i, pk(ctx), timestamp = 1700000000000L + i))

  private def proof(ctx: BlockchainContext,
                    nisp: Array[Byte],
                    table: Seq[ContextVar] = Seq(tableVar)): Fraud =
    fraud(ctx, fpIncorrectN(ctx), nisp, score, extraVars = table)

  private def honestNisp(ctx: BlockchainContext, at: Int): Array[Byte] =
    NispFixtures.nisp(score, shares(ctx, at))

  /** The same shares with `n` written over the `N` each one derived from its height. */
  private def nispDeclaring(ctx: BlockchainContext, at: Int, n: Int): Array[Byte] =
    NispFixtures.rawNisp(score, shares(ctx, at).map(s => NispFixtures.withN(s.serialize, n)))

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: N derived from each share's own height, so the proof declines cleanly") {
    withCtx { ctx =>
      val f = proof(ctx, honestNisp(ctx, rollupBlock(ctx).toInt))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The table is a step function, so a row above the first increase has to work too. */
  property("honest: a share past the first increase carries the second row's N") {
    withCtx { ctx =>
      // 640,000 sits inside [614400, 665600), the row the first increase opens.
      NispFixtures.calcN(640000) shouldBe secondN
      val f = proof(ctx, honestNisp(ctx, 640000))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The last row has no successor, so its `shareHeight >= tableHeight` branch is a separate path. */
  property("honest: a share in the table's final row is accepted") {
    withCtx { ctx =>
      val lastHeight = NTable.N_Table.last._1
      val f = proof(ctx, honestNisp(ctx, lastHeight + 100000))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the fraud ────────────────────────────────────────────────────────────

  property("wrong N: a share declaring an N from a different height range is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, secondN))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  /** The cheap direction: a smaller table makes `powHit` easier, which is what this closes. */
  property("wrong N: a share declaring an N smaller than any table row is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 1024))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("wrong N: a share declaring a negative N is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, -1))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("wrong N: a share declaring zero is caught") {
    withCtx { ctx =>
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 0))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("wrong N: one bad share among nine correct ones is caught") {
    withCtx { ctx =>
      val ss = shares(ctx, rollupBlock(ctx).toInt)
      val bad = NispFixtures.withN(ss.last.serialize, secondN)
      val f = proof(ctx, NispFixtures.rawNisp(score, ss.dropRight(1).map(_.serialize) :+ bad))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the table itself ─────────────────────────────────────────────────────
  //
  // A wrong table is the prover's error, not the miner's, so all three of these decline. Firing on a
  // table the contract cannot vouch for would slash an honest miner on the prover's mistake.

  property("table: a proof with no table at all declines") {
    withCtx { ctx =>
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 1024), table = Seq.empty)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("table: a truncated table declines even against a genuinely wrong N") {
    withCtx { ctx =>
      val short = ErgoValue.of(
        sigma.Colls.fromArray(NTable.N_Table.take(3)),
        ErgoType.pairType(org.ergoplatform.appkit.scalaapi.scalaIntType,
          org.ergoplatform.appkit.scalaapi.scalaIntType))
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 1024),
        table = Seq(ContextVar.of(3.toByte, short)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("table: a table with one row's N altered declines") {
    withCtx { ctx =>
      val tampered = NTable.N_Table.clone()
      tampered(0) = (tampered(0)._1, 1024)
      val v = ErgoValue.of(
        sigma.Colls.fromArray(tampered),
        ErgoType.pairType(org.ergoplatform.appkit.scalaapi.scalaIntType,
          org.ergoplatform.appkit.scalaapi.scalaIntType))
      val f = proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 1024),
        table = Seq(ContextVar.of(3.toByte, v)))
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The hash the contract pins. If this moves, every deployed `FP_IncorrectN` stops working. */
  property("table: the pinned hash still matches the generated table") {
    NTable.toString shouldBe "5c40f60e9dabf1272597810085d9827d41c2db44e3a12b7f07f38d90868e410d"
    genesisN shouldBe 67108864
  }

  transitionChecks("incorrectN") { ctx =>
    proof(ctx, nispDeclaring(ctx, rollupBlock(ctx).toInt, 1024))
  }
}
