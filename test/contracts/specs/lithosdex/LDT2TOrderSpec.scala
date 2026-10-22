package contracts.specs.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.LDOrderContracts
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Token, UTXO}

/** LD_T2T_SwapOrder, LD_T2T_DepositOrder and LD_T2T_RedeemOrder, executed against the real pool. */
class LDT2TOrderSpec extends AnyPropSpec with LDT2TOrderSpecBase {

  import LDT2TSpecBase._

  private val REWARD = 1
  private val TAKINGS = 2

  private val otherOps: Seq[Byte] =
    Seq(LDHelpers.POOL_SWAP, LDHelpers.POOL_DEPOSIT, LDHelpers.POOL_REDEEM, LDHelpers.POOL_FLUSH, LDHelpers.POOL_RESIZE)

  private def opIsolation(ownOp: Byte, run: PoolSetup => Execution)(implicit ctx: BlockchainContext): Unit = {
    accepts(executor(ctx), run(PoolSetup(standIn = true)).tx)
    otherOps.filterNot(_ == ownOp).foreach { op =>
      rejectsAtSigning(executor(ctx), run(PoolSetup(standIn = true, op = Some(op))).tx)
    }
    rejectsAtSigning(executor(ctx), run(PoolSetup(standIn = true, op = None)).tx)
  }

  // ═══ SWAP ═════════════════════════════════════════════════════════════════

  property("swap: a known-good execution is accepted in both directions") {
    withCtx { ctx =>
      accepts(executor(ctx), swapOrderScenario(ctx, xIn = true).tx)
      accepts(executor(ctx), swapOrderScenario(ctx, xIn = false).tx)
    }
  }

  property("swap: the owner may be paid one unit under the pool's output, never two (fairPrice)") {
    withCtx { ctx =>
      // The side fees differ, so a floor priced with the other direction's fee would land elsewhere
      FEE_PARAMS(1) should not equal FEE_PARAMS(2)
      Seq(true, false).foreach { xIn =>
        val e = swapOrderScenario(ctx, xIn = xIn, minQuote = 1L)
        val s = e.before
        val base = 1000L * 1000000L
        val out = s.view.simSwap(base, xIn).amountOut
        val floor =
          if (xIn) fairFloor(s.reservesX, s.reservesY, base, FEE_PARAMS(1))
          else fairFloor(s.reservesY, s.reservesX, base, FEE_PARAMS(2))
        floor should (be(out) or be(out - 1))
        val quote = if (xIn) tokenY else tokenX
        def paying(toOwner: Long) = e
          .output(REWARD, rewardUTXO(ctx, REWARD_ERG, Seq(Token(quote, toOwner))))
          .output(TAKINGS, takingsUTXO(ctx, FEE, if (out > toOwner) Seq(Token(quote, out - toOwner)) else Seq.empty))
        accepts(executor(ctx), paying(floor).tx)
        rejectsAtSigning(executor(ctx), paying(floor - 1).tx)
      }
    }
  }

  property("swap: the owner's minimum is inclusive (minQuote)") {
    withCtx { ctx =>
      val out = traded.view.simSwap(1000L * 1000000L, xIn = true).amountOut
      accepts(executor(ctx), swapOrderScenario(ctx, minQuote = out).tx)
      rejectsAtSigning(executor(ctx), swapOrderScenario(ctx, minQuote = out + 1).tx)
    }
  }

