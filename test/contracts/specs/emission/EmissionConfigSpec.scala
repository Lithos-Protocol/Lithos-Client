package contracts.specs.emission

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.scalatest.propspec.AnyPropSpec
import scorex.crypto.hash.Blake2b256
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Emission_Config.ergo — one property per named condition in the contract.
 *
 * Two spending paths, joined by `sigmaProp(recreated) || (CONST_TESTNET_PK && sigmaProp(successorWellFormed))`:
 *
 *   recreated            anyone may respend the box provided nothing about it changes, which is what
 *                        keeps it alive against storage rent without letting anyone edit it
 *   successorWellFormed  the key holder may change the parameters, but only into a successor that is
 *                        still a usable config box
 *
 * The split is what §17.7.3 asked for: the key gates *what* changes and never *whether the box
 * survives*. The well-formedness clauses close both malformed-successor hazards on record — a short R4
 * halts every Join (§26.1) and a bad R7 mints collateral boxes paying into nothing (§27.2).
 *
 * R5 and R6 hold hashed VALUE bytes while R7 holds hashed PROP bytes. Nothing in any contract
 * distinguishes them, so only their length is checkable here.
 */
class EmissionConfigSpec extends AnyPropSpec with EmissionSpecBase {

  /** Retuned values, distinct from the launch parameters so a change is visible. */
  private val retunedFloor: Long = 2000L * LIT
  private val retunedCeiling: Long = 40000L * LIT
  private val retunedSlope: Long = 3L * LIT

  private def hashOf(s: String): Array[Byte] = Blake2b256.hash(s.getBytes("UTF-8"))

  private def paramsValue(values: Array[Long]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(values), scalaLongType)

  private def config(ctx: BlockchainContext): UTXO = configBox(ctx, enforcerScript(ctx))

  private def configIn(ctx: BlockchainContext, box: UTXO): InputUTXO = inputAt(box, ctx, 0)

  private def spend(ctx: BlockchainContext,
                    prover: ErgoProver,
                    box: UTXO,
                    successor: UTXO,
                    inputs: Seq[InputUTXO] = null): UnsignedTransaction = {
    val ins = if (inputs == null) Seq(configIn(ctx, box), fundingInput(ctx, prover)) else inputs
    build(ctx, ins, Seq(successor), prover.getAddress)
  }

  // ─── path 1: recreate ─────────────────────────────────────────────────────

