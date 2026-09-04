package transactions

import lfsm.LFSMHelpers
import lfsm.contracts.FraudProofContracts.FraudProofSet
import lfsm.contracts.{CollateralContract, DictionaryContracts, FraudProofContracts, HoldingScripts, RollupContracts}
import org.ergoplatform.appkit.{Address, BlockchainContext, NetworkType}
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigma.SigmaProp
import work.lithos.mutations.Contract

/**
 * Every contract this client compiles, for one network, built together.
 *
 * Their constants chain — Evaluation carries Payout's hash, Holding carries Evaluation's,
 * `FP_MalformedGenesis` carries the collateral and holding trees whole — so sourcing them apart lets
 * two callers hold sets whose halves disagree.
 */
case class CompiledContracts(payout: Contract,
                             eval: Contract,
                             holdingScripts: HoldingScripts,
                             gate: Contract,
                             collateral: Contract,
                             emission: Contract,
                             guard: Contract,
                             enforcer: Contract,
                             minerDictionary: Contract,
                             minerData: Contract,
                             minerDataLogic: Contract,
                             fraudProofs: FraudProofSet) {

  /** What a holding box carries. */
  def holding: Contract = holdingScripts.guard

  /** The rules a holding spend hands to context variable 64, whose hash the guard checks first. */
  def holdingLogic: Contract = holdingScripts.logic
}

/**
 * The one place the protocol's contracts are compiled, cached per network for the life of the JVM.
 *
 * Not only an optimisation: the sigma compiler mutates `_sourceContext` on shared AST nodes, so
 * compiling the same script twice in one run can throw. It is also what keeps compilation off the
 * critical path of mining.
 *
 * Keyed by network rather than by context, because synchronization builds its constants before any
 * context exists. Both `Contract.fromErgoScript` overloads are one code path, so either caller gets
 * the same contracts.
 */
object ProtocolContracts {

  private var compiled: Map[NetworkType, CompiledContracts] = Map.empty

  def apply(ctx: BlockchainContext): CompiledContracts = forNetwork(ctx.getNetworkType)

  def forNetwork(network: NetworkType): CompiledContracts = synchronized {
    compiled.getOrElse(network, {
      val payout = RollupContracts.mkPayoutContract(network)
      val eval = RollupContracts.mkEvalContract(
        network, LFSMHelpers.EVAL_PERIOD, payout.hashedPropBytes, LFSMHelpers.getFPToken(network))
      val holdingScripts = RollupContracts.mkHoldingScripts(
        network, LFSMHelpers.HOLDING_PERIOD, eval.hashedPropBytes)

      val gate = CollateralContract.mkEmissionGateContract(network)
      val collateral = CollateralContract.mkMainnetCollatContract(
        network, LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val emission = CollateralContract.mkEmissionsContract(
        network, collateral.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val guard = CollateralContract.mkEmissionsGuardContract(
        network, LFSMHelpers.EMISSION_NFT, LFSMHelpers.EMCONFIG_NFT,
        collateral.hashedPropBytes, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
      val enforcer = CollateralContract.mkCollateralEnforcerContract(network, LFSMHelpers.LIT_ID)

      // MinerData's guard and logic, compiled once and passed on by hash. The dictionary and
      // FP_NonMatchingCommitment both inject that hash, and each would otherwise compile its own.
      val mdToken = LFSMHelpers.getMDToken(network)
      val minerDataLogic = DictionaryContracts.mkMinerDataLogicContract(network, mdToken)
      val minerData = DictionaryContracts.mkMinerDataGuard(network, minerDataLogic)
      val minerDataHash = minerData.hashedPropBytes

      val all = CompiledContracts(payout, eval, holdingScripts, gate, collateral, emission, guard,
        enforcer,
        DictionaryContracts.mkMinerDictionaryContract(network, mdToken, minerDataHash),
        minerData,
        minerDataLogic,
        FraudProofContracts.fraudProofSet(network, collateral, holdingScripts.guard, minerDataHash))
      compiled += network -> all
      all
    })
  }

  /** How a lender is keyed in the emission box's active set. */
  def lenderEntry(prop: SigmaProp): Array[Byte] = Blake2b256.hash(prop.propBytes.toArray)

  def lenderEntry(address: Address): Array[Byte] = Contract.fromAddress(address).hashedPropBytes

  def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)
}
