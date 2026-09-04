package lfsm.contracts

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.Contract

/**
 * The contracts more than one component needs, so that each is compiled once.
 *
 * Their constants chain — Evaluation carries Payout's hash, Holding carries Evaluation's — so
 * building any of them separately risks a set whose halves disagree.
 */
final case class DeployedContracts(payout: Contract,
                                   eval: Contract,
                                   holding: HoldingScripts,
                                   gate: Contract,
                                   collateral: Contract)

object DeployedContracts {

  private var cache: Option[(String, DeployedContracts)] = None

  /**
   * Compiled once per network for the life of the JVM.
   *
   * Required rather than an optimisation: the sigma compiler mutates shared AST nodes, so compiling
   * one script twice in a run can throw.
   */
  def apply(ctx: BlockchainContext): DeployedContracts = synchronized {
    val network = ctx.getNetworkType.toString
    cache match {
      case Some((cached, set)) if cached == network => set
      case _ =>
        val payout = RollupContracts.mkPayoutContract(ctx)
        val eval = RollupContracts.mkEvalContract(
          ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, LFSMHelpers.getFPToken(ctx))
        val holding = RollupContracts.mkHoldingScripts(
          ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)
        val gate = CollateralContract.mkEmissionGateContract(ctx)
        val collateral = CollateralContract.mkMainnetCollatContract(
          ctx, LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)

        val set = DeployedContracts(payout, eval, holding, gate, collateral)
        cache = Some((network, set))
        set
    }
  }
}
