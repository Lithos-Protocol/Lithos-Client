package contracts.specs.rollup

import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import lfsm.contracts.FraudProofContracts
import nisp.{SuperShare, TransactionProof}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

/**
 * `FP_MalformedGenesis.ergo` — the proof that makes a NISP non-transferable.
 *
 * Every other proof asks whether the ten super-shares are good work. None asks whose. The header's
 * miner key is the *lender's*, shared by every miner drawing that collateral box, so a NISP sitting
 * in the public tree is ten shares anyone could paste into their own submission and be paid for.
 *
 * What separates two miners is that each builds their own genesis transaction, and its holding output
 * carries their own hash in R8. This proof walks the serialized transaction inside the share down to
 * that register and compares it to the accused. `GenesisLayoutSpec` pins the offsets it walks.
 *
 * Nothing is deserialized on the way. The bytes come out of a NISP the accused wrote, so a parser
 * that throws on them is a miner nobody can slash — which is why the last property here feeds it
 * rubbish and demands a verdict rather than an exception.
 */
class FPMalformedGenesisSpec extends AnyPropSpec with EmissionSpecBase with FraudProofSpecBase
  with FraudProofTransitionChecks {

  private val score = 100000L

  /** Compiled once: the sigma compiler mutates shared AST nodes, so one script twice can throw. */
  private def malformedGenesis(ctx: BlockchainContext): Contract =
    FPMalformedGenesisSpec.compiled(ctx, collateralContract(ctx), holdingContract(ctx))

  /** One built genesis transaction, and the pieces a super-share carries from it. */
  private case class Genesis(tx: Array[Byte], collat: InputUTXO, lenderKey: Array[Byte])

  /**
   * A genesis transaction exactly as `CandidateTxBuilder` assembles it, for `minerHash`.
   *
   * `lenderSecret` decides both the collateral box's key and the block's coinbase recipient, so two
   * calls with different secrets are two different rollups.
   */
  private def genesisFor(ctx: BlockchainContext,
                         minerHash: Array[Byte],
                         lenderSecret: java.math.BigInteger = null,
                         wide: Boolean = false,
                         r9: Option[ErgoValue[_]] = None): Genesis = {
    val lenderProver =
      if (lenderSecret == null) lender(ctx) else proverWith(ctx, lenderSecret)
    val height = ctx.getHeight + 1
    val rollup = holdingContract(ctx)
    val permit = if (wide) LFSMHelpers.PERMIT_CEIL else LFSMHelpers.PERMIT_FLOOR
    val (emitted, pub, split) = emissionAt(0, litSupply)
    val finderLIT = pub / 25L
    val poolLIT = pub - finderLIT
    val fat = Long.MaxValue / 8L

    val collatBox = collateralUTXO(ctx, lenderProver, lit = emitted + permit, pub = pub,
      founderSplit = split, rollupHash = rollup.hashedPropBytes,
      feeValue = if (wide) fat / 2L else DUST_BUDGET, value = if (wide) fat else 0L,
      contract = collateralContract(ctx))
    val collatIn = inputAt(collatBox, ctx, 0)

    val founderBoxes = split.map { case (key, amount) =>
      val recipient = Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
        .find(c => java.util.Arrays.equals(c.hashedPropBytes, key)).get
      UTXO(recipient, 1000000L, Seq(Token(LFSMHelpers.LIT_ID, amount)))
    }
    val permitBox = UTXO(contractOf(lenderProver), 1000000L, Seq(Token(LFSMHelpers.LIT_ID, permit)))
    val pos = UTXO(gateContract(ctx), 200000L, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
      Seq(bytesValue(setEntry(lenderProver)))).setCreationHeight(height)
    val finderBox = UTXO(contractOf(miner(ctx)), 200000L, Seq(Token(LFSMHelpers.LIT_ID, finderLIT)))

    val trailing = founderBoxes ++ Seq(permitBox, pos, finderBox)
    val holdingRegs = Seq(emptyTree.ergoValue, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger),
      stateReg(height.toLong, height.toLong, 0L), bytesValue(minerHash)) ++ r9.toSeq
    val holding = UTXO(rollup, collatBox.value - trailing.map(_.value).sum,
      Seq(Token(collatIn.id, 1L), Token(LFSMHelpers.LIT_ID, poolLIT)),
      holdingRegs
      )

    val uTx = TxBuilder(ctx)
      .setInputs(collatIn)
      .setOutputs((holding +: trailing): _*)
      .setPreHeader(ctx.createPreHeader().height(height)
        .minerPk(lenderProver.getAddress.getPublicKeyGE).build())
      .buildTx(0, lenderProver.getAddress)

    Genesis(uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign, collatIn,
      lenderProver.getAddress.getPublicKeyGE.getEncoded.toArray)
  }

  /** Ten shares carrying `tx` and `collat`, mined under `key` — the shape a real NISP has. */
  private def sharesFrom(ctx: BlockchainContext,
                         tx: Array[Byte],
                         collat: Array[Byte],
                         key: Array[Byte]): Seq[Array[Byte]] = {
    val proof = TransactionProof(tx, collat)
    (0 until 10).map { i =>
      val base = NispFixtures.superShare(rollupBlock(ctx).toInt - i, NispFixtures.pkOf(miner(ctx)),
        timestamp = 1700000000000L + i, proof = Some(proof))
      NispFixtures.shareWithHeader(base, NispFixtures.withGroupElement(base.headerBytes, key))
    }
  }

  /**
   * The whole fraud-proof spend.
   *
   * The evaluation box carries its own rollup NFT because every real one does, but this proof never
   * reads it: the NFT it checks is the one inside the share's own transaction.
   */
  private def proofOver(ctx: BlockchainContext,
                        g: Genesis,
                        tx: Array[Byte] = null,
                        collat: Array[Byte] = null,
                        key: Array[Byte] = null): Fraud = {
    val shares = sharesFrom(ctx,
      if (tx == null) g.tx else tx,
      if (collat == null) g.collat.bytes else collat,
      if (key == null) g.lenderKey else key)
    fraud(ctx, malformedGenesis(ctx), NispFixtures.rawNisp(score, shares), score,
      tokens = Seq(Token(fpTokenId, 1L)))
  }

  /** The accused, which is who `fraud` builds the proof against. */
  private def accused(ctx: BlockchainContext): Array[Byte] = contractOf(miner(ctx)).hashedPropBytes

  private def patch(bytes: Array[Byte], at: Int, replacement: Array[Byte]): Array[Byte] =
    bytes.slice(0, at) ++ replacement ++ bytes.drop(at + replacement.length)

  /** A transaction re-pointed at whatever collateral bytes are being supplied with it. */
  private def spending(tx: Array[Byte], collat: Array[Byte]): Array[Byte] =
    patch(tx, 1, Blake2b256.hash(collat))

  // ─── the control ──────────────────────────────────────────────────────────

  property("honest: the accused's own genesis transaction, so the proof declines cleanly") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val f = proofOver(ctx, g)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * The same, with every VLQ-encoded field pushed to its widest honest encoding: a box value and a
   * fee value near the top of a Long, and a permit at the ceiling.
   *
   * Nothing validates these VLQs before this proof runs — the header ones the other proofs read are
   * covered by `FP_InvalidFormat`, but the ones inside a genesis transaction and a collateral box are
   * not. Safety rests on each buffer being wide enough for the largest legal encoding of its field, so
   * this is the property that says those buffers are actually wide enough. A short one would make the
   * parse fail closed and slash an honest miner for holding a lot of ERG.
   */
  property("honest: widest legal VLQ encodings still parse, so the proof declines") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx), wide = true)
      val f = proofOver(ctx, g)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the theft this proof exists to stop ─────────────────────────────────

  /**
   * The headline. A NISP is public the moment it is submitted, so without this the ten shares below
   * are worth a full payout to whoever copies them.
   */
  property("theft: shares whose genesis transaction names another miner are caught") {
    withCtx { ctx =>
      val victim = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
      val g = genesisFor(ctx, victim)
      val f = proofOver(ctx, g)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("theft: an R8 one byte off the accused is caught") {
    withCtx { ctx =>
      val near = accused(ctx).clone()
      near(31) = (near(31) ^ 0x01).toByte
      val g = genesisFor(ctx, near)
      val f = proofOver(ctx, g)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the collateral box is this rollup's ─────────────────────────────────

  /**
   * The regression that matters most here. A miner draws whichever collateral box they like out of
   * the active set, and it is almost never the one whose spend opened the rollup they are submitting
   * to — so a share carrying a different collateral box is the ordinary case, not fraud.
   *
   * Comparing against the evaluation box's own NFT instead of the one inside the share's transaction
   * would fire here, and would therefore slash nearly every honest miner.
   */
  property("different collateral box: the ordinary case, so the proof declines") {
    withCtx { ctx =>
      val other = genesisFor(ctx, accused(ctx), lenderSecret = java.math.BigInteger.valueOf(7007L))
      val f = proofOver(ctx, other)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  property("extension: an R9 existing is not fraudulent") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx), r9 = Some(ErgoValue.of(0)))
      val f = proofOver(ctx, g)
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /** The NFT still has to be the one that box would mint, so a transaction naming another is caught. */
  property("nft: a holding output whose NFT is not the spent box's id is caught") {
    withCtx { ctx =>
      val mine = genesisFor(ctx, accused(ctx))
      val other = genesisFor(ctx, accused(ctx), lenderSecret = java.math.BigInteger.valueOf(9009L))
      // The transaction and the box agree; only the minted NFT names a different box.
      val f = proofOver(ctx, mine, tx = patch(mine.tx, 37, other.collat.id.getBytes))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("mismatch: a transaction spending a box other than the one supplied is caught") {
    withCtx { ctx =>
      val mine = genesisFor(ctx, accused(ctx))
      val other = genesisFor(ctx, accused(ctx), lenderSecret = java.math.BigInteger.valueOf(8008L))
      val f = proofOver(ctx, mine, collat = other.collat.bytes)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the supplied bytes are a real collateral box ────────────────────────

  /**
   * Fabricated bytes with a transaction re-pointed to their hash. The hash equality alone would pass;
   * the script and token checks are what refuse it.
   */
  property("fabricated: arbitrary bytes passed off as a collateral box are caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val fake = Array.fill(1250)(0x33.toByte)
      val f = proofOver(ctx, g, tx = spending(g.tx, fake), collat = fake)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("fabricated: a collateral box under a different script is caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val treeStart = 5 // the value VLQ measured by GenesisLayoutSpec
      val broken = patch(g.collat.bytes, treeStart + 10, Array.fill(8)(0x77.toByte))
      val f = proofOver(ctx, g, tx = spending(g.tx, broken), collat = broken)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("fabricated: a collateral box without the collateral token is caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val bytes = g.collat.bytes
      val tokenPos = bytes.indexOfSlice(LFSMHelpers.COLLAT_TOKEN.getBytes)
      tokenPos should be > 0
      val broken = patch(bytes, tokenPos, Array.fill(32)(0x44.toByte))
      val f = proofOver(ctx, g, tx = spending(g.tx, broken), collat = broken)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the transaction shape ────────────────────────────────────────────────

  /** Extra inputs would move every offset after the first, and fund outputs the dust cannot. */
  property("shape: a transaction declaring two inputs is caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val f = proofOver(ctx, g, tx = patch(g.tx, 0, Array(2.toByte)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  property("shape: a transaction carrying a context extension is caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val f = proofOver(ctx, g, tx = patch(g.tx, 34, Array(1.toByte)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the share was mined for this lender ─────────────────────────────────

  /**
   * The header's miner key is the lender the coinbase pays. A share mined under a different key is a
   * share mined for a different collateral box, whatever transaction it carries.
   */
  property("lender: a share mined under a different key is caught") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val stranger = proverWith(ctx, funderSecret).getAddress.getPublicKeyGE.getEncoded.toArray
      val f = proofOver(ctx, g, key = stranger)
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── the predicate is an exists ───────────────────────────────────────────

  property("stray: one stolen share among nine of the accused's own is caught") {
    withCtx { ctx =>
      val mine = genesisFor(ctx, accused(ctx))
      val victim = genesisFor(ctx, contractOf(proverWith(ctx, otherSecret)).hashedPropBytes)
      val ours = sharesFrom(ctx, mine.tx, mine.collat.bytes, mine.lenderKey)
      val theirs = sharesFrom(ctx, victim.tx, victim.collat.bytes, victim.lenderKey)
      val f = fraud(ctx, malformedGenesis(ctx),
        NispFixtures.rawNisp(score, ours.dropRight(1) :+ theirs.head), score,
        tokens = Seq(Token(mine.collat.id, 1L)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }

  // ─── it must never throw ──────────────────────────────────────────────────

  /**
   * The property the whole no-deserializer design exists for. Each of these is a transaction the
   * parser cannot walk, and every one has to come back as a verdict rather than an exception — a
   * throw is indistinguishable from a proof that never ran, and leaves the miner unslashable.
   */
  property("robustness: unwalkable transactions produce a verdict, never a throw") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      val cases = Seq(
        "truncated to 60 bytes" -> g.tx.take(60),
        "truncated mid-script" -> g.tx.take(200),
        "all zeroes" -> Array.fill(g.tx.length)(0.toByte),
        "all 0xFF" -> Array.fill(g.tx.length)(0xFF.toByte),
        "impossible token count" -> patch(g.tx, 36, Array(0x7F.toByte)),
        "negative token count" -> patch(g.tx, 36, Array(0x80.toByte)),
        "zero outputs" -> patch(g.tx, 37 + g.tx(36).toInt * 32, Array(0.toByte)))

      cases.foreach { case (name, tx) =>
        val f = proofOver(ctx, g, tx = spending(tx, g.collat.bytes))
        val verdict = evaluatesCleanly(f.prover, fraudTx(f)())
        println(f"[robust] $name%-24s $verdict")
        withClue(s"$name: an unwalkable transaction is fraud, not honesty: ") {
          verdict shouldBe "fires"
        }
      }
    }
  }

  property("robustness: a collateral box of rubbish produces a verdict, never a throw") {
    withCtx { ctx =>
      val g = genesisFor(ctx, accused(ctx))
      Seq(Array.emptyByteArray, Array.fill(4)(0xFF.toByte), Array.fill(3000)(0x00.toByte))
        .foreach { collat =>
          val f = proofOver(ctx, g, tx = spending(g.tx, collat), collat = collat)
          evaluatesCleanly(f.prover, fraudTx(f)()) shouldBe "fires"
        }
    }
  }

  transitionChecks("malformedGenesis") { ctx =>
    proofOver(ctx, genesisFor(ctx, contractOf(proverWith(ctx, otherSecret)).hashedPropBytes))
  }
}

object FPMalformedGenesisSpec {
  private var cache: Option[Contract] = None

  /**
   * This spec's own copy, built from the spec base's holding contract so the transactions it
   * assembles are under the same script the proof is compiled against.
   */
  def compiled(ctx: BlockchainContext, collateral: Contract, holding: Contract): Contract =
    synchronized {
      cache.getOrElse {
        val contract = FraudProofContracts.mkMalformedGenesisContract(ctx.getNetworkType, collateral, holding)
        cache = Some(contract)
        contract
      }
    }
}
