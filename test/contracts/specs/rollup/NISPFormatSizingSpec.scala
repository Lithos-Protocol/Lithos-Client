package contracts.specs.rollup

import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.{Coll, Colls}
import work.lithos.mutations.{Contract, Token, TxBuilder, UTXO}

import scala.util.Try

/**
 * Measures serialization limits and compares full-payload AVL witnesses with compact commitments.
 * The raw-value experiment retains the legacy format as a comparison; Holding cost measurements use
 * the current compact format. The payload size envelope remains unchanged.
 */
class NISPFormatSizingSpec extends AnyPropSpec with EmissionSpecBase with RollupSpecBase {

  // ─── the layout, from FP_InvalidFormat ────────────
  //
  // NISP  = [score: 8][10 super-shares]
  // share = [N: 4][header][txProofSize: 2][numLevels: 1][txProof][levels: 33n][yCoord: 32]

  private val SCORE = 8
  private val N_SIZE = 4
  private val SIZE_FIELDS = 3 // txProofSize short + numLevels byte
  private val LEVEL = 33
  private val Y_COORD = 32
  private val SHARES = 10

  /** 130 + vlq(timestamp) + 36 + vlq(height) + 45. */
  private val HDR_MIN = 220 // timestamp 6 bytes, height 3
  private val HDR_MAX = 221 // height passes 2^21 and its VLQ widens to 4

  private def share(txProof: Int, levels: Int, hdr: Int): Int =
    N_SIZE + hdr + SIZE_FIELDS + txProof + LEVEL * levels + Y_COORD

  private def nispSize(txProof: Int, levels: Int, hdr: Int): Int =
    SCORE + SHARES * share(txProof, levels, hdr)

  // ─── 1. the deployed min side is a derivation; the max side is not ────────

  property("layout: the deployed min constants reproduce SUPER_SHARE_MIN and NISP_MIN exactly") {
    // The formula the holding contract states above its own CONST_NISP_MIN.
    share(LFSMHelpers.TX_PROOF_MIN, 1, HDR_MIN) shouldBe 794
    nispSize(LFSMHelpers.TX_PROOF_MIN, 1, HDR_MIN) shouldBe 7948

    // and TX_PROOF_MIN is itself 2 + TX_SIZE_MIN + COLLAT_BOX_MIN, which is why it closes
    2 + LFSMHelpers.TX_SIZE_MIN + LFSMHelpers.COLLAT_BOX_MIN shouldBe LFSMHelpers.TX_PROOF_MIN
  }

  /**
   * The same derivation on the max side. If these drift apart, one share can be over-sized inside a
   * NISP the holding contract still accepts, which is what the fraud proof would then punish.
   */
  property("layout: the max constants close against CONST_NISP_MAX (regression)") {
    val implied = nispSize(LFSMHelpers.TX_PROOF_MAX, LFSMHelpers.NUM_LVLS_MAX, HDR_MAX)
    implied should be <= LFSMHelpers.NISP_MAX
    withClue("ten maximal shares must fit the envelope Holding enforces: ") {
      LFSMHelpers.NISP_MAX - implied should be < 10 // the +1 for Holding's strict <, and no slack
    }
    println(s"[layout] max constants imply a ${implied}-byte NISP; CONST_NISP_MAX is " +
      s"${LFSMHelpers.NISP_MAX}")
  }

  /** The client mirror and the contract have to agree or submissions are sized against the wrong floor. */
  property("layout: LFSMHelpers.NISP_MIN agrees with the holding contract") {
    LFSMHelpers.NISP_MIN shouldBe 7948
    LFSMHelpers.NISP_MIN shouldBe nispSize(LFSMHelpers.TX_PROOF_MIN, 1, HDR_MIN)
  }

  /** Both ends derived from the layout, with no hand-chosen number left in the envelope. */
  property("layout: the whole envelope is a derivation") {
    nispSize(LFSMHelpers.TX_PROOF_MIN, 1, HDR_MIN) shouldBe LFSMHelpers.NISP_MIN
    nispSize(LFSMHelpers.TX_PROOF_MAX, LFSMHelpers.NUM_LVLS_MAX, HDR_MAX) + 1 shouldBe LFSMHelpers.NISP_MAX
    println(s"[layout] NISP_MIN=${LFSMHelpers.NISP_MIN} NISP_MAX=${LFSMHelpers.NISP_MAX} " +
      s"TX_PROOF_MIN=${LFSMHelpers.TX_PROOF_MIN} TX_PROOF_MAX=${LFSMHelpers.TX_PROOF_MAX}")
  }

  // ─── 2. what a real super-share is made of ────────────────────────────────

