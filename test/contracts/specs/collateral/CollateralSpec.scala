package contracts.specs.collateral

import contracts.specs.emission.EmissionSpecBase
import contracts.specs.rollup.RollupSpecBase
import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.GroupElement
import sigma.data.AvlTreeFlags
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}
import work.lithos.plasma.PlasmaParameters

/**
 * Collateral_Mainnet.ergo — one property per named condition in the contract.
 *
 * This is the genesis transaction: the one a miner inserts into the block they are mining, before PoW.
 * It consumes a live collateral box and lays out everything that block owes —
 *
 *   INPUTS(0)   the collateral box (principal, collateral token, this block's LIT plus the permit)
 *   OUTPUTS(0)  the rollup holding box, opening the LFSM for this block
 *   ...         one box per founder, the lender's permit returned, and a proof of spend retiring the
 *               lender from the emission box's active set
 *
 * The finder's share is not a named output. Whatever the holding box does not take simply stays in the
 * transaction, and the transaction belongs to whoever built it, so nothing has to be matched against
 * the block's miner. The coinbase paying `lenderPk` is what returns the principal, and it is also what
 * keeps a block to a single collateral box.
 *
 * Two things this spec has to set up that no other spec does. **A preHeader**, because
 * `lenderIsBlockMiner` reads `CONTEXT.preHeader.minerPk` — which also fixes `HEIGHT`, so the holding
 * box's R7 and the proof of spend's creation height are pinned to the value set here. And **no miner
 * fee**: the whole transaction is funded out of the collateral box's dust budget with nothing left
 * over, which is the shape §26.0 requires, since every output is serialised into the collateral
 * transaction embedded in every super-share.
 *
 * Positive properties keep that exact shape and carry no extra inputs. Negatives add a funding input
 * under the finder's address so a perturbation stays isolated instead of unbalancing the transaction;
 * the contract permits this, since only INPUTS(0) is pinned. Change lands on the finder, who is
 * deliberately neither the lender nor a founder, so it can never satisfy one of the `exists` clauses
 * by accident.
 */
class CollateralSpec extends AnyPropSpec with EmissionSpecBase with RollupSpecBase {

  /** Contract-imposed minimums: founder boxes and the permit return are checked against 1e6. */
  private val founderBoxValue: Long = 1000000L
  private val permitBoxValue: Long = 1000000L
  private val posBoxValue: Long = 200000L
  private val finderBoxValue: Long = 200000L

  private val FINDER_DIVISOR: Long = 25L

  private def hashOf(s: String): Array[Byte] = Blake2b256.hash(s.getBytes("UTF-8"))

  /** The miner's hashed prop bytes, which the collateral contract only length-checks. */
  private def minerHash(ctx: BlockchainContext): Array[Byte] = contractOf(miner(ctx)).hashedPropBytes

  private case class Genesis(ctx: BlockchainContext,
                             collatIn: InputUTXO,
                             holding: UTXO,
                             founderBoxes: Seq[UTXO],
                             permitBox: Option[UTXO],
                             pos: UTXO,
                             finder: UTXO,
                             finderProver: ErgoProver,
                             lenderProver: ErgoProver,
                             lenderGE: GroupElement,
                             height: Int,
                             dataInputs: Seq[InputUTXO],
                             poolLIT: Long,
                             finderLIT: Long,
                             permitChange: Long)