  property("swap: rejects keeping one nanoERG of the owner's ERG") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, e.outputs(REWARD).subValue(1L))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(1L)).tx)
    }
  }

  property("swap: rejects a reward paid to anyone but the owner") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("swap: rejects a reward in the token the owner sold (quoteId)") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx, xIn = true)
      val out = e.outputs(REWARD).tokens.head.amount
      rejectsAtSigning(executor(ctx), e
        .input(2, executorFunding(ctx, 2, Seq(Token(tokenX, out))))
        .output(REWARD, rewardUTXO(ctx, REWARD_ERG, Seq(Token(tokenX, out))))
        .output(TAKINGS, takingsUTXO(ctx, FEE, Seq(Token(tokenY, out)))).tx)
    }
  }

  property("swap: rejects an order holding neither of the pool's tokens") {
    withCtx { ctx =>
      // A token that is not X is priced as Y, so this fill is fair for that reading and the base-token
      // check is the only thing left to refuse it
      rejectsAtSigning(executor(ctx), swapOrderScenario(ctx, xIn = false, orderToken = unrelatedToken).tx)
    }
  }

  property("swap: rejects a miner fee above the order's cap") {
    withCtx { ctx =>
      accepts(executor(ctx), swapOrderScenario(ctx, maxMinerFee = Parameters.MinFee).tx)
      rejectsAtSigning(executor(ctx), swapOrderScenario(ctx, maxMinerFee = Parameters.MinFee - 1).tx)
    }
  }

  property("swap: refuses to execute from any input but 1") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(2), e.inputs(1))).tx)
    }
  }

  property("swap: refuses a pool other than the one it names") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx)
      val elsewhere = LDOrderContracts.t2tSwap(terms(ctx, poolNFTId = unrelatedToken), e.outputs(REWARD).tokens.head.amount)
      rejectsAtSigning(executor(ctx),
        e.input(1, inputAt(UTXO(elsewhere, REWARD_ERG + FEE, e.inputs(1).tokens), ctx, 1)).tx)
    }
  }

  property("swap: two identical orders cannot share one reward (double satisfaction)") {
    withCtx { ctx =>
      val e = swapOrderScenario(ctx)
      val twin = inputAt(e.inputs(1).toUTXO, ctx, 9)
      rejectsAtSigning(executor(ctx), e
        .copy(inputs = e.inputs.take(2) ++ Seq(twin) ++ e.inputs.drop(2))
        .output(TAKINGS, e.outputs(TAKINGS).addValue(twin.value).setTokens(twin.tokens: _*)).tx)
    }
  }

  property("swap: accepts only the pool's swap operation (stand-in pool isolates the order)") {
    withCtx { implicit ctx =>
      opIsolation(LDHelpers.POOL_SWAP, setup => swapOrderScenario(ctx, setup = setup))
    }
  }

  // ═══ DEPOSIT ══════════════════════════════════════════════════════════════

  property("deposit: a known-good execution is accepted with either side in excess") {
    withCtx { ctx =>
      val yExcess = depositOrderScenario(ctx, ratio = 1.2)
      depositTerms(traded, 500L * 1000000L, offeredYFor(traded, 500L * 1000000L, 1.2)).excessY should be > BigInt(0)
      accepts(executor(ctx), yExcess.tx)
      val xExcess = depositOrderScenario(ctx, ratio = 0.8)
      depositTerms(traded, 500L * 1000000L, offeredYFor(traded, 500L * 1000000L, 0.8)).excessX should be > BigInt(0)
      accepts(executor(ctx), xExcess.tx)
    }
  }

  property("deposit: rejects a provision one share smaller than the tokens buy") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, minShares = 1L)
      val shares = e.after.supply - e.before.supply
      val nft = e.inputs.head.id
      rejectsAtSigning(executor(ctx), e
        .output(0, poolUTXO(e.after.copy(supply = e.after.supply - 1L)))
        .output(1, provisionUTXO(traded.accX, traded.accY, nft, shares - 1L)).tx)
    }
  }

  property("deposit: the owner's minimum is inclusive (CONST_MIN_SHARES)") {
    withCtx { ctx =>
      val shares = depositTerms(traded, 500L * 1000000L, offeredYFor(traded, 500L * 1000000L, 1.2)).shares.toLong
      accepts(executor(ctx), depositOrderScenario(ctx, minShares = shares).tx)
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, minShares = shares + 1).tx)
    }
  }

  property("deposit: rejects keeping one unit of the owner's Y change") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 1.2)
      val r = e.outputs(REWARD + 1)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD + 1, r.setTokens(r.tokens.head, r.tokens(1) - 1L))
        .output(TAKINGS + 1, takingsUTXO(ctx, FEE, Seq(Token(tokenY, 1L)))).tx)
    }
  }

  property("deposit: rejects keeping one unit of the owner's X change") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx, ratio = 0.8)
      val r = e.outputs(REWARD + 1)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD + 1, r.setTokens(r.tokens.head, r.tokens(1) - 1L))
        .output(TAKINGS + 1, takingsUTXO(ctx, FEE, Seq(Token(tokenX, 1L)))).tx)
    }
  }

  property("deposit: rejects keeping one nanoERG of the owner's ERG") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD + 1, e.outputs(REWARD + 1).subValue(1L))
        .output(TAKINGS + 1, e.outputs(TAKINGS + 1).addValue(1L)).tx)
    }
  }

  property("deposit: rejects the ownership NFT going anywhere but the owner's reward") {
    withCtx { ctx =>
      val e = depositOrderScenario(ctx)
      val r = e.outputs(REWARD + 1)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD + 1, r.setTokens(r.tokens.tail: _*))
        .output(TAKINGS + 1, e.outputs(TAKINGS + 1).addToken(r.tokens.head)).tx)
    }
  }

  property("deposit: rejects an order holding its tokens in the wrong order") {
    withCtx { ctx =>
      rejectsAtSigning(executor(ctx), depositOrderScenario(ctx, swapped = true).tx)
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
    withCtx { implicit ctx =>
      opIsolation(LDHelpers.POOL_DEPOSIT, setup => depositOrderScenario(ctx, setup = setup))
    }
  }

  /**
   * The attack the operation check exists for: under a swap the pool pins no output but its own, yet the
   * transaction may still mint a token with its id. The executor donates, hands the owner that token
   * beside a box that only looks like a provision, and keeps the deposit.
   */
  private def mintUnderSwap(ctx: BlockchainContext, withOrder: Boolean): UnsignedTransaction = {
    val e = depositOrderScenario(ctx, ratio = 0.8)
    val s = e.before
    val donation = 1000000L
    val q = s.view.simSwap(donation, xIn = true)
    val donated = s.copy(reservesX = s.reservesX + q.tradedIn, pendingX = s.pendingX + q.protocolFee,
      accX = s.accX + (BigInt(q.protocolFee) * SCALE) / BigInt(s.supply))
    val poolIn = poolInput(ctx, poolUTXO(s), LDHelpers.POOL_SWAP)
    val nft = poolIn.id
    val shares = e.after.supply - s.supply
    val order = e.inputs(1)
    val orderIn = if (withOrder) order else inputAt(UTXO(executorContract(ctx), order.value, order.tokens), ctx, 1)
    val t = depositTerms(s, order.tokens.head.amount, order.tokens(1).amount)

    val fakeProvision = UTXO(executorContract(ctx), PROVISION_MIN, Seq.empty[Token],
      Seq(bigIntValue(s.accX), bigIntValue(s.accY), bytesValue(nft.getBytes), ErgoValue.of(shares)))
    val reward = rewardUTXO(ctx, REWARD_ERG, Seq(Token(nft, 1L), Token(tokenX, t.excessX.toLong)))
    val kept = UTXO(executorContract(ctx), FEE,
      Seq(Token(tokenX, order.tokens.head.amount - t.excessX.toLong - donation), Token(tokenY, order.tokens(1).amount)))

    build(ctx, Seq(poolIn, orderIn, executorFunding(ctx, 2)),
      Seq(poolUTXO(donated), fakeProvision, reward, kept), executor(ctx).getAddress)
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

  // ═══ REDEEM ═══════════════════════════════════════════════════════════════

  property("redeem: a known-good execution is accepted") {
    withCtx { ctx =>
      accepts(executor(ctx), redeemOrderScenario(ctx).tx)
    }
  }

  property("redeem: rejects keeping one unit of either token of the owner's proceeds") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      val r = e.outputs(REWARD)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, r.setTokens(r.tokens.head - 1L, r.tokens(1)))
        .output(TAKINGS, takingsUTXO(ctx, FEE, Seq(Token(tokenX, 1L)))).tx)
      rejectsAtSigning(executor(ctx), e
        .output(REWARD, r.setTokens(r.tokens.head, r.tokens(1) - 1L))
        .output(TAKINGS, takingsUTXO(ctx, FEE, Seq(Token(tokenY, 1L)))).tx)
    }
  }

  property("redeem: accepts the proceeds in either token order") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      val r = e.outputs(REWARD)
      accepts(executor(ctx), e.output(REWARD, r.setTokens(r.tokens.reverse: _*)).tx)
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

  property("redeem: rejects proceeds paid to anyone but the owner") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.output(REWARD, e.outputs(REWARD).setContract(executorContract(ctx))).tx)
    }
  }

  property("redeem: refuses to execute from any input but 2") {
    withCtx { ctx =>
      val e = redeemOrderScenario(ctx)
      rejectsAtSigning(executor(ctx), e.copy(inputs = Seq(e.inputs(0), e.inputs(1), e.inputs(3), e.inputs(2))).tx)
    }
  }

  property("redeem: accepts only the pool's redeem operation (stand-in pool isolates the order)") {
    withCtx { implicit ctx =>
      opIsolation(LDHelpers.POOL_REDEEM, setup => redeemOrderScenario(ctx, setup = setup))
    }
  }

  /** A real redemption of somebody else's provision, with the order's NFT carried along and taken. */
  private def otherProvisionRedeemed(ctx: BlockchainContext, withOrder: Boolean): UnsignedTransaction = {
    val e = redeemOrderScenario(ctx)
    val shares = e.before.supply - e.after.supply
    val other = provisionOf(shares, ownerId = otherOwnerNFT)
    val order = e.inputs(2)
    val orderIn = if (withOrder) order else inputAt(UTXO(executorContract(ctx), order.value, order.tokens), ctx, 2)
    build(ctx,
      Seq(e.inputs(0), provisionInput(ctx, other.utxo, LDHelpers.PROV_REDEEM, 1), orderIn,
        executorFunding(ctx, 3, Seq(Token(otherOwnerNFT, 1L)))),
      Seq(e.outputs(0), e.outputs(REWARD), UTXO(executorContract(ctx), FEE, Seq(Token(ownerNFT, 1L)))),
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
}
