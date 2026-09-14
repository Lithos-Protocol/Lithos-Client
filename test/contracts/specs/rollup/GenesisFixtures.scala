package contracts.specs.rollup

import contracts.specs.emission.EmissionSpecBase
import lfsm.LFSMHelpers
import nisp.{SuperShare, TransactionProof}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import scorex.crypto.hash.Digest32
import scorex.utils.Longs
import sigma.crypto.Platform
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

/**
 * A genesis transaction as `CandidateTxBuilder` assembles it, and a NISP built on one.
 *
 * `FP_MalformedGenesis` walks the transaction inside a share down to the holding output's R8, so a
 * NISP that proof declines has to carry a real genesis. Shared here so a spec can build a NISP that
 * is honest to every proof at once, not only to the one it exercises.
 */
trait GenesisFixtures { this: EmissionSpecBase with FraudProofSpecBase =>

  /** One built genesis transaction, and the pieces a super-share carries from it. */
  protected case class Genesis(tx: Array[Byte], collat: InputUTXO, lenderKey: Array[Byte])

  /**
   * A genesis transaction exactly as `CandidateTxBuilder` assembles it, for `minerHash`.
   *
   * `lenderSecret` decides both the collateral box's key and the block's coinbase recipient, so two
   * calls with different secrets are two different rollups.
   */
  protected def genesisFor(ctx: BlockchainContext,
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
      UTXO(recipient, 1000000L, Seq(Token(LFSMHelpers.LIT_ID_MAINNET, amount)))
    }
    val permitBox = UTXO(contractOf(lenderProver), 1000000L, Seq(Token(LFSMHelpers.LIT_ID_MAINNET, permit)))
    val pos = UTXO(gateContract(ctx), 200000L, Seq(Token(LFSMHelpers.COLLAT_TOKEN_MAINNET, 1L)),
      Seq(bytesValue(setEntry(lenderProver)))).setCreationHeight(height)
    val finderBox = UTXO(contractOf(miner(ctx)), 200000L, Seq(Token(LFSMHelpers.LIT_ID_MAINNET, finderLIT)))

    val trailing = founderBoxes ++ Seq(permitBox, pos, finderBox)
    val holdingRegs = Seq(emptyTree.ergoValue, ErgoValue.of(0), ErgoValue.of(BigInt(0).bigInteger),
      stateReg(height.toLong, height.toLong, 0L), bytesValue(minerHash)) ++ r9.toSeq
    val holding = UTXO(rollup, collatBox.value - trailing.map(_.value).sum,
      Seq(Token(collatIn.id, 1L), Token(LFSMHelpers.LIT_ID_MAINNET, poolLIT)),
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

  // ─── a NISP honest to every proof ─────────────────────────────────────────

  /**
   * Ten shares every proof declines. Heights in window and distinct, so `FP_NotInWindow` and
   * `FP_NonUniqueHeaders` pass; N derived from each share's own height; the lender's key as the miner
   * key, so the stored y matches it; the accused's genesis proved into the header's transactions
   * root, so `FP_TransactionNotIncluded` and `FP_MalformedGenesis` pass; and each header mined to the
   * threshold a score of `FPInvalidDiffSpec.MinedScore` sets.
   *
   * Mining is the expensive part. The nonces are pinned the way `FPInvalidDiffSpec.PinnedNonce` is:
   * the search resumes from the pin, so it costs one hash per share while the fixture is unchanged,
   * and prints the new nonce if the fixture ever moves.
   */
  protected def honestNisp(ctx: BlockchainContext): GenesisFixtures.HonestNisp = {
    val accused = contractOf(miner(ctx)).hashedPropBytes
    val genesis = genesisFor(ctx, accused)
    val key = lender(ctx).getAddress.getPublicKey.value
    val proof = TransactionProof(genesis.tx, genesis.collat.bytes)
    val lvls = NispFixtures.levels(2)
    val root = Digest32 @@ NispFixtures.merkleRoot(genesis.tx, lvls)
    val shares = GenesisFixtures.PinnedNonces.zipWithIndex.map { case (pin, i) =>
      val (share, nonce) = GenesisFixtures.mine(rollupBlock(ctx).toInt - i, key, 1700000000000L + i,
        root, proof, lvls, from = pin)
      if (nonce != pin) println(s"[honest] share $i now mines at nonce $nonce, not the pinned $pin")
      share
    }
    GenesisFixtures.HonestNisp(shares, NispFixtures.nisp(FPInvalidDiffSpec.MinedScore, shares))
  }
}

object GenesisFixtures {

  /** The honest NISP, and the shares it was assembled from, for a spec that patches its bytes. */
  case class HonestNisp(shares: Seq[SuperShare], nisp: Array[Byte])

  /** Found by walking from zero, one per share, against the fixture genesis. See `honestNisp`. */
  val PinnedNonces: Seq[Long] =
    Seq(2736L, 14968L, 36819L, 3044L, 5792L, 6739L, 2409L, 19043L, 3500L, 540L)

  /** As `NispFixtures.minedShare`, for a share carrying its own proof, levels and root. */
  def mine(height: Int,
           pk: Platform.Ecp,
           timestamp: Long,
           txRoot: Digest32,
           proof: TransactionProof,
           levels: Seq[Array[Byte]],
           from: Long,
           tries: Int = 400000): (SuperShare, Long) = {
    var n = from
    while (n < from + tries) {
      val candidate = SuperShare(
        NispFixtures.header(height, pk, timestamp = timestamp, txRoot = txRoot, nonce = Longs.toByteArray(n)).bytes,
        proof, levels)
      if (candidate.powHit <= FPInvalidDiffSpec.Threshold) return (candidate, n)
      n += 1
    }
    throw new IllegalStateException(
      s"no nonce in [$from, ${from + tries}) reaches a hit below ${FPInvalidDiffSpec.Threshold}")
  }
}
