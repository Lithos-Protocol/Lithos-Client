package mining

import configs.CandidateConfig
import contracts.specs.emission.EmissionSpecBase
import lfsm.{EmissionSchedule, LFSMHelpers}
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoValue}
import org.ergoplatform.sdk.SecretString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import stratum.CollateralNotFoundException
import transactions.ProtocolContracts
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * `CandidateTxBuilder.buildGenesis` against the contract it has to satisfy.
 *
 * This is a BUILDER test, and the distinction matters. `contracts.specs.CollateralSpec` constructs a
 * genesis transaction by hand and asks whether `Collateral_Mainnet.ergo` accepts that shape — which
 * is settled, and is not re-asked here. What is untested until now is whether the production builder
 * PRODUCES that shape. The two can drift in either direction and neither spec alone would notice.
 *
 * The strongest form of the assertion is a round trip: build with the real builder, then sign
 * against the real contract. Every conjunct the contract checks — output order, the dust budget, the
 * height in R7 and the proof-of-spend's creation height, the LIT split, the founder boxes — is
 * covered by that one signature, and covered against the contract rather than against a restatement
 * of it.
 */
class CandidateTxBuilderSpec extends AnyFlatSpec with Matchers with EmissionSpecBase with MockitoSugar {

  private val cfg = CandidateConfig.Default