  /**
   * A complete, well-formed genesis transaction. Every negative takes this and perturbs one field.
   *
   * `currentBlock` and `litHeld` drive the emission split exactly as `LIT_Emissions` computed it when
   * the box was minted, so the founder and pool figures here are the same ones that spec pins.
   */
  private def genesis(ctx: BlockchainContext,
                      currentBlock: Int = 0,
                      litHeld: Long = -1L,
                      permit: Long = LFSMHelpers.PERMIT_FLOOR,
                      extensionFlag: Byte = 0x00,
                      extScript: Contract = Contract.SIGMA_TRUE,
                      configExtHash: Array[Byte] = null,
                      configNft: ErgoId = LFSMHelpers.EMCONFIG_NFT,
                      rollupContract: Contract = null): Genesis = {
    val lenderProver = lender(ctx)
    val finderProver = miner(ctx)
    val holdingScript = if (rollupContract == null) holdingContract(ctx) else rollupContract
    val held = if (litHeld < 0L) litSupply else litHeld
    val (emitted, pub, split) = emissionAt(currentBlock, held)

    val height = ctx.getHeight + 1
    val finderLIT = pub / FINDER_DIVISOR
    val poolLIT = pub - finderLIT
    val permitChange = permit

    val collatBox = collateralUTXO(ctx, lenderProver, lit = emitted + permit, pub = pub,
      founderSplit = split, rollupHash = holdingScript.hashedPropBytes,
      extensionFlag = extensionFlag, contract = collateralContract(ctx))

    val collatIn = {
      val base = inputAt(collatBox, ctx, 0)
      if (extensionFlag == 0x00) base
      else base.setCtxVars(ContextVar.of(0.toByte, bytesValue(extScript.valueBytes)))
    }

    val founderBoxes = split.map { case (key, amount) =>
      UTXO(founderContract(key), founderBoxValue, Seq(Token(LFSMHelpers.LIT_ID, amount)))
    }

    val permitBox =
      if (permitChange > 0)
        Some(UTXO(contractOf(lenderProver), permitBoxValue, Seq(Token(LFSMHelpers.LIT_ID, permitChange))))
      else None

    val pos = UTXO(gateContract(ctx), posBoxValue, Seq(Token(LFSMHelpers.COLLAT_TOKEN, 1L)),
      Seq(bytesValue(setEntry(lenderProver)))).setCreationHeight(height)

    val finder = UTXO(contractOf(finderProver), finderBoxValue,
      if (finderLIT > 0) Seq(Token(LFSMHelpers.LIT_ID, finderLIT)) else Seq.empty[Token])

    val dust = founderBoxes.map(_.value).sum + permitBox.map(_.value).getOrElse(0L) + posBoxValue + finderBoxValue
    val holding = UTXO(holdingScript, collatBox.value - dust,
      if (poolLIT > 0) Seq(Token(LFSMHelpers.LIT_ID, poolLIT)) else Seq.empty[Token],
      Seq(
        emptyTree.ergoValue,
        ErgoValue.of(0),
        ErgoValue.of(BigInt(0).bigInteger),
        ErgoValue.of(height.toLong),
        bytesValue(minerHash(ctx))
      ))

    val dataInputs =
      if (extensionFlag == 0x00) Seq.empty[InputUTXO]
      else Seq(configInput(ctx, configBox(ctx, enforcerScript(ctx), nft = configNft,
        extensionHash = if (configExtHash == null) extScript.hashedValueBytes else configExtHash)))

    Genesis(ctx, collatIn, holding, founderBoxes, permitBox, pos, finder, finderProver, lenderProver,
      lenderProver.getAddress.getPublicKeyGE, height, dataInputs, poolLIT, finderLIT, permitChange)
  }

  /** Founder identities are recognised by `blake2b256(propositionBytes)`, so the box carries the script. */
  private def founderContract(key: Array[Byte]): Contract =
    Seq(LFSMHelpers.FOUNDER_1, LFSMHelpers.FOUNDER_2, LFSMHelpers.FOUNDER_3)
      .find(c => java.util.Arrays.equals(c.hashedPropBytes, key))
      .getOrElse(throw new IllegalArgumentException("no founder for that key"))

  private def outputsOf(g: Genesis): Seq[UTXO] =
    Seq(g.holding) ++ g.founderBoxes ++ g.permitBox.toSeq ++ Seq(g.pos, g.finder)

  /**
   * Funding for negatives, so a perturbed output does not unbalance the transaction. Carries enough
   * ERG that a change box always exists — with no fee output there is nothing for `TxBuilder` to fold
   * a sub-minimum change amount into, and leftover tokens need somewhere to land.
   */
  private def slack(g: Genesis): InputUTXO =
    inputAt(UTXO(contractOf(g.finderProver), 2L * Parameters.OneErg,
      Seq(Token(LFSMHelpers.LIT_ID, 100L * LIT))), g.ctx, 5)

  private def genesisTx(g: Genesis)(outputs: Seq[UTXO] = outputsOf(g),
                                    inputs: Seq[InputUTXO] = Seq(g.collatIn),
                                    minerPk: GroupElement = g.lenderGE,
                                    dataInputs: Seq[InputUTXO] = g.dataInputs,
                                    fee: Long = 0L): UnsignedTransaction =
    TxBuilder(g.ctx)
      .setInputs(inputs: _*)
      .setDataInputs(dataInputs: _*)
      .setOutputs(outputs: _*)
      .setPreHeader(g.ctx.createPreHeader().height(g.height).minerPk(minerPk).build())
      .buildTx(fee, g.finderProver.getAddress)

  /** Replaces one output in place, leaving OUTPUTS(0) and the rest where they were. */
  private def replacing(g: Genesis, original: UTXO, replacement: UTXO): Seq[UTXO] =
    outputsOf(g).map(o => if (o eq original) replacement else o)