  /**
   * The collateral box at its structural maximum. R4-R8 are pinned by the emission contract and the
   * enforcer refuses R9, so the only freedom left is how wide each pinned value serialises.
   */
  private def collateralSizes(ctx: BlockchainContext): (Int, Int, Int) = {
    val prover = lender(ctx)
    def sizeOf(box: UTXO): Int = inputAt(box, ctx, 0).bytes.length
    val founders = Seq(
      (LFSMHelpers.FOUNDER_1.hashedPropBytes, 100L * LIT),
      (LFSMHelpers.FOUNDER_2.hashedPropBytes, 25L * LIT),
      (LFSMHelpers.FOUNDER_3.hashedPropBytes, 15L * LIT))

    val launch = collateralUTXO(ctx, prover, lit = 500L * LIT + LFSMHelpers.PERMIT_FLOOR,
      pub = 500L * LIT, founderSplit = founders, contract = collateralContract(ctx))
    val worst = collateralUTXO(ctx, prover, lit = 500L * LIT + LFSMHelpers.PERMIT_CEIL,
      pub = 500L * LIT, founderSplit = founders,
      feeValue = Long.MaxValue / 8L, value = Long.MaxValue / 8L, contract = collateralContract(ctx))
    val lean = collateralUTXO(ctx, prover, lit = 0L, pub = 0L, founderSplit = Seq.empty,
      contract = collateralContract(ctx))
    (sizeOf(lean), sizeOf(launch), sizeOf(worst))
  }

  /** Measured rather than pinned: it moves whenever the collateral contract is recompiled. */
  private def collatMaxOf(ctx: BlockchainContext): Int = collateralSizes(ctx)._3

  /** Worst honest txProof under a given rollup script: launch shape plus 5 bytes, plus the box max. */
  private def worstProofUnder(ctx: BlockchainContext, rollup: Contract): Int = {
    val (tx, _) = genesisTxSizeWith(ctx, rollup)
    2 + (tx + 5) + collatMaxOf(ctx)
  }

  /** The genesis transaction exactly as `CandidateTxBuilder.buildGenesis` assembles it. */
  private def genesisTxSize(ctx: BlockchainContext): (Int, Int) =
    genesisTxSizeWith(ctx, holdingContract(ctx))

  private def genesisTxSizeWith(ctx: BlockchainContext, rollup: Contract): (Int, Int) = {
    val lenderProver = lender(ctx)
    val finderProver = miner(ctx)
    val height = ctx.getHeight + 1
    val permit = LFSMHelpers.PERMIT_FLOOR
    val (emitted, pub, split) = emissionAt(0, litSupply)
    val finderLIT = pub / 25L
    val poolLIT = pub - finderLIT

    val collatBox = collateralUTXO(ctx, lenderProver, lit = emitted + permit, pub = pub,
      founderSplit = split, rollupHash = rollup.hashedPropBytes,
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
    val finderBox = UTXO(contractOf(finderProver), 200000L, Seq(Token(LFSMHelpers.LIT_ID, finderLIT)))

    val trailing = founderBoxes ++ Seq(permitBox, pos, finderBox)
    // Token 0 is the rollup NFT, minted from the collateral box's own id; LIT sits behind it.
    val holding = UTXO(rollup, collatBox.value - trailing.map(_.value).sum,
      Seq(Token(collatIn.id, 1L), Token(LFSMHelpers.LIT_ID, poolLIT)),
      Seq(emptyTree.ergoValue, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger),
        stateReg(height.toLong, height.toLong, 0L),
        bytesValue(contractOf(finderProver).hashedPropBytes)))

    val uTx = TxBuilder(ctx)
      .setInputs(collatIn)
      .setOutputs((holding +: trailing): _*)
      .setPreHeader(ctx.createPreHeader().height(height)
        .minerPk(lenderProver.getAddress.getPublicKeyGE).build())
      .buildTx(0, lenderProver.getAddress)
    accepts(finderProver, uTx) // a size taken off a transaction the contract rejects means nothing

    (uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign.length, collatIn.bytes.length)
  }

  property("sizing: the measured inputs to a super-share") {
    withCtx { ctx =>
      val (lean, launch, worst) = collateralSizes(ctx)
      val (txSize, collatInTx) = genesisTxSize(ctx)
      val txProof = 2 + txSize + collatInTx

      println(s"[sizing] Collateral_Mainnet ergoTree          = ${collateralContract(ctx).ergoTree.bytes.length}")
      println(s"[sizing] Holding ergoTree                     = ${holdingContract(ctx).ergoTree.bytes.length}")
      println(s"[sizing] collateral box, post-emission        = $lean")
      println(s"[sizing] collateral box, launch shape         = $launch")
      println(s"[sizing] collateral box, STRUCTURAL MAX       = $worst")
      println(s"[sizing] genesis tx messageToSign             = $txSize")
      println(s"[sizing] txProof = 2 + tx + collateral        = $txProof")

      // The bound the enforcer's R9 refusal buys: the box can no longer be attacker-sized. Every
      // byte here is ten off the NISP ceiling, so this is a drift guard on a number that is meant
      // to be watched, not a limit anything enforces.
      worst should be < 1300

      println("[sizing] honest 10-share NISP by Merkle depth (hdr 221):")
      Seq(1, 4, 8, 9, 12, 16).foreach { l =>
        val n = nispSize(txProof, l, HDR_MAX)
        val verdict = if (n >= LFSMHelpers.NISP_MAX) s"REJECTED by CONST_NISP_MAX (over by ${n - LFSMHelpers.NISP_MAX})" else "ok"
        println(f"[sizing]   levels=$l%-3d share=${share(txProof, l, HDR_MAX)}%-5d NISP=$n%-6d $verdict")
      }
    }
  }