  /** A prover with EIP-3 addresses, which `NodeWallet` requires and a DLog-only prover lacks. */
  private def nodeWallet(ctx: BlockchainContext): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(
        "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"),
        SecretString.empty(), false)
      .withEip3Secret(0).build())

  private def builder(ctx: BlockchainContext): (CandidateTxBuilder, NodeWallet) = {
    val wallet = nodeWallet(ctx)
    (new CandidateTxBuilder(wallet, mock[NodeApi], cfg), wallet)
  }

  /**
   * A collateral box exactly as the emission contract mints one, under the script THIS build
   * compiles — so the transaction the builder produces is signed against the real thing.
   */
  private def collateralInput(ctx: BlockchainContext,
                              currentBlock: Int = 0,
                              permit: Long = LFSMHelpers.PERMIT_FLOOR,
                              rollupHash: Array[Byte] = null): InputUTXO = {
    val c = ProtocolContracts(ctx)
    val (emitted, pub, split) = emissionAt(currentBlock, litSupply)
    val box = collateralUTXO(ctx, lender(ctx), lit = emitted + permit, pub = pub,
      founderSplit = split,
      rollupHash = if (rollupHash == null) c.holding.hashedPropBytes else rollupHash,
      contract = c.collateral)
    inputAt(box, ctx, 0)
  }

  // ─── the round trip ───────────────────────────────────────────────────────

  "A built genesis transaction" should "be accepted by the collateral contract itself" in {
    withCtx { ctx =>
      val (txBuilder, wallet) = builder(ctx)
      val collat = collateralInput(ctx)
      val data = txBuilder.buildGenesis(ctx, collat, ctx.getHeight + 1)

      // Signing IS the assertion: `prover.sign` runs the contract, so a transaction that reaches
      // here has satisfied every conjunct of `validLithosBlock`.
      data.txId should not be empty
      data.pk should not be empty
      wallet should not be null
    }
  }

  it should "pin the PASSED block height into the proof-of-spend, not the context's height" in {
    // `proofOfSpend.creationInfo._1 == HEIGHT` is a contract conjunct, and the block is mined one
    // past the tip — so the height has to travel from `CandidateBuilder` rather than be read again
    // from the context downstream. Signing cannot catch a mistake here: `buildGenesis` builds its
    // own preHeader at whatever height it is handed, so the transaction is self-consistent at any
    // value and only the NODE would reject it. That makes this worth asserting on the outputs.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val target = ctx.getHeight + 7 // deliberately not ctx.getHeight, and not +1
      val data = txBuilder.buildGenesis(ctx, collateralInput(ctx), target)

      val outputs = new org.json.JSONObject(data.txJSON).getJSONArray("outputs")
      // The proof-of-spend is the last output and the only one whose creation height is set
      // explicitly; everything else defaults to the context's.
      val pos = outputs.getJSONObject(outputs.length() - 1)
      pos.getInt("creationHeight") shouldEqual target
      pos.getInt("creationHeight") should not equal ctx.getHeight
    }
  }

  it should "carry the lender's key, since the coinbase has to pay them" in {
    // `lenderIsBlockMiner` compares the block's miner key against R5. The pk the builder returns is
    // what the candidate is requested for, so a mismatch makes every collateral box unspendable.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val lenderProver = lender(ctx)
      val data = txBuilder.buildGenesis(ctx, collateralInput(ctx), ctx.getHeight + 1)

      val expected = org.bouncycastle.util.encoders.Hex.toHexString(
        sigma.serialization.GroupElementSerializer.toBytes(
          lenderProver.getAddress.getPublicKey.value))
      data.pk shouldEqual expected
      data.lenderAddress shouldEqual lenderProver.getAddress.toString
    }
  }

  it should "name the collateral box it spends" in {
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val collat = collateralInput(ctx)
      val data = txBuilder.buildGenesis(ctx, collat, ctx.getHeight + 1)
      data.collateralId shouldEqual collat.id.toString
    }
  }

  it should "still balance once the founders' allocation has ended" in {
    // After the private period the founder boxes disappear, which changes both the output count and
    // the dust total — 1.25M rather than 4.25M. The contract checks the holding box against the fee
    // channel either way.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val afterFounders = EmissionSchedule.EPOCH_LENGTH * EmissionSchedule.PRIV_LENGTH
      val collat = collateralInput(ctx, currentBlock = afterFounders)
      val data = txBuilder.buildGenesis(ctx, collat, ctx.getHeight + 1)
      data.txId should not be empty
    }
  }

  it should "still balance with no permit to return" in {
    // A permit of zero omits the box entirely rather than emitting a dust one, and the contract's
    // `permitChangeReturned` has to be satisfied by its absence.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val collat = collateralInput(ctx, permit = 0L)
      val data = txBuilder.buildGenesis(ctx, collat, ctx.getHeight + 1)
      data.txId should not be empty
    }
  }

  // ─── the two throw paths ──────────────────────────────────────────────────

  "A collateral box naming a rollup contract this build does not produce" should "be refused" in {
    // `blake2b256(holdingBox.propositionBytes) == rollupHoldingHash` is a guaranteed rejection, so
    // the builder fails here instead of spending a POST and falling back to solo. Combined with the
    // per-block skip set, this draws a different box on the same block.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val foreign = collateralInput(ctx, rollupHash = Contract.SIGMA_TRUE.hashedPropBytes)
      a[CollateralNotFoundException] should be thrownBy
        txBuilder.buildGenesis(ctx, foreign, ctx.getHeight + 1)
    }
  }

  "A collateral box naming an unknown founder" should "be refused rather than paid" in {
    // The founder split comes off the box, and anyone can put anything in a register. Paying an
    // address the contract does not recognise would be a guaranteed rejection at best.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val c = ProtocolContracts(ctx)
      val bogus = Seq(scorex.crypto.hash.Blake2b256.hash("not-a-founder") -> 100L)
      val box = collateralUTXO(ctx, lender(ctx), lit = 1000L, pub = 500L, founderSplit = bogus,
        rollupHash = c.holding.hashedPropBytes, contract = c.collateral)
      a[CollateralNotFoundException] should be thrownBy
        txBuilder.buildGenesis(ctx, inputAt(box, ctx, 0), ctx.getHeight + 1)
    }
  }

  // ─── what the collateral data carries onward ──────────────────────────────

  "The returned collateral data" should "carry the bytes a super-share proof needs" in {
    // Every super share serialises this, and `SuperShare.fromCandidate` looks the transaction up in
    // the candidate's merkle proof by id. Empty bytes here are a super share that cannot be built.
    withCtx { ctx =>
      val (txBuilder, _) = builder(ctx)
      val collat = collateralInput(ctx)
      val data = txBuilder.buildGenesis(ctx, collat, ctx.getHeight + 1)

      data.txId.length shouldEqual 64
      data.txBytes should not be empty
      data.collateralBoxBytes should not be empty
      data.txJSON should include(data.txId)
    }
  }
}
