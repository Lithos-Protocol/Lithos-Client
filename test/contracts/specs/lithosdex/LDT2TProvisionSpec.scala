package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.UTXO

/**
 * LD_T2T_Provision's paid refresh: an aged token/token provision may be renewed by anyone, who keeps up to
 * CONST_REFRESH_BOUNTY of the box's ERG but never takes it below CONST_REFRESH_FLOOR. Everything else about a
 * refresh is LD_Provision's, so the age, slack and identity properties in `LDProvisionSpec` are repeated here
 * only where the bounty could change them.
 */
class LDT2TProvisionSpec extends AnyPropSpec with LDT2TSpecBase {
  import LDT2TSpecBase._

  private val shares: Long = 10000000000L
  private val refreshHeight: Int = 1100000
  private val agedAt: Int = refreshHeight - LDHelpers.REFRESH_AGE - 1
  private val BOUNTY: Long = LDHelpers.T2T_REFRESH_BOUNTY
  private val FLOOR: Long = LDHelpers.T2T_REFRESH_FLOOR

  /** An aged provision holding `value`, its refresh input, and a successor holding `successorValue`. */
  private def refresh(ctx: BlockchainContext,
                      value: Long,
                      successorValue: Long,
                      createdAt: Int = agedAt,
                      withFunding: Boolean = true): UnsignedTransaction = {
    val box = provisionUTXO(traded.accX, traded.accY, ownerNFT, shares, value).setCreationHeight(createdAt)
    val successor = provisionUTXO(traded.accX, traded.accY, ownerNFT, shares, successorValue)
      .setCreationHeight(refreshHeight)
    val inputs = provisionInput(ctx, box, LDHelpers.PROV_REFRESH, 0) +:
      (if (withFunding) Seq(funding(ctx, 1)) else Seq.empty)
    buildAt(ctx, refreshHeight, inputs, Seq(successor), user(ctx).getAddress)
  }

  // ─── the bounty ───────────────────────────────────────────────────────────

