package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/** LD_RedeemOrder, executed against the real pool and provision. */
class LDRedeemOrderSpec extends AnyPropSpec with LDOrderSpecBase {

  import LithosDexSpecBase._

  private val REWARD = 1
  private val TAKINGS = 2

  private val otherOps: Seq[Byte] =
    Seq(LDHelpers.POOL_SWAP, LDHelpers.POOL_DEPOSIT, LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE)

  property("redeem: a known-good execution is accepted") {
    withCtx { ctx =>
      accepts(executor(ctx), redeemOrderScenario(ctx).tx)
    }
  }

  property("redeem: rejects keeping one nanoERG of the owner's proceeds") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).subValue(1L))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(1L)).tx)
    }
  }

  property("redeem: rejects keeping one token of the owner's proceeds") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      val y = e.outputs(REWARD).tokens.head
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).setTokens(Token(tokenY, y.amount - 1)))
        .output(TAKINGS, takingsUTXO(ctx, FEE, Seq(Token(tokenY, 1L)))).tx)
    }
  }

  property("redeem: rejects proceeds paid to anyone but the owner") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("redeem: rejects a miner fee above the order's cap") {
    withCtx { ctx =>
      accepts(executor(ctx), redeemOrderScenario(ctx, maxMinerFee = Parameters.MinFee).tx)
      rejectsAtSigning(executor(ctx), redeemOrderScenario(ctx, maxMinerFee = Parameters.MinFee - 1).tx)
    }
  }

  property("redeem: refuses to execute from any input but 2") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(1), e.inputs(3), e.inputs(2))).tx)
    }
  }

  property("redeem: accepts only the pool's redeem operation (stand-in pool isolates the order)") {
    withCtx { ctx =>
      accepts(executor(ctx), redeemOrderScenario(ctx, setup = PoolSetup(standIn = true)).tx)
      otherOps.foreach { op =>
        rejectsAtSigning(executor(ctx),
          redeemOrderScenario(ctx, setup = PoolSetup(standIn = true, op = Some(op))).tx)
      }
      rejectsAtSigning(executor(ctx), redeemOrderScenario(ctx, setup = PoolSetup(standIn = true, op = None)).tx)
    }
  }

  /**
   * Under a swap the pool authenticates nothing at input 1, so a box that merely claims to be the owner's
   * provision can sit there. The order's NFT then leaves to the executor, who owns the real provision.
   */
  private def fakeProvisionUnderSwap(ctx: BlockchainContext, withOrder: Boolean): UnsignedTransaction = {
    val e = redeemOrderScenario(ctx)
    val s = e.before
    val donation = Parameters.OneErg
    val q = s.view.simSwap(donation, ergIn = true)
    val donated = s.copy(
      reservesX = s.reservesX + q.tradedIn,
      pendingX = s.pendingX + q.protocolFee,
      accX = s.accX + (BigInt(q.protocolFee) * SCALE) / BigInt(s.supply))

    val fake = UTXO(executorContract(ctx), PROVISION_MIN, Seq.empty[Token],
      Seq(bigIntValue(BigInt(0)), bigIntValue(BigInt(0)), bytesValue(ownerNFT.getBytes), ErgoValue.of(1L)))
    val order = e.inputs(2)
    val orderIn = if (withOrder) order else inputAt(UTXO(executorContract(ctx), order.value, order.tokens), ctx, 2)

    build(ctx,
      Seq(poolInput(ctx, poolUTXO(ctx, s), LDHelpers.POOL_SWAP), inputAt(fake, ctx, 1), orderIn, executorFunding(ctx, 3)),
      Seq(poolUTXO(ctx, donated),
        rewardUTXO(ctx, REWARD_ERG + PROVISION_MIN),
        UTXO(executorContract(ctx), FEE, Seq(Token(ownerNFT, 1L)))),
      executor(ctx).getAddress)
  }

  property("redeem: the pool accepts a donation beside a box posing as the owner's provision") {
    withCtx { ctx =>
      accepts(executor(ctx), fakeProvisionUnderSwap(ctx, withOrder = false))
    }
  }

  property("redeem: rejects that fake provision when the NFT is the order's") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), fakeProvisionUnderSwap(ctx, withOrder = true))
    }
  }

  /**
   * A real redemption of somebody else's provision, with the order's NFT carried along and taken. The
   * pool and both provision checks are satisfied: the provision being closed has its own owner present.
   */
  private def otherProvisionRedeemed(ctx: BlockchainContext, withOrder: Boolean): UnsignedTransaction = {
    val e = redeemOrderScenario(ctx)
    val shares = e.before.supply - e.after.supply
    val other = provisionOf(ctx, shares, ownerId = otherOwnerNFT)
    val order = e.inputs(2)
    val orderIn = if (withOrder) order else inputAt(UTXO(executorContract(ctx), order.value, order.tokens), ctx, 2)

    build(ctx,
      Seq(e.inputs(0), provisionInput(ctx, other.utxo, LDHelpers.PROV_REDEEM, 1), orderIn,
        executorFunding(ctx, 3, Seq(Token(otherOwnerNFT, 1L)))),
      Seq(e.outputs(0), e.outputs(REWARD),
        UTXO(executorContract(ctx), FEE, Seq(Token(ownerNFT, 1L)))),
      executor(ctx).getAddress, Seq.empty, Seq(Token(otherOwnerNFT, 1L)))
  }

  property("redeem: the stack accepts redeeming another provision with this NFT carried along") {
    withCtx { ctx =>
      accepts(executor(ctx), otherProvisionRedeemed(ctx, withOrder = false))
    }
  }

  property("redeem: rejects redeeming another provision when the NFT carried along is the order's") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), otherProvisionRedeemed(ctx, withOrder = true))
    }
  }

  property("redeem: refunding returns the NFT to the owner") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      val signed = accepts(user(ctx), refundTx(ctx, e.inputs(2).toUTXO))
      signed.getOutputsToSpend.get(0).getTokens.get(0).getId shouldEqual ownerNFT
    }
  }
}
