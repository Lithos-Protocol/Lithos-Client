package lithosdex

import _root_.contracts.specs.lithosdex.LDT2TSpecBase
import com.google.gson.JsonParser
import lithosdex.LDGenesisBuilder.{N2TSeed, T2TSeed}
import node.MutationConversions._
import node.rest.NodeCodecs
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.io.Source

/**
 * `LDGenesis` against the live ERG:LIT genesis and rigged copies of it, and `LDGenesisBuilder` end to end:
 * each of its three transactions signed, and the boxes it creates judged sound by the checker for their kind.
 */
class LDGenesisSpec extends AnyPropSpec with LDT2TSpecBase {

  import LDT2TSpecBase._

  private val litEmissions = LDGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY)

  /** A box the mainnet node reported, as captured in test resources. */
  private def captured(ctx: BlockchainContext, name: String): InputUTXO = {
    val src = Source.fromResource(s"lithosdex-deployments/$name")
    val json = try src.mkString finally src.close()
    NodeCodecs.box(new JsonParser().parse(json).getAsJsonObject).toInputUTXO(ctx)
  }

  private def livePool(ctx: BlockchainContext) = captured(ctx, "erg-lit-genesis-pool.json")
  private def liveVault(ctx: BlockchainContext) = captured(ctx, "erg-lit-genesis-vault.json")

  private def checkLive(ctx: BlockchainContext, pool: InputUTXO = null, vault: InputUTXO = null,
                        emissions: LDGenesis.Emissions = litEmissions): LDGenesis.Report =
    LDGenesis.check(NetworkType.MAINNET, Option(pool).getOrElse(livePool(ctx)),
      Option(vault).getOrElse(liveVault(ctx)), emissions)

  private def rebuilt(ctx: BlockchainContext, box: InputUTXO)(change: UTXO => UTXO): InputUTXO =
    inputAt(change(UTXO(box.contract, box.value, box.tokens, box.registers)), ctx, 0)

  private def flags(report: LDGenesis.Report, about: String): Unit =
    withClue(report.problems.mkString("; ")) {
      report.problems should have size 1
      report.problems.head should include(about)
    }

  // ─── the live genesis ────────────────────────────────────────────────────

  property("n2t genesis: the live ERG:LIT genesis is sound, with no warnings, and names the canonical deployment") {
    withCtx { ctx =>
      val r = checkLive(ctx)
      withClue(r.problems.mkString("; "))(r.sound shouldBe true)
      r.warnings shouldBe empty
      r.contracts.map(_.liquidityPool.propBytes.toSeq) shouldBe
        Some(lithosdex.contracts.LDContracts(NetworkType.MAINNET).liquidityPool.propBytes.toSeq)
    }
  }

  property("n2t genesis: flags provision tokens minted outside the pool") {
    withCtx { ctx =>
      flags(checkLive(ctx, emissions = litEmissions.copy(provToken = LDHelpers.PROV_TOKEN_SUPPLY + 1L)), "provision token")
    }
  }

  property("n2t genesis: flags either NFT minted more than once") {
    withCtx { ctx =>
      flags(checkLive(ctx, emissions = litEmissions.copy(poolNFT = 2L)), "pool NFT")
      flags(checkLive(ctx, emissions = litEmissions.copy(vaultNFT = 2L)), "vault NFT")
    }
  }

  property("n2t genesis: flags a pool or vault at any script but its own deployment's") {
    withCtx { ctx =>
      flags(checkLive(ctx, pool = rebuilt(ctx, livePool(ctx))(_.setContract(userContract(ctx)))), "LD_LiquidityPool")
      flags(checkLive(ctx, vault = rebuilt(ctx, liveVault(ctx))(_.setContract(userContract(ctx)))), "LD_FeeVault")
    }
  }

  property("n2t genesis: flags fees out of bounds, pending fees and a moved accumulator") {
    withCtx { ctx =>
      val pool = livePool(ctx)
      def withReg(i: Int, v: ErgoValue[_]) = checkLive(ctx, pool = rebuilt(ctx, pool)(_.withReg(i, v)))
      flags(withReg(1, longsValue(Array(0L, 15L, 15L))), "R5")
      flags(withReg(1, longsValue(Array(9985L, 10000L, 15L))), "R5")
      flags(withReg(2, longsValue(Array(1L, 0L))), "R6")
      flags(withReg(3, bigIntValue(BigInt(1))), "R7 and R8")
      flags(checkLive(ctx, vault = rebuilt(ctx, liveVault(ctx))(_.withReg(0, bigIntValue(BigInt(1))))), "vault accumulators")
    }
  }

  property("n2t genesis: flags an extra register, and a layout it cannot read at all") {
    withCtx { ctx =>
      val pool = livePool(ctx)
      flags(checkLive(ctx, pool = rebuilt(ctx, pool)(p => p.setRegs(p.registers :+ bytesValue(Array(1.toByte)): _*))), "exactly R4 to R8")
      val r = checkLive(ctx, pool = rebuilt(ctx, pool)(_.addToken(Token(unrelatedToken, 1L))))
      r.contracts shouldBe None
      r.problems.head should include("expected 3 and 1")
    }
  }

  property("n2t genesis: warns on a vault under the floor every claim keeps back") {
    withCtx { ctx =>
      val r = checkLive(ctx, vault = rebuilt(ctx, liveVault(ctx))(_.setValue(LDHelpers.VAULT_MIN - 1L)))
      r.sound shouldBe true
      r.warnings.mkString should include("every claim keeps back")
    }
  }

  // ─── the builders ────────────────────────────────────────────────────────

  /** Signs the three genesis transactions in turn, each spending the outputs of the ones before it. */
  private def mintBoth(ctx: BlockchainContext): (InputUTXO, InputUTXO) = {
    val p = user(ctx)
    val poolMint = accepts(p, LDGenesisBuilder.mintNft(ctx, Seq(funding(ctx, 0)), userContract(ctx),
      "LD-POOL", "LithosDex pool NFT", p.getAddress))
    val vaultMint = accepts(p, LDGenesisBuilder.mintNft(ctx, Seq(funding(ctx, 1)), userContract(ctx),
      "LD-VAULT", "LithosDex vault NFT", p.getAddress))
    (InputUTXO(poolMint.getOutputsToSpend.get(0)), InputUTXO(vaultMint.getOutputsToSpend.get(0)))
  }

  property("builder: a minted NFT is one unit, named, whose id is the first input's") {
    withCtx { ctx =>
      val first = funding(ctx, 0)
      val signed = accepts(user(ctx), LDGenesisBuilder.mintNft(ctx, Seq(first, funding(ctx, 1)), userContract(ctx),
        "LD-POOL", "LithosDex pool NFT", user(ctx).getAddress))
      val nft = InputUTXO(signed.getOutputsToSpend.get(0))
      nft.tokens shouldBe Seq(Token(first.id, 1L))
      new String(nft.registers.head.getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8") shouldBe "LD-POOL"
    }
  }

  property("builder: an ERG/token genesis signs, and LDGenesis calls what it creates sound with no warnings") {
    withCtx { ctx =>
      val (poolNft, vaultNft) = mintBoth(ctx)
      val seed = N2TSeed(tokenY, reserveX = 2L * Parameters.OneErg, reserveY = 1000000L)
      val (tx, deployment) = LDGenesisBuilder.n2tGenesis(ctx, poolNft, vaultNft,
        Seq(funding(ctx, 2, Seq(Token(tokenY, seed.reserveY)))), seed, user(ctx).getAddress)
      val signed = accepts(user(ctx), tx)
      deployment.provToken shouldBe poolNft.id
      val outs = signed.getOutputsToSpend
      val r = LDGenesis.check(ctx.getNetworkType, InputUTXO(outs.get(0)), InputUTXO(outs.get(1)),
        LDGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY))
      withClue(r.problems.mkString("; "))(r.sound shouldBe true)
      r.warnings shouldBe empty
      r.contracts.map(_.liquidityPool.propBytes.toSeq) shouldBe Some(deployment.liquidityPool.propBytes.toSeq)
    }
  }

  property("builder: an ERG/token genesis with the creator's provision signs, and LDGenesis calls it sound") {
    withCtx { ctx =>
      val (poolNft, vaultNft) = mintBoth(ctx)
      val owner = ErgoId.create("ab" * 32)
      val seed = N2TSeed(tokenY, reserveX = 2L * Parameters.OneErg, reserveY = 1000000L)
      val (tx, deployment) = LDGenesisBuilder.n2tGenesis(ctx, poolNft, vaultNft,
        Seq(funding(ctx, 2, Seq(Token(tokenY, seed.reserveY)))), seed, user(ctx).getAddress, Some(owner))
      val outs = accepts(user(ctx), tx).getOutputsToSpend
      val provision = InputUTXO(outs.get(2))
      provision.contract.ergoTreeHex shouldBe deployment.provisionGuard.ergoTreeHex
      InputUTXO(outs.get(0)).tokens(2) shouldBe Token(deployment.provToken, LDHelpers.PROV_TOKEN_SUPPLY - 1L)
      val r = LDGenesis.check(ctx.getNetworkType, InputUTXO(outs.get(0)), InputUTXO(outs.get(1)),
        LDGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY, Map(owner.toString -> 1L)), Seq(provision))
      withClue(r.problems.mkString("; "))(r.sound shouldBe true)
      r.warnings shouldBe empty
      // Without the provision, the unit it holds is a provision token loose outside the pool
      LDGenesis.check(ctx.getNetworkType, InputUTXO(outs.get(0)), InputUTXO(outs.get(1)),
        LDGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY)).sound shouldBe false
    }
  }

  property("builder: a token/token genesis signs, and LDT2TGenesis calls what it creates sound with no warnings") {
    withCtx { ctx =>
      val (poolNft, vaultNft) = mintBoth(ctx)
      val seed = T2TSeed(tokenX, tokenY, reserveX = 5000000L, reserveY = 1000000L)
      val held = Seq(Token(tokenX, seed.reserveX + 1L), Token(tokenY, seed.reserveY + 1L))
      val (tx, deployment) = LDGenesisBuilder.t2tGenesis(ctx, poolNft, vaultNft, Seq(funding(ctx, 2, held)),
        seed, user(ctx).getAddress)
      val outs = accepts(user(ctx), tx).getOutputsToSpend
      val r = LDT2TGenesis.check(InputUTXO(outs.get(0)), InputUTXO(outs.get(1)),
        LDT2TGenesis.Emissions(1L, 1L, LDHelpers.PROV_TOKEN_SUPPLY))
      withClue(r.problems.mkString("; "))(r.sound shouldBe true)
      r.warnings shouldBe empty
      r.contracts.map(_.provisionGuard.propBytes.toSeq) shouldBe Some(deployment.provisionGuard.propBytes.toSeq)
    }
  }

  property("builder: refuses a genesis the checker would flag") {
    withCtx { ctx =>
      val (poolNft, vaultNft) = mintBoth(ctx)
      val fund = Seq(funding(ctx, 2, Seq(Token(tokenX, 10000000L), Token(tokenY, 10000000L))))
      def n2t(seed: N2TSeed) = LDGenesisBuilder.n2tGenesis(ctx, poolNft, vaultNft, fund, seed, user(ctx).getAddress)
      def t2t(seed: T2TSeed) = LDGenesisBuilder.t2tGenesis(ctx, poolNft, vaultNft, fund, seed, user(ctx).getAddress)
      an[IllegalArgumentException] should be thrownBy n2t(N2TSeed(tokenY, LDHelpers.MIN_RENT - 1L, 1000L))
      an[IllegalArgumentException] should be thrownBy n2t(N2TSeed(tokenY, Parameters.OneErg, 1000L, Seq(9985L, 10000L, 15L)))
      an[IllegalArgumentException] should be thrownBy n2t(N2TSeed(poolNft.tokens.head.id, Parameters.OneErg, 1L))
      an[IllegalArgumentException] should be thrownBy t2t(T2TSeed(tokenX, tokenX, 1000L, 1000L))
      an[IllegalArgumentException] should be thrownBy t2t(T2TSeed(tokenX, tokenY, 0L, 1000L))
      an[IllegalArgumentException] should be thrownBy LDGenesisBuilder.n2tGenesis(ctx, funding(ctx, 3), vaultNft, fund,
        N2TSeed(tokenY, Parameters.OneErg, 1000L), user(ctx).getAddress)
    }
  }
}