  private def without(g: Genesis, dropped: UTXO): Seq[UTXO] =
    outputsOf(g).filterNot(_ eq dropped)

  // ─── the whole transaction ────────────────────────────────────────────────

  property("genesis: accepts the transaction a miner inserts into the block they are mining") {
    withCtx { ctx =>
      val g = genesis(ctx)
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  /**
   * The builder is the finder, not the lender. Nothing in the contract identifies who assembled the
   * transaction — the coinbase recipient is the only actor it pins.
   */
  property("genesis: the finder need not be the lender") {
    withCtx { ctx =>
      val g = genesis(ctx)
      g.finderProver.getAddress should not be g.lenderProver.getAddress
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  /**
   * The dust budget is what funds every output, so the transaction has to close with no extra inputs:
   * each one would be serialised into the collateral transaction carried by every super-share.
   */
  property("genesis: balances out of the dust budget alone, with no extra inputs and no fee") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val outs = outputsOf(g)
      assert(outs.map(_.value).sum == g.collatIn.value)
      outs.size shouldBe 7
      assert(g.holding.value >= g.collatIn.value - DUST_BUDGET)
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  /**
   * The handoff. This box is built by `activateScenario` — the same helper `EmissionSpec` hands to the
   * emission contract — and re-scripted to the real collateral contract. It pins that the two agree on
   * R4 through R8, which nothing else checks: the emission specs isolate themselves behind a
   * `sigmaProp(true)` stand-in for this contract.
   */
  property("handoff: a collateral box exactly as the emission contract mints it is spendable here") {
    withCtx { ctx =>
      val a = activateScenario(ctx, rollupHash = holdingContract(ctx).hashedPropBytes)
      val g = genesis(ctx, permit = a.permit)
      val minted = a.collatOut.setContract(collateralContract(ctx))
      assert(minted.value == g.collatIn.value)
      minted.tokens shouldBe g.collatIn.tokens
      minted.registers.map(_.toHex) shouldBe g.collatIn.registers.map(_.toHex)
      accepts(g.finderProver, genesisTx(g)(inputs = Seq(inputAt(minted, ctx, 0))))
    }
  }

  // ─── schedule-dependent shapes ────────────────────────────────────────────

  property("genesis: accepts a spend once the founders' allocation has ended (foundersPaid)") {
    withCtx { ctx =>
      val g = genesis(ctx, currentBlock = PRIV_LENGTH * EPOCH_LENGTH)
      g.founderBoxes shouldBe empty
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  /** Past the final block nothing is emitted, so the holding box carries no LIT at all. */
  property("genesis: accepts a spend once emission has ended (tokensTransferred)") {
    withCtx { ctx =>
      val g = genesis(ctx, currentBlock = FINAL_BLOCK)
      g.poolLIT shouldBe 0L
      g.holding.tokens shouldBe empty
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  property("genesis: accepts a spend with no permit to return (permitChangeReturned)") {
    withCtx { ctx =>
      val g = genesis(ctx, permit = 0L)
      g.permitBox shouldBe empty
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  /** The finder's share is a flat 4% of the pool's block reward, taken as slack. */
  property("genesis: the finder keeps CONST_FINDER_DIVISOR's share of the block reward") {
    withCtx { ctx =>
      val g = genesis(ctx)
      g.finderLIT shouldBe 20L * LIT
      g.poolLIT shouldBe 480L * LIT
      g.finderLIT + g.poolLIT shouldBe 500L * LIT
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  // ─── holdingValid ─────────────────────────────────────────────────────────

  property("holding: accepts a holding box taking more than the lower bound (value)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      accepts(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.addValue(Parameters.OneErg)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("holding: rejects a holding box below the principal less the fee (value)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val short = g.holding.setValue(g.collatIn.value - DUST_BUDGET - 1L)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, short), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /**
   * R8 is stamped by the emission contract from the config, not chosen here, so this is what ties a
   * collateral box to one rollup contract for its whole life.
   */
  property("holding: rejects a holding box under a script R8 does not name (rollupHoldingHash)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.setContract(Contract.SIGMA_TRUE)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("holding: rejects a holding box shorting the pool's LIT (tokensTransferred)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val short = g.holding.setTokens(Token(LFSMHelpers.LIT_ID, g.poolLIT - 1L))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, short), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /** Exact equality, so the finder cannot route their own share into the pool box either. */
  property("holding: rejects a holding box taking the finder's share as well (tokensTransferred)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val greedy = g.holding.setTokens(Token(LFSMHelpers.LIT_ID, g.poolLIT + g.finderLIT))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, greedy), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("holding: rejects a holding box whose LIT is some other token (tokensTransferred)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val wrong = g.holding.setTokens(Token(unrelatedTokenId, g.poolLIT))
      val funding = inputAt(UTXO(contractOf(g.finderProver), Parameters.OneErg,
        Seq(Token(unrelatedTokenId, g.poolLIT))), ctx, 5)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, wrong), inputs = Seq(g.collatIn, funding)))
    }
  }

  // ─── validAvlTree ─────────────────────────────────────────────────────────

  property("avl: the digest the contract hard-codes is the one an empty PlasmaMap produces") {
    withCtx { _ =>
      val digest = emptyTree.ergoValue.getValue.digest.toArray
      org.bouncycastle.util.encoders.Hex.toHexString(digest) shouldBe
        "4ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e160900"
    }
  }

  property("avl: rejects a holding box opening with a non-empty tree (digest)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val seeded = treeWith(Seq(setEntry(g.lenderProver) -> Array.fill(8)(1.toByte)))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, seeded.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("avl: rejects a tree that does not allow insertion (isInsertAllowed)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val tree = emptyTreeWith(AvlTreeFlags(insertAllowed = false, updateAllowed = true, removeAllowed = true))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, tree.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("avl: rejects a tree that does not allow removal (isRemoveAllowed)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val tree = emptyTreeWith(AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, tree.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("avl: rejects a tree that does not allow updates (isUpdateAllowed)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val tree = emptyTreeWith(AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = true))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, tree.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /** The keys are 32-byte identity hashes, so a tree sized for anything else is not the rollup's. */
  property("avl: rejects a tree with a different key length (keyLength)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val tree = treeWith(Seq.empty, AvlTreeFlags.AllOperationsAllowed, PlasmaParameters(33, None))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, tree.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /** NISPs vary in size, so a fixed value length would make the tree unable to hold them. */
  property("avl: rejects a tree with a fixed value length (valueLengthOpt)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val tree = treeWith(Seq.empty, AvlTreeFlags.AllOperationsAllowed, PlasmaParameters(32, Some(64)))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(0, tree.ergoValue)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  // ─── the holding box's opening registers ──────────────────────────────────

  property("holding: rejects an opening miner count that is not zero (R5)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(1, ErgoValue.of(1))),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("holding: rejects an opening total score that is not zero (R6)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(2, ErgoValue.of(BigInt(1).bigInteger))),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /**
   * The period must start at this block. A holding box backdated past the window would close its
   * submission phase early, or open one that never accepts anything.
   */
  property("holding: rejects a period start that is not this block (R7 == HEIGHT)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(3, ErgoValue.of(g.height.toLong - 1L))),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /**
   * R8 is only length-checked here on purpose: it is meant to be the miner's hashed prop bytes, and
   * `FP_MalformedTransaction` slashes a miner who sets it wrongly.
   */
  property("holding: rejects an R8 that is not 32 bytes long") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(4, bytesValue(minerHash(ctx).take(31)))),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("holding: accepts any 32-byte R8, since the fraud proof polices its contents") {
    withCtx { ctx =>
      val g = genesis(ctx)
      accepts(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.holding, g.holding.withReg(4, bytesValue(hashOf("not-the-miner")))),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  // ─── permitChangeReturned ─────────────────────────────────────────────────

  property("permit: rejects a transaction that does not return the permit at all") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = without(g, g.permitBox.get), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  /** Matched on exact propositionBytes, so it can only ever go back to the lender's own script. */
  property("permit: rejects a permit returned to anyone but the lender (lenderPk.propBytes)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val hijacked = g.permitBox.get.setContract(contractOf(otherLender(ctx)))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.permitBox.get, hijacked), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("permit: rejects a short permit return (permitChange)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val short = g.permitBox.get.setTokens(Token(LFSMHelpers.LIT_ID, g.permitChange - 1L))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.permitBox.get, short), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("permit: rejects a permit-change box below the min box value floor") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val dusted = g.permitBox.get.setValue(999999L)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.permitBox.get, dusted), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  // ─── foundersPaid ─────────────────────────────────────────────────────────

  property("founders: rejects a transaction that skips a founder") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = without(g, g.founderBoxes.last), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("founders: rejects a short founder payment") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val short = g.founderBoxes.head.setTokens(Token(LFSMHelpers.LIT_ID, F1_RATE - 1L))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.founderBoxes.head, short), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("founders: rejects a founder box carrying no LIT") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val empty = g.founderBoxes.head.setTokens()
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.founderBoxes.head, empty), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("founders: rejects a founder box below the min box value floor") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val dusted = g.founderBoxes.head.setValue(999999L)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.founderBoxes.head, dusted), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  // ─── proofOfSpendMade ─────────────────────────────────────────────────────

  /**
   * Every path that consumes this box produces one, so an identity can never be stranded in the
   * emission box's active set with no way to free its slot.
   */
  property("proof of spend: rejects a transaction that does not retire the lender") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = without(g, g.pos), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("proof of spend: rejects one that is not under the emission gate (CONST_GATE_HASH)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.pos, g.pos.setContract(Contract.SIGMA_TRUE)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("proof of spend: rejects one not carrying the collateral token") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.pos, g.pos.setTokens()),
        inputs = Seq(g.collatIn, slack(g)), fee = Parameters.MinFee))
    }
  }

  /**
   * The height is pinned to this block exactly, not merely bounded. An exact demand is safe because
   * the genesis transaction is built for a candidate height the miner already knows, and it is not
   * forgeable downward the way an age comparison would be.
   */
  property("proof of spend: rejects one whose creation height is not this block (creationInfo)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.pos, g.pos.setCreationHeight(g.height - 1)),
        inputs = Seq(g.collatIn, slack(g))))
    }
  }

  property("proof of spend: rejects one naming a different lender (R4)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      val wrong = g.pos.withReg(0, bytesValue(setEntry(otherLender(ctx))))
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        outputs = replacing(g, g.pos, wrong), inputs = Seq(g.collatIn, slack(g))))
    }
  }

  // ─── lenderIsBlockMiner and onlyOne ───────────────────────────────────────

  /**
   * The coinbase paying the lender is how the principal comes back, and it is also what keeps a block
   * to one collateral box: two boxes would demand two different coinbase recipients, and the emission
   * box holds every live lender distinct.
   */
  property("coinbase: rejects a block whose coinbase pays anyone but the lender (lenderIsBlockMiner)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        minerPk = otherLender(ctx).getAddress.getPublicKeyGE))
    }
  }

