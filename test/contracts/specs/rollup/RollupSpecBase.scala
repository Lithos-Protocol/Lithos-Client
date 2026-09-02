package contracts.specs.rollup

import contracts.specs.harness.ContractSpecBase
import lfsm.LFSMHelpers
import lfsm.contracts.RollupContracts
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import scorex.utils.Longs
import sigma.Colls
import work.lithos.mutations.{Contract, Token, UTXO}

/**
 * Compiled once and shared. The sigma compiler mutates `_sourceContext` on shared AST nodes, so
 * compiling a script twice — or from two suites at once — throws "can only be set once".
 * `app/utils/Helpers.scala` works around the same thing with pre-compiled lazy vals.
 */
case class RollupSet(payout: Contract, eval: Contract, holding: Contract, holdingLogic: Contract)

object RollupSpecBase {
  private var cache: Option[RollupSet] = None

  def compiled(ctx: BlockchainContext, fpToken: ErgoId): RollupSet = synchronized {
    cache.getOrElse {
      val payout = RollupContracts.mkPayoutContract(ctx)
      val eval = RollupContracts.mkEvalContract(
        ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, fpToken)
      // mkHoldingContract returns the guard. The logic is compiled again here so specs can hand it
      // to context var 64; both must use identical constants or the guard rejects it.
      val holding = RollupContracts.mkHoldingContract(
        ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
      val logic = RollupContracts.mkHoldingLogicContract(
        ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
      val all = RollupSet(payout, eval, holding, logic)
      cache = Some(all)
      all
    }
  }
}

/** Adds the three rollup contracts and NISP-shaped helpers to [[ContractSpecBase]]. */
trait RollupSpecBase extends ContractSpecBase {

  protected val fpTokenId: ErgoId =
    ErgoId.create("20fa2bf23962cdf51b07722d6237c0c7b8a44f78856c0f7ec308dc1ef1a92a51")

  protected def payoutContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId).payout

  protected def evalContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId).eval

  /** The guard — what a holding box actually carries, and what the collateral contract pins. */
  protected def holdingContract(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId).holding

  /** The rules the guard runs out of context var 64. Never on a box. */
  protected def holdingLogic(ctx: BlockchainContext): Contract = RollupSpecBase.compiled(ctx, fpTokenId).holdingLogic

  /**
   * R7 for every box in the rollup chain: three Longs, whose meaning shifts by phase.
   * Holding and Evaluation read (period start, block height, bond total); Payout reads
   * (block reward, LIT reward, bond total).
   */
  protected def stateReg(first: Long, second: Long, bond: Long): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(Array(first, second, bond)), scalaLongType)

  /** The bond a submission of this score has to post, mirroring `bondForScore` in the contracts. */
  protected def bondFor(score: Long): Long =
    math.max(LFSMHelpers.MIN_ENTRY_BOND, score / LFSMHelpers.BOND_DIVISOR)

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
                          extraRegister: Option[ErgoValue[_]] = None,
                          blockOrLit: Long = 0L,
                          bond: Long = 0L): UTXO = {
    val base = Seq(
      tree.ergoValue,
      ErgoValue.of(miners),
      ErgoValue.of(BigInt(totalScore).bigInteger),
      stateReg(periodOrReward, blockOrLit, bond)
    )
    UTXO(contract, value, tokens, base ++ extraRegister.toSeq)
  }
}
