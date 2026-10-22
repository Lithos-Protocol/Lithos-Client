package lithosdex

import _root_.contracts.specs.lithosdex.{LDT2TSpecBase, LithosDexSpecBase}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{InputUTXO, Token, UTXO}

/**
 * `LDT2TGenesis` against one sound genesis and one rigged copy of it per fact the contracts take on
 * trust. Each rigged copy changes exactly one thing, so a problem reported for it can only come from the
 * check that fact needs.
 */
class LDT2TGenesisSpec extends AnyPropSpec with LDT2TSpecBase {

  import LDT2TSpecBase._

  private val sound = LDT2TGenesis.Emissions(poolNFT = 1L, vaultNFT = 1L, provToken = LDHelpers.PROV_TOKEN_SUPPLY)

  private val otherTxId = "0a" * 32

  private def poolBox(ctx: BlockchainContext, box: UTXO = poolUTXO(genesis)): InputUTXO = inputAt(box, ctx, 0)

  private def vaultBox(ctx: BlockchainContext, box: UTXO = vaultUTXO(SEED, SEED, BigInt(0), BigInt(0))): InputUTXO =
    inputAt(box, ctx, 1)

  private def check(ctx: BlockchainContext,
                    pool: InputUTXO = null,
                    vault: InputUTXO = null,
                    emissions: LDT2TGenesis.Emissions = sound): LDT2TGenesis.Report =
    LDT2TGenesis.check(if (pool == null) poolBox(ctx) else pool, if (vault == null) vaultBox(ctx) else vault, emissions)

  /** Exactly one problem, mentioning `about`. */
  private def flags(report: LDT2TGenesis.Report, about: String): Unit = {
    withClue(report.problems.mkString("; ")) {
      report.problems should have size 1
      report.problems.head should include(about)
    }
  }

  property("genesis: a sound genesis has no problems and names its own deployment") {
    withCtx { ctx =>
      val r = check(ctx)
      withClue(r.problems.mkString("; "))(r.sound shouldBe true)
      r.warnings shouldBe empty
      r.contracts.map(_.liquidityPool.propBytes.toSeq) shouldBe Some(deployment.liquidityPool.propBytes.toSeq)
    }
  }

  // ─── what lets a pool be drained ──────────────────────────────────────────

  property("genesis: flags provision tokens minted outside the pool") {
    withCtx { ctx =>
      flags(check(ctx, emissions = sound.copy(provToken = LDHelpers.PROV_TOKEN_SUPPLY + 1L)), "provision token")
    }
  }

  property("genesis: flags a pool NFT minted more than once") {
    withCtx { ctx =>
      flags(check(ctx, emissions = sound.copy(poolNFT = 2L)), "pool NFT")
    }
  }

  property("genesis: flags a vault NFT minted more than once") {
    withCtx { ctx =>
      flags(check(ctx, emissions = sound.copy(vaultNFT = 2L)), "vault NFT")
    }
  }

