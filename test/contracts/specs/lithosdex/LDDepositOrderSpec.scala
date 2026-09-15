package contracts.specs.lithosdex

import lithosdex.LDHelpers
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, Token, UTXO}

/** LD_DepositOrder, executed against the real pool. */
class LDDepositOrderSpec extends AnyPropSpec with LDOrderSpecBase {

  import LithosDexSpecBase._

  private val PROVISION = 1
  private val REWARD = 2
  private val TAKINGS = 3

  private val otherOps: Seq[Byte] =
    Seq(LDHelpers.POOL_SWAP, LDHelpers.POOL_REDEEM, LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE)

  property("deposit: a known-good execution with the token side in excess is accepted") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 1.2)
      e.outputs(REWARD).tokens should have size 2
      accepts(executor(ctx), e.tx)
    }
  }

  property("deposit: a known-good execution with the ERG side in excess is accepted") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 0.8)
      e.outputs(REWARD).value should be > REWARD_ERG
      accepts(executor(ctx), e.tx)
    }
  }

  property("deposit: rejects a provision one share smaller than the funds buy") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx)
      val shares = e.after.supply - e.before.supply
      val smaller = e.copy(after = e.after.copy(supply = e.after.supply - 1))
      val nft = e.inputs(0).id
      rejectsAtSigning(executor(ctx), smaller
        .output(0, poolUTXO(ctx, smaller.after))
        .output(PROVISION, provisionUTXO(ctx, e.before.accX, e.before.accY, nft, shares - 1)).tx)
    }
  }

  property("deposit: the owner's minimum is inclusive (CONST_MIN_SHARES)") {
    withCtx { ctx =>
      val shares = depositOrderScenario(ctx).after.supply - traded.supply
      accepts(executor(ctx), depositOrderScenario(ctx, minShares = shares).tx)
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, minShares = shares + 1).tx)
    }
  }

  /**
   * A sandwich moves the pool before the deposit and back after it, taking the difference from the owner.
   * An order asking for its fair shares less 1% refuses the moved pool, which signs with no minimum.
   */
  property("deposit: refuses a pool moved against it before execution (CONST_MIN_SHARES)") {
    withCtx { ctx =>
      val offeredY = offeredYFor(traded, Parameters.OneErg, 1.2)
      val fairShares = depositOrderScenario(ctx, offered = offeredY).after.supply - traded.supply
      val tolerated = fairShares * 99 / 100
      val moved = afterSwap(traded, 10L * Parameters.OneErg, ergIn = true)
      accepts(executor(ctx), depositOrderScenario(ctx, state = moved, offered = offeredY, minShares = 0L).tx)
      rejectsAtSigning(executor(ctx),
        depositOrderScenario(ctx, state = moved, offered = offeredY, minShares = tolerated).tx)
    }
  }

  property("deposit: rejects keeping one unit of the owner's token change") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 1.2)
      val change = e.outputs(REWARD).tokens(1)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).setTokens(e.outputs(REWARD).tokens.head, Token(tokenY, change.amount - 1)))
        .output(TAKINGS, takingsUTXO(ctx, FEE, Seq(Token(tokenY, 1L)))).tx)
    }
  }

  property("deposit: rejects keeping one nanoERG of the owner's ERG change") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 0.8)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).subValue(1L))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(1L)).tx)
    }
  }

  property("deposit: rejects keeping one nanoERG when nothing but the reward box is owed back") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 1.2)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).subValue(1L))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(1L)).tx)
    }
  }

  property("deposit: ERG moved from the reward into the provision box still counts as returned") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 0.8)
      accepts(executor(ctx), e
        .output(PROVISION, e.outputs(PROVISION).addValue(1000L))
        .output(REWARD, e.outputs(REWARD).subValue(1000L)).tx)
    }
  }

  property("deposit: rejects the ownership NFT going anywhere but the owner's reward") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 0.8)
      val nft = e.outputs(REWARD).tokens.head
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).setTokens())
        .output(TAKINGS, e.outputs(TAKINGS).addToken(nft)).tx)
    }
  }

  property("deposit: rejects a reward paid to anyone but the owner") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("deposit: rejects an order holding some other token") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, orderToken = unrelatedToken).tx)
    }
  }

  property("deposit: rejects a miner fee above the order's cap") {
    withCtx { ctx =>
      accepts(executor(ctx), depositOrderScenario(ctx, maxMinerFee = Parameters.MinFee).tx)
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, maxMinerFee = Parameters.MinFee - 1).tx)
    }
  }

  property("deposit: refuses to execute from any input but 1") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(2), e.inputs(1))).tx)
    }
  }

  property("deposit: accepts only the pool's deposit operation (stand-in pool isolates the order)") {
    withCtx { ctx =>
      accepts(executor(ctx), depositOrderScenario(ctx, setup = PoolSetup(standIn = true)).tx)
      otherOps.foreach { op =>
        rejectsAtSigning(executor(ctx),
          depositOrderScenario(ctx, setup = PoolSetup(standIn = true, op = Some(op))).tx)
      }
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, setup = PoolSetup(standIn = true, op = None)).tx)
    }
  }

  /**
   * The attack the operation check exists for. Under a swap the pool mints nothing itself and pins no
   * output but its own, yet any transaction spending it first may still mint a token with its id. So an
   * executor can donate to the pool, hand the owner a freshly minted "NFT" beside a box that only looks
   * like a provision, and keep the deposit.
   */
  private def mintUnderSwap(ctx: BlockchainContext, withOrder: Boolean): UnsignedTransaction = {
    val e = depositOrderScenario(ctx, ratio = 0.8)
    val s = e.before
    val donation = Parameters.OneErg
    val q = s.view.simSwap(donation, ergIn = true)
    val donated = s.copy(
      reservesX = s.reservesX + q.tradedIn,
      pendingX = s.pendingX + q.protocolFee,
      accX = s.accX + (BigInt(q.protocolFee) * SCALE) / BigInt(s.supply))
    val poolIn = poolInput(ctx, poolUTXO(ctx, s), LDHelpers.POOL_SWAP)
    val nft = poolIn.id
    val shares = e.after.supply - s.supply

    val order = e.inputs(1)
    val orderIn = if (withOrder) order else inputAt(UTXO(executorContract(ctx), order.value, order.tokens), ctx, 1)
    val offeredY = order.tokens.head.amount
    val t = depositTerms(s, Parameters.OneErg, offeredY)

    val fakeProvision = UTXO(executorContract(ctx), PROVISION_MIN, Seq.empty[Token],
      Seq(bigIntValue(s.accX), bigIntValue(s.accY), bytesValue(nft.getBytes), ErgoValue.of(shares)))
    val reward = rewardUTXO(ctx, REWARD_ERG + t.excessX.toLong, Seq(Token(nft, 1L)))
    val kept = UTXO(executorContract(ctx), Parameters.OneErg - t.excessX.toLong + FEE,
      Seq(Token(tokenY, offeredY)))

    build(ctx, Seq(poolIn, orderIn, executorFunding(ctx, 2)),
      Seq(poolUTXO(ctx, donated), fakeProvision, reward, kept), executor(ctx).getAddress)
  }

  property("deposit: the pool accepts a donation that mints a token with its id beside a fake provision") {
    withCtx { ctx =>
      accepts(executor(ctx), mintUnderSwap(ctx, withOrder = false))
    }
  }

  property("deposit: rejects that same shape when the funds are the order's") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), mintUnderSwap(ctx, withOrder = true))
    }
  }
}