  /**
   * Deliberately permissionless. If only the key holder could respend it, losing the key would let the
   * box die of storage rent and take the whole emission subsystem with it.
   */
  property("recreate: accepts an unchanged respend by someone who does not hold the key") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      accepts(prover, spend(ctx, prover, box, box))
    }
  }

  property("recreate: rejects when the config box is not INPUTS(0) (SELF.id == INPUTS(0).id)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      rejectsAtSigning(prover, spend(ctx, prover, box, box,
        inputs = Seq(fundingInput(ctx, prover), inputAt(box, ctx, 2))))
    }
  }

  property("recreate: rejects changed permit params (nextPermitParams)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      val successor = box.withReg(0, paramsValue(Array(1L, 2L, 3L)))
      rejectsAtSigning(prover, spend(ctx, prover, box, successor))
    }
  }

  property("recreate: rejects a changed enforcer hash (nextEnforcerHash)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      rejectsAtSigning(prover, spend(ctx, prover, box, box.withReg(1, bytesValue(hashOf("other-enforcer")))))
    }
  }

  property("recreate: rejects a changed extension hash (nextExtensionHash)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      rejectsAtSigning(prover, spend(ctx, prover, box, box.withReg(2, bytesValue(hashOf("other-extension")))))
    }
  }

  property("recreate: rejects a changed rollup hash (nextRollupHash)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      rejectsAtSigning(prover, spend(ctx, prover, box, box.withReg(3, bytesValue(hashOf("other-rollup")))))
    }
  }

  property("recreate: rejects a successor that does not carry the config NFT (nextEmConfigToken)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      val successor = box.setTokens(Token(unrelatedTokenId, 1L))
      val in = inputAt(UTXO(contractOf(prover), Parameters.OneErg, Seq(Token(unrelatedTokenId, 1L))), ctx, 1)
      rejectsAtSigning(prover, spend(ctx, prover, box, successor,
        inputs = Seq(configIn(ctx, box), in)))
    }
  }

  property("recreate: rejects a successor under a different script (propositionBytes)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = config(ctx)
      rejectsAtSigning(prover, spend(ctx, prover, box, box.setContract(Contract.SIGMA_TRUE)))
    }
  }

  // ─── path 2: the key holder changes parameters ────────────────────────────

  /**
   * The thermostat's numbers live in R4 data rather than in the enforcer's code, so retuning them is a
   * config spend with no redeploy (analysis §26.1). Fast dynamics come from the queue term, slow ones
   * from here.
   */
  property("key path: accepts retuned permit params signed by the key holder") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      val successor = box.withReg(0, paramsValue(Array(retunedFloor, retunedCeiling, retunedSlope)))
      accepts(owner, spend(ctx, owner, box, successor))
    }
  }

  /** A drain-then-switch enforcer upgrade is exactly this: a new hash in R5 (analysis §22.9). */
  property("key path: accepts a new enforcer hash signed by the key holder") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      accepts(owner, spend(ctx, owner, box, box.withReg(1, bytesValue(hashOf("next-enforcer")))))
    }
  }

  property("key path: accepts more than three permit params (R4.size >= 3)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      val successor = box.withReg(0, paramsValue(Array(retunedFloor, retunedCeiling, retunedSlope, 99L)))
      accepts(owner, spend(ctx, owner, box, successor))
    }
  }

  property("key path: rejects the same change signed by anyone else (CONST_TESTNET_PK)") {
    withCtx { ctx =>
      val stranger = miner(ctx)
      val box = config(ctx)
      val successor = box.withReg(1, bytesValue(hashOf("next-enforcer")))
      rejectsAtSigning(stranger, spend(ctx, stranger, box, successor))
    }
  }

  /**
   * `permitParams(2)` on a short collection halts every Join, and the enforcer is stateless so nothing
   * else would catch it. This is the clause that makes the remaining key-holder risk parameter abuse
   * rather than protocol death.
   */
  property("key path: rejects fewer than three permit params (R4.size >= 3)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      rejectsAtSigning(owner, spend(ctx, owner, box, box.withReg(0, paramsValue(Array(1L, 2L)))))
    }
  }

  property("key path: rejects an enforcer hash that is not 32 bytes (R5.size)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      rejectsAtSigning(owner, spend(ctx, owner, box, box.withReg(1, bytesValue(hashOf("short").take(31)))))
    }
  }

  property("key path: rejects an extension hash that is not 32 bytes (R6.size)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      rejectsAtSigning(owner, spend(ctx, owner, box, box.withReg(2, bytesValue(hashOf("short").take(31)))))
    }
  }

  /** A short R7 mints collateral boxes paying into nothing — dead active-set slots, unrepairable. */
  property("key path: rejects a rollup hash that is not 32 bytes (R7.size)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      rejectsAtSigning(owner, spend(ctx, owner, box, box.withReg(3, bytesValue(hashOf("short").take(31)))))
    }
  }

  property("key path: rejects a successor that drops the config NFT (nextEmConfigToken)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      val successor = box.withReg(1, bytesValue(hashOf("next-enforcer"))).setTokens(Token(unrelatedTokenId, 1L))
      val in = inputAt(UTXO(contractOf(owner), Parameters.OneErg, Seq(Token(unrelatedTokenId, 1L))), ctx, 1)
      rejectsAtSigning(owner, spend(ctx, owner, box, successor, inputs = Seq(configIn(ctx, box), in)))
    }
  }

  property("key path: rejects a successor under a different script (propositionBytes)") {
    withCtx { ctx =>
      val owner = configOwner(ctx)
      val box = config(ctx)
      val successor = box.withReg(1, bytesValue(hashOf("next-enforcer"))).setContract(Contract.SIGMA_TRUE)
      rejectsAtSigning(owner, spend(ctx, owner, box, successor))
    }
  }

  // ─── what the config actually carries ─────────────────────────────────────

  property("config: the launch thermostat parameters are the ones §26.2 decided") {
    withCtx { ctx =>
      LFSMHelpers.PERMIT_FLOOR shouldBe 1000L * LIT
      LFSMHelpers.PERMIT_CEIL shouldBe 30000L * LIT
      LFSMHelpers.PERMIT_SLOPE shouldBe 10L * LIT
      LFSMHelpers.PERMIT_PARAMS.length shouldBe 3
      config(ctx).registers.size shouldBe 4
    }
  }
}
