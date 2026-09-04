package transactions

import configs.CandidateConfig
import contracts.specs.emission.EmissionSpecBase
import contracts.specs.rollup.{FraudProofSpecBase, NispFixtures}
import lfsm.LFSMHelpers
import mining.CandidateTxBuilder
import mutations.NodeWallet
import nisp.TransactionProof
import node.NodeApi
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.SecretString
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.mockito.MockitoSugar
import stratum.CollateralData
import work.lithos.mutations.Token

/**
 * The `FP_MalformedGenesis` that gets minted, against the genesis transaction this client builds.
 *
 * A spec that compiles its own copy of the proof and its own holding contract agrees with itself
 * whatever the deployed constants are. `FP_CONTROL` is minted once and cannot be replaced, so a
 * `CONST_HOLDING_ERGOTREE` wrong by one byte makes every honest miner slashable for good.
 */
class GenesisProofDeploymentSpec extends AnyPropSpec with EmissionSpecBase with FraudProofSpecBase
  with MockitoSugar {

  private val score = 100000L

  /** The same fixed test mnemonic `support.FakeNodeContext` uses. Holds nothing, anywhere. */
  private val mnemonic =
    "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"

  /**
   * A mnemonic prover rather than `withDLogSecret`: `NodeWallet` reads `getEip3Addresses`, which a
   * DLog-only prover has none of, and every member throws on construction.
   */
  private def walletOf(ctx: BlockchainContext): NodeWallet =
    NodeWallet(ctx.newProverBuilder()
      .withMnemonic(SecretString.create(mnemonic), SecretString.empty(), false)
      .withEip3Secret(0).build())

  /** The production builder, over a collateral box under the production collateral script. */
  private def productionGenesis(ctx: BlockchainContext, wallet: NodeWallet): CollateralData = {
    val c = ProtocolContracts(ctx)
    val (emitted, pub, split) = emissionAt(0, litSupply)
    val collat = inputAt(collateralUTXO(ctx, lender(ctx),
      lit = emitted + LFSMHelpers.PERMIT_FLOOR, pub = pub, founderSplit = split,
      rollupHash = c.holding.hashedPropBytes, contract = c.collateral), ctx, 0)
    new CandidateTxBuilder(wallet, mock[NodeApi], CandidateConfig.Default)
      .buildGenesis(ctx, collat, ctx.getHeight + 1)
  }

  /**
   * Ten shares carrying that transaction, mined under the lender's key, which is who the coinbase
   * pays. The proof compares that key against the collateral box's R5 without decoding either.
   */
  private def sharesOver(ctx: BlockchainContext, data: CollateralData): Seq[Array[Byte]] = {
    val proof = TransactionProof(data.txBytes, data.collateralBoxBytes)
    val lenderKey = lender(ctx).getAddress.getPublicKeyGE.getEncoded.toArray
    (0 until 10).map { i =>
      val base = NispFixtures.superShare(rollupBlock(ctx).toInt - i,
        NispFixtures.pkOf(miner(ctx)), timestamp = 1700000000000L + i, proof = Some(proof))
      NispFixtures.shareWithHeader(base, NispFixtures.withGroupElement(base.headerBytes, lenderKey))
    }
  }

  // ─── the constants ────────────────────────────────────────────────────────

  /**
   * Read out of the compiled tree rather than by recompiling the proof, which would be a second
   * compilation of the largest script in the set. Constant segregation writes each injected
   * `Coll[Byte]` into the tree verbatim, so containment names which constant drifted.
   */
  property("constants: the deployed proof carries the production holding and collateral ergoTrees") {
    withCtx { ctx =>
      val c = ProtocolContracts(ctx)
      val deployed = fpMalformedGenesis(ctx).ergoTree.bytes

      withClue("CONST_HOLDING_ERGOTREE is not the holding guard this build compiles, so every " +
        "honest genesis transaction fails scriptMatches and every miner is slashable: ") {
        deployed.indexOfSlice(c.holding.ergoTree.bytes) should be >= 0
      }
      withClue("CONST_COLLAT_ERGOTREE is not the collateral script this build compiles: ") {
        deployed.indexOfSlice(c.collateral.ergoTree.bytes) should be >= 0
      }

      println(s"[deployed] proof tree ${deployed.length}B carries holding " +
        s"${c.holding.ergoTree.bytes.length}B and collateral ${c.collateral.ergoTree.bytes.length}B")
    }
  }

  // ─── the builder ──────────────────────────────────────────────────────────

  /**
   * `buildGenesis` chooses the output order, the token layout, the register set and the input count.
   * The deployed proof walks all four at hardcoded offsets, so each is a place they can drift apart.
   */
  property("deployed: the proof declines on a genesis transaction CandidateTxBuilder produced") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val data = productionGenesis(ctx, wallet)
      val f = fraud(ctx, fpMalformedGenesis(ctx),
        NispFixtures.rawNisp(score, sharesOver(ctx, data)), score,
        minerHash = wallet.contract.hashedPropBytes, tokens = Seq(Token(fpTokenId, 1L)))

      println(s"[deployed] genesis tx ${data.txBytes.length}B, collateral box " +
        s"${data.collateralBoxBytes.length}B, accused ${wallet.p2pk}")
      findsNoFraud(f.prover, fraudTx(f)())
    }
  }

  /**
   * Says the property above is running the proof rather than passing vacuously. Same shares, same
   * transaction, a different accused, which is the theft the proof exists to stop.
   */
  property("deployed: the same shares claimed by another miner are caught") {
    withCtx { ctx =>
      val wallet = walletOf(ctx)
      val data = productionGenesis(ctx, wallet)
      val thief = contractOf(proverWith(ctx, otherSecret)).hashedPropBytes
      val f = fraud(ctx, fpMalformedGenesis(ctx),
        NispFixtures.rawNisp(score, sharesOver(ctx, data)), score,
        minerHash = thief, tokens = Seq(Token(fpTokenId, 1L)))
      provesFraud(f.prover, fraudTx(f)())
    }
  }
}
