package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, Constants, ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import sigma.data.ProveDlog
import work.lithos.mutations.{Contract, Mutator}

/**
 * A holding box's guard and the logic it executes out of context variable 64. They are produced
 * together because the guard carries the hash of these exact logic bytes: a builder that attaches
 * any other instance is refused before the logic runs at all.
 */
final case class HoldingScripts(guard: Contract, logic: Contract)

object RollupContracts {

  /**
   * The drain path refuses any transaction carrying a miner-fee output, which is what leaves a stuck
   * payout box claimable only by a block producer or someone paying one.
   *
   * `feeProposition` reads the miner key out of the context rather than baking it in, and takes no
   * network prefix, so the fee ergo tree — and therefore this hash — is a single global constant.
   * It is derived here rather than hard-coded so it cannot drift from the fee output the client's own
   * builders emit.
   */
  /**
   * The refundable per-NISP bond. All three rollup contracts price it with the same formula —
   * Holding collects it, Evaluation slashes it to the prover, Payout refunds what is left — so the
   * two constants are injected into all three and a change to either moves every rollup address.
   */
  private def bondConstants(builder: ConstantsBuilder): ConstantsBuilder = builder
    .item("CONST_MIN_ENTRY_BOND", LFSMHelpers.MIN_ENTRY_BOND)
    .item("CONST_BOND_DIVISOR", LFSMHelpers.BOND_DIVISOR)

  private def payoutConstants: Constants = bondConstants(ConstantsBuilder.create())
    .item("CONST_FEE_HASH", Colls.fromArray(Contract.FEE_720.hashedPropBytes))
    .build()

  def mkPayoutContract(ctx: BlockchainContext): Contract = {
    Contract.fromErgoScript(ctx, payoutConstants, ScriptGenerator.mkRollupScript("Payout"))
  }

  def mkPayoutContract(networkType: NetworkType): Contract = {
    Contract.fromErgoScript(networkType, payoutConstants, ScriptGenerator.mkRollupScript("Payout"), Seq.empty[Mutator])
  }

  def mkEvalContract(ctx: BlockchainContext, periodLength: Long, payoutBytes: Array[Byte], fpToken: ErgoId): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("Evaluation"))
  }

  def mkEvalContract(networkType: NetworkType, periodLength: Long, payoutBytes: Array[Byte], fpToken: ErgoId): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

    Contract.fromErgoScript(networkType, constants, ScriptGenerator.mkRollupScript("Evaluation"), Seq.empty[Mutator])
  }

  /**
   * The holding rules. Never sits on a box: rides in context var 64 and is executed by the guard,
   * which checks its hash first. Bytes here are free, bytes in the guard are not.
   */
  private def holdingLogicConstants(periodLength: Long, evalBytes: Array[Byte]): Constants =
    bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", Colls.fromArray(evalBytes))
      //.item("CONST_CMD_ID, cmdToken")
      .build()

  def mkHoldingLogicContract(ctx: BlockchainContext, periodLength: Long, evalBytes: Array[Byte]): Contract =
    Contract.fromErgoScript(ctx, holdingLogicConstants(periodLength, evalBytes),
      ScriptGenerator.mkRollupScript("Holding_Logic"))

  def mkHoldingLogicContract(networkType: NetworkType, periodLength: Long, evalBytes: Array[Byte]): Contract =
    Contract.fromErgoScript(networkType, holdingLogicConstants(periodLength, evalBytes),
      ScriptGenerator.mkRollupScript("Holding_Logic"), Seq.empty[Mutator])

  private def guardConstants(logic: Contract): Constants = ConstantsBuilder.create()
    // Value bytes, not prop bytes: the guard hashes what it executes.
    .item("CONST_HOLDING_LOGIC_HASH", Colls.fromArray(logic.hashedValueBytes))
    .build()

  /**
   * The script a holding box carries, together with the logic whose hash is injected into it. Both
   * come from one call so a builder cannot pin one instance and attach another. Compiles two
   * scripts, so callers should cache.
   */
  def mkHoldingScripts(ctx: BlockchainContext, periodLength: Long, evalBytes: Array[Byte]): HoldingScripts = {
    val logic = mkHoldingLogicContract(ctx, periodLength, evalBytes)
    HoldingScripts(
      Contract.fromErgoScript(ctx, guardConstants(logic), ScriptGenerator.mkRollupScript("Holding_Guard")),
      logic)
  }

  def mkHoldingScripts(networkType: NetworkType, periodLength: Long, evalBytes: Array[Byte]): HoldingScripts = {
    val logic = mkHoldingLogicContract(networkType, periodLength, evalBytes)
    HoldingScripts(
      Contract.fromErgoScript(networkType, guardConstants(logic),
        ScriptGenerator.mkRollupScript("Holding_Guard"), Seq.empty[Mutator]),
      logic)
  }

  def mkHoldingContract(ctx: BlockchainContext, periodLength: Long, evalBytes: Array[Byte]): Contract =
    mkHoldingScripts(ctx, periodLength, evalBytes).guard

  def mkHoldingContract(networkType: NetworkType, periodLength: Long, evalBytes: Array[Byte]): Contract =
    mkHoldingScripts(networkType, periodLength, evalBytes).guard

  def mkFPControlTestnetContract(ctx: BlockchainContext, proveDlog: ProveDlog): Contract = {
    val constants = ConstantsBuilder.create()
      .item("CONST_TESTNET_PK", proveDlog)
      .build()

    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("FP_Control_Testnet"))
  }

}
