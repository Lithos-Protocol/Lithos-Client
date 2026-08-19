package contracts.specs.emission

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * Emission_Guard.ergo — one property per named condition in the contract.
 *
 * The emission box's propositionBytes are the guard, not the emission logic (analysis §27.1). It
 * authenticates four things and then runs both scripts from context:
 *
 *   sigmaProp(authenticConfig && authenticEMScript && authenticEnforcerScript && authenticGuard)
 *     && executeFromVar[SigmaProp](64) && executeFromVar[SigmaProp](65)
 *
 * The emission script's hash is a guard *constant* while the enforcer's is read from config R5, so
 * only the enforcer slot can be substituted here. These properties therefore drive a known-good Join
 * with the real emission logic in var 64 and `sigmaProp(true)` in var 65, and perturb one
 * authentication at a time; `LIT_Emissions`' own conditions are covered by [[EmissionSpec]].
 */
class EmissionGuardSpec extends AnyPropSpec with EmissionSpecBase {

  // ─── authentication ───────────────────────────────────────────────────────

  property("guard: accepts a spend whose config, both scripts and emission NFT all check out") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      accepts(j.prover, joinTx(j)())
    }
  }

  property("guard: accepts the production enforcer in var 65 when config R5 names it") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = enforcerScript(ctx))
      accepts(j.prover, joinTx(j)())
    }
  }

  property("guard: rejects a data input that is not the emission config (authenticConfig)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val impostor = configInput(ctx, configBox(ctx, Contract.SIGMA_TRUE, nft = LFSMHelpers.LIT_ID))
      rejects(j.prover, joinTx(j)(dataInputs = Seq(impostor)))
    }
  }

  property("guard: rejects a script in var 64 that is not the emission logic (authenticEMScript)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, emScript = Contract.SIGMA_TRUE)
      rejects(j.prover, joinTx(j)())
    }
  }

  /**
   * The scenario builds the config from whatever goes into var 65, so pointing R5 at a different
   * script is what breaks the pairing — the same failure a stale config R5 would cause on chain.
   */
  property("guard: rejects a script in var 65 that config R5 does not name (authenticEnforcerScript)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val staleConfig = configInput(ctx, configBox(ctx, enforcerScript(ctx)))
      rejects(j.prover, joinTx(j)(dataInputs = Seq(staleConfig)))
    }
  }

  /**
   * Without this the guard script is public: anyone could build a box under it and run the whole
   * emission logic against tokens of their own minting, which is what produced the counterfeit
   * collateral box in analysis §27.1.
   */
  property("guard: rejects a box that does not carry the emission NFT (authenticGuard)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val counterfeit = emissionUTXO(ctx, nft = LFSMHelpers.LIT_ID)
      val in = emissionInput(ctx, counterfeit, 0.toByte, lenderGE = Some(lenderGEValue(ctx, j.lenderProver)))
      val out = emissionUTXO(ctx, nft = LFSMHelpers.LIT_ID, tail = 1L, queueTokens = queueSupply - 1L)
      rejects(j.prover, joinTx(j)(emissionOut = out, inputs = Seq(in, j.funding)))
    }
  }

  // ─── dispatch ─────────────────────────────────────────────────────────────

  /**
   * Authenticating a script is not the same as requiring it to succeed. With `sigmaProp(false)` in
   * var 65 and config R5 naming it, every authentication passes and only `executeFromVar` can reject.
   */
  property("guard: rejects when the authenticated enforcer script evaluates to false (executeFromVar 65)") {
    withCtx { ctx =>
      val j = joinScenario(ctx, enfScript = Contract.SIGMA_FALSE)
      rejects(j.prover, joinTx(j)())
    }
  }

  /**
   * Var 64 cannot be substituted, so the emission logic is forced false from the inside instead: an
   * unrecognised op byte ends `LIT_Emissions` at its fail-closed `sigmaProp(false)`.
   */
  property("guard: rejects when the emission script evaluates to false (executeFromVar 64)") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      val in = emissionInput(ctx, emissionUTXO(ctx), 3.toByte,
        lenderGE = Some(lenderGEValue(ctx, j.lenderProver)))
      rejects(j.prover, joinTx(j)(inputs = Seq(in, j.funding)))
    }
  }

  property("guard: rejects a spend with no data input to authenticate against") {
    withCtx { ctx =>
      val j = joinScenario(ctx)
      rejects(j.prover, joinTx(j)(dataInputs = Seq.empty[InputUTXO]))
    }
  }
}
