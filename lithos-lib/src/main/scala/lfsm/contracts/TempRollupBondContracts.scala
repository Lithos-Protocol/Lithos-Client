package lfsm.contracts

import lfsm.{LFSMHelpers, ScriptGenerator}
import org.ergoplatform.appkit.{BlockchainContext, Constants, ConstantsBuilder, NetworkType}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, Mutator}

/**
 * Compilation entry points for the TEMP refundable-score-bond contract revision.
 *
 * The live builders remain on the existing contracts until the revised scripts have their own test
 * pass. Keeping this object separate also makes it impossible for an unfinished TEMP script to
 * silently change a configured mainnet address.
 */
object TempRollupBondContracts {
  final val MinEntryBond: Long = 2000000L
  final val BondDivisor: Long = 100L

  final case class Suite(payout: Contract,
                         evaluation: Contract,
                         holding: Contract,
                         fraudProofs: Seq[Contract])

  def bondForScore(score: Long): Long = {
    require(score > 0L, "score must be positive")
    math.max(MinEntryBond, score / BondDivisor)
  }

  private def bondConstants(builder: ConstantsBuilder): ConstantsBuilder = builder
    .item("CONST_MIN_ENTRY_BOND", MinEntryBond)
    .item("CONST_BOND_DIVISOR", BondDivisor)

  private def payoutConstants: Constants = bondConstants(ConstantsBuilder.create())
    .item("CONST_FEE_HASH", Colls.fromArray(Contract.FEE_720.hashedPropBytes))
    .build()

  def mkPayoutContract(ctx: BlockchainContext): Contract =
    Contract.fromErgoScript(ctx, payoutConstants, ScriptGenerator.mkRollupScript("TEMP_Payout"))

  def mkPayoutContract(networkType: NetworkType): Contract =
    Contract.fromErgoScript(networkType, payoutConstants,
      ScriptGenerator.mkRollupScript("TEMP_Payout"), Seq.empty[Mutator])

  def mkEvalContract(ctx: BlockchainContext,
                     periodLength: Long,
                     payoutBytes: Array[Byte],
                     fpToken: ErgoId): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("TEMP_Evaluation"))
  }

  def mkEvalContract(networkType: NetworkType,
                     periodLength: Long,
                     payoutBytes: Array[Byte],
                     fpToken: ErgoId): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_PAYOUT_PROPBYTES", Colls.fromArray(payoutBytes))
      .item("CONST_FP_ID", Colls.fromArray(fpToken.getBytes))
      .build()
    Contract.fromErgoScript(networkType, constants,
      ScriptGenerator.mkRollupScript("TEMP_Evaluation"), Seq.empty[Mutator])
  }

  def mkHoldingContract(ctx: BlockchainContext,
                        periodLength: Long,
                        evalBytes: Array[Byte]): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", Colls.fromArray(evalBytes))
      .build()
    Contract.fromErgoScript(ctx, constants, ScriptGenerator.mkRollupScript("TEMP_Holding"))
  }

  def mkHoldingContract(networkType: NetworkType,
                        periodLength: Long,
                        evalBytes: Array[Byte]): Contract = {
    val constants = bondConstants(ConstantsBuilder.create())
      .item("CONST_PERIOD_LENGTH", periodLength)
      .item("CONST_EVAL_PROPBYTES", Colls.fromArray(evalBytes))
      .build()
    Contract.fromErgoScript(networkType, constants,
      ScriptGenerator.mkRollupScript("TEMP_Holding"), Seq.empty[Mutator])
  }

  private def invalidFormatConstants: Constants = ConstantsBuilder.create()
    .item("CONST_TX_PROOF_MIN", LFSMHelpers.TX_PROOF_MIN)
    .item("CONST_TX_PROOF_MAX", LFSMHelpers.TX_PROOF_MAX)
    .item("CONST_TX_SIZE_MIN", LFSMHelpers.TX_SIZE_MIN)
    .item("CONST_COLLAT_BOX_MIN", LFSMHelpers.COLLAT_BOX_MIN)
    .item("CONST_NUM_LVLS_MAX", LFSMHelpers.NUM_LVLS_MAX)
    .build()

  /**
   * The seven fraud proofs currently wired into Evaluator, followed by the two initial proofs being
   * completed alongside this revision. The FP control box is deliberately not changed here.
   */
  def mkFraudProofContracts(networkType: NetworkType,
                            holding: Contract): Seq[Contract] = {
    def compile(name: String, constants: Constants = ConstantsBuilder.empty()): Contract =
      Contract.fromErgoScript(networkType, constants,
        ScriptGenerator.mkFraudProofScript(name), Seq.empty[Mutator])

    val current = Seq(
      compile("TEMP_FP_InvalidFormat", invalidFormatConstants),
      compile("TEMP_FP_MalformedGE"),
      compile("TEMP_FP_NotInWindow"),
      compile("TEMP_FP_NonUniqueHeaders"),
      compile("TEMP_FP_IncorrectN"),
      compile("TEMP_FP_InvalidDiff"),
      compile("TEMP_FP_TransactionNotIncluded")
    )

    val nonMatchingConstants = ConstantsBuilder.create()
      .item("CONST_MD_ID", Colls.fromArray(LFSMHelpers.getMDToken(networkType).getBytes))
      .build()
    val gate = CollateralContract.mkEmissionGateContract(networkType)
    val collateral = CollateralContract.mkMainnetCollatContract(networkType,
      LFSMHelpers.EMCONFIG_NFT, gate.hashedPropBytes, LFSMHelpers.LIT_ID)
    val malformedTransactionConstants = ConstantsBuilder.create()
      .item("CONST_COLLATERAL_PROPBYTES", Colls.fromArray(collateral.hashedPropBytes))
      .item("CONST_HOLDING_PROPBYTES", Colls.fromArray(holding.propBytes))
      .build()

    current ++ Seq(
      compile("TEMP_FP_NonMatchingCommitment", nonMatchingConstants),
      compile("FP_MalformedTransaction", malformedTransactionConstants)
    )
  }

  def compileSuite(networkType: NetworkType): Suite = {
    val payout = mkPayoutContract(networkType)
    val evaluation = mkEvalContract(networkType, LFSMHelpers.EVAL_PERIOD,
      payout.hashedPropBytes, LFSMHelpers.getFPToken(networkType))
    val holding = mkHoldingContract(networkType, LFSMHelpers.HOLDING_PERIOD,
      evaluation.hashedPropBytes)
    Suite(payout, evaluation, holding, mkFraudProofContracts(networkType, holding))
  }

  def main(args: Array[String]): Unit = {
    val suite = compileSuite(NetworkType.TESTNET)
    val contracts = Seq("payout" -> suite.payout, "evaluation" -> suite.evaluation,
      "holding" -> suite.holding) ++ suite.fraudProofs.zipWithIndex.map {
      case (contract, index) => s"fraud-proof-$index" -> contract
    }
    contracts.foreach { case (name, contract) =>
      println(s"$name ${contract.ergoTreeHex.length / 2} bytes")
    }
  }
}