  /**
   * Merkle depth is set by how many transactions each block held, not by the miner, so the ceiling
   * has to clear every depth the fraud proof admits or honest miners are locked out.
   */
  property("sizing: an honest NISP fits at every Merkle depth up to CONST_NUM_LVLS_MAX (regression)") {
    withCtx { ctx =>
      val txProof = worstProofUnder(ctx, holdingContract(ctx))
      (1 to LFSMHelpers.NUM_LVLS_MAX).foreach { levels =>
        withClue(s"honest NISP at $levels Merkle levels: ") {
          nispSize(txProof, levels, HDR_MAX) should be < LFSMHelpers.NISP_MAX
        }
      }
      // Only the guard is measured: the script it replaced no longer exists to compare against.
    }
  }

  // ─── 2b. contract-size leverage ───────────────────────────────────────────

  /** Raw witness bytes for a 256-neighbour fixture; excludes transaction and serialization overhead. */
  private lazy val compactWitnessOverhead: Int = {
    val proofs = commitmentProofs(256)
    proofs.lookup + proofs.remove
  }

  private def fraudWitnessBytes(nisp: Int): Int = nisp + compactWitnessOverhead

  /** A NISP ceiling has to clear the honest worst case: 16 Merkle levels, 4-byte height VLQ. */
  private def nispCeilingFor(txProof: Int): Int = nispSize(txProof, 16, HDR_MAX)

  /**
   * A byte off either contract is ten bytes off the NISP ceiling and ten off a payload-dependent fraud witness: the
   * genesis transaction rides in all ten super-shares, and a fraud proof carries one payload alongside compact AVL proofs.
   */
  property("leverage: what trimming Holding or Collateral_Mainnet actually buys") {
    withCtx { ctx =>
      val holdingTree = holdingContract(ctx).ergoTree.bytes.length
      val collatTree = collateralContract(ctx).ergoTree.bytes.length
      val (realTx, collatBytes) = genesisTxSize(ctx)

      // Swap the rollup script for a one-byte one; the difference is what the script costs the tx.
      val (tinyTx, _) = genesisTxSizeWith(ctx, Contract.SIGMA_TRUE)
      val holdingCostInTx = realTx - tinyTx

      println(s"[leverage] Holding ergoTree                 = $holdingTree bytes")
      println(s"[leverage] its cost inside the genesis tx   = $holdingCostInTx bytes " +
        s"(tx $realTx with it, $tinyTx with a 1-byte script)")
      println(s"[leverage] Collateral_Mainnet ergoTree      = $collatTree bytes")
      println(s"[leverage] collateral box total             = $collatBytes bytes " +
        s"(the tree is ${100 * collatTree / collatBytes}%% of it)")

      // A byte is a byte: the ergoTree goes into the output candidate verbatim.
      holdingCostInTx shouldBe (holdingTree - Contract.SIGMA_TRUE.ergoTree.bytes.length)

      val txProof = 2 + realTx + collatBytes
      println(s"[leverage] today: txProof=$txProof ceiling=${nispCeilingFor(txProof)} " +
        s"fraudWitness=${fraudWitnessBytes(nispCeilingFor(txProof))}")
      println("[leverage] bytes trimmed from the two contracts, and where it lands:")
      Seq(0, 100, 200, 400, 600, 800, 1000).foreach { saved =>
        val tp = txProof - saved
        val ceiling = nispCeilingFor(tp)
        val fp = fraudWitnessBytes(ceiling)
        println(f"[leverage]   -$saved%-5d txProof=$tp%-6d NISP ceiling=$ceiling%-6d " +
          f"fraudWitness=$fp%-7d ${if (fp <= 90000) "under 90k" else "OVER 90k"}")
      }
    }
  }

  /**
   * The 90 KB target, solved for the ceiling rather than guessed at.
   * `NISP + compact lookup + compact removal <= 90000` bounds raw witness bytes.
   * The separate payload must also fit its context-variable serialization limit.
   */
  property("leverage: the payload limit under a 90 KB witness budget and context serialization cap") {
    withCtx { ctx =>
      val txProof = worstProofUnder(ctx, holdingContract(ctx))
      val budgetCeiling = math.min(90000 - compactWitnessOverhead, MAX_CTXVAR_BYTES)
      val needed = nispCeilingFor(txProof)

      println(s"[budget] a 90000-byte witness budget and context cap allow a payload of $budgetCeiling")
      println(s"[budget] the honest worst case needs $needed (16 levels, txProof $txProof)")
      println(s"[budget] headroom = ${budgetCeiling - needed} bytes " +
        s"(${(budgetCeiling - needed) / 10} per share)")

      // It fits, but only just — which is the reason the leverage table above matters.
      needed should be <= budgetCeiling
    }
  }

