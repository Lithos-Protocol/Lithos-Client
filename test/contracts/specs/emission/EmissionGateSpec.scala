package contracts.specs.emission

import lfsm.LFSMHelpers
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Emission_Gate.ergo — the whole contract is one condition:
 *
 *   sigmaProp( INPUTS(0).tokens(0)._1 == CONST_EMISSION_NFT )
 *
 * Queue boxes and proof-of-spend boxes both sit under it, and both are spendable by anyone, but only
 * in a transaction the emission box is driving. Everything about where their value and tokens may go
 * is decided by the emission contract, so there is nothing else here to test — which is the point.
 *
 * These properties drive the gate directly rather than through a full emission spend: the emission box
 * is stood in for by a box carrying the emission NFT, which is all the gate ever looks at.
 */
class EmissionGateSpec extends AnyPropSpec with EmissionSpecBase {

  /** Stands in for the emission box. The gate reads nothing but token 0 of INPUTS(0). */
  private def nftHolder(ctx: BlockchainContext,
                        nft: ErgoId = LFSMHelpers.EMISSION_NFT,
                        index: Int = 0): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg, Seq(Token(nft, 1L))), ctx, index)

  private def tokenlessInput(ctx: BlockchainContext): InputUTXO =
    inputAt(UTXO(Contract.SIGMA_TRUE, Parameters.OneErg), ctx, 0)

  /** Wherever the gate box's contents go is the emission contract's business, not the gate's. */
  private def sink(box: UTXO): UTXO =
    UTXO(Contract.SIGMA_TRUE, box.value, box.tokens)

  private def queueBox(ctx: BlockchainContext): UTXO =
    queueUTXO(ctx, lender(ctx), position = 0L, permit = permitAt(0L))

  private def posBox(ctx: BlockchainContext): UTXO =
    proofOfSpendUTXO(ctx, setEntry(lender(ctx)))

  // ─── queue boxes ──────────────────────────────────────────────────────────

  property("gate: accepts a queue box spent behind a box carrying the emission NFT") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = queueBox(ctx)
      accepts(prover, build(ctx, Seq(nftHolder(ctx), inputAt(box, ctx, 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }

  property("gate: rejects when INPUTS(0) carries a token that is not the emission NFT") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = queueBox(ctx)
      val impostor = nftHolder(ctx, LFSMHelpers.COLLAT_TOKEN)
      rejects(prover, build(ctx, Seq(impostor, inputAt(box, ctx, 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }

  property("gate: rejects when INPUTS(0) carries no tokens at all") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = queueBox(ctx)
      rejects(prover, build(ctx, Seq(tokenlessInput(ctx), inputAt(box, ctx, 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }

  property("gate: rejects when the queue box is itself INPUTS(0)") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = queueBox(ctx)
      rejects(prover, build(ctx, Seq(inputAt(box, ctx, 0), nftHolder(ctx, index = 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }

  // ─── proof-of-spend boxes ─────────────────────────────────────────────────

  property("gate: accepts a proof-of-spend box spent behind the emission NFT") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = posBox(ctx)
      accepts(prover, build(ctx, Seq(nftHolder(ctx), inputAt(box, ctx, 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }

  property("gate: rejects a proof-of-spend box with no emission box driving the transaction") {
    withCtx { ctx =>
      val prover = miner(ctx)
      val box = posBox(ctx)
      rejects(prover, build(ctx, Seq(inputAt(box, ctx, 0), nftHolder(ctx, index = 1)),
        Seq(sink(box)), prover.getAddress))
    }
  }
}
