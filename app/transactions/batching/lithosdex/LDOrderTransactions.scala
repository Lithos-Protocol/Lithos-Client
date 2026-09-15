package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderContracts, LDOrderTerms}
import lithosdex.states.LDFeeValue
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import org.ergoplatform.appkit.BlockchainContext
import transactions.batching.lithosdex.LDBoxes.Provision
import transactions.batching.lithosdex.LithosDexTransactions.{FUNDING_HEADROOM, TX_FEE, build, funded}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * Placing and cancelling LithosDex orders owned by this wallet.
 *
 * {{{
 *   place     IN  wallet...                          OUT order, [change], fee
 *   place     IN  vault, provision, wallet...        OUT vault, provision, order, [change], fee   (redeem with a claim)
 *   cancel    IN  order, [wallet...]                 OUT refund, [change], fee
 * }}}
 *
 * The owner is always this wallet's primary address, so the fill and the refund land where every other
 * DEX transaction sends its change.
 */
object LDOrderTransactions {

  /**
   * nanoERG an order box holds beyond what its fill spends, returned to the owner beside the fill. The
   * executor refuses a reward box under `MIN_CHANGE`, and this covers the node's per-byte minimum for any
   * reward box this wallet's P2PK owner produces: the largest, a deposit's with its EIP-4 registers, is
   * under 1 kB, or 360,000 nanoERG.
   */
  final val RETURNED: Long = UTXO.MIN_CHANGE

  /** nanoERG a swap order box holds. */
  def swapValue(amountIn: Long, ergIn: Boolean, executorFee: Long): Long =
    if (ergIn) Math.addExact(Math.addExact(amountIn, executorFee), RETURNED) else RETURNED

  /** nanoERG a deposit order box holds: the ERG offered, the provision box, the fee and the return. */
  def depositValue(amountX: Long, executorFee: Long): Long =
    Math.addExact(Math.addExact(amountX, LDHelpers.PROVISION_MIN), Math.addExact(executorFee, RETURNED))

  /** Order terms naming this wallet's primary address as the owner. */
  private[lithosdex] def terms(wallet: NodeWallet, pool: LDLiquidityPool, executorFee: Long, maxMinerFee: Long) =
    LDOrderTerms(wallet.p2pk.getPublicKey, pool.poolNFT, executorFee, maxMinerFee)

  /** Sells `amountIn` of one side for at least `minOutput` of the other, net of the fee when that is ERG. */
  def swap(ctx: BlockchainContext, wallet: NodeWallet, pool: LDLiquidityPool, amountIn: Long, ergIn: Boolean,
           minOutput: Long, executorFee: Long, maxMinerFee: Long): DexPlan[LDOrderTx] = {
    val t = terms(wallet, pool, executorFee, maxMinerFee)
    val contract =
      if (ergIn) LDOrderContracts.swapSell(t, amountIn, minOutput) else LDOrderContracts.swapBuy(t, minOutput)
    val tokens = if (ergIn) Seq.empty[Token] else Seq(Token(pool.tokenY, amountIn))
    place(ctx, wallet, UTXO(contract, swapValue(amountIn, ergIn, executorFee), tokens))
  }

  /** Offers `amountX` nanoERG and `amountY` tokens for at least `minShares`. */
  def deposit(ctx: BlockchainContext, wallet: NodeWallet, pool: LDLiquidityPool, amountX: Long, amountY: Long,
              minShares: Long, executorFee: Long, maxMinerFee: Long): DexPlan[LDOrderTx] = {
    require(pool.canDeposit, "the pool has no provision tokens left to hand out")
    val contract = LDOrderContracts.deposit(terms(wallet, pool, executorFee, maxMinerFee), amountX, minShares)
    place(ctx, wallet, UTXO(contract, depositValue(amountX, executorFee), Seq(Token(pool.tokenY, amountY))))
  }