  /**
   * What a smaller rollup or collateral script buys, in the units the constants are set in. Every
   * ergoTree byte is one transaction byte, asserted above, so these are arithmetic on anchors.
   */
  property("leverage: sizing the constants for a smaller Holding script") {
    val TX_ANCHOR = 593 // genesis tx bytes that are not the rollup script (NFT entry included)
    val COLLAT_ANCHOR = 327 // collateral box bytes that are not the collateral script, worst case

    def row(holdingTree: Int, collatTree: Int, txSlack: Int, collatSlack: Int): String = {
      val txSize = TX_ANCHOR + holdingTree
      val collatBox = COLLAT_ANCHOR + collatTree
      val honestProof = 2 + txSize + collatBox
      val honestCeiling = nispCeilingFor(honestProof)

      // the constant set, with headroom on each half
      val txMax = txSize + txSlack
      val collatMax = collatBox + collatSlack
      val proofMax = 2 + txMax + collatMax
      val nispMax = nispCeilingFor(proofMax) + 1
      val fp = fraudWitnessBytes(nispMax - 1)

      f"holding=$holdingTree%-5d collat=$collatTree%-5d | honest ceiling=$honestCeiling%-6d | " +
        f"TX_SIZE_MAX=$txMax%-5d TX_PROOF_MAX=$proofMax%-5d NISP_MAX=$nispMax%-6d " +
        f"fraudWitness=$fp%-7d ${if (fp <= 90000) "under 90k" else "OVER 90k"}"
    }

    println("[shave] illustrative script sizes and headroom:")
    println(s"[shave]   ${row(403, 783, txSlack = 543, collatSlack = 90)}")

    println("[shave] Holding trimmed to 200 bytes, same headroom — the direct answer:")
    println(s"[shave]   ${row(200, 783, txSlack = 543, collatSlack = 90)}")

    println("[shave] Holding at 200 AND the tx headroom tightened to ~150 bytes:")
    println(s"[shave]   ${row(200, 783, txSlack = 150, collatSlack = 90)}")

    println("[shave] both scripts trimmed, tight headroom:")
    Seq((200, 600), (200, 500), (150, 500)).foreach { case (h, c) =>
      println(s"[shave]   ${row(h, c, txSlack = 150, collatSlack = 90)}")
    }

    // The direct answer, asserted: 203 bytes off the script is 2030 off the ceiling.
    val before = nispCeilingFor(2 + (TX_ANCHOR + 403) + 543 + (COLLAT_ANCHOR + 783) + 90) + 1
    val after = nispCeilingFor(2 + (TX_ANCHOR + 200) + 543 + (COLLAT_ANCHOR + 783) + 90) + 1
    before - after shouldBe 2030
    println(s"[shave] Holding 403 -> 200 moves NISP_MAX $before -> $after (-${before - after}), " +
      s"and the fraud proof ${fraudWitnessBytes(before - 1)} -> ${fraudWitnessBytes(after - 1)} " +
      s"(-${fraudWitnessBytes(before - 1) - fraudWitnessBytes(after - 1)})")
  }

  /**
   * Why no collateral-box or tx-size maximum is needed: collatBoxSize is txProofSize - 2 - txSize,
   * so TX_PROOF_MAX and TX_SIZE_MIN already bound it from both ends. A redundant cap can only
   * tighten a sound bound, and one set slightly too small rejects honest shares forever.
   */
  property("constants: TX_PROOF_MAX alone bounds the collateral box, so no COLLAT_BOX_MAX is needed") {
    withCtx { ctx =>
      val TX_PROOF_MAX = 2106
      val (honestTx, honestCollat) = genesisTxSize(ctx)

      // What the existing conditions already imply, with no new constant beyond TX_PROOF_MAX:
      //   collatBoxSize = txProofSize - 2 - txSize, txSize >= TX_SIZE_MIN
      val impliedCollatMax = TX_PROOF_MAX - 2 - LFSMHelpers.TX_SIZE_MIN
      val impliedTxMax = TX_PROOF_MAX - 2 - LFSMHelpers.COLLAT_BOX_MIN

      println(s"[constants] with TX_PROOF_MAX=$TX_PROOF_MAX and the existing minima:")
      println(s"[constants]   collateral box is implicitly bounded at $impliedCollatMax " +
        s"(honest max 1110, measured $honestCollat)")
      println(s"[constants]   genesis tx is implicitly bounded at $impliedTxMax " +
        s"(honest $honestTx)")

      // Neither implicit bound can reject an honest share — that is what makes them sufficient.
      impliedCollatMax should be > 1110
      impliedTxMax should be > honestTx

      // And the NISP arithmetic depends only on txProofSize, so the split never enters it.
      val worstProof = 2 + honestTx + 1110
      worstProof should be < TX_PROOF_MAX
      println(s"[constants] worst honest txProof = $worstProof, ceiling ${nispCeilingFor(TX_PROOF_MAX)}")
      println("[constants] => the whole change is TX_PROOF_MAX and CONST_NISP_MAX. Two numbers.")
    }
  }

