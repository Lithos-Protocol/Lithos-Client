package contracts.specs.rollup

import contracts.specs.harness.ContractSpecBase
import lfsm.LFSMHelpers
import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import scorex.utils.Longs
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * Compiled once and shared. The sigma compiler mutates `_sourceContext` on shared AST nodes, so
 * compiling a script twice — or from two suites at once — throws "can only be set once".
 * `app/utils/Helpers.scala` works around the same thing with pre-compiled lazy vals.
 */
object RollupSpecBase {
  private var cache: Option[(Contract, Contract, Contract)] = None

  def compiled(ctx: BlockchainContext, fpToken: ErgoId): (Contract, Contract, Contract) = synchronized {
    cache.getOrElse {
      val payout = RollupContracts.mkPayoutContract(ctx)
      val eval = RollupContracts.mkEvalContract(
        ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, fpToken)
      val holding = RollupContracts.mkHoldingContract(
        ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
      val all = (payout, eval, holding)
      cache = Some(all)
      all
    }
  }
}

/** Adds the three rollup contracts and NISP-shaped helpers to [[ContractSpecBase]]. */
trait RollupSpecBase extends ContractSpecBase {

  protected val fpTokenId: ErgoId =
    ErgoId.create("20fa2bf23962cdf51b07722d6237c0c7b8a44f78856c0f7ec308dc1ef1a92a51")

  protected def payoutContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId)._1

  protected def evalContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId)._2

  protected def holdingContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId)._3

  /** A NISP is `[score: 8 BE bytes][payload]`; payload content is irrelevant to the rollup contracts. */
  protected def nispBytes(score: Long, totalSize: Int): Array[Byte] = {
    require(totalSize >= 8, "a NISP is at least its 8 score bytes")
    Longs.toByteArray(score) ++ Array.fill(totalSize - 8)(7.toByte)
  }

  /** Size that satisfies Holding's CONST_NISP_MIN of 7948. */
  protected val realisticNispSize: Int = 8000

  protected def rollupBox(contract: Contract,
                          tree: Tree,
                          miners: Int,
                          totalScore: Long,
                          periodOrReward: Long,
                          value: Long = boxValue,
                          tokens: Seq[Token] = Seq.empty[Token],
                          extraRegister: Option[ErgoValue[_]] = None): UTXO = {
    val base = Seq(
      tree.ergoValue,
      ErgoValue.of(miners),
      ErgoValue.of(BigInt(totalScore).bigInteger),
      ErgoValue.of(periodOrReward)
    )
    UTXO(contract, value, tokens, base ++ extraRegister.toSeq)
  }
}