  /**
   * Moves `provision`'s ownership NFT into a redeem order. With `vaultBox`, the same transaction first
   * claims the provision's settled fees, which a fill would otherwise forfeit: the vault sits at input 0
   * and the provision at input 1, each finding its successor at the matching output, and the claim
   * leaves the NFT free to go into the order.
   */
  def redeem(ctx: BlockchainContext, wallet: NodeWallet, pool: LDLiquidityPool, provision: Provision,
             vaultBox: Option[InputUTXO], executorFee: Long, maxMinerFee: Long): DexPlan[LDOrderTx] = {
    val nft = Token(provision.ownerNFT, 1L)
    val orderOut = UTXO(LDOrderContracts.redeem(terms(wallet, pool, executorFee, maxMinerFee)), RETURNED, Seq(nft))
    vaultBox match {
      case None => place(ctx, wallet, orderOut)
      case Some(box) =>
        val v = LDFeeValue.fromBox(box, 0, ctx.getHeight)
        require(v.canClaim(provision.entryX, provision.entryY),
          s"provision ${provision.boxId} has nothing to settle against the vault")
        val (owedX, owedY) = v.owed(provision.shares, provision.entryX, provision.entryY)
        require(v.payableX >= owedX && v.payableY >= owedY,
          s"the vault can pay ${v.payableX} nanoERG and ${v.payableY} tokens of the $owedX and $owedY this provision is owed")
        val vaultOut = LithosDexTransactions.vaultUTXO(ctx, v, None, v.balanceX - owedX, v.balanceY - owedY, v.accX, v.accY)
        val provisionOut = LithosDexTransactions.provisionUTXO(ctx, provision.box.tokens.head.id, v.accX, v.accY,
          provision.ownerNFT, provision.shares, provision.value)
        funded(Math.addExact(RETURNED, FUNDING_HEADROOM), Seq(nft)) { funding =>
          val inputs = Seq(
            LithosDexTransactions.vaultInput(box, LDHelpers.VAULT_CLAIM, 1),
            LithosDexTransactions.provisionInput(ctx, provision.box, LDHelpers.PROV_CLAIM)) ++ funding
          val uTx = build(ctx, inputs, Seq(vaultOut, provisionOut, orderOut), wallet)
          DexUnsigned(uTx, signed => LDOrderTx(signed, InputUTXO(signed.getOutputsToSpend.get(2)), owedX, owedY,
            Some(signed.getOutputsToSpend.get(1).getId.toString)))
        }
    }
  }

  /**
   * Refunds `order` to this wallet through the order's owner key. The order sits at input 0, where no
   * order contract reads an execution, so its refund path is the one that runs. The network fee comes
   * out of the order when what remains still funds the refund box, and out of the wallet otherwise.
   */
  def cancel(ctx: BlockchainContext, wallet: NodeWallet, order: InputUTXO): DexPlan[LDCancelTx] =
    if (order.value - TX_FEE >= UTXO.MIN_CHANGE)
      DexPlan(0L, Seq.empty, _ => {
        val uTx = build(ctx, Seq(order), Seq(UTXO(wallet.contract, order.value - TX_FEE, order.tokens)), wallet)
        DexUnsigned(uTx, signed => LDCancelTx(signed, order.value, order.tokens, feeFromOrder = true))
      })
    else
      funded(FUNDING_HEADROOM) { funding =>
        val uTx = build(ctx, order +: funding, Seq(UTXO(wallet.contract, order.value, order.tokens)), wallet)
        DexUnsigned(uTx, signed => LDCancelTx(signed, order.value, order.tokens, feeFromOrder = false))
      }

  private def place(ctx: BlockchainContext, wallet: NodeWallet, orderOut: UTXO): DexPlan[LDOrderTx] =
    funded(Math.addExact(orderOut.value, FUNDING_HEADROOM), orderOut.tokens) { funding =>
      val uTx = build(ctx, funding, Seq(orderOut), wallet)
      DexUnsigned(uTx, signed => LDOrderTx(signed, InputUTXO(signed.getOutputsToSpend.get(0))))
    }
}