  property("genesis: flags a pool at any script but its own deployment's") {
    withCtx { ctx =>
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis, contract = userContract(ctx)))), "LD_T2T_LiquidityPool")
      val ergPool = LithosDexSpecBase.compiled(ctx).liquidityPool
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis, contract = ergPool))), "LD_T2T_LiquidityPool")
    }
  }

  property("genesis: flags a vault at any script but its own deployment's") {
    withCtx { ctx =>
      flags(check(ctx, vault = vaultBox(ctx, vaultUTXO(SEED, SEED, BigInt(0), BigInt(0),
        contract = userContract(ctx)))), "LD_T2T_FeeVault")
    }
  }

  property("genesis: flags a pool and vault created in different transactions") {
    withCtx { ctx =>
      val elsewhere = inputAt(vaultUTXO(SEED, SEED, BigInt(0), BigInt(0)), ctx, 1, otherTxId)
      flags(check(ctx, vault = elsewhere), "same transaction")
    }
  }

  property("genesis: flags a pool trading one of its own deployment's tokens") {
    withCtx { ctx =>
      // A token can only repeat across the two boxes, since one box cannot list it twice
      val r = check(ctx, pool = poolBox(ctx, poolUTXO(genesis, tokenXId = vaultNFT)))
      withClue(r.problems.mkString("; "))(r.problems.exists(_.contains("five distinct")) shouldBe true)
    }
  }

  property("genesis: flags a vault holding the pool's tokens the wrong way round") {
    withCtx { ctx =>
      flags(check(ctx, vault = vaultBox(ctx, vaultUTXO(SEED, SEED, BigInt(0), BigInt(0),
        tokenXId = tokenY, tokenYId = tokenX))), "indices 1 and 2")
    }
  }

  property("genesis: flags nonzero accumulators in either box") {
    withCtx { ctx =>
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis.copy(accY = BigInt(1))))), "R7 and R8")
      flags(check(ctx, vault = vaultBox(ctx, vaultUTXO(SEED, SEED, BigInt(1), BigInt(0)))), "vault accumulators")
    }
  }

  property("genesis: flags fees already pending") {
    withCtx { ctx =>
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis.copy(pendingX = 1L)))), "R6")
    }
  }

  // ─── what breaks the arithmetic ───────────────────────────────────────────

  property("genesis: flags a supply under CONST_MIN_SUPPLY, and one the register cannot hold") {
    withCtx { ctx =>
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis.copy(supply = MIN_SUPPLY - 1L)))), "R4")
      soundWith(genesis.copy(supply = MIN_SUPPLY))(ctx)
      // R4 holds CONST_LOCKED_LP - supply, so a supply above the ceiling is a negative register
      val over = poolUTXO(genesis).withReg(0, ErgoValue.of(-1L))
      flags(check(ctx, pool = poolBox(ctx, over)), "R4")
    }
  }

  private def soundWith(s: PoolState)(ctx: BlockchainContext): Unit = {
    val r = check(ctx, pool = poolBox(ctx, poolUTXO(s)))
    withClue(r.problems.mkString("; "))(r.sound shouldBe true)
  }

  property("genesis: flags fee numerators outside the bounds the swap condition needs") {
    withCtx { ctx =>
      val D = FEE_DENOM
      Seq(Array(0L, 15L, 15L), Array(D + 1, 15L, 15L), Array(9970L, D, 15L), Array(9970L, 15L, -1L))
        .foreach(f => flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis.copy(feeParams = f)))), "R5"))
      soundWith(genesis.copy(feeParams = Array(D, 0L, D - 1)))(ctx)
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis.copy(feeParams = Array(9970L, 15L))))), "R5")
    }
  }

  property("genesis: reads a register of the wrong type as absent rather than throwing") {
    withCtx { ctx =>
      val ints = poolUTXO(genesis).withReg(1, ErgoValue.of(Colls.fromArray(Array(9970, 15, 15)), scalaIntType))
      flags(check(ctx, pool = poolBox(ctx, ints)), "R5")
    }
  }

  property("genesis: flags an extra register on either box") {
    withCtx { ctx =>
      val padding = bytesValue(Array.fill(8)(7.toByte))
      flags(check(ctx, pool = poolBox(ctx, poolUTXO(genesis).withReg(5, padding))), "exactly R4 to R8")
      flags(check(ctx, vault = vaultBox(ctx, vaultUTXO(SEED, SEED, BigInt(0), BigInt(0)).withReg(2, padding))),
        "exactly R4 to R8")
    }
  }

  property("genesis: refuses to judge a pool whose token layout is unreadable") {
    withCtx { ctx =>
      val short = poolBox(ctx, poolUTXO(genesis).setTokens(poolUTXO(genesis).tokens.take(3): _*))
      val r = check(ctx, pool = short)
      r.sound shouldBe false
      r.contracts shouldBe None
    }
  }

  // ─── warnings ─────────────────────────────────────────────────────────────

  property("genesis: warns, without calling it unsound, when either box is under its rent floor") {
    withCtx { ctx =>
      val r = check(ctx,
        pool = poolBox(ctx, poolUTXO(genesis.copy(value = POOL_VALUE - 1L))),
        vault = vaultBox(ctx, vaultUTXO(SEED, SEED, BigInt(0), BigInt(0), value = VAULT_VALUE - 1L)))
      r.sound shouldBe true
      r.warnings should have size 2
    }
  }

  property("genesis: a pool that passes is one the contracts accept a first swap on") {
    withCtx { ctx =>
      // Ties the checker to the contracts: the genesis it calls sound is a pool the stack can trade
      check(ctx).sound shouldBe true
      val (s, _, _) = swapScenario(ctx, state = genesis)
      accepts(user(ctx), s.tx)
    }
  }
}