  /**
   * The worst honest genesis transaction. Its size is not structurally bounded and does not need to
   * be: the miner builds it, and padding it blows their own NISP past the ceiling. So TX_PROOF_MAX
   * only has to cover the worst honest shape, which is far smaller than a hostile bound.
   */
  property("worstcase: the genesis transaction with the deployed Holding script") {
    withCtx { ctx =>
      val lenderProver = lender(ctx)
      val finderProver = miner(ctx)
      val height = ctx.getHeight + 1
      val rollup = holdingContract(ctx)

      def genesis(permit: Long, wideValues: Boolean, founderEra: Boolean): (Int, Int) = {
        val (emitted, pub, split) = emissionAt(if (founderEra) 0 else PRIV_LENGTH * EPOCH_LENGTH, litSupply)
        val finderLIT = pub / 25L
        val poolLIT = pub - finderLIT
        val wide = Long.MaxValue / 8L

        val collatBox = collateralUTXO(ctx, lenderProver, lit = emitted + permit, pub = pub,
          founderSplit = split, rollupHash = rollup.hashedPropBytes,
          feeValue = if (wideValues) wide / 2L else DUST_BUDGET,
          value = if (wideValues) wide else 0L,
          contract = collateralContract(ctx))
        val collatIn = inputAt(collatBox, ctx, 0)

        val founderBoxes = split.map { case (key, amount) =>
          val recipient = Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
            .find(c => java.util.Arrays.equals(c.hashedPropBytes, key)).get
          UTXO(recipient, 1000000L, Seq(Token(LFSMHelpers.LIT_ID, amount)))
        }
        val permitBox =
          if (permit > 0) Seq(UTXO(contractOf(lenderProver), 1000000L, Seq(Token(LFSMHelpers.LIT_ID, permit))))
          else Seq.empty[UTXO]
        val pos = UTXO(gateContract(ctx), 200000L, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
          Seq(bytesValue(setEntry(lenderProver)))).setCreationHeight(height)
        val finderBox =
          if (finderLIT > 0) Seq(UTXO(contractOf(finderProver), 200000L, Seq(Token(LFSMHelpers.LIT_ID, finderLIT))))
          else Seq.empty[UTXO]

        val trailing = founderBoxes ++ permitBox ++ Seq(pos) ++ finderBox
        val holding = UTXO(rollup, collatBox.value - trailing.map(_.value).sum,
          Seq(Token(collatIn.id, 1L)) ++
            (if (poolLIT > 0) Seq(Token(LFSMHelpers.LIT_ID, poolLIT)) else Seq.empty[Token]),
          Seq(emptyTree.ergoValue, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger),
            stateReg(height.toLong, height.toLong, 0L),
            bytesValue(contractOf(finderProver).hashedPropBytes)))

        val uTx = TxBuilder(ctx)
          .setInputs(collatIn)
          .setOutputs((holding +: trailing): _*)
          .setPreHeader(ctx.createPreHeader().height(height)
            .minerPk(lenderProver.getAddress.getPublicKeyGE).build())
          .buildTx(0, lenderProver.getAddress)
        accepts(finderProver, uTx)
        (uTx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign.length, collatIn.bytes.length)
      }

      val shapes = Seq(
        ("launch: founders, permit floor, narrow values", genesis(LFSMHelpers.PERMIT_FLOOR, false, true)),
        ("founders, permit CEILING, narrow values", genesis(LFSMHelpers.PERMIT_CEIL, false, true)),
        ("founders, permit CEILING, WIDE values", genesis(LFSMHelpers.PERMIT_CEIL, true, true)),
        ("post-founder era, permit ceiling, wide", genesis(LFSMHelpers.PERMIT_CEIL, true, false)),
        ("no permit, founders, wide values", genesis(0L, true, true)))

      shapes.foreach { case (name, (tx, box)) =>
        println(f"[worstcase] ${name}%-46s tx=$tx%-6d collat=$box%-6d txProof=${2 + tx + box}")
      }

      val worstProof = shapes.map { case (_, (tx, box)) => 2 + tx + box }.max
      println(s"[worstcase] worst honest txProof (Holding unchanged at " +
        s"${rollup.ergoTree.bytes.length} bytes) = $worstProof")

      Seq(0, 100, 200, 400).foreach { margin =>
        val proofMax = worstProof + margin
        val nispMax = nispCeilingFor(proofMax) + 1
        val fpTx = fraudWitnessBytes(nispMax - 1)
        println(f"[worstcase]   margin=$margin%-4d TX_PROOF_MAX=$proofMax%-5d NISP_MAX=$nispMax%-6d " +
          f"fraudWitness=$fpTx%-7d slack=${98304 - fpTx}%-6d ${if (fpTx < 98304) "fits" else "OVER"}")
      }
    }
  }

  /**
   * What a guard-shaped rollup script buys. Only the box's propositionBytes ride in the genesis
   * transaction, so moving the logic into a context variable takes those bytes off txProof. The
   * logic is then paid for by the submission transaction, which is inside no NISP.
   */
  property("guard: the envelope a smaller Holding script buys") {
    withCtx { ctx =>
      val deployedTree = holdingContract(ctx).ergoTree.bytes.length
      val stubTree = Contract.SIGMA_TRUE.ergoTree.bytes.length
      val (deployedTx, collatBox) = genesisTxSize(ctx)
      val (stubTx, _) = genesisTxSizeWith(ctx, Contract.SIGMA_TRUE)

      // Proven linear: every ergoTree byte is one output-candidate byte.
      deployedTx - stubTx shouldBe (deployedTree - stubTree)
      // The worst honest shape runs 5 bytes over the launch one (wide values, permit ceiling).
      val worstOverLaunch = 5
      def txAt(treeBytes: Int): Int = stubTx + (treeBytes - stubTree) + worstOverLaunch
      def proofAt(treeBytes: Int): Int = 2 + txAt(treeBytes) + collatMaxOf(ctx)

      println(s"[guard] deployed Holding ergoTree = $deployedTree, worst honest txProof = ${proofAt(deployedTree)}")
      println(s"[guard] collateral box is fixed at ${collatMaxOf(ctx)} whatever Holding does")
      println("[guard] tree  txProof | keep NISP_MAX 29629      | re-derive at a 100-byte margin")
      Seq(deployedTree, 300, 200, 137, 100).foreach { tree =>
        val proof = proofAt(tree)

        // (a) spend the saving as upgrade margin: NISP_MAX unchanged, TX_PROOF_MAX unchanged
        val marginIfKept = LFSMHelpers.TX_PROOF_MAX - proof
        val fpIfKept = fraudWitnessBytes(LFSMHelpers.NISP_MAX - 1)

        // (b) spend it as transaction headroom: re-derive the ceiling from the new worst case
        val proofMax = proof + 100
        val nispMax = nispCeilingFor(proofMax) + 1
        val fpIfRederived = fraudWitnessBytes(nispMax - 1)

        println(f"[guard] $tree%-5d $proof%-8d | margin=$marginIfKept%-4d fpWitness=$fpIfKept%-6d " +
          f"slack=${98304 - fpIfKept}%-6d | NISP_MAX=$nispMax%-6d fpWitness=$fpIfRederived%-6d " +
          f"slack=${98304 - fpIfRederived}")
      }

      // A 137-byte guard has to leave the deployed ceiling with real upgrade room, since that is
      // the option that changes no constants at all.
      val guardProof = proofAt(137)
      // What is left over for a larger replacement rollup script once the guard, the rollup NFT and
      // the bond ledger have taken their share.
      (LFSMHelpers.TX_PROOF_MAX - guardProof) should be > 150
      println(s"[guard] at 137 bytes the deployed TX_PROOF_MAX 2174 leaves " +
        s"${LFSMHelpers.TX_PROOF_MAX - guardProof} bytes of upgrade margin — a replacement " +
        s"guard may be up to ${137 + (LFSMHelpers.TX_PROOF_MAX - guardProof)} bytes")
    }
  }

  /**
   * The rollup NFT costs txProof bytes twice over: the numNFT fold enlarges the collateral box, and
   * the token entry enlarges the holding box. Both are multiplied by ten into the NISP ceiling, so
   * this is what says whether the guard still leaves the envelope closed.
   */
  property("nft: the holding guard absorbs the rollup NFT's cost") {
    withCtx { ctx =>
      val guard = holdingContract(ctx)
      val guardProof = worstProofUnder(ctx, guard)
      val honestNisp = nispCeilingFor(guardProof)

      println(s"[nft] Collateral_Mainnet ergoTree    = ${collateralContract(ctx).ergoTree.bytes.length}")
      println(s"[nft] collateral box, structural max = ${collatMaxOf(ctx)}")
      println(s"[nft] Holding_Guard ergoTree         = ${guard.ergoTree.bytes.length}")
      println(s"[nft] worst honest txProof           = $guardProof against TX_PROOF_MAX ${LFSMHelpers.TX_PROOF_MAX}" +
        s" (${LFSMHelpers.TX_PROOF_MAX - guardProof} spare)")
      println(s"[nft] honest NISP at 16 levels       = $honestNisp against CONST_NISP_MAX ${LFSMHelpers.NISP_MAX}")

      // The rollup NFT spends most of the margin the guard bought, so these two assertions are
      // what say the envelope still closes.
      guardProof should be <= LFSMHelpers.TX_PROOF_MAX
      honestNisp should be < LFSMHelpers.NISP_MAX
    }
  }

  // ─── 3. AVL proof sizing at max NISP bytes ────────────────────────────────

  private def proofBytes(v: ErgoValue[_]): Int =
    v.getValue.asInstanceOf[Coll[Byte]].length

  private def keyFor(i: Int): Array[Byte] =
    Blake2b256.hash(Array((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, i.toByte))

  /** `filler` is what the OTHER entries hold — varied deliberately, see the first property below. */
  private def fill(n: Int, filler: Int): Seq[(Array[Byte], Array[Byte])] =
    (0 until n).map(i => keyFor(i) -> nispBytes(1000L + i, filler))

  private case class Proofs(insert: Int, lookup: Int, remove: Int)

  private def proofsAt(treeSize: Int, nisp: Int, filler: Int, compact: Boolean = false): Proofs = {
    val key = keyFor(1000000)
    val payload = nispBytes(5000L, nisp)
    val bytes = if (compact) _root_.nisp.NispCommitment.fromPayload(payload).bytes else payload
    val entries = fill(treeSize, filler)
    val values = if (compact) entries.map { case (k, raw) =>
      k -> _root_.nisp.NispCommitment.fromPayload(raw).bytes
    } else entries

    // Insert: the tree does NOT yet hold the key, which is the submission shape.
    val insertTree = treeWith(values)
    val insert = proofBytes(insertTree.insert(key -> bytes).proof.ergoValue)

    // Lookup and remove: the tree already holds it, which is the fraud-proof shape. lookUp first —
    // it does not move the digest, so both proofs are against the same input tree.
    val readTree = treeWith(values :+ (key -> bytes))
    val lookup = proofBytes(readTree.lookUp(key).proof.ergoValue)
    val remove = proofBytes(readTree.delete(key).proof.ergoValue)

    Proofs(insert, lookup, remove)
  }

  private val MAX_CTXVAR_BYTES = 65535

  private def commitmentProofs(treeSize: Int): Proofs =
    proofsAt(treeSize, LFSMHelpers.NISP_MIN, LFSMHelpers.NISP_MIN, compact = true)

  property("avl: commitment witnesses carry one payload separately, independent of AVL value size") {
    _root_.nisp.NispCommitment.EntrySize shouldBe SCORE + 32
    Seq(0, 1, 8, 64, 256, 1024).foreach { miners =>
      val proofs = commitmentProofs(miners)
      Seq(proofs.insert, proofs.lookup, proofs.remove).foreach(_ should be < MAX_CTXVAR_BYTES)
      Seq(LFSMHelpers.NISP_MAX - 1, 34999, 59999).foreach { payload =>
        proofsAt(miners, payload, payload, compact = true) shouldBe proofs
        println(s"[avl] miners=$miners payload=$payload proofs=$proofs " +
          s"submissionWitness=${payload + proofs.insert} " +
          s"payloadFraudWitness=${payload + proofs.lookup + proofs.remove} " +
          s"payloadIndependentFraudWitness=${proofs.lookup + proofs.remove}")
      }
    }
    fraudWitnessBytes(34999) - fraudWitnessBytes(24999) shouldBe 10000
  }

  property("avl: compact Payout batch witnesses serialize without NISP payloads") {
    val entries = fill(256, LFSMHelpers.NISP_MAX - 1)
    Seq(1, 2, 8, 32, 128, 256).foreach { batch =>
      val tree = commitmentTree(entries)
      val keys = entries.take(batch).map(_._1)
      val lookup = tree.lookUp(keys: _*).proof.ergoValue
      val remove = tree.delete(keys: _*).proof.ergoValue
      Seq(lookup, remove).foreach { proof =>
        proofBytes(proof) should be <= MAX_CTXVAR_BYTES
        proof.toHex should not be empty
      }
      // Witness serialization alone does not establish a transaction's cost or output-size limit.
      println(s"[payout] batch=$batch lookup=${proofBytes(lookup)} remove=${proofBytes(remove)} " +
        s"total=${proofBytes(lookup) + proofBytes(remove)}")
    }
  }

  /**
   * Base serialization fixture with compact proofs and a separate payload. It omits signatures,
   * script dispatch and proof-specific evidence, so this is not a production transaction bound.
   */
  property("budget: compact fraud-proof base transaction carries the payload once") {
    withCtx { ctx =>
      val MAX_TX_SIZE = 98304 // ergo application.conf: maxTransactionSize = 98304 // 96 kb

      def fraudTxBytes(nisp: Int, treeSize: Int): (Int, Int) = {
        val entries = fill(treeSize, nisp)
        val key = entries.head._1
        val tree = commitmentTree(entries)
        val inTree = tree.ergoValue
        val lookup = tree.lookUp(key)
        val removal = tree.delete(key)
        val outTree = tree.ergoValue
        val proofTotal = proofBytes(lookup.proof.ergoValue) + proofBytes(removal.proof.ergoValue)

        // INPUTS(0) eval box, INPUTS(1) the FP box carrying the vars; OUTPUTS(0) eval successor.
        val evalIn = UTXO(evalContract(ctx), boxValue, Seq.empty[Token], Seq(
          inTree, ErgoValue.of(treeSize), ErgoValue.of(BigInt(9999L).bigInteger),
          stateReg(ctx.getHeight.toLong, ctx.getHeight.toLong, 0L)))
          .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)

        val fpIn = UTXO(Contract.SIGMA_TRUE, 1000000L)
          .toInput(ctx, ErgoId.create(dummyTxId), 1.toShort)
          .setCtxVars(
            ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(key), scalaByteType)),
            ContextVar.of(1.toByte, lookup.proof.ergoValue),
            ContextVar.of(2.toByte, removal.proof.ergoValue),
            ContextVar.of(_root_.nisp.NispCommitment.PayloadContextSlot, bytesValue(entries.head._2)))

        val evalOut = UTXO(evalContract(ctx), boxValue, Seq.empty[Token], Seq(
          outTree, ErgoValue.of(treeSize - 1), ErgoValue.of(BigInt(0L).bigInteger),
          stateReg(ctx.getHeight.toLong, ctx.getHeight.toLong, 0L)))

        val tx = build(ctx, Seq(evalIn, fpIn), Seq(evalOut), miner(ctx).getAddress)
        (tx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign.length, proofTotal)
      }

      Seq(LFSMHelpers.NISP_MAX - 1, 34999, 59999).foreach { payload =>
        val (txBytes, proofs) = fraudTxBytes(payload, 256)
        val overhead = txBytes - payload - proofs
        overhead should be > 0
        txBytes should be < MAX_TX_SIZE
        println(s"[budget] payload=$payload proofs=$proofs overhead=$overhead baseTx=$txBytes " +
          s"slack=${MAX_TX_SIZE - txBytes}")
      }
      val (smallTx, smallProofs) = fraudTxBytes(LFSMHelpers.NISP_MAX - 1, 256)
      val (largeTx, largeProofs) = fraudTxBytes(LFSMHelpers.NISP_MAX - 1 + 1000, 256)
      largeProofs shouldBe smallProofs
      largeTx - smallTx shouldBe 1000
    }
  }

  /**
   * Compare the legacy full-payload tree with deployed score/hash commitments. Payload-dependent
   * fraud proofs carry one original NISP; Payout only needs commitment scores.
   */
  property("comparison: deployed commitments versus legacy full-payload AVL values") {
    val nisp = LFSMHelpers.NISP_MAX - 1

    val legacy = proofsAt(treeSize = 256, nisp = nisp, filler = nisp)
    val digested = proofsAt(treeSize = 256, nisp = nisp, filler = nisp, compact = true)

    val legacyFp = legacy.lookup + legacy.remove
    val digestedFp = nisp + digested.lookup + digested.remove // the NISP now rides separately
    val legacySub = nisp + legacy.insert
    val digestedSub = nisp + digested.insert

    println(s"[dedup] ---- 256 miners, ${nisp}-byte NISPs ----")
    println(f"[dedup] fraud proof: NISP in tree = $legacyFp%-8d digest in tree = $digestedFp%-8d " +
      f"(${100 - 100 * digestedFp / legacyFp}%% smaller)")
    println(f"[dedup] submission : NISP in tree = $legacySub%-8d digest in tree = $digestedSub%-8d " +
      f"(${100 - 100 * digestedSub / legacySub}%% smaller)")

    digestedFp should be < legacyFp
    digestedFp should be < 90000

    // With a 40-byte value the ceiling stops being the binding constraint on the fraud proof,
    // so the NISP ceiling can be set from the format alone rather than from proof arithmetic.
    println(s"[dedup] fraud proof at a 35000 ceiling, digest-valued = " +
      s"${34999 + digested.lookup + digested.remove}")


  }

  /**
   * A real submission at the ceiling, signed and measured off messageToSign so the context
   * extension is counted: that is where both the NISP and its insert proof live.
   */
  property("avl: a real Holding submission transaction at the NISP ceiling") {
    withCtx { ctx =>
      def submissionBytes(nispLen: Int, treeSize: Int): Int = {
        val holding = holdingContract(ctx)
        val prover = miner(ctx)
        val key = contractOf(prover).hashedPropBytes
        val nisp = nispBytes(5000L, nispLen)

        // Neighbouring leaves contain commitments; the original payload travels in context 1.
        val tree = commitmentTree(fill(treeSize, nispLen))
        val inTree = tree.ergoValue
        val insertion = tree.insert(key -> _root_.nisp.NispCommitment.fromPayload(nisp).bytes)
        val outTree = tree.ergoValue
        val periodStart = ctx.getHeight.toLong - 100L

        val in = UTXO(holding, boxValue, Seq.empty[Token], Seq(
          inTree, ErgoValue.of(treeSize), ErgoValue.of(BigInt(0).bigInteger),
          stateReg(periodStart, periodStart, 0L)))
          .toInput(ctx, ErgoId.create(dummyTxId), 0.toShort)
          // The guard's submission shape: signer script in 0, the entry in 1, the insert proof in
          // 2, the op byte in 3, and the holding logic itself in 64. The logic's value bytes ride
          // in every submission — that is the cost the guard trades for its size on the box.
          .setCtxVars(
            ContextVar.of(0.toByte, ErgoValue.of(Colls.fromArray(contractOf(prover).valueBytes), scalaByteType)),
            ContextVar.of(1.toByte, ErgoValue.pairOf(
              ErgoValue.of(Colls.fromArray(key), scalaByteType),
              ErgoValue.of(Colls.fromArray(nisp), scalaByteType))),
            ContextVar.of(2.toByte, insertion.proof.ergoValue),
            ContextVar.of(3.toByte, ErgoValue.of(0.toByte)),
            ContextVar.of(64.toByte, ErgoValue.of(Colls.fromArray(holdingLogic(ctx).valueBytes), scalaByteType)))

        // A submission raises the box value by the bond it posts, so the successor is not a copy.
        val bond = bondFor(5000L)
        val out = UTXO(holding, boxValue + bond, Seq.empty[Token], Seq(
          outTree, ErgoValue.of(treeSize + 1), ErgoValue.of(BigInt(5000L).bigInteger),
          stateReg(periodStart, periodStart, bond)))

        val tx = build(ctx, Seq(in, fundingInput(ctx, prover)), Seq(out), prover.getAddress)
        // Only sizes below the ceiling can be signed; above it the transaction is still built and
        // measured, since size is what is being asked for. Cost is reported where it does sign,
        // against the 1000000 a single transaction is allowed to spend.
        if (nispLen < LFSMHelpers.NISP_MAX) {
          val signed = accepts(prover, tx)
          val cost = Try(signed.getCost).toOption
          println(s"[cost] Holding submission, $treeSize miners, ${nispLen}-byte NISP, " +
            s"${proofBytes(insertion.proof.ergoValue)}-byte insert proof: " +
            cost.map(c => s"cost $c of 1000000 (${100 * c / 1000000}%)").getOrElse("cost unavailable"))
        }
        tx.asInstanceOf[UnsignedTransactionImpl].getTx.messageToSign.length
      }

      Seq(8, 256, 1024).foreach { n =>
        val deployed = submissionBytes(LFSMHelpers.NISP_MAX - 1, n)
        val proposed = submissionBytes(34999, n)
        println(f"[tx] $n%-5d miners: signed submission at deployed ceiling = $deployed%-7d " +
          f"| at hypothetical 35000-byte ceiling = $proposed%-7d (+${proposed - deployed})")
      }
    }
  }
}
