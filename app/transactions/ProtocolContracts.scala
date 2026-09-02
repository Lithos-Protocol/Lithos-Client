package transactions

import lfsm.LFSMHelpers
import lfsm.contracts.{CollateralContract, FraudProofContracts, RollupContracts}
import org.ergoplatform.appkit.{Address, BlockchainContext}
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigma.SigmaProp
import work.lithos.mutations.Contract

/**
 * The emission- and rollup-side contracts the client compiles, held together so they are built once.
 * `holdingLogic` is the script a holding spend hands to context variable 64; it is the instance whose
 * hash `holding` carries, so the two may not be sourced separately.
 */
case class CompiledContracts(holding: Contract,
                             holdingLogic: Contract,
                             gate: Contract,
                             collateral: Contract,
                             emission: Contract,
                             guard: Contract,
                             enforcer: Contract)

/**
 * The one place the protocol's contracts are compiled, cached for the life of the JVM.
 *
 * Not only an optimisation: the sigma compiler mutates `_sourceContext` on shared AST nodes, so
 * compiling the same script twice in one run can throw. It is also what keeps compilation off the
 * critical path of mining.
 */
object ProtocolContracts {

  private var compiled: Option[CompiledContracts] = None
  private var fpCompiled: Option[Seq[Contract]] = None

  def apply(ctx: BlockchainContext): CompiledContracts = synchronized {
    compiled.getOrElse {
      val payout = RollupContracts.mkPayoutContract(ctx)
      val eval = RollupContracts.mkEvalContract(
        ctx, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, LFSMHelpers.getFPToken(ctx))
      val holding = RollupContracts.mkHoldingScripts(ctx, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)

      val gate = CollateralContract.mkEmissionGateContract(ctx)
      val collateral = CollateralContract.mkMainnetCollatContract(
        ctx, LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val emission = CollateralContract.mkEmissionsContract(
        ctx, collateral.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val guard = CollateralContract.mkEmissionsGuardContract(
        ctx, LFSMHelpers.EMISSION_NFT, LFSMHelpers.EMCONFIG_NFT,
        collateral.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val enforcer = CollateralContract.mkCollateralEnforcerContract(ctx, LFSMHelpers.LIT_ID)

      val all = CompiledContracts(holding.guard, holding.logic, gate, collateral, emission, guard,
        enforcer)
      compiled = Some(all)
      all
    }
  }

  /**
   * The fraud proof contracts, in the order `Evaluator` depends on. Cached separately because they
   * are compiled on a different schedule to the emission set and by different callers.
   */
  def fraudProofs(ctx: BlockchainContext): Seq[Contract] = synchronized {
    fpCompiled.getOrElse {
      val all = FraudProofContracts.getFraudProofContracts(ctx)
      fpCompiled = Some(all)
      all
    }
  }

  /** How a lender is keyed in the emission box's active set. */
  def lenderEntry(prop: SigmaProp): Array[Byte] = Blake2b256.hash(prop.propBytes.toArray)

  def lenderEntry(address: Address): Array[Byte] = Contract.fromAddress(address).hashedPropBytes

  def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)
}