  property("bounty: a refresh may keep the whole bounty from a box at the deposit floor") {
    withCtx { ctx =>
      accepts(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN - BOUNTY))
    }
  }

  property("bounty: rejects keeping one nanoERG more than the bounty") {
    withCtx { ctx =>
      rejectsAtSigning(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN - BOUNTY - 1))
    }
  }

  property("bounty: pays for its own refresh, with no ERG of the refresher's in the transaction") {
    withCtx { ctx =>
      // The point of the bounty: the network fee comes out of it and the rest is change to the refresher,
      // so renewing someone else's box costs nothing and earns something
      val signed = accepts(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN - BOUNTY, withFunding = false))
      signed.getOutputsToSpend.size shouldBe 3
    }
  }

  property("bounty: never takes the box below the floor") {
    withCtx { ctx =>
      val nearFloor = FLOOR + BOUNTY / 2
      accepts(user(ctx), refresh(ctx, nearFloor, FLOOR))
      rejectsAtSigning(user(ctx), refresh(ctx, nearFloor, FLOOR - 1))
    }
  }

  property("bounty: a box at the floor is renewed for nothing") {
    withCtx { ctx =>
      accepts(user(ctx), refresh(ctx, FLOOR, FLOOR))
      rejectsAtSigning(user(ctx), refresh(ctx, FLOOR, FLOOR - 1))
    }
  }

  property("bounty: a box already under the floor, as after a rent collection, is renewed for nothing") {
    withCtx { ctx =>
      val collected = FLOOR - 100000000L
      accepts(user(ctx), refresh(ctx, collected, collected))
      rejectsAtSigning(user(ctx), refresh(ctx, collected, collected - 1))
    }
  }

  property("bounty: the contract and LDHelpers.t2tRefreshMinValue agree at every boundary") {
    withCtx { ctx =>
      Seq(FLOOR - 1, FLOOR, FLOOR + 1, FLOOR + BOUNTY - 1, FLOOR + BOUNTY, FLOOR + BOUNTY + 1,
        PROVISION_MIN, 5L * Parameters.OneErg).foreach { value =>
        val least = LDHelpers.t2tRefreshMinValue(value)
        withClue(s"box of $value: ") {
          accepts(user(ctx), refresh(ctx, value, least))
          rejectsAtSigning(user(ctx), refresh(ctx, value, least - 1))
        }
      }
    }
  }

  property("bounty: a topped-up successor is still a valid refresh") {
    withCtx { ctx =>
      accepts(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN + Parameters.OneErg))
    }
  }

  // ─── what the bounty does not open ───────────────────────────────────────

  property("bounty: not before CONST_REFRESH_AGE, even taking nothing") {
    withCtx { ctx =>
      rejectsAtSigning(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN - BOUNTY, createdAt = agedAt + 1))
      rejectsAtSigning(user(ctx), refresh(ctx, PROVISION_MIN, PROVISION_MIN, createdAt = agedAt + 1))
    }
  }

  property("bounty: the refreshed box is young, so a second bounty waits another window") {
    withCtx { ctx =>
      // The successor declares refreshHeight; spending it again at once is a box one block old
      val again = provisionUTXO(traded.accX, traded.accY, ownerNFT, shares, PROVISION_MIN - BOUNTY)
        .setCreationHeight(refreshHeight)
      val next = provisionUTXO(traded.accX, traded.accY, ownerNFT, shares, PROVISION_MIN - 2 * BOUNTY)
        .setCreationHeight(refreshHeight + 1)
      rejectsAtSigning(user(ctx), buildAt(ctx, refreshHeight + 1,
        Seq(provisionInput(ctx, again, LDHelpers.PROV_REFRESH, 0), funding(ctx, 1)), Seq(next), user(ctx).getAddress))
    }
  }

  property("bounty: a claim may not take it") {
    withCtx { ctx =>
      val (s, _, _) = claimScenario(ctx)
      val prov = s.outputs(1)
      val skimmed = prov.subValue(BOUNTY)
      rejectsAtSigning(user(ctx), s.output(1, skimmed).tx)
    }
  }

  property("bounty: two aged provisions each keep their own bounty in one transaction") {
    withCtx { ctx =>
      val a = provisionUTXO(traded.accX, traded.accY, ownerNFT, shares, PROVISION_MIN).setCreationHeight(agedAt)
      val b = provisionUTXO(traded.accX, traded.accY, otherOwnerNFT, shares / 2, PROVISION_MIN).setCreationHeight(agedAt)
      def after(p: UTXO): UTXO = p.subValue(BOUNTY)
        .setCreationHeight(refreshHeight)
      accepts(user(ctx), buildAt(ctx, refreshHeight,
        Seq(provisionInput(ctx, a, LDHelpers.PROV_REFRESH, 0), provisionInput(ctx, b, LDHelpers.PROV_REFRESH, 1)),
        Seq(after(a), after(b)), user(ctx).getAddress))
    }
  }

  property("bounty: an ERG/token provision's refresh still pays nothing") {
    withCtx { ctx =>
      // The ERG/token logic is a different script; a box under it keeps LD_Provision's rules
      val n2tLogic = lithosdex.contracts.LDContracts(poolNFT, vaultNFT, provToken, NetworkType.MAINNET)
      val box = UTXO(n2tLogic.provisionGuard, PROVISION_MIN,
        Seq(work.lithos.mutations.Token(provToken, 1L)),
        Seq(bigIntValue(traded.accX), bigIntValue(traded.accY), bytesValue(ownerNFT.getBytes), ErgoValue.of(shares)))
        .setCreationHeight(agedAt)
      val in = inputAt(box, ctx, 0)
        .withCtxVar(0.toByte, ErgoValue.of(LDHelpers.PROV_REFRESH))
        .withCtxVar(LDHelpers.PROVISION_LOGIC_VAR, bytesValue(n2tLogic.provisionLogic.valueBytes))
      val skimmed = box.subValue(BOUNTY).setCreationHeight(refreshHeight)
      rejectsAtSigning(user(ctx), buildAt(ctx, refreshHeight, Seq(in, funding(ctx, 1)), Seq(skimmed), user(ctx).getAddress))
    }
  }
}