  property("onlyOne: rejects when the collateral box is not INPUTS(0)") {
    withCtx { ctx =>
      val g = genesis(ctx)
      rejectsAtSigning(g.finderProver, genesisTx(g)(
        inputs = Seq(slack(g), g.collatIn), fee = Parameters.MinFee))
    }
  }

  // ─── extensionActivated ───────────────────────────────────────────────────

  /**
   * A nonzero flag routes the spend through a script named by the config *at spend time*, which is why
   * the enforcer pins the flag at 0x00 until a live extension exists — a flag nothing handles makes
   * the box unspendable forever (analysis §26.3.1).
   */
  property("extension: accepts a flagged box whose extension script authenticates and succeeds") {
    withCtx { ctx =>
      val g = genesis(ctx, extensionFlag = 1.toByte, extScript = Contract.SIGMA_TRUE)
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  property("extension: accepts an NFT id extension") {
    withCtx { ctx =>
      val g = genesis(ctx, extensionFlag = 1.toByte, extScript = Contract.SIGMA_TRUE)
      accepts(g.finderProver, genesisTx(g)())
    }
  }

  property("extension: rejects a script the config's R6 does not name (authenticExtension)") {
    withCtx { ctx =>
      val g = genesis(ctx, extensionFlag = 1.toByte, extScript = Contract.SIGMA_TRUE,
        configExtHash = hashOf("some-other-extension"))
      rejectsAtSigning(g.finderProver, genesisTx(g)())
    }
  }

  property("extension: rejects an authenticated script that evaluates to false (executeFromVar 0)") {
    withCtx { ctx =>
      val g = genesis(ctx, extensionFlag = 1.toByte, extScript = Contract.SIGMA_FALSE)
      rejectsAtSigning(g.finderProver, genesisTx(g)())
    }
  }

  property("extension: rejects a data input that is not the emission config (authenticConfig)") {
    withCtx { ctx =>
      val g = genesis(ctx, extensionFlag = 1.toByte, extScript = Contract.SIGMA_TRUE,
        configNft = unrelatedTokenId)
      rejectsAtSigning(g.finderProver, genesisTx(g)())
    }
  }

  property("extension: an unflagged box needs no extension script and no data input") {
    withCtx { ctx =>
      val g = genesis(ctx)
      g.dataInputs shouldBe empty
      g.collatIn.ctxVars shouldBe empty
      accepts(g.finderProver, genesisTx(g)())
    }
  }
}
